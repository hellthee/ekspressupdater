package org.hp.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP transfer that assumes the network is bad.
 *
 * <ul>
 *   <li>Every file is downloaded into a <code>.part</code> file in a cache directory
 *       and resumed with a Range request, across retries <em>and</em> across restarts
 *       of the application.</li>
 *   <li>Failures are retried with exponential backoff and jitter.</li>
 *   <li>Nothing is accepted until its SHA-256 matches, so a truncated or corrupted
 *       body can never reach the installation.</li>
 * </ul>
 */
public final class Downloader {

    /**
     * Thrown when the connection is too slow to be worth making the user wait. Not a
     * failure: what has been downloaded is kept and the update continues next time.
     */
    public static final class TooSlow extends IOException {

        private static final long serialVersionUID = 1L;

        public final long bytesPerSec;

        TooSlow(long bytesPerSec) {
            super("download speed " + (bytesPerSec / 1024) + " KB/s");
            this.bytesPerSec = bytesPerSec;
        }
    }

    /**
     * The server answered, but with an error that will not go away by asking again: the
     * file is not there (404), or access is refused. Retrying only keeps the user waiting.
     */
    public static final class Permanent extends IOException {

        private static final long serialVersionUID = 1L;

        public final int status;

        Permanent(int status, String url) {
            super("HTTP " + status + (status == 404 ? " (not found on the server)" : "") + " for " + url);
            this.status = status;
        }
    }

    /** 4xx answers are final, except a request timeout or "too many requests". */
    static IOException httpError(int code, String url) {
        if (code >= 400 && code < 500 && code != 408 && code != 429) {
            return new Permanent(code, url);
        }
        return new IOException("HTTP " + code + " for " + url);
    }

    /**
     * The server sent the whole file twice and both times it was not the file the release
     * describes: asking this address again will not help.
     */
    public static final class WrongContent extends IOException {
        private static final long serialVersionUID = 1L;

        WrongContent(String message) {
            super(message);
        }
    }

    /** Thrown when the user cancelled; never treated as an error. */
    public static final class Cancelled extends IOException {
        private static final long serialVersionUID = 1L;

        public Cancelled() {
            super("cancelled");
        }
    }

    /** Receives the absolute number of verified bytes present for the current file. */
    public interface BytesSink {
        void bytes(long absoluteBytesInFile);
    }

    /** A release description is a few KB; anything this big is not one. */
    static final int MAX_SMALL_FETCH = 4 * 1024 * 1024;
    /** However slowly a server trickles it, app.txt must arrive within this time. */
    static final long SMALL_FETCH_DEADLINE_MS = 60000L;

    private final UpdateConfig cfg;
    private volatile UpdateListener listener;
    private final Random random = new Random();

    public Downloader(UpdateConfig cfg, UpdateListener listener) {
        this.cfg = cfg;
        setListener(listener);
    }

    public void setListener(UpdateListener l) {
        this.listener = l == null ? UpdateListener.NOOP : l;
    }

    // -------------------------------------------------------------- small GETs

    /** Fetches a small resource (the manifest) with the same retry policy. */
    public byte[] fetch(String url, AtomicBoolean cancel) throws IOException {
        return fetch(url, cancel, cfg.maxAttemptsPerFile());
    }

    /** Fetches a small resource, giving up after {@code attempts} tries. */
    public byte[] fetch(String url, AtomicBoolean cancel, int attempts) throws IOException {
        return fetch(url, cancel, attempts, cfg.connectTimeoutMs(), cfg.readTimeoutMs());
    }

    /** As above, with timeouts for this call only. */
    public byte[] fetch(String url, AtomicBoolean cancel, int attempts,
            int connectTimeoutMs, int readTimeoutMs) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            checkCancel(cancel);
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                conn = open(url, -1, true, connectTimeoutMs, readTimeoutMs);
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw httpError(code, url);
                }
                long declared = conn.getContentLengthLong();
                if (declared > MAX_SMALL_FETCH) {
                    throw new Permanent(413, url); // far too big for a release description
                }
                in = conn.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long deadline = System.currentTimeMillis() + SMALL_FETCH_DEADLINE_MS;
                int n;
                while ((n = in.read(buf)) > 0) {
                    checkCancel(cancel);
                    out.write(buf, 0, n);
                    if (out.size() > MAX_SMALL_FETCH) {
                        throw new Permanent(413, url);
                    }
                    if (System.currentTimeMillis() > deadline) {
                        throw new IOException("The server is sending " + url + " far too slowly");
                    }
                }
                // A dropped connection ends the stream early and looks like a clean EOF.
                // Without this check a truncated app.txt would be treated as the whole
                // release, and every file below the cut would be deleted as "removed".
                if (declared >= 0 && out.size() != declared) {
                    throw new IOException("Truncated response for " + url + ": got "
                            + out.size() + " of " + declared + " bytes");
                }
                return out.toByteArray();
            } catch (Cancelled c) {
                throw c;
            } catch (Permanent p) {
                throw p;
            } catch (WrongContent w) {
                throw w;
            } catch (IOException e) {
                last = e;
                listener.onWarning("Attempt " + attempt + " failed for " + url + ": " + e);
                if (attempt < attempts) {
                    backoff(attempt, cancel);
                }
            } finally {
                Io.closeQuietly(in);
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw last != null ? last : new IOException("Could not fetch " + url);
    }

    // --------------------------------------------------------- resumable files

    /**
     * Downloads {@code url} into {@code partFile} and, once the digest matches, moves
     * it to {@code target}. A partially downloaded file is left in place on failure so
     * the next attempt resumes instead of starting over.
     */
    public void download(String url, Path partFile, Path target, long expectedSize,
            String sha256, BytesSink sink, AtomicBoolean cancel) throws IOException {

        Io.ensureParent(partFile);
        Io.ensureParent(target);

        IOException last = null;
        // Two separate budgets. "Stalled" counts attempts that moved no bytes at all, and
        // is what gives up on a dead link. An attempt that downloaded something, even a
        // few KB, resets it: a slow, constantly dropping connection still finishes
        // instead of running out of retries while it is making progress.
        int stalled = 0;
        int attempt = 0;
        int mismatches = 0;
        long best = Files.isRegularFile(partFile) ? sizeOf(partFile) : 0L;
        while (stalled < cfg.maxAttemptsPerFile() && attempt < cfg.maxTotalAttemptsPerFile()) {
            attempt++;
            checkCancel(cancel);
            try {
                long have = Files.isRegularFile(partFile) ? Files.size(partFile) : 0L;
                if (have > expectedSize && expectedSize >= 0) {
                    // stale or corrupt leftover, start clean
                    Io.deleteQuietly(partFile);
                    have = 0;
                }
                if (have < expectedSize || expectedSize < 0) {
                    have = transfer(url, partFile, have, expectedSize, sink, cancel);
                }
                if (sink != null) {
                    sink.bytes(have);
                }
                String actual = Io.sha256(partFile);
                if (!Io.hashEquals(actual, sha256)) {
                    Io.deleteQuietly(partFile);
                    if (sink != null) {
                        sink.bytes(0);
                    }
                    String m = "Checksum mismatch for " + url
                            + " (expected " + shorten(sha256) + ", got " + shorten(actual) + ")";
                    if (++mismatches >= 2) {
                        throw new WrongContent(m); // the same wrong file again: the server has it wrong
                    }
                    throw new IOException(m);
                }
                Io.moveAtomic(partFile, target);
                return;
            } catch (Cancelled c) {
                throw c;
            } catch (TooSlow slow) {
                throw slow;
            } catch (Permanent p) {
                throw p;
            } catch (WrongContent w) {
                throw w; // the same wrong file twice: let the caller try the other address
            } catch (IOException e) {
                last = e;
                long now = Files.isRegularFile(partFile) ? sizeOf(partFile) : 0L;
                if (now > best) {
                    best = now;
                    stalled = 0; // progress was made, so keep going
                } else {
                    stalled++;
                }
                listener.onWarning("Attempt " + attempt + " for " + url + ": " + e.getMessage()
                        + (stalled == 0 ? " (resuming)" : " (no progress, " + stalled + "/"
                                + cfg.maxAttemptsPerFile() + ")"));
                backoff(stalled + 1, cancel);
            }
        }
        throw last != null ? last : new IOException("Could not download " + url);
    }

    /** One transfer attempt. Returns the number of bytes now in the part file. */
    private long transfer(String url, Path partFile, long startAt, long expectedSize,
            BytesSink sink, AtomicBoolean cancel) throws IOException {

        HttpURLConnection conn = open(url, startAt, false);
        InputStream in = null;
        java.io.OutputStream out = null;
        try {
            int code = conn.getResponseCode();
            long written = startAt;

            if (code == 416) { // range unsatisfiable: local part file is wrong
                Io.deleteQuietly(partFile);
                throw new IOException("Server rejected resume range, restarting file");
            }
            if (code == 206) {
                String range = conn.getHeaderField("Content-Range");
                if (range != null && !range.trim().toLowerCase().startsWith("bytes " + startAt + "-")) {
                    // The server sent another part of the file than the one asked for.
                    Io.deleteQuietly(partFile);
                    throw new IOException("Unexpected range from the server (" + range
                            + "), restarting file");
                }
                out = Files.newOutputStream(partFile, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } else if (code == 200) {
                // Server ignored the Range header: restart from zero.
                written = 0;
                if (sink != null) {
                    sink.bytes(0);
                }
                out = Files.newOutputStream(partFile, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                throw httpError(code, url);
            }

            in = conn.getInputStream();
            byte[] buf = new byte[1 << 16];
            long lastReport = 0;
            long transferStartMs = System.currentTimeMillis();
            long bytesThisAttempt = 0;
            long minRate = cfg.minDownloadKbps() * 1024L;
            long graceMs = cfg.slowGraceSec() * 1000L;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel.get()) {
                    out.flush();
                    throw new Cancelled();
                }
                out.write(buf, 0, n);
                written += n;
                bytesThisAttempt += n;

                // After a fair chance to get going, judge the speed. A connection this
                // slow will not finish in any reasonable time, and the user has a working
                // version waiting: stop, keep what we have, and try again next time.
                if (minRate > 0) {
                    long elapsedMs = System.currentTimeMillis() - transferStartMs;
                    if (elapsedMs > graceMs) {
                        long rate = bytesThisAttempt * 1000L / elapsedMs;
                        if (rate < minRate) {
                            out.flush();
                            throw new TooSlow(rate);
                        }
                    }
                }
                if (sink != null && written - lastReport > (1 << 18)) {
                    sink.bytes(written);
                    lastReport = written;
                }
            }
            out.flush();
            if (expectedSize >= 0 && written < expectedSize) {
                throw new IOException("Connection closed early: " + written + "/" + expectedSize + " bytes");
            }
            return written;
        } finally {
            Io.closeQuietly(out);
            Io.closeQuietly(in);
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------ helpers

    private HttpURLConnection open(String url, long rangeStart, boolean noCache) throws IOException {
        return open(url, rangeStart, noCache, cfg.connectTimeoutMs(), cfg.readTimeoutMs());
    }

    private HttpURLConnection open(String url, long rangeStart, boolean noCache,
            int connectTimeoutMs, int readTimeoutMs) throws IOException {
        String current = url;
        for (int hop = 0; hop <= cfg.maxRedirects(); hop++) {
            HttpURLConnection conn = connect(current);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setInstanceFollowRedirects(false); // handled here so http -> https works
            conn.setRequestProperty("User-Agent", cfg.userAgent());
            conn.setRequestProperty("Accept-Encoding", "identity"); // keep byte ranges meaningful
            if (rangeStart > 0) {
                conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            }
            if (noCache) {
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("Pragma", "no-cache");
            }
            int code;
            try {
                code = conn.getResponseCode();
            } catch (RuntimeException e) {
                conn.disconnect();
                throw new IOException("Bad answer from " + current + ": " + e, e);
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) {
                    throw new IOException("Redirect without Location for " + current);
                }
                current = new URL(new URL(current), location).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects for " + url);
    }

    /** Opens an HTTP or HTTPS connection; any other kind of address is refused. */
    private static HttpURLConnection connect(String url) throws IOException {
        try {
            URL u = new URL(url);
            String protocol = u.getProtocol().toLowerCase();
            if (!protocol.equals("http") && !protocol.equals("https")) {
                throw new IOException("Refusing a non-HTTP address: " + url);
            }
            java.net.URLConnection c = u.openConnection();
            if (!(c instanceof HttpURLConnection)) {
                throw new IOException("Refusing a non-HTTP address: " + url);
            }
            return (HttpURLConnection) c;
        } catch (RuntimeException e) {
            throw new IOException("Unusable address " + url + ": " + e, e);
        }
    }

    private void backoff(int attempt, AtomicBoolean cancel) throws IOException {
        checkCancel(cancel);
        long delay = Math.min(cfg.retryMaxDelayMs(),
                (long) (cfg.retryBaseDelayMs() * Math.pow(2, attempt - 1)));
        delay += random.nextInt(400);
        long deadline = System.currentTimeMillis() + delay;
        while (System.currentTimeMillis() < deadline) {
            checkCancel(cancel);
            Io.sleep(200);
        }
    }

    private static void checkCancel(AtomicBoolean cancel) throws IOException {
        if (cancel != null && cancel.get()) {
            throw new Cancelled();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("interrupted");
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String shorten(String sha) {
        return sha == null ? "null" : sha.substring(0, Math.min(12, sha.length()));
    }

    /** Convenience: digest bytes already in memory. */
    public static String sha256(byte[] data) {
        MessageDigest md = Io.sha256Digest();
        md.update(data);
        return Io.hex(md.digest());
    }
}
