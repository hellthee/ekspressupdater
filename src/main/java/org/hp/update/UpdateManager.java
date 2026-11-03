package org.hp.update;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads a release and puts it in place in the application folder.
 *
 * <p>The application runs from its own folder exactly as it does from {@code dist}.
 * Updates are downloaded into {@code .update/staging} and verified there; only when every
 * file is present and correct are they moved into the application folder (see
 * {@link InstallStore#apply}). An interrupted, cancelled or failed download never touches
 * the installed application.</p>
 *
 * <p>Called from inside the running application (a "Check for updates" menu item) it
 * only downloads: the application's own jars are open, and on Windows open files cannot
 * be replaced. The launcher puts the staged release in place on the next start.</p>
 */
public final class UpdateManager {

    public enum Status {
        /** The installed version is the one the server offers. */
        UP_TO_DATE,
        /** The new version is in place in the application folder. */
        UPDATED,
        /** Downloaded and verified; it is put in place the next time the launcher starts. */
        STAGED,
        /** Nothing changed. The installed application is untouched and usable. */
        FAILED,
        /** The user cancelled. What was downloaded is kept for next time. */
        CANCELLED,
        /** The connection was too slow. What was downloaded is kept for next time. */
        POSTPONED
    }

    public static final class Result {
        public final Status status;
        public final String version;
        public final String message;
        public final Throwable cause;

        Result(Status status, String version, String message, Throwable cause) {
            this.status = status;
            this.version = version;
            this.message = message;
            this.cause = cause;
        }

        /** True when a restart is needed to use the new version. */
        public boolean restartRequired() {
            return status == Status.UPDATED || status == Status.STAGED;
        }

        @Override
        public String toString() {
            return status + (version == null ? "" : " " + version)
                    + (message == null ? "" : " - " + message);
        }
    }

    private final UpdateConfig cfg;
    private volatile UpdateListener listener;
    private final InstallStore store;
    private final Downloader downloader;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    /** Set for a plugin: the name its release must carry. Null for the application. */
    private volatile String pluginName;

    public UpdateManager(UpdateConfig cfg, UpdateListener listener) {
        this.cfg = cfg;
        this.listener = listener == null ? UpdateListener.NOOP : listener;
        this.store = new InstallStore(cfg.appDir());
        this.downloader = new Downloader(cfg, this.listener);
    }

    public UpdateListener listener() {
        return listener;
    }

    public void setListener(UpdateListener l) {
        this.listener = l == null ? UpdateListener.NOOP : l;
        this.downloader.setListener(this.listener);
    }

    /**
     * Makes this manager update the plugin {@code name} instead of an application: the
     * release must carry {@code plugin = name}, and it is put in place at once even inside
     * the running application (a plugin's files are not open until it is loaded).
     */
    UpdateManager forPlugin(String name) {
        this.pluginName = UpdateManifest.checkPluginName(name);
        return this;
    }

    public InstallStore store() {
        return store;
    }

    public UpdateConfig config() {
        return cfg;
    }

    public String currentVersion() {
        return store.installedVersion();
    }

    public UpdateManifest installedManifest() {
        return store.installed();
    }

    public void cancel() {
        cancel.set(true);
    }

    /** True inside the application started by the launcher, where files are in use. */
    public static boolean runningInsideApplication() {
        return System.getProperty("update.version") != null;
    }

    /** Kept for applications that call it; the launcher decides this by itself now. */
    @Deprecated
    public void markBootSuccessful() {
    }

    // ------------------------------------------------------------------ server check

    /**
     * Asks the server for the release description, trying only {@code attempts} times
     * and with the short timeouts meant for the startup check.
     */
    public UpdateManifest fetchRemoteManifest(int attempts) throws IOException {
        listener.onPhase("CHECKING");
        return release(fetchManifest(cfg.manifestUrl(), attempts), attempts, 0);
    }

    /** As above, with the normal retry policy. */
    public UpdateManifest fetchRemoteManifest() throws IOException {
        listener.onPhase("CHECKING");
        return release(fetchManifest(cfg.manifestUrl(), -1), -1, 0);
    }

    private String fetchManifest(String url, int attempts) throws IOException {
        listener.onLog("Fetching " + url);
        byte[] raw = attempts < 0 ? downloader.fetch(url, cancel)
                : downloader.fetch(url, cancel, attempts, cfg.checkConnectTimeoutMs(),
                        cfg.checkReadTimeoutMs());
        return new String(raw, Io.UTF8);
    }

    /** Persists a new address when the server says the release has moved. */
    interface MoveListener {
        void moved(String from, String to) throws IOException;
    }

    private MoveListener moveListener;

    /** Who records a move; without one, the application's own app.txt is changed. */
    UpdateManager onMoved(MoveListener l) {
        this.moveListener = l;
        return this;
    }

    /**
     * The release in {@code text}, or, when it says {@code moved = <address>}, the release at
     * that address. The PC switches only once a usable release was found there; then its
     * app.txt (or setapp.txt) is changed for good. A wrong address leaves it where it is.
     */
    private UpdateManifest release(String text, int attempts, int hops) throws IOException {
        String to = movedTo(text);
        if (to == null) {
            return parseRelease(text);
        }
        String from = cfg.baseUrl();
        String target = to.endsWith("/") ? to : to + "/";
        if (hops >= 3) {
            throw new IOException("the release has moved more than 3 times; stopped at " + from);
        }
        if (!(target.startsWith("http://") || target.startsWith("https://"))
                || PluginSet.sameAddress(from, target)) {
            throw new IOException(UpdateConfig.APP_TXT + " at " + from + " says moved = " + to
                    + ", which is not a usable address");
        }
        listener.onLog("The release has moved to " + target);
        UpdateManifest m;
        cfg.setBaseUrl(target);
        try {
            m = release(fetchManifest(cfg.manifestUrl(), attempts), attempts, hops + 1);
        } catch (IOException e) {
            cfg.setBaseUrl(from);
            throw new IOException("the release has moved to " + target + ", but nothing usable was"
                    + " found there (" + e.getMessage() + "); this PC stays with " + from, e);
        }
        if (hops == 0) {
            try {
                if (moveListener != null) {
                    moveListener.moved(from, cfg.baseUrl());
                } else if (pluginName == null) {
                    UpdateConfig.writeAppBase(cfg.appDir().resolve(UpdateConfig.APP_TXT), cfg.baseUrl());
                }
                listener.onLog("Address changed for good: " + from + " -> " + cfg.baseUrl());
            } catch (IOException e) {
                // Used for this start anyway; the next start follows the move again.
                listener.onWarning("could not record the new address: " + e.getMessage());
            }
        }
        return m;
    }

    /** The address of a "moved = ..." line, or null. */
    static String movedTo(String text) {
        if (text == null) {
            return null;
        }
        for (String raw : text.split("\n")) {
            String s = raw.trim();
            if (s.startsWith("\uFEFF")) {
                s = s.substring(1);
            }
            int eq = s.indexOf('=');
            if (!s.startsWith("#") && eq > 0 && "moved".equalsIgnoreCase(s.substring(0, eq).trim())) {
                String v = s.substring(eq + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private UpdateManifest parseRelease(String text) throws IOException {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (!UpdateManifest.isComplete(text)) {
            throw new IOException("Incomplete " + UpdateConfig.APP_TXT
                    + " (the download was cut short, or the file on the server was not"
                    + " written by ManifestTool)");
        }
        UpdateManifest m = UpdateManifest.parse(text);
        if (pluginName != null) {
            m.requirePlugin(pluginName);
        } else {
            m.requireRelease();
        }
        checkSignature(text, m);
        return m;
    }

    /**
     * Release signing is switched off for now (see TODO.md). The check stays for PCs whose
     * app.txt carries a {@code publickey} line: those accept only releases signed with it.
     * PCs without one accept any release and do NOT adopt a key from the server, so a
     * forged "signed" release cannot lock a PC out of your later, unsigned releases.
     */
    private void checkSignature(String text, UpdateManifest m) throws IOException {
        String pinned = cfg.publicKey();
        if (pinned != null && !Signing.verify(text, Signing.publicKey(pinned))) {
            throw new Signing.BadSignature("The release on the server is not signed with "
                    + cfg.displayName() + "'s publisher key, so it was refused (it may have"
                    + " been changed on the way). If you made a new signing key, put its"
                    + " publickey line into app.txt on each PC.");
        }
    }

    public boolean isUpdateAvailable() {
        try {
            return !fetchRemoteManifest().version().equals(store.installedVersion());
        } catch (IOException e) {
            listener.onWarning("Update check failed: " + e.getMessage());
            return false;
        }
    }

    // --------------------------------------------------------------------- update

    /** Checks the server and installs (or, inside the app, stages) the new version. */
    public Result update() {
        cancel.set(false);
        try {
            return install(fetchRemoteManifest());
        } catch (Downloader.Cancelled c) {
            return new Result(Status.CANCELLED, store.installedVersion(), "Cancelled", null);
        } catch (Exception e) {
            listener.onFailed("Update check failed", e);
            return new Result(Status.FAILED, store.installedVersion(),
                    "Could not reach the update server: " + e.getMessage(), e);
        }
    }

    /**
     * Downloads what has changed into staging and, unless running inside the application,
     * moves it into the application folder. Never throws; never damages the installed
     * application.
     */
    public Result install(UpdateManifest remote) {
        cancel.set(false);
        if (pluginName == null) {
            // lib/set.jar and the like belong to their plugin (setapp.txt), even if a release
            // of the application still carries a copy.
            java.util.Set<String> managed = PluginSet.managedPaths(cfg.appDir());
            UpdateManifest stripped = remote.without(managed);
            if (stripped != remote) {
                listener.onLog("Leaving " + managed + " to the plugin updater (the release's"
                        + " copy is not used)");
                remote = stripped;
            }
        }
        Closeable lock = null;
        try {
            lock = store.lock();
        } catch (IOException e) {
            return new Result(Status.FAILED, store.installedVersion(), e.getMessage(), e);
        }
        try {
            String current = store.installedVersion();
            if (remote.version().equals(current)) {
                listener.onPhase("DONE");
                return new Result(Status.UP_TO_DATE, current, "Already up to date", null);
            }
            listener.onLog("Updating to " + remote.version()
                    + (current == null ? " (first install)" : " from " + current));

            Path staging = store.stagingDir(remote.version());
            Files.createDirectories(staging);

            // 1. Compare versions file by file; only what actually changed is downloaded.
            UpdateManifest old = store.installed();
            List<UpdateManifest.FileEntry> toDownload = new ArrayList<UpdateManifest.FileEntry>();
            long downloadBytes = 0;
            int unchanged = 0;
            int kept = 0;
            long unchangedBytes = 0;
            for (UpdateManifest.FileEntry e : remote.entries().values()) {
                Path staged = InstallStore.inside(staging, e.path);
                if (InstallStore.isSeed(e)) {
                    // Settings: the customer's copy is never replaced; only the pristine
                    // default is refreshed, and only if the publisher changed it.
                    if (store.hasDefault(e)
                            || (Files.isRegularFile(staged) && Io.matches(staged, e.size, e.sha256))) {
                        unchanged++;
                        unchangedBytes += e.size;
                        continue;
                    }
                } else {
                    InstallStore.Plan plan = store.plan(e, old);
                    if (plan == InstallStore.Plan.UNCHANGED) {
                        unchanged++;
                        unchangedBytes += e.size;
                        continue;
                    }
                    if (plan == InstallStore.Plan.KEEP_LOCAL) {
                        kept++;
                        listener.onLog("Keeping this PC's own " + e.path
                                + " (the release did not change it)");
                        continue;
                    }
                    if (Files.isRegularFile(staged) && Io.matches(staged, e.size, e.sha256)) {
                        continue; // already downloaded by an earlier, interrupted attempt
                    }
                }
                toDownload.add(e);
                downloadBytes += e.size;
            }
            listener.onLog(unchanged + " file(s) already up to date (" + humanBytes(unchangedBytes)
                    + " not downloaded)" + (kept > 0 ? ", " + kept + " kept as they are" : "")
                    + ", " + toDownload.size() + " to download (" + humanBytes(downloadBytes) + ")");
            int listed = 0;
            for (UpdateManifest.FileEntry e : toDownload) {
                if (listed++ < 12) {
                    boolean isNew = old == null || old.entries().get(e.path) == null;
                    if (InstallStore.isSeed(e) && Files.exists(store.file(e.path))) {
                        listener.onLog("  default: " + e.path + "  " + humanBytes(e.size)
                                + "  (stored for new PCs; this PC's own copy is kept)");
                    } else {
                        listener.onLog((isNew ? "  new:     " : "  changed: ") + e.path
                                + "  " + humanBytes(e.size));
                    }
                }
            }
            if (toDownload.size() > 12) {
                listener.onLog("  ... and " + (toDownload.size() - 12) + " more");
            }

            // 2. Download, resumable and verified.
            listener.onPhase("DOWNLOADING");
            final long total = downloadBytes;
            final AtomicLong completed = new AtomicLong(0);
            for (final UpdateManifest.FileEntry e : toDownload) {
                if (cancel.get()) {
                    throw new Downloader.Cancelled();
                }
                final long base = completed.get();
                listener.onProgress(base, total, e.path);
                fetchFile(remote, e, InstallStore.inside(staging, e.path),
                        new Downloader.BytesSink() {
                            @Override
                            public void bytes(long inFile) {
                                listener.onProgress(base + inFile, total, e.path);
                            }
                        });
                completed.addAndGet(e.size);
                listener.onProgress(completed.get(), total, e.path);
            }

            // 3. Everything is here and verified.
            listener.onPhase("VERIFYING");
            store.markStaged(remote);

            Set<String> keep = new HashSet<String>();
            for (UpdateManifest.FileEntry e : remote.entries().values()) {
                keep.add(e.sha256);
            }
            store.pruneCache(keep);

            if (runningInsideApplication() && pluginName == null) {
                listener.onPhase("DONE");
                return new Result(Status.STAGED, remote.version(),
                        "Version " + remote.version() + " is downloaded. Restart to use it.", null);
            }

            // 4. Put it in place.
            listener.onPhase("ACTIVATING");
            store.apply(remote, listener);
            listener.onLog("Version " + remote.version() + " is in place");
            listener.onPhase("DONE");
            return new Result(Status.UPDATED, remote.version(), "Updated", null);

        } catch (Downloader.TooSlow slow) {
            listener.onLog("Connection too slow (" + (slow.bytesPerSec / 1024)
                    + " KB/s). What has been downloaded is kept for next time.");
            return new Result(Status.POSTPONED, store.installedVersion(),
                    "The connection is too slow to finish the update now ("
                    + (slow.bytesPerSec / 1024) + " KB/s). It will continue next time.", null);
        } catch (Downloader.Cancelled c) {
            listener.onLog("Cancelled. What was downloaded is kept for next time.");
            return new Result(Status.CANCELLED, store.installedVersion(), "Cancelled", null);
        } catch (Exception e) {
            listener.onFailed("Update failed, keeping version " + store.installedVersion(), e);
            return new Result(Status.FAILED, store.installedVersion(),
                    e.getMessage() + " The installed version is unchanged.", e);
        } finally {
            Io.closeQuietly(lock);
        }
    }

    /** Puts a release downloaded earlier (from inside the app) into place. */
    public Result applyStaged(UpdateManifest staged) {
        Closeable lock = null;
        try {
            lock = store.lock();
            listener.onPhase("ACTIVATING");
            store.apply(staged, listener);
            return new Result(Status.UPDATED, staged.version(), "Updated", null);
        } catch (IOException | RuntimeException e) {
            return new Result(Status.FAILED, store.installedVersion(), e.getMessage(), e);
        } finally {
            Io.closeQuietly(lock);
        }
    }

    /**
     * Re-downloads any installed file that is missing or damaged. Runs before the
     * application starts, so the files can be replaced directly.
     */
    public Result repair() {
        UpdateManifest m = store.installed();
        if (m == null) {
            return new Result(Status.FAILED, null, "Nothing installed to repair", null);
        }
        if (pluginName == null) {
            m = m.without(PluginSet.managedPaths(cfg.appDir()));
        }
        int fixed = 0;
        try {
            for (UpdateManifest.FileEntry e : m.entries().values()) {
                if (InstallStore.isSeed(e) || store.plan(e, m) != InstallStore.Plan.DOWNLOAD) {
                    continue;
                }
                listener.onLog("Damaged or missing: " + e.path);
                fetchFile(m, e, store.file(e.path), null);
                fixed++;
            }
            return new Result(Status.UPDATED, m.version(),
                    fixed == 0 ? "All files intact" : fixed + " file(s) repaired", null);
        } catch (Exception e) {
            return new Result(Status.FAILED, m.version(), "Repair failed: " + e.getMessage(), e);
        }
    }

    /**
     * Downloads one file of a release, trying where the release says it is first and the
     * other server layout second, so older and newer releases both install.
     */
    private void fetchFile(UpdateManifest release, UpdateManifest.FileEntry e, Path target,
            Downloader.BytesSink sink) throws IOException {
        String[] urls = cfg.fileUrls(release, e);
        IOException first = null;
        for (String url : urls) {
            try {
                downloader.download(url, store.partFile(e.sha256), target, e.size, e.sha256,
                        sink, cancel);
                return;
            } catch (Downloader.Permanent p) {
                if (p.status != 404 && p.status != 410 && p.status != 403) {
                    throw p;
                }
                if (first == null) {
                    first = p; // not at this address; try the other layout
                }
            } catch (Downloader.WrongContent w) {
                if (first == null) {
                    first = w; // the server's copy here is damaged; the other may be fine
                }
            }
        }
        throw first;
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }
}
