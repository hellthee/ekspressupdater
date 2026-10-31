package org.hp.update;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * The window shown while the application starts.
 *
 * <p>Quiet by default: a line of plain text and a progress bar, and it disappears as soon
 * as the application is running. The technical detail is one click away under
 * <b>Details</b>, and appears by itself if anything fails, in which case the window stays
 * put with a button to copy it all.</p>
 *
 * <p>While an update is downloading there is a <b>Skip and start now</b> button: a slow
 * server should never stand between the user and the version they already have.</p>
 */
public final class LauncherWindow extends JFrame implements LauncherUi {

    private static final long serialVersionUID = 1L;

    private final JLabel title = new JLabel();
    private final JLabel stage = new JLabel("Please wait...");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JTextArea details = new JTextArea();
    private final JScrollPane detailScroll = new JScrollPane(details);
    private final JButton detailsButton = new JButton("Details");
    private final JButton skipButton = new JButton("Skip and start now");
    private final JButton copyButton = new JButton("Copy details");
    private final JButton logButton = new JButton("Open log folder");
    private final JButton closeButton = new JButton("Close");
    private final JButton retryButton = new JButton("Retry");
    private final java.util.concurrent.SynchronousQueue<Boolean> choice =
            new java.util.concurrent.SynchronousQueue<Boolean>();
    private volatile boolean asking;
    private volatile Runnable userClose;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final UpdateConfig cfg;
    private volatile boolean failed;
    private volatile Runnable skipAction;

    private static final Dimension SMALL = new Dimension(520, 190);
    private static final Dimension LARGE = new Dimension(640, 460);

    private LauncherWindow(UpdateConfig cfg) {
        super(cfg.displayName());
        this.cfg = cfg;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        // Text from the server or the application is shown as text, never as HTML.
        title.putClientProperty("html.disable", Boolean.TRUE);
        stage.putClientProperty("html.disable", Boolean.TRUE);
        title.setText(cfg.displayName());
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 4f));
        stage.setForeground(new Color(0x44, 0x44, 0x44));
        bar.setStringPainted(true);
        bar.setIndeterminate(true);
        bar.setString(" ");

        details.setEditable(false);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        detailScroll.setVisible(false);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        title.setAlignmentX(LEFT_ALIGNMENT);
        stage.setAlignmentX(LEFT_ALIGNMENT);
        bar.setAlignmentX(LEFT_ALIGNMENT);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        top.add(title);
        top.add(Box.createVerticalStrut(6));
        top.add(stage);
        top.add(Box.createVerticalStrut(10));
        top.add(bar);

        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
        center.add(detailScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 16, 12, 16));
        buttons.add(detailsButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(skipButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(copyButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(logButton);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(retryButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(closeButton);
        retryButton.setVisible(false);
        skipButton.setVisible(false);
        copyButton.setVisible(false);
        logButton.setVisible(false);

        detailsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showDetails(!detailScroll.isVisible());
            }
        });
        skipButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                skipButton.setEnabled(false);
                stage.setText("Skipping the update, starting the installed version...");
                Runnable r = skipAction;
                if (r != null) {
                    new Thread(r, "skip-update").start();
                }
            }
        });
        copyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(details.getText()), null);
                copyButton.setText("Copied");
            }
        });
        logButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    java.awt.Desktop.getDesktop().open(
                            Relauncher.appLog(LauncherWindow.this.cfg).getParent().toFile());
                } catch (Exception ignored) {
                    // not every desktop can open a folder for us
                }
            }
        });
        closeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (asking) {
                    answer(false);
                }
                dismiss();
            }
        });
        retryButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                answer(true);
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (asking) {
                    answer(false);
                }
                dismiss();
            }
        });

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        setPreferredSize(SMALL);
        pack();
        setLocationRelativeTo(null);
    }

    public static LauncherWindow show(final UpdateConfig cfg) {
        final LauncherWindow[] holder = new LauncherWindow[1];
        Runnable build = new Runnable() {
            @Override
            public void run() {
                holder[0] = new LauncherWindow(cfg);
                holder[0].setVisible(true);
                holder[0].bringToFront();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            build.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(build);
            } catch (Exception e) {
                return null;
            }
        }
        return holder[0];
    }

    /**
     * On macOS the launcher has no Dock icon (see Bootstrap), and a window of such an app
     * can open behind whatever is in front. Briefly keeping it on top puts it in view.
     */
    private void bringToFront() {
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            setAlwaysOnTop(true);
            toFront();
            setAlwaysOnTop(false);
        } else {
            toFront();
        }
    }

    private void showDetails(boolean visible) {
        detailScroll.setVisible(visible);
        detailsButton.setText(visible ? "Hide details" : "Details");
        setSize(visible ? LARGE : SMALL);
        validate();
        repaint();
    }

    private void dismiss() {
        Runnable quit = userClose;
        boolean busy = !failed && !asking && quit != null && isVisible();
        closed.countDown();
        dispose();
        if (busy) {
            quit.run(); // closed in the middle of an update: the user wants out
        }
    }

    @Override
    public void onUserClose(Runnable action) {
        this.userClose = action;
    }

    // ------------------------------------------------------------------ LauncherUi

    @Override
    public void stage(final String text) {
        onEdt(new Runnable() {
            @Override
            public void run() {
                stage.setText(text);
            }
        });
        detail("== " + text);
    }

    /** Lines kept for the details pane; older ones are dropped (app.log has everything). */
    private static final int MAX_LINES = 2000;
    private final java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<String>();
    private boolean flushScheduled;
    private int shownLines;

    /**
     * Lines arrive from the application's output, possibly thousands a second, for as long
     * as it runs. They are queued and added to the text in batches, and only the last
     * {@value #MAX_LINES} are kept, so the launcher stays small and idle however long the
     * application runs. While the window is hidden nothing is drawn at all.
     */
    @Override
    public void detail(final String line) {
        synchronized (pending) {
            pending.addLast(line);
            while (pending.size() > MAX_LINES) {
                pending.removeFirst();
            }
            if (flushScheduled || !isVisible() && !failed) {
                return;
            }
            flushScheduled = true;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                flushDetails();
            }
        });
    }

    private void flushDetails() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        synchronized (pending) {
            flushScheduled = false;
            for (String l : pending) {
                sb.append(l).append('\n');
                n++;
            }
            pending.clear();
        }
        if (n == 0) {
            return;
        }
        details.append(sb.toString());
        shownLines += n;
        if (shownLines > MAX_LINES) {
            try {
                javax.swing.text.Element root = details.getDocument().getDefaultRootElement();
                int drop = shownLines - MAX_LINES;
                int end = root.getElement(Math.min(drop, root.getElementCount() - 1)).getStartOffset();
                details.getDocument().remove(0, end);
                shownLines -= drop;
            } catch (javax.swing.text.BadLocationException ignored) {
                // keep what is there
            }
        }
        details.setCaretPosition(details.getDocument().getLength());
    }

    @Override
    public void progress(final long done, final long total, final String currentFile) {
        onEdt(new Runnable() {
            @Override
            public void run() {
                if (total > 0) {
                    bar.setIndeterminate(false);
                    bar.setValue((int) Math.min(1000L, done * 1000L / total));
                    bar.setString(UpdateManager.humanBytes(done) + " of "
                            + UpdateManager.humanBytes(total));
                } else {
                    bar.setIndeterminate(true);
                }
            }
        });
    }

    /** Offers the user a way out of a slow download. */
    @Override
    public void offerSkip(final Runnable action) {
        this.skipAction = action;
        onEdt(new Runnable() {
            @Override
            public void run() {
                skipButton.setEnabled(true);
                skipButton.setVisible(true);
                validate();
            }
        });
    }

    @Override
    public void hideSkip() {
        this.skipAction = null;
        onEdt(new Runnable() {
            @Override
            public void run() {
                skipButton.setVisible(false);
                validate();
            }
        });
    }

    @Override
    public void appStarted() {
        userClose = null;
        onEdt(new Runnable() {
            @Override
            public void run() {
                if (!failed) {
                    setVisible(false);
                }
            }
        });
    }

    @Override
    public void appRunning(final String name) {
        detail("== " + name + " is running");
        onEdt(new Runnable() {
            @Override
            public void run() {
                stage.setText(name + " is running.");
                bar.setIndeterminate(false);
                bar.setValue(bar.getMaximum());
                bar.setString("done");
            }
        });
    }

    @Override
    public void failed(final String headline, final String body) {
        failed = true;
        detail("");
        detail("---------------- PROBLEM ----------------");
        detail(headline);
        if (body != null && !body.isEmpty()) {
            detail(body);
        }
        onEdt(new Runnable() {
            @Override
            public void run() {
                setVisible(true);
                flushDetails();
                setTitle(cfg.displayName() + " - problem");
                title.setForeground(new Color(0xB0, 0x00, 0x20));
                title.setText(headline);
                stage.setText("Details below. Copy them if you need to report this.");
                bar.setIndeterminate(false);
                bar.setValue(0);
                bar.setString("stopped");
                skipButton.setVisible(false);
                copyButton.setVisible(true);
                logButton.setVisible(true);
                showDetails(true);
                bringToFront();
                requestFocus();
            }
        });
    }

    @Override
    public boolean askRetry(String headline, String body) {
        asking = true;
        failed(headline, body);
        onEdt(new Runnable() {
            @Override
            public void run() {
                retryButton.setVisible(true);
                retryButton.requestFocusInWindow();
                validate();
            }
        });
        boolean again;
        try {
            again = choice.take().booleanValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            again = false;
        }
        asking = false;
        if (again) {
            failed = false;
            onEdt(new Runnable() {
                @Override
                public void run() {
                    setTitle(cfg.displayName());
                    title.setForeground(UIManagerColor.label());
                    title.setText(cfg.displayName());
                    stage.setText("Trying again...");
                    bar.setIndeterminate(true);
                    bar.setString(" ");
                    retryButton.setVisible(false);
                    copyButton.setVisible(false);
                    logButton.setVisible(false);
                    validate();
                }
            });
            detail("");
            detail("== Retrying");
        }
        return again;
    }

    private void answer(boolean value) {
        choice.offer(Boolean.valueOf(value));
    }

    /** The look and feel's normal label colour, to undo the red of a failure. */
    private static final class UIManagerColor {
        static Color label() {
            Color c = javax.swing.UIManager.getColor("Label.foreground");
            return c != null ? c : Color.BLACK;
        }
    }

    @Override
    public void awaitClose() {
        try {
            closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (!failed) {
            userClose = null; // the launcher closing it is not the user quitting
            dismiss();
        }
    }

    public boolean hasFailed() {
        return failed;
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }
}
