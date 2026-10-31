package org.hp.publish;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.hp.update.Io;
import org.hp.update.UpdateManager;
import org.hp.update.UpdateManifest;

/**
 * Puts a release built by the publisher onto the server, in the order that keeps
 * customers safe:
 *
 * <ol>
 * <li>Every file goes up under a temporary {@code .part} name and is renamed only once it
 *     is complete, so a file under its real name is always whole. An interrupted upload
 *     continues where it stopped.</li>
 * <li>Files already on the server are not sent again.</li>
 * <li>Before anyone is switched over, every file of the release is checked at the
 *     download URL, exactly as customers' PCs will fetch it, and new files are downloaded
 *     back and their checksums compared. A wrong folder or missing permission stops the
 *     upload here, with customers still on the previous release.</li>
 * <li>{@code app.txt} goes last. That single step switches every customer to the release.</li>
 * </ol>
 *
 * <p>A copy of each release's {@code app.txt} is kept in {@code releases/}, which is
 * what "Clean up server" uses to know which files are still needed.</p>
 */
public final class Uploader {

    /** Progress messages. */
    public interface Log {
        void line(String text);
    }

    /** Everything the uploader needs from whoever runs it. */
    public interface Listener extends Log {
        void progress(long done, long total, String file);

        /** The server's TLS certificate is not trusted yet. True: trust and remember it. */
        boolean trustCertificate(String server, String details, String fingerprint, boolean changed);

        /** The SSH server's key is not known yet, or changed. True: trust and remember it. */
        boolean trustHostKey(String server, String type, String fingerprint, boolean changed);

        /** A yes/no question before something unusual. True: go ahead. */
        boolean confirm(String title, String question);
    }

    /** Held in memory only, never saved. */
    public static final class Secrets {
        public char[] password;
        public char[] passphrase;
    }

    /** What an upload did. */
    public static final class Report {
        public String version;
        public int files;
        public int uploaded;
        public long uploadedBytes;
        public int alreadyThere;
        /** Of {@link #uploaded}: second copies in the old layout, for older updaters. */
        public int oldCopies;

        /** "47 file(s) (42.0 MB) plus 47 copies for older updaters" */
        public String uploadedText() {
            int files = uploaded - oldCopies;
            if (oldCopies == 0) {
                return files + " file(s) uploaded (" + UpdateManager.humanBytes(uploadedBytes) + ")";
            }
            return files + " file(s) uploaded, plus " + oldCopies
                    + (oldCopies == 1 ? " copy" : " copies") + " for older updaters ("
                    + UpdateManager.humanBytes(uploadedBytes) + " in all)";
        }

        public boolean urlChecked;
        public long millis;
        public final List<String> warnings = new ArrayList<String>();
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_FAILURES = 5;

    private final ServerProfile profile;
    private final Secrets secrets;
    private final Listener listener;
    private final AtomicBoolean cancel;
    private volatile RemoteFs fs;
    private volatile Connecting connecting;
    private final Http http;
    private String root;
    private final Set<String> madeDirs = new HashSet<String>();

    public Uploader(ServerProfile profile, Secrets secrets, Listener listener, AtomicBoolean cancel) {
        this.profile = profile;
        this.secrets = secrets == null ? new Secrets() : secrets;
        this.listener = listener;
        this.cancel = cancel == null ? new AtomicBoolean() : cancel;
        this.http = new Http(this.cancel);
    }

    // ------------------------------------------------------------------ connecting

    private RemoteFs open() throws IOException {
        String host = profile.host.trim();
        int port = profile.port();
        String user = profile.user.trim();
        for (int attempt = 0; attempt < 3; attempt++) {
            Connecting c = new Connecting();
            connecting = c;
            checkCancel();
            try {
                switch (profile.protocol) {
                    case SFTP:
                        return openSftp(host, port, user, c);
                    case FTPS:
                        return FtpFs.connect(host, port, true, user, secrets.password, listener, c);
                    default:
                        return FtpFs.connect(host, port, false, user, secrets.password, listener, c);
                }
            } catch (FtpFs.TrustNeeded t) {
                Trust.UntrustedCertificate u = t.certificate;
                String details = "Server  : " + host + ":" + port + "\n"
                        + "Problem : " + u.reason + "\n\n" + Trust.describe(u.certificate);
                if (!listener.trustCertificate(host + ":" + port, details,
                        Trust.certFingerprint(u.certificate), u.changed)) {
                    throw new IOException("Not connected: the server's certificate was not trusted.");
                }
                Trust.pin(host, port, u.certificate);
                listener.line("Certificate remembered for " + host + ":" + port);
            } catch (SftpFs.HostKeyNeeded k) {
                if (!listener.trustHostKey(k.knownHostsName, k.type, k.fingerprint, k.changed)) {
                    throw new IOException("Not connected: the server's host key was not trusted.");
                }
                SftpFs.trust(k);
                listener.line("Host key remembered for " + k.knownHostsName);
            }
        }
        throw new IOException("Could not make a trusted connection to " + host);
    }

    private RemoteFs openSftp(String host, int port, String user, Connecting c) throws IOException {
        try {
            String key = profile.keyFile == null ? "" : profile.keyFile.trim();
            return SftpFs.connect(host, port, user, secrets.password,
                    key.isEmpty() ? null : java.nio.file.Paths.get(key), secrets.passphrase,
                    listener, c);
        } catch (NoClassDefFoundError e) {
            throw new IOException("SFTP needs jsch.jar in the same folder as publisher.jar.");
        }
    }

    private void connect() throws IOException {
        listener.line("Connecting to " + profile.host.trim() + ":" + profile.port() + " ...");
        fs = open();
        listener.line("Connected: " + fs.describe());
        root = fs.absolute(profile.remoteDir);
        madeDirs.clear();
    }

    /**
     * Stops whatever is running, from any thread: the connection is closed at once, so even
     * a transfer stuck in the network ends now. Files already complete stay on the server.
     */
    public void abort() {
        cancel.set(true);
        Connecting c = connecting;
        if (c != null) {
            c.abort();
        }
        RemoteFs f = fs;
        if (f != null) {
            f.abort();
        }
        http.abort();
    }

    /** True once Cancel was pressed: whatever error follows is just the cancellation. */
    public boolean cancelled() {
        return cancel.get();
    }

    public void closeQuietly() {
        if (fs != null) {
            try {
                fs.close();
            } catch (IOException ignored) {
                // leaving anyway
            }
            fs = null;
        }
    }

    private void checkCancel() throws IOException {
        if (cancel.get()) {
            throw new RemoteFs.Cancelled();
        }
    }

    private void mkdirsFor(String remoteFile) throws IOException {
        String dir = remoteFile.substring(0, remoteFile.lastIndexOf('/'));
        if (dir.isEmpty() || madeDirs.contains(dir)) {
            return;
        }
        fs.mkdirs(dir);
        madeDirs.add(dir);
    }

    // ------------------------------------------------------------------ test

    /**
     * Logs in, makes sure the folder exists and is writable, and checks that the download
     * URL really serves that folder: the mistake that otherwise only shows when customers
     * cannot update.
     */
    public void testConnection(String appBase) throws IOException {
        try {
            connect();
            fs.mkdirs(root);
            listener.line("Folder on the server: " + root);
            probe(appBase);
        } finally {
            closeQuietly();
        }
    }

    /**
     * Writes a small file with random content and, when the URL check is on, fetches it
     * through the download URL. Proves in seconds that the login can write here and that
     * this folder is what customers download from, before anything big is sent.
     */
    private void probe(String appBase) throws IOException {
        byte[] token = new byte[12];
        new SecureRandom().nextBytes(token);
        String name = ".hp-upload-test-" + hex(token).substring(0, 12) + ".txt";
        String content = "hp-updater upload test " + hex(token) + "\n";
        putBytes(content.getBytes(UTF8), root + "/" + name);
        listener.line("Writing to the folder works.");
        try {
            if (profile.checkUrl && appBase != null && !appBase.trim().isEmpty()) {
                String url = slash(appBase) + name;
                Http.Response r = http.get(url, 4096);
                if (r.code == 200 && content.equals(new String(r.body, UTF8))) {
                    listener.line("The download URL serves this folder: " + slash(appBase));
                } else if (r.code < 0 || r.code >= 300 && r.code < 400 || r.code == 401
                        || r.code == 403) {
                    throw urlProblem(url, r);
                } else {
                    throw new IOException("A test file written to " + root + " is not what "
                            + url + " returns (" + r.describe() + ").\n"
                            + "The folder and the download URL do not match, so customers would"
                            + " not get what you upload. Check the folder on the server (and the"
                            + " download URL). Nothing was published.");
                }
            }
        } finally {
            try {
                fs.delete(root + "/" + name);
            } catch (IOException ignored) {
                listener.line("(could not remove the test file " + name + ")");
            }
        }
    }

    // ------------------------------------------------------------------ upload

    private static final class Item {
        final String rel;          // "files/b1fe8b5968b5/GasX.jar"
        final Path local;
        final long size;
        final String sha256;
        final String label;        // "GasX.jar"
        final boolean oldCopy;     // the copy in the original layout, for older updaters

        Item(String rel, Path local, long size, String sha256, String label, boolean oldCopy) {
            this.rel = rel;
            this.local = local;
            this.size = size;
            this.sha256 = sha256;
            this.label = label;
            this.oldCopy = oldCopy;
        }
    }

    public Report upload(Path outDir) throws IOException {
        long t0 = System.currentTimeMillis();
        Report report = new Report();

        Path appTxt = outDir.resolve("app.txt");
        String text = Io.readString(appTxt);
        if (text == null) {
            throw new IOException("There is no app.txt in " + outDir + ". Click Create setup first.");
        }
        if (!UpdateManifest.isComplete(text)) {
            throw new IOException(appTxt + " is incomplete. Click Create setup again.");
        }
        UpdateManifest release = UpdateManifest.parse(text);
        if (release.plugin() != null) {
            release.requirePlugin(release.plugin());
        } else {
            release.requireRelease();
        }
        report.version = release.version();
        report.files = release.entries().size();
        String base = slash(release.appBase());
        byte[] manifestBytes = text.getBytes(UTF8);

        // Everything that will go up, checked against the release before connecting.
        Map<String, Item> items = new LinkedHashMap<String, Item>();
        for (UpdateManifest.FileEntry e : release.entries().values()) {
            for (String key : release.serverKeys(e)) {
                String rel = "files/" + key;
                if (items.containsKey(rel)) {
                    continue; // identical files share one copy
                }
                Path local = outDir.resolve("files").resolve(key);
                if (!Files.isRegularFile(local) || !Io.matches(local, e.size, e.sha256)) {
                    throw new IOException(rel + " in " + outDir + " is missing or has changed since"
                            + " Create setup. Click Create setup again before uploading.");
                }
                // An extra copy only when it is not where this release itself says the file is.
                boolean extra = !key.equals(release.storeKey(e));
                items.put(rel, new Item(rel, local, e.size, e.sha256,
                        extra ? e.path + " (old layout)" : e.path, extra));
            }
        }
        listener.line("Release " + release.version() + ": " + release.entries().size() + " files ("
                + UpdateManager.humanBytes(release.totalBytes()) + ")"
                + (release.hasHashedCopies() ? ", with copies for older updaters" : ""));

        try {
            connect();
            fs.mkdirs(root);
            listener.line("Folder on the server: " + root);
            probe(base);

            // What the server has now.
            byte[] liveBytes = fs.read(root + "/app.txt");
            UpdateManifest current = readRemote(root + "/app.txt");
            if (liveBytes != null && current == null) {
                boolean replace = listener.confirm("Unusual app.txt on the server",
                        "The folder " + root + " holds an app.txt that is not a complete release"
                        + "\n(a hand-written one, or one cut off by an earlier failed upload).\n\n"
                        + "Replace it with version " + release.version() + "?");
                if (!replace) {
                    throw new IOException("Nothing was published: " + root + "/app.txt is not a"
                            + " complete release and was left as it is.");
                }
            }
            if (current != null) {
                listener.line("Customers currently get version " + current.version());
                if (!sameUrl(current.appBase(), release.appBase())) {
                    boolean replace = listener.confirm("A different application?",
                            "The folder " + root + " already holds a release for\n    "
                            + current.appBase() + "\n(" + current.uiName() + " " + current.version()
                            + "), but this release is for\n    " + release.appBase() + "\n\n"
                            + "Replace it? Everyone downloading from this folder would get "
                            + release.uiName() + " " + release.version() + ".");
                    if (!replace) {
                        throw new IOException("Nothing was published: the folder on the server"
                                + " holds a release for another download URL.");
                    }
                    current = null; // nothing of it counts as ours
                } else if (!sameKind(current, release)) {
                    boolean replace = listener.confirm("A different kind of release?",
                            "The folder " + root + " holds " + kind(current) + " " + current.version()
                            + ",\nbut this release is " + kind(release) + ".\n\nReplace it? Every PC"
                            + " using this address would get " + kind(release) + ".");
                    if (!replace) {
                        throw new IOException("Nothing was published: the folder on the server"
                                + " holds " + kind(current) + ".");
                    }
                    current = null;
                }
            }

            // A version number must always mean the same files, or PCs that installed it
            // would never receive the difference. (Notes may change.)
            UpdateManifest earlier = current != null && current.version().equals(release.version())
                    ? current : readRemote(root + "/releases/" + safeName(release.version()) + ".txt");
            boolean republish = false;
            if (earlier != null) {
                String diff = sameFiles(earlier, release);
                if (diff != null) {
                    throw new IOException("Version " + release.version() + " was published before"
                            + " with different files (" + diff + "). PCs that installed it would"
                            + " never receive these changes. Give this release a new version"
                            + " number and click Create setup again.");
                }
                republish = current != null && current.version().equals(release.version());
                if (republish) {
                    listener.line("This release is already live: checking every file in full,"
                            + " and repairing any that are wrong.");
                }
            }
            if (current != null && compareVersions(release.version(), current.version()) < 0) {
                if (earlier != null) {
                    // The same files as when it was published: a deliberate rollback.
                    listener.line("Rolling back: PCs on " + current.version() + " will move back to "
                            + release.version() + ".");
                } else {
                    boolean lower = listener.confirm("Lower version",
                            "Version " + release.version() + " is lower than the live "
                            + current.version() + " and was never published before.\nPCs would"
                            + " still move to it: any different version counts as an update.\n\n"
                            + "Publish " + release.version() + "?");
                    if (!lower) {
                        throw new IOException("Nothing was published: " + release.version()
                                + " is lower than the live " + current.version() + ".");
                    }
                }
            }

            // Files of the live release went up complete and were checked before it was
            // switched on, so they only need to be there with the right size. Everything
            // else - and everything when the live release itself is being re-published - is
            // downloaded back and its checksum compared.
            Set<String> live = new HashSet<String>();
            if (current != null && !republish) {
                for (UpdateManifest.FileEntry e : current.entries().values()) {
                    for (String key : current.serverKeys(e)) {
                        live.add("files/" + key);
                    }
                }
            }

            List<Item> send = new ArrayList<Item>();
            for (Item it : items.values()) {
                checkCancel();
                if (live.contains(it.rel) && profile.checkUrl) {
                    report.alreadyThere++;
                    continue;
                }
                if (remoteSize(root + "/" + it.rel) == it.size) {
                    report.alreadyThere++;
                } else {
                    send.add(it);
                }
            }
            long total = 0;
            for (Item it : send) {
                total += it.size;
            }
            listener.line(report.alreadyThere + " file(s) already on the server, " + send.size()
                    + " to upload (" + UpdateManager.humanBytes(total) + ")");
            AtomicLong done = new AtomicLong();
            for (Item it : send) {
                put(it, done, total, false);
                report.uploaded++;
                if (it.oldCopy) {
                    report.oldCopies++;
                }
                report.uploadedBytes += it.size;
            }

            // Check at the URL customers use, before switching anybody over.
            if (profile.checkUrl) {
                listener.line("Checking the release at " + base + " ...");
                Collection<Item> toCheck = items.values();
                for (int round = 0; ; round++) {
                    Check c = verifyAtUrl(base, toCheck, live);
                    if (c.ok()) {
                        break;
                    }
                    if (round > 0) {
                        Item bad = c.missing.isEmpty() ? c.corrupt.get(0) : c.missing.get(0);
                        throw new IOException(base + bad.rel + (c.missing.isEmpty()
                                ? " does not return the file that was uploaded, even after"
                                + " uploading it again. Something on the server changes files."
                                : " is still not served after uploading it again.")
                                + "\nNothing has been switched over: customers keep the release"
                                + " they have.");
                    }
                    List<Item> again = new ArrayList<Item>();
                    long extra = 0;
                    for (Item it : c.missing) {
                        again.add(it);
                        extra += it.size;
                    }
                    for (Item it : c.corrupt) {
                        again.add(it);
                        extra += it.size;
                    }
                    listener.line(c.missing.size() + " file(s) missing and " + c.corrupt.size()
                            + " damaged on the server; uploading them again.");
                    AtomicLong d2 = new AtomicLong();
                    for (Item it : c.missing) {
                        put(it, d2, extra, false);
                    }
                    for (Item it : c.corrupt) {
                        put(it, d2, extra, true); // from scratch: what is there is wrong
                    }
                    report.uploaded += again.size();
                    for (Item it : again) {
                        report.oldCopies += it.oldCopy ? 1 : 0;
                    }
                    report.uploadedBytes += extra;
                    toCheck = again;
                    live = new HashSet<String>(); // check these in full now
                }
                report.urlChecked = true;
                listener.line("All " + items.size() + " file(s) are correct at the download URL.");
            } else {
                report.warnings.add("The release was not checked at the download URL (switched off).");
            }

            // Last guard: every file the release names, where it names it, has been dealt with.
            for (UpdateManifest.FileEntry e : release.entries().values()) {
                if (!items.containsKey("files/" + release.storeKey(e))) {
                    throw new IllegalStateException("internal error: " + e.path
                            + " was not uploaded; nothing has been switched over");
                }
            }

            // Switch customers over, then keep the release's description for clean-up and
            // for the same-version check.
            checkCancel();
            TestHooks.pauseBeforeSwitch();
            putBytes(manifestBytes, root + "/app.txt", liveBytes, true);
            listener.line("app.txt replaced: customers now get version " + release.version() + ".");
            try {
                // Customers are switched now; Cancel no longer stops this small record.
                putBytes(manifestBytes, root + "/releases/" + safeName(release.version()) + ".txt",
                        null, false);
            } catch (IOException e) {
                report.warnings.add("The copy in releases/ could not be written (" + e.getMessage()
                        + "); clean-up will not know about this release.");
            }

            if (profile.checkUrl) {
                Http.Response r = http.get(base + "app.txt?check=" + System.nanoTime(), 4 << 20);
                if (r.code != 200 || !java.util.Arrays.equals(r.body, manifestBytes)) {
                    report.warnings.add(base + "app.txt does not show the new release yet ("
                            + r.describe() + "). A cache between you and the server may still"
                            + " hold the old one; it will catch up.");
                }
            }
        } finally {
            closeQuietly();
        }
        report.millis = System.currentTimeMillis() - t0;
        return report;
    }

    /**
     * Null when two releases contain the same files with the same content, the same main
     * class and the same options; otherwise what differs.
     */
    static String sameFiles(UpdateManifest a, UpdateManifest b) {
        Map<String, UpdateManifest.FileEntry> x = a.entries();
        Map<String, UpdateManifest.FileEntry> y = b.entries();
        for (UpdateManifest.FileEntry e : y.values()) {
            UpdateManifest.FileEntry o = x.get(e.path);
            if (o == null) {
                return e.path + " is new";
            }
            if (!o.sha256.equalsIgnoreCase(e.sha256) || o.classpath != e.classpath
                    || o.executable != e.executable) {
                return e.path + " changed";
            }
        }
        for (String path : x.keySet()) {
            if (!y.containsKey(path)) {
                return path + " was removed";
            }
        }
        if (!String.valueOf(a.mainClass()).equals(String.valueOf(b.mainClass()))
                || !a.jvmArgs().equals(b.jvmArgs()) || !a.appArgs().equals(b.appArgs())) {
            return "start settings changed";
        }
        return null;
    }

    private static boolean sameKind(UpdateManifest a, UpdateManifest b) {
        String x = a.plugin() == null ? "" : a.plugin().toLowerCase();
        String y = b.plugin() == null ? "" : b.plugin().toLowerCase();
        return x.equals(y);
    }

    private static String kind(UpdateManifest m) {
        return m.plugin() != null ? "the plugin \"" + m.plugin() + "\""
                : "the application " + (m.uiName() != null ? m.uiName() : m.mainClass());
    }

        static boolean sameUrl(String a, String b) {
        return a != null && b != null && slash(a).equalsIgnoreCase(slash(b));
    }

    private long remoteSize(String path) throws IOException {
        int failures = 0;
        while (true) {
            checkCancel();
            try {
                ensureConnected();
                return fs.size(path);
            } catch (FtpFs.LoginFailed e) {
                throw e;
            } catch (RemoteFs.Cancelled e) {
                throw e;
            } catch (IOException e) {
                if (++failures >= MAX_FAILURES) {
                    throw e;
                }
                retryAfter(e, failures);
            }
        }
    }

    private void retryAfter(IOException e, int failures) throws IOException {
        checkCancel();
        listener.line("Connection problem (" + e.getMessage() + "); reconnecting ...");
        closeQuietly();
        pause(failures);
    }

    /**
     * Uploads one file as .part, resuming after a dropped connection, then puts it in
     * place. {@code fresh}: start from nothing, because what is on the server is wrong.
     */
    private void put(final Item it, final AtomicLong done, final long total, boolean fresh)
            throws IOException {
        final String remote = root + "/" + it.rel;
        final String part = remote + ".part";
        final long base = done.get();
        int failures = 0;
        long bestSoFar = Long.MIN_VALUE;
        while (true) {
            checkCancel();
            try {
                ensureConnected();
                mkdirsFor(remote);
                if (fresh) {
                    deleteIfThere(remote);
                    deleteIfThere(part);
                    fresh = false;
                }
                long have = fs.size(part);
                if (have > bestSoFar) {
                    if (bestSoFar != Long.MIN_VALUE) {
                        failures = 0; // the last attempt got further: keep going
                    }
                    bestSoFar = have;
                }
                if (have != it.size) {
                    if (have > it.size) {
                        fs.delete(part); // not ours, or from a different upload
                        have = -1;
                    }
                    long offset = have > 0 ? have : 0;
                    listener.line((offset > 0 ? "Resuming " : "Uploading ") + it.label
                            + (offset > 0 ? " at " + UpdateManager.humanBytes(offset) : "")
                            + "  (" + UpdateManager.humanBytes(it.size) + ")");
                    sendWatched(it, part, offset, base, total);
                    long after = fs.size(part);
                    if (after >= 0 && after != it.size) {
                        fs.delete(part);
                        throw new IOException("the server ended up with " + after + " bytes of "
                                + it.size + "; sending it again");
                    }
                } else {
                    // Complete from an earlier attempt whose confirmation was lost.
                    listener.line("Completing " + it.label + " (already fully sent)");
                }
                replace(part, remote);
                done.set(base + it.size);
                listener.progress(done.get(), total, it.label);
                return;
            } catch (FtpFs.LoginFailed e) {
                throw e;
            } catch (RemoteFs.Cancelled e) {
                throw e;
            } catch (IOException e) {
                if (++failures >= MAX_FAILURES) {
                    throw new IOException("Upload of " + it.label + " failed: " + e.getMessage()
                            + "\nWhat was sent is kept on the server; Upload again continues"
                            + " from there.", e);
                }
                retryAfter(e, failures);
            }
        }
    }

    /** No progress for this long and the connection is treated as dead. */
    private static final long STALL_MS = Long.getLong("hp.upload.stallSeconds", 60L) * 1000L;

    /**
     * Sends the file, with a watchdog: a server that stops accepting data would otherwise
     * leave the upload hanging (Java has no timeout on writes). No progress for
     * {@link #STALL_MS} closes the connection, and the retry continues from what arrived.
     */
    private void sendWatched(final Item it, String part, long offset, final long base,
            final long total) throws IOException {
        final AtomicLong last = new AtomicLong(System.currentTimeMillis());
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean stalled = new AtomicBoolean();
        // Once every byte is out, the server may take a while to confirm (virus scanning,
        // slow disks): allow more time for that than for data to keep flowing.
        final AtomicLong limit = new AtomicLong(STALL_MS);
        final RemoteFs conn = fs;
        Thread dog = new Thread("upload-watchdog") {
            @Override
            public void run() {
                while (!finished.get()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!finished.get() && System.currentTimeMillis() - last.get() > limit.get()) {
                        stalled.set(true);
                        conn.abort();
                        return;
                    }
                }
            }
        };
        dog.setDaemon(true);
        dog.start();
        try {
            fs.upload(it.local, part, offset, new RemoteFs.Progress() {
                @Override
                public void bytes(long written) {
                    last.set(System.currentTimeMillis());
                    if (written >= it.size) {
                        limit.set(Math.max(STALL_MS, 180000L));
                    }
                    listener.progress(base + written, total, it.label);
                    TestHooks.afterBytes(written);
                }
            }, cancel);
        } catch (TestDrop d) {
            closeQuietly(); // as if the line went dead
            throw new IOException("connection dropped (simulated)");
        } catch (IOException e) {
            if (stalled.get()) {
                throw new IOException("no data accepted for " + STALL_MS / 1000
                        + " seconds; the connection seems stuck", e);
            }
            throw e;
        } finally {
            finished.set(true);
            dog.interrupt();
        }
    }

    private void deleteIfThere(String path) throws IOException {
        if (fs.exists(path)) {
            fs.delete(path);
        }
    }

    /**
     * Renames {@code from} over {@code to}. Most servers replace the target; for those that
     * refuse, the old file is moved aside first and moved back if the rename still fails,
     * so app.txt is never simply deleted.
     */
    private void replace(String from, String to) throws IOException {
        replace(from, to, null);
    }

    /**
     * As {@link #replace(String, String)}; for a small file ({@code content} given, e.g.
     * app.txt) a server that will not rename over it gets the content written in place
     * instead, so the file is never missing, not even for a moment.
     */
    private void replace(String from, String to, Path content) throws IOException {
        try {
            fs.rename(from, to);
            return;
        } catch (IOException first) {
            if (!fs.exists(to)) {
                throw first; // a real problem, not "the target exists"
            }
            if (content != null) {
                long size = Files.size(content);
                fs.upload(content, to, 0, null, cancel); // overwrite in place
                long after = fs.size(to);
                if (after >= 0 && after != size) {
                    throw new IOException(to + " has " + after + " bytes of " + size
                            + " after writing it in place");
                }
                try {
                    fs.delete(from);
                } catch (IOException ignored) {
                    listener.line("(left " + from + " on the server)");
                }
                return;
            }
            String aside = to + ".old";
            if (fs.exists(aside)) {
                fs.delete(aside);
            }
            try {
                fs.rename(to, aside);
            } catch (IOException e) {
                throw first;
            }
            try {
                fs.rename(from, to);
            } catch (IOException e) {
                try {
                    fs.rename(aside, to);
                } catch (IOException restore) {
                    listener.line("WARNING: could not put " + to + " back from " + aside + ": "
                            + restore.getMessage());
                }
                throw e;
            }
            try {
                fs.delete(aside);
            } catch (IOException ignored) {
                listener.line("(left " + aside + " on the server)");
            }
        }
    }

    private void ensureConnected() throws IOException {
        if (fs == null) {
            connect();
        }
    }

    private void pause(int failures) throws IOException {
        try {
            Thread.sleep(Math.min(8000L, 1000L << (failures - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteFs.Cancelled();
        }
    }

    /** Small files (app.txt) through the same .part-then-rename path. */
    private void putBytes(byte[] data, String remote) throws IOException {
        putBytes(data, remote, null, true);
    }

    /**
     * @param previous what {@code remote} held before, put back if this fails part way, so
     *     the server is never left with half a file
     * @param cancellable false once customers have been switched: Cancel no longer stops it
     */
    private void putBytes(byte[] data, String remote, byte[] previous, boolean cancellable)
            throws IOException {
        Path tmp = Files.createTempFile("hp-upload", ".tmp");
        AtomicBoolean never = new AtomicBoolean();
        try {
            Files.write(tmp, data);
            String part = remote + ".part";
            int failures = 0;
            while (true) {
                if (cancellable) {
                    checkCancel();
                }
                try {
                    ensureConnected();
                    mkdirsFor(remote);
                    final Path file = tmp;
                    final String partName = part;
                    final String target = remote;
                    final AtomicBoolean stop = cancellable ? cancel : never;
                    timed(new IOAction() {
                        @Override
                        public void run() throws IOException {
                            fs.upload(file, partName, 0, null, stop);
                            long after = fs.size(partName);
                            if (after >= 0 && after != Files.size(file)) {
                                throw new IOException("the server has " + after + " bytes of "
                                        + Files.size(file));
                            }
                            replace(partName, target, file);
                        }
                    });
                    return;
                } catch (FtpFs.LoginFailed e) {
                    throw e;
                } catch (RemoteFs.Cancelled e) {
                    throw e;
                } catch (IOException e) {
                    if (++failures >= MAX_FAILURES || !cancellable && cancel.get()) {
                        throw restoreAfterFailure(remote, previous, e);
                    }
                    retryAfter(e, failures);
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** After a failed write of app.txt: make sure the server still has a whole one. */
    private IOException restoreAfterFailure(String remote, byte[] previous, IOException e) {
        if (previous == null) {
            return e;
        }
        try {
            ensureConnected();
            byte[] now = fs.read(remote);
            if (now != null && UpdateManifest.isComplete(new String(now, UTF8))) {
                return e; // still a whole release (old or new); nothing to repair
            }
            final Path old = Files.createTempFile("hp-upload", ".old");
            final String target = remote;
            try {
                Files.write(old, previous);
                timed(new IOAction() {
                    @Override
                    public void run() throws IOException {
                        fs.upload(old, target, 0, null, new AtomicBoolean());
                    }
                });
                byte[] check = fs.read(remote);
                if (check == null || !java.util.Arrays.equals(check, previous)) {
                    throw new IOException("the previous app.txt could not be written back");
                }
            } finally {
                Files.deleteIfExists(old);
            }
            return new IOException(e.getMessage() + "\nThe previous app.txt was put back:"
                    + " customers keep the release they have.", e);
        } catch (IOException again) {
            return new IOException(e.getMessage() + "\napp.txt on the server may now be"
                    + " incomplete. PCs that are installed keep running their version; new"
                    + " installs fail until you upload again.", e);
        }
    }

    private interface IOAction {
        void run() throws IOException;
    }

    private static final java.util.Timer TIMER = new java.util.Timer("upload-timeouts", true);

    /**
     * Runs a short exchange (a small file, a rename) with a time limit: SFTP has no timeout
     * of its own, so a server that stops answering would otherwise leave this waiting forever.
     */
    private void timed(IOAction a) throws IOException {
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicBoolean fired = new AtomicBoolean();
        final RemoteFs conn = fs;
        java.util.TimerTask t = new java.util.TimerTask() {
            @Override
            public void run() {
                if (!done.get()) {
                    fired.set(true);
                    conn.abort();
                }
            }
        };
        TIMER.schedule(t, STALL_MS);
        try {
            a.run();
        } catch (IOException e) {
            if (fired.get()) {
                throw new IOException("no answer for " + STALL_MS / 1000
                        + " seconds; the connection seems stuck", e);
            }
            throw e;
        } finally {
            done.set(true);
            t.cancel();
        }
    }

    /** Files not served (404), and files served with the wrong content. */
    private static final class Check {
        final List<Item> missing = new ArrayList<Item>();
        final List<Item> corrupt = new ArrayList<Item>();

        boolean ok() {
            return missing.isEmpty() && corrupt.isEmpty();
        }
    }

    /**
     * Checks files at the download URL. Files of the live release only need to be there
     * with the right size; every other file is downloaded and its checksum compared.
     * Problems that uploading again cannot fix (permissions, redirects, no answer) stop
     * the upload here.
     */
    private Check verifyAtUrl(String base, Collection<Item> check, Set<String> liveFiles)
            throws IOException {
        Check result = new Check();
        for (Item it : check) {
            checkCancel();
            String url = base + Io.encodeUrlPath(it.rel);
            if (!liveFiles.contains(it.rel)) {
                Http.Response r = http.getDigest(url);
                if (r.code == 404 || r.code == 410) {
                    result.missing.add(it);
                } else if (r.code != 200) {
                    throw urlProblem(url, r);
                } else if (!it.sha256.equalsIgnoreCase(r.sha256)) {
                    result.corrupt.add(it);
                }
            } else {
                Http.Response r = http.head(url);
                if (r.code == 404 || r.code == 410) {
                    result.missing.add(it);
                } else if (r.code != 200 && r.code != 206) {
                    throw urlProblem(url, r);
                } else if (r.length >= 0 && r.length != it.size) {
                    result.corrupt.add(it);
                }
            }
        }
        return result;
    }

    private IOException urlProblem(String url, Http.Response r) {
        String why;
        if (r.code >= 300 && r.code < 400) {
            why = "The server redirects it to " + r.location + ". The updater on customers' PCs"
                    + " may not follow that; set the download URL (appbase) to the final address.";
        } else if (r.code == 401 || r.code == 403) {
            why = "The web server refuses to serve it. Check the folder permissions (files must"
                    + " be readable by the web server).";
        } else if (r.code < 0) {
            why = "The download URL cannot be reached from this computer (" + r.error + ")."
                    + " If that is expected, switch off \"Check the download URL\".";
        } else {
            why = "Unexpected answer.";
        }
        return new IOException(url + ": " + r.describe() + "\n" + why
                + "\nNothing has been switched over: customers keep the release they have.");
    }

    private UpdateManifest readRemote(String path) throws IOException {
        byte[] b = fs.read(path);
        if (b == null) {
            return null;
        }
        String t = new String(b, UTF8);
        if (!UpdateManifest.isComplete(t)) {
            return null;
        }
        try {
            UpdateManifest m = UpdateManifest.parse(t);
            return m.version() == null ? null : m;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ clean-up

    /** What "Clean up server" would remove. Nothing is removed until {@link #cleanup}. */
    public static final class CleanupPlan {
        public final List<String> keptVersions = new ArrayList<String>();
        public final List<String> droppedVersions = new ArrayList<String>();
        public final List<String> files = new ArrayList<String>();   // relative to the folder
        public final List<String> folders = new ArrayList<String>(); // relative, emptied by this
        public long bytes = -1;

        public boolean isEmpty() {
            return files.isEmpty() && folders.isEmpty();
        }
    }

    /**
     * Works out which files on the server no release worth keeping still uses: the
     * current release (what app.txt says) and the {@code keep - 1} releases before it.
     * Only names the publisher creates are ever considered; anything else in the folder
     * is left alone.
     */
    public CleanupPlan planCleanup() throws IOException {
        try {
            connect();
            return computePlan();
        } finally {
            closeQuietly();
        }
    }

    private CleanupPlan computePlan() throws IOException {
        int keep = Math.max(1, profile.keepReleases);
        {
            CleanupPlan plan = new CleanupPlan();
            UpdateManifest current = readRemote(root + "/app.txt");
            if (current == null) {
                throw new IOException("There is no complete app.txt in " + root + " on the server,"
                        + " so it is not safe to decide what is unused. Nothing was removed.");
            }

            // Release history, newest first.
            List<UpdateManifest> history = new ArrayList<UpdateManifest>();
            Map<UpdateManifest, String> historyFile = new LinkedHashMap<UpdateManifest, String>();
            for (String name : fs.list(root + "/releases")) {
                if (!name.matches("[A-Za-z0-9._-]+\\.txt")) {
                    continue;
                }
                UpdateManifest m = readRemote(root + "/releases/" + name);
                if (m != null) {
                    history.add(m);
                    historyFile.put(m, name);
                }
            }
            Collections.sort(history, new Comparator<UpdateManifest>() {
                @Override
                public int compare(UpdateManifest a, UpdateManifest b) {
                    return compareVersions(b.version(), a.version());
                }
            });

            // Kept: the live release and the newest releases before it. A record of a
            // version above the live one (never switched on, or rolled back) is not kept.
            List<UpdateManifest> kept = new ArrayList<UpdateManifest>();
            kept.add(current);
            plan.keptVersions.add(current.version());
            for (UpdateManifest m : history) {
                String v = m.version();
                if (plan.keptVersions.contains(v)) {
                    continue; // the live release's own record
                }
                if (kept.size() < keep && compareVersions(v, current.version()) < 0) {
                    kept.add(m);
                    plan.keptVersions.add(v);
                } else {
                    // Its record in releases/ stays (a few KB): it is what stops this
                    // version number from ever being reused for different files.
                    plan.droppedVersions.add(v);
                }
            }

            Set<String> needed = new HashSet<String>();
            for (UpdateManifest m : kept) {
                for (UpdateManifest.FileEntry e : m.entries().values()) {
                    needed.addAll(m.serverKeys(e));
                }
            }
            // The current release in both layouts, so no updater loses what it can reach now.
            for (UpdateManifest.FileEntry e : current.entries().values()) {
                needed.add(UpdateManifest.namedKey(e));
                needed.add(UpdateManifest.hashedKey(e));
            }

            long bytes = 0;
            boolean sizesKnown = true;
            for (String dir : fs.list(root + "/files")) {
                boolean hashedDir = dir.matches("[0-9a-f]{2}");
                boolean namedDir = dir.matches("[0-9a-f]{12}");
                if (!hashedDir && !namedDir) {
                    continue; // not ours
                }
                List<String> names = fs.list(root + "/files/" + dir);
                int removing = 0;
                for (String name : names) {
                    if (needed.contains(dir + "/" + name)) {
                        continue; // a file of a kept release (whatever its name ends with)
                    }
                    String unfinished = name.endsWith(".part") || name.endsWith(".old")
                            ? name.substring(0, name.length() - (name.endsWith(".part") ? 5 : 4))
                            : null;
                    if (unfinished != null && needed.contains(dir + "/" + unfinished)) {
                        continue; // an upload of a kept file may be under way
                    }
                    String real = unfinished != null ? unfinished : name;
                    if (hashedDir && !real.matches("[0-9a-f]{64}")) {
                        continue; // not ours
                    }
                    String rel = "files/" + dir + "/" + name;
                    plan.files.add(rel);
                    removing++;
                    long s = fs.size(root + "/" + rel);
                    if (s >= 0) {
                        bytes += s;
                    } else {
                        sizesKnown = false;
                    }
                }
                if (removing == names.size()) {
                    plan.folders.add("files/" + dir);
                }
            }
            plan.bytes = sizesKnown ? bytes : -1;
            return plan;
        }
    }

    public void cleanup(CleanupPlan plan) throws IOException {
        try {
            connect();
            // Work it out again now: since the plan was shown, someone may have published
            // (or rolled back to) a release that uses some of these files again.
            CleanupPlan now = computePlan();
            Set<String> stillUnused = new HashSet<String>(now.files);
            List<String> files = new ArrayList<String>();
            for (String rel : plan.files) {
                if (stillUnused.contains(rel)) {
                    files.add(rel);
                }
            }
            if (files.size() < plan.files.size()) {
                listener.line((plan.files.size() - files.size()) + " file(s) are in use again"
                        + " since the list was made, and are kept.");
            }
            Set<String> emptyNow = new HashSet<String>(now.folders);
            List<String> folders = new ArrayList<String>();
            for (String rel : plan.folders) {
                if (emptyNow.contains(rel)) {
                    folders.add(rel);
                }
            }
            int n = 0;
            for (String rel : files) {
                checkCancel();
                try {
                    fs.delete(root + "/" + rel);
                    n++;
                } catch (IOException e) {
                    if (fs.size(root + "/" + rel) >= 0) {
                        throw e;
                    }
                }
                listener.progress(n, files.size(), rel);
            }
            for (String rel : folders) {
                try {
                    fs.rmdir(root + "/" + rel);
                } catch (IOException e) {
                    listener.line("(folder " + rel + " was left: " + e.getMessage() + ")");
                }
            }
            listener.line("Removed " + n + " file(s) and " + folders.size()
                    + " folder(s) no longer used by versions " + now.keptVersions + ".");
        } finally {
            closeQuietly();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 1.0.10 after 1.0.9; numbers compared as numbers, the rest as text. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)");
        String[] y = b.split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c;
            if (x[i].matches("\\d+") && y[i].matches("\\d+")) {
                c = new java.math.BigInteger(x[i]).compareTo(new java.math.BigInteger(y[i]));
            } else {
                c = x[i].compareTo(y[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return x.length - y.length;
    }

    static String safeName(String version) {
        String s = version.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() || s.startsWith(".") ? "v" + s : s;
    }

    static String slash(String url) {
        String u = url.trim();
        return u.endsWith("/") ? u : u + "/";
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    /** Plain HTTP checks, the same way the updater on customers' PCs downloads. */
    static final class Http {

        static final class Response {
            int code = -1;
            long length = -1;
            String location;
            String error;
            byte[] body;
            String sha256;

            String describe() {
                return code < 0 ? "no answer: " + error : "HTTP " + code;
            }
        }

        private final AtomicBoolean cancel;
        private volatile HttpURLConnection current;

        Http(AtomicBoolean cancel) {
            this.cancel = cancel;
        }

        /** Ends a request in progress (Cancel). */
        void abort() {
            HttpURLConnection c = current;
            if (c != null) {
                c.disconnect();
            }
        }

        private HttpURLConnection open(String url, String method) throws IOException {
            if (cancel.get()) {
                throw new RemoteFs.Cancelled();
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("User-Agent", "hp-updater-publisher");
            current = c;
            return c;
        }

        private void checkCancel() throws IOException {
            if (cancel.get()) {
                throw new RemoteFs.Cancelled();
            }
        }

        Response head(String url) throws IOException {
            Response r = new Response();
            try {
                HttpURLConnection c = open(url, "HEAD");
                r.code = c.getResponseCode();
                r.length = c.getContentLengthLong();
                r.location = c.getHeaderField("Location");
                c.disconnect();
                if (r.code == 405 || r.code == 501) {
                    // no HEAD here: ask for the first byte instead
                    c = open(url, "GET");
                    c.setRequestProperty("Range", "bytes=0-0");
                    r.code = c.getResponseCode();
                    r.location = c.getHeaderField("Location");
                    String range = c.getHeaderField("Content-Range");
                    if (r.code == 206 && range != null && range.contains("/")) {
                        try {
                            r.length = Long.parseLong(range.substring(range.indexOf('/') + 1).trim());
                        } catch (NumberFormatException ignored) {
                            r.length = -1;
                        }
                    } else {
                        r.length = c.getContentLengthLong();
                    }
                    c.disconnect(); // do not read a whole file just to learn its size
                }
            } catch (IOException e) {
                checkCancel();
                r.code = -1;
                r.error = e.toString();
            }
            return r;
        }

        Response get(String url, int max) throws IOException {
            Response r = new Response();
            try {
                HttpURLConnection c = open(url, "GET");
                r.code = c.getResponseCode();
                r.location = c.getHeaderField("Location");
                InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                if (in != null) {
                    try {
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0 && out.size() <= max) {
                            checkCancel();
                            out.write(b, 0, n);
                        }
                    } finally {
                        in.close();
                    }
                }
                r.body = out.toByteArray();
                r.length = r.body.length;
            } catch (RemoteFs.Cancelled e) {
                throw e;
            } catch (IOException e) {
                checkCancel();
                r.code = -1;
                r.error = e.toString();
            }
            return r;
        }

        Response getDigest(String url) throws IOException {
            Response r = new Response();
            try {
                HttpURLConnection c = open(url, "GET");
                r.code = c.getResponseCode();
                r.location = c.getHeaderField("Location");
                if (r.code != 200) {
                    c.disconnect();
                    return r;
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                InputStream in = c.getInputStream();
                long len = 0;
                try {
                    byte[] b = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        checkCancel();
                        md.update(b, 0, n);
                        len += n;
                    }
                } finally {
                    in.close();
                }
                r.length = len;
                r.sha256 = hex(md.digest());
            } catch (RemoteFs.Cancelled e) {
                throw e;
            } catch (IOException e) {
                checkCancel();
                r.code = -1;
                r.error = e.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            return r;
        }
    }

    /**
     * Only for the automated tests: simulate a dropped connection or a crash part way
     * through a file. Inactive unless the system properties are set.
     */
    static final class TestHooks {
        private static final long DROP = Long.getLong("hp.upload.test.dropAfterBytes", -1L);
        private static final long KILL = Long.getLong("hp.upload.test.killAfterBytes", -1L);
        private static boolean dropped;
        private static final long PAUSE = Long.getLong("hp.upload.test.pauseBeforeSwitchMs", 0L);

        static void pauseBeforeSwitch() {
            if (PAUSE > 0) {
                System.out.println("[test] pausing before app.txt");
                try {
                    Thread.sleep(PAUSE);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        static void afterBytes(long written) {
            if (KILL > 0 && written >= KILL) {
                System.out.println("[test] simulating a crash after " + written + " bytes");
                Runtime.getRuntime().halt(9);
            }
            if (DROP > 0 && written >= DROP && !dropped) {
                dropped = true;
                throw new TestDrop();
            }
        }
    }

    /** A simulated network failure (tests only). Unchecked so it can leave a callback. */
    static final class TestDrop extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
