package com.github.theprez.repotool;
import javax.swing.table.AbstractTableModel;
import java.util.List;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class RpmRepoToolGui extends JFrame {

	// Button to serve selected zip
	private JButton btnServeSelectedZip;
	// Dropdown for existing repo zips
	private JComboBox<String> cmbExistingZips;
	private JButton btnRefreshZips;
	private RepoPickerPanel picker;
	private JButton btnZipSelected;
	private JButton btnUnzipAndServe;
	private JTextField txtPort;
	private JLabel lblHttpServerStatus;
	private JettyServer httpServer;
	private JCheckBox chkInstallRepos;
	private JLabel lblInstallTarget;

	// Defines the table and model used to display download progress
	private JTable tblProgress;
	private DownloadTableModel progressModel;

	public RpmRepoToolGui() {
		super("RPM Repo Tool");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setSize(900, 600);
		setLayout(new BorderLayout());
		picker = new RepoPickerPanel();
		add(picker, BorderLayout.CENTER);

		// Progress table setup
		progressModel = new DownloadTableModel();
		tblProgress = new JTable(progressModel);
		JScrollPane scrollPane = new JScrollPane(tblProgress);
		scrollPane.setPreferredSize(new Dimension(880, 120));
		add(scrollPane, BorderLayout.NORTH);
		// Set custom renderer for progress bar column (after table is created)
		tblProgress.getColumnModel().getColumn(4).setCellRenderer(new ProgressBarRenderer());

		btnZipSelected = new JButton("Zip Selected Repos");
		btnZipSelected.addActionListener(e -> zipSelectedRepos());

		btnUnzipAndServe = new JButton("Serve Downloaded Repo");
		btnUnzipAndServe.addActionListener(e -> unzipAndServeLastZip());

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
					   JOptionPane.showMessageDialog(RpmRepoToolGui.this,
						   "Failed to stop server: " + ex.getMessage(),
						   "HTTP Server Error", JOptionPane.ERROR_MESSAGE);
				   }
			   }
			   else {
				   JOptionPane.showMessageDialog(RpmRepoToolGui.this,
						   "HTTP server is already stopped.");
			   }
		   });

		chkInstallRepos = new JCheckBox("Install .repo files");
		lblInstallTarget = new JLabel("/QOpenSys/etc/yum/repos.d");



		JPanel bottomPanel = new JPanel();
		bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));


		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttonPanel.add(btnZipSelected);
		buttonPanel.add(Box.createHorizontalStrut(35));
		buttonPanel.add(btnUnzipAndServe);

		bottomPanel.add(buttonPanel);

		// Serving Options section
		JPanel servingPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		servingPanel.setBorder(BorderFactory.createTitledBorder("Serving Options"));
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
		southPanel.add(bottomPanel);
		southPanel.add(Box.createVerticalStrut(8));
		southPanel.add(new JSeparator(SwingConstants.HORIZONTAL));
		southPanel.add(zipPanel);
		southPanel.add(Box.createVerticalStrut(8));
		southPanel.add(new JSeparator(SwingConstants.HORIZONTAL));
		southPanel.add(servingPanel);
		add(southPanel, BorderLayout.SOUTH);

		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) {
			// If setting the system L&F fails, continue with the default L&F.
		}
		Font font = new Font("Arial", Font.PLAIN, 10);
		UIManager.put("Label.font", font);
		UIManager.put("Button.font", font);
		UIManager.put("TextField.font", font);
		UIManager.put("Table.font", font);

		setVisible(true);
	}

	// Custom renderer for progress bar column
	public static class ProgressBarRenderer extends JProgressBar implements javax.swing.table.TableCellRenderer {
		public ProgressBarRenderer() {
			super(0, 100);
			setStringPainted(false);
		}
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value,
													  boolean isSelected, boolean hasFocus, int row, int column) {
			int percent = 0;
			if (value instanceof Integer) {
				percent = (Integer) value;
			} else if (value instanceof Number) {
				percent = ((Number) value).intValue();
			}
			setValue(percent);
			return this;
		}
	}

	/**
	 * Refreshes the dropdown menu with existing repo zip files in the snapshots directory.
	 */
	private void refreshZipDropdown() {
		cmbExistingZips.removeAllItems();
		File snapshotsDir = new File("snapshots");
		File[] zips = snapshotsDir.listFiles((dir, name) -> name.matches("snapshot_\\d{8}_\\d{6}\\.zip"));
		if (zips != null) {
			Arrays.sort(zips, Comparator.comparing(File::getName).reversed());
			for (File zip : zips) {
				cmbExistingZips.addItem(zip.getName());
			}
		}
	}
	// Table model for download progress
	private static class DownloadTableModel extends AbstractTableModel {
		private final String[] columns = {"Repo ID", "URL", "Status", "Current File", "Progress (%)"};
		private final List<Object[]> rows = new ArrayList<>();
		// Tracks overall progress for each repository, indexed by table row
		private final List<Long> totalBytesList = new ArrayList<>();
		private final List<Long> downloadedBytesList = new ArrayList<>();

		public void clear() {
			rows.clear();
			totalBytesList.clear();
			downloadedBytesList.clear();
			fireTableDataChanged();
		}
		public void addRepo(String id, String url) {
			rows.add(new Object[]{id, url, "Pending", "", 0});
			totalBytesList.add(0L);
			downloadedBytesList.add(0L);
			fireTableRowsInserted(rows.size()-1, rows.size()-1);
		}
		public void updateStatus(int row, String status) {
			if (row >= 0 && row < rows.size()) {
				rows.get(row)[2] = status;
				fireTableRowsUpdated(row, row);
			}
		}
		public void updateCurrentFile(int row, String fileName) {
			if (row >= 0 && row < rows.size()) {
				rows.get(row)[3] = fileName;
				fireTableRowsUpdated(row, row);
			}
		}
		public void setTotalBytes(int row, long totalBytes) {
			if (row >= 0 && row < totalBytesList.size()) {
				totalBytesList.set(row, totalBytes);
			}
		}
		public void addDownloadedBytes(int row, long bytes) {
			if (row >= 0 && row < downloadedBytesList.size()) {
				downloadedBytesList.set(row, downloadedBytesList.get(row) + bytes);
				updateOverallProgress(row);
			}
		}
		public void updateOverallProgress(int row) {
			long total = totalBytesList.get(row);
			long downloaded = downloadedBytesList.get(row);
			int percent = (total > 0) ? (int)((downloaded * 100) / total) : 0;
			rows.get(row)[4] = percent;
			fireTableRowsUpdated(row, row);
		}
		public void setProgressComplete(int row) {
			rows.get(row)[4] = 100;
			fireTableRowsUpdated(row, row);
		}
		@Override public int getRowCount() { return rows.size(); }
		@Override public int getColumnCount() { return columns.length; }
		@Override public String getColumnName(int col) { return columns[col]; }
		@Override public Object getValueAt(int row, int col) { return rows.get(row)[col]; }
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

	private void unzipAndServeLastZip() {
		btnUnzipAndServe.setEnabled(false);
		updateHttpServerStatus("starting...");
		new SwingWorker<Void, Void>() {
			@Override protected Void doInBackground() {
				try {
					File snapshotsDir = new File("snapshots");
					File[] zips = snapshotsDir.listFiles((dir, name) -> name.matches("snapshot_\\d{8}_\\d{6}\\.zip"));
					if (zips == null || zips.length == 0) {
						SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
							"No repo zip found in snapshots/", "No zip found", JOptionPane.WARNING_MESSAGE));
						return null;
					}
					// Find the most recent zip by name
					File lastZip = zips[0];
					for (File f : zips) {
						if (f.getName().compareTo(lastZip.getName()) > 0) lastZip = f;
					}
					// Unzip to a subfolder named after the zip file
					Path extractDir = snapshotsDir.toPath().resolve(lastZip.getName().replaceFirst("\\.zip$", ""));
					if (!extractDir.toFile().exists()) {
						extractDir.toFile().mkdirs();
						com.github.theprez.repotool.RepoDownloader.unzip(lastZip.toPath(), snapshotsDir.toPath());
					}
					// Generate .repo files for yum compatibility in the extracted subfolder
					Path extractDirForRepo = snapshotsDir.toPath().resolve(lastZip.getName().replaceFirst("\\.zip$", ""));
					com.github.theprez.repotool.RepoUtils.generateRepoFiles(extractDirForRepo, "localhost", parsePortOrDefault(txtPort.getText().trim(), 9000));
					// Optionally install .repo files: on non-IBM i just inform the user and skip.
					if (chkInstallRepos.isSelected()) {
						if (!com.github.theprez.repotool.RepoUtils.isIbmI()) {
							SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
								"Installing .repo files is only supported on IBM i. Skipping installation.",
								"Install Skipped", JOptionPane.INFORMATION_MESSAGE));
						} else {
							String targetFinal = "/QOpenSys/etc/yum/repos.d";
							boolean systemTarget = "/QOpenSys/etc/yum/repos.d".equals(targetFinal) || targetFinal.startsWith("/etc/") || targetFinal.startsWith("/QOpenSys/etc/");
							if (systemTarget && !com.github.theprez.repotool.RepoUtils.isRunningAsRoot()) {
								int r = JOptionPane.showConfirmDialog(RpmRepoToolGui.this,
									"You are not running as root. Installing to " + targetFinal + " will likely fail. Continue?",
									"Install without root?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
								if (r != JOptionPane.YES_OPTION) {
									SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
										"Install cancelled.", "Install", JOptionPane.INFORMATION_MESSAGE));
								} else {
									try {
										int installed = com.github.theprez.repotool.RepoUtils.installRepoFiles(extractDirForRepo, java.nio.file.Paths.get(targetFinal));
										SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
											"Installed " + installed + " .repo files to: " + targetFinal,
											"Install", JOptionPane.INFORMATION_MESSAGE));
									} catch (Exception ex) {
										SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
											"Failed to install .repo files: " + ex.getMessage(),
											"Install Error", JOptionPane.ERROR_MESSAGE));
									}
								}
							} else {
								try {
									int installed = com.github.theprez.repotool.RepoUtils.installRepoFiles(extractDirForRepo, java.nio.file.Paths.get(targetFinal));
									SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
										"Installed " + installed + " .repo files to: " + targetFinal,
										"Install", JOptionPane.INFORMATION_MESSAGE));
								} catch (Exception ex) {
									SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
										"Failed to install .repo files: " + ex.getMessage(),
										"Install Error", JOptionPane.ERROR_MESSAGE));
								}
							}
						}
					}
					// Start HTTP server
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
							   SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
								   "HTTP server failed to start: " + ex.getMessage(), "HTTP Server Error", JOptionPane.ERROR_MESSAGE));
						   }
					   }).start();
				} catch (Exception ex) {
					updateHttpServerStatus("stopped (error)");
					SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
						"Failed to unzip/serve: " + ex.getMessage(), "Unzip/Serve Error", JOptionPane.ERROR_MESSAGE));
				}
				return null;
			}
			@Override protected void done() {
				btnUnzipAndServe.setEnabled(true);
			}
		}.execute();
	}

	
	/**
	 * Unzips and serves the repo zip selected in the dropdown.
	 */
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
						SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
							"Selected zip not found in snapshots/", "No zip found", JOptionPane.WARNING_MESSAGE));
						return null;
					}
					// Unzip to a subfolder named after the zip file, but only if it doesn't already exist
					Path extractDir = snapshotsDir.toPath().resolve(selectedZip.replaceFirst("\\.zip$", ""));
					if (!extractDir.toFile().exists()) {
						extractDir.toFile().mkdirs();
						com.github.theprez.repotool.RepoDownloader.unzip(zipFile.toPath(), snapshotsDir.toPath());
					}
					// Always generate .repo files for the served repo
					String hostForRepo = "localhost";
					int portForRepo = parsePortOrDefault(txtPort.getText().trim(), 9000);
					com.github.theprez.repotool.RepoUtils.generateRepoFiles(extractDir, hostForRepo, portForRepo);
					// Optionally install .repo files: on non-IBM i just inform the user and skip.
					if (chkInstallRepos.isSelected()) {
						if (!com.github.theprez.repotool.RepoUtils.isIbmI()) {
							SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
								"Installing .repo files is only supported on IBM i. Skipping installation.",
								"Install Skipped", JOptionPane.INFORMATION_MESSAGE));
						} else {
							String targetFinal = "/QOpenSys/etc/yum/repos.d";
							boolean systemTarget = "/QOpenSys/etc/yum/repos.d".equals(targetFinal) || targetFinal.startsWith("/etc/") || targetFinal.startsWith("/QOpenSys/etc/");
							if (systemTarget && !com.github.theprez.repotool.RepoUtils.isRunningAsRoot()) {
								int r = JOptionPane.showConfirmDialog(RpmRepoToolGui.this,
									"You are not running as root. Installing to " + targetFinal + " will likely fail. Continue?",
									"Install without root?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
								if (r != JOptionPane.YES_OPTION) {
									SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
										"Install cancelled.", "Install", JOptionPane.INFORMATION_MESSAGE));
								} else {
									try {
										int installed = com.github.theprez.repotool.RepoUtils.installRepoFiles(extractDir, java.nio.file.Paths.get(targetFinal));
										SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
											"Installed " + installed + " .repo files to: " + targetFinal,
											"Install", JOptionPane.INFORMATION_MESSAGE));
									} catch (Exception ex) {
										SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
											"Failed to install .repo files: " + ex.getMessage(),
											"Install Error", JOptionPane.ERROR_MESSAGE));
									}
								}
							} else {
								try {
									int installed = com.github.theprez.repotool.RepoUtils.installRepoFiles(extractDir, java.nio.file.Paths.get(targetFinal));
									SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
										"Installed " + installed + " .repo files to: " + targetFinal,
										"Install", JOptionPane.INFORMATION_MESSAGE));
								} catch (Exception ex) {
									SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
										"Failed to install .repo files: " + ex.getMessage(),
										"Install Error", JOptionPane.ERROR_MESSAGE));
								}
							}
						}
					}
					// Start HTTP server
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
							SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
								"HTTP server failed to start: " + ex.getMessage(), "HTTP Server Error", JOptionPane.ERROR_MESSAGE));
						}
					}).start();
				} catch (Exception ex) {
					updateHttpServerStatus("stopped (error)");
					SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
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

	private void deleteDirectoryRecursively(File dir) {
		if (dir.isDirectory()) {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File f : files) deleteDirectoryRecursively(f);
			}
		}
		dir.delete();
	}

	private void zipSelectedRepos() {
		java.util.List<String> selectedUrls = picker.getSelectedRepoUrls();
		java.util.List<String> selectedIds = picker.getSelectedRepoIds();
		if (selectedUrls.isEmpty()) {
			JOptionPane.showMessageDialog(this,
				"No repos selected.",
				"Zip Selected Repos", JOptionPane.WARNING_MESSAGE);
			return;
		}
		btnZipSelected.setEnabled(false);
		progressModel.clear();
		for (int i = 0; i < selectedUrls.size(); i++) {
			progressModel.addRepo(selectedIds.get(i), selectedUrls.get(i));
		}
		new SwingWorker<Void, Void>() {
			@Override protected Void doInBackground() {
				java.util.List<com.github.theprez.repotool.RepoDownloader> downloaders = new java.util.ArrayList<>();
				// Prepare all downloaders and fetch metadata in parallel
				java.util.concurrent.CountDownLatch metaLatch = new java.util.concurrent.CountDownLatch(selectedUrls.size());
				for (int i = 0; i < selectedUrls.size(); i++) {
					final int row = i;
					new Thread(() -> {
						try {
							String url = selectedUrls.get(row);
							com.github.theprez.config.data.RepoParams params = new com.github.theprez.config.data.RepoParams();
							params.setUrl(url);
							params.setConcurrency(5);
							com.github.theprez.repotool.RepoDownloader dl = new com.github.theprez.repotool.RepoDownloader(params);
							dl.setDownloadProgressListener((repoId, fileName, bytesDownloaded, totalBytesForRepo) -> {
								SwingUtilities.invokeLater(() -> {
									progressModel.updateStatus(row, "Downloading...");
									progressModel.updateCurrentFile(row, fileName);
									progressModel.setTotalBytes(row, totalBytesForRepo);
									progressModel.addDownloadedBytes(row, bytesDownloaded);
								});
							});
							synchronized (downloaders) { downloaders.add(dl); }
							dl.prepareDownload();
							long totalBytes = dl.getTotalBytesToDownload();
							SwingUtilities.invokeLater(() -> progressModel.setTotalBytes(row, totalBytes));
						} catch (Exception ex) {
							ex.printStackTrace();
						} finally {
							metaLatch.countDown();
						}
					}).start();
				}
				try {
					metaLatch.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				// Now download all files in parallel (using saveMultipleToZip)
				String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
				String zipName = "snapshot_" + timestamp + ".zip";
				java.io.File snapshotsDir = new java.io.File("snapshots");
				if (!snapshotsDir.exists()) snapshotsDir.mkdirs();
				java.io.File combinedZip = new java.io.File(snapshotsDir, zipName);
				try {
					com.github.theprez.repotool.RepoDownloader.saveMultipleToZip(downloaders, selectedIds, combinedZip);
					// Post-zip: generate .repo files and optionally install, as in CLI
					Path extractDir = combinedZip.toPath().getParent().resolve(zipName.replaceFirst("\\.zip$", ""));
					// Unzip to extractDir
					com.github.theprez.repotool.RepoDownloader.unzip(combinedZip.toPath(), combinedZip.toPath().getParent());
					// Generate .repo files
					String hostForRepo = "localhost";
					int portForRepo = parsePortOrDefault(txtPort.getText().trim(), 9000);
					com.github.theprez.repotool.RepoUtils.generateRepoFiles(extractDir, hostForRepo, portForRepo);
					// Optionally install .repo files: on non-IBM i just inform the user and skip.
					if (chkInstallRepos.isSelected()) {
						if (!com.github.theprez.repotool.RepoUtils.isIbmI()) {
							SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
								"Installing .repo files is only supported on IBM i. Skipping installation.",
								"Install Skipped", JOptionPane.INFORMATION_MESSAGE));
						} else {
							String targetFinal = "/QOpenSys/etc/yum/repos.d";
							boolean systemTarget = "/QOpenSys/etc/yum/repos.d".equals(targetFinal) || targetFinal.startsWith("/etc/") || targetFinal.startsWith("/QOpenSys/etc/");
							if (systemTarget && !com.github.theprez.repotool.RepoUtils.isRunningAsRoot()) {
								int r = JOptionPane.showConfirmDialog(RpmRepoToolGui.this,
									"You are not running as root. Installing to " + targetFinal + " will likely fail. Continue?",
									"Install without root?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
								if (r != JOptionPane.YES_OPTION) {
									SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
										"Install cancelled.", "Install", JOptionPane.INFORMATION_MESSAGE));
								} else {
									try {
										int installed = com.github.theprez.repotool.RepoUtils.installRepoFiles(extractDir, java.nio.file.Paths.get(targetFinal));
										SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
											"Installed " + installed + " .repo files to: " + targetFinal,
											"Install", JOptionPane.INFORMATION_MESSAGE));
									} catch (Exception ex) {
										SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
											"Failed to install .repo files: " + ex.getMessage(),
											"Install Error", JOptionPane.ERROR_MESSAGE));
									}
								}
							} else {
								try {
									int installed = com.github.theprez.repotool.RepoUtils.installRepoFiles(extractDir, java.nio.file.Paths.get(targetFinal));
									SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
										"Installed " + installed + " .repo files to: " + targetFinal,
										"Install", JOptionPane.INFORMATION_MESSAGE));
								} catch (Exception ex) {
									SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(RpmRepoToolGui.this,
										"Failed to install .repo files: " + ex.getMessage(),
										"Install Error", JOptionPane.ERROR_MESSAGE));
								}
							}
						}
					}
					SwingUtilities.invokeLater(() -> {
						for (int i = 0; i < selectedUrls.size(); i++) {
							progressModel.updateStatus(i, "Complete");
							progressModel.updateCurrentFile(i, "");
							progressModel.setProgressComplete(i);
						}
						btnZipSelected.setEnabled(true);
						JOptionPane.showMessageDialog(RpmRepoToolGui.this,
							"All selected repos zipped to " + combinedZip.getAbsolutePath(),
							"Zip Complete", JOptionPane.INFORMATION_MESSAGE);
						refreshZipDropdown();
					});
				} catch (Exception ex) {
					ex.printStackTrace();
					SwingUtilities.invokeLater(() -> {
						StringWriter sw = new StringWriter();
						ex.printStackTrace(new PrintWriter(sw));
						JOptionPane.showMessageDialog(RpmRepoToolGui.this,
							"Failed to zip repos:\n" + sw.toString(),
							"Zip Error", JOptionPane.ERROR_MESSAGE);
						btnZipSelected.setEnabled(true);
					});
				}
				return null;
			}
		}.execute();
	}
}