package org.hp.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Settings for one application, read from {@code app.txt} in its folder.
 *
 * <p>Every application gets its own folder, and that folder is all the launcher needs.
 * The file can be as short as this:</p>
 *
 * <pre>
 * appbase = http://172.104.47.59:8080/apps/accord/
 * ui.name = Saint
 * </pre>
 *
 * <p>Two applications means two folders, each with its own {@code app.txt}. The same
 * {@code launcher.jar} runs both.</p>
 *
 * <p>Optional extra lines, all with sensible defaults:</p>
 *
 * <pre>
 * check-on-start = true   # look for updates every launch
 * keep-versions  = 2      # old versions kept for rollback
 * </pre>
 */
public final class UpdateConfig {

    /** The one config file, in the application folder. */
    public static final String APP_TXT = "app.txt";

    private String baseUrl;
    private Path appDir;
    private String uiName;
    private String javaExe;
    /** The publisher's public key: only releases signed with it are accepted. */
    private String publicKey;

    private String manifestName = APP_TXT;
    private String blobPath = "files/";

    private int connectTimeoutMs = 15000;
    private int readTimeoutMs = 30000;
    private int maxAttemptsPerFile = 6;      // consecutive attempts with no progress
    private int maxTotalAttemptsPerFile = 300; // hard stop, however much progress is made
    private long retryBaseDelayMs = 800;
    private long retryMaxDelayMs = 15000;
    private int maxRedirects = 5;

    private int keepVersions = 1;
    private int maxBootAttempts = 2;
    /** Kept only so an older app.txt that still sets it does not fail to parse. */
    private long minHealthyRunSec = 10;
    private boolean checkOnStart = true;
    private boolean mandatory = true;
    private boolean allowOffline = true;
    private int recheckMinutes = 30;
    private int checkAttempts = 1;
    private int checkConnectTimeoutMs = 5000;
    private int checkReadTimeoutMs = 8000;
    private int minDownloadKbps = 20;
    private int slowGraceSec = 15;

    private String userAgent = "hp-updater/1.1";
    private boolean useHardLinks = true;

    public UpdateConfig(String baseUrl, Path appDir) {
        setBaseUrl(baseUrl);
        this.appDir = appDir.toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------ load and save

    /** Reads {@code app.txt} from an application folder. */
    public static UpdateConfig load(Path appDir) throws IOException {
        Path file = appDir.resolve(APP_TXT);
        String text = Io.readString(file);
        if (text == null) {
            throw new IOException("No " + APP_TXT + " in " + appDir);
        }
        UpdateManifest m = UpdateManifest.parse(text);
        if (m.appBase() == null || m.appBase().isEmpty()) {
            throw new IOException(file + " has no \"appbase\" line");
        }
        UpdateConfig cfg = new UpdateConfig(m.appBase(), appDir);
        cfg.uiName = m.uiName();
        cfg.checkOnStart = boolLine(text, "check-on-start", true);
        cfg.mandatory = boolLine(text, "mandatory", true);
        // getdown spells it allow_offline; accept both
        String offline = line(text, "allow-offline");
        if (offline == null) {
            offline = line(text, "allow_offline");
        }
        cfg.allowOffline = offline == null || !"false".equalsIgnoreCase(offline);
        cfg.recheckMinutes = Math.max(0, intLine(text, "recheck-minutes", 30));
        cfg.checkAttempts = Math.max(1, intLine(text, "check-attempts", 1));
        cfg.checkConnectTimeoutMs = Math.max(500, intLine(text, "check-connect-timeout-ms", 5000));
        cfg.checkReadTimeoutMs = Math.max(500, intLine(text, "check-read-timeout-ms", 8000));
        cfg.minDownloadKbps = Math.max(0, intLine(text, "min-download-kbps", 20));
        cfg.slowGraceSec = Math.max(1, intLine(text, "slow-grace-sec", 15));
        cfg.javaExe = trimToNullStatic(line(text, "java"));
        cfg.publicKey = trimToNullStatic(line(text, "publickey"));
        cfg.keepVersions = Math.max(1, intLine(text, "keep-versions", 1));
        cfg.maxBootAttempts = Math.max(1, intLine(text, "max-boot-attempts", 2));
        cfg.minHealthyRunSec = intLine(text, "healthy-after-sec",
                intLine(text, "min-healthy-run-sec", 10));
        cfg.maxAttemptsPerFile = Math.max(1, intLine(text, "max-attempts", 6));
        cfg.maxTotalAttemptsPerFile = Math.max(1, intLine(text, "max-total-attempts", 300));
        cfg.connectTimeoutMs = intLine(text, "connect-timeout-ms", 15000);
        cfg.readTimeoutMs = intLine(text, "read-timeout-ms", 30000);
        return cfg;
    }

    /** Writes a minimal {@code app.txt}, enough for the launcher to find the server. */
    public void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("appbase = ").append(baseUrl).append('\n');
        if (uiName != null) {
            sb.append("ui.name = ").append(uiName).append('\n');
        }
        if (!checkOnStart) {
            sb.append("check-on-start = false\n");
        }
        Io.writeStringAtomic(appDir.resolve(APP_TXT), sb.toString());
    }

    /**
     * Updates the application folder's {@code app.txt} after a successful install, so it
     * names the version actually installed.
     *
     * <p>Lines the user put there (appbase, ui.name, java, tuning) are kept exactly as
     * they are. Any {@code code}/{@code resource} lines are dropped: those describe a
     * release and belong in the server's file, and leaving a stale copy here is the kind
     * of thing that makes people think the update did not happen.</p>
     */
    public void writeInstalledVersion(String installedVersion) throws IOException {
        Path file = appDir.resolve(APP_TXT);
        String text = Io.readString(file);
        if (text != null && installedVersion.equals(line(text, "version"))) {
            return; // already says so; no need to write (it may even be read-only)
        }
        try {
            writeVersionLine(file, text, installedVersion);
        } catch (IOException e) {
            // Only a convenience for people reading app.txt; installed.txt is what counts.
            System.err.println("[updater] could not update the version line in " + file + ": " + e);
        }
    }

    private void writeVersionLine(Path file, String text, String installedVersion)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean wroteVersion = false;
        if (text != null) {
            for (String raw : text.split("\n")) {
                String t = raw.trim();
                String key = t.indexOf('=') > 0
                        ? t.substring(0, t.indexOf('=')).trim().toLowerCase() : "";
                if (key.equals("code") || key.equals("resource")) {
                    continue;
                }
                if (key.equals("version")) {
                    sb.append("version = ").append(installedVersion).append('\n');
                    wroteVersion = true;
                    continue;
                }
                sb.append(raw).append('\n');
            }
        } else {
            sb.append("appbase = ").append(baseUrl).append('\n');
            if (uiName != null) {
                sb.append("ui.name = ").append(uiName).append('\n');
            }
        }
        if (!wroteVersion) {
            while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                sb.setLength(sb.length() - 1);
            }
            sb.append("\n\n# installed version, written by the launcher\n");
            sb.append("version = ").append(installedVersion).append('\n');
        }
        Io.writeStringAtomic(file, sb.toString());
    }

    /**
     * Takes the network settings and the publisher key from the application's own
     * app.txt, for a plugin that is downloaded from somewhere else.
     */
    void copyTuningFrom(UpdateConfig app) {
        publicKey = app.publicKey;
        connectTimeoutMs = app.connectTimeoutMs;
        readTimeoutMs = app.readTimeoutMs;
        maxAttemptsPerFile = app.maxAttemptsPerFile;
        maxTotalAttemptsPerFile = app.maxTotalAttemptsPerFile;
        checkAttempts = app.checkAttempts;
        checkConnectTimeoutMs = app.checkConnectTimeoutMs;
        checkReadTimeoutMs = app.checkReadTimeoutMs;
        minDownloadKbps = app.minDownloadKbps;
        slowGraceSec = app.slowGraceSec;
        useHardLinks = app.useHardLinks;
    }

    /** The publisher's public key from this PC's app.txt, or null when none is set yet. */
    public String publicKey() {
        return publicKey;
    }

    /**
     * Remembers the publisher's key in this PC's app.txt, the first time a signed release
     * arrives. From then on only releases signed with it are accepted.
     */
    public void rememberPublicKey(String key) {
        this.publicKey = key;
        Path file = appDir.resolve(APP_TXT);
        String text = Io.readString(file);
        if (text == null || line(text, "publickey") != null) {
            return;
        }
        try {
            Io.writeStringAtomic(file, text + "\n\n# the publisher's key: only releases signed with"
                    + " it are installed\npublickey = " + key + "\n");
        } catch (IOException ignored) {
            // read-only app.txt: it is checked against the release's key again next time
        }
    }

    /**
     * Config for the application that is running right now, from the properties the
     * launcher passed it. Use this inside your app for a "Check for updates" menu item.
     */
    public static UpdateConfig fromSystemProperties() {
        String dir = System.getProperty("update.appdir");
        if (dir == null) {
            throw new IllegalStateException("Not started by launcher.jar (update.appdir unset)");
        }
        try {
            return load(Paths.get(dir));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + APP_TXT + " in " + dir, e);
        }
    }

    /** Explicit java executable from app.txt, or null to use the launcher's own JVM. */
    public String javaExe() {
        return javaExe;
    }

    public UpdateConfig setJavaExe(String path) {
        this.javaExe = trimToNullStatic(path);
        return this;
    }

    private static String trimToNullStatic(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Reads a simple "key = value" line straight out of the config text. */
    private static String line(String text, String key) {
        for (String raw : text.split("\n")) {
            String s = raw.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq > 0 && s.substring(0, eq).trim().equalsIgnoreCase(key)) {
                return s.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static int intLine(String text, String key, int fallback) {
        try {
            String v = line(text, key);
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolLine(String text, String key, boolean fallback) {
        String v = line(text, key);
        return v == null ? fallback : !"false".equalsIgnoreCase(v);
    }

    // ------------------------------------------------------------------ identity

    /** Name for windows and messages: {@code ui.name}, or the folder name. */
    public String displayName() {
        if (uiName != null && !uiName.isEmpty()) {
            return uiName;
        }
        Path name = appDir.getFileName();
        return name == null ? "Application" : name.toString();
    }

    public UpdateConfig setUiName(String name) {
        this.uiName = name;
        return this;
    }

    // --------------------------------------------------------------------- urls

    public String baseUrl() {
        return baseUrl;
    }

    public UpdateConfig setBaseUrl(String url) {
        this.baseUrl = url.endsWith("/") ? url : url + "/";
        return this;
    }

    public String manifestUrl() {
        return baseUrl + manifestName;
    }

    public String blobUrl(String sha256) {
        return baseUrl + blobPath + sha256.substring(0, 2) + "/" + sha256;
    }

    /**
     * Where to download a file of a release: first where that release says it is, then
     * the other layout, so a release published either way can always be installed.
     */
    public String[] fileUrls(UpdateManifest release, UpdateManifest.FileEntry e) {
        return new String[] {
            baseUrl + blobPath + Io.encodeUrlPath(release.storeKey(e)),
            baseUrl + blobPath + Io.encodeUrlPath(release.alternateStoreKey(e))
        };
    }

    public UpdateConfig setBlobPath(String p) {
        this.blobPath = p.isEmpty() || p.endsWith("/") ? p : p + "/";
        return this;
    }

    // ------------------------------------------------------------------- layout

    /** The application's folder: config, version pointers and installed versions. */
    public Path appDir() {
        return appDir;
    }

    /** Older name for {@link #appDir()}. */
    public Path appHome() {
        return appDir;
    }

    /**
     * The folder holding {@code launcher.jar}, used when no application folder was
     * given on the command line.
     */
    public static Path launcherDir() {
        try {
            Path jar = Paths.get(UpdateConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isRegularFile(jar) ? jar.getParent() : jar;
            if (dir != null) {
                return dir.toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return Paths.get(".").toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ tuning

    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public UpdateConfig setConnectTimeoutMs(int ms) {
        this.connectTimeoutMs = ms;
        return this;
    }

    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    public UpdateConfig setReadTimeoutMs(int ms) {
        this.readTimeoutMs = ms;
        return this;
    }

    public int maxAttemptsPerFile() {
        return maxAttemptsPerFile;
    }

    public UpdateConfig setMaxAttemptsPerFile(int n) {
        this.maxAttemptsPerFile = Math.max(1, n);
        return this;
    }

    public int maxTotalAttemptsPerFile() {
        return maxTotalAttemptsPerFile;
    }

    public UpdateConfig setMaxTotalAttemptsPerFile(int n) {
        this.maxTotalAttemptsPerFile = Math.max(1, n);
        return this;
    }

    public long retryBaseDelayMs() {
        return retryBaseDelayMs;
    }

    public long retryMaxDelayMs() {
        return retryMaxDelayMs;
    }

    public UpdateConfig setRetryDelays(long baseMs, long maxMs) {
        this.retryBaseDelayMs = baseMs;
        this.retryMaxDelayMs = maxMs;
        return this;
    }

    public int maxRedirects() {
        return maxRedirects;
    }

    public int keepVersions() {
        return keepVersions;
    }

    /**
     * How many installed versions to keep. 1 (the default) means the previous version is
     * deleted as soon as the new one has started successfully; 2 or more keeps older ones
     * on disk.
     */
    public UpdateConfig setKeepVersions(int n) {
        this.keepVersions = Math.max(1, n);
        return this;
    }

    public int maxBootAttempts() {
        return maxBootAttempts;
    }

    public UpdateConfig setMaxBootAttempts(int n) {
        this.maxBootAttempts = Math.max(1, n);
        return this;
    }

    /**
     * @deprecated the launcher no longer waits: a successful start is decided from what
     *             the Java runtime reports, not from how long the application survives.
     */
    @Deprecated
    public long minHealthyRunSec() {
        return minHealthyRunSec;
    }

    public UpdateConfig setMinHealthyRunSec(long sec) {
        this.minHealthyRunSec = sec;
        return this;
    }

    public boolean checkOnStart() {
        return checkOnStart;
    }

    /**
     * Mandatory updates (the default). Once the server offers a newer version, the old one
     * is never started again: there is no "skip", a slow connection is waited out, and a
     * failed download stops with Retry instead of falling back. Use this when releases
     * change the database and an old client would break against it.
     */
    public boolean mandatory() {
        return mandatory;
    }

    public UpdateConfig setMandatory(boolean b) {
        this.mandatory = b;
        return this;
    }

    /**
     * Whether the installed version may start when the update server cannot be reached
     * at all. Then nobody can know whether an update exists, so the default is yes, as
     * with getdown's {@code allow_offline = true}.
     */
    public boolean allowOffline() {
        return allowOffline;
    }

    public UpdateConfig setAllowOffline(boolean b) {
        this.allowOffline = b;
        return this;
    }

    /** How often a running application looks for a new release, in minutes. 0 = never. */
    public int recheckMinutes() {
        return recheckMinutes;
    }

    /**
     * How many times to ask the server for the release description at startup. A server
     * that is down answers with a connection error, or not at all until the connect
     * timeout expires; either way there is no sense in retrying for minutes when a
     * working version is already installed, so this is deliberately small.
     */
    public int checkAttempts() {
        return checkAttempts;
    }

    public UpdateConfig setCheckAttempts(int n) {
        this.checkAttempts = Math.max(1, n);
        return this;
    }

    /**
     * Timeouts for the startup check only. The release description is a couple of
     * kilobytes, so a server that has not answered in a few seconds is not going to, and
     * the user should not be kept waiting for it when a working version is installed.
     * Downloading the update itself keeps the longer timeouts.
     */
    public int checkConnectTimeoutMs() {
        return checkConnectTimeoutMs;
    }

    public int checkReadTimeoutMs() {
        return checkReadTimeoutMs;
    }

    public UpdateConfig setCheckTimeouts(int connectMs, int readMs) {
        this.checkConnectTimeoutMs = Math.max(500, connectMs);
        this.checkReadTimeoutMs = Math.max(500, readMs);
        return this;
    }

    /**
     * Download speed below which an update is abandoned for now, in KB per second.
     * The part that was downloaded is kept and continues next time. 0 disables the check.
     */
    public int minDownloadKbps() {
        return minDownloadKbps;
    }

    public UpdateConfig setMinDownloadKbps(int kbps) {
        this.minDownloadKbps = Math.max(0, kbps);
        return this;
    }

    /** How long a transfer is given before its speed is judged. */
    public int slowGraceSec() {
        return slowGraceSec;
    }

    public UpdateConfig setSlowGraceSec(int sec) {
        this.slowGraceSec = Math.max(1, sec);
        return this;
    }

    public UpdateConfig setCheckOnStart(boolean b) {
        this.checkOnStart = b;
        return this;
    }

    public String userAgent() {
        return userAgent;
    }

    public UpdateConfig setUserAgent(String ua) {
        this.userAgent = ua;
        return this;
    }

    public boolean useHardLinks() {
        return useHardLinks;
    }

    public UpdateConfig setUseHardLinks(boolean b) {
        this.useHardLinks = b;
        return this;
    }

    @Override
    public String toString() {
        return displayName() + " (" + baseUrl + ") in " + appDir;
    }
}
