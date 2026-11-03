package org.hp.update;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Command line front end for {@link ReleaseBuilder}, for scripts and Ant. The window
 * version is {@link Publisher}; both produce byte-identical output.
 *
 * <pre>
 * java -cp launcher.jar org.hp.update.ManifestTool app.txt dist 1.4.2 upload
 * </pre>
 *
 * <p>You do not list jars anywhere. Every file in the build folder is included: {@code .jar}
 * files as classpath entries, everything else as plain files. Build junk (.DS_Store,
 * README.TXT, getdown.txt, launcher.jar / launcher.exe, client folders holding a set.txt)
 * is skipped, and so is anything listed as
 * {@code # exclude = ...} in your {@code app.txt}.</p>
 */
public final class ManifestTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("usage: ManifestTool <app.txt> <buildDir> <version> <outDir>"
                    + " [--notes=whatsnew.txt] [--exec=path ...] [--include=path ...]"
                    + " [--old-layout=no]");
            System.out.println();
            System.out.println("  app.txt   your config: appbase, ui.name, class, jvmarg");
            System.out.println("            (a plugin such as set.jar: appbase, ui.name, plugin = set)");
            System.out.println("  buildDir  your dist folder");
            System.out.println("  version   e.g. 1.0.2");
            System.out.println("  outDir    what you upload to the server");
            System.exit(1);
        }
        Path config = Paths.get(args[0]).toAbsolutePath().normalize();
        Path buildDir = Paths.get(args[1]).toAbsolutePath().normalize();
        String version = args[2];
        Path outDir = Paths.get(args[3]).toAbsolutePath().normalize();

        UpdateManifest cfg = ReleaseBuilder.readConfig(config);
        if (cfg.appBase() == null) {
            throw new IOException(config + " has no \"appbase\" line");
        }
        if (cfg.mainClass() == null && cfg.plugin() == null) {
            throw new IOException(config + " has no \"class\" line (or \"plugin\" line, for a plugin)");
        }

        ReleaseBuilder.Options o = new ReleaseBuilder.Options();
        o.buildDir = buildDir;
        o.outDir = outDir;
        o.version = version;
        o.appBase = cfg.appBase();
        o.uiName = cfg.uiName();
        o.mainClass = cfg.mainClass();
        o.plugin = cfg.plugin();
        o.pluginTarget = cfg.pluginTarget();
        o.jvmArgs.addAll(cfg.jvmArgs());
        o.appArgs.addAll(cfg.appArgs());
        o.exclude.addAll(ReleaseBuilder.readExcludes(config));

        for (int i = 4; i < args.length; i++) {
            if (args[i].startsWith("--exec=")) {
                o.executable.add(args[i].substring("--exec=".length()).replace('\\', '/'));
            } else if (args[i].startsWith("--notes=")) {
                String text = Io.readString(Paths.get(args[i].substring("--notes=".length())));
                if (text != null) {
                    for (String l : text.split("\n")) {
                        o.notes.add(l.replace("\r", ""));
                    }
                }
            } else if (args[i].startsWith("--old-layout=")) {
                o.oldLayout = Boolean.valueOf(args[i].endsWith("=yes") || args[i].endsWith("=true"));
            } else if (args[i].startsWith("--include=")) {
                o.exclude.remove(args[i].substring("--include=".length()).replace('\\', '/'));
            } else {
                System.out.println("warning: ignoring unknown option " + args[i]);
            }
        }

        // Skip build junk unless the caller asked for it back with --include.
        for (ReleaseBuilder.Candidate c : ReleaseBuilder.scan(buildDir)) {
            if (c.skipByDefault && !o.exclude.contains(c.path)) {
                boolean forced = false;
                for (int i = 4; i < args.length; i++) {
                    forced = forced || args[i].equals("--include=" + c.path);
                }
                if (!forced) {
                    o.exclude.add(c.path);
                }
            }
        }

        ReleaseBuilder.Impact impact = ReleaseBuilder.preview(o);
        if (impact.worthMentioning()) {
            System.out.println();
            System.out.println(impact.describe());
            System.out.println();
        }

        ReleaseBuilder.Result r = ReleaseBuilder.build(o, new ReleaseBuilder.Progress() {
            @Override
            public void file(String path, long size, int index, int total) {
            }

            @Override
            public void log(String message) {
                System.out.println("  " + message);
            }
        });

        System.out.println();
        System.out.println((cfg.plugin() != null ? "plugin      : " + cfg.plugin() + " - " : "application : ")
                + (cfg.uiName() == null ? "(no ui.name)" : cfg.uiName()));
        System.out.println("appbase     : " + ReleaseBuilder.normalizeUrl(cfg.appBase()));
        System.out.println("version     : " + version);
        System.out.println("files       : " + r.fileCount + " ("
                + UpdateManager.humanBytes(r.totalBytes) + ")");
        System.out.println("new files   : " + r.newFiles + " ("
                + UpdateManager.humanBytes(r.newBytes) + ") to upload");
        System.out.println("written     : " + r.manifest);
        System.out.println("layout      : " + (Io.readString(r.manifest).contains("hashed")
                ? "readable names + old layout copies (for PCs with an older updater)"
                : "readable names"));
        System.out.println();
        System.out.println("Upload the contents of " + outDir + " to "
                + ReleaseBuilder.normalizeUrl(cfg.appBase()));
        System.out.println("Upload files/ first and app.txt last: clients act on app.txt at once.");
    }
}
