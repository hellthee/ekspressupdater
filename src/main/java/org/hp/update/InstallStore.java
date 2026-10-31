package org.hp.update;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Owns the application folder, laid out the way getdown lays it out: the application's
 * files sit at their real paths in one folder and the application runs from there, so
 * every relative path in your code behaves exactly as it does when you run from
 * {@code dist}.
 *
 * <pre>
 * C:\GasX\
 *   GasX.jar  lib\...  properties-gasx.prp     the application, exactly like dist
 *   launcher.jar  app.txt  GasX.bat  logs\
 *   .update\
 *     installed.txt          what is in the folder: version, files, checksums
 *     staging\1.0.3\...      a download in progress, or finished and waiting
 *     cache\                 partly downloaded files, resumed next time
 *     backup\                the files an update replaced, until the new version starts
 *     defaults\              pristine copies of settings files
 *     journal.properties     present only while an update is being applied
 *     state.properties       which release failed to start, if any
 * </pre>
 *
 * <p>Unlike getdown, nothing is ever downloaded over the installed files. A release is
 * downloaded and verified in {@code staging} first; only then are its files moved into
 * place, and the files they replace are moved to {@code backup} rather than deleted.
 * Every move is written to the journal before it happens, so if the machine stops in the
 * middle the next start puts the old files back. A new version that cannot start is
 * reverted the same way.</p>
 */
public final class InstallStore {

    public static final String UPDATE_DIR = ".update";
    public static final String INSTALLED = "installed.txt";
    private static final String JOURNAL = "journal.properties";
    private static final String STATE = "state.properties";
    private static final String STAGED_RELEASE = ".release.txt";
    private static final String BACKUP_INSTALLED = ".installed.txt";

    private final Path appDir;

    public InstallStore(Path appDir) {
        this.appDir = appDir.toAbsolutePath().normalize();
    }

    public Path appDir() {
        return appDir;
    }

    public Path updateDir() {
        return appDir.resolve(UPDATE_DIR);
    }

    public Path stagingDir(String version) {
        return inside(updateDir().resolve("staging"), UpdateManifest.checkVersion(version));
    }

    /**
     * {@code base/relative}, refusing anything that would land outside {@code base}: a
     * second line of defence behind the checks on every path in a release.
     */
    static Path inside(Path base, String relative) {
        Path root = base.toAbsolutePath().normalize();
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new UnsafePathException(relative);
        }
        return p;
    }

    /** A path in a release or journal that would leave the application folder. */
    public static final class UnsafePathException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        UnsafePathException(String path) {
            super("Refusing a path outside the application folder: " + path);
        }
    }

    public Path cacheDir() {
        return updateDir().resolve("cache");
    }

    public Path partFile(String sha256) {
        return cacheDir().resolve(sha256.substring(0, 2)).resolve(sha256 + ".part");
    }

    private Path backupDir() {
        return updateDir().resolve("backup");
    }

    private Path defaultsDir() {
        return updateDir().resolve("defaults");
    }

    // ------------------------------------------------------------- what is installed

    /** The release currently in the application folder, or null on a fresh PC. */
    public UpdateManifest installed() {
        String text = Io.readString(updateDir().resolve(INSTALLED));
        if (text == null) {
            return null;
        }
        try {
            return UpdateManifest.parse(text);
        } catch (IOException e) {
            return null;
        }
    }

    public String installedVersion() {
        UpdateManifest m = installed();
        return m == null ? null : m.version();
    }

    /** Where an installed file lives. Never outside the application folder. */
    public Path file(String relativePath) {
        return inside(appDir, relativePath);
    }

    private Path staged(Path staging, String relativePath) {
        return inside(staging, relativePath);
    }

    private Path backup(String relativePath) {
        return inside(backupDir(), relativePath);
    }

    /**
     * True when the file at this path in the application folder is exactly this entry,
     * so there is nothing to download for it.
     */
    public boolean hasIntact(UpdateManifest.FileEntry e) {
        Path f = file(e.path);
        try {
            return Files.isRegularFile(f) && Files.size(f) == e.size
                    && Io.hashEquals(Io.sha256(f), e.sha256);
        } catch (IOException ex) {
            return false;
        }
    }

    /** What an update does with one file of the new release. */
    public enum Plan {
        /** The file on this PC is already exactly the new one. */
        UNCHANGED,
        /**
         * The publisher did not change this file since the installed release, but the copy
         * on this PC differs: the application changed it while running. It is left alone.
         */
        KEEP_LOCAL,
        /** New, or changed by the publisher, or missing or damaged here: download it. */
        DOWNLOAD
    }

    /**
     * Decides whether a file has to come over the network, by comparing versions:
     * what the publisher shipped before ({@code old}), what is shipped now ({@code e}),
     * and what is actually on this PC.
     *
     * <ul>
     *   <li>Same checksum on the PC: nothing to do. Jasper, POI, the MySQL driver and the
     *       other libraries are byte-identical from build to build, so they are never
     *       downloaded twice.</li>
     *   <li>Jar files differing on the PC: always downloaded. Nothing writes to a jar, so a
     *       difference means it is damaged or missing, and replacing it repairs it.</li>
     *   <li>Other files differing on the PC: downloaded only if the publisher changed them.
     *       If not, the difference is the application's own doing (a .dat it writes, say)
     *       and the customer's copy is kept.</li>
     * </ul>
     */
    public Plan plan(UpdateManifest.FileEntry e, UpdateManifest old) {
        if (hasIntact(e)) {
            return Plan.UNCHANGED;
        }
        boolean present = Files.isRegularFile(file(e.path));
        if (old == null) {
            // First install into a folder that already has the application (copied from
            // dist, or installed by getdown): there is no record of what the publisher sent
            // before, so a settings file that differs is the customer's own. It is kept;
            // from now on it is replaced whenever you change it.
            return !e.classpath && present ? Plan.KEEP_LOCAL : Plan.DOWNLOAD;
        }
        UpdateManifest.FileEntry before = old.entries().get(e.path);
        boolean publisherUnchanged = before != null && Io.hashEquals(before.sha256, e.sha256);
        if (!e.classpath && publisherUnchanged && present) {
            return Plan.KEEP_LOCAL;
        }
        return Plan.DOWNLOAD;
    }

    /** True when the pristine copy of a settings file is already the one in this release. */
    public boolean hasDefault(UpdateManifest.FileEntry e) {
        Path pristine = inside(defaultsDir(), e.path);
        try {
            return Files.isRegularFile(pristine) && Files.size(pristine) == e.size
                    && Io.hashEquals(Io.sha256(pristine), e.sha256);
        } catch (IOException ex) {
            return false;
        }
    }

    // --------------------------------------------------------------------- staging

    /** Records that a release has been downloaded and verified in full. */
    public void markStaged(UpdateManifest release) throws IOException {
        Io.writeStringAtomic(stagingDir(release.version()).resolve(STAGED_RELEASE), release.toText());
        // Only the newest download waits: an older one left behind could otherwise be put
        // in place later and take the application back to an older version.
        discardStagingExcept(release.version());
    }

    private void discardStagingExcept(String keep) {
        java.io.File[] dirs = updateDir().resolve("staging").toFile().listFiles();
        if (dirs != null) {
            for (java.io.File d : dirs) {
                if (!d.getName().equals(keep)) {
                    Io.deleteQuietly(d.toPath());
                }
            }
        }
    }

    /** A release downloaded in full and waiting to be applied, or null. */
    public UpdateManifest stagedRelease() {
        java.io.File[] dirs = updateDir().resolve("staging").toFile().listFiles();
        if (dirs == null) {
            return null;
        }
        UpdateManifest best = null;
        for (java.io.File d : dirs) {
            String text = Io.readString(d.toPath().resolve(STAGED_RELEASE));
            if (text == null) {
                continue;
            }
            try {
                UpdateManifest m = UpdateManifest.parse(text);
                if (!d.getName().equals(m.version())) {
                    continue; // not where this release would have been staged
                }
                if (best == null || d.lastModified() > stagingDir(best.version()).toFile().lastModified()) {
                    best = m;
                }
            } catch (IOException ignored) {
                // a damaged staging folder is simply not offered
            } catch (IllegalArgumentException ignored) {
                // nor one with an unsafe version or path in it
            }
        }
        return best;
    }

    public void discardStaging(String version) {
        Io.deleteQuietly(stagingDir(version));
    }

    // ---------------------------------------------------------------------- apply

    /**
     * Moves a fully staged release into the application folder.
     *
     * <ul>
     *   <li>Changed and new files are moved in from staging; each file they replace goes
     *       to backup first.</li>
     *   <li>Files the previous release had and this one does not are moved to backup,
     *       which is how a library dropped from the release disappears from the PC.</li>
     *   <li>Settings files are placed only if the customer does not have one yet.</li>
     *   <li>Nothing that was never part of a release is touched: launcher.jar, app.txt,
     *       logs, the customer's own files.</li>
     * </ul>
     *
     * Any failure part way through - a file locked by a running copy of the application,
     * a full disk - puts everything back as it was and reports the reason.
     */
    public void apply(UpdateManifest release, UpdateListener listener) throws IOException {
        UpdateListener log = listener == null ? UpdateListener.NOOP : listener;
        // Plugin files (lib/set.jar) are never replaced by the application's release, even
        // one staged before the plugin was set up.
        release = release.without(PluginSet.managedPaths(appDir));
        UpdateManifest old = installed();
        Path staging = stagingDir(release.version());

        // Plan the moves before touching anything.
        List<String> replace = new ArrayList<String>();
        Set<String> remove = new LinkedHashSet<String>();
        for (UpdateManifest.FileEntry e : release.entries().values()) {
            Path fromStaging = staged(staging, e.path);
            if (Files.isRegularFile(fromStaging)) {
                // Checked again here: something (an antivirus, a disk error) may have
                // changed it since it was downloaded.
                if (!Io.matches(fromStaging, e.size, e.sha256)) {
                    throw new StagingDamaged(e.path);
                }
                replace.add(e.path);
            } else if (plan(e, old) == Plan.DOWNLOAD) {
                throw new StagingDamaged(e.path);
            }
        }
        if (old != null) {
            for (UpdateManifest.FileEntry e : old.entries().values()) {
                // A library dropped from the release is deleted, so it can never be loaded
                // by mistake. Any other file the publisher stops sending (a .prp, a .dat)
                // stays on the PC as it is and is simply no longer updated: unticking a
                // settings file must not delete a customer's settings.
                if (!release.entries().containsKey(e.path) && e.classpath) {
                    remove.add(e.path);
                }
            }
        } else {
            remove.addAll(leftoverJars(release));
        }
        // Plugin files in place (lib/set.jar) are the plugin updater's, never the release's.
        Set<String> managed = new HashSet<String>();
        for (String m : PluginSet.managedPaths(appDir)) {
            managed.add(m.toLowerCase());
        }
        for (java.util.Iterator<String> i = remove.iterator(); i.hasNext(); ) {
            if (managed.contains(i.next().toLowerCase())) {
                i.remove();
            }
        }
        // "GasX.jar" becoming "gasx.jar": on Windows and macOS that is the same file, which
        // the replacement takes care of. Removing the old name would delete the new one.
        for (java.util.Iterator<String> i = remove.iterator(); i.hasNext(); ) {
            String r = i.next();
            for (String n : release.entries().keySet()) {
                if (n.equalsIgnoreCase(r)) {
                    i.remove();
                    break;
                }
            }
        }
        // A file standing where the new release needs a folder ("docs" -> "docs/manual.txt")
        // has to make way, whatever kind of file it is.
        for (String path : replace) {
            for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) {
                String parent = path.substring(0, slash);
                if (Files.isRegularFile(file(parent))) {
                    remove.add(parent);
                }
            }
            Path target = file(path);
            if (Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                String[] inside = target.toFile().list();
                if (inside != null && inside.length > 0) {
                    throw new IOException("The update would replace the folder " + path
                            + " with a file, and that folder is not empty. Nothing was changed.");
                }
            }
        }

        Io.deleteQuietly(backupDir());
        Files.createDirectories(backupDir());
        Journal j = newJournal("SWAPPING", release.version(), old == null ? "" : old.version());
        j.save();
        if (Files.isRegularFile(updateDir().resolve(INSTALLED))) {
            Files.copy(updateDir().resolve(INSTALLED), backupDir().resolve(BACKUP_INSTALLED),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        Set<Path> emptied = new LinkedHashSet<Path>();
        try {
            // Removals first, so a file that is in the way of a new folder is gone in time.
            for (String path : remove) {
                if (Files.exists(file(path), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    j.record("moved", path);
                    moveIntoBackup(path);
                    emptied.add(file(path).getParent());
                    log.onLog("Removed " + path + " (no longer part of the application)");
                }
            }
            for (String path : replace) {
                Path target = file(path);
                if (Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(target); // an empty folder where the file goes (checked above)
                }
                boolean existed = Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                j.record(existed ? "moved" : "added", path);
                if (existed) {
                    moveIntoBackup(path);
                }
                Io.ensureParent(target);
                Files.move(staged(staging, path), target, StandardCopyOption.REPLACE_EXISTING);
            }
            Io.writeStringAtomic(updateDir().resolve(INSTALLED), release.toText());
            j.state = "APPLIED";
            j.save();
        } catch (IOException e) {
            revert();
            throw new IOException(describeSwapFailure(e), e);
        }
        Io.deleteQuietly(updateDir().resolve("staging")); // nothing older may be applied later
        pruneEmptied(emptied);
    }

    /**
     * A staged release that cannot be used as it is (a file missing or changed since it
     * was downloaded). It is discarded and downloaded again.
     */
    public static final class StagingDamaged extends IOException {
        private static final long serialVersionUID = 1L;

        StagingDamaged(String path) {
            super("The downloaded update is incomplete or damaged (" + path + "); it will be"
                    + " downloaded again");
        }
    }

    /** True when an update is part way through or not yet confirmed by a successful start. */
    public boolean hasJournal() {
        return Files.isRegularFile(updateDir().resolve(JOURNAL));
    }

    /** True while an update has been applied but the new version has not yet started. */
    public boolean hasUnconfirmedUpdate() {
        Journal j = Journal.load(this);
        return j != null && "APPLIED".equals(j.state);
    }

    /** The version the files were before the last update, or null. */
    public String versionBeforeUpdate() {
        Journal j = Journal.load(this);
        return j == null || j.previous.isEmpty() ? null : j.previous;
    }

    /** The new version has started: the old files are no longer needed. */
    public void confirmUpdate() {
        Io.deleteQuietly(updateDir().resolve(JOURNAL));
        Io.deleteQuietly(backupDir());
        removeLegacyLayout();
    }

    /** Deletes the {@code journal} only; for a journal that cannot be trusted or used. */
    private void dropJournal() {
        Io.deleteQuietly(updateDir().resolve(JOURNAL));
    }

    /**
     * Puts back the files an update replaced and removes the ones it added, using the
     * journal. Safe to run after a crash at any point of {@link #apply}.
     *
     * @return the version that is installed afterwards, or null if nothing was pending
     */
    public String revert() throws IOException {
        Journal j = Journal.load(this);
        if (j == null) {
            return null;
        }
        List<String> ops = j.ops();
        Path staging;
        try {
            staging = stagingDir(j.version);
        } catch (IllegalArgumentException e) {
            dropJournal(); // not a journal this updater wrote: act on nothing in it
            return installedVersion();
        }
        Set<Path> emptied = new LinkedHashSet<Path>();
        for (int i = ops.size() - 1; i >= 0; i--) {
            String op = ops.get(i);
            int colon = op.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String kind = op.substring(0, colon);
            String path = op.substring(colon + 1);
            Path target;
            Path saved;
            Path park;
            try {
                UpdateManifest.checkPath(path);
                target = file(path);
                saved = backup(path);
                park = staged(staging, path);
            } catch (IllegalArgumentException unsafe) {
                continue; // never act on a path outside the application folder
            }
            if ("added".equals(kind)) {
                // The new file goes back to staging, not the bin: it was downloaded and
                // verified, and the next attempt should not have to fetch it again.
                parkInStaging(target, park);
                emptied.add(target.getParent());
            } else if (Files.exists(saved)) {
                parkInStaging(target, park);
                Io.ensureParent(target);
                Files.move(saved, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path oldInstalled = backupDir().resolve(BACKUP_INSTALLED);
        if (Files.isRegularFile(oldInstalled)) {
            Files.move(oldInstalled, updateDir().resolve(INSTALLED), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(updateDir().resolve(INSTALLED));
        }
        Io.deleteQuietly(updateDir().resolve(JOURNAL));
        Io.deleteQuietly(backupDir());
        pruneEmptied(emptied);
        return installedVersion();
    }

    /**
     * Run at every start. An update interrupted while files were being moved is undone,
     * so the application folder is never left half old and half new.
     */
    public String recover() throws IOException {
        Journal j = Journal.load(this);
        if (j != null && "SWAPPING".equals(j.state)) {
            String version = j.version;
            revert();
            return version;
        }
        return null;
    }

    /** A settings file that was deleted is put back from the release's own copy. */
    public List<String> restoreMissingSettings() {
        List<String> restored = new ArrayList<String>();
        UpdateManifest m = installed();
        if (m == null) {
            return restored;
        }
        for (UpdateManifest.FileEntry e : m.entries().values()) {
            if (!isSeed(e)) {
                continue;
            }
            Path pristine = inside(defaultsDir(), e.path);
            if (isSeed(e) && !Files.exists(file(e.path)) && Files.isRegularFile(pristine)) {
                try {
                    Io.ensureParent(file(e.path));
                    Files.copy(pristine, file(e.path));
                    restored.add(e.path);
                } catch (IOException ignored) {
                    // the application will report a missing file itself
                }
            }
        }
        return restored;
    }

    // ------------------------------------------------------------- failure memory

    public String failedVersion() {
        String v = Io.loadProps(updateDir().resolve(STATE)).getProperty("failed");
        return v == null || v.isEmpty() ? null : v;
    }

    public void markFailed(String version) throws IOException {
        Properties p = Io.loadProps(updateDir().resolve(STATE));
        p.setProperty("failed", version);
        Io.storeProps(updateDir().resolve(STATE), p);
    }

    public void clearFailed() throws IOException {
        Properties p = Io.loadProps(updateDir().resolve(STATE));
        p.remove("failed");
        Io.storeProps(updateDir().resolve(STATE), p);
    }

    // ---------------------------------------------------------------------- upkeep

    /** Drops partial downloads that belong to no current release. */
    public void pruneCache(Set<String> keepHashes) {
        try {
            for (Path f : Io.listFilesRecursively(cacheDir())) {
                String name = f.getFileName().toString();
                String sha = name.endsWith(".part") ? name.substring(0, name.length() - 5) : name;
                if (!keepHashes.contains(sha)) {
                    Io.deleteQuietly(f);
                }
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** Like {@link #lock()}, but returns null at once if someone else holds it. */
    public Closeable tryLock() {
        try {
            return lock();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------- running app

    private static FileChannel appLockChannel;
    private static FileLock appLock;

    /**
     * Held by a running application for its whole life, so an updater started from it
     * can tell when it has closed. Released by the operating system when the JVM ends,
     * however it ends.
     */
    /** True when this JVM holds the "application is running" lock. */
    static synchronized boolean holdsAppLock() {
        return appLock != null && appLock.isValid();
    }

    public synchronized void holdWhileRunning() {
        if (appLock != null) {
            return;
        }
        try {
            Files.createDirectories(updateDir());
            appLockChannel = FileChannel.open(updateDir().resolve("app.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            appLock = appLockChannel.tryLock();
        } catch (Exception ignored) {
            // a second copy of the app already holds it; that is fine
        }
    }

    /**
     * Waits until no copy of the application holds its lock, i.e. it has exited.
     *
     * @return true if it exited, false if it was still running after {@code maxMs}
     */
    public boolean waitForAppExit(long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        while (true) {
            FileChannel ch = null;
            try {
                Files.createDirectories(updateDir());
                ch = FileChannel.open(updateDir().resolve("app.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock l = ch.tryLock();
                if (l != null) {
                    l.release();
                    return true;
                }
            } catch (OverlappingFileLockException e) {
                return true; // held by this very JVM, which is not the app
            } catch (IOException ignored) {
                // try again
            } finally {
                Io.closeQuietly(ch);
            }
            if (System.currentTimeMillis() > end) {
                return false;
            }
            Io.sleep(200);
        }
    }

    /** Locks this JVM holds, so code that already holds one can take it again. */
    private static final java.util.Map<Path, int[]> HELD = new java.util.HashMap<Path, int[]>();
    private static final java.util.Map<Path, Closeable> RELEASE = new java.util.HashMap<Path, Closeable>();

    /**
     * Cross-process lock so two launchers never update the same folder at once. Taking it
     * again in the same JVM (the launcher around the whole update, the update itself
     * inside) simply nests.
     */
    public Closeable lock() throws IOException {
        final Path key = updateDir().resolve("update.lock");
        synchronized (HELD) {
            int[] n = HELD.get(key);
            if (n != null) {
                n[0]++;
                return nested(key);
            }
        }
        Files.createDirectories(updateDir());
        // A leading dot hides a folder on Linux and macOS but not on Windows.
        try {
            Files.setAttribute(updateDir(), "dos:hidden", Boolean.TRUE);
        } catch (Exception ignored) {
            // not a Windows file system; the dot already does the job
        }
        final FileChannel channel = FileChannel.open(key,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        final FileLock fileLock;
        try {
            fileLock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            Io.closeQuietly(channel);
            throw new IOException("Another update is already running");
        } catch (IOException e) {
            Io.closeQuietly(channel);
            String m = String.valueOf(e.getMessage()).toLowerCase();
            if (m.contains("no locks available") || m.contains("not supported")
                    || m.contains("function not implemented")) {
                // A network or special drive without file locking: go on without one
                // rather than never updating at all.
                return new Closeable() {
                    @Override
                    public void close() {
                    }
                };
            }
            throw e;
        } catch (RuntimeException e) {
            Io.closeQuietly(channel);
            throw e;
        }
        if (fileLock == null) {
            Io.closeQuietly(channel);
            throw new IOException("Another copy of the launcher is updating this folder");
        }
        synchronized (HELD) {
            HELD.put(key, new int[] {1});
            RELEASE.put(key, new Closeable() {
                @Override
                public void close() {
                    try {
                        fileLock.release();
                    } catch (IOException ignored) {
                        // already gone
                    }
                    Io.closeQuietly(channel);
                }
            });
        }
        return nested(key);
    }

    private static Closeable nested(final Path key) {
        return new Closeable() {
            private boolean closed;

            @Override
            public void close() {
                synchronized (HELD) {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    int[] n = HELD.get(key);
                    if (n != null && --n[0] == 0) {
                        HELD.remove(key);
                        Io.closeQuietly(RELEASE.remove(key));
                    }
                }
            }
        };
    }

    // --------------------------------------------------------------------- helpers

    /**
     * Every published file is treated alike: delivered whenever the publisher's version of
     * it changes. There is no "settings file" exception any more; a file the publisher
     * does not want sent is simply left unticked. The {@code |seed} marker that releases
     * published for a short while carry is ignored.
     */
    static boolean isSeed(UpdateManifest.FileEntry e) {
        return false;
    }

    /** Moves a file that came from staging back there; anything else is removed. */
    private void parkInStaging(Path target, Path stagedPath) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        if (Files.isDirectory(stagedPath.getParent() == null ? stagedPath : stagedPath.getParent())
                || Files.isDirectory(updateDir().resolve("staging"))) {
            Io.ensureParent(stagedPath);
            Files.move(target, stagedPath, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(target);
        }
    }

    private void moveIntoBackup(String path) throws IOException {
        Path saved = backup(path);
        Io.ensureParent(saved);
        Files.move(file(path), saved, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * On the very first install into a folder that already holds an older copy of the
     * application (one installed by getdown, say), there is no record of what it owns.
     * The one thing that is certainly stale is a jar in a library folder this release
     * also uses but does not list: it is moved out of the way, into backup.
     */
    private Set<String> leftoverJars(UpdateManifest release) {
        Set<String> dirs = new HashSet<String>();
        for (UpdateManifest.FileEntry e : release.classpathEntries()) {
            int slash = e.path.lastIndexOf('/');
            if (slash > 0) {
                dirs.add(e.path.substring(0, slash));
            }
        }
        Set<String> out = new LinkedHashSet<String>();
        for (String d : dirs) {
            java.io.File[] files = appDir.resolve(d).toFile().listFiles();
            if (files == null) {
                continue;
            }
            for (java.io.File f : files) {
                String rel = d + "/" + f.getName();
                if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")
                        && !release.entries().containsKey(rel)) {
                    out.add(rel);
                }
            }
        }
        return out;
    }

    private static String describeSwapFailure(IOException e) {
        String msg = String.valueOf(e.getMessage());
        if (e instanceof java.nio.file.FileSystemException
                || msg.contains("being used by another process") || msg.contains("Access is denied")) {
            return "Some files are in use, probably because the application is still open."
                    + " Close it and start again to finish the update. (" + msg + ")";
        }
        return "The update could not be put in place: " + msg;
    }

    /**
     * Removes folders an update left empty (a dropped library's folder), and only those:
     * empty folders the customer or the application made are none of the updater's business.
     */
    private void pruneEmptied(Set<Path> dirs) {
        for (Path d : dirs) {
            Path p = d;
            while (p != null && p.startsWith(appDir) && !p.equals(appDir)
                    && !p.equals(updateDir()) && !Files.isSymbolicLink(p)) {
                String[] left = p.toFile().list();
                if (left == null || left.length > 0 || !p.toFile().delete()) {
                    break;
                }
                p = p.getParent();
            }
        }
    }

    /**
     * Removes the versions/ layout used by the earliest test builds of this updater, but
     * only in a folder that really has it: folders called "cache" or "staging" can just as
     * well be the application's own.
     */
    private void removeLegacyLayout() {
        boolean legacy = Files.isRegularFile(appDir.resolve("update-state.properties"))
                || Files.isDirectory(appDir.resolve("versions"))
                        && Files.isRegularFile(appDir.resolve("current"));
        if (!legacy) {
            return;
        }
        for (String name : new String[] {"versions", "staging", "cache", "current", "previous",
            "update-state.properties", "update.lock"}) {
            Io.deleteQuietly(appDir.resolve(name));
        }
    }

    /** The record of an update being applied, written before each step. */
    private static final class Journal {

        String state;
        final String version;
        final String previous;
        private final Properties props = new Properties();
        private InstallStore store;
        private int count;

        Journal(String state, String version, String previous) {
            this.state = state;
            this.version = version;
            this.previous = previous;
        }

        static Journal load(InstallStore s) {
            Properties p = Io.loadProps(s.updateDir().resolve(JOURNAL));
            if (p.getProperty("state") == null) {
                return null;
            }
            Journal j = new Journal(p.getProperty("state"), p.getProperty("version", ""),
                    p.getProperty("previous", ""));
            j.props.putAll(p);
            j.store = s;
            try {
                j.count = Math.max(0, Math.min(1000000, Integer.parseInt(p.getProperty("count", "0"))));
            } catch (NumberFormatException e) {
                return null;
            }
            return j;
        }

        List<String> ops() {
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < count; i++) {
                String op = props.getProperty("op." + i);
                if (op != null) {
                    out.add(op);
                }
            }
            return out;
        }

        void record(String kind, String path) throws IOException {
            props.setProperty("op." + count, kind + ":" + path);
            count++;
            save();
            // Test hook: stop the JVM dead after the n-th recorded step, exactly as a power
            // cut would, so recovery can be proven rather than assumed. Unset in normal use.
            String halt = System.getProperty("update.test.haltAfterOps");
            if (halt != null && count >= Integer.parseInt(halt)) {
                Runtime.getRuntime().halt(9);
            }
        }

        void save() throws IOException {
            props.setProperty("state", state);
            props.setProperty("version", version);
            props.setProperty("previous", previous);
            props.setProperty("count", String.valueOf(count));
            Io.storeProps(target().updateDir().resolve(JOURNAL), props);
        }

        private InstallStore target() {
            return store;
        }

        Journal bind(InstallStore s) {
            this.store = s;
            return this;
        }
    }

    // Journal needs its store; created through here so it is never left unbound.
    private Journal newJournal(String state, String version, String previous) {
        return new Journal(state, version, previous).bind(this);
    }
}
