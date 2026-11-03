package org.hp.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a finished build folder into the files you upload. This is the engine behind both
 * {@link ManifestTool} (command line) and {@link Publisher} (window).
 *
 * <p>No state, no GUI, no printing: the caller decides how to report progress.</p>
 */
public final class ReleaseBuilder {

    /** Build junk that is never part of a release. */
    private static final String[] ALWAYS_SKIP = {
        ".ds_store", "thumbs.db", "desktop.ini", "readme.txt", "getdown.txt",
        "app.txt", "digest.txt", "digest2.txt", "set.txt"
    };

    /**
     * Marks a client folder: a folder holding one client's set, laid out like the
     * application folder (lib/Set.jar, Forms/..., kop.png). The publisher's Set tab keeps
     * the set's settings in it; the application release never includes such a folder.
     */
    public static final String CLIENT_FOLDER_FILE = "set.txt";

    /** Extensions that usually mean "settings the application writes while running". */
    private static final String[] SETTINGS_EXTENSIONS = {
        ".prp", ".dat", ".properties", ".ini", ".cfg", ".conf"
    };

    private ReleaseBuilder() {
    }

    /** True for files that look like settings an application reads and writes itself. */
    public static boolean isSettingsFile(String path) {
        String lower = path.toLowerCase();
        for (String ext : SETTINGS_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ scanning

    /** One candidate file found in the build folder. */
    public static final class Candidate {

        public final String path;        // relative, forward slashes
        public final long size;
        public final boolean classpath;  // a jar, so it belongs on the classpath
        public final boolean skipByDefault;
        public final String note;        // why it is worth a second look, or null
        /** The client folder (relative) this file is in, or null. */
        public final String clientFolder;

        Candidate(String path, long size, boolean classpath, boolean skipByDefault, String note,
                String clientFolder) {
            this.path = path;
            this.size = size;
            this.classpath = classpath;
            this.skipByDefault = skipByDefault;
            this.note = note;
            this.clientFolder = clientFolder;
        }
    }

    /**
     * Lists what is in the build folder, flagging build junk and files that look like
     * settings the application writes at runtime. Those must stay out of the release:
     * everything in a release is replaced on every update, so a managed settings file
     * would lose the customer's changes each time.
     */
    public static List<Candidate> scan(Path buildDir) throws IOException {
        List<Path> files = Io.listFilesRecursively(buildDir);
        Collections.sort(files);
        // Client folders below the build folder (not the build folder itself): each holds
        // one client's set and is published from the Set tab, never with the application.
        List<String> clientFolders = new ArrayList<String>();
        for (Path f : files) {
            String rel = Io.relative(buildDir, f);
            if (f.getFileName().toString().equalsIgnoreCase(CLIENT_FOLDER_FILE) && rel.indexOf('/') > 0) {
                clientFolders.add(rel.substring(0, rel.lastIndexOf('/')));
            }
        }
        List<Candidate> out = new ArrayList<Candidate>();
        for (Path f : files) {
            String rel = Io.relative(buildDir, f);
            String lower = rel.toLowerCase();
            String name = f.getFileName().toString().toLowerCase();

            boolean skip = false;
            String note = null;
            for (String junk : ALWAYS_SKIP) {
                if (name.equals(junk)) {
                    skip = true;
                    note = "not part of the application";
                }
            }
            if (lower.startsWith("__macosx") || lower.contains("/__macosx/")) {
                skip = true;
                note = "macOS archive leftover";
            }
            if (lower.endsWith(".part") || lower.endsWith(".tmp") || lower.endsWith(".bak")) {
                skip = true;
                note = "temporary file";
            }
            // Other .exe files (GasX.exe) are published like any file, so a new one is never
            // forgotten; only the launcher stays out: it is needed before anything is installed.
            if (!skip && (name.equals("launcher.exe") || name.equals("launcher.jar"))) {
                skip = true;
                note = "launcher - stays next to the application, not in the release";
            }
            String client = null;
            for (String c : clientFolders) {
                if (rel.startsWith(c + "/")) {
                    client = c;
                    skip = true;
                    note = "client folder " + c + " (Set tab)";
                }
            }

            out.add(new Candidate(rel, Files.size(f), lower.endsWith(".jar"), skip, note, client));
        }
        return out;
    }

    /**
     * Reads {@code appbase}, {@code ui.name}, {@code class} and {@code jvmarg} out of an
     * existing config file. Works with this updater's {@code app.txt} and with a getdown
     * {@code getdown.txt}, since the keys are the same; getdown's {@code code} lines are
     * ignored because the file list now comes from the folder itself.
     */
    public static UpdateManifest readConfig(Path file) throws IOException {
        String text = Io.readString(file);
        if (text == null) {
            throw new IOException("Cannot read " + file);
        }
        // Strip file lines that carry no checksum, so a getdown.txt parses cleanly.
        StringBuilder clean = new StringBuilder();
        for (String raw : text.split("\n")) {
            String s = raw.trim();
            boolean fileLine = s.toLowerCase().startsWith("code")
                    || s.toLowerCase().startsWith("resource");
            if (fileLine && s.indexOf('|') < 0) {
                continue;
            }
            clean.append(raw).append('\n');
        }
        return UpdateManifest.parse(clean.toString());
    }

    /** Main-Class from a jar's manifest, or null. Used to prefill the main class. */
    public static String mainClassOf(Path jar) {
        java.util.zip.ZipFile zip = null;
        try {
            zip = new java.util.zip.ZipFile(jar.toFile());
            java.util.zip.ZipEntry e = zip.getEntry("META-INF/MANIFEST.MF");
            if (e == null) {
                return null;
            }
            java.util.jar.Manifest mf = new java.util.jar.Manifest(zip.getInputStream(e));
            return mf.getMainAttributes().getValue("Main-Class");
        } catch (Exception ex) {
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    // ------------------------------------------------------------------ building

    /** What to build. */
    public static final class Options {

        public Path buildDir;
        public Path outDir;
        public String version;
        public String appBase;
        public String uiName;
        public String mainClass;
        /**
         * For a plugin release (a client's set.jar): its name, e.g. "set". The release then
         * has no main class, and PCs load it with {@link PluginUpdate}. Null for an
         * application.
         */
        public String plugin;
        public List<String> jvmArgs = new ArrayList<String>();
        public List<String> appArgs = new ArrayList<String>();
        /** Relative paths to leave out of the release. */
        public Set<String> exclude = new LinkedHashSet<String>();
        /** Relative paths to mark executable on Linux and macOS. */
        public Set<String> executable = new HashSet<String>();
        /** "What's new" text shown to users with the update; may be empty. */
        public List<String> notes = new ArrayList<String>();
        /**
         * Also store files in the original checksum-only layout, for PCs whose updater (or
         * launcher.jar) predates readable names. On unless switched off: a PC that cannot
         * download an update cannot run at all while updates are mandatory.
         */
        public Boolean oldLayout;
        /**
         * The key to sign with. Signing is switched off for now (see TODO.md): releases are
         * signed only when a key is given here or -Dhp.sign=true is set.
         */
        public java.security.KeyPair signingKey;
        /**
         * For a plugin: "." keeps the build-folder paths on the PC (lib/set.jar,
         * config.dat); null puts every file into lib/ (the original rule).
         */
        public String pluginTarget;

        public boolean isPlugin() {
            return plugin != null && !plugin.trim().isEmpty();
        }

        /** True when this release will be signed. */
        boolean signing() {
            return signingKey != null || Boolean.getBoolean("hp.sign");
        }

        public void validate() throws IOException {
            if (buildDir == null || !Files.isDirectory(buildDir)) {
                throw new IOException("Choose a build folder (the one holding your application jar)");
            }
            if (outDir == null) {
                throw new IOException("Choose an output folder");
            }
            if (version == null || version.trim().isEmpty()) {
                throw new IOException("Enter a version, for example 1.0.0");
            }
            try {
                UpdateManifest.checkVersion(version.trim());
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage());
            }
            if (appBase == null || appBase.trim().isEmpty()) {
                throw new IOException("Enter the download URL (appbase)");
            }
            String lower = appBase.trim().toLowerCase();
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw new IOException("The download URL must be a web address starting with"
                        + " http:// or https://, for example http://172.104.47.59:8080/apps/gasx/"
                        + "\n(the folder on the server goes into the Upload window, not here)");
            }
            if (pluginTarget != null && !".".equals(pluginTarget)) {
                throw new IOException("A plugin's target can only be \".\" (or none)");
            }
            if (isPlugin()) {
                try {
                    UpdateManifest.checkPluginName(plugin.trim());
                } catch (IllegalArgumentException e) {
                    throw new IOException(e.getMessage());
                }
            } else if (mainClass == null || mainClass.trim().isEmpty()) {
                throw new IOException("Enter the main class, for example gasx.Main");
            }
            if (outDir.toAbsolutePath().normalize()
                    .startsWith(buildDir.toAbsolutePath().normalize())) {
                throw new IOException("The output folder must be outside the build folder");
            }
        }
    }

    /**
     * True when an earlier release in this output folder used the original layout, so
     * PCs may still have an updater that only knows that layout.
     */
    public static boolean usedOldLayout(Path outDir) {
        Path files = outDir.resolve("files");
        if (!Files.isDirectory(files)) {
            return false;
        }
        java.io.File[] dirs = files.toFile().listFiles();
        if (dirs != null) {
            for (java.io.File d : dirs) {
                if (d.isDirectory() && d.getName().matches("[0-9a-f]{2}")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A file name that cannot be published safely: it would break the app.txt line, or
     * the commands sent to an FTP server.
     */
    static String badName(String rel) {
        try {
            UpdateManifest.checkPath(rel);
        } catch (IllegalArgumentException e) {
            return "is not allowed in a release (" + e.getMessage() + ")";
        }
        for (int i = 0; i < rel.length(); i++) {
            char c = rel.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return "contains a control character";
            }
            if (c == '|') {
                return "contains '|'";
            }
        }
        for (String seg : rel.split("/")) {
            if (seg.equals("..") || seg.equals(".")) {
                return "contains '" + seg + "'";
            }
        }
        return null;
    }

    /** Progress callbacks. Called on whichever thread runs the build. */
    public interface Progress {

        Progress NOOP = new Progress() {
            @Override
            public void file(String path, long size, int index, int total) {
            }

            @Override
            public void log(String message) {
            }
        };

        void file(String path, long size, int index, int total);

        void log(String message);
    }

    /** What was produced. */
    public static final class Result {

        public final int fileCount;
        public final long totalBytes;
        public final int newFiles;
        public final long newBytes;
        public final Path manifest;
        public final Path outDir;

        Result(int fileCount, long totalBytes, int newFiles, long newBytes, Path manifest, Path outDir) {
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
            this.newFiles = newFiles;
            this.newBytes = newBytes;
            this.manifest = manifest;
            this.outDir = outDir;
        }
    }

    /**
     * Builds the upload folder: one copy of every file named by its checksum, plus the
     * {@code app.txt} that describes the release. Files already present from an earlier
     * release are left alone, so only genuinely new content is added.
     */
    public static Result build(Options o, Progress progress) throws IOException {
        o.validate();
        Progress p = progress == null ? Progress.NOOP : progress;

        Path blobs = o.outDir.resolve("files");
        // A plugin is new: no PC has an updater for it that predates readable names.
        boolean oldLayout = o.oldLayout == null ? !o.isPlugin() : o.oldLayout.booleanValue();
        Files.createDirectories(blobs);

        List<Path> files = Io.listFilesRecursively(o.buildDir);
        Collections.sort(files);

        List<UpdateManifest.FileEntry> entries = new ArrayList<UpdateManifest.FileEntry>();
        long totalBytes = 0;
        int newFiles = 0;
        long newBytes = 0;
        int index = 0;
        int total = files.size();

        for (Path f : files) {
            index++;
            String rel = Io.relative(o.buildDir, f);
            if (o.exclude.contains(rel)) {
                p.log("skipped  " + rel);
                continue;
            }
            String bad = badName(rel);
            if (bad != null) {
                throw new IOException("Cannot publish \"" + rel + "\": the name " + bad
                        + ". Rename it, or untick it.");
            }
            long size = Files.size(f);
            p.file(rel, size, index, total);

            String sha = Io.sha256(f);
            boolean code = rel.toLowerCase().endsWith(".jar");
            boolean seed = false; // every published file is updated the same way
            UpdateManifest.FileEntry entry = new UpdateManifest.FileEntry(rel, size, sha, code,
                    o.executable.contains(rel), seed);
            entries.add(entry);
            totalBytes += size;

            List<String> keys = new ArrayList<String>();
            keys.add(UpdateManifest.namedKey(entry));
            if (oldLayout) {
                keys.add(UpdateManifest.hashedKey(entry));
            }
            boolean added = false;
            for (String key : keys) {
                Path blob = blobs.resolve(key);
                if (!(Files.exists(blob) && Io.matches(blob, size, sha))) {
                    Io.copyOrLink(f, blob);
                    added = true;
                }
            }
            if (added) {
                newFiles++;
                newBytes += size;
                p.log((code ? "code      " : "resource  ") + rel);
            } else {
                p.log("unchanged  " + rel);
            }
        }
        if (entries.isEmpty()) {
            throw new IOException("Nothing to publish: every file in " + o.buildDir
                    + " was excluded, or the folder is empty");
        }
        boolean hasCode = false;
        for (UpdateManifest.FileEntry e : entries) {
            hasCode = hasCode || e.classpath;
        }
        if (!hasCode) {
            throw new IOException("No .jar files in the release, so there would be no classpath");
        }

        UpdateManifest m = UpdateManifest.of(normalizeUrl(o.appBase), trimToNull(o.uiName),
                o.version.trim(), o.isPlugin() ? null : o.mainClass.trim(),
                o.isPlugin() ? new ArrayList<String>() : o.jvmArgs,
                o.isPlugin() ? new ArrayList<String>() : o.appArgs, entries)
                .withNotes(o.notes)
                .withLayout(oldLayout
                        ? UpdateManifest.LAYOUT_NAMED + "," + UpdateManifest.LAYOUT_HASHED
                        : UpdateManifest.LAYOUT_NAMED)
                .withPlugin(o.isPlugin() ? o.plugin.trim() : null)
                .withPluginTarget(o.isPlugin() ? o.pluginTarget : null);
        Path manifest = o.outDir.resolve(UpdateConfig.APP_TXT);
        if (o.signing()) {
            // Signed with your key, so PCs can tell it really comes from you.
            java.security.KeyPair key = o.signingKey != null ? o.signingKey : Signing.publisherKey();
            m = m.withPublicKey(Signing.encode(key.getPublic()));
            Io.writeStringAtomic(manifest, Signing.sign(m.toText(), key.getPrivate()));
            p.log("signed with key " + Signing.fingerprint(key.getPublic()) + " (" + Signing.keyFile() + ")");
        } else {
            Io.writeStringAtomic(manifest, m.toText());
        }

        return new Result(entries.size(), totalBytes, newFiles, newBytes, manifest, o.outDir);
    }

    /** What a new release will do to customers' PCs, compared with the previous one. */
    public static final class Impact {
        /** Libraries in the previous release but not this one: deleted from PCs. */
        public final List<String> removedJars = new ArrayList<String>();
        /** Other files no longer published: left on PCs as they are, no longer updated. */
        public final List<String> unmanaged = new ArrayList<String>();
        /** Non-jar files whose content changed: each PC's copy will be replaced. */
        public final List<String> replacedFiles = new ArrayList<String>();
        public String previousVersion;

        public boolean worthMentioning() {
            return !removedJars.isEmpty() || !unmanaged.isEmpty() || !replacedFiles.isEmpty();
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (!replacedFiles.isEmpty()) {
                sb.append("Changed since ").append(previousVersion)
                        .append(" - every customer's copy will be REPLACED:\n");
                for (String f : replacedFiles) {
                    sb.append("    ").append(f).append('\n');
                }
                sb.append('\n');
            }
            if (!removedJars.isEmpty()) {
                sb.append("Libraries no longer published - DELETED from customers' PCs:\n");
                for (String f : removedJars) {
                    sb.append("    ").append(f).append('\n');
                }
                sb.append('\n');
            }
            if (!unmanaged.isEmpty()) {
                sb.append("No longer published - customers keep their current copy,"
                        + " it is just not updated any more:\n");
                for (String f : unmanaged) {
                    sb.append("    ").append(f).append('\n');
                }
            }
            return sb.toString().trim();
        }
    }

    /**
     * Compares what is about to be published with the release already in the output
     * folder. Jar files are left out of "replaced": a changed library is routine.
     */
    public static Impact preview(Options o) throws IOException {
        Impact impact = new Impact();
        Path prevFile = o.outDir == null ? null : o.outDir.resolve(UpdateConfig.APP_TXT);
        String prevText = prevFile == null ? null : Io.readString(prevFile);
        if (prevText == null) {
            return impact;
        }
        UpdateManifest prev = UpdateManifest.parse(prevText);
        impact.previousVersion = prev.version();
        Set<String> now = new HashSet<String>();
        for (Path f : Io.listFilesRecursively(o.buildDir)) {
            String rel = Io.relative(o.buildDir, f);
            if (o.exclude.contains(rel)) {
                continue;
            }
            now.add(rel);
            UpdateManifest.FileEntry before = prev.entries().get(rel);
            if (before != null && !before.classpath && !rel.toLowerCase().endsWith(".jar")
                    && !Io.hashEquals(before.sha256, Io.sha256(f))) {
                impact.replacedFiles.add(rel);
            }
        }
        for (UpdateManifest.FileEntry e : prev.entries().values()) {
            if (!now.contains(e.path)) {
                (e.classpath ? impact.removedJars : impact.unmanaged).add(e.path);
            }
        }
        return impact;
    }

    /** Writes the two files a client PC needs, next to the upload folder. */
    public static Path writeClientFiles(Options o, Path clientDir, String batName) throws IOException {
        Files.createDirectories(clientDir);
        StringBuilder sb = new StringBuilder();
        sb.append("appbase = ").append(normalizeUrl(o.appBase)).append('\n');
        if (trimToNull(o.uiName) != null) {
            sb.append("ui.name = ").append(o.uiName.trim()).append('\n');
        }
        if (o.signing()) {
            java.security.KeyPair key = o.signingKey != null ? o.signingKey : Signing.publisherKey();
            sb.append("\n# your publisher key: this PC installs only releases signed with it\n");
            sb.append("publickey = ").append(Signing.encode(key.getPublic())).append('\n');
        }
        Io.writeStringAtomic(clientDir.resolve(UpdateConfig.APP_TXT), sb.toString());

        String bat = "@echo off\r\n"
                + "rem Starts the application through the updater.\r\n"
                + "rem Keep this file, launcher.jar and app.txt in the same folder.\r\n"
                + "start \"\" javaw -jar \"%~dp0launcher.jar\" \"%~dp0.\"\r\n";
        Path batFile = clientDir.resolve(batName);
        Io.writeStringAtomic(batFile, bat);
        return clientDir;
    }

    /** Saves the hand-edited part of the settings as an {@code app.txt} you keep in the project. */
    public static void saveConfig(Options o, Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Build configuration. The file list is not here on purpose:\n");
        sb.append("# it is read from the build folder every time you publish.\n\n");
        sb.append("appbase = ").append(normalizeUrl(o.appBase)).append('\n');
        if (trimToNull(o.uiName) != null) {
            sb.append("ui.name = ").append(o.uiName.trim()).append('\n');
        }
        if (o.isPlugin()) {
            sb.append("plugin  = ").append(o.plugin.trim()).append('\n');
            if (o.pluginTarget != null) {
                sb.append("target  = ").append(o.pluginTarget).append('\n');
            }
        } else {
            sb.append("class   = ").append(o.mainClass == null ? "" : o.mainClass.trim()).append('\n');
        }
        for (String a : o.isPlugin() ? new ArrayList<String>() : o.jvmArgs) {
            sb.append("jvmarg  = ").append(a).append('\n');
        }
        for (String a : o.isPlugin() ? new ArrayList<String>() : o.appArgs) {
            sb.append("apparg  = ").append(a).append('\n');
        }
        if (o.outDir != null) {
            sb.append("\n# Where the publisher writes this project's releases.\n");
            sb.append("# out = ").append(outDirText(o)).append('\n');
        }
        if (!o.exclude.isEmpty()) {
            sb.append("\n# Files in the build folder that are deliberately not published.\n");
            for (String e : o.exclude) {
                sb.append("# exclude = ").append(e).append('\n');
            }
        }
        Io.writeStringAtomic(file, sb.toString());
    }

    /** The output folder, relative to the project folder when it is inside it (Git, two PCs). */
    private static String outDirText(Options o) {
        Path out = o.outDir.toAbsolutePath().normalize();
        Path project = o.buildDir.toAbsolutePath().normalize().getParent();
        if (project != null && out.startsWith(project)) {
            return project.relativize(out).toString().replace('\\', '/');
        }
        return out.toString();
    }

    /**
     * The output folder recorded by {@link #saveConfig}, or null. A relative one is inside
     * the project folder; an absolute one only counts if its parent exists on this PC.
     */
    public static Path readOutDir(Path config, Path buildDir) {
        String text = Io.readString(config);
        if (text == null) {
            return null;
        }
        for (String raw : text.split("\n")) {
            String s = raw.trim();
            if (s.startsWith("# out") && s.indexOf('=') > 0
                    && s.substring(1, s.indexOf('=')).trim().equals("out")) {
                String v = s.substring(s.indexOf('=') + 1).trim();
                if (v.isEmpty()) {
                    return null;
                }
                try {
                    Path p = java.nio.file.Paths.get(v);
                    if (!p.isAbsolute()) {
                        Path project = buildDir.toAbsolutePath().normalize().getParent();
                        return project == null ? null : project.resolve(p).normalize();
                    }
                    return p.getParent() != null && Files.isDirectory(p.getParent()) ? p : null;
                } catch (RuntimeException e) {
                    return null; // a Windows path read on the Mac, or the other way round
                }
            }
        }
        return null;
    }

    /** Excluded paths recorded as comments by {@link #saveConfig}. */
    public static Set<String> readExcludes(Path file) {
        Set<String> out = new LinkedHashSet<String>();
        String text = Io.readString(file);
        if (text == null) {
            return out;
        }
        for (String raw : text.split("\n")) {
            String s = raw.trim();
            if (s.startsWith("# exclude")) {
                int eq = s.indexOf('=');
                if (eq > 0) {
                    out.add(s.substring(eq + 1).trim());
                }
            }
        }
        return out;
    }

    public static String normalizeUrl(String url) {
        String s = url.trim();
        return s.endsWith("/") ? s : s + "/";
    }

    static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** "1.0.3" -> "1.0.4". Returns null when the version is not numeric at the end. */
    public static String nextVersion(String version) {
        if (version == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*?)(\\d+)(\\D*)$").matcher(version.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            long n = Long.parseLong(m.group(2)) + 1;
            return m.group(1) + n + m.group(3);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
