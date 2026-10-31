package org.hp.publish;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hp.update.UpdateManager;

/**
 * Uploads from the command line, for scripts:
 *
 * <pre>
 * java -cp publisher.jar org.hp.publish.UploadTool upload sftp://deploy@203.0.113.5/var/www/apps/gasx
 * java -cp publisher.jar org.hp.publish.UploadTool upload ftps://me@ftp.example.com/~/public_html/apps/gasx
 * </pre>
 *
 * The password comes from HP_UPLOAD_PASSWORD, or is asked for. A key file's passphrase
 * comes from HP_UPLOAD_PASSPHRASE, or is asked for.
 */
public final class UploadTool {

    private UploadTool() {
    }

    public static void main(String[] args) {
        FtpFs.prepareTls();
        if (args.length < 2) {
            usage();
            System.exit(1);
        }
        Path outDir = Paths.get(args[0]).toAbsolutePath().normalize();
        String action = "upload";
        String trustFingerprint = null;
        boolean yes = false;
        ServerProfile p;
        try {
            p = ServerProfile.parseUrl(args[1]);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--key=")) {
                p.keyFile = a.substring(6);
            } else if (a.equals("--no-url-check")) {
                p.checkUrl = false;
            } else if (a.equals("--test")) {
                action = "test";
            } else if (a.startsWith("--cleanup")) {
                action = "cleanup";
                if (a.startsWith("--cleanup=")) {
                    p.keepReleases = Integer.parseInt(a.substring(10));
                }
            } else if (a.startsWith("--trust=")) {
                trustFingerprint = a.substring(8);
            } else if (a.equals("--yes")) {
                yes = true;
            } else {
                System.err.println("unknown option " + a);
                System.exit(1);
            }
        }

        Uploader.Secrets secrets = new Uploader.Secrets();
        String pw = System.getenv("HP_UPLOAD_PASSWORD");
        if (pw != null) {
            secrets.password = pw.toCharArray();
        } else if (p.keyFile.isEmpty()) {
            secrets.password = ask("Password for " + p.user + "@" + p.host + ": ");
        }
        if (!p.keyFile.isEmpty()) {
            String pp = System.getenv("HP_UPLOAD_PASSPHRASE");
            secrets.passphrase = pp != null ? pp.toCharArray() : null;
        }

        Uploader u = new Uploader(p, secrets, new ConsoleListener(trustFingerprint, yes), null);
        try {
            if (action.equals("test")) {
                String appBase = null;
                String text = org.hp.update.Io.readString(outDir.resolve("app.txt"));
                if (text != null) {
                    appBase = org.hp.update.UpdateManifest.parse(text).appBase();
                }
                u.testConnection(appBase);
                System.out.println("OK");
            } else if (action.equals("cleanup")) {
                Uploader.CleanupPlan plan = u.planCleanup();
                System.out.println("Keeping versions " + plan.keptVersions
                        + (plan.droppedVersions.isEmpty() ? "" : ", dropping " + plan.droppedVersions));
                if (plan.isEmpty()) {
                    System.out.println("Nothing to remove.");
                    return;
                }
                System.out.println(plan.files.size() + " file(s)"
                        + (plan.bytes >= 0 ? " (" + UpdateManager.humanBytes(plan.bytes) + ")" : "")
                        + " no longer used:");
                for (String f : plan.files) {
                    System.out.println("  " + f);
                }
                if (!yes && !confirm("Remove them? [y/N] ")) {
                    System.out.println("Nothing removed.");
                    return;
                }
                u.cleanup(plan);
            } else {
                Uploader.Report r = u.upload(outDir);
                System.out.println();
                System.out.println("Published version " + r.version + ": " + r.uploadedText()
                        + ", " + r.alreadyThere + " already there, " + (r.millis / 1000) + "s.");
                for (String w : r.warnings) {
                    System.out.println("warning: " + w);
                }
            }
        } catch (IOException e) {
            System.err.println();
            System.err.println("FAILED: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void usage() {
        System.out.println("usage: UploadTool <upload-folder> <sftp|ftps|ftp>://user@host[:port]/folder [options]");
        System.out.println();
        System.out.println("  <upload-folder>   what Create setup produced (holds app.txt and files/)");
        System.out.println("  /folder           absolute path on the server, or /~/folder inside the login folder");
        System.out.println();
        System.out.println("  --key=file        SFTP private key (passphrase from HP_UPLOAD_PASSPHRASE)");
        System.out.println("  --test            only check the connection, the folder and the download URL");
        System.out.println("  --cleanup[=N]     remove files no longer used by the last N releases (default 3)");
        System.out.println("  --yes             answer yes to questions (removing files, replacing another app's release)");
        System.out.println("  --no-url-check    do not check the release at the download URL");
        System.out.println("  --trust=FP        accept the server whose key/certificate has this fingerprint");
        System.out.println();
        System.out.println("  Password: HP_UPLOAD_PASSWORD, or asked for.");
    }

    private static char[] ask(String prompt) {
        Console c = System.console();
        if (c != null) {
            return c.readPassword("%s", prompt);
        }
        System.out.print(prompt);
        try {
            String l = new BufferedReader(new InputStreamReader(System.in)).readLine();
            return l == null ? new char[0] : l.toCharArray();
        } catch (IOException e) {
            return new char[0];
        }
    }

    private static boolean confirm(String prompt) {
        System.out.print(prompt);
        try {
            String l = new BufferedReader(new InputStreamReader(System.in)).readLine();
            return l != null && l.trim().toLowerCase().startsWith("y");
        } catch (IOException e) {
            return false;
        }
    }

    private static final class ConsoleListener implements Uploader.Listener {
        private final String trusted;
        private final boolean yes;
        private int lastPercent = -1;

        ConsoleListener(String trusted, boolean yes) {
            this.trusted = trusted;
            this.yes = yes;
        }

        @Override
        public boolean confirm(String title, String question) {
            System.out.println();
            System.out.println(title);
            System.out.println(question);
            if (yes) {
                System.out.println("Yes (--yes).");
                return true;
            }
            if (System.console() == null) {
                System.out.println("Run again with --yes to go ahead.");
                return false;
            }
            return UploadTool.confirm("Go ahead? [y/N] ");
        }

        @Override
        public void line(String text) {
            System.out.println(text);
        }

        @Override
        public void progress(long done, long total, String file) {
            int pct = total <= 0 ? 100 : (int) (done * 100 / total);
            if (pct / 10 != lastPercent / 10) {
                lastPercent = pct;
                System.out.println("  " + pct + "%  " + UpdateManager.humanBytes(done) + " of "
                        + UpdateManager.humanBytes(total));
            }
        }

        private boolean decide(String fingerprint, String what) {
            if (trusted != null) {
                boolean ok = trusted.replace(":", "").equalsIgnoreCase(fingerprint.replace(":", ""));
                System.out.println(ok ? "Accepted by --trust." : "Does not match --trust=" + trusted);
                return ok;
            }
            if (System.console() == null) {
                System.out.println("Run again with --trust=" + fingerprint + " once you have checked it.");
                return false;
            }
            return UploadTool.confirm("Trust this " + what + " and remember it? [y/N] ");
        }

        @Override
        public boolean trustCertificate(String server, String details, String fingerprint,
                boolean changed) {
            System.out.println();
            System.out.println(changed
                    ? "WARNING: the certificate of " + server + " is NOT the one you trusted before."
                    : "The certificate of " + server + " is not trusted by Java:");
            System.out.println(details);
            return decide(fingerprint, "certificate");
        }

        @Override
        public boolean trustHostKey(String server, String type, String fingerprint, boolean changed) {
            System.out.println();
            System.out.println(changed
                    ? "WARNING: the host key of " + server + " has CHANGED. If you did not reinstall"
                    + " the server, someone may be intercepting the connection."
                    : "First connection to " + server + ".");
            System.out.println("  " + type + " " + fingerprint);
            return decide(fingerprint, "host key");
        }
    }
}
