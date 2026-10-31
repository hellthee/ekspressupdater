package org.hp.update;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the application from its own folder, the way getdown does: the working
 * directory is the application folder and the jars are at their real paths in it, so
 * every relative path in the application behaves exactly as it does when you run it
 * from {@code dist}.
 */
public final class Relauncher {

    private Relauncher() {
    }

    /**
     * The line an application with {@link SelfUpdate} built in prints when the launcher
     * started it and its main method is running. The launcher closes on seeing it, so
     * only the application is left in the taskbar.
     */
    public static final String STARTED_MARKER = "[updater] application is up, launcher can close";

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static String javaExecutable(boolean gui) {
        return javaExecutable(gui, null);
    }

    /**
     * The java to start the application with: a {@code java = ...} line in app.txt,
     * then -Dupdate.java, otherwise the JVM running the launcher (javaw on Windows).
     */
    public static String javaExecutable(boolean gui, UpdateConfig cfg) {
        if (cfg != null && cfg.javaExe() != null) {
            return cfg.javaExe();
        }
        String override = System.getProperty("update.java");
        if (override != null && !override.isEmpty()) {
            return override;
        }
        Path bin = new File(System.getProperty("java.home"), "bin").toPath();
        if (isWindows()) {
            Path javaw = bin.resolve("javaw.exe");
            if (gui && Files.isRegularFile(javaw)) {
                return javaw.toString();
            }
            return bin.resolve("java.exe").toString();
        }
        return bin.resolve("java").toString();
    }

    /** "java [jvmargs] -cp GasX.jar;lib\a.jar;... gasx.Main [args]" for the installed release. */
    public static List<String> buildCommand(UpdateConfig cfg, boolean gui, String[] extraArgs)
            throws IOException {

        InstallStore store = new InstallStore(cfg.appDir());
        UpdateManifest m = store.installed();
        if (m == null) {
            throw new IOException("Nothing is installed in " + cfg.appDir());
        }
        if (m.mainClass() == null) {
            throw new IOException("Version " + m.version() + " has no \"class\" line");
        }
        StringBuilder cp = new StringBuilder();
        for (UpdateManifest.FileEntry e : m.classpathEntries()) {
            if (cp.length() > 0) {
                cp.append(File.pathSeparatorChar);
            }
            // Relative to the application folder, which is where it runs: much shorter than
            // absolute paths, so a long library list stays under Windows' command limit.
            cp.append(store.appDir().relativize(store.file(e.path)).toString());
        }
        if (cp.length() == 0) {
            throw new IOException("Version " + m.version() + " lists no jar files");
        }
        // Plugins put in place by the updater (lib/set.jar): part of the class path too.
        for (String managed : PluginSet.classPathJars(cfg.appDir())) {
            if (m.entries().get(managed) == null) {
                cp.append(File.pathSeparatorChar).append(managed.replace('/', File.separatorChar));
            }
        }

        List<String> cmd = new ArrayList<String>();
        cmd.add(javaExecutable(gui, cfg));
        cmd.addAll(m.jvmArgs());
        cmd.add("-Dupdate.appdir=" + cfg.appDir());
        cmd.add("-Dupdate.url=" + cfg.baseUrl());
        cmd.add("-Dupdate.version=" + m.version());
        for (String prop : new String[] {"update.plugins.checked", "update.plugins.updated"}) {
            if (System.getProperty(prop) != null) {
                cmd.add("-D" + prop + "=" + System.getProperty(prop));
            }
        }
        if (gui) {
            // lets SelfUpdate tell us it is running, so the launcher does not linger
            cmd.add("-Dupdate.detach=true");
        }
        cmd.add("-cp");
        cmd.add(cp.toString());
        cmd.add(m.mainClass());
        cmd.addAll(m.appArgs());
        if (extraArgs != null) {
            for (String a : extraArgs) {
                cmd.add(a);
            }
        }
        return cmd;
    }

    /**
     * Starts a prepared command from the application folder, with its output piped back
     * so the launcher can show it and write it to the log.
     */
    public static Process launchPiped(UpdateConfig cfg, List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cfg.appDir().toFile());
        pb.redirectErrorStream(true);
        return pb.start();
    }

    public static Path appLog(UpdateConfig cfg) {
        return cfg.appDir().resolve("logs").resolve("app.log");
    }

    public static String logTail(UpdateConfig cfg, int lines) {
        String text = Io.readString(appLog(cfg));
        if (text == null || text.trim().isEmpty()) {
            return "(the application produced no output)";
        }
        String[] all = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
            sb.append(all[i]).append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------ startup checks

    /**
     * What the JVM prints when it cannot start the application at all - the failures an
     * update can cause. Anything the application reports once it is running is its own
     * business.
     */
    private static final String[] JVM_FAILURES = {
        "Error: Could not find or load main class",
        "Error: Could not create the Java Virtual Machine",
        "Error occurred during initialization of VM",
        "Error: A JNI error has occurred",
        "Error: LinkageError occurred while loading main class",
        "Error: Main method not found",
        "Error: Unable to initialize main class",
        "Unrecognized option:",
        "Invalid maximum heap size",
        "Invalid initial heap size"
    };

    private static final String[] MAIN_THREAD_DEATHS = {
        "NoClassDefFoundError", "ClassNotFoundException", "UnsupportedClassVersionError",
        "ExceptionInInitializerError", "LinkageError"
    };

    /**
     * A line that proves the application could not start: the Java runtime itself
     * refusing (bad option, missing main class, wrong Java version), or the main thread
     * dying because a class is missing. Exceptions the application prints and carries on
     * from - "optional driver not found: ClassNotFoundException" - do not count.
     */
    public static boolean isStartupFailure(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        for (String p : JVM_FAILURES) {
            if (t.startsWith(p)) {
                return true;
            }
        }
        if (t.startsWith("Exception in thread \"main\"")) {
            for (String p : MAIN_THREAD_DEATHS) {
                if (t.contains(p)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Everything checkable before starting. A message to show, or null when fine. */
    public static String startupProblem(UpdateConfig cfg) {
        String missing = mainClassMissing(cfg);
        return missing != null ? missing : javaVersionProblem(cfg);
    }

    /** The class named in app.txt must be inside one of the installed jars. */
    public static String mainClassMissing(UpdateConfig cfg) {
        try {
            InstallStore store = new InstallStore(cfg.appDir());
            UpdateManifest m = store.installed();
            if (m == null || m.mainClass() == null) {
                return null;
            }
            String entry = m.mainClass().replace('.', '/') + ".class";
            for (UpdateManifest.FileEntry e : m.classpathEntries()) {
                if (classFileMajor(store.file(e.path), entry) > 0) {
                    return null;
                }
            }
            return "The main class " + m.mainClass() + " is not in any of the "
                    + m.classpathEntries().size() + " jar files of version " + m.version() + "."
                    + "\n\nThe release on the server is missing a file, or the \"class\" line"
                    + " in app.txt names the wrong class.";
        } catch (Exception e) {
            return null;
        }
    }

    /** This Java must be new enough for the application's classes. */
    public static String javaVersionProblem(UpdateConfig cfg) {
        try {
            InstallStore store = new InstallStore(cfg.appDir());
            UpdateManifest m = store.installed();
            if (m == null || m.mainClass() == null) {
                return null;
            }
            String entry = m.mainClass().replace('.', '/') + ".class";
            int needed = 0;
            String neededIn = null;
            for (UpdateManifest.FileEntry e : m.classpathEntries()) {
                int major = classFileMajor(store.file(e.path), entry);
                if (major > 0) {
                    needed = major;
                    neededIn = e.path;
                    break;
                }
            }
            if (needed <= 0 || cfg.javaExe() != null) {
                return null; // an explicit java = line is the user's call
            }
            int running = runningClassVersion();
            if (running > 0 && needed > running) {
                return cfg.displayName() + " needs Java " + (needed - 44)
                        + " or newer, but this computer is running Java " + (running - 44)
                        + ".\n\n" + neededIn + " cannot start on this Java.\n\nInstall Java "
                        + (needed - 44) + " or newer, or add a line to app.txt pointing at one:\n"
                        + "    java = C:\\Program Files\\Java\\jdk-" + (needed - 44)
                        + "\\bin\\javaw.exe";
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int classFileMajor(Path jar, String entry) {
        java.util.zip.ZipFile zip = null;
        java.io.InputStream in = null;
        try {
            zip = new java.util.zip.ZipFile(jar.toFile());
            java.util.zip.ZipEntry e = zip.getEntry(entry);
            if (e == null) {
                return 0;
            }
            in = zip.getInputStream(e);
            byte[] head = new byte[8];
            int read = 0;
            while (read < 8) {
                int n = in.read(head, read, 8 - read);
                if (n < 0) {
                    return 0;
                }
                read += n;
            }
            return ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
        } catch (Exception e) {
            return 0;
        } finally {
            Io.closeQuietly(in);
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    static int runningClassVersion() {
        try {
            String v = System.getProperty("java.class.version", "");
            int dot = v.indexOf('.');
            return Integer.parseInt(dot > 0 ? v.substring(0, dot) : v);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Restarts through the launcher, which puts a downloaded update in place before
     * starting the application again, then ends this JVM. Call it after an in-app
     * update returns {@link UpdateManager.Status#STAGED}.
     */
    public static void restartAndExit(UpdateConfig cfg, String[] args) {
        try {
            String launcher = System.getProperty("update.launcher");
            if (launcher == null) {
                Path candidate = cfg.appDir().resolve("launcher.jar");
                launcher = Files.isRegularFile(candidate) ? candidate.toString() : null;
            }
            if (launcher == null) {
                throw new IOException("launcher.jar not found in " + cfg.appDir());
            }
            List<String> cmd = new ArrayList<String>();
            cmd.add(javaExecutable(true));
            cmd.add("-jar");
            cmd.add(launcher);
            cmd.add(cfg.appDir().toString());
            if (args != null) {
                for (String a : args) {
                    cmd.add(a);
                }
            }
            java.io.File log = cfg.appDir().resolve("logs").resolve("handover.log").toFile();
            log.getParentFile().mkdirs();
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cfg.appDir().toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(log)); // never a pipe to a JVM that is exiting
            pb.start();
        } catch (Exception e) {
            System.err.println("Restart failed: " + e);
        }
        System.exit(0);
    }
}
