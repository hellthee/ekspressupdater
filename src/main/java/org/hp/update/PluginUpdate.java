package org.hp.update;

import java.awt.GraphicsEnvironment;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Keeps a client's plugin - {@code lib/set.jar} with that client's reports, formats and
 * {@code com.sbm.option} classes - up to date. The jar sits on the application's normal
 * class path, so {@code Class.forName(...)} works as always.
 *
 * <p><b>Where the set comes from</b> is remembered in {@code setapp.txt} next to
 * {@code app.txt}. With that file present, the set is updated like the application itself:
 * at startup, before the application opens it, without the database and without a
 * restart. The database is only asked when {@code setapp.txt} is missing (a first install,
 * a deleted or damaged file).</p>
 *
 * <p>Call {@link #ensure} once the database connection is up, before the first
 * {@code Class.forName} of a set class:</p>
 *
 * <pre>
 * PluginUpdate.ensure("set", mainFrame, new PluginUpdate.Source() {
 *     public String address() throws Exception {
 *         return ... SELECT set_url FROM company ...;    // only when setapp.txt is missing
 *     }
 *     public void installed(String version) throws Exception {
 *         ... UPDATE company SET set_version = ? ...;    // then close the connection:
 *         connection.close();                            // the application restarts next
 *     }
 * });
 * </pre>
 *
 * <p>When the set on this PC is the right one, {@code ensure} returns at once (it only
 * compares a checksum). Otherwise it downloads the set, writes {@code setapp.txt}, calls
 * {@link Source#installed}, tells the user, and restarts the application: the updater puts
 * {@code lib/set.jar} in place while the application is closed. {@code ensure} does not
 * return in that case.</p>
 */
public final class PluginUpdate {

    private PluginUpdate() {
    }

    /** What the application provides: the address from its database, and a place to record. */
    public interface Source {
        /**
         * The plugin's address on the server, from your database (for example
         * {@code http://172.104.47.59:8080/apps/accord/sets/pt-abc/}). Only called when
         * this PC does not know it yet.
         */
        String address() throws Exception;

        /**
         * A new version has been downloaded and checked, {@code setapp.txt} records it, and
         * the application is about to restart to put it in place. Write the version to your
         * database here and close the connection. An exception here is logged and the
         * restart goes ahead.
         */
        void installed(String version) throws Exception;
    }

    /** The plugin cannot be used: never downloaded and the server cannot be reached, etc. */
    public static final class Unavailable extends IOException {
        private static final long serialVersionUID = 1L;

        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Makes sure the right plugin is in place; the database is only asked (through
     * {@code source.address()}) when this PC does not know where the plugin comes from.
     * Safe to call on the Swing thread.
     *
     * @throws Unavailable when there is no usable plugin and none could be put in place
     * @throws IOException when the address from the database is unusable
     */
    public static void ensure(String name, Window owner, Source source) throws IOException {
        ensure(name, null, owner, source);
    }

    /**
     * As {@link #ensure(String, Window, Source)}, for PCs that work with several clients'
     * databases: the address read from the database at every login is compared with
     * {@code setapp.txt}, and a different one (another client) switches the plugin, with a
     * restart. Nothing is downloaded when the address is the same.
     */
    public static void ensure(final String name, final String addressFromDatabase,
            final Window owner, final Source source) throws IOException {
        if (!SwingUtilities.isEventDispatchThread()) {
            ensureHere(name, addressFromDatabase, owner, source);
            return;
        }
        // On the Swing thread: do the work elsewhere and keep the event queue running, so
        // windows repaint, the progress dialog can show, and an exit is never blocked.
        final Object[] outcome = new Object[1];
        final SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue()
                .createSecondaryLoop();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureHere(name, addressFromDatabase, owner, source);
                    outcome[0] = Boolean.TRUE;
                } catch (Throwable t) {
                    outcome[0] = t;
                } finally {
                    long until = System.currentTimeMillis() + 10000;
                    while (!loop.exit() && System.currentTimeMillis() < until) {
                        Io.sleep(5); // exit() only succeeds once enter() has started
                    }
                }
            }
        }, "plugin-update");
        worker.setDaemon(true);
        worker.start();
        loop.enter();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Object o = outcome[0];
        if (o instanceof IOException) {
            throw (IOException) o;
        }
        if (o instanceof RuntimeException) {
            throw (RuntimeException) o;
        }
        if (o instanceof Error) {
            throw (Error) o;
        }
    }

    /**
     * The version of the plugin in place on this PC, or null. Handy to keep the version in
     * your database current after an update made at startup (when there was no database).
     */
    public static String version(String name) {
        Path appDir = installedAppDir();
        if (appDir == null) {
            return null;
        }
        PluginSet p = new PluginSet(appDir, name);
        UpdateManifest m = p.store().installed();
        return m != null && p.inPlace() ? m.version() : p.activeVersion();
    }

    /** True when this start of the application is the first with a newly put-in-place plugin. */
    public static boolean justUpdated(String name) {
        String v = System.getProperty("update.plugins.updated", "");
        for (String n : v.split(",")) {
            if (n.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the work

    private static void ensureHere(String name, String addressFromDatabase, Window owner,
            Source source) throws IOException {
        UpdateManifest.checkPluginName(name);
        Path appDir = installedAppDir();
        if (appDir == null) {
            log(name + ": not an installed copy (NetBeans or dist); the " + name
                    + " on the class path is used as it is");
            return;
        }
        PluginSet p = new PluginSet(appDir, name);
        UpdateConfig app = appConfig(appDir);
        UpdateListener log = consoleListener();
        String known = p.address();
        String fromDb = addressFromDatabase == null ? null : cleanUrl(name, addressFromDatabase);

        // 1. The usual case: setapp.txt names the right address and lib/set.jar is that release.
        if (known != null && (fromDb == null || PluginSet.sameAddress(fromDb, known))
                && PluginSet.sameAddress(known, p.activeAddress()) && p.inPlace()) {
            if (!Boolean.getBoolean("update.plugins.checked")) {
                // Started by something that does not look after plugins (an older launcher.jar):
                // look for a new release now instead.
                UpdateManifest remote = p.refresh(app, log);
                if (remote != null && !p.inPlace()) {
                    restartToPutInPlace(p, app, owner, source, known);
                }
            }
            return;
        }

        // 2. Something to do: find the address, download, record it in setapp.txt.
        String address = fromDb != null ? fromDb : known;
        if (address == null) {
            String a;
            try {
                a = source == null ? null : source.address();
            } catch (Exception e) {
                throw new IOException("The " + name + "'s address could not be read from the"
                        + " database: " + e.getMessage(), e);
            }
            address = cleanUrl(name, a);
        } else {
            address = cleanUrl(name, address);
        }
        UpdateManager mgr = p.manager(address, app, log);
        UpdateManifest release;
        try {
            UpdateManifest remote = mgr.fetchRemoteManifest(mgr.config().checkAttempts());
            UpdateManager.Result r = p.install(mgr, remote, owner);
            if (r.status != UpdateManager.Status.UPDATED && r.status != UpdateManager.Status.UP_TO_DATE) {
                throw new IOException(r.status == UpdateManager.Status.CANCELLED
                        ? "The download was cancelled" : r.message);
            }
            release = remote;
        } catch (IOException e) {
            if (isWrongAddress(e)) {
                throw e;
            }
            // Offline (or the download failed): what was downloaded before for this address
            // can still be put in place.
            UpdateManifest have = p.store().installed();
            if (have != null && (p.storeIntact() || p.inPlace())
                    && PluginSet.sameAddress(address, p.storeAddress())) {
                log("using the " + name + " " + have.version() + " downloaded earlier: " + e.getMessage());
                release = have;
            } else {
                String why = whyNot(name, e);
                throw new Unavailable(why + ". The " + name + " for this company has not been"
                        + " downloaded to this PC yet, so it cannot be used."
                        + (why.contains("could not be reached")
                                ? " Check the internet connection and try again." : ""), e);
            }
        }
        if (p.inPlace()) {
            // lib already holds exactly this release (setapp.txt was only missing)
            p.writeConfig(address, release.uiName(), release.version());
            p.recordInPlace(address);
            log(name + " " + release.version() + " is already in place; "
                    + p.configFile().getFileName() + " written");
            return;
        }
        restartToPutInPlace(p, app, owner, source, address);
    }

    /** Tells the user, lets the application record the version, and restarts. */
    private static void restartToPutInPlace(PluginSet p, UpdateConfig app, Window owner,
            Source source, String address) throws IOException {
        UpdateManifest m = p.store().installed();
        String version = m.version();
        String failed = p.failedActivation(version);
        if (failed != null || p.restartsFor(version) >= 2) {
            String why = failed != null ? failed : "restarting did not put it in place";
            if (PluginSet.sameAddress(address, p.activeAddress())) {
                // The right client's older set is in place: carry on with it.
                notice(owner, p.name + " " + version + " is downloaded but could not be put in"
                        + " place: " + why, JOptionPane.WARNING_MESSAGE);
                return;
            }
            throw new Unavailable("The " + p.name + " for this company could not be put in"
                    + " place: " + why, null);
        }
        // Everything that can fail comes before the database is told and closed.
        Path updater;
        try {
            updater = SelfUpdate.prepareRestart(app);
            p.countRestart(version);
        } catch (IOException e) {
            throw new Unavailable("The " + p.name + " " + version + " is downloaded, but "
                    + app.displayName() + " cannot restart to use it: " + e.getMessage(), e);
        }
        // Only now that the restart will happen does setapp.txt name the new release: a
        // refused switch must not leave the PC pointing at another client's set.
        try {
            p.writeConfig(address, m.uiName(), version);
        } catch (IOException e) {
            throw new Unavailable("The " + p.name + " " + version + " is downloaded, but "
                    + p.configFile() + " cannot be written: " + e.getMessage(), e);
        }
        if (source != null) {
            try {
                source.installed(version);
            } catch (Exception e) {
                log("recording " + p.name + " " + version + " in the database failed: " + e);
            }
        }
        String title = m.uiName() != null ? m.uiName() : p.name;
        notice(owner, title + " " + version + " has been downloaded.\n" + app.displayName()
                + " will now restart to use it.", JOptionPane.INFORMATION_MESSAGE);
        SelfUpdate.restartAndExit(app, updater); // does not return
    }

    private static void notice(final Window owner, final String text, final int type) {
        log(text.replace('\n', ' '));
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Runnable show = new Runnable() {
            @Override
            public void run() {
                JOptionPane.showMessageDialog(owner, text, "Update", type);
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                show.run();
            } else {
                SwingUtilities.invokeAndWait(show);
            }
        } catch (Exception ignored) {
            // no display: the log has it
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The installed application's folder, or null when running from NetBeans or dist. */
    static Path installedAppDir() {
        UpdateConfig cfg = SelfUpdate.config();
        return cfg != null ? cfg.appDir() : SelfUpdate.appDir();
    }

    private static UpdateConfig appConfig(Path appDir) throws IOException {
        UpdateConfig cfg = SelfUpdate.config();
        return cfg != null ? cfg : UpdateConfig.load(appDir);
    }

    static UpdateListener consoleListener() {
        return new UpdateListener() {
            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onWarning(String message) {
                log("warning: " + message);
            }

            @Override
            public void onFailed(String message, Throwable cause) {
                log(message + (cause == null ? "" : ": " + cause.getMessage()));
            }
        };
    }

    private static void log(String m) {
        System.out.println("[updater] " + m);
    }

    /** Why the release could not be fetched, in words that point at the right cause. */
    static String whyNot(String name, IOException e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof Downloader.Permanent) {
            int status = ((Downloader.Permanent) e).status;
            if (status == 404 || status == 410 || status == 403) {
                return "There is no " + name + " release at its address (HTTP " + status
                        + "); check the address stored in the database";
            }
        }
        if (m.startsWith("Line ") || m.contains("Unacceptable") || m.startsWith("Incomplete")
                || m.contains("no \"version\"") || m.contains("lists no files")) {
            return "The " + name + " release on the server is not usable (" + m + ")";
        }
        return "The " + name + " server could not be reached (" + m + ")";
    }

    /** Answers that mean "this address is wrong", not "the server is down". */
    private static boolean isWrongAddress(IOException e) {
        if (e instanceof Signing.BadSignature) {
            return true;
        }
        String m = String.valueOf(e.getMessage());
        return m.contains("does not hold a plugin") || m.contains("holds the plugin");
    }

    static String cleanUrl(String name, String url) throws IOException {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            throw new IOException("No address is stored for the " + name
                    + " (the address from the database is empty)");
        }
        String lower = u.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IOException("The address stored for the " + name + " must start with"
                    + " http:// or https://: " + u);
        }
        if (lower.endsWith("/app.txt")) {
            u = u.substring(0, u.length() - "app.txt".length());
        }
        boolean odd = false;
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            // control characters, spaces (also the invisible kinds) and anything non-ASCII
            odd = odd || c <= 0x20 || c >= 0x7f || c == '?' || c == '#' || c == '\\';
        }
        if (odd || u.substring(u.indexOf("//") + 2).isEmpty()) {
            throw new IOException("The address stored for the " + name + " is not usable: " + u);
        }
        return u.endsWith("/") ? u : u + "/";
    }
}
