package com.github.theprez.repotool;

import javax.swing.*;
import javax.swing.JLabel;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;

public class RpmRepoToolGuiUser extends JFrame {
    private JComboBox<String> cmbExistingZips;
    private JButton btnRefreshZips;
    private JButton btnServeSelectedZip;
    private JTextField txtPort;
    private JLabel lblHttpServerStatus;
    private JettyServer httpServer;
    private JCheckBox chkInstallRepos;
    private JLabel lblInstallTarget;

    public RpmRepoToolGuiUser() {
        super("RPM Repo Tool (User Mode)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 200);
        setLayout(new BorderLayout());

        // Serving Options section (same as full GUI)
        JPanel servingPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        servingPanel.setBorder(BorderFactory.createTitledBorder("Serving Options"));
        txtPort = new JTextField("9000", 6);
        lblHttpServerStatus = new JLabel("HTTP server: stopped");
        JButton btnStopHttpServer = new JButton("Stop server");
        btnStopHttpServer.setEnabled(true);
        btnStopHttpServer.addActionListener(e -> {
            if (httpServer != null) {
                try {
                    httpServer.stop();
                    httpServer = null;
                    updateHttpServerStatus("stopped");
                    btnStopHttpServer.setEnabled(false);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(RpmRepoToolGuiUser.this,
                        "Failed to stop server: " + ex.getMessage(),
                        "HTTP Server Error", JOptionPane.ERROR_MESSAGE);
                }
            }
            else {
                JOptionPane.showMessageDialog(RpmRepoToolGuiUser.this,
                        "HTTP server is already stopped.");
            }
        });
        chkInstallRepos = new JCheckBox("Install .repo files");
        lblInstallTarget = new JLabel("/QOpenSys/etc/yum/repos.d");
        servingPanel.add(new JLabel("Port:"));
        servingPanel.add(txtPort);
        servingPanel.add(Box.createHorizontalStrut(15));
        servingPanel.add(lblHttpServerStatus);
        servingPanel.add(Box.createHorizontalStrut(15));
        servingPanel.add(btnStopHttpServer);
        servingPanel.add(Box.createHorizontalStrut(65));
        servingPanel.add(chkInstallRepos);
        servingPanel.add(Box.createHorizontalStrut(15));
        servingPanel.add(new JLabel("Target:"));
        servingPanel.add(lblInstallTarget);

        // Section for existing repo zips dropdown
        JPanel zipPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        zipPanel.setBorder(BorderFactory.createTitledBorder("Serve Existing Repo"));
        cmbExistingZips = new JComboBox<>();
        btnRefreshZips = new JButton("⟳");
        btnRefreshZips.setMargin(new Insets(2, 6, 2, 6));
        btnRefreshZips.setToolTipText("Refresh list");
        btnRefreshZips.addActionListener(e -> refreshZipDropdown());
        refreshZipDropdown();
        zipPanel.add(new JLabel("Existing Repo Zips:"));
        zipPanel.add(cmbExistingZips);
        zipPanel.add(btnRefreshZips);
        btnServeSelectedZip = new JButton("Serve Selected Repo");
        btnServeSelectedZip.addActionListener(e -> serveSelectedZip());
        zipPanel.add(Box.createHorizontalStrut(35));
        zipPanel.add(btnServeSelectedZip);

        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
        southPanel.add(zipPanel);
        southPanel.add(Box.createVerticalStrut(8));
        southPanel.add(new JSeparator(SwingConstants.HORIZONTAL));
        southPanel.add(servingPanel);
        add(southPanel, BorderLayout.CENTER);

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Ignore
        }
        Font font = new Font("Arial", Font.PLAIN, 10);
        UIManager.put("Label.font", font);
        UIManager.put("Button.font", font);
        UIManager.put("TextField.font", font);

        setVisible(true);
    }

    private void updateHttpServerStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            lblHttpServerStatus.setText("HTTP server: " + status);
            // Enable/disable stop button based on status
            Container parent = lblHttpServerStatus.getParent();
            if (parent != null) {
                for (Component comp : parent.getComponents()) {
                    if (comp instanceof JButton && ((JButton) comp).getText().equals("Stop server")) {
                        ((JButton) comp).setEnabled(status.startsWith("running"));
                    }
                }
            }
        });
    }

    private void refreshZipDropdown() {
        cmbExistingZips.removeAllItems();
        File snapshotsDir = new File("snapshots");
        File[] zips = snapshotsDir.listFiles((dir, name) -> name.matches("\\d{8}_\\d{6}_repo\\.zip"));
        if (zips != null) {
            java.util.Arrays.sort(zips, java.util.Comparator.comparing(File::getName).reversed());
            for (File zip : zips) {
                cmbExistingZips.addItem(zip.getName());
            }
        }
    }

    private void serveSelectedZip() {
        String selectedZip = (String) cmbExistingZips.getSelectedItem();
        if (selectedZip == null || selectedZip.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No repo zip selected.", "Serve Repo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        btnServeSelectedZip.setEnabled(false);
        updateHttpServerStatus("starting...");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                try {
                    File snapshotsDir = new File("snapshots");
                    File zipFile = new File(snapshotsDir, selectedZip);
                    if (!zipFile.exists()) {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGuiUser.this,
                            "Selected zip not found in snapshots/", "No zip found", JOptionPane.WARNING_MESSAGE));
                        return null;
                    }
                    Path extractDir = snapshotsDir.toPath().resolve(selectedZip.replaceFirst("\\.zip$", ""));
                    if (!extractDir.toFile().exists()) {
                        extractDir.toFile().mkdirs();
                        com.github.theprez.repotool.RepoDownloader.unzip(zipFile.toPath(), snapshotsDir.toPath());
                    }
                    String hostForRepo = "localhost";
                    int portForRepo = parsePortOrDefault(txtPort.getText().trim(), 9000);
                    com.github.theprez.repotool.RepoUtils.generateRepoFiles(extractDir, hostForRepo, portForRepo);

                    if (chkInstallRepos.isSelected()) {
                        String targetFinal = "/QOpenSys/etc/yum/repos.d";
                        boolean systemTarget = "/QOpenSys/etc/yum/repos.d".equals(targetFinal) || targetFinal.startsWith("/etc/") || targetFinal.startsWith("/QOpenSys/etc/");
                        if (systemTarget && !com.github.theprez.repotool.RepoUtils.isRunningAsRoot()) {
                            int r = JOptionPane.showConfirmDialog(RpmRepoToolGuiUser.this,
                                "You are not running as root. Installing to " + targetFinal + " will likely fail. Continue?",
                                "Install without root?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                            if (r != JOptionPane.YES_OPTION) {
                                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGuiUser.this,
                                    "Install cancelled.", "Install", JOptionPane.INFORMATION_MESSAGE));
                            } else {
                                try {
                                    int installed = com.github.theprez.repotool.RepoUtils.installRepoFiles(extractDir, Path.of(targetFinal));
                                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGuiUser.this,
                                        "Installed " + installed + " .repo files to: " + targetFinal,
                                        "Install", JOptionPane.INFORMATION_MESSAGE));
                                } catch (Exception ex) {
                                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGuiUser.this,
                                        "Failed to install .repo files: " + ex.getMessage(),
                                        "Install Error", JOptionPane.ERROR_MESSAGE));
                                }
                            }
                        } else {
                            try {
                                int installed = com.github.theprez.repotool.RepoUtils.installRepoFiles(extractDir, Path.of(targetFinal));
                                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGuiUser.this,
                                    "Installed " + installed + " .repo files to: " + targetFinal,
                                    "Install", JOptionPane.INFORMATION_MESSAGE));
                            } catch (Exception ex) {
                                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGuiUser.this,
                                    "Failed to install .repo files: " + ex.getMessage(),
                                    "Install Error", JOptionPane.ERROR_MESSAGE));
                            }
                        }
                    }

                    int port = parsePortOrDefault(txtPort.getText().trim(), 9000);
                    if (httpServer != null) {
                        try { httpServer.stop(); } catch (Exception ignored) {}
                        httpServer = null;
                    }
                    httpServer = new JettyServer(port, extractDir);
                    new Thread(() -> {
                        try {
                            httpServer.start();
                            updateHttpServerStatus("running");
                        } catch (Exception ex) {
                            updateHttpServerStatus("stopped (error)");
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGuiUser.this,
                                "HTTP server failed to start: " + ex.getMessage(), "HTTP Server Error", JOptionPane.ERROR_MESSAGE));
                        }
                    }).start();
                } catch (Exception ex) {
                    updateHttpServerStatus("stopped (error)");
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGuiUser.this,
                        "Failed to unzip/serve: " + ex.getMessage(), "Unzip/Serve Error", JOptionPane.ERROR_MESSAGE));
                }
                return null;
            }
            @Override protected void done() {
                btnServeSelectedZip.setEnabled(true);
            }
        }.execute();
    }

    private int parsePortOrDefault(String s, int def) {
        try {
            int p = Integer.parseInt(s);
            return (p >= 1 && p <= 65535) ? p : def;
        } catch (Exception e) { return def; }
    }
}
