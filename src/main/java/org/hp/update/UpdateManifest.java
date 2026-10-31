package org.hp.update;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contents of {@code app.txt} — the one config file of this updater, in the same
 * style as getdown's {@code getdown.txt}.
 *
 * <p><b>On the client</b>, the file only needs to say where to download from:</p>
 *
 * <pre>
 * appbase = http://172.104.47.59:8080/apps/accord/
 * ui.name = Saint
 * </pre>
 *
 * <p><b>On the server</b>, the build tool writes the same file with the release details
 * added. The {@code |size|sha256} part is what lets the updater resume a broken
 * download, verify every file and delete files you removed from the release:</p>
 *
 * <pre>
 * appbase = http://172.104.47.59:8080/apps/accord/
 * ui.name = Saint
 * version = 1.4.2
 * class   = org.hp.accord.Main
 * jvmarg  = -Xmx1024m
 *
 * code     = Saint.jar|182734|3f9a...
 * code     = lib/SaintLib.jar|48211|71bd...
 * resource = Config.dat|822|9c02...
 * </pre>
 *
 * <p>{@code code} goes on the classpath, {@code resource} is any other file. Unknown
 * keys are ignored, so an old client survives a newer file.</p>
 */
public final class UpdateManifest {

    /**
     * Last line of a generated file. If the download is cut short this line is missing,
     * which is how a truncated release description is told apart from a complete one —
     * important, because a release description is also the instruction to delete files
     * that are no longer part of the release.
     */
    public static final String END_MARKER = "# end of release";

    /**
     * Server layout in which each file keeps its own name, in a folder named after its
     * checksum: {@code files/b1fe8b5968b5/GasX.jar}. Releases without a {@code layout}
     * line use the original layout, {@code files/b1/b1fe8b59...} (the full checksum).
     */
    public static final String LAYOUT_NAMED = "named";

    /**
     * Written after {@link #LAYOUT_NAMED} ({@code layout = named, hashed}) when the release
     * is also stored in the original layout, for PCs whose updater predates the named one.
     * Older updaters ignore the line and use the original layout.
     */
    public static final String LAYOUT_HASHED = "hashed";

    /** Characters of the checksum used as the folder name in the named layout. */
    static final int NAMED_PREFIX = 12;

    /** One file in a release. */
    public static final class FileEntry {

        public final String path;       // relative, forward slashes
        public final long size;
        public final String sha256;
        public final boolean classpath; // a "code" line rather than a "resource" line
        public final boolean executable;
        /**
         * A settings file the application reads and writes by relative path. It is copied
         * into the application folder once, if it is not already there, and never
         * overwritten afterwards, so a customer's own settings survive every update.
         */
        public final boolean seed;

        public FileEntry(String path, long size, String sha256, boolean classpath, boolean executable) {
            this(path, size, sha256, classpath, executable, false);
        }

        public FileEntry(String path, long size, String sha256, boolean classpath,
                boolean executable, boolean seed) {
            this.path = normalize(path);
            this.size = size;
            this.sha256 = sha256.toLowerCase();
            this.classpath = classpath;
            this.executable = executable;
            this.seed = seed;
        }

        static String normalize(String p) {
            String s = p.trim().replace('\\', '/');
            while (s.startsWith("./")) {
                s = s.substring(2);
            }
            return s;
        }

        String toLine() {
            String flag = executable ? "|exec" : (seed ? "|seed" : "");
            return (classpath ? "code     = " : "resource = ")
                    + path + "|" + size + "|" + sha256 + flag;
        }

        @Override
        public String toString() {
            return toLine();
        }
    }

    private final String appBase;
    private final String uiName;
    private final String version;
    private final String mainClass;
    private final List<String> jvmArgs;
    private final List<String> appArgs;
    private final Map<String, FileEntry> entries; // keyed by relative path
    /** "What's new" lines, shown to the user when the update is offered. */
    private List<String> notes = Collections.emptyList();
    /** {@link #LAYOUT_NAMED}, or null for the original checksum-only layout. */
    private String layout;
    /** The publisher's public key, carried in the release so a PC can remember it. */
    private String publicKey;
    /** For a plugin release (a set.jar and the like), its name; null for an application. */
    private String plugin;
    /**
     * Where a plugin's files go on the PC: "." means at the same paths as in the build
     * folder (lib/set.jar to lib/set.jar, config.dat to the application folder); null means
     * the original rule, everything into lib/.
     */
    private String pluginTarget;

    private UpdateManifest(String appBase, String uiName, String version, String mainClass,
            List<String> jvmArgs, List<String> appArgs, Map<String, FileEntry> entries) {
        this.appBase = appBase;
        this.uiName = uiName;
        this.version = version;
        this.mainClass = mainClass;
        this.jvmArgs = Collections.unmodifiableList(jvmArgs);
        this.appArgs = Collections.unmodifiableList(appArgs);
        this.entries = Collections.unmodifiableMap(entries);
    }

    /** Download location, i.e. the {@code appbase} line. */
    public String appBase() {
        return appBase;
    }

    /** Display name shown in the update window, i.e. {@code ui.name}. */
    public String uiName() {
        return uiName;
    }

    public String version() {
        return version;
    }

    public String mainClass() {
        return mainClass;
    }

    public List<String> jvmArgs() {
        return jvmArgs;
    }

    public List<String> appArgs() {
        return appArgs;
    }

    /** Release notes, one line each; empty when the release has none. */
    public List<String> notes() {
        return notes;
    }

    /** A copy of this release with release notes attached. */
    public UpdateManifest withNotes(List<String> lines) {
        UpdateManifest m = new UpdateManifest(appBase, uiName, version, mainClass,
                new ArrayList<String>(jvmArgs), new ArrayList<String>(appArgs),
                new LinkedHashMap<String, FileEntry>(entries));
        List<String> clean = new ArrayList<String>();
        if (lines != null) {
            for (String l : lines) {
                if (l != null) {
                    clean.add(l.replace("\r", "").replace("\n", " "));
                }
            }
        }
        while (!clean.isEmpty() && clean.get(clean.size() - 1).trim().isEmpty()) {
            clean.remove(clean.size() - 1);
        }
        m.notes = Collections.unmodifiableList(clean);
        m.layout = layout;
        m.publicKey = publicKey;
        m.plugin = plugin;
        m.pluginTarget = pluginTarget;
        return m;
    }

    /**
     * A copy of this release without the given paths (compared ignoring case): the files
     * a plugin has put in place (lib/set.jar) belong to the plugin, not to the release.
     */
    public UpdateManifest without(java.util.Set<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return this;
        }
        java.util.Set<String> lower = new java.util.HashSet<String>();
        for (String p : paths) {
            lower.add(p.toLowerCase());
        }
        Map<String, FileEntry> kept = new LinkedHashMap<String, FileEntry>();
        for (Map.Entry<String, FileEntry> e : entries.entrySet()) {
            if (!lower.contains(e.getKey().toLowerCase())) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        if (kept.size() == entries.size()) {
            return this;
        }
        UpdateManifest m = new UpdateManifest(appBase, uiName, version, mainClass,
                new ArrayList<String>(jvmArgs), new ArrayList<String>(appArgs), kept);
        m.notes = notes;
        m.layout = layout;
        m.publicKey = publicKey;
        m.plugin = plugin;
        m.pluginTarget = pluginTarget;
        return m;
    }

    /** A copy of this plugin release whose files keep their build-folder paths ("."). */
    public UpdateManifest withPluginTarget(String target) {
        UpdateManifest m = withNotes(notes);
        m.pluginTarget = target;
        return m;
    }

    /** The {@code target} line of a plugin release, or null (the files go into lib/). */
    public String pluginTarget() {
        return pluginTarget;
    }

    /** A copy of this release marked as the plugin with this name (null: an application). */
    public UpdateManifest withPlugin(String name) {
        UpdateManifest m = withNotes(notes);
        m.plugin = name;
        return m;
    }

    /** The {@code plugin} line: the plugin's name, or null for an application release. */
    public String plugin() {
        return plugin;
    }

    /** A copy of this release that names the publisher's public key. */
    public UpdateManifest withPublicKey(String key) {
        UpdateManifest m = withNotes(notes);
        m.publicKey = key;
        return m;
    }

    /** The {@code publickey} line, or null. */
    public String publicKey() {
        return publicKey;
    }

    /** A copy of this release using the given server layout ({@link #LAYOUT_NAMED} or null). */
    public UpdateManifest withLayout(String newLayout) {
        UpdateManifest m = withNotes(notes);
        m.layout = newLayout;
        return m;
    }

    /** The {@code layout} line as written, or null for the original layout. */
    public String layout() {
        return layout;
    }

    private boolean primaryIsNamed() {
        return layout != null && layout.split(",")[0].trim().equals(LAYOUT_NAMED);
    }

    /** True when the release is also stored in the original layout for older updaters. */
    public boolean hasHashedCopies() {
        if (layout == null) {
            return true;
        }
        for (String t : layout.split(",")) {
            if (t.trim().equals(LAYOUT_HASHED)) {
                return true;
            }
        }
        return false;
    }

    /** Where a file of this release is stored on the server, relative to {@code files/}. */
    public String storeKey(FileEntry e) {
        return primaryIsNamed() ? namedKey(e) : hashedKey(e);
    }

    /** The other layout's location, tried when the first is not on the server. */
    public String alternateStoreKey(FileEntry e) {
        return primaryIsNamed() ? hashedKey(e) : namedKey(e);
    }

    /** Every location this release puts the file in, relative to {@code files/}. */
    public List<String> serverKeys(FileEntry e) {
        List<String> keys = new ArrayList<String>();
        keys.add(storeKey(e));
        if (primaryIsNamed() && hasHashedCopies()) {
            keys.add(hashedKey(e));
        }
        return keys;
    }

    /** {@code b1fe8b5968b5/GasX.jar}: readable, and a new version is always a new folder. */
    public static String namedKey(FileEntry e) {
        return e.sha256.substring(0, NAMED_PREFIX) + "/" + serverName(baseName(e.path));
    }

    /**
     * Extensions web servers refuse to hand out (IIS: .config, .mdb ...), hide (.htaccess),
     * or even run as programs (.php, .cgi, .asp ...). Files with these get ".file" added to
     * their name on the server only; on PCs they keep their real name.
     */
    private static final java.util.Set<String> RISKY = new java.util.HashSet<String>(
            java.util.Arrays.asList("php", "php3", "php4", "php5", "php7", "php8", "phtml", "pht",
                    "phar", "phps", "cgi", "fcgi", "pl", "py", "rb", "sh", "asp", "aspx", "ascx",
                    "ashx", "asmx", "asax", "axd", "cshtml", "vbhtml", "config", "jsp", "jspx",
                    "shtml", "shtm", "stm", "htaccess", "htpasswd", "user", "ini", "cs", "vb",
                    "mdb", "ldb", "mdf", "ldf", "resx", "resources", "master", "skin", "browser",
                    "sitemap", "licx", "rules", "exclude", "refresh", "compiled", "java", "inc"));

    static String serverName(String name) {
        // Web servers hide or refuse names starting with a dot (".htaccess", ".git").
        String n = name.startsWith(".") ? "_" + name.substring(1) : name;
        String[] parts = n.toLowerCase(java.util.Locale.ROOT).split("\\.");
        boolean risky = false;
        for (int i = 1; i < parts.length && !risky; i++) {
            risky = RISKY.contains(parts[i]); // "x.php.txt" can still run as PHP on Apache
        }
        return risky ? n + ".file" : n;
    }

    /** {@code b1/b1fe8b59...}: the original layout, still read by older updaters. */
    public static String hashedKey(FileEntry e) {
        return e.sha256.substring(0, 2) + "/" + e.sha256;
    }

    static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public Map<String, FileEntry> entries() {
        return entries;
    }

    /** The {@code code} entries, in the order they were listed. */
    public List<FileEntry> classpathEntries() {
        List<FileEntry> out = new ArrayList<FileEntry>();
        for (FileEntry e : entries.values()) {
            if (e.classpath) {
                out.add(e);
            }
        }
        return out;
    }

    public long totalBytes() {
        long total = 0;
        for (FileEntry e : entries.values()) {
            total += e.size;
        }
        return total;
    }

    // ----------------------------------------------------------------- parsing

    public static UpdateManifest parse(String text) throws IOException {
        String appBase = null;
        String uiName = null;
        String version = null;
        String mainClass = null;
        List<String> jvmArgs = new ArrayList<String>();
        List<String> appArgs = new ArrayList<String>();
        List<String> notes = new ArrayList<String>();
        String layout = null;
        String publicKey = null;
        String plugin = null;
        String pluginTarget = null;
        Map<String, FileEntry> entries = new LinkedHashMap<String, FileEntry>();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1); // a BOM left by Notepad
        }

        BufferedReader r = new BufferedReader(new StringReader(text));
        String line;
        int lineNo = 0;
        while ((line = r.readLine()) != null) {
            lineNo++;
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq < 0) {
                throw new IOException("Line " + lineNo + " is not \"key = value\": " + s);
            }
            String key = s.substring(0, eq).trim().toLowerCase();
            String value = s.substring(eq + 1).trim();

            if ("appbase".equals(key)) {
                appBase = value;
            } else if ("ui.name".equals(key)) {
                uiName = value;
            } else if ("version".equals(key)) {
                version = value;
            } else if ("class".equals(key)) {
                mainClass = value;
            } else if ("jvmarg".equals(key)) {
                jvmArgs.add(value);
            } else if ("apparg".equals(key)) {
                appArgs.add(value);
            } else if ("note".equals(key)) {
                notes.add(value);
            } else if ("layout".equals(key)) {
                layout = value.toLowerCase().replace(" ", "");
            } else if ("publickey".equals(key)) {
                publicKey = value;
            } else if ("plugin".equals(key)) {
                plugin = value.isEmpty() ? null : value;
            } else if ("target".equals(key)) {
                pluginTarget = value.isEmpty() ? null : value;
            } else if ("code".equals(key) || "resource".equals(key)) {
                FileEntry fe = parseFileLine(value, "code".equals(key), lineNo);
                entries.put(fe.path, fe);
            }
            // every other key is ignored on purpose
        }
        UpdateManifest parsed = new UpdateManifest(appBase, uiName, version, mainClass, jvmArgs,
                appArgs, entries);
        UpdateManifest result = notes.isEmpty() ? parsed : parsed.withNotes(notes);
        result.layout = layout;
        result.publicKey = publicKey;
        result.plugin = plugin;
        result.pluginTarget = pluginTarget;
        return result;
    }

    private static final java.util.regex.Pattern RESERVED = java.util.regex.Pattern.compile(
            "(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9]|conin\\$|conout\\$)(\\..*)?");

    /**
     * Refuses a file path that could reach outside the application folder or that Windows
     * treats specially: "..", absolute paths, drive letters, network paths, ":" (alternate
     * data streams), device names like CON or NUL, names ending in a dot or a space.
     * A release is a list of files; nothing in it may point anywhere else.
     */
    public static String checkPath(String path) {
        String p = path == null ? "" : path;
        if (p.isEmpty() || p.length() > 400) {
            throw new IllegalArgumentException("Unacceptable file path in the release: \"" + p + "\"");
        }
        if (p.startsWith("/") || p.indexOf('\\') >= 0 || p.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Unacceptable file path in the release: " + p);
        }
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x20 || c == 0x7f || "<>\"|?*".indexOf(c) >= 0) {
                throw new IllegalArgumentException("Unacceptable file path in the release: " + p);
            }
        }
        for (String seg : p.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.endsWith(".")
                    || seg.endsWith(" ") || seg.startsWith(" ") || RESERVED.matcher(seg).matches()) {
                throw new IllegalArgumentException("Unacceptable file path in the release: " + p);
            }
        }
        return p;
    }

    /** A version is used as a folder name: letters, digits and . _ + - only. */
    public static String checkVersion(String v) {
        if (v == null || !v.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}") || v.contains("..")) {
            throw new IllegalArgumentException("Unacceptable version in the release: \""
                    + v + "\" (use letters, digits and . _ + - only)");
        }
        return v;
    }

    private static FileEntry parseFileLine(String value, boolean code, int lineNo) throws IOException {
        String[] parts = value.split("\\|", -1);
        if (parts.length < 3) {
            throw new IOException("Line " + lineNo + " has no size and checksum. Run ManifestTool"
                    + " to generate the server file: " + value);
        }
        long size;
        try {
            size = Long.parseLong(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IOException("Bad size on line " + lineNo + ": " + value);
        }
        String sha = parts[2].trim();
        if (sha.length() != 64) {
            throw new IOException("Bad checksum on line " + lineNo + ": " + value);
        }
        if (!sha.matches("[0-9a-fA-F]{64}") || size < 0) {
            throw new IOException("Bad checksum or size on line " + lineNo + ": " + value);
        }
        String flag = parts.length > 3 ? parts[3].trim().toLowerCase() : "";
        String path = FileEntry.normalize(parts[0]);
        try {
            checkPath(path);
        } catch (IllegalArgumentException e) {
            throw new IOException("Line " + lineNo + ": " + e.getMessage());
        }
        return new FileEntry(path, size, sha, code, flag.equals("exec"), flag.equals("seed"));
    }

    /** True when the text ends with the marker, i.e. it arrived in full. */
    public static boolean isComplete(String text) {
        return text != null && text.trim().endsWith(END_MARKER);
    }

    /** Checks that this is a usable release description, not just a client stub. */
    public void requireRelease() throws IOException {
        if (version == null || version.isEmpty()) {
            throw new IOException("app.txt on the server has no \"version\" line");
        }
        try {
            checkVersion(version);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
        if (entries.isEmpty()) {
            throw new IOException("app.txt on the server lists no code or resource files");
        }
        if (plugin != null) {
            throw new IOException("The download URL holds the plugin \"" + plugin + "\", not an"
                    + " application. Check the appbase line in app.txt.");
        }
        if (mainClass == null || mainClass.isEmpty()) {
            throw new IOException("app.txt on the server has no \"class\" line");
        }
    }

    /** Valid plugin names: what the publisher accepts and PCs expect. */
    public static String checkPluginName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9]([A-Za-z0-9._-]{0,38}[A-Za-z0-9])?")
                || RESERVED.matcher(name).matches()) {
            throw new IllegalArgumentException("Unacceptable plugin name \"" + name
                    + "\": use letters, digits, '.', '_' or '-' (up to 40), e.g. set");
        }
        return name;
    }

    /**
     * Like {@link #requireRelease()}, for a plugin: the release must say it is the plugin
     * {@code name}. An application's release at that address is refused, so a wrong URL
     * can never put an application's files into a plugin folder.
     */
    public void requirePlugin(String name) throws IOException {
        if (version == null || version.isEmpty()) {
            throw new IOException("app.txt at the plugin's address has no \"version\" line");
        }
        try {
            checkVersion(version);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
        if (plugin != null) {
            try {
                checkPluginName(plugin);
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage());
            }
        }
        if (plugin == null) {
            throw new IOException("The address does not hold a plugin release"
                    + (mainClass != null ? " (it holds the application " + (uiName == null ? mainClass : uiName)
                    + ")" : "") + ". Check the URL stored for the " + name + ".");
        }
        if (!plugin.equalsIgnoreCase(name)) {
            throw new IOException("The address holds the plugin \"" + plugin + "\", not \""
                    + name + "\". Check the URL stored for the " + name + ".");
        }
        if (entries.isEmpty()) {
            throw new IOException("The " + name + " release lists no files");
        }
    }

    public static UpdateManifest of(String appBase, String uiName, String version, String mainClass,
            List<String> jvmArgs, List<String> appArgs, List<FileEntry> files) {
        Map<String, FileEntry> map = new LinkedHashMap<String, FileEntry>();
        for (FileEntry f : files) {
            map.put(f.path, f);
        }
        return new UpdateManifest(appBase, uiName, version, mainClass,
                jvmArgs == null ? new ArrayList<String>() : jvmArgs,
                appArgs == null ? new ArrayList<String>() : appArgs, map);
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by org.hp.update.ManifestTool - do not edit by hand.\n\n");
        sb.append("appbase = ").append(appBase == null ? "" : appBase).append('\n');
        if (uiName != null) {
            sb.append("ui.name = ").append(uiName).append('\n');
        }
        sb.append("version = ").append(version).append('\n');
        if (plugin != null) {
            sb.append("plugin  = ").append(plugin).append('\n');
            if (pluginTarget != null) {
                sb.append("target  = ").append(pluginTarget).append('\n');
            }
        }
        if (mainClass != null) {
            sb.append("class   = ").append(mainClass).append('\n');
        }
        for (String a : jvmArgs) {
            sb.append("jvmarg  = ").append(a).append('\n');
        }
        for (String a : appArgs) {
            sb.append("apparg  = ").append(a).append('\n');
        }
        for (String n : notes) {
            sb.append("note    = ").append(n).append('\n');
        }
        if (layout != null) {
            sb.append("layout  = ").append(layout).append('\n');
        }
        if (publicKey != null) {
            sb.append("publickey = ").append(publicKey).append('\n');
        }
        sb.append('\n');
        for (FileEntry e : entries.values()) {
            sb.append(e.toLine()).append('\n');
        }
        sb.append(END_MARKER).append('\n');
        return sb.toString();
    }
}
