package org.hp.publish;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import org.hp.update.Io;
import org.hp.update.UpdateManager;
import org.hp.update.UpdateManifest;

/**
 * Sends a release to the server: SFTP for a VPS, FTPS for shared hosting (or plain FTP
 * if nothing else is offered). The server settings are remembered per application;
 * passwords only for as long as the publisher stays open.
 */
final class UploadDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    /** Passwords typed in this session, per server; never written to disk. */
    private static final java.util.Map<String, char[]> SESSION_PASSWORDS =
            new java.util.HashMap<String, char[]>();

    private final Path outDir;
    private final String appBase;
    private final String version;
    private final Path prefsFile;
    private final String prefix;

    private final JComboBox<ServerProfile.Protocol> protocol =
            new JComboBox<ServerProfile.Protocol>(ServerProfile.Protocol.values());
    private final JTextField host = new JTextField(22);
    private final JTextField port = new JTextField(5);
    private final JTextField user = new JTextField(14);
    private final JPasswordField password = new JPasswordField(14);
    private final JTextField keyFile = new JTextField();
    private final JButton keyBrowse = new JButton("Browse...");
    private final JPasswordField passphrase = new JPasswordField(14);
    private final JLabel keyLabel = new JLabel("Key file");
    private final JLabel passphraseLabel = new JLabel("Key passphrase");
    private final JTextField remoteDir = new JTextField();
    private final JCheckBox checkUrl = new JCheckBox(
            "Check every file at the download URL first (recommended)", true);
    private final JSpinner keep = new JSpinner(new SpinnerNumberModel(3, 1, 50, 1));
    private final JLabel warning = new JLabel(" ");

    private final JProgressBar progress = new JProgressBar();
    private final JTextArea log = new JTextArea();
    private final JButton testButton = new JButton("Test connection");
    private final JButton cleanButton = new JButton("Clean up server...");
    private final JButton closeButton = new JButton("Close");
    private final JButton uploadButton = new JButton();

    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile boolean busy;
    private volatile Uploader running;

    UploadDialog(Window owner, Path outDir, String appBase, String version, Path prefsFile) {
        super(owner, "Upload to server", ModalityType.APPLICATION_MODAL);
        this.outDir = outDir;
        this.appBase = appBase == null ? "" : appBase.trim();
        this.version = version;
        this.prefsFile = prefsFile;
        this.prefix = "upload." + this.appBase.replaceAll("[^A-Za-z0-9]", "_") + ".";
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeOrCancel();
            }
        });

        setLayout(new BorderLayout());
        add(form(), BorderLayout.NORTH);
        add(center(), BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        load();
        protocolChanged();
        setPreferredSize(new Dimension(780, 640));
        pack();
        setLocationRelativeTo(owner);
    }

    // ------------------------------------------------------------------ layout

    private JComponent form() {
        JPanel f = new JPanel(new GridBagLayout());
        f.setBorder(BorderFactory.createEmptyBorder(12, 14, 4, 14));
        int row = 0;

        protocol.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                protocolChanged();
            }
        });
        row(f, row++, "Connect with", protocol, null);

        JPanel hp = new JPanel();
        hp.setLayout(new BoxLayout(hp, BoxLayout.X_AXIS));
        hp.add(host);
        hp.add(Box.createHorizontalStrut(10));
        hp.add(new JLabel("Port "));
        hp.add(port);
        port.setMaximumSize(port.getPreferredSize());
        row(f, row++, "Server", hp, null);

        JPanel up = new JPanel();
        up.setLayout(new BoxLayout(up, BoxLayout.X_AXIS));
        up.add(user);
        up.add(Box.createHorizontalStrut(10));
        up.add(new JLabel("Password "));
        up.add(password);
        row(f, row++, "User", up, null);
        password.setToolTipText("Kept only while the publisher is open; never saved to disk");

        keyBrowse.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home"), ".ssh"));
                fc.setFileHidingEnabled(false);
                fc.setDialogTitle("Your SSH private key (not the .pub file)");
                if (fc.showOpenDialog(UploadDialog.this) == JFileChooser.APPROVE_OPTION) {
                    keyFile.setText(fc.getSelectedFile().getAbsolutePath());
                }
            }
        });
        rowLabelled(f, row++, keyLabel, keyFile, keyBrowse);
        JPanel pp = new JPanel(new BorderLayout());
        pp.add(passphrase, BorderLayout.WEST);
        JLabel ppHint = new JLabel("  only if the key has one");
        ppHint.setForeground(Color.GRAY);
        pp.add(ppHint, BorderLayout.CENTER);
        rowLabelled(f, row++, passphraseLabel, pp, null);

        row(f, row++, "Folder on server", remoteDir, null);
        remoteDir.setToolTipText("The folder the download URL serves. VPS: e.g. /var/www/html/apps/gasx."
                + "  Shared hosting: e.g. public_html/apps/gasx (inside your login folder).");
        JLabel url = new JLabel(appBase.isEmpty() ? "(no download URL set)" : appBase);
        url.setFont(url.getFont().deriveFont(Font.BOLD));
        row(f, row++, "Download URL", url, null);
        row(f, row++, "", checkUrl, null);

        JPanel kp = new JPanel();
        kp.setLayout(new BoxLayout(kp, BoxLayout.X_AXIS));
        kp.add(new JLabel("Clean up keeps the newest "));
        keep.setMaximumSize(keep.getPreferredSize());
        kp.add(keep);
        kp.add(new JLabel(" releases (including the current one)"));
        kp.add(Box.createHorizontalGlue());
        row(f, row++, "", kp, null);

        warning.setForeground(new Color(0xB0, 0x30, 0x00));
        row(f, row++, "", warning, null);
        return f;
    }

    private void row(JPanel f, int r, String label, JComponent field, JComponent trailing) {
        rowLabelled(f, r, new JLabel(label), field, trailing);
    }

    private void rowLabelled(JPanel f, int r, JLabel label, JComponent field, JComponent trailing) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = r;
        lc.anchor = GridBagConstraints.EAST;
        lc.insets = new Insets(3, 0, 3, 8);
        f.add(label, lc);
        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1;
        fc.gridy = r;
        fc.weightx = 1;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.insets = new Insets(3, 0, 3, 6);
        f.add(field, fc);
        if (trailing != null) {
            GridBagConstraints tc = new GridBagConstraints();
            tc.gridx = 2;
            tc.gridy = r;
            f.add(trailing, tc);
        }
    }

    private JComponent center() {
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, log.getFont().getSize()));
        progress.setStringPainted(true);
        progress.setString(" ");
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(4, 14, 0, 14));
        p.add(progress, BorderLayout.NORTH);
        p.add(new JScrollPane(log), BorderLayout.CENTER);
        return p;
    }

    private JComponent buttons() {
        JPanel b = new JPanel();
        b.setLayout(new BoxLayout(b, BoxLayout.X_AXIS));
        b.setBorder(BorderFactory.createEmptyBorder(10, 14, 12, 14));
        b.add(testButton);
        b.add(Box.createHorizontalStrut(8));
        b.add(cleanButton);
        b.add(Box.createHorizontalGlue());
        b.add(closeButton);
        b.add(Box.createHorizontalStrut(8));
        b.add(uploadButton);
        uploadButton.setText(version == null ? "Upload" : "Upload version " + version);
        getRootPane().setDefaultButton(uploadButton);

        testButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                test();
            }
        });
        cleanButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                clean();
            }
        });
        uploadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                upload();
            }
        });
        closeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                closeOrCancel();
            }
        });
        return b;
    }

    private void protocolChanged() {
        ServerProfile.Protocol p = (ServerProfile.Protocol) protocol.getSelectedItem();
        enableKeyFields(!busy && p == ServerProfile.Protocol.SFTP);
        port.setText(port.getText().trim().isEmpty() || isDefaultPort(port.getText().trim())
                ? String.valueOf(p.defaultPort) : port.getText().trim());
        warning.setText(p == ServerProfile.Protocol.FTP
                ? "Plain FTP sends your password and files unencrypted. Use SFTP or FTPS if the"
                + " server offers either."
                : " ");
    }

    private void enableKeyFields(boolean on) {
        keyLabel.setEnabled(on);
        keyFile.setEnabled(on);
        keyBrowse.setEnabled(on);
        passphraseLabel.setEnabled(on);
        passphrase.setEnabled(on);
    }

    private static boolean isDefaultPort(String s) {
        for (ServerProfile.Protocol p : ServerProfile.Protocol.values()) {
            if (String.valueOf(p.defaultPort).equals(s)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ settings

    private ServerProfile profile() {
        ServerProfile p = new ServerProfile();
        p.protocol = (ServerProfile.Protocol) protocol.getSelectedItem();
        p.host = host.getText().trim();
        try {
            p.port = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            p.port = 0;
        }
        p.user = user.getText().trim();
        p.keyFile = p.protocol == ServerProfile.Protocol.SFTP ? keyFile.getText().trim() : "";
        p.remoteDir = remoteDir.getText().trim();
        p.checkUrl = checkUrl.isSelected();
        p.keepReleases = ((Number) keep.getValue()).intValue();
        return p;
    }

    private void load() {
        Properties props = Io.loadProps(prefsFile);
        ServerProfile p = ServerProfile.load(props, prefix);
        protocol.setSelectedItem(p.protocol);
        host.setText(p.host);
        port.setText(p.port > 0 ? String.valueOf(p.port) : "");
        user.setText(p.user);
        keyFile.setText(p.keyFile);
        remoteDir.setText(p.remoteDir);
        checkUrl.setSelected(p.checkUrl);
        keep.setValue(Integer.valueOf(p.keepReleases));
        char[] remembered = SESSION_PASSWORDS.get(p.toUrl());
        if (remembered != null) {
            password.setText(new String(remembered));
        }
    }

    private void save(ServerProfile p) {
        Properties props = Io.loadProps(prefsFile);
        p.save(props, prefix);
        try {
            Io.storeProps(prefsFile, props);
        } catch (IOException ignored) {
            // remembering the server is a convenience
        }
        SESSION_PASSWORDS.put(p.toUrl(), password.getPassword());
    }

    private Uploader.Secrets secrets() {
        Uploader.Secrets s = new Uploader.Secrets();
        s.password = password.getPassword();
        s.passphrase = passphrase.getPassword();
        return s;
    }

    private boolean ready(ServerProfile p) {
        String missing = p.host.isEmpty() ? "the server" : p.user.isEmpty() ? "the user name"
                : p.remoteDir.isEmpty() ? "the folder on the server" : null;
        if (missing != null) {
            JOptionPane.showMessageDialog(this, "Please fill in " + missing + ".", "Not ready yet",
                    JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (password.getPassword().length == 0 && p.keyFile.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter the password"
                    + (p.protocol == ServerProfile.Protocol.SFTP ? " (or choose a key file)." : "."),
                    "Not ready yet", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (p.protocol == ServerProfile.Protocol.FTP) {
            int ok = JOptionPane.showConfirmDialog(this, "Plain FTP sends your password and the"
                    + " files unencrypted over the internet.\nContinue with plain FTP?",
                    "Not encrypted", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ actions

    private void test() {
        final ServerProfile p = profile();
        if (!ready(p)) {
            return;
        }
        save(p);
        run("Testing " + p.toUrl(), new Job() {
            @Override
            public String call(Uploader u) throws Exception {
                u.testConnection(appBase);
                return "Connection OK.";
            }
        }, p);
    }

    private void upload() {
        final ServerProfile p = profile();
        if (!ready(p)) {
            return;
        }
        save(p);
        run("Uploading version " + version + " to " + p.toUrl(), new Job() {
            @Override
            public String call(Uploader u) throws Exception {
                Uploader.Report r = u.upload(outDir);
                StringBuilder sb = new StringBuilder();
                sb.append("Version ").append(r.version).append(" is live.\n\n")
                        .append(r.uploadedText()).append(";\n")
                        .append(r.alreadyThere).append(" already on the server.");
                if (r.urlChecked) {
                    sb.append("\nEvery file was checked at ").append(appBase);
                }
                for (String w : r.warnings) {
                    sb.append("\n\nNote: ").append(w);
                }
                return sb.toString();
            }
        }, p);
    }

    private void clean() {
        final ServerProfile p = profile();
        if (!ready(p)) {
            return;
        }
        save(p);
        final AtomicReference<Uploader.CleanupPlan> planRef = new AtomicReference<Uploader.CleanupPlan>();
        run("Looking for files no longer needed on " + p.toUrl(), new Job() {
            @Override
            public String call(Uploader u) throws Exception {
                Uploader.CleanupPlan plan = u.planCleanup();
                planRef.set(plan);
                return null;
            }
        }, p, new Runnable() {
            @Override
            public void run() {
                confirmCleanup(p, planRef.get());
            }
        });
    }

    private void confirmCleanup(final ServerProfile p, final Uploader.CleanupPlan plan) {
        if (plan == null) {
            return;
        }
        if (plan.isEmpty()) {
            append("Nothing to remove: every file on the server belongs to versions "
                    + plan.keptVersions + ".");
            JOptionPane.showMessageDialog(this, "Nothing to remove.", "Clean up",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Keeping version(s): ").append(plan.keptVersions).append('\n');
        if (!plan.droppedVersions.isEmpty()) {
            sb.append("No longer kept:    ").append(plan.droppedVersions).append('\n');
        }
        sb.append('\n').append(plan.files.size()).append(" file(s)");
        if (plan.bytes >= 0) {
            sb.append(" (").append(UpdateManager.humanBytes(plan.bytes)).append(")");
        }
        sb.append(" are used by none of the kept versions and will be removed:\n\n");
        int shown = 0;
        for (String f : plan.files) {
            if (shown++ == 30) {
                sb.append("   ... and ").append(plan.files.size() - 30).append(" more\n");
                break;
            }
            sb.append("   ").append(f).append('\n');
        }
        JTextArea text = new JTextArea(sb.toString());
        text.setEditable(false);
        text.setRows(Math.min(20, sb.toString().split("\n").length + 1));
        text.setColumns(64);
        int ok = JOptionPane.showConfirmDialog(this, new JScrollPane(text), "Clean up the server",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            append("Clean-up cancelled; nothing removed.");
            return;
        }
        run("Removing " + plan.files.size() + " file(s)", new Job() {
            @Override
            public String call(Uploader u) throws Exception {
                u.cleanup(plan);
                return "Clean-up finished.";
            }
        }, p);
    }

    private void closeOrCancel() {
        if (busy) {
            append("Cancelling ...");
            Uploader u = running;
            if (u != null) {
                u.abort(); // closes the connection, so even a stuck transfer stops now
            } else {
                cancel.set(true);
            }
            return;
        }
        dispose();
    }

    // ------------------------------------------------------------------ running

    private interface Job {
        /** Returns a message for the user, or null. */
        String call(Uploader u) throws Exception;
    }

    private void run(String title, Job job, ServerProfile p) {
        run(title, job, p, null);
    }

    private void run(final String title, final Job job, final ServerProfile p, final Runnable after) {
        busy = true;
        cancel.set(false);
        setControlsEnabled(false);
        closeButton.setText("Cancel");
        progress.setIndeterminate(true);
        progress.setString(" ");
        append("");
        append("== " + title);
        final Uploader u = new Uploader(p, secrets(), new SwingListener(), cancel);
        running = u;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return job.call(u);
            }

            @Override
            protected void done() {
                busy = false;
                running = null;
                setControlsEnabled(true);
                closeButton.setText("Close");
                progress.setIndeterminate(false);
                try {
                    String message = get();
                    progress.setValue(progress.getMaximum());
                    progress.setString("Done");
                    if (message != null) {
                        append(message);
                        JOptionPane.showMessageDialog(UploadDialog.this, message, "Done",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                    if (after != null) {
                        after.run();
                    }
                } catch (Exception e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    progress.setValue(0);
                    if (c instanceof RemoteFs.Cancelled || cancel.get()) {
                        progress.setString("Cancelled");
                        append("Cancelled. Anything already uploaded is kept and will not be sent again.");
                        return;
                    }
                    progress.setString("Failed");
                    String m = c.getMessage() == null ? c.toString() : c.getMessage();
                    append("FAILED: " + m);
                    JTextArea text = new JTextArea(m);
                    text.setEditable(false);
                    text.setLineWrap(true);
                    text.setWrapStyleWord(true);
                    text.setColumns(60);
                    text.setRows(Math.min(14, 2 + m.length() / 60 + m.split("\n").length));
                    JOptionPane.showMessageDialog(UploadDialog.this, new JScrollPane(text),
                            "Problem", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setControlsEnabled(boolean on) {
        for (JComponent c : new JComponent[] {protocol, host, port, user, password, remoteDir,
                checkUrl, keep, testButton, cleanButton, uploadButton}) {
            c.setEnabled(on);
        }
        enableKeyFields(on && protocol.getSelectedItem() == ServerProfile.Protocol.SFTP);
    }

    private void append(final String line) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                log.append(line + "\n");
                log.setCaretPosition(log.getDocument().getLength());
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** Runs a question on the Swing thread and waits for the answer. */
    private boolean askOnEdt(final Callable<Boolean> question) {
        final AtomicBoolean answer = new AtomicBoolean();
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        answer.set(question.call().booleanValue());
                    } catch (Exception e) {
                        answer.set(false);
                    }
                }
            });
        } catch (Exception e) {
            return false;
        }
        return answer.get();
    }

    private boolean trustQuestion(String title, String text, boolean changed) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setColumns(76);
        area.setRows(Math.min(20, text.length() / 70 + text.split("\n").length + 1));
        Object[] options = {changed ? "Trust the new one" : "Trust and remember", "Cancel"};
        int r = JOptionPane.showOptionDialog(this, new JScrollPane(area), title,
                JOptionPane.DEFAULT_OPTION,
                changed ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE, null,
                options, options[1]);
        return r == 0;
    }

    /** Feeds the uploader's progress into this window, and asks the trust questions. */
    private final class SwingListener implements Uploader.Listener {
        @Override
        public void line(String text) {
            append(text);
        }

        @Override
        public void progress(final long done, final long total, final String file) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    progress.setIndeterminate(false);
                    progress.setMaximum(1000);
                    progress.setValue(total <= 0 ? 1000 : (int) Math.min(1000, done * 1000 / total));
                    progress.setString(file + "   " + UpdateManager.humanBytes(done) + " of "
                            + UpdateManager.humanBytes(total));
                }
            });
        }

        @Override
        public boolean confirm(final String title, final String question) {
            return askOnEdt(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    Object[] options = {"Go ahead", "Cancel"};
                    int r = JOptionPane.showOptionDialog(UploadDialog.this, question, title,
                            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                            options, options[1]);
                    return Boolean.valueOf(r == 0);
                }
            });
        }

        @Override
        public boolean trustCertificate(final String server, final String details,
                String fingerprint, final boolean changed) {
            return askOnEdt(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    String intro = changed
                            ? "WARNING: " + server + " presents a DIFFERENT certificate from the one"
                            + " you trusted before. If your host did not just replace it, someone"
                            + " may be intercepting the connection. Do not continue unless you are"
                            + " sure.\n\n"
                            : "Java does not trust the certificate of " + server + " by itself."
                            + " That is normal on shared hosting (the certificate is in the hosting"
                            + " company's name) and on servers with a self-signed certificate."
                            + " Check the fingerprint with your host - FileZilla shows the same"
                            + " one - and then trust it.\n\n";
                    return Boolean.valueOf(trustQuestion(changed ? "Certificate changed"
                            : "Unknown certificate", intro + details, changed));
                }
            });
        }

        @Override
        public boolean trustHostKey(final String server, final String type,
                final String fingerprint, final boolean changed) {
            return askOnEdt(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    String text = changed
                            ? "WARNING: the host key of " + server + " has CHANGED.\n\nIf you did not"
                            + " reinstall or rebuild the server, someone may be intercepting the"
                            + " connection. Do not continue unless you are sure.\n\n"
                            : "First connection to " + server + ".\n\nTo be sure it is your server,"
                            + " compare the fingerprint below with the one the server shows for"
                            + " this command (or with your VPS provider's console):\n\n"
                            + "  ssh-keygen -lf /etc/ssh/ssh_host_" + keyFileName(type) + "_key.pub\n\n";
                    text += "Key type    : " + type + "\nFingerprint : " + fingerprint;
                    return Boolean.valueOf(trustQuestion(changed ? "Host key changed"
                            : "New server", text, changed));
                }
            });
        }
    }

    /** ssh-ed25519 -> ed25519, ecdsa-sha2-nistp256 -> ecdsa, ssh-rsa -> rsa */
    private static String keyFileName(String type) {
        String t = type.toLowerCase();
        return t.contains("ed25519") ? "ed25519" : t.contains("ecdsa") ? "ecdsa" : "rsa";
    }

    /** Shows the dialog for the release in {@code outDir}. */
    static void open(Window owner, Path outDir, Path prefsFile) {
        String appBase = null;
        String version = null;
        String text = Io.readString(outDir.resolve("app.txt"));
        if (text != null) {
            try {
                UpdateManifest m = UpdateManifest.parse(text);
                appBase = m.appBase();
                version = m.version();
            } catch (IOException ignored) {
                // the upload itself reports a broken app.txt properly
            }
        }
        if (version == null) {
            JOptionPane.showMessageDialog(owner, "There is no release in " + outDir
                    + " yet.\nClick Create setup first.", "Nothing to upload",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new UploadDialog(owner, outDir, appBase, version, prefsFile).setVisible(true);
    }
}
