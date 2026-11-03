package org.hp.update;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * The updater, embedded in the application. Put one line at the very top of your
 * {@code main}:
 *
 * <pre>
 * public static void main(String[] args) {
 *     SelfUpdate.ensureUpToDate(args);
 *     ... your normal startup ...
 * }
 * </pre>
 *
 * However the application is started - GasX.jar double-clicked, an old .exe, a shortcut,
 * {@code java -jar} - it checks first:
 *
 * <ul>
 *   <li><b>Up to date</b>, or the server cannot be reached and offline starts are allowed:
 *       this method returns and the application starts normally, in the same JVM, as fast
 *       as before.</li>
 *   <li><b>An update is needed</b>: a copy of the updater is placed in {@code .update},
 *       started, and this JVM exits. The updater waits for it to close (Windows will not
 *       replace open files), downloads and installs the new version with a progress
 *       window, then starts the application again - now up to date, so this method simply
 *       returns.</li>
 * </ul>
 *
 * <p>When the application runs from NetBeans or anywhere without an {@code app.txt}, this
 * does nothing at all.</p>
 *
 * <p>For applications that stay open all day, also call {@link #watch(Window)} once the
 * main window is showing: it looks for new releases while the application runs and asks
 * the user to restart when one arrives.</p>
 */
public final class SelfUpdate {

    private static volatile String[] originalArgs = new String[0];
    private static volatile UpdateConfig config;
    private static volatile Callable<Boolean> beforeRestart;
    private static volatile boolean noticeShowing;
    /** The version this running copy of the application is: what watch() compares with. */
    private static volatile String runningVersion;
    private static final java.util.concurrent.atomic.AtomicBoolean WATCHING =
            new java.util.concurrent.atomic.AtomicBoolean();

    private SelfUpdate() {
    }

    /**
     * Makes sure the application is the version the server requires. Returns normally
     * when the application should carry on starting; otherwise hands over to the updater
     * and ends this JVM.
     */
    public static void ensureUpToDate(String[] args) {
        originalArgs = args == null ? new String[0] : args.clone();
        try {
            check();
        } catch (Throwable t) {
            // A fault in the updater itself must never be what stops the application.
            System.err.println("[updater] update check skipped: " + t);
        }
    }

    /**
     * Called before "Restart now" closes the application. Return false to cancel, for
     * example when the user has unsaved work and chooses not to lose it.
     */
    public static void setBeforeRestart(Callable<Boolean> check) {
        beforeRestart = check;
    }

    /** The update settings in use, or null when updating is not active. */
    public static UpdateConfig config() {
        return config;
    }

    // ---------------------------------------------------------------- startup

    private static void check() throws IOException {
        Path appDir = findAppDir();
        if (appDir == null) {
            return; // running from the IDE or a folder without app.txt: not managed
        }
        UpdateConfig cfg = UpdateConfig.load(appDir);
        config = cfg;
        InstallStore store = new InstallStore(appDir);
        store.holdWhileRunning();
        runningVersion = store.installedVersion();

        // Started by our own launcher, which has just made sure everything is current.
        if (System.getProperty("update.version") != null) {
            runningVersion = System.getProperty("update.version");
            detachFromLauncher(cfg, Boolean.getBoolean("update.detach"));
            return;
        }

        String reason = whyUpdaterIsNeeded(cfg, store);
        if (reason == null) {
            reason = whyPluginsNeedUpdater(cfg);
        }
        if (reason == null) {
            System.setProperty("update.plugins.checked", "true"); // PluginUpdate need not look again
            return;
        }
        log("handing over to the updater: " + reason);
        handOver(cfg, store, originalArgs);
        System.exit(0);
    }

    /**
     * Tells the launcher that the application is running, so it can close and leave just
     * the application in the taskbar. Its output can no longer go through the launcher,
     * so from here on it is written straight to logs/app.log.
     */
    private static void detachFromLauncher(UpdateConfig cfg, boolean detach) {
        java.io.PrintStream toLauncher = System.out;
        if (!detach) {
            // The launcher keeps showing the output (console mode); it only needs to know
            // that the application got this far.
            toLauncher.println(Relauncher.STARTED_MARKER);
            toLauncher.flush();
            return;
        }
        try {
            Path log = Relauncher.appLog(cfg);
            Io.ensureParent(log);
            java.io.PrintStream file = new java.io.PrintStream(
                    new java.io.FileOutputStream(log.toFile(), true), true, "UTF-8");
            System.setOut(file);
            System.setErr(file);
        } catch (IOException e) {
            // no log file then; the application still starts
        }
        toLauncher.println(Relauncher.STARTED_MARKER);
        toLauncher.flush();
    }

    /** Null when the application may start right away; otherwise the reason it may not. */
    private static String whyUpdaterIsNeeded(UpdateConfig cfg, InstallStore store) {
        if (store.installed() == null) {
            // A copy of dist set up by hand: the updater has to adopt it - but if the
            // server cannot be reached, the files that are here can still be used.
            if (cfg.allowOffline() && !serverReachable(cfg)) {
                log("not set up yet and the update server cannot be reached: starting as is");
                return null;
            }
            return "this folder has not been set up by the updater yet";
        }
        if (store.hasJournal()) {
            return "an update was interrupted or has not been confirmed";
        }
        List<String> missing = store.missingFiles();
        if (!missing.isEmpty()) {
            return "files of version " + store.installedVersion() + " are missing or damaged: "
                    + (missing.size() <= 3 ? missing.toString() : missing.subList(0, 3) + " and "
                    + (missing.size() - 3) + " more");
        }
        UpdateManifest staged = store.stagedRelease();
        if (staged != null && !staged.version().equals(store.installedVersion())) {
            return "update " + staged.version() + " is downloaded and waiting";
        }
        if (!cfg.checkOnStart()) {
            return null;
        }
        Splash splash = Splash.showSoon(cfg);
        try {
            UpdateManifest remote = new UpdateManager(cfg, UpdateListener.NOOP)
                    .fetchRemoteManifest(cfg.checkAttempts());
            if (remote.version().equals(store.installedVersion())) {
                return null;
            }
            if (!cfg.mandatory() && remote.version().equals(store.failedVersion())) {
                return null;
            }
            return "version " + remote.version() + " is available";
        } catch (IOException e) {
            // The server cannot be reached, so nobody can know whether an update exists.
            return cfg.allowOffline() ? null : "the update server could not be reached";
        } finally {
            splash.close();
        }
    }

    /**
     * Plugins (setapp.txt): a downloaded release not yet in lib, or a newer one on the
     * server. Either way the updater does it while this application is closed.
     */
    private static String whyPluginsNeedUpdater(UpdateConfig cfg) {
        for (PluginSet p : PluginSet.all(cfg.appDir())) {
            boolean ours = PluginSet.sameAddress(p.storeAddress(), p.address());
            if (ours && p.storeIntact() && !p.inPlace()) {
                return "a new " + p.name + " is downloaded and waiting";
            }
            if (!cfg.checkOnStart()) {
                continue;
            }
            try {
                UpdateManager mgr = p.manager(p.address(), cfg, UpdateListener.NOOP);
                UpdateManifest remote = mgr.fetchRemoteManifest(cfg.checkAttempts());
                UpdateManifest have = p.store().installed();
                if (have == null || !ours || !remote.version().equals(have.version()) || !p.inPlace()) {
                    return p.name + " " + remote.version() + " is available";
                }
            } catch (Exception e) {
                // offline or a wrong address: the plugin in place is used; the login says more
            }
        }
        return null;
    }

    /**
     * Restarts the application through the updater right now, from any thread, and never
     * returns. Used when a plugin has to be put in place: unlike {@link #restartNow} there
     * is nothing to ask, the user has just logged in.
     */
    static void restartAndExit(UpdateConfig cfg, Path updater) throws IOException {
        startUpdater(cfg, updater, originalArgs);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                for (Window w : Window.getWindows()) {
                    w.dispose();
                }
            }
        });
        Thread exit = new Thread("update-restart") {
            @Override
            public void run() {
                System.exit(0);
            }
        };
        exit.start();
        // A shutdown hook of the application that waits forever must not keep the files open.
        Thread halt = new Thread("update-restart-halt") {
            @Override
            public void run() {
                Io.sleep(15000);
                Runtime.getRuntime().halt(0);
            }
        };
        halt.setDaemon(true);
        halt.start();
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                // keep waiting for the exit
            }
        }
    }

    /**
     * Everything a restart needs that can fail, done before anything is changed: the
     * updater copied out, and this copy of the application known to be the only one (the
     * updater waits for it; a second copy would keep lib/set.jar open).
     */
    static Path prepareRestart(UpdateConfig cfg) throws IOException {
        InstallStore store = new InstallStore(cfg.appDir());
        store.holdWhileRunning();
        if (!InstallStore.holdsAppLock()) {
            throw new IOException("another copy of " + cfg.displayName() + " is open. Close it,"
                    + " then start " + cfg.displayName() + " again");
        }
        return extractUpdater(store);
    }

    private static boolean serverReachable(UpdateConfig cfg) {
        Splash splash = Splash.showSoon(cfg);
        try {
            new UpdateManager(cfg, UpdateListener.NOOP).fetchRemoteManifest(cfg.checkAttempts());
            return true;
        } catch (IOException e) {
            return !(e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
                    || e instanceof java.net.SocketTimeoutException
                    || e instanceof java.net.NoRouteToHostException);
        } finally {
            splash.close();
        }
    }

    // --------------------------------------------------------------- while running

    /**
     * Looks for new releases while the application is open, every {@code recheck-minutes}
     * (30 by default), and asks the user to restart when one is published. With
     * mandatory updates the request comes back until they do. Calling it again does
     * nothing; the window is only remembered loosely, so closing it does not keep it alive.
     */
    public static void watch(Window owner) {
        final UpdateConfig cfg = config;
        // update.test.recheckMs exists only so this can be tested in seconds
        final long intervalMs = Long.getLong("update.test.recheckMs", cfg == null ? 0L
                : cfg.recheckMinutes() * 60000L).longValue();
        if (cfg == null || intervalMs <= 0 || GraphicsEnvironment.isHeadless()) {
            return;
        }
        if (!WATCHING.compareAndSet(false, true)) {
            return; // already watching
        }
        final java.lang.ref.WeakReference<Window> ownerRef = new java.lang.ref.WeakReference<Window>(owner);
        final InstallStore store = new InstallStore(cfg.appDir());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        return; // asked to stop
                    }
                    if (noticeShowing) {
                        continue;
                    }
                    try {
                        UpdateManifest remote = new UpdateManager(cfg, UpdateListener.NOOP)
                                .fetchRemoteManifest(cfg.checkAttempts());
                        String mine = runningVersion != null ? runningVersion : store.installedVersion();
                        if (mine != null && !remote.version().equals(mine)
                                && (cfg.mandatory() || !remote.version().equals(store.failedVersion()))) {
                            showRestartNotice(ownerRef, cfg, remote);
                        }
                    } catch (IOException ignored) {
                        // offline for now; try again next time round
                    } catch (Throwable unexpected) {
                        // Whatever went wrong, the next check must still happen.
                        System.err.println("[updater] update check failed: " + unexpected);
                    }
                }
            }
        }, "update-watch");
        t.setDaemon(true);
        t.start();
    }

    private static void showRestartNotice(final java.lang.ref.WeakReference<Window> ownerRef,
            final UpdateConfig cfg, final UpdateManifest remote) {
        noticeShowing = true;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    RestartNotice.show(usableOwner(ownerRef.get()), cfg, remote, new Runnable() {
                        @Override
                        public void run() {
                            restartNow();
                        }
                    }, new Runnable() {
                        @Override
                        public void run() {
                            noticeShowing = false;
                        }
                    });
                } catch (Throwable t) {
                    noticeShowing = false; // so the next check can try again
                    System.err.println("[updater] could not show the update notice: " + t);
                }
            }
        });
    }

    /** The window to attach the notice to: the given one if it is still a usable frame. */
    private static Window usableOwner(Window w) {
        if ((w instanceof java.awt.Frame || w instanceof java.awt.Dialog) && w.isDisplayable()) {
            return w;
        }
        for (java.awt.Frame f : java.awt.Frame.getFrames()) {
            if (f.isVisible()) {
                return f;
            }
        }
        return null;
    }

    /**
     * Closes the application and restarts it through the updater, which installs the new
     * release on the way. Asks {@link #setBeforeRestart} first - only once the updater is
     * ready to start, so a failure here never leaves the application half shut down.
     */
    public static void restartNow() {
        final UpdateConfig cfg = config;
        if (cfg == null) {
            return;
        }
        final InstallStore store = new InstallStore(cfg.appDir());
        final Path updater;
        try {
            updater = extractUpdater(store);
        } catch (Exception e) {
            noticeShowing = false;
            restartFailed(e);
            return;
        }
        try {
            Callable<Boolean> ok = beforeRestart;
            if (ok != null && !Boolean.TRUE.equals(ok.call())) {
                noticeShowing = false;
                return;
            }
        } catch (Exception e) {
            noticeShowing = false;
            System.err.println("[updater] restart cancelled by the application: " + e);
            return;
        }
        for (Window w : Window.getWindows()) {
            w.dispose();
        }
        // Off the Swing thread: an application shutdown hook that waits for Swing would
        // otherwise freeze the exit, with the application still holding its files.
        Thread exit = new Thread("update-restart") {
            @Override
            public void run() {
                try {
                    startUpdater(cfg, updater, originalArgs);
                } catch (Exception e) {
                    System.err.println("[updater] restart failed: " + e);
                }
                System.exit(0);
            }
        };
        exit.start();
    }

    private static void restartFailed(Exception e) {
        System.err.println("[updater] restart failed: " + e);
        try {
            javax.swing.JOptionPane.showMessageDialog(null,
                    "The update could not be started:\n" + e.getMessage()
                    + "\n\nClose and reopen the application to install it.",
                    "Update", javax.swing.JOptionPane.WARNING_MESSAGE);
        } catch (Throwable ignored) {
            // no display: the message above is in the log
        }
    }

    // ------------------------------------------------------------------ hand-over

    /**
     * Starts the updater from a copy in {@code .update}, never from a jar that is about to
     * be replaced. It waits for this application to exit before touching anything.
     */
    static void handOver(UpdateConfig cfg, InstallStore store, String[] args) throws IOException {
        startUpdater(cfg, extractUpdater(store), args);
    }

    private static void startUpdater(UpdateConfig cfg, Path updaterJar, String[] args)
            throws IOException {
        List<String> cmd = new ArrayList<String>();
        cmd.add(Relauncher.javaExecutable(!GraphicsEnvironment.isHeadless(), null));
        cmd.add("-cp");
        cmd.add(updaterJar.toString());
        cmd.add("org.hp.update.Bootstrap");
        cmd.add(cfg.appDir().toString());
        cmd.add("--wait-for-exit");
        if (GraphicsEnvironment.isHeadless()) {
            cmd.add("--console");
        }
        for (String a : args) {
            cmd.add(a);
        }
        Path log = cfg.appDir().resolve("logs").resolve("handover.log");
        Io.ensureParent(log);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cfg.appDir().toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
        pb.start();
    }

    /** Copies the org.hp.update classes out of whichever jar holds them. */
    private static Path extractUpdater(InstallStore store) throws IOException {
        Path source;
        try {
            source = Paths.get(SelfUpdate.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (Exception e) {
            throw new IOException("Cannot locate the updater classes", e);
        }
        Files.createDirectories(store.updateDir());
        // Copies made while an older updater was still running from updater.jar: remove
        // those no longer in use (one still running cannot be deleted on Windows; fine).
        java.io.File[] old = store.updateDir().toFile().listFiles();
        if (old != null) {
            for (java.io.File f : old) {
                if (f.getName().matches("updater-\\d+\\.jar|updater\\.jar\\.tmp")) {
                    f.delete();
                }
            }
        }
        Path target = store.updateDir().resolve("updater.jar");
        Path tmp = store.updateDir().resolve("updater.jar.tmp");

        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "org.hp.update.Bootstrap");
        JarOutputStream out = new JarOutputStream(Files.newOutputStream(tmp), mf);
        try {
            if (Files.isDirectory(source)) {
                Path root = source.resolve("org/hp/update");
                for (Path f : Io.listFilesRecursively(root)) {
                    out.putNextEntry(new ZipEntry("org/hp/update/" + Io.relative(root, f)));
                    Files.copy(f, out);
                    out.closeEntry();
                }
            } else {
                ZipInputStream in = new ZipInputStream(Files.newInputStream(source));
                try {
                    ZipEntry e;
                    while ((e = in.getNextEntry()) != null) {
                        if (!e.isDirectory() && e.getName().startsWith("org/hp/update/")) {
                            copyEntry(out, e.getName(), new NonClosing(in));
                        }
                    }
                } finally {
                    Io.closeQuietly(in);
                }
            }
        } catch (IOException e) {
            Io.closeQuietly(out);
            Io.deleteQuietly(tmp);
            throw e;
        } finally {
            Io.closeQuietly(out);
        }
        try {
            Io.moveAtomic(tmp, target);
            return target;
        } catch (IOException inUse) {
            // An earlier updater may still be running from it; use a fresh name.
            Path alt = store.updateDir().resolve("updater-" + System.currentTimeMillis() + ".jar");
            Files.move(tmp, alt);
            return alt;
        }
    }

    private static void copyEntry(JarOutputStream out, String name, InputStream in) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        out.closeEntry();
    }

    /** Lets a ZipInputStream entry be copied without closing the whole archive. */
    private static final class NonClosing extends InputStream {
        private final InputStream in;

        NonClosing(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, len);
        }

        @Override
        public void close() {
            // leave the archive open
        }
    }

    // ------------------------------------------------------------------- helpers

    /**
     * The application folder: the one holding app.txt. Looked for where the launcher said,
     * in the working folder, next to the jar this class came from, and one level up from
     * it (for an updater kept in lib\).
     */
    /** The installed application's folder, or null when running from NetBeans or dist. */
    static Path appDir() {
        return findAppDir();
    }

    private static Path findAppDir() {
        List<Path> candidates = new ArrayList<Path>();
        String fromLauncher = System.getProperty("update.appdir");
        if (fromLauncher != null) {
            candidates.add(Paths.get(fromLauncher));
        }
        // Where the application's own jars are - never the working directory, which in
        // NetBeans is your project folder.
        try {
            Path jar = Paths.get(SelfUpdate.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            if (!Files.isRegularFile(jar)) {
                return fromLauncher != null ? installFolder(Paths.get(fromLauncher)) : null;
            }
            Path dir = jar.getParent();
            if (dir != null) {
                candidates.add(dir);
                if (dir.getParent() != null) {
                    candidates.add(dir.getParent());
                }
            }
        } catch (Exception ignored) {
            // only what the launcher said, then
        }
        for (Path c : candidates) {
            Path found = installFolder(c);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The folder, if it is an installed copy of the application: one the updater already
     * manages, or one with a PC's app.txt. A build configuration (it has a "class" line,
     * as the publisher writes into dist) is not an installation, so running from dist or
     * from NetBeans never updates anything.
     */
    private static Path installFolder(Path c) {
        Path dir = c.toAbsolutePath().normalize();
        if (Files.isRegularFile(dir.resolve(InstallStore.UPDATE_DIR).resolve(InstallStore.INSTALLED))) {
            return dir;
        }
        String text = Io.readString(dir.resolve(UpdateConfig.APP_TXT));
        if (text == null || !text.contains("appbase")) {
            return null;
        }
        for (String line : text.split("\n")) {
            String t = line.trim().toLowerCase();
            if (t.startsWith("class") && t.substring(5).trim().startsWith("=")) {
                return null;
            }
        }
        return dir;
    }

    private static void log(String msg) {
        System.out.println("[updater] " + msg);
    }

    /** A small "checking for updates" window, shown only if the check is not instant. */
    private static final class Splash {
        private volatile boolean done;
        private volatile JWindow window;

        static Splash showSoon(final UpdateConfig cfg) {
            final Splash s = new Splash();
            if (GraphicsEnvironment.isHeadless()) {
                return s;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    Io.sleep(700); // a quick check should not flash a window at anyone
                    if (s.done) {
                        return;
                    }
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            if (s.done) {
                                return;
                            }
                            JWindow w = new JWindow();
                            JPanel p = new JPanel(new BorderLayout(0, 8));
                            p.setBorder(BorderFactory.createCompoundBorder(
                                    BorderFactory.createLineBorder(new java.awt.Color(0xBBBBBB)),
                                    BorderFactory.createEmptyBorder(14, 18, 14, 18)));
                            JLabel l = new JLabel(cfg.displayName() + ": checking for updates...");
                            l.setFont(l.getFont().deriveFont(Font.PLAIN, l.getFont().getSize2D() + 1f));
                            JProgressBar bar = new JProgressBar();
                            bar.setIndeterminate(true);
                            p.add(l, BorderLayout.NORTH);
                            p.add(bar, BorderLayout.CENTER);
                            w.setContentPane(p);
                            w.pack();
                            w.setSize(Math.max(320, w.getWidth()), w.getHeight());
                            w.setLocationRelativeTo(null);
                            w.setVisible(true);
                            s.window = w;
                        }
                    });
                }
            }, "update-splash");
            t.setDaemon(true);
            t.start();
            return s;
        }

        void close() {
            done = true;
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    if (window != null) {
                        window.dispose();
                    }
                }
            });
        }
    }

    /** For the file system path of an installed file, from inside the application. */
    public static File appFile(String relativePath) {
        UpdateConfig cfg = config;
        return cfg == null ? new File(relativePath) : cfg.appDir().resolve(relativePath).toFile();
    }
}
