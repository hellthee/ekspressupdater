package org.hp.update;

import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Main-Class of {@code launcher.jar}. Give it an application folder and it updates and
 * starts that application, exactly as getdown takes an appdir:
 *
 * <pre>
 * java -jar launcher.jar C:\Apps\GasX
 * java -jar launcher.jar C:\Apps\ERPX
 * </pre>
 *
 * <p>A window appears straight away showing what is happening: the update check, the
 * download, the command used to start the application and the application's own output.
 * It closes itself once the application is up, and stays on screen with the details if
 * anything goes wrong.</p>
 *
 * <p>Options: {@code --no-update}, {@code --repair}, {@code --console} (no window).
 * Anything else is passed on to your application.</p>
 */
public final class Bootstrap {

    private static Path logFile;

    public static void main(String[] args) {
        hideFromDock();
        boolean gui = !GraphicsEnvironment.isHeadless();

        Path appDir = null;
        Boolean checkOverride = null;
        boolean repair = false;
        boolean waitForExit = false;
        List<String> appArgs = new ArrayList<String>();

        for (String a : args) {
            if ("--no-update".equals(a)) {
                checkOverride = Boolean.FALSE;
            } else if ("--update".equals(a)) {
                checkOverride = Boolean.TRUE;
            } else if ("--repair".equals(a)) {
                repair = true;
            } else if ("--console".equals(a)) {
                gui = false;
            } else if ("--wait-for-exit".equals(a)) {
                waitForExit = true;
            } else if (appDir == null && !a.startsWith("--") && new File(a).isDirectory()) {
                appDir = Paths.get(a);
            } else {
                appArgs.add(a);
            }
        }
        if (gui && startedInConsoleWindow() && restartWithoutConsole(args)) {
            System.exit(0);
        }
        if (appDir == null) {
            String prop = System.getProperty("update.appdir");
            appDir = prop != null ? Paths.get(prop) : UpdateConfig.launcherDir();
        }
        appDir = appDir.toAbsolutePath().normalize();

        UpdateConfig cfg = null;
        LauncherUi ui = null;
        try {
            if (!Files.isRegularFile(appDir.resolve(UpdateConfig.APP_TXT))) {
                throw new IOException("There is no " + UpdateConfig.APP_TXT + " in\n" + appDir
                        + "\n\nIt needs two lines:\n"
                        + "    appbase = http://your.server/apps/yourapp/\n"
                        + "    ui.name = Your App\n\n"
                        + "Or start the launcher with the application folder:\n"
                        + "    java -jar launcher.jar <folder>");
            }
            cfg = UpdateConfig.load(appDir);
            if (checkOverride != null) {
                cfg.setCheckOnStart(checkOverride.booleanValue());
            }
            logFile = appDir.resolve("logs").resolve("launcher.log");

            ui = gui ? LauncherWindow.show(cfg) : new ConsoleUi();
            if (ui == null) {
                ui = new ConsoleUi();
            }
            describeEnvironment(ui, cfg, appDir);
            if (waitForExit) {
                // Started by the application itself, which is closing so that its files can
                // be replaced. Nothing may be touched until it has gone.
                ui.stage("Preparing the update");
                InstallStore store = new InstallStore(appDir);
                if (!store.waitForAppExit(30000)) {
                    // Its files are in use: nothing may be replaced until every copy is
                    // closed. Wait for that, however long it takes; the window says why.
                    ui.stage("Waiting for every copy of " + cfg.displayName() + " to close");
                    ui.detail("Another copy of " + cfg.displayName() + " is still open on this"
                            + " computer. The update continues as soon as it is closed.");
                    while (!store.waitForAppExit(60000)) {
                        log("still waiting for " + cfg.displayName() + " to close");
                    }
                }
            }
            System.exit(run(cfg, ui, gui, repair, appArgs.toArray(new String[0])));

        } catch (Throwable t) {
            // Nothing may escape silently: this is the case the user was left guessing about.
            String headline = t.getMessage() == null ? t.toString() : t.getMessage();
            log("FAILED " + headline);
            if (ui == null) {
                ui = gui && cfg != null ? LauncherWindow.show(cfg) : new ConsoleUi();
                if (ui == null) {
                    ui = new ConsoleUi();
                }
            }
            ui.failed("Could not start", headline + "\n\n" + stackTrace(t));
            ui.awaitClose();
            System.exit(2);
        }
    }

    /**
     * On macOS every Java process that opens a window gets its own icon in the Dock, and
     * keeps it until the process ends - hiding the window does not remove it. Running as
     * a background ("UI element") app, the launcher can still show its window but never
     * has a Dock icon, so the only Java icon is the application's. Must happen before
     * anything touches AWT.
     */
    private static void hideFromDock() {
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")
                && System.getProperty("apple.awt.UIElement") == null) {
            System.setProperty("apple.awt.UIElement", "true");
        }
    }

    /**
     * True when Windows gave this launcher a console window: started with java.exe from a
     * shortcut or a .bat file rather than with javaw.exe or a double-click. That window
     * is a second Java icon in the taskbar, next to the application's.
     */
    private static boolean startedInConsoleWindow() {
        if (!Relauncher.isWindows() || System.getProperty("update.relaunched") != null) {
            return false;
        }
        java.io.Console c = System.console();
        if (c == null) {
            return false;
        }
        try {
            // Java 22 and later return a Console even without a terminal; ask it directly
            Object terminal = java.io.Console.class.getMethod("isTerminal").invoke(c);
            return Boolean.TRUE.equals(terminal);
        } catch (NoSuchMethodException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Starts this launcher again under javaw.exe, which has no console window, so that
     * the console can close. False if that is not possible; the launcher then simply
     * carries on where it is.
     */
    private static boolean restartWithoutConsole(String[] args) {
        try {
            String jar = launcherPath();
            File javaw = new File(new File(System.getProperty("java.home"), "bin"), "javaw.exe");
            if (jar == null || !javaw.isFile()) {
                return false;
            }
            List<String> cmd = new ArrayList<String>();
            cmd.add(javaw.getPath());
            cmd.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
            cmd.add("-Dupdate.relaunched=true");
            cmd.add("-cp");
            cmd.add(jar);
            cmd.add(Bootstrap.class.getName());
            for (String a : args) {
                cmd.add(a);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(new File("NUL"));
            pb.redirectError(new File("NUL"));
            pb.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** First lines in the window: everything needed to understand a failure. */
    private static void describeEnvironment(LauncherUi ui, UpdateConfig cfg, Path appDir) {
        ui.detail("application folder : " + appDir);
        ui.detail("download url       : " + cfg.baseUrl());
        ui.detail("launcher java      : " + System.getProperty("java.version")
                + "  (" + System.getProperty("java.home") + ")");
        ui.detail("os                 : " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch"));
        ui.detail("");
    }

    // --------------------------------------------------------- update and launch

    private static int run(final UpdateConfig cfg, final LauncherUi ui, boolean gui, boolean repair,
            String[] appArgs) throws IOException, InterruptedException {

        final InstallStore store = new InstallStore(cfg.appDir());
        String launcherJar = launcherPath();
        if (launcherJar != null) {
            System.setProperty("update.launcher", launcherJar);
        }
        final UpdateManager mgr = new UpdateManager(cfg, ui.asListener());
        ui.onUserClose(new Runnable() {
            @Override
            public void run() {
                // Closing the window during an update means "quit". Nothing installed is
                // touched; what was downloaded so far is kept for next time.
                mgr.cancel();
                log("closed by the user during the update");
                System.exit(1);
            }
        });

        while (true) {
            // Only one updater at a time may touch this folder: a second launcher (a
            // double click) waits here instead of undoing the first one's work.
            java.io.Closeable lock = waitForLock(store, ui, cfg);
            Outcome o;
            try {
                // 1. An update cut off half way (power cut, killed process) is undone
                //    first, so the application folder is never left half old and half new.
                String interrupted = store.recover();
                if (interrupted != null) {
                    ui.detail("An update to " + interrupted + " was interrupted. The previous"
                            + " files were put back; it will be completed now.");
                }
                for (String restored : store.restoreMissingSettings()) {
                    ui.detail("Restored default " + restored);
                }
                o = update(cfg, ui, store, mgr);
                if (o == Outcome.READY) {
                    updatePlugins(cfg, ui); // the application is not running: lib can change
                }
            } finally {
                Io.closeQuietly(lock);
            }
            if (o == Outcome.READY) {
                break;
            }
            if (o == Outcome.STOP) {
                ui.awaitClose();
                return 2;
            }
            // RETRY: go round again
        }

        if (repair) {
            store.clearFailed();
            ui.stage("Repairing installation");
            ui.detail(String.valueOf(mgr.repair()));
        }
        return launch(cfg, store, ui, gui, appArgs);
    }

    private enum Outcome { READY, RETRY, STOP }

    /**
     * Plugins with a setapp.txt: a newer release is downloaded, and a downloaded one is put
     * in place in lib. Never stops the application from starting: at the login the
     * application finds out (PluginUpdate.ensure) and says what is wrong.
     */
    private static void updatePlugins(UpdateConfig cfg, final LauncherUi ui) {
        StringBuilder updated = new StringBuilder();
        UpdateListener toUi = new UpdateListener() {
            @Override
            public void onLog(String message) {
                ui.detail(message);
            }

            @Override
            public void onProgress(long done, long total, String file) {
                ui.progress(done, total, file);
            }

            @Override
            public void onFailed(String message, Throwable cause) {
                ui.detail(message + (cause == null ? "" : ": " + cause.getMessage()));
            }
        };
        for (PluginSet p : PluginSet.all(cfg.appDir())) {
            try {
                if (cfg.checkOnStart()) {
                    p.refresh(cfg, toUi);
                }
                if (!p.inPlace() && p.storeIntact()) {
                    ui.stage("Putting the " + p.name + " in place");
                    String done = p.activate();
                    if (done != null) {
                        ui.detail(done);
                        log(done);
                        updated.append(updated.length() == 0 ? "" : ",").append(p.name);
                    }
                }
            } catch (Exception e) {
                ui.detail("The " + p.name + " could not be updated: " + e.getMessage());
                log("plugin " + p.name + ": " + e.getMessage());
            }
        }
        System.setProperty("update.plugins.checked", "true");
        if (updated.length() > 0) {
            System.setProperty("update.plugins.updated", updated.toString());
        }
    }

    /** Takes the update lock, waiting while another updater holds it. */
    private static java.io.Closeable waitForLock(InstallStore store, LauncherUi ui,
            UpdateConfig cfg) {
        boolean told = false;
        while (true) {
            java.io.Closeable lock = store.tryLock();
            if (lock != null) {
                return lock;
            }
            if (!told) {
                ui.stage("Another update of " + cfg.displayName() + " is running; waiting for it");
                told = true;
            }
            Io.sleep(500);
        }
    }

    /**
     * Brings the application folder up to date, following the update policy:
     *
     * <ul>
     *   <li><b>Mandatory</b> (default): once the server offers a newer version the old one
     *       is never started. A failed or cancelled download stops with Retry.</li>
     *   <li><b>Optional</b> ({@code mandatory = false}): the old version starts whenever the
     *       new one cannot be fetched, and the user may skip a slow download.</li>
     *   <li>Server unreachable: nobody can know an update exists, so the installed version
     *       starts if {@code allow-offline} is on (the default, as in getdown).</li>
     * </ul>
     */
    private static Outcome update(final UpdateConfig cfg, final LauncherUi ui,
            final InstallStore store, final UpdateManager mgr) throws IOException {

        boolean mandatory = cfg.mandatory();
        boolean firstInstall = store.installed() == null;

        // A release downloaded earlier but not yet put in place goes in now, before the
        // application starts and while none of its files are open.
        UpdateManifest staged = store.stagedRelease();
        if (!firstInstall && staged != null
                && !staged.version().equals(store.installedVersion())
                && (mandatory || !staged.version().equals(store.failedVersion()))) {
            ui.stage("Installing update " + staged.version());
            UpdateManager.Result r = mgr.applyStaged(staged);
            ui.detail(String.valueOf(r));
            if (r.status == UpdateManager.Status.UPDATED) {
                cfg.writeInstalledVersion(staged.version());
            } else if (r.cause instanceof InstallStore.StagingDamaged
                    || r.cause != null && r.cause.getCause() instanceof InstallStore.StagingDamaged) {
                // Unusable as it is: forget it, and the normal check below fetches it again.
                ui.detail(r.message);
                store.discardStaging(staged.version());
            } else if (mandatory) {
                return ui.askRetry("Update to " + staged.version() + " could not be installed",
                        r.message) ? Outcome.RETRY : Outcome.STOP;
            }
        }

        if (!firstInstall && !cfg.checkOnStart()) {
            ui.detail("Update check skipped");
            return Outcome.READY;
        }

        // Ask the server. A dead or silent server answers with an exception.
        ui.stage(firstInstall ? "Installing " + cfg.displayName() : "Checking for updates");
        UpdateManifest remote;
        try {
            remote = firstInstall ? mgr.fetchRemoteManifest()
                    : mgr.fetchRemoteManifest(cfg.checkAttempts());
        } catch (IOException e) {
            ui.detail("Could not reach the update server: " + e.getMessage());
            if (firstInstall) {
                return ui.askRetry("Could not install " + cfg.displayName(),
                        "The update server could not be reached:\n" + e.getMessage()
                        + "\n\nCheck that this address opens in a browser:\n"
                        + cfg.manifestUrl()) ? Outcome.RETRY : Outcome.STOP;
            }
            if (cfg.allowOffline()) {
                ui.detail("Starting the installed version " + store.installedVersion() + ".");
                return Outcome.READY;
            }
            return ui.askRetry(cfg.displayName() + " needs to check for updates before it starts",
                    "The update server could not be reached:\n" + e.getMessage())
                    ? Outcome.RETRY : Outcome.STOP;
        }

        if (remote.version().equals(store.installedVersion())) {
            ui.detail("Up to date (version " + remote.version() + ")");
            return Outcome.READY;
        }
        if (!mandatory && remote.version().equals(store.failedVersion())) {
            ui.detail("Version " + remote.version() + " would not start on this computer last"
                    + " time, so it is skipped. Start with --repair to try again.");
            return Outcome.READY;
        }

        // A newer version exists. With mandatory updates it has to arrive before anything
        // starts: no skipping, and a slow connection is waited out rather than abandoned.
        ui.stage((firstInstall ? "Installing " : "Downloading update ") + remote.version());
        if (mandatory || firstInstall) {
            cfg.setMinDownloadKbps(0);
        } else {
            ui.offerSkip(new Runnable() {
                @Override
                public void run() {
                    mgr.cancel();
                }
            });
        }
        UpdateManager.Result r = mgr.install(remote);
        ui.hideSkip();

        if (r.status == UpdateManager.Status.UPDATED || r.status == UpdateManager.Status.UP_TO_DATE) {
            cfg.writeInstalledVersion(remote.version());
            ui.detail("Version " + remote.version() + " is installed");
            return Outcome.READY;
        }
        if (!mandatory && !firstInstall) {
            ui.detail(r.status == UpdateManager.Status.POSTPONED ? r.message : String.valueOf(r.message));
            ui.detail("What was downloaded is kept. Starting version "
                    + store.installedVersion() + ".");
            return Outcome.READY;
        }
        String why = r.message == null ? String.valueOf(r.status) : r.message;
        return ui.askRetry(cfg.displayName() + " " + remote.version() + " is required",
                "This update has to be installed before " + cfg.displayName() + " can start,"
                + " and it could not be completed:\n\n" + why
                + "\n\nWhat was downloaded so far is kept, so Retry continues from there.")
                ? Outcome.RETRY : Outcome.STOP;
    }

    /** Starts the application from its folder and watches only for a failure to start. */
    private static int launch(UpdateConfig cfg, InstallStore store, LauncherUi ui, boolean gui,
            String[] args) throws IOException, InterruptedException {

        String version = store.installedVersion();
        ui.stage("Starting " + cfg.displayName() + " " + version);

        String problem = Relauncher.startupProblem(cfg);
        if (problem != null) {
            return handleStartupFailure(cfg, store, ui, gui, args, problem, 3);
        }

        List<String> cmd = Relauncher.buildCommand(cfg, gui, args);
        ui.detail("java executable : " + cmd.get(0));
        ui.detail("classpath       : " + classpathSummary(cmd));
        ui.detail("working folder  : " + cfg.appDir());
        log("command: " + cmd);
        ui.detail("");

        Process p = Relauncher.launchPiped(cfg, cmd);
        OutputPump pump = new OutputPump(p, ui, Relauncher.appLog(cfg), gui);
        pump.start();
        ui.appStarted();

        if (pump.awaitStartOrExit()) {
            // Its main method is running: the update worked.
            confirm(cfg, store, version);
            if (gui) {
                // The launcher's job is done, so it closes and only the application stays
                // in the taskbar.
                log(cfg.displayName() + " " + version + " is running; launcher closing");
                ui.close();
                return 0;
            }
            int exit = p.waitFor(); // console: keep showing its output until it ends
            pump.join(2000);
            ui.close();
            return exit;
        }
        int exit = p.waitFor();
        pump.join(2000);

        // Only evidence counts: the Java runtime refusing to start it, or its main thread
        // dying for a missing class. An exit code alone proves nothing (Task Manager, a
        // crash late in the day).
        if (!pump.sawStarted() && pump.sawStartupFailure()) {
            return handleStartupFailure(cfg, store, ui, gui, args,
                    cfg.displayName() + " " + version + " could not be started."
                    + "\n\nThe output above is what the Java runtime reported.", exit);
        }

        // It ran. Anything after that is the application's business.
        confirm(cfg, store, version);
        ui.close();
        return exit;
    }

    /**
     * The application ran, so if this was the first run after an update the replaced
     * files are no longer needed.
     *
     * <p>Done under the update lock: if the application restarted itself to install a
     * newer release, another launcher may be putting that one in place right now, and
     * this one must not touch its journal or backup.</p>
     */
    private static void confirm(UpdateConfig cfg, InstallStore store, String version)
            throws IOException {
        java.io.Closeable lock = store.tryLock();
        if (lock == null) {
            return;
        }
        try {
            if (store.hasUnconfirmedUpdate() && version.equals(store.installedVersion())) {
                store.confirmUpdate();
                log("update to " + version + " confirmed");
            }
            if (store.installedVersion() != null) {
                cfg.writeInstalledVersion(store.installedVersion());
            }
        } finally {
            Io.closeQuietly(lock);
        }
    }

    /**
     * A version that cannot start right after an update gets the previous files back and
     * the previous version is started. Without a fresh update there is nothing to undo,
     * so the window explains instead.
     */
    private static int handleStartupFailure(UpdateConfig cfg, InstallStore store, LauncherUi ui,
            boolean gui, String[] args, String message, int exit)
            throws IOException, InterruptedException {

        String version = store.installedVersion();
        log("startup failure in " + version + ": " + message.replace("\n", " "));

        if (!store.hasUnconfirmedUpdate()) {
            ui.failed(cfg.displayName() + " could not start", message);
            ui.awaitClose();
            return exit == 0 ? 3 : exit;
        }

        String back;
        java.io.Closeable lock = waitForLock(store, ui, cfg);
        try {
            back = store.revert();
            store.markFailed(version);
        } finally {
            Io.closeQuietly(lock);
        }
        if (back != null && cfg.mandatory()) {
            // The previous files are back, so the folder is consistent, but with mandatory
            // updates the old version must not run: the database may already have been
            // changed for the new one.
            cfg.writeInstalledVersion(back);
            ui.failed(cfg.displayName() + " " + version + " could not start", message
                    + "\n\nThe update has been undone so nothing is left half installed,"
                    + " but because updates are mandatory version " + back + " is not started"
                    + " either.\n\nA corrected release is needed. Copy these details and send"
                    + " them to whoever publishes " + cfg.displayName() + ".");
            ui.awaitClose();
            return exit == 0 ? 3 : exit;
        }
        if (back == null) {
            ui.failed(cfg.displayName() + " could not start", message
                    + "\n\nThere is no earlier version on this computer to go back to.");
            ui.awaitClose();
            return exit == 0 ? 3 : exit;
        }
        cfg.writeInstalledVersion(back);
        ui.detail("");
        ui.stage("Version " + version + " could not start, going back to " + back);

        String stillBroken = Relauncher.startupProblem(cfg);
        if (stillBroken != null) {
            ui.failed(cfg.displayName() + " could not start", message
                    + "\n\nGoing back to version " + back + " did not help either:\n" + stillBroken);
            ui.awaitClose();
            return 3;
        }

        Process second = Relauncher.launchPiped(cfg, Relauncher.buildCommand(cfg, gui, args));
        OutputPump pump2 = new OutputPump(second, ui, Relauncher.appLog(cfg), gui);
        pump2.start();
        ui.appStarted();
        if (pump2.awaitStartOrExit()) {
            log("version " + version + " could not start; " + back + " restored and running");
            if (!gui) {
                int code = second.waitFor();
                pump2.join(2000);
                ui.close();
                return code;
            }
            ui.close();
            return 0;
        }
        int secondExit = second.waitFor();
        pump2.join(2000);

        if (!pump2.sawStarted() && pump2.sawStartupFailure()) {
            ui.failed(cfg.displayName() + " could not start",
                    "Version " + version + " failed, and so did the previous version " + back
                    + ".\n\nThe output above is from the Java runtime.");
            ui.awaitClose();
            return secondExit;
        }
        log("version " + version + " could not start; " + back + " restored");
        ui.close();
        return secondExit;
    }

    /**
     * Copies the application's output into the window and into app.log, and notices
     * whether the Java runtime reported a failure to start.
     */
    private static final class OutputPump extends Thread {

        private final Process process;
        private final LauncherUi ui;
        private final Path appLog;
        private volatile boolean sawOutput;
        private volatile boolean startupFailure;
        private boolean started;
        private boolean exited;

        /** Stop reading at the marker (the application then writes its own log). */
        private final boolean stopAtMarker;

        OutputPump(Process process, LauncherUi ui, Path appLog) {
            this(process, ui, appLog, true);
        }

        OutputPump(Process process, LauncherUi ui, Path appLog, boolean stopAtMarker) {
            super("app-output");
            this.process = process;
            this.ui = ui;
            this.appLog = appLog;
            this.stopAtMarker = stopAtMarker;
            setDaemon(true);
        }

        synchronized boolean sawStarted() {
            return started;
        }

        boolean sawAnyOutput() {
            return sawOutput;
        }

        boolean sawStartupFailure() {
            return startupFailure;
        }

        /**
         * Waits for whichever comes first: the application reporting that it is running
         * ({@link Relauncher#STARTED_MARKER}, printed by SelfUpdate), or the application
         * ending. True for the first. An application without SelfUpdate never reports, so
         * this simply waits for it to end, as before.
         */
        boolean awaitStartOrExit() throws InterruptedException {
            Thread exitWatch = new Thread("app-exit") {
                @Override
                public void run() {
                    try {
                        process.waitFor();
                    } catch (InterruptedException ignored) {
                        // the launcher is closing anyway
                    }
                    synchronized (OutputPump.this) {
                        exited = true;
                        OutputPump.this.notifyAll();
                    }
                }
            };
            exitWatch.setDaemon(true);
            exitWatch.start();
            synchronized (this) {
                while (!started && !exited) {
                    wait();
                }
                return started;
            }
        }

        @Override
        public void run() {
            BufferedReader r = null;
            Writer w = null;
            try {
                Io.ensureParent(appLog);
                Path previous = appLog.resolveSibling("app-previous.log");
                if (Files.isRegularFile(appLog)) {
                    Io.deleteQuietly(previous);
                    try {
                        Files.move(appLog, previous);
                    } catch (IOException ignored) {
                        // one old log is a convenience, not a requirement
                    }
                }
                w = new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(appLog.toFile(), true), Io.UTF8);
            } catch (Exception ignored) {
                w = null; // a read-only folder must not stop us showing the output
            }
            try {
                r = new BufferedReader(new InputStreamReader(process.getInputStream(), Io.UTF8));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.endsWith(Relauncher.STARTED_MARKER)) {
                        // From here the application writes app.log itself.
                        String before = line.substring(0, line.length()
                                - Relauncher.STARTED_MARKER.length());
                        if (!before.isEmpty() && w != null) {
                            w.write(before);
                            w.write(System.lineSeparator());
                            w.flush();
                        }
                        synchronized (this) {
                            started = true;
                            notifyAll();
                        }
                        if (stopAtMarker) {
                            break;
                        }
                        continue;
                    }
                    if (!line.trim().isEmpty()) {
                        sawOutput = true;
                    }
                    if (Relauncher.isStartupFailure(line)) {
                        startupFailure = true;
                    }
                    ui.detail(line);
                    if (w != null) {
                        w.write(line);
                        w.write(System.lineSeparator());
                        w.flush();
                    }
                }
            } catch (IOException ignored) {
                // the process ended; nothing more to read
            } finally {
                Io.closeQuietly(r);
                Io.closeQuietly(w);
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private static int countTrailingArgs(List<String> cmd, String[] args) {
        return args == null ? 0 : args.length;
    }

    /** "46 jars, 3,912 characters" rather than pages of paths in the window. */
    private static String classpathSummary(List<String> cmd) {
        int i = cmd.indexOf("-cp");
        if (i < 0 || i + 1 >= cmd.size()) {
            return "(none)";
        }
        String cp = cmd.get(i + 1);
        int entries = cp.split(java.util.regex.Pattern.quote(File.pathSeparator)).length;
        return entries + " entries, " + cp.length() + " characters";
    }

    private static String stackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static String launcherPath() {
        try {
            File f = new File(Bootstrap.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return f.isFile() ? f.getAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The launcher's own log, since under javaw there is no console to print to. */
    private static void log(String msg) {
        String line = "[updater] " + msg;
        System.out.println(line);
        if (logFile == null) {
            return;
        }
        Writer w = null;
        try {
            Io.ensureParent(logFile);
            if (Files.isRegularFile(logFile) && Files.size(logFile) > 1024 * 1024) {
                // keep one previous log; the file never grows without limit
                Files.move(logFile, logFile.resolveSibling("launcher-previous.log"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(logFile.toFile(), true), Io.UTF8);
            w.write(new java.util.Date() + "  " + line + System.lineSeparator());
        } catch (Exception ignored) {
            // logging must never break a launch
        } finally {
            Io.closeQuietly(w);
        }
    }
}
