package org.hp.publish;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import org.hp.update.Io;
import org.hp.update.ReleaseBuilder;
import org.hp.update.UpdateConfig;
import org.hp.update.UpdateManager;
import org.hp.update.UpdateManifest;

/**
 * Window for publishing: the <b>Application</b> tab builds the application's release, the
 * <b>Set</b> tab builds a client's set (lib/set.jar plus any supporting files) from the same
 * build folder. A file belongs to one of them at most. <b>Create setup</b> and
 * <b>Upload</b> act on the tab that is showing.
 *
 * <pre>
 * java -jar publisher.jar
 * </pre>
 *
 * <p>The same steps are available on the command line: {@code org.hp.update.ManifestTool}
 * builds a release, {@link UploadTool} uploads it.</p>
 */
public final class Publisher extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final String PREFS = ".hp-publisher.properties";
    /** The plugin name sets are published under: setapp.txt, PluginUpdate.ensure("set", ...). */
    static final String SET_NAME = "set";

    static final int NONE = 0;
    static final int APP = 1;
    static final int SET = 2;

    // shared
    private final JTextField buildField = new JTextField();
    private final Files2 files = new Files2();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea log = new JTextArea();
    private final JProgressBar progress = new JProgressBar();
    private final JLabel summary = new JLabel(" ");
    private final JButton createButton = new JButton("Create setup");
    private final JButton openOutButton = new JButton("Open output folder");
    private final JButton uploadButton = new JButton("Upload...");
    private final JButton optionsButton = new JButton("Options...");
    private final JCheckBox clientFilesBox = new JCheckBox("Also create a client folder", true);
    private final JCheckBox oldLayoutBox = new JCheckBox("Copies for older updaters", false);

    // application tab
    private final JTextField appBaseField = new JTextField();
    private final JTextField uiNameField = new JTextField();
    private final JTextField mainClassField = new JTextField();
    private final JTextField jvmArgsField = new JTextField();
    private final JTextField versionField = new JTextField(10);
    private final JLabel appLastLabel = new JLabel(" ");
    private final JTextArea notesArea = new JTextArea(2, 40);
    private final FileTableModel appModel = new FileTableModel(files, APP);

    // set tab
    private final JLabel setBuildLabel = new JLabel(" ");
    private final JTextField setUrlField = new JTextField();
    private final JTextField setNameField = new JTextField();
    private final JTextField setVersionField = new JTextField(10);
    private final JLabel setLastLabel = new JLabel(" ");
    private final JTextArea setNotesArea = new JTextArea(2, 40);
    private final FileTableModel setModel = new FileTableModel(files, SET);

    /** The build folder the fields belong to. */
    private Path lastBuildDir;
    /** Application output folder (from the project's app.txt, or project\\upload). */
    private Path appOutDir;
    /** The client the set's version was last suggested for. */
    private String setClientShown = "";

    public Publisher() {
        super("Release publisher");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        tabs.addTab("Application", appTab());
        tabs.addTab("Set", setTab());
        tabs.addChangeListener(new javax.swing.event.ChangeListener() {
            @Override
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                refreshFooter();
            }
        });
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, logPanel());
        split.setResizeWeight(0.8);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);

        loadPrefs();
        setPreferredSize(new Dimension(900, 760));
        pack();
        setLocationRelativeTo(null);
    }

    // -------------------------------------------------------------------- layout

    private JComponent appTab() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        int row = 0;
        addRow(form, row++, "Build folder", buildField, browseButton());
        addRow(form, row++, "Download URL", appBaseField, null);
        addRow(form, row++, "App name", uiNameField, null);
        addRow(form, row++, "Main class", mainClassField, null);
        addRow(form, row++, "JVM options", jvmArgsField, null);
        addRow(form, row++, "Version", versionRow(versionField, appLastLabel), null);
        addRow(form, row++, "What's new", notesScroll(notesArea), null);
        return tabPanel(form, "Files of the application", appModel);
    }

    private JComponent setTab() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        int row = 0;
        setBuildLabel.setForeground(new Color(0x44, 0x44, 0x44));
        addRow(form, row++, "Build folder", setBuildLabel, null);
        setUrlField.setToolTipText("<html>The client's own set folder on the server, e.g."
                + "<br>http://172.104.47.59:8080/apps/gasx/sets/pt-abc/"
                + "<br>Put the same address into that client's database (set_url).</html>");
        addRow(form, row++, "Download URL", setUrlField, null);
        addRow(form, row++, "Set name", setNameField, null);
        addRow(form, row++, "Version", versionRow(setVersionField, setLastLabel), null);
        addRow(form, row++, "What's new", notesScroll(setNotesArea), null);
        setUrlField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                setUrlChanged(false);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                setUrlChanged(false);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                setUrlChanged(false);
            }
        });
        setUrlField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                setUrlChanged(true);
            }
        });
        return tabPanel(form, "Files of the set", setModel);
    }

    private JComponent versionRow(JTextField field, JLabel last) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        field.setMaximumSize(new Dimension(120, field.getPreferredSize().height));
        p.add(field);
        p.add(Box.createHorizontalStrut(12));
        last.setForeground(new Color(0x55, 0x55, 0x55));
        p.add(last);
        p.add(Box.createHorizontalGlue());
        return p;
    }

    private static JScrollPane notesScroll(JTextArea area) {
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane s = new JScrollPane(area);
        s.setPreferredSize(new Dimension(200, 44));
        return s;
    }

    /** Form on top, file list with Check all / Uncheck all below. */
    private JComponent tabPanel(JComponent form, String title, final FileTableModel model) {
        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        TableColumn include = table.getColumnModel().getColumn(0);
        include.setMaxWidth(60);
        include.setMinWidth(60);
        include.setCellRenderer(new CheckRenderer(table.getDefaultRenderer(Boolean.class)));
        table.getColumnModel().getColumn(2).setMaxWidth(90);
        table.getColumnModel().getColumn(3).setMaxWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(220);
        table.getColumnModel().getColumn(4).setCellRenderer(new NoteRenderer());

        JButton all = new JButton("Check all");
        all.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                files.checkAll(model.side);
            }
        });
        JButton none = new JButton("Uncheck all");
        none.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                files.uncheckAll(model.side);
            }
        });
        JPanel head = new JPanel(new BorderLayout());
        head.add(new JLabel(title), BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(all);
        buttons.add(none);
        head.add(buttons, BorderLayout.EAST);

        JPanel list = new JPanel(new BorderLayout(0, 4));
        list.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        list.add(head, BorderLayout.NORTH);
        list.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel p = new JPanel(new BorderLayout());
        p.add(form, BorderLayout.NORTH);
        p.add(list, BorderLayout.CENTER);
        return p;
    }

    private JComponent logPanel() {
        log.setEditable(false);
        log.setRows(6);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(4, 12, 0, 12));
        p.add(new JScrollPane(log), BorderLayout.CENTER);
        return p;
    }

    private JComponent footer() {
        progress.setStringPainted(true);
        progress.setString(" ");
        openOutButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openFolder(outDir(currentSide()));
            }
        });
        optionsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showOptions();
            }
        });
        uploadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Path out = outDir(currentSide());
                if (out != null) {
                    UploadDialog.open(Publisher.this, out, prefsFile());
                }
            }
        });
        createButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                createSetup(currentSide());
            }
        });
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        buttons.add(summary);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(openOutButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(optionsButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(createButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(uploadButton);

        JPanel south = new JPanel(new BorderLayout());
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 12, 0, 12));
        p.add(progress, BorderLayout.CENTER);
        south.add(p, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        files.addListener(new Runnable() {
            @Override
            public void run() {
                refreshFooter();
            }
        });
        return south;
    }

    private void addRow(JPanel form, int row, String label, JComponent field, JComponent trailing) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = row;
        lc.anchor = GridBagConstraints.EAST;
        lc.insets = new Insets(3, 0, 3, 8);
        form.add(new JLabel(label), lc);
        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1;
        fc.gridy = row;
        fc.weightx = 1;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.insets = new Insets(3, 0, 3, 6);
        form.add(field, fc);
        if (trailing != null) {
            GridBagConstraints tc = new GridBagConstraints();
            tc.gridx = 2;
            tc.gridy = row;
            form.add(trailing, tc);
        }
    }

    private JButton browseButton() {
        JButton b = new JButton("Browse...");
        b.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fc = new JFileChooser();
                fc.setDialogTitle("Choose the folder holding your application jar (usually dist)");
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                String current = buildField.getText().trim();
                if (!current.isEmpty()) {
                    File f = new File(current);
                    fc.setCurrentDirectory(f.isDirectory() ? f : f.getParentFile());
                }
                if (fc.showOpenDialog(Publisher.this) == JFileChooser.APPROVE_OPTION) {
                    buildField.setText(fc.getSelectedFile().getAbsolutePath());
                    buildFolderChosen(fc.getSelectedFile().toPath());
                }
            }
        });
        return b;
    }

    private void showOptions() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(new JLabel("For the application release:"));
        p.add(Box.createVerticalStrut(6));
        p.add(clientFilesBox);
        p.add(oldLayoutBox);
        JLabel hint = new JLabel("<html><small>Copies for older updaters: only needed while PCs still"
                + " run an earlier hp-updater build.<br>Sets never get them.</small></html>");
        hint.setForeground(new Color(0x55, 0x55, 0x55));
        p.add(Box.createVerticalStrut(6));
        p.add(hint);
        JOptionPane.showMessageDialog(this, p, "Options", JOptionPane.PLAIN_MESSAGE);
        savePrefs();
    }

    // ---------------------------------------------------------------- behaviour

    private int currentSide() {
        return tabs.getSelectedIndex() == 1 ? SET : APP;
    }

    /** Scans the folder and fills both tabs from the project's settings. */
    private void buildFolderChosen(Path dir) {
        try {
            Path previous = lastBuildDir;
            boolean otherProject = previous == null
                    || !previous.toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize());
            lastBuildDir = dir;
            files.setRows(ReleaseBuilder.scan(dir));
            setBuildLabel.setText(dir.toAbsolutePath() + "   (same as Application)");
            append("Scanned " + dir + ": " + files.size() + " files");

            if (otherProject) {
                appBaseField.setText("");
                uiNameField.setText("");
                mainClassField.setText("");
                jvmArgsField.setText("");
                versionField.setText("");
                setUrlField.setText("");
                setNameField.setText("");
                setVersionField.setText("");
            }
            appOutDir = dir.toAbsolutePath().getParent().resolve("upload");
            Path config = findConfig(dir);
            if (config != null) {
                UpdateManifest cfg = ReleaseBuilder.readConfig(config);
                if (cfg.appBase() != null && !"/".equals(cfg.appBase())) {
                    appBaseField.setText(cfg.appBase());
                }
                if (cfg.uiName() != null) {
                    uiNameField.setText(cfg.uiName());
                }
                if (cfg.mainClass() != null) {
                    mainClassField.setText(cfg.mainClass());
                }
                if (!cfg.jvmArgs().isEmpty()) {
                    jvmArgsField.setText(join(cfg.jvmArgs()));
                }
                Path savedOut = ReleaseBuilder.readOutDir(config, dir);
                if (savedOut != null) {
                    appOutDir = savedOut;
                }
                for (String ex : ReleaseBuilder.readExcludes(config)) {
                    files.setOwner(ex, NONE);
                }
                SetConfig sc = SetConfig.read(config);
                if (sc.url != null) {
                    setUrlField.setText(sc.url);
                }
                if (sc.name != null) {
                    setNameField.setText(sc.name);
                }
                for (String f : sc.files) {
                    files.setOwner(f, SET);
                }
                append("Read settings from " + config);
            } else if (otherProject) {
                append("No app.txt for this project yet: fill in the fields of both tabs.");
            }
            if (mainClassField.getText().trim().isEmpty()) {
                for (ReleaseBuilder.Candidate c : files.rows()) {
                    if (c.classpath && c.path.indexOf('/') < 0) {
                        String mc = ReleaseBuilder.mainClassOf(dir.resolve(c.path));
                        if (mc != null) {
                            mainClassField.setText(mc);
                            break;
                        }
                    }
                }
            }
            if (versionField.getText().trim().isEmpty()) {
                versionField.setText(suggestedVersion(appOutDir));
            }
            setClientShown = "";
            setUrlChanged(true);
            appLastLabel.setText(lastSetupText(appOutDir));
            refreshFooter();
        } catch (IOException e) {
            error("Could not read that folder", e);
        }
    }

    /** The set's address changed: its output folder, and (once settled) its version. */
    private void setUrlChanged(boolean settled) {
        Path out = outDir(SET);
        setLastLabel.setText(out == null ? " " : lastSetupText(out) + "   output: "
                + displayPath(out));
        String client = clientOf(setUrlField.getText());
        if (settled && out != null && !client.equals(setClientShown)) {
            setClientShown = client;
            setVersionField.setText(suggestedVersion(out));
            setLastLabel.setText(lastSetupText(out) + "   output: " + displayPath(out));
        }
        refreshFooter();
    }

    private String displayPath(Path out) {
        Path project = lastBuildDir == null ? null : lastBuildDir.toAbsolutePath().getParent();
        return project != null && out.startsWith(project) ? project.relativize(out).toString()
                : out.toString();
    }

    /** "last setup: 1.0.3", from the release in that output folder. */
    private static String lastSetupText(Path out) {
        String v = versionIn(out);
        return v == null ? "no setup yet" : "last setup: " + v;
    }

    private static String versionIn(Path out) {
        try {
            Path live = out.resolve(UpdateConfig.APP_TXT);
            return Files.isRegularFile(live) ? ReleaseBuilder.readConfig(live).version() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String suggestedVersion(Path out) {
        String v = out == null ? null : versionIn(out);
        String next = v == null ? null : ReleaseBuilder.nextVersion(v);
        return next != null ? next : "1.0.0";
    }

    /** The last part of the set's address: pt-abc for .../sets/pt-abc/. */
    static String clientOf(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        int slash = u.lastIndexOf('/');
        String last = slash >= 0 ? u.substring(slash + 1) : u;
        if (u.indexOf("://") >= 0 && u.indexOf('/', u.indexOf("://") + 3) < 0) {
            last = ""; // only a host, no folder
        }
        return last.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Output folder of a side: the application's, or project\\upload-set\\CLIENT. */
    private Path outDir(int side) {
        if (side == APP) {
            return appOutDir;
        }
        String client = clientOf(setUrlField.getText());
        if (lastBuildDir == null || client.isEmpty()) {
            return null;
        }
        return lastBuildDir.toAbsolutePath().getParent().resolve("upload-set").resolve(client);
    }

    private void createSetup(final int side) {
        final ReleaseBuilder.Options o = new ReleaseBuilder.Options();
        o.buildDir = Paths.get(buildField.getText().trim());
        o.outDir = outDir(side);
        o.exclude = new LinkedHashSet<String>(files.pathsNotOwnedBy(side));
        if (side == APP) {
            o.version = versionField.getText().trim();
            o.appBase = appBaseField.getText().trim();
            o.uiName = uiNameField.getText().trim();
            o.mainClass = mainClassField.getText().trim();
            for (String a : jvmArgsField.getText().trim().split("\\s+")) {
                if (!a.isEmpty()) {
                    o.jvmArgs.add(a);
                }
            }
            o.oldLayout = Boolean.valueOf(oldLayoutBox.isSelected());
            addNotes(o, notesArea);
        } else {
            o.version = setVersionField.getText().trim();
            o.appBase = setUrlField.getText().trim();
            o.uiName = setNameField.getText().trim();
            o.plugin = SET_NAME;
            o.pluginTarget = ".";
            o.oldLayout = Boolean.FALSE;
            addNotes(o, setNotesArea);
            if (o.outDir == null) {
                warn("Enter the set's download URL, ending with the client's folder,\n"
                        + "for example http://172.104.47.59:8080/apps/gasx/sets/pt-abc/");
                return;
            }
        }
        if (files.count(side) == 0) {
            warn("No file is ticked in the " + sideName(side) + " tab.");
            return;
        }
        try {
            o.validate();
        } catch (IOException e) {
            warn(e.getMessage());
            return;
        }
        String existing = versionIn(o.outDir);
        if (existing != null && o.version.equals(existing)) {
            int ok = JOptionPane.showConfirmDialog(this, "The output folder already holds version "
                    + existing + ".\nPublishing the same version again means PCs that already"
                    + " have it will not update.\n\nContinue?", "Same version",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            ReleaseBuilder.Impact impact = ReleaseBuilder.preview(o);
            if (impact.worthMentioning()) {
                JTextArea text = new JTextArea(impact.describe());
                text.setEditable(false);
                text.setRows(Math.min(16, impact.describe().split("\n").length + 1));
                text.setColumns(60);
                int ok = JOptionPane.showConfirmDialog(this, new JScrollPane(text),
                        "Version " + o.version + " will change customers' files",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ok != JOptionPane.OK_OPTION) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // no readable previous release in the output folder: nothing to compare with
        }

        final ReleaseBuilder.Options appForConfig = appOptionsForConfig();
        final SetConfig setForConfig = currentSetConfig();
        setBusy(true);
        append("");
        append("Building " + sideName(side) + " version " + o.version + " from " + o.buildDir);

        new SwingWorker<ReleaseBuilder.Result, String>() {
            @Override
            protected ReleaseBuilder.Result doInBackground() throws Exception {
                ReleaseBuilder.Result r = ReleaseBuilder.build(o, new ReleaseBuilder.Progress() {
                    @Override
                    public void file(final String path, long size, final int index, final int total) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                progress.setMaximum(total);
                                progress.setValue(index);
                                progress.setString(index + " of " + total + "   " + path);
                            }
                        });
                    }

                    @Override
                    public void log(String message) {
                        publish(message);
                    }
                });
                if (side == SET) {
                    Path f = writeClientSetapp(o.outDir.getParent().getParent().resolve("client-set")
                            .resolve(o.outDir.getFileName().toString()), o.appBase, o.uiName);
                    publish("client file for new PCs of this client: " + f);
                }
                if (side == APP && clientFilesBox.isSelected()) {
                    String name = (o.uiName == null || o.uiName.isEmpty() ? "Start" : o.uiName)
                            .replaceAll("[^A-Za-z0-9._-]", "") + ".bat";
                    Path client = ReleaseBuilder.writeClientFiles(o,
                            o.outDir.getParent().resolve("client"), name);
                    publish("client folder: " + client);
                }
                for (Path f : saveProjectConfig(appForConfig, setForConfig)) {
                    publish("saved settings to " + f);
                }
                return r;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String s : chunks) {
                    append(s);
                }
            }

            @Override
            protected void done() {
                setBusy(false);
                progress.setString(" ");
                try {
                    ReleaseBuilder.Result r = get();
                    progress.setValue(progress.getMaximum());
                    append("Done. " + sideName(side) + " version " + o.version + " is ready in "
                            + r.outDir);
                    if (side == SET) {
                        append("Address for this client's database (set_url): "
                                + ReleaseBuilder.normalizeUrl(o.appBase));
                    }
                    savePrefs();
                    String next = ReleaseBuilder.nextVersion(o.version);
                    if (side == APP) {
                        notesArea.setText("");
                        if (next != null) {
                            versionField.setText(next);
                        }
                        appLastLabel.setText(lastSetupText(o.outDir));
                    } else {
                        setNotesArea.setText("");
                        if (next != null) {
                            setVersionField.setText(next);
                        }
                        setUrlChanged(false);
                    }
                    refreshFooter();
                    int up = JOptionPane.showConfirmDialog(Publisher.this,
                            sideName(side) + " version " + o.version + " is ready.\n\n"
                            + r.fileCount + " files, " + UpdateManager.humanBytes(r.totalBytes) + "\n"
                            + r.newFiles + " new or changed (" + UpdateManager.humanBytes(r.newBytes)
                            + ")\n\nUpload it to the server now?",
                            "Setup created", JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                    if (up == JOptionPane.YES_OPTION) {
                        UploadDialog.open(Publisher.this, r.outDir, prefsFile());
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    append("FAILED: " + cause.getMessage());
                    error("Could not create the setup", cause);
                }
            }
        }.execute();
    }

    /**
     * Writes the setapp.txt a new PC of this client can start with (copy it next to app.txt):
     * the set is then downloaded at the very first start, without the database and without
     * a restart. Outside the upload folder, so it is never uploaded.
     */
    static Path writeClientSetapp(Path dir, String url, String name) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(SET_NAME + "app.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("# Copy next to app.txt on a new PC of this client: the set is then\n");
        sb.append("# downloaded at the first start, without the database.\n");
        sb.append("plugin  = ").append(SET_NAME).append('\n');
        sb.append("appbase = ").append(ReleaseBuilder.normalizeUrl(url)).append('\n');
        if (name != null && !name.trim().isEmpty()) {
            sb.append("ui.name = ").append(name.trim()).append('\n');
        }
        Io.writeStringAtomic(f, sb.toString());
        return f;
    }

    private static void addNotes(ReleaseBuilder.Options o, JTextArea area) {
        for (String l : area.getText().split("\n")) {
            o.notes.add(l.replace("\r", ""));
        }
    }

    private static String sideName(int side) {
        return side == SET ? "Set" : "Application";
    }

    private void setBusy(boolean busy) {
        createButton.setEnabled(!busy);
        uploadButton.setEnabled(!busy);
        openOutButton.setEnabled(!busy);
        progress.setIndeterminate(false);
        if (busy) {
            progress.setValue(0);
        } else {
            refreshFooter();
        }
    }

    // ------------------------------------------------------------------ settings

    /** The application tab's settings, for app.txt (also when the set is being built). */
    private ReleaseBuilder.Options appOptionsForConfig() {
        ReleaseBuilder.Options a = new ReleaseBuilder.Options();
        a.buildDir = Paths.get(buildField.getText().trim());
        a.outDir = appOutDir;
        a.appBase = appBaseField.getText().trim();
        a.uiName = uiNameField.getText().trim();
        a.mainClass = mainClassField.getText().trim();
        for (String s : jvmArgsField.getText().trim().split("\\s+")) {
            if (!s.isEmpty()) {
                a.jvmArgs.add(s);
            }
        }
        // Everything the application release does not carry, set files included, so the
        // command line (ManifestTool with this app.txt) builds the same release.
        a.exclude = new LinkedHashSet<String>(files.pathsNotOwnedBy(APP));
        return a;
    }

    private SetConfig currentSetConfig() {
        SetConfig s = new SetConfig();
        s.url = setUrlField.getText().trim();
        s.name = setNameField.getText().trim();
        s.files.addAll(files.pathsWithOwner(SET));
        return s;
    }

    /**
     * Writes the project's app.txt (in dist, and next to build.xml so it survives Clean and
     * Build): the application's settings, its unticked files, and the set's address and files.
     */
    private List<Path> saveProjectConfig(ReleaseBuilder.Options app, SetConfig set) throws IOException {
        List<Path> written = new ArrayList<Path>();
        List<Path> targets = new ArrayList<Path>();
        targets.add(app.buildDir.resolve(UpdateConfig.APP_TXT));
        Path project = projectFolder(app.buildDir);
        if (project != null) {
            Path keep = project.resolve(UpdateConfig.APP_TXT);
            if (!Files.exists(keep) || isBuildConfig(keep)) {
                targets.add(keep);
            }
        }
        for (Path f : targets) {
            ReleaseBuilder.saveConfig(app, f);
            String text = Io.readString(f);
            Io.writeStringAtomic(f, (text == null ? "" : text) + set.toText());
            written.add(f);
        }
        return written;
    }

    /** The set tab's part of app.txt, as comment lines the application release ignores. */
    static final class SetConfig {
        String url;
        String name;
        final List<String> files = new ArrayList<String>();

        String toText() {
            StringBuilder sb = new StringBuilder("\n# Set (client plugin) published from this build"
                    + " folder.\n");
            if (url != null && !url.isEmpty()) {
                sb.append("# set.appbase = ").append(url).append('\n');
            }
            if (name != null && !name.isEmpty()) {
                sb.append("# set.name = ").append(name).append('\n');
            }
            for (String f : files) {
                sb.append("# set.file = ").append(f).append('\n');
            }
            return sb.toString();
        }

        static SetConfig read(Path file) {
            SetConfig c = new SetConfig();
            String text = Io.readString(file);
            if (text == null) {
                return c;
            }
            for (String raw : text.split("\n")) {
                String s = raw.trim();
                if (!s.startsWith("# set.") || s.indexOf('=') < 0) {
                    continue;
                }
                String key = s.substring(6, s.indexOf('=')).trim();
                String value = s.substring(s.indexOf('=') + 1).trim();
                if ("appbase".equals(key)) {
                    c.url = value;
                } else if ("name".equals(key)) {
                    c.name = value;
                } else if ("file".equals(key) && !value.isEmpty()) {
                    c.files.add(value);
                }
            }
            return c;
        }
    }

    /** The project's settings file: in the build folder, or next to build.xml. */
    private static Path findConfig(Path dir) {
        Path[] places = {dir.resolve(UpdateConfig.APP_TXT), dir.resolve("getdown.txt"),
            dir.toAbsolutePath().getParent() == null ? null
                    : dir.toAbsolutePath().getParent().resolve(UpdateConfig.APP_TXT)};
        for (Path p : places) {
            if (p != null && Files.isRegularFile(p) && isBuildConfig(p)) {
                return p;
            }
        }
        return null;
    }

    /** A settings file of ours or getdown's (with an appbase), not some other app.txt. */
    private static boolean isBuildConfig(Path p) {
        String text = Io.readString(p);
        return text != null && text.toLowerCase().contains("appbase");
    }

    /** The project folder (the one with build.xml) when the build folder is its dist. */
    private static Path projectFolder(Path buildDir) {
        Path parent = buildDir.toAbsolutePath().normalize().getParent();
        if (parent != null && (Files.isRegularFile(parent.resolve("build.xml"))
                || Files.isDirectory(parent.resolve("nbproject")))) {
            return parent;
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private void refreshFooter() {
        int side = currentSide();
        Path out = outDir(side);
        boolean ready = out != null && Files.isRegularFile(out.resolve(UpdateConfig.APP_TXT));
        uploadButton.setEnabled(ready);
        openOutButton.setEnabled(out != null && Files.isDirectory(out));
        optionsButton.setEnabled(side == APP);
        String label = side == SET
                ? "Set" + (clientOf(setUrlField.getText()).isEmpty() ? ""
                        : " (" + clientOf(setUrlField.getText()) + ")")
                : "Application";
        int n = files.count(side);
        summary.setText(label + ": " + n + " files, " + UpdateManager.humanBytes(files.bytes(side)));
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

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, "Not ready yet", JOptionPane.WARNING_MESSAGE);
    }

    private void error(String message, Throwable cause) {
        JOptionPane.showMessageDialog(this,
                message + (cause == null ? "" : ":\n" + cause.getMessage()),
                "Problem", JOptionPane.ERROR_MESSAGE);
    }

    private void openFolder(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
            }
        } catch (Exception e) {
            error("Could not open " + dir, e);
        }
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    static Path prefsFile() {
        return Paths.get(System.getProperty("user.home"), PREFS);
    }

    private void loadPrefs() {
        Properties p = Io.loadProps(prefsFile());
        buildField.setText(p.getProperty("build", ""));
        oldLayoutBox.setSelected("true".equals(p.getProperty("oldLayout")));
        clientFilesBox.setSelected(!"false".equals(p.getProperty("clientFiles")));
        String b = buildField.getText().trim();
        if (!b.isEmpty() && Files.isDirectory(Paths.get(b))) {
            buildFolderChosen(Paths.get(b));
        }
    }

    private void savePrefs() {
        Properties p = Io.loadProps(prefsFile()); // keeps the upload settings stored there
        p.setProperty("build", buildField.getText().trim());
        p.setProperty("oldLayout", String.valueOf(oldLayoutBox.isSelected()));
        p.setProperty("clientFiles", String.valueOf(clientFilesBox.isSelected()));
        try {
            Io.storeProps(prefsFile(), p);
        } catch (IOException ignored) {
            // remembering the last settings is a convenience, not a requirement
        }
    }

    // ------------------------------------------------------------------ the files

    /**
     * Every file of the build folder and who it belongs to: the application, the set, or
     * neither. Both tabs show this one list, so a file can never be in both releases.
     */
    static final class Files2 {
        private final List<ReleaseBuilder.Candidate> rows = new ArrayList<ReleaseBuilder.Candidate>();
        private final List<Integer> owner = new ArrayList<Integer>();
        private final List<Runnable> listeners = new ArrayList<Runnable>();

        void addListener(Runnable r) {
            listeners.add(r);
        }

        private void changed() {
            for (Runnable r : listeners) {
                r.run();
            }
        }

        void setRows(List<ReleaseBuilder.Candidate> list) {
            rows.clear();
            owner.clear();
            for (ReleaseBuilder.Candidate c : list) {
                rows.add(c);
                owner.add(Integer.valueOf(c.skipByDefault ? NONE : APP));
            }
            changed();
        }

        List<ReleaseBuilder.Candidate> rows() {
            return rows;
        }

        int size() {
            return rows.size();
        }

        int ownerAt(int i) {
            return owner.get(i).intValue();
        }

        void setOwnerAt(int i, int who) {
            owner.set(i, Integer.valueOf(who));
            changed();
        }

        void setOwner(String path, int who) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).path.equals(path)) {
                    owner.set(i, Integer.valueOf(who));
                }
            }
            changed();
        }

        /** Ticks every file the other side does not own (build junk stays as it is). */
        void checkAll(int side) {
            for (int i = 0; i < rows.size(); i++) {
                if (ownerAt(i) == NONE && !rows.get(i).skipByDefault) {
                    owner.set(i, Integer.valueOf(side));
                }
            }
            changed();
        }

        /** Unticks every file of this side; they become free, not the other side's. */
        void uncheckAll(int side) {
            for (int i = 0; i < rows.size(); i++) {
                if (ownerAt(i) == side) {
                    owner.set(i, Integer.valueOf(NONE));
                }
            }
            changed();
        }

        List<String> pathsWithOwner(int who) {
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < rows.size(); i++) {
                if (ownerAt(i) == who) {
                    out.add(rows.get(i).path);
                }
            }
            return out;
        }

        Set<String> pathsNotOwnedBy(int side) {
            Set<String> out = new LinkedHashSet<String>();
            for (int i = 0; i < rows.size(); i++) {
                if (ownerAt(i) != side) {
                    out.add(rows.get(i).path);
                }
            }
            return out;
        }

        int count(int side) {
            return pathsWithOwner(side).size();
        }

        long bytes(int side) {
            long total = 0;
            for (int i = 0; i < rows.size(); i++) {
                total += ownerAt(i) == side ? rows.get(i).size : 0;
            }
            return total;
        }
    }

    /** One tab's view of the shared file list. */
    private static final class FileTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"Publish", "File", "Size", "Type", "Note"};

        final Files2 files;
        final int side;

        FileTableModel(Files2 files, int side) {
            this.files = files;
            this.side = side;
            files.addListener(new Runnable() {
                @Override
                public void run() {
                    fireTableDataChanged();
                }
            });
        }

        @Override
        public int getRowCount() {
            return files.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            int other = side == APP ? SET : APP;
            return column == 0 && files.ownerAt(row) != other;
        }

        @Override
        public Object getValueAt(int row, int column) {
            ReleaseBuilder.Candidate c = files.rows().get(row);
            int who = files.ownerAt(row);
            switch (column) {
                case 0:
                    return Boolean.valueOf(who == side);
                case 1:
                    return c.path;
                case 2:
                    return UpdateManager.humanBytes(c.size);
                case 3:
                    return c.classpath ? "code" : "file";
                default:
                    if (side == APP && who == SET) {
                        return "in Set tab";
                    }
                    if (side == SET && who == APP) {
                        return "in Application tab";
                    }
                    if (side == SET && who == SET && !c.classpath) {
                        return "customer copy kept";
                    }
                    return c.note == null ? "" : c.note;
            }
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0 && isCellEditable(row, column)) {
                files.setOwnerAt(row, Boolean.TRUE.equals(value) ? side : NONE);
            }
        }
    }

    /** The tick box, greyed out for a file the other tab owns. */
    private static final class CheckRenderer implements TableCellRenderer {
        private final TableCellRenderer base;

        CheckRenderer(TableCellRenderer base) {
            this.base = base;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                boolean focused, int row, int column) {
            Component c = base.getTableCellRendererComponent(t, value, selected, focused, row, column);
            c.setEnabled(t.getModel().isCellEditable(row, column));
            return c;
        }
    }

    /** Greys out ordinary rows so the flagged ones stand out. */
    private static final class NoteRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                boolean focused, int row, int column) {
            Component c = super.getTableCellRendererComponent(t, value, selected, focused, row, column);
            String v = value == null ? "" : value.toString();
            if (!selected) {
                if (v.startsWith("in ")) {
                    c.setForeground(new Color(0x88, 0x88, 0x88));
                } else if (v.isEmpty() || v.startsWith("customer")) {
                    c.setForeground(v.isEmpty() ? t.getForeground() : new Color(0x2E, 0x6B, 0x30));
                } else {
                    c.setForeground(new Color(0xA0, 0x52, 0x00));
                }
            }
            return c;
        }
    }

    public static void main(String[] args) {
        FtpFs.prepareTls(); // before anything uses TLS
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // the default look and feel is fine
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new Publisher().setVisible(true);
            }
        });
    }
}
