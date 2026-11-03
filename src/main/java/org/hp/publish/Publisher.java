package org.hp.publish;

import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
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

    static final int APP = 1;
    static final int SET = 2;

    /** The application's files: the build folder without its client folders. */
    private final Files2 appFiles = new Files2(APP);
    /** The set's files: everything in the client folder. */
    private final Files2 setFiles = new Files2(SET);
    private final FileTableModel appModel = new FileTableModel(appFiles);
    private final FileTableModel setModel = new FileTableModel(setFiles);
    /** Shown in Options... (not part of the window). */
    private final JCheckBox clientFilesBox = new JCheckBox("Also create a client folder", true);
    private final JCheckBox oldLayoutBox = new JCheckBox("Copies for older updaters", false);

    /** The build folder the fields belong to. */
    private Path lastBuildDir;
    /** Everything in the build folder, client folders included, as last scanned. */
    private List<ReleaseBuilder.Candidate> appScan = new ArrayList<ReleaseBuilder.Candidate>();
    /** The client folder of the Set tab, or null. */
    private Path setDir;
    /** Application output folder (from the project's app.txt, or project\\upload). */
    private Path appOutDir;
    /** The client the set's version was last suggested for. */
    private String setClientShown = "";

    public Publisher() {
        initComponents();

        split.setBorder(null); // here, not in the form: NetBeans drops a "no border" setting
        versionField.setMaximumSize(new Dimension(120, versionField.getPreferredSize().height));
        setVersionField.setMaximumSize(new Dimension(120, setVersionField.getPreferredSize().height));
        configureTable(appTable);
        configureTable(setTable);
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
        // Added here, not in the form: adding the tabs would call it before the window is complete.
        tabs.addChangeListener(new javax.swing.event.ChangeListener() {
            @Override
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                refreshFooter();
            }
        });
        Runnable footer = new Runnable() {
            @Override
            public void run() {
                refreshFooter();
            }
        };
        appFiles.addListener(footer);
        setFiles.addListener(footer);

        loadPrefs();
        pack();
        setLocationRelativeTo(null);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {
        java.awt.GridBagConstraints gridBagConstraints;

        split = new javax.swing.JSplitPane();
        tabs = new javax.swing.JTabbedPane();
        appPanel = new javax.swing.JPanel();
        appForm = new javax.swing.JPanel();
        appBuildLabel = new javax.swing.JLabel();
        buildField = new javax.swing.JTextField();
        browseButton = new javax.swing.JButton();
        appBaseLabel = new javax.swing.JLabel();
        appBaseField = new javax.swing.JTextField();
        uiNameLabel = new javax.swing.JLabel();
        uiNameField = new javax.swing.JTextField();
        mainClassLabel = new javax.swing.JLabel();
        mainClassField = new javax.swing.JTextField();
        jvmArgsLabel = new javax.swing.JLabel();
        jvmArgsField = new javax.swing.JTextField();
        versionLabel = new javax.swing.JLabel();
        appVersionPanel = new javax.swing.JPanel();
        versionField = new javax.swing.JTextField();
        appVersionGap = new javax.swing.Box.Filler(new java.awt.Dimension(12, 0), new java.awt.Dimension(12, 0), new java.awt.Dimension(12, 32767));
        appLastLabel = new javax.swing.JLabel();
        appVersionGlue = new javax.swing.Box.Filler(new java.awt.Dimension(0, 0), new java.awt.Dimension(0, 0), new java.awt.Dimension(32767, 0));
        notesLabel = new javax.swing.JLabel();
        notesScroll = new javax.swing.JScrollPane();
        notesArea = new javax.swing.JTextArea();
        appList = new javax.swing.JPanel();
        appHead = new javax.swing.JPanel();
        appListLabel = new javax.swing.JLabel();
        appListButtons = new javax.swing.JPanel();
        appCheckAll = new javax.swing.JButton();
        appUncheckAll = new javax.swing.JButton();
        appTableScroll = new javax.swing.JScrollPane();
        appTable = new javax.swing.JTable(appModel);
        setPanel = new javax.swing.JPanel();
        setForm = new javax.swing.JPanel();
        setFolderLabel = new javax.swing.JLabel();
        setFolderField = new javax.swing.JTextField();
        setBrowseButton = new javax.swing.JButton();
        setUrlLabel = new javax.swing.JLabel();
        setUrlField = new javax.swing.JTextField();
        setNameLabel = new javax.swing.JLabel();
        setNameField = new javax.swing.JTextField();
        setVersionLabel = new javax.swing.JLabel();
        setVersionPanel = new javax.swing.JPanel();
        setVersionField = new javax.swing.JTextField();
        setVersionGap = new javax.swing.Box.Filler(new java.awt.Dimension(12, 0), new java.awt.Dimension(12, 0), new java.awt.Dimension(12, 32767));
        setLastLabel = new javax.swing.JLabel();
        setVersionGlue = new javax.swing.Box.Filler(new java.awt.Dimension(0, 0), new java.awt.Dimension(0, 0), new java.awt.Dimension(32767, 0));
        setNotesLabel = new javax.swing.JLabel();
        setNotesScroll = new javax.swing.JScrollPane();
        setNotesArea = new javax.swing.JTextArea();
        setList = new javax.swing.JPanel();
        setHead = new javax.swing.JPanel();
        setListLabel = new javax.swing.JLabel();
        setListButtons = new javax.swing.JPanel();
        setCheckAll = new javax.swing.JButton();
        setUncheckAll = new javax.swing.JButton();
        setTableScroll = new javax.swing.JScrollPane();
        setTable = new javax.swing.JTable(setModel);
        logPanel = new javax.swing.JPanel();
        logScroll = new javax.swing.JScrollPane();
        log = new javax.swing.JTextArea();
        south = new javax.swing.JPanel();
        progressPanel = new javax.swing.JPanel();
        progress = new javax.swing.JProgressBar();
        buttons = new javax.swing.JPanel();
        summary = new javax.swing.JLabel();
        buttonsGlue = new javax.swing.Box.Filler(new java.awt.Dimension(0, 0), new java.awt.Dimension(0, 0), new java.awt.Dimension(32767, 0));
        openOutButton = new javax.swing.JButton();
        buttonsGap1 = new javax.swing.Box.Filler(new java.awt.Dimension(6, 0), new java.awt.Dimension(6, 0), new java.awt.Dimension(6, 32767));
        optionsButton = new javax.swing.JButton();
        buttonsGap2 = new javax.swing.Box.Filler(new java.awt.Dimension(6, 0), new java.awt.Dimension(6, 0), new java.awt.Dimension(6, 32767));
        createButton = new javax.swing.JButton();
        buttonsGap3 = new javax.swing.Box.Filler(new java.awt.Dimension(6, 0), new java.awt.Dimension(6, 0), new java.awt.Dimension(6, 32767));
        uploadButton = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("Release publisher");
        setPreferredSize(new java.awt.Dimension(900, 760));
        getContentPane().setLayout(new java.awt.BorderLayout());
        split.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);
        split.setResizeWeight(0.8);
        appPanel.setLayout(new java.awt.BorderLayout());
        appForm.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 4, 12));
        appForm.setLayout(new java.awt.GridBagLayout());
        appBuildLabel.setText("Build folder");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        appForm.add(appBuildLabel, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        appForm.add(buildField, gridBagConstraints);

        browseButton.setText("Browse...");
        browseButton.addActionListener(this::browseButtonActionPerformed);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 0;
        appForm.add(browseButton, gridBagConstraints);

        appBaseLabel.setText("Download URL");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        appForm.add(appBaseLabel, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        appForm.add(appBaseField, gridBagConstraints);

        uiNameLabel.setText("App name");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        appForm.add(uiNameLabel, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        appForm.add(uiNameField, gridBagConstraints);

        mainClassLabel.setText("Main class");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        appForm.add(mainClassLabel, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        appForm.add(mainClassField, gridBagConstraints);

        jvmArgsLabel.setText("JVM options");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        appForm.add(jvmArgsLabel, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        appForm.add(jvmArgsField, gridBagConstraints);

        versionLabel.setText("Version");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        appForm.add(versionLabel, gridBagConstraints);

        appVersionPanel.setLayout(new javax.swing.BoxLayout(appVersionPanel, javax.swing.BoxLayout.X_AXIS));
        versionField.setColumns(10);
        appVersionPanel.add(versionField);

        appVersionPanel.add(appVersionGap);

        appLastLabel.setText(" ");
        appLastLabel.setForeground(new java.awt.Color(85, 85, 85));
        appVersionPanel.add(appLastLabel);

        appVersionPanel.add(appVersionGlue);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        appForm.add(appVersionPanel, gridBagConstraints);

        notesLabel.setText("What's new");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 6;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        appForm.add(notesLabel, gridBagConstraints);

        notesScroll.setPreferredSize(new java.awt.Dimension(200, 44));
        notesArea.setColumns(40);
        notesArea.setLineWrap(true);
        notesArea.setRows(2);
        notesArea.setWrapStyleWord(true);
        notesScroll.setViewportView(notesArea);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 6;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        appForm.add(notesScroll, gridBagConstraints);

        appPanel.add(appForm, java.awt.BorderLayout.NORTH);

        appList.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 12, 4, 12));
        appList.setLayout(new java.awt.BorderLayout(0, 4));
        appHead.setLayout(new java.awt.BorderLayout());
        appListLabel.setText("Files of the application");
        appHead.add(appListLabel, java.awt.BorderLayout.WEST);

        appListButtons.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        appCheckAll.setText("Check all");
        appCheckAll.addActionListener(this::appCheckAllActionPerformed);
        appListButtons.add(appCheckAll);

        appUncheckAll.setText("Uncheck all");
        appUncheckAll.addActionListener(this::appUncheckAllActionPerformed);
        appListButtons.add(appUncheckAll);

        appHead.add(appListButtons, java.awt.BorderLayout.EAST);

        appList.add(appHead, java.awt.BorderLayout.NORTH);

        appTableScroll.setViewportView(appTable);

        appList.add(appTableScroll, java.awt.BorderLayout.CENTER);

        appPanel.add(appList, java.awt.BorderLayout.CENTER);

        tabs.addTab("Application", appPanel);

        setPanel.setLayout(new java.awt.BorderLayout());
        setForm.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 4, 12));
        setForm.setLayout(new java.awt.GridBagLayout());
        setFolderLabel.setText("Client folder");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        setForm.add(setFolderLabel, gridBagConstraints);

        setFolderField.setEditable(false);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        setForm.add(setFolderField, gridBagConstraints);

        setBrowseButton.setText("Browse...");
        setBrowseButton.addActionListener(this::setBrowseButtonActionPerformed);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 0;
        setForm.add(setBrowseButton, gridBagConstraints);

        setUrlLabel.setText("Download URL");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        setForm.add(setUrlLabel, gridBagConstraints);

        setUrlField.setToolTipText("<html>The client's own set folder on the server, e.g.<br>http://172.104.47.59:8080/apps/gasx/sets/pt-abc/<br>Put the same address into that client's database (set_url).</html>");
        setUrlField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                setUrlFieldFocusLost(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        setForm.add(setUrlField, gridBagConstraints);

        setNameLabel.setText("Set name");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        setForm.add(setNameLabel, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        setForm.add(setNameField, gridBagConstraints);

        setVersionLabel.setText("Version");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        setForm.add(setVersionLabel, gridBagConstraints);

        setVersionPanel.setLayout(new javax.swing.BoxLayout(setVersionPanel, javax.swing.BoxLayout.X_AXIS));
        setVersionField.setColumns(10);
        setVersionPanel.add(setVersionField);

        setVersionPanel.add(setVersionGap);

        setLastLabel.setText(" ");
        setLastLabel.setForeground(new java.awt.Color(85, 85, 85));
        setVersionPanel.add(setLastLabel);

        setVersionPanel.add(setVersionGlue);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        setForm.add(setVersionPanel, gridBagConstraints);

        setNotesLabel.setText("What's new");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 8);
        setForm.add(setNotesLabel, gridBagConstraints);

        setNotesScroll.setPreferredSize(new java.awt.Dimension(200, 44));
        setNotesArea.setColumns(40);
        setNotesArea.setLineWrap(true);
        setNotesArea.setRows(2);
        setNotesArea.setWrapStyleWord(true);
        setNotesScroll.setViewportView(setNotesArea);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 3, 6);
        setForm.add(setNotesScroll, gridBagConstraints);

        setPanel.add(setForm, java.awt.BorderLayout.NORTH);

        setList.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 12, 4, 12));
        setList.setLayout(new java.awt.BorderLayout(0, 4));
        setHead.setLayout(new java.awt.BorderLayout());
        setListLabel.setText("Files of the set (each is installed at the same place in the application folder)");
        setHead.add(setListLabel, java.awt.BorderLayout.WEST);

        setListButtons.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        setCheckAll.setText("Check all");
        setCheckAll.addActionListener(this::setCheckAllActionPerformed);
        setListButtons.add(setCheckAll);

        setUncheckAll.setText("Uncheck all");
        setUncheckAll.addActionListener(this::setUncheckAllActionPerformed);
        setListButtons.add(setUncheckAll);

        setHead.add(setListButtons, java.awt.BorderLayout.EAST);

        setList.add(setHead, java.awt.BorderLayout.NORTH);

        setTableScroll.setViewportView(setTable);

        setList.add(setTableScroll, java.awt.BorderLayout.CENTER);

        setPanel.add(setList, java.awt.BorderLayout.CENTER);

        tabs.addTab("Set", setPanel);

        split.setTopComponent(tabs);

        logPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 12, 0, 12));
        logPanel.setLayout(new java.awt.BorderLayout());
        log.setEditable(false);
        log.setLineWrap(true);
        log.setRows(6);
        log.setWrapStyleWord(true);
        logScroll.setViewportView(log);

        logPanel.add(logScroll, java.awt.BorderLayout.CENTER);

        split.setBottomComponent(logPanel);

        getContentPane().add(split, java.awt.BorderLayout.CENTER);

        south.setLayout(new java.awt.BorderLayout());
        progressPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 12, 0, 12));
        progressPanel.setLayout(new java.awt.BorderLayout());
        progress.setString(" ");
        progress.setStringPainted(true);
        progressPanel.add(progress, java.awt.BorderLayout.CENTER);

        south.add(progressPanel, java.awt.BorderLayout.NORTH);

        buttons.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 12, 12, 12));
        buttons.setLayout(new javax.swing.BoxLayout(buttons, javax.swing.BoxLayout.X_AXIS));
        summary.setText(" ");
        buttons.add(summary);

        buttons.add(buttonsGlue);

        openOutButton.setText("Open output folder");
        openOutButton.addActionListener(this::openOutButtonActionPerformed);
        buttons.add(openOutButton);

        buttons.add(buttonsGap1);

        optionsButton.setText("Options...");
        optionsButton.addActionListener(this::optionsButtonActionPerformed);
        buttons.add(optionsButton);

        buttons.add(buttonsGap2);

        createButton.setText("Create setup");
        createButton.addActionListener(this::createButtonActionPerformed);
        buttons.add(createButton);

        buttons.add(buttonsGap3);

        uploadButton.setText("Upload...");
        uploadButton.addActionListener(this::uploadButtonActionPerformed);
        buttons.add(uploadButton);

        south.add(buttons, java.awt.BorderLayout.SOUTH);

        getContentPane().add(south, java.awt.BorderLayout.SOUTH);
    }// </editor-fold>//GEN-END:initComponents

    /** The file list of a tab: check box column, note column. */
    private static void configureTable(JTable table) {
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        TableColumn include = table.getColumnModel().getColumn(0);
        include.setMaxWidth(60);
        include.setMinWidth(60);
        include.setCellRenderer(new CheckRenderer(table.getDefaultRenderer(Boolean.class)));
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setMaxWidth(160);
        table.getColumnModel().getColumn(3).setMaxWidth(90);
        table.getColumnModel().getColumn(4).setMaxWidth(70);
        table.getColumnModel().getColumn(5).setPreferredWidth(220);
        table.getColumnModel().getColumn(5).setCellRenderer(new NoteRenderer());
    }

    private void browseButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_browseButtonActionPerformed
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose the folder holding your application jar (usually dist)");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String current = buildField.getText().trim();
        if (!current.isEmpty()) {
            File f = new File(current);
            fc.setCurrentDirectory(f.isDirectory() ? f : f.getParentFile());
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            buildField.setText(fc.getSelectedFile().getAbsolutePath());
            buildFolderChosen(fc.getSelectedFile().toPath());
        }
    }//GEN-LAST:event_browseButtonActionPerformed

    private void appCheckAllActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_appCheckAllActionPerformed
        appFiles.checkAll();
    }//GEN-LAST:event_appCheckAllActionPerformed

    private void appUncheckAllActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_appUncheckAllActionPerformed
        appFiles.uncheckAll();
    }//GEN-LAST:event_appUncheckAllActionPerformed

    private void setBrowseButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_setBrowseButtonActionPerformed
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose the client's folder (laid out like the application folder: lib, Forms, ...)");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        Path start = setDir != null ? setDir : lastBuildDir;
        if (start != null && Files.isDirectory(start)) {
            fc.setCurrentDirectory(start.toFile());
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setFolderChosen(fc.getSelectedFile().toPath());
        }
    }//GEN-LAST:event_setBrowseButtonActionPerformed

    private void setUrlFieldFocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_setUrlFieldFocusLost
        setUrlChanged(true);
    }//GEN-LAST:event_setUrlFieldFocusLost

    private void setCheckAllActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_setCheckAllActionPerformed
        setFiles.checkAll();
    }//GEN-LAST:event_setCheckAllActionPerformed

    private void setUncheckAllActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_setUncheckAllActionPerformed
        setFiles.uncheckAll();
    }//GEN-LAST:event_setUncheckAllActionPerformed

    private void openOutButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openOutButtonActionPerformed
        openFolder(outDir(currentSide()));
    }//GEN-LAST:event_openOutButtonActionPerformed

    private void optionsButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_optionsButtonActionPerformed
        showOptions();
    }//GEN-LAST:event_optionsButtonActionPerformed

    private void createButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_createButtonActionPerformed
        createSetup(currentSide());
    }//GEN-LAST:event_createButtonActionPerformed

    private void uploadButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_uploadButtonActionPerformed
        Path out = outDir(currentSide());
        if (out != null) {
            UploadDialog.open(this, out, prefsFile());
        }
    }//GEN-LAST:event_uploadButtonActionPerformed

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
            appScan = ReleaseBuilder.scan(dir);
            if (otherProject) {
                appFiles.clear();
                setFiles.clear();
                setDir = null;
                setFolderField.setText("");
            }
            refreshAppFiles();
            append("Scanned " + dir + ": " + appFiles.size() + " files");

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
                    appFiles.setTicked(ex, false);
                }
                append("Read settings from " + config);
            } else if (otherProject) {
                append("No app.txt for this project yet: fill in the fields of both tabs.");
            }
            if (mainClassField.getText().trim().isEmpty()) {
                for (ReleaseBuilder.Candidate c : appFiles.rows()) {
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
            if (otherProject) {
                List<String> clients = clientFolders();
                if (clients.size() == 1) {
                    setFolderChosen(dir.resolve(clients.get(0)));
                }
            }
        } catch (IOException e) {
            error("Could not read that folder", e);
        }
    }

    /** The client folders inside the build folder (relative), each with its set.txt. */
    private List<String> clientFolders() {
        List<String> out = new ArrayList<String>();
        for (ReleaseBuilder.Candidate c : appScan) {
            if (c.clientFolder != null && !out.contains(c.clientFolder)) {
                out.add(c.clientFolder);
            }
        }
        return out;
    }

    /**
     * Fills the Application tab from the last scan: client folders are left out, and so is
     * every file a client folder also has (the client's set puts its own copy there).
     */
    private void refreshAppFiles() throws IOException {
        String chosen = null;
        if (setDir != null && lastBuildDir != null) {
            Path build = lastBuildDir.toAbsolutePath().normalize();
            Path set = setDir.toAbsolutePath().normalize();
            if (set.startsWith(build) && !set.equals(build)) {
                chosen = build.relativize(set).toString().replace('\\', '/');
            }
        }
        List<ReleaseBuilder.Candidate> rows = new ArrayList<ReleaseBuilder.Candidate>();
        for (ReleaseBuilder.Candidate c : appScan) {
            if (c.clientFolder == null && (chosen == null || !c.path.startsWith(chosen + "/"))) {
                rows.add(c);
            }
        }
        Map<String, String> claimed = new HashMap<String, String>();
        if (setDir != null) {
            for (ReleaseBuilder.Candidate c : setFiles.rows()) {
                if (!c.skipByDefault) {
                    claimed.put(c.path, "left out: in client folder " + setDir.getFileName());
                }
            }
        }
        for (String folder : clientFolders()) {
            for (ReleaseBuilder.Candidate c : ReleaseBuilder.scan(lastBuildDir.resolve(folder))) {
                if (!c.skipByDefault && !claimed.containsKey(c.path)) {
                    claimed.put(c.path, "left out: in client folder " + folder);
                }
            }
        }
        appFiles.setRows(rows);
        appFiles.setClaimed(claimed);
    }

    /** Shows a client folder in the Set tab, with the settings in its set.txt. */
    private void setFolderChosen(Path dir) {
        if (lastBuildDir != null && lastBuildDir.toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize())) {
            warn("That is the build folder. Choose the client's own folder, for example "
                    + lastBuildDir.resolve("stg") + ".");
            return;
        }
        try {
            boolean other = setDir == null
                    || !setDir.toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize());
            setDir = dir;
            setFolderField.setText(dir.toAbsolutePath().toString());
            if (other) {
                setFiles.clear();
            }
            setFiles.setRows(ReleaseBuilder.scan(dir));
            if (other) {
                SetConfig sc = SetConfig.read(dir.resolve(ReleaseBuilder.CLIENT_FOLDER_FILE));
                if (sc == null && lastBuildDir != null) {
                    // No set.txt yet: take the set lines an older publisher kept in app.txt,
                    // if they were for this client.
                    Path config = findConfig(lastBuildDir);
                    SetConfig old = config == null ? null : SetConfig.readLegacy(config);
                    if (old != null && clientOf(old.url).equalsIgnoreCase(dir.getFileName().toString())) {
                        sc = old;
                    }
                }
                setUrlField.setText(sc == null || sc.url == null ? "" : sc.url);
                setNameField.setText(sc == null || sc.name == null ? "" : sc.name);
                if (sc != null) {
                    for (String ex : sc.exclude) {
                        setFiles.setTicked(ex, false);
                    }
                }
                setNotesArea.setText("");
                setClientShown = "";
            }
            refreshAppFiles();
            append("Client folder " + dir + ": " + setFiles.size() + " files");
            setUrlChanged(true);
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
        o.outDir = outDir(side);
        if (side == APP) {
            o.buildDir = Paths.get(buildField.getText().trim());
            // Scan again: a set build may have added a client folder's set.txt since.
            try {
                appScan = ReleaseBuilder.scan(o.buildDir);
                refreshAppFiles();
            } catch (IOException e) {
                error("Could not read the build folder", e);
                return;
            }
            // Everything scanned that the application does not publish: unticked files,
            // files a client folder also has, and the client folders themselves.
            o.exclude = new LinkedHashSet<String>();
            for (ReleaseBuilder.Candidate c : appScan) {
                o.exclude.add(c.path);
            }
            o.exclude.removeAll(appFiles.published());
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
            if (setDir == null) {
                warn("Choose the client's folder in the Set tab first.");
                return;
            }
            o.buildDir = setDir;
            o.exclude = new LinkedHashSet<String>(setFiles.notPublished());
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
        if (filesOf(side).count() == 0) {
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
        final SetConfig setForConfig = side == SET ? currentSetConfig() : null;
        final Path setFolder = setDir;
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
                for (Path f : saveProjectConfig(appForConfig)) {
                    publish("saved settings to " + f);
                }
                if (setForConfig != null) {
                    Path f = setFolder.resolve(ReleaseBuilder.CLIENT_FOLDER_FILE);
                    Io.writeStringAtomic(f, setForConfig.toText());
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
        // Every file of the Application tab it does not carry (client folders are left out
        // by ManifestTool itself), so the command line builds the same release.
        a.exclude = new LinkedHashSet<String>(appFiles.notPublished());
        return a;
    }

    private SetConfig currentSetConfig() {
        SetConfig s = new SetConfig();
        s.url = setUrlField.getText().trim();
        s.name = setNameField.getText().trim();
        s.exclude.addAll(setFiles.notPublished());
        return s;
    }

    /**
     * Writes the project's app.txt (in dist, and next to build.xml so it survives Clean and
     * Build): the application's settings and its unticked files. A set's settings are in its
     * client folder (set.txt).
     */
    private List<Path> saveProjectConfig(ReleaseBuilder.Options app) throws IOException {
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
            written.add(f);
        }
        return written;
    }

    /** A client folder's settings, in its set.txt (never published). */
    static final class SetConfig {
        String url;
        String name;
        final List<String> exclude = new ArrayList<String>();

        String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append("# This client's set, for the Release publisher (Set tab). Not published.\n");
            sb.append("# Every other file here is installed at the same place in the application folder.\n");
            if (url != null && !url.isEmpty()) {
                sb.append("appbase = ").append(url).append('\n');
            }
            if (name != null && !name.isEmpty()) {
                sb.append("ui.name = ").append(name).append('\n');
            }
            for (String f : exclude) {
                sb.append("exclude = ").append(f).append('\n');
            }
            return sb.toString();
        }

        /** The settings in a set.txt, or null when there is none. */
        static SetConfig read(Path file) {
            String text = Io.readString(file);
            if (text == null) {
                return null;
            }
            SetConfig c = new SetConfig();
            for (String raw : text.split("\n")) {
                String s = raw.trim();
                if (s.startsWith("#") || s.indexOf('=') < 0) {
                    continue;
                }
                String key = s.substring(0, s.indexOf('=')).trim();
                String value = s.substring(s.indexOf('=') + 1).trim();
                if ("appbase".equals(key)) {
                    c.url = value;
                } else if ("ui.name".equals(key)) {
                    c.name = value;
                } else if ("exclude".equals(key) && !value.isEmpty()) {
                    c.exclude.add(value);
                }
            }
            return c;
        }

        /** The "# set.appbase / # set.name" lines older publishers wrote into app.txt, or null. */
        static SetConfig readLegacy(Path appTxt) {
            String text = Io.readString(appTxt);
            if (text == null) {
                return null;
            }
            SetConfig c = new SetConfig();
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
                }
            }
            return c.url == null ? null : c;
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
        summary.setText(label + ": " + filesOf(side).count() + " files, "
                + UpdateManager.humanBytes(filesOf(side).bytes()));
    }

    private Files2 filesOf(int side) {
        return side == SET ? setFiles : appFiles;
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
            String sf = p.getProperty("setFolder", "");
            if (!sf.isEmpty() && Files.isDirectory(Paths.get(sf))) {
                setFolderChosen(Paths.get(sf));
            }
        }
    }

    private void savePrefs() {
        Properties p = Io.loadProps(prefsFile()); // keeps the upload settings stored there
        p.setProperty("build", buildField.getText().trim());
        p.setProperty("setFolder", setDir == null ? "" : setDir.toAbsolutePath().toString());
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
     * One tab's files: the scanned files, which are ticked, and which are left out because
     * the other release carries them (a client folder's copy wins over the application's).
     */
    static final class Files2 {
        final int side;
        private final List<ReleaseBuilder.Candidate> rows = new ArrayList<ReleaseBuilder.Candidate>();
        private final List<Boolean> ticked = new ArrayList<Boolean>();
        private Map<String, String> claimed = new HashMap<String, String>();
        private final List<Runnable> listeners = new ArrayList<Runnable>();

        Files2(int side) {
            this.side = side;
        }

        void addListener(Runnable r) {
            listeners.add(r);
        }

        private void changed() {
            for (Runnable r : listeners) {
                r.run();
            }
        }

        void clear() {
            rows.clear();
            ticked.clear();
            claimed = new HashMap<String, String>();
            changed();
        }

        /** A new scan: files listed before keep their tick, new ones follow skipByDefault. */
        void setRows(List<ReleaseBuilder.Candidate> list) {
            Map<String, Boolean> before = new HashMap<String, Boolean>();
            for (int i = 0; i < rows.size(); i++) {
                before.put(rows.get(i).path, ticked.get(i));
            }
            rows.clear();
            ticked.clear();
            for (ReleaseBuilder.Candidate c : list) {
                rows.add(c);
                Boolean b = before.get(c.path);
                ticked.add(b != null ? b : Boolean.valueOf(!c.skipByDefault));
            }
            changed();
        }

        /** Paths the other release carries, with the reason shown in the Note column. */
        void setClaimed(Map<String, String> paths) {
            claimed = paths;
            changed();
        }

        List<ReleaseBuilder.Candidate> rows() {
            return rows;
        }

        int size() {
            return rows.size();
        }

        String claimedAt(int i) {
            return claimed.get(rows.get(i).path);
        }

        boolean publishedAt(int i) {
            return ticked.get(i).booleanValue() && claimedAt(i) == null;
        }

        void setTickedAt(int i, boolean on) {
            ticked.set(i, Boolean.valueOf(on));
            changed();
        }

        void setTicked(String path, boolean on) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).path.equals(path)) {
                    ticked.set(i, Boolean.valueOf(on));
                }
            }
            changed();
        }

        /** Ticks every file except build junk. */
        void checkAll() {
            for (int i = 0; i < rows.size(); i++) {
                if (!rows.get(i).skipByDefault) {
                    ticked.set(i, Boolean.TRUE);
                }
            }
            changed();
        }

        void uncheckAll() {
            for (int i = 0; i < rows.size(); i++) {
                ticked.set(i, Boolean.FALSE);
            }
            changed();
        }

        List<String> published() {
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < rows.size(); i++) {
                if (publishedAt(i)) {
                    out.add(rows.get(i).path);
                }
            }
            return out;
        }

        Set<String> notPublished() {
            Set<String> out = new LinkedHashSet<String>();
            for (int i = 0; i < rows.size(); i++) {
                if (!publishedAt(i)) {
                    out.add(rows.get(i).path);
                }
            }
            return out;
        }

        int count() {
            return published().size();
        }

        long bytes() {
            long total = 0;
            for (int i = 0; i < rows.size(); i++) {
                total += publishedAt(i) ? rows.get(i).size : 0;
            }
            return total;
        }
    }

    /** One tab's file list. */
    private static final class FileTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"Publish", "File", "Location", "Size", "Type", "Note"};

        final Files2 files;

        FileTableModel(Files2 files) {
            this.files = files;
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
            return column == 0 && files.claimedAt(row) == null;
        }

        @Override
        public Object getValueAt(int row, int column) {
            ReleaseBuilder.Candidate c = files.rows().get(row);
            switch (column) {
                case 0:
                    return Boolean.valueOf(files.publishedAt(row));
                case 1:
                    return c.path;
                case 2:
                    // where it is installed, relative to the application folder
                    return c.path.indexOf('/') < 0 ? "(root)" : c.path.substring(0, c.path.lastIndexOf('/'));
                case 3:
                    return UpdateManager.humanBytes(c.size);
                case 4:
                    return c.classpath ? "code" : "file";
                default:
                    if (files.claimedAt(row) != null) {
                        return files.claimedAt(row);
                    }
                    if (files.side == SET && files.publishedAt(row) && !c.classpath) {
                        return "customer copy kept";
                    }
                    return c.note == null ? "" : c.note;
            }
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0 && isCellEditable(row, column)) {
                files.setTickedAt(row, Boolean.TRUE.equals(value));
            }
        }
    }

    /** The tick box, greyed out for a file the other release carries. */
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
                if (v.startsWith("left out")) {
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

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JTextField appBaseField;
    private javax.swing.JLabel appBaseLabel;
    private javax.swing.JLabel appBuildLabel;
    private javax.swing.JButton appCheckAll;
    private javax.swing.JPanel appForm;
    private javax.swing.JPanel appHead;
    private javax.swing.JLabel appLastLabel;
    private javax.swing.JPanel appList;
    private javax.swing.JPanel appListButtons;
    private javax.swing.JLabel appListLabel;
    private javax.swing.JPanel appPanel;
    private javax.swing.JTable appTable;
    private javax.swing.JScrollPane appTableScroll;
    private javax.swing.JButton appUncheckAll;
    private javax.swing.Box.Filler appVersionGap;
    private javax.swing.Box.Filler appVersionGlue;
    private javax.swing.JPanel appVersionPanel;
    private javax.swing.JButton browseButton;
    private javax.swing.JTextField buildField;
    private javax.swing.JPanel buttons;
    private javax.swing.Box.Filler buttonsGap1;
    private javax.swing.Box.Filler buttonsGap2;
    private javax.swing.Box.Filler buttonsGap3;
    private javax.swing.Box.Filler buttonsGlue;
    private javax.swing.JButton createButton;
    private javax.swing.JTextField jvmArgsField;
    private javax.swing.JLabel jvmArgsLabel;
    private javax.swing.JTextArea log;
    private javax.swing.JPanel logPanel;
    private javax.swing.JScrollPane logScroll;
    private javax.swing.JTextField mainClassField;
    private javax.swing.JLabel mainClassLabel;
    private javax.swing.JTextArea notesArea;
    private javax.swing.JLabel notesLabel;
    private javax.swing.JScrollPane notesScroll;
    private javax.swing.JButton openOutButton;
    private javax.swing.JButton optionsButton;
    private javax.swing.JProgressBar progress;
    private javax.swing.JPanel progressPanel;
    private javax.swing.JButton setBrowseButton;
    private javax.swing.JButton setCheckAll;
    private javax.swing.JTextField setFolderField;
    private javax.swing.JLabel setFolderLabel;
    private javax.swing.JPanel setForm;
    private javax.swing.JPanel setHead;
    private javax.swing.JLabel setLastLabel;
    private javax.swing.JPanel setList;
    private javax.swing.JPanel setListButtons;
    private javax.swing.JLabel setListLabel;
    private javax.swing.JTextField setNameField;
    private javax.swing.JLabel setNameLabel;
    private javax.swing.JTextArea setNotesArea;
    private javax.swing.JLabel setNotesLabel;
    private javax.swing.JScrollPane setNotesScroll;
    private javax.swing.JPanel setPanel;
    private javax.swing.JTable setTable;
    private javax.swing.JScrollPane setTableScroll;
    private javax.swing.JButton setUncheckAll;
    private javax.swing.JTextField setUrlField;
    private javax.swing.JLabel setUrlLabel;
    private javax.swing.JTextField setVersionField;
    private javax.swing.Box.Filler setVersionGap;
    private javax.swing.Box.Filler setVersionGlue;
    private javax.swing.JLabel setVersionLabel;
    private javax.swing.JPanel setVersionPanel;
    private javax.swing.JPanel south;
    private javax.swing.JSplitPane split;
    private javax.swing.JLabel summary;
    private javax.swing.JTabbedPane tabs;
    private javax.swing.JTextField uiNameField;
    private javax.swing.JLabel uiNameLabel;
    private javax.swing.JButton uploadButton;
    private javax.swing.JTextField versionField;
    private javax.swing.JLabel versionLabel;
    // End of variables declaration//GEN-END:variables
}
