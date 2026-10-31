package org.hp.update;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Plain Swing progress dialog. No look and feel assumptions, no external libraries.
 *
 * <pre>
 * UpdateManager.Result r = UpdateDialog.runModal(mainFrame, mgr, "Software update");
 * if (r.restartRequired()) { ... }
 * </pre>
 */
public final class UpdateDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final JLabel status = new JLabel("Contacting the update server...");
    private final JLabel detail = new JLabel(" ");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JTextArea log = new JTextArea(8, 48);
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton detailsButton = new JButton("Details");
    private final JScrollPane logScroll = new JScrollPane(log);

    private UpdateDialog(Window owner, String title, final UpdateManager mgr) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        bar.setStringPainted(true);
        bar.setIndeterminate(true);
        log.setEditable(false);
        log.setLineWrap(true);
        logScroll.setVisible(false);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        status.setAlignmentX(LEFT_ALIGNMENT);
        bar.setAlignmentX(LEFT_ALIGNMENT);
        detail.setAlignmentX(LEFT_ALIGNMENT);
        top.add(status);
        top.add(Box.createVerticalStrut(8));
        top.add(bar);
        top.add(Box.createVerticalStrut(6));
        top.add(detail);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 16, 12, 16));
        buttons.add(detailsButton);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(cancelButton);

        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
        center.add(logScroll, BorderLayout.CENTER);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        cancelButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancelButton.setEnabled(false);
                status.setText("Cancelling...");
                mgr.cancel();
            }
        });
        detailsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                logScroll.setVisible(!logScroll.isVisible());
                setSize(getWidth(), logScroll.isVisible() ? 400 : 190);
                validate();
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                mgr.cancel();
            }
        });

        setPreferredSize(new Dimension(480, 190));
        pack();
        setLocationRelativeTo(owner);
    }

    /** Listener that drives this dialog. Attach it to an UpdateManager. */
    public UpdateListener listener() {
        return new UpdateListener() {
            @Override
            public void onPhase(final String phase) {
                onEdt(new Runnable() {
                    @Override
                    public void run() {
                        if ("CHECKING".equals(phase)) {
                            status.setText("Checking for updates...");
                            bar.setIndeterminate(true);
                        } else if ("DOWNLOADING".equals(phase)) {
                            status.setText("Downloading update...");
                        } else if ("VERIFYING".equals(phase)) {
                            status.setText("Verifying files...");
                            bar.setIndeterminate(true);
                        } else if ("ACTIVATING".equals(phase)) {
                            status.setText("Installing...");
                            bar.setIndeterminate(true);
                        } else if ("DONE".equals(phase)) {
                            status.setText("Finished");
                            bar.setIndeterminate(false);
                            bar.setValue(bar.getMaximum());
                        }
                    }
                });
            }

            @Override
            public void onProgress(final long done, final long total, final String file) {
                onEdt(new Runnable() {
                    @Override
                    public void run() {
                        bar.setIndeterminate(total <= 0);
                        if (total > 0) {
                            bar.setValue((int) Math.min(1000L, (done * 1000L) / total));
                            bar.setString(UpdateManager.humanBytes(done) + " of "
                                    + UpdateManager.humanBytes(total));
                        }
                        detail.setText(file == null ? " " : file);
                    }
                });
            }

            @Override
            public void onLog(String message) {
                append(message);
            }

            @Override
            public void onWarning(String message) {
                append("! " + message);
            }

            @Override
            public void onFailed(String message, Throwable cause) {
                append("FAILED " + message + (cause == null ? "" : " (" + cause + ")"));
            }
        };
    }

    private void append(final String line) {
        onEdt(new Runnable() {
            @Override
            public void run() {
                log.append(line + "\n");
                log.setCaretPosition(log.getDocument().getLength());
            }
        });
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /**
     * Shows the dialog, runs the update on a worker thread and returns the result once
     * it finishes. Safe to call from the EDT (menu item) or from a plain thread
     * (launcher).
     */
    public static UpdateManager.Result runModal(final Window owner, final UpdateManager mgr,
            final String title) {
        return runModal(owner, mgr, title, null);
    }

    /**
     * As {@link #runModal(Window, UpdateManager, String)}, installing a release already
     * fetched from the server (null: fetch it first).
     */
    static UpdateManager.Result runModal(final Window owner, final UpdateManager mgr,
            final String title, final UpdateManifest release) {

        final UpdateDialog[] holder = new UpdateDialog[1];
        Runnable build = new Runnable() {
            @Override
            public void run() {
                holder[0] = new UpdateDialog(owner, title, mgr);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            build.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(build);
            } catch (Exception e) {
                return work(mgr, release); // no UI available, still do the work
            }
        }
        final UpdateDialog dialog = holder[0];

        final UpdateListener previous = mgr.listener();
        mgr.setListener(UpdateListener.all(previous, dialog.listener()));

        final UpdateManager.Result[] result = new UpdateManager.Result[1];
        final Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = work(mgr, release);
                } finally {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            dialog.dispose();
                        }
                    });
                }
            }
        }, "updater");
        worker.setDaemon(true);
        // Started once the dialog is on screen, so the work can never finish (and close
        // the dialog) before it has been shown - which would leave a dialog nobody closes.
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                worker.start();
            }
        });
        Runnable show = new Runnable() {
            @Override
            public void run() {
                dialog.setVisible(true); // returns when the worker disposes it
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(show);
            } catch (Exception e) {
                return work(mgr, release);
            }
        }
        try {
            worker.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        mgr.setListener(previous);

        return result[0] != null ? result[0]
                : new UpdateManager.Result(UpdateManager.Status.CANCELLED, null, "Cancelled", null);
    }

    private static UpdateManager.Result work(UpdateManager mgr, UpdateManifest release) {
        return release == null ? mgr.update() : mgr.install(release);
    }

    /** Convenience for a "Check for updates" menu item. */
    public static UpdateManager.Result run(Window owner, UpdateConfig cfg, String title) {
        return runModal(owner, new UpdateManager(cfg, UpdateListener.NOOP), title);
    }
}
