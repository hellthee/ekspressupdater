package org.hp.update;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
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
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;

/**
 * Asks a user who has the application open to restart for a new release.
 *
 * <p>It does not close the application by itself: someone may be half way through an
 * invoice. With mandatory updates "Later" only puts the question off until the next
 * check.</p>
 */
final class RestartNotice extends JDialog {

    private static final long serialVersionUID = 1L;

    private RestartNotice(Window owner, UpdateConfig cfg, UpdateManifest remote,
            final Runnable restart, final Runnable dismissed) {
        super(owner, cfg.displayName() + " update", ModalityType.MODELESS);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JLabel headline = new JLabel(cfg.displayName() + " " + remote.version()
                + (cfg.mandatory() ? " is required" : " is available"));
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize2D() + 2f));
        JLabel line = new JLabel(cfg.mandatory()
                ? "Save your work, then restart to install it."
                : "Restart when convenient to install it.");
        headline.putClientProperty("html.disable", Boolean.TRUE);
        line.putClientProperty("html.disable", Boolean.TRUE);
        line.setForeground(new Color(0x44, 0x44, 0x44));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        headline.setAlignmentX(LEFT_ALIGNMENT);
        line.setAlignmentX(LEFT_ALIGNMENT);
        top.add(headline);
        top.add(Box.createVerticalStrut(6));
        top.add(line);

        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
        if (!remote.notes().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String n : remote.notes()) {
                sb.append(n).append('\n');
            }
            JTextArea notes = new JTextArea(sb.toString().trim());
            notes.setEditable(false);
            notes.setLineWrap(true);
            notes.setWrapStyleWord(true);
            notes.setRows(Math.min(6, remote.notes().size() + 1));
            notes.setBackground(getBackground());
            JScrollPane sp = new JScrollPane(notes);
            sp.setBorder(BorderFactory.createTitledBorder("What's new"));
            center.add(sp, BorderLayout.CENTER);
        }

        JButton later = new JButton("Later");
        JButton now = new JButton("Restart now");
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 16, 12, 16));
        buttons.add(Box.createHorizontalGlue());
        buttons.add(later);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(now);

        now.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
                restart.run();
            }
        });
        later.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                dismissed.run();
            }
        });

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setSize(new Dimension(Math.max(420, getWidth()), getHeight()));
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(now);
    }

    static void show(Window owner, UpdateConfig cfg, UpdateManifest remote,
            Runnable restart, Runnable dismissed) {
        RestartNotice n = new RestartNotice(owner, cfg, remote, restart, dismissed);
        n.setVisible(true);
        n.toFront();
    }
}
