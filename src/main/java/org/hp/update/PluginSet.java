package org.hp.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * One plugin (a client's {@code set.jar}) of an installed application, as the PC knows it:
 *
 * <pre>
 * C:\Saint\setapp.txt              where the set comes from, and its version (like app.txt)
 * C:\Saint\lib\set.jar             the set in use: on the class path, so Class.forName works
 * C:\Saint\.update\plugins\set\.update\installed.txt   the release: every file, size, SHA-256
 * C:\Saint\.update\plugin-set.properties   what is in lib, from where; failed attempts
 * </pre>
 *
 * <p>A new release is downloaded into {@code .update\plugins\set} at any time; copying it to
 * {@code lib} ("putting it in place") happens only while the application is closed,
 * because a jar the application has open cannot be replaced (Windows) or would not be
 * re-read (everywhere else). Once it is in place the downloaded copy is deleted and only
 * the release's list is kept, as for the application itself: a damaged or replaced file is
 * downloaded again. (Older updaters kept a full copy in {@code plugins\set}; it is moved
 * and trimmed at the next start.)</p>
 */
final class PluginSet {

    static final String CONFIG_SUFFIX = "app.txt";

    final Path appDir;
    final String name;

    PluginSet(Path appDir, String name) {
        this.appDir = appDir.toAbsolutePath().normalize();
        this.name = UpdateManifest.checkPluginName(name);
    }

    /** Every plugin with a {@code <name>app.txt} that says {@code plugin = <name>}. */
    static List<PluginSet> all(Path appDir) {
        List<PluginSet> out = new ArrayList<PluginSet>();
        java.io.File[] files = appDir.toFile().listFiles();
        if (files == null) {
            return out;
        }
        for (java.io.File f : files) {
            String n = f.getName();
            if (!f.isFile() || !n.endsWith(CONFIG_SUFFIX) || n.equals(UpdateConfig.APP_TXT)) {
                continue;
            }
            String prefix = n.substring(0, n.length() - CONFIG_SUFFIX.length());
            try {
                PluginSet p = new PluginSet(appDir, prefix);
                if (p.address() != null) {
                    out.add(p);
                }
            } catch (IllegalArgumentException notOurs) {
                // some other file that happens to end in app.txt
            }
        }
        return out;
    }

    /**
     * Paths (relative, with /) that belong to plugins, so the application's own updater
     * never replaces or deletes them: what is in place now, and what is about to be.
     */
    static Set<String> managedPaths(Path appDir) {
        Set<String> out = new LinkedHashSet<String>();
        for (PluginSet p : known(appDir)) {
            out.addAll(p.activated());
            UpdateManifest m = p.store().installed();
            if (m != null) {
                for (UpdateManifest.FileEntry e : m.entries().values()) {
                    out.add(p.targetPath(e));
                }
            }
        }
        return out;
    }

    /**
     * Jars to add to the class path: those put in place for the address setapp.txt names.
     * A set left in lib by another client (setapp.txt missing or pointing elsewhere) is not.
     */
    static List<String> classPathJars(Path appDir) {
        List<String> out = new ArrayList<String>();
        for (PluginSet p : all(appDir)) {
            if (sameAddress(p.address(), p.activeAddress())) {
                for (String a : p.activated()) {
                    if (a.toLowerCase().endsWith(".jar")) {
                        out.add(a);
                    }
                }
            }
        }
        return out;
    }

    /** Plugins with a setapp.txt, a download (or its record), or a record of files in place. */
    private static List<PluginSet> known(Path appDir) {
        Set<String> names = new LinkedHashSet<String>();
        for (Path base : new Path[] {storesDir(appDir), appDir.resolve(LEGACY_STORES)}) {
            java.io.File[] dirs = base.toFile().listFiles();
            if (dirs != null) {
                for (java.io.File d : dirs) {
                    if (d.isDirectory()) {
                        names.add(d.getName());
                    }
                }
            }
        }
        java.io.File[] states = appDir.resolve(InstallStore.UPDATE_DIR).toFile().listFiles();
        if (states != null) {
            for (java.io.File f : states) {
                String n = f.getName();
                if (n.startsWith("plugin-") && n.endsWith(".properties")) {
                    names.add(n.substring("plugin-".length(), n.length() - ".properties".length()));
                }
            }
        }
        List<PluginSet> out = new ArrayList<PluginSet>();
        for (String n : names) {
            try {
                out.add(new PluginSet(appDir, n));
            } catch (IllegalArgumentException ignored) {
                // not a plugin
            }
        }
        for (PluginSet p : all(appDir)) {
            if (!names.contains(p.name)) {
                out.add(p);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ files

    Path configFile() {
        return appDir.resolve(name + CONFIG_SUFFIX);
    }

    /** Where older updaters kept each plugin's download, next to the application. */
    private static final String LEGACY_STORES = "plugins";

    private static Path storesDir(Path appDir) {
        return appDir.resolve(InstallStore.UPDATE_DIR).resolve("plugins");
    }

    /** The plugin's download and record: in .update, or where an older updater left it. */
    Path storeDir() {
        Path dir = storesDir(appDir).resolve(name);
        Path legacy = appDir.resolve(LEGACY_STORES).resolve(name);
        return !Files.exists(dir) && Files.isDirectory(legacy) ? legacy : dir;
    }

    /**
     * Moves an older updater's plugins\NAME into .update\plugins\NAME, and removes the
     * plugins folder once nothing is left in it. Call it while holding the update lock.
     */
    void moveLegacyStore() {
        Path legacy = appDir.resolve(LEGACY_STORES).resolve(name);
        if (!Files.isDirectory(legacy)) {
            return;
        }
        Path dir = storesDir(appDir).resolve(name);
        try {
            if (Files.exists(dir)) {
                Io.deleteQuietly(legacy); // both: the one in .update is the newer
            } else {
                Files.createDirectories(dir.getParent());
                Files.move(legacy, dir);
            }
        } catch (IOException e) {
            return; // in use: the next start tries again, the old place still works
        }
        java.io.File[] left = appDir.resolve(LEGACY_STORES).toFile().listFiles();
        if (left != null && left.length == 0) {
            Io.deleteQuietly(appDir.resolve(LEGACY_STORES));
        }
    }

    /**
     * Once the release is in place, deletes the downloaded copy of its files and keeps only
     * its list (installed.txt): no second copy of the set stays on the PC.
     */
    void trimStore() {
        if (!inPlace()) {
            return;
        }
        Path dir = storeDir();
        java.io.File[] top = dir.toFile().listFiles();
        if (top == null) {
            return;
        }
        for (java.io.File f : top) {
            if (!f.getName().equals(InstallStore.UPDATE_DIR)) {
                Io.deleteQuietly(f.toPath());
            }
        }
        java.io.File[] record = dir.resolve(InstallStore.UPDATE_DIR).toFile().listFiles();
        if (record != null) {
            for (java.io.File f : record) {
                if (!f.getName().equals(InstallStore.INSTALLED)) {
                    Io.deleteQuietly(f.toPath());
                }
            }
        }
    }

    InstallStore store() {
        return new InstallStore(storeDir());
    }

    private UpdateManifest config() {
        String text = Io.readString(configFile());
        if (text == null) {
            return null;
        }
        try {
            UpdateManifest m = UpdateManifest.parse(text);
            return name.equalsIgnoreCase(m.plugin()) ? m : null;
        } catch (IOException e) {
            return null; // damaged: the application asks its database again
        }
    }

    /** The {@code appbase} of setapp.txt, or null when the file is missing or unusable. */
    String address() {
        UpdateManifest m = config();
        String a = m == null ? null : m.appBase();
        return a == null || a.trim().isEmpty() ? null : a.trim();
    }

    /** The {@code target} line of setapp.txt, if it names a usable folder; else null. */
    private String configTarget() {
        String text = Io.readString(configFile());
        String t = null;
        if (text != null) {
            for (String raw : text.split("\n")) {
                String s = raw.trim();
                int eq = s.indexOf('=');
                if (!s.startsWith("#") && eq > 0 && s.substring(0, eq).trim().equalsIgnoreCase("target")) {
                    t = s.substring(eq + 1).trim();
                }
            }
        }
        if (t == null || t.isEmpty()) {
            return null;
        }
        t = t.replace('\\', '/');
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        try {
            UpdateManifest.checkPath(t + "/x");
            return t;
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    /**
     * Folder the plugin's files go to, relative to the application: "" when the release says
     * {@code target = .} (files keep their build-folder paths: lib/set.jar, config.dat),
     * otherwise setapp.txt's {@code target} line, otherwise lib.
     */
    String targetDir() {
        UpdateManifest m = store().installed();
        return targetDir(m);
    }

    private String targetDir(UpdateManifest m) {
        if (m != null && ".".equals(m.pluginTarget())) {
            return "";
        }
        String c = configTarget();
        return c != null ? c : "lib";
    }

    String targetPath(UpdateManifest.FileEntry e) {
        return targetPath(e, targetDir());
    }

    private static String targetPath(UpdateManifest.FileEntry e, String dir) {
        return dir.isEmpty() ? e.path : dir + "/" + e.path;
    }

    Path target(UpdateManifest.FileEntry e) {
        return InstallStore.inside(appDir, targetPath(e));
    }

    private Path target(UpdateManifest.FileEntry e, String dir) {
        return InstallStore.inside(appDir, targetPath(e, dir));
    }

    /** Writes setapp.txt: where the plugin comes from and which version is (about to be) in place. */
    void writeConfig(String address, String uiName, String version) throws IOException {
        String target = configTarget();
        StringBuilder sb = new StringBuilder();
        sb.append("# Written by the updater from the address in the database.\n");
        sb.append("# Delete this file and the application asks the database again at the next login.\n");
        sb.append("plugin  = ").append(name).append('\n');
        sb.append("appbase = ").append(address).append('\n');
        if (uiName != null) {
            sb.append("ui.name = ").append(uiName).append('\n');
        }
        if (target != null && !"lib".equals(target)) {
            sb.append("target  = ").append(target).append('\n');
        }
        if (version != null) {
            sb.append("version = ").append(version).append('\n');
        }
        Io.writeStringAtomic(configFile(), sb.toString());
    }

    // ------------------------------------------------------------------ state

    /**
     * True when the downloaded release is in place: every jar byte for byte; every other
     * file present, and the customer's own copy is good enough unless you changed that file
     * since it was last put in place (a .prp or .dat the application writes to).
     */
    boolean inPlace() {
        UpdateManifest m = store().installed();
        if (m == null) {
            return false;
        }
        String dir = targetDir(m);
        Properties st = state();
        for (UpdateManifest.FileEntry e : m.entries().values()) {
            try {
                Path t = target(e, dir);
                if (Io.matches(t, e.size, e.sha256)) {
                    continue;
                }
                if (e.classpath || !keepsCustomerCopy(st, targetPath(e, dir), e, t)) {
                    return false;
                }
            } catch (IllegalArgumentException bad) {
                return false;
            }
        }
        return true;
    }

    /**
     * A non-jar file that differs from the release is the customer's own version when it
     * exists and this release did not change it: the sha last put in place equals the
     * release's, or nothing was recorded yet (the file came with an earlier install).
     */
    private static boolean keepsCustomerCopy(Properties st, String rel, UpdateManifest.FileEntry e,
            Path target) {
        if (e.classpath || !Files.isRegularFile(target)) {
            return false;
        }
        String last = st.getProperty("sha." + rel);
        return last == null || last.isEmpty() || Io.hashEquals(last, e.sha256);
    }

    /** True when the downloaded release itself is complete and correct. */
    boolean storeIntact() {
        InstallStore s = store();
        UpdateManifest m = s.installed();
        if (m == null) {
            return false;
        }
        for (UpdateManifest.FileEntry e : m.entries().values()) {
            if (!s.hasIntact(e)) {
                return false;
            }
        }
        return true;
    }

    /** Kept in the application's .update, so it survives the download folder starting over. */
    private Path stateFile() {
        return appDir.resolve(InstallStore.UPDATE_DIR).resolve("plugin-" + name + ".properties");
    }

    private Properties state() {
        return Io.loadProps(stateFile());
    }

    private String stateValue(String key) {
        String v = state().getProperty(key);
        return v == null || v.isEmpty() ? null : v;
    }

    private void saveState(Properties p) throws IOException {
        Files.createDirectories(stateFile().getParent());
        Io.storeProps(stateFile(), p);
    }

    /** The address the download in .update\plugins\NAME came from, or null. */
    String storeAddress() {
        return stateValue("storeAddress");
    }

    /** The address of the plugin last put in place in lib, or null. */
    String activeAddress() {
        return stateValue("activeAddress");
    }

    /** The version last put in place in lib, or null. */
    String activeVersion() {
        return stateValue("activeVersion");
    }

    /** The files last put in place (relative paths). */
    Set<String> activated() {
        Set<String> out = new LinkedHashSet<String>();
        String a = stateValue("activated");
        if (a != null) {
            for (String s : a.split(",")) {
                if (!s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    /** Failures and restarts are counted per release: this address and version. */
    private String attemptKey(String version) {
        String a = storeAddress();
        return (a == null ? "" : normalize(a)) + "|" + version;
    }

    /** Why the last attempt to put this release in place failed, or null. */
    String failedActivation(String version) {
        Properties p = state();
        return attemptKey(version).equals(p.getProperty("failedKey")) ? p.getProperty("failedReason") : null;
    }

    /** How many restarts were asked for to put this release in place. */
    int restartsFor(String version) {
        Properties p = state();
        if (!attemptKey(version).equals(p.getProperty("restartKey"))) {
            return 0;
        }
        try {
            return Integer.parseInt(p.getProperty("restarts", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Counts a restart for this release. Throws if it cannot be written: without the count
     * a failing restart could repeat for ever.
     */
    void countRestart(String version) throws IOException {
        Properties p = state();
        int n = restartsFor(version);
        p.setProperty("restartKey", attemptKey(version));
        p.setProperty("restarts", String.valueOf(n + 1));
        saveState(p);
        if (restartsFor(version) != n + 1) {
            throw new IOException("cannot record the restart in " + stateFile());
        }
    }

    private void setState(String key, String value) {
        try {
            Properties p = state();
            p.setProperty(key, value);
            saveState(p);
        } catch (IOException ignored) {
            // advisory
        }
    }

    // ------------------------------------------------------------------ putting in place

    /**
     * Copies the downloaded release into the application (lib\set.jar). Only while the
     * application is closed, and only when the download is for the address setapp.txt
     * names. All files or none: on a failure the previous files are put back and the
     * reason is remembered, so the application does not restart in a loop.
     *
     * @return what was done, for the log; null when nothing had to be done
     */
    String activate() throws IOException {
        UpdateManifest m = store().installed();
        if (m == null || inPlace()) {
            return null;
        }
        if (!sameAddress(storeAddress(), address())) {
            throw new IOException("the downloaded " + name + " is for " + storeAddress()
                    + ", not for " + address() + "; it is not put in place");
        }
        if (!storeIntact()) {
            throw new IOException("the downloaded " + name + " is damaged; it will be downloaded again");
        }
        Set<String> before = activated();
        Set<String> appOwn = new java.util.HashSet<String>();
        UpdateManifest app = new InstallStore(appDir).installed();
        if (app != null) {
            for (String path : app.entries().keySet()) {
                appOwn.add(path.toLowerCase());
            }
        }
        String dir = targetDir(m);
        Properties st = state();
        Set<String> now = new LinkedHashSet<String>();
        List<Path[]> moved = new ArrayList<Path[]>(); // {target, backup or null}
        Path backupDir = store().updateDir().resolve("replaced");
        Io.deleteQuietly(backupDir);
        try {
            for (UpdateManifest.FileEntry e : m.entries().values()) {
                String rel = targetPath(e, dir);
                now.add(rel);
                Path target = target(e, dir);
                if (Io.matches(target, e.size, e.sha256)
                        || keepsCustomerCopy(st, rel, e, target)) {
                    continue; // in place, or the customer's own copy of a file you did not change
                }
                Path source = store().file(e.path);
                Io.ensureParent(target);
                Path tmp = target.resolveSibling(target.getFileName() + ".hp-new");
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                if (!Io.matches(tmp, e.size, e.sha256)) {
                    Io.deleteQuietly(tmp);
                    throw new IOException("copying " + e.path + " gave a different file");
                }
                Path backup = null;
                if (Files.exists(target)) {
                    backup = InstallStore.inside(backupDir, rel);
                    Io.ensureParent(backup);
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Io.moveAtomic(tmp, target);
                } catch (IOException notMoved) {
                    Io.deleteQuietly(tmp);
                    if (!Io.matches(target, e.size, e.sha256)) {
                        throw notMoved; // in use; if it got there after all, carry on
                    }
                }
                moved.add(new Path[] {target, backup});
            }
        } catch (IOException | RuntimeException e) {
            for (int i = moved.size() - 1; i >= 0; i--) {
                Path[] mv = moved.get(i);
                try {
                    if (mv[1] != null) {
                        Io.moveAtomic(mv[1], mv[0]);
                    } else {
                        Files.deleteIfExists(mv[0]);
                    }
                } catch (IOException ignored) {
                    // nothing more can be done; the next start tries again
                }
            }
            String reason = String.valueOf(e.getMessage());
            Properties p = state();
            p.setProperty("failedKey", attemptKey(m.version()));
            p.setProperty("failedReason", reason + ". Close every copy of the application and"
                    + " start it again.");
            try {
                saveState(p);
            } catch (IOException ignored) {
                // the restart counter is the second guard
            }
            throw new IOException(name + " " + m.version() + " could not be put in place: "
                    + reason, e);
        }
        // Files of the previous release that this one no longer has (a renamed jar).
        for (String old : before) {
            // Only jars: a dropped .prp or .dat stays as the customer has it (as for the app).
            if (!now.contains(old) && !appOwn.contains(old.toLowerCase())
                    && old.toLowerCase().endsWith(".jar")) {
                try {
                    UpdateManifest.checkPath(old);
                    Files.deleteIfExists(InstallStore.inside(appDir, old));
                } catch (IOException | IllegalArgumentException ignored) {
                    // left behind
                }
            }
        }
        Io.deleteQuietly(backupDir);
        Properties p = state();
        p.remove("failedKey");
        p.remove("failedReason");
        p.remove("restartKey");
        p.remove("restarts");
        StringBuilder sb = new StringBuilder();
        for (String a : now) {
            sb.append(sb.length() == 0 ? "" : ",").append(a);
        }
        p.setProperty("activated", sb.toString());
        p.setProperty("activeAddress", storeAddress());
        p.setProperty("activeVersion", m.version());
        recordShas(p, m, dir);
        saveState(p);
        trimStore();
        return name + " " + m.version() + " put in place (" + now + ")";
    }

    /** Remembers the release's checksum of every file put in place (for the customer-copy rule). */
    private static void recordShas(Properties p, UpdateManifest m, String dir) {
        for (String key : new ArrayList<String>(p.stringPropertyNames())) {
            if (key.startsWith("sha.")) {
                p.remove(key);
            }
        }
        for (UpdateManifest.FileEntry e : m.entries().values()) {
            p.setProperty("sha." + targetPath(e, dir), e.sha256);
        }
    }

    /** lib already holds exactly this release (setapp.txt was only missing): record it as such. */
    void recordInPlace(String address) {
        UpdateManifest m = store().installed();
        if (m == null) {
            return;
        }
        try {
            Properties p = state();
            String dir = targetDir(m);
            StringBuilder sb = new StringBuilder();
            for (UpdateManifest.FileEntry e : m.entries().values()) {
                sb.append(sb.length() == 0 ? "" : ",").append(targetPath(e, dir));
            }
            p.setProperty("activated", sb.toString());
            p.setProperty("activeAddress", address);
            p.setProperty("activeVersion", m.version());
            recordShas(p, m, dir);
            saveState(p);
        } catch (IOException ignored) {
            // advisory
        }
        trimStore();
    }

    // ------------------------------------------------------------------ downloading

    /**
     * Looks for a newer release at the address in setapp.txt and downloads it into
     * .update\plugins\NAME (not into lib). Short timeouts: this runs while the user waits for the
     * application to open.
     *
     * @return the release on the server, or null when it could not be checked
     */
    UpdateManifest refresh(UpdateConfig app, UpdateListener log) {
        String address = address();
        if (address == null) {
            return null;
        }
        try {
            UpdateManager mgr = manager(address, app, log);
            UpdateManifest remote = mgr.fetchRemoteManifest(mgr.config().checkAttempts());
            UpdateManager.Result r = install(mgr, remote, null);
            return r.status == UpdateManager.Status.UPDATED || r.status == UpdateManager.Status.UP_TO_DATE
                    ? remote : null;
        } catch (Exception e) {
            log.onLog("Could not check the " + name + " for updates: " + e.getMessage());
            return null;
        }
    }

    UpdateManager manager(String address, UpdateConfig app, UpdateListener log) {
        UpdateConfig cfg = new UpdateConfig(address, storeDir());
        if (app != null) {
            cfg.copyTuningFrom(app);
        }
        return new UpdateManager(cfg, log).forPlugin(name);
    }

    /**
     * Downloads {@code remote} into .update\plugins\NAME unless it is already there, or
     * already in place. A download for another address, or a damaged one, is never mixed
     * with this one: the folder starts over.
     */
    UpdateManager.Result install(UpdateManager mgr, UpdateManifest remote, java.awt.Window owner)
            throws IOException {
        String address = mgr.config().baseUrl();
        UpdateManifest have = store().installed();
        // Usable: complete in the download folder, or already in place (its copy trimmed).
        boolean usable = have != null && (storeIntact() || inPlace());
        if (have != null && (!sameAddress(storeAddress(), address) || !usable
                || have.version().equals(remote.version()) && !sameFiles(have, remote))) {
            if (sameFiles(have, remote) && usable) {
                setState("storeAddress", address); // the same files under another address
            } else {
                Io.deleteQuietly(storeDir());
                if (store().installed() != null) {
                    throw new IOException("the old download in " + storeDir() + " cannot be removed");
                }
            }
        }
        have = store().installed();
        if (have != null && have.version().equals(remote.version())) {
            return new UpdateManager.Result(UpdateManager.Status.UP_TO_DATE, remote.version(),
                    "Already downloaded", null);
        }
        Files.createDirectories(storeDir());
        copyUnchangedFromPlace(remote);
        UpdateManager.Result r;
        if (owner != null && !java.awt.GraphicsEnvironment.isHeadless()) {
            String title = (remote.uiName() != null ? remote.uiName() : name) + " " + remote.version();
            r = UpdateDialog.runModal(owner, mgr, "Downloading " + title, remote);
        } else {
            r = mgr.install(remote);
        }
        if (r.status == UpdateManager.Status.UPDATED) {
            mgr.store().confirmUpdate(); // the downloaded copy is never run from
            setState("storeAddress", address);
        }
        return r;
    }

    /**
     * Files of the new release that are already in place, byte for byte, are copied into the
     * download folder instead of being downloaded again (the copy there was trimmed).
     */
    private void copyUnchangedFromPlace(UpdateManifest remote) {
        String dir = targetDir(remote);
        InstallStore s = store();
        for (UpdateManifest.FileEntry e : remote.entries().values()) {
            try {
                Path placed = target(e, dir);
                if (s.hasIntact(e) || !Io.matches(placed, e.size, e.sha256)) {
                    continue;
                }
                Path copy = s.file(e.path);
                Io.ensureParent(copy);
                Files.copy(placed, copy, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | IllegalArgumentException ignored) {
                // downloaded instead
            }
        }
    }

    private static boolean sameFiles(UpdateManifest a, UpdateManifest b) {
        if (a.entries().size() != b.entries().size()) {
            return false;
        }
        for (UpdateManifest.FileEntry e : a.entries().values()) {
            UpdateManifest.FileEntry o = b.entries().get(e.path);
            if (o == null || !Io.hashEquals(o.sha256, e.sha256)) {
                return false;
            }
        }
        return true;
    }

    /** Same address, ignoring a trailing slash, "app.txt" and the case of scheme and host. */
    static boolean sameAddress(String a, String b) {
        return a != null && b != null && normalize(a).equals(normalize(b));
    }

    static String normalize(String url) {
        String u = url.trim();
        if (u.toLowerCase().endsWith("/app.txt")) {
            u = u.substring(0, u.length() - "app.txt".length());
        }
        if (!u.endsWith("/")) {
            u = u + "/";
        }
        int start = u.indexOf("//");
        int slash = start < 0 ? -1 : u.indexOf('/', start + 2);
        return slash < 0 ? u.toLowerCase() : u.substring(0, slash).toLowerCase() + u.substring(slash);
    }
}
