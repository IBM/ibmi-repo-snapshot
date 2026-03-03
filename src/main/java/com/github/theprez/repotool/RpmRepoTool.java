package com.github.theprez.repotool;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Map;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.github.theprez.config.RpmConfigParser;
import com.github.theprez.config.data.Repo;
import com.github.theprez.config.data.RepoParams;
import com.github.theprez.repotool.RepoDownloader.FStatus;
import com.github.theprez.repotool.RepoDownloader.RepoFile;
import com.github.theprez.repotool.RepoDownloader.StatusListener;

public class RpmRepoTool {

    /**
     * Performs the bootstrap workflow if the system is not already bootstrapped.
     * Steps:
     * Create in-progress marker file
     * Extract /tmp/bootstrap.tar.Z using tar
     * Decompress and extract bootstrap-stage2.tar.zst using zstd and tar binaries
     * Link /bin/bash to /QOpenSys/pkgs/bin/bash
     * Remove in-progress marker file
     * Print success or failure messages
     */
    private static void performBootstrap() {
        File inProgress = new File("/tmp/bootstrap.in-progress-dont-remove");
        // Java 8 compatible unique temp dir: use PID from getName() or fallback to timestamp
        String pid = null;
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName(); // format: pid@host
            int atIdx = name.indexOf('@');
            if (atIdx > 0) {
                pid = name.substring(0, atIdx);
            }
        } catch (Throwable t) {
            // ignore, fallback below
        }
        if (pid == null) {
            pid = String.valueOf(System.currentTimeMillis());
        }
        String tempDirPath = "/tmp/bootstrap." + pid;
        File tempDir = new File(tempDirPath);
        try {
            // Create unique temp directory
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                throw new Exception("Failed to create temp directory: " + tempDirPath);
            }
            // Create in-progress marker file
            try (java.io.FileWriter fw = new java.io.FileWriter(inProgress)) {
                fw.write(java.time.LocalDateTime.now().toString());
            }
            System.out.println("Starting bootstrap at " + java.time.LocalDateTime.now());
            System.out.println("Please do not interrupt");

            // Extract bootstrap.tar.Z from current working directory to tempDir using tar
            String cwd = System.getProperty("user.dir");
            File tarZ = new File(cwd, "bootstrap.tar.Z");
            if (!tarZ.exists()) {
                throw new Exception("Missing bootstrap.tar.Z in current directory (" + cwd + ") for bootstrapping");
            }
            Process tarExtract = new ProcessBuilder("/QOpenSys/usr/bin/tar", "-xof", tarZ.getAbsolutePath())
                .directory(tempDir)
                .inheritIO()
                .start();
            int tarResult = tarExtract.waitFor();
            if (tarResult != 0) throw new Exception("Failed to extract bootstrap.tar.Z");

            // Decompress and extract bootstrap-stage2.tar.zst using zstd and tar binaries in tempDir
            File zstdBin = new File(tempDir, "zstd.bin");
            File tarBin = new File(tempDir, "tar.bin");
            File stage2 = new File(tempDir, "bootstrap-stage2.tar.zst");


            // Check required files exist
            if (!zstdBin.exists() || !tarBin.exists() || !stage2.exists()) {
                throw new Exception("Missing stage2 bootstrap files in " + tempDirPath);
            }

            // Use a shell pipeline to let the shell handle piping between zstd and tar
            String shellCmd = zstdBin.getAbsolutePath() + " -dc " + stage2.getAbsolutePath() + " | " +
                tarBin.getAbsolutePath() + " -x -f - --same-permissions --same-owner --checkpoint=5120 --checkpoint-action=totals";
            ProcessBuilder shellPb = new ProcessBuilder("sh", "-c", shellCmd);
            shellPb.directory(new File("/"));
            shellPb.inheritIO();
            Process shellProc = shellPb.start();
            int shellExit = shellProc.waitFor();
            if (shellExit != 0) throw new Exception("Failed to extract stage2 bootstrap (shell pipeline exit=" + shellExit + ")");

            // Link /bin/bash to /QOpenSys/pkgs/bin/bash
            Process ln = new ProcessBuilder("ln", "-sf", "/QOpenSys/pkgs/bin/bash", "/bin/bash")
                .inheritIO()
                .start();
            int lnResult = ln.waitFor();
            if (lnResult != 0) throw new Exception("Failed to link /bin/bash");

            // Remove in-progress marker file
            inProgress.delete();

            // Cleanup temp directory
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();

            // Print success message
            System.out.println("Bootstrap succeeded at " + java.time.LocalDateTime.now());
        } catch (Exception e) {
            System.err.println("Bootstrap failed at " + java.time.LocalDateTime.now());
            e.printStackTrace();
            // Remove in-progress marker on failure as well
            inProgress.delete();
            // Cleanup temp directory on failure
            if (tempDir.exists()) {
                for (File f : tempDir.listFiles()) {
                    f.delete();
                }
                tempDir.delete();
            }
            System.exit(1);
        }
    }

    /**
     * Checks if the system is already bootstrapped by verifying the existence of key files.
     * @return true if bootstrapped, false otherwise
     */
    private static boolean ensureBootstrapped() {
        File rpmLib = new File("/QOpenSys/var/lib/rpm");
        File inProgress = new File("/tmp/bootstrap.in-progress-dont-remove");
        if (inProgress.exists()) {
            System.out.println("Bootstrap in progress or previously failed. Needs attention.");
            return false;
        }
        if (rpmLib.exists()) {
            System.out.println("System is already bootstrapped.");
            return true;
        }
        System.out.println("System is not bootstrapped.");
        return false;
    }

    public static void main(String[] args) {
        try {
            // Only run bootstrap logic on IBM i
            String osName = System.getProperty("os.name");
            boolean isIBMi = osName != null && (osName.equalsIgnoreCase("OS/400") || osName.toLowerCase().contains("ibm i"));
            if (isIBMi) {
                try {
                    if (!ensureBootstrapped()) {
                        performBootstrap();
                    }
                } catch (Exception e) {
                    System.err.println("Bootstrap check or process failed: " + e.getMessage());
                    e.printStackTrace();
                    System.exit(1);
                }
            } else {
                System.out.println("Non-IBM i OS detected (" + osName + "). Skipping bootstrap checks.");
            }
            Path extractedDir = null;



            Options opts = new Options();

            Option createSnap = new Option("cr", "createSnap", true, "create a snapshot from RPMs");
            createSnap.setRequired(false);
            opts.addOption(createSnap);

            Option repoNameOpt = new Option("rn", "reponame", true, "Name of the created repo (used with -cr). Default: customrepo");
            repoNameOpt.setRequired(false);
            opts.addOption(repoNameOpt);

            Option config = new Option("c", "config", true, "config file path");
            config.setRequired(false);
            opts.addOption(config);

            Option adminGui = new Option(null, "adminGui", false, "Invoke admin GUI application");
            adminGui.setRequired(false);
            opts.addOption(adminGui);

            Option userGui = new Option(null, "userGui", false, "Invoke user GUI application (serve existing repos only)");
            userGui.setRequired(false);
            opts.addOption(userGui);

            Option serveJetty = new Option(null, "serve", false, "Invoke server to provide repo endpoint");
            serveJetty.setRequired(false);
            opts.addOption(serveJetty);

            Option portOption = new Option("p", "port", true, "Port for Jetty server (default: 9000)");
            portOption.setRequired(false);
            opts.addOption(portOption);

            Option hostOption = new Option(null, "host", true, "Host/IP to use in generated .repo files (default: localhost)");
            hostOption.setRequired(false);
            opts.addOption(hostOption);

            Option dirOption = new Option("d", "dir", true, "Root directory to serve (default: .)");
            dirOption.setRequired(false);
            opts.addOption(dirOption);

            Option serveExisting = new Option(null, "serve-existing", true, "Assume repos are already downloaded/unzipped in the dir; generate .repo files and optionally install/serve them");
            serveExisting.setRequired(false);
            opts.addOption(serveExisting);

            Option installRepos = new Option(null, "install-repos", false, "Install generated .repo files into the system yum repo directory (requires permissions)");
            installRepos.setRequired(false);
            opts.addOption(installRepos);

            Option installTarget = new Option(null, "install-target", true, "Target directory to install .repo files (default: /QOpenSys/etc/yum/repos.d)");
            installTarget.setRequired(false);
            opts.addOption(installTarget);

            Option forceInstall = new Option(null, "force-install", false, "When set, proceed with --install-repos even if not running as root (unsafe)");
            forceInstall.setRequired(false);
            opts.addOption(forceInstall);

            CommandLineParser parser = new DefaultParser();
            HelpFormatter formatter = new HelpFormatter();
            CommandLine cmd = null;

            try {
                cmd = parser.parse(opts, args);
            } catch (ParseException e) {
                System.out.println(e.getMessage());
                formatter.printHelp("RepoSnapshotTool", opts);
                System.exit(1);
            }

            
            boolean hasConfig = cmd.hasOption("config");
            Boolean adminGuiView = cmd.hasOption("adminGui");
            Boolean userGuiView = cmd.hasOption("userGui");
            Boolean useServeExisting = cmd.hasOption("serve-existing");
            Boolean serveWithJetty = cmd.hasOption("serve");
            Boolean crSnap = cmd.hasOption("createSnap") || cmd.hasOption("cr");

            int modeCount = 0;
            if (hasConfig) modeCount++;
            if (adminGuiView) modeCount++;
            if (userGuiView) modeCount++;
            if (useServeExisting) modeCount++;
            if (crSnap) modeCount++;
            if (modeCount != 1) {
                System.err.println("You must specify exactly one of -c <file> OR --adminGui OR --userGui OR --serve-existing");
                formatter.printHelp("RepoSnapshotTool", opts);
                System.exit(1);
            }

            if (hasConfig) {
                String configFile = cmd.getOptionValue("config");
                System.out.println(configFile);
                RpmConfigParser configParser = new RpmConfigParser(configFile);
                System.out.println(configParser.get_repos());

                java.util.List<RepoDownloader> downloaders = new java.util.ArrayList<>();
                java.util.List<String> repoIds = new java.util.ArrayList<>();

                for (Map.Entry<Integer, Repo> entry : configParser.get_repos().entrySet()) {
                    Repo curRepo = entry.getValue();
                    RepoParams curRepoParams = curRepo.getParams();

                    System.out.println("--- Prepare RepoDownloader for: " + curRepo.getRepo());
                    RepoDownloader dl = new RepoDownloader(curRepoParams);
                    dl.addStatusListener(getConsoleListener());
                    downloaders.add(dl);
                    repoIds.add(curRepo.getRepo());
                }

                // Save all repos to one zip file with timestamped name
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                String zipName = "snapshot_" + timestamp + ".zip";
                File combinedZip = new File("snapshots/" + zipName);
                System.out.println("Zipping...");
                RepoDownloader.saveMultipleToZip(downloaders, repoIds, combinedZip);
                System.out.println("All repos saved to: " + combinedZip.getAbsolutePath());
                System.out.println("Unzipping...");
                RepoDownloader.unzip(combinedZip.toPath(), new File("snapshots").toPath());
                System.out.println("Unzipped " + zipName + " to: snapshots/");

                // Generate repodata if needed so Yum can use the repo
                // Track the directory that was just unzipped (extractedDir)
                extractedDir = combinedZip.toPath().getParent().resolve(zipName.replaceFirst("\\.zip$", ""));
                RepoUtils.ensureRepodata(extractedDir);
                // Write .repo files (use --host if provided)
                String hostForRepo = cmd.getOptionValue("host", "localhost");
                int portForRepo = Integer.parseInt(cmd.getOptionValue("port", "9000"));
                // Generate .repo files in extractedDir (mirroring GUI)
                RepoUtils.generateRepoFiles(extractedDir, hostForRepo, portForRepo);

                // Optionally install generated .repo files into system repo dir
                if (cmd.hasOption("install-repos")) {
                    String target = cmd.getOptionValue("install-target", "/QOpenSys/etc/yum/repos.d");
                    boolean force = cmd.hasOption("force-install");
                    if (!RepoUtils.isRunningAsRoot() && !force) {
                        System.err.println("Warning: not running as root. Installing into " + target + " will likely fail. Rerun with --force-install to proceed anyway.");
                    } else {
                        int installed = RepoUtils.installRepoFiles(extractedDir, java.nio.file.Paths.get(target));
                        System.out.println("Installed " + installed + " .repo files to: " + target);
                    }
                }
            }

            // Serve-existing mode: assume snapshots are already present under dir (default snapshots/)
            if (useServeExisting) {
                String dirArg = cmd.getOptionValue("serve-existing");
                if (dirArg == null || dirArg.isEmpty()) {
                    System.err.println("Error: --serve-existing requires a directory or zip argument.");
                    formatter.printHelp("RepoSnapshotTool", opts);
                    System.exit(1);
                }
                // extractedDir already declared
                if (dirArg.endsWith(".zip")) {
                    java.nio.file.Path zipPath = java.nio.file.Paths.get(dirArg).toAbsolutePath().normalize();
                    java.nio.file.Path parentDir = zipPath.getParent();
                    if (parentDir == null) {
                        parentDir = java.nio.file.Paths.get(System.getProperty("user.dir"));
                    }
                    String zipBaseName = zipPath.getFileName().toString().replaceFirst("\\.zip$", "");
                    extractedDir = parentDir.resolve(zipBaseName);
                    if (!Files.exists(extractedDir)) {
                        System.out.println("Unzipping...");
                        try {
                            if (!Files.exists(zipPath)) {
                                throw new Exception("Zip file does not exist: " + zipPath);
                            }
                            Files.createDirectories(extractedDir);
                            com.github.theprez.repotool.RepoDownloader.unzip(zipPath, parentDir);
                            System.out.println("Unzipped " + zipPath + " to: " + extractedDir);
                        } catch (Exception ex) {
                            System.err.println("Failed to unzip repo zip: " + ex.getMessage());
                            System.exit(1);
                        }
                    } else {
                        System.out.println("Extracted directory already exists, skipping unzip: " + extractedDir);
                    }
                } else {
                    extractedDir = java.nio.file.Paths.get(dirArg).toAbsolutePath().normalize();
                }
                System.out.println("Using extracted directory: " + extractedDir);
                // Ensure repodata exists (attempt to create if missing)
                RepoUtils.ensureRepodata(extractedDir);

                String hostForRepo = cmd.getOptionValue("host", "localhost");
                int portForRepo = Integer.parseInt(cmd.getOptionValue("port", "9000"));
                RepoUtils.generateRepoFiles(extractedDir, hostForRepo, portForRepo);

                if (cmd.hasOption("install-repos")) {
                    String target = cmd.getOptionValue("install-target", "/QOpenSys/etc/yum/repos.d");
                    boolean force = cmd.hasOption("force-install");
                    if (!RepoUtils.isRunningAsRoot() && !force) {
                        System.err.println("Warning: not running as root. Installing into " + target + " will likely fail. Rerun with --force-install to proceed anyway.");
                    } else {
                        int installed = RepoUtils.installRepoFiles(extractedDir, java.nio.file.Paths.get(target));
                        System.out.println("Installed " + installed + " .repo files to: " + target);
                    }
                }


                int port = Integer.parseInt(cmd.getOptionValue("port", "9000"));
                JettyServer server = new JettyServer(port, extractedDir);
                server.start();

            }

            if (adminGuiView) {
                new RpmRepoToolGui();
            }
            if (userGuiView) {
                new RpmRepoToolGuiUser();
            }

                
            // Start Jetty server only if it was not started in serve-existing mode
            if (serveWithJetty && !adminGuiView && !useServeExisting && !userGuiView) {
                int port = Integer.parseInt(cmd.getOptionValue("port", "9000"));
                if (extractedDir != null) {
                    JettyServer server = new JettyServer(port, extractedDir);
                    server.start();
                } else {
                    System.err.println("Error: extractedDir is not set. Jetty will not start.");
                }
            }

            if (crSnap) {
                String newDir = cmd.getOptionValue("createSnap");
                String repoName = cmd.getOptionValue("reponame", cmd.getOptionValue("rn", "customrepo"));
                Path dir = java.nio.file.Paths.get(newDir).toAbsolutePath().normalize();
                if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                    System.err.println("Error: directory does not exist: " + dir);
                    System.exit(1);
                }

                // Prepare temp workspace: <tmp>/<repoName>/{src,repodata}
                Path tmp = Files.createTempDirectory("repo-snap-");
                Path repoRoot = tmp.resolve(repoName);
                Path srcDir = repoRoot.resolve("src");
                Files.createDirectories(srcDir);

                // Copy RPM files from source dir into srcDir (non-recursive: only top-level RPMs)
                try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                    s.filter(Files::isRegularFile)
                     .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".rpm"))
                     .forEach(p -> {
                         try {
                             Files.copy(p, srcDir.resolve(p.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                         } catch (Exception e) {
                             throw new RuntimeException(e);
                         }
                     });
                }

                // If no RPMs found at top-level, try recursive copy
                boolean hasRpms = Files.list(srcDir).anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".rpm"));
                if (!hasRpms) {
                    try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
                        s.filter(Files::isRegularFile)
                         .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".rpm"))
                         .forEach(p -> {
                             try {
                                 Files.copy(p, srcDir.resolve(p.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                             } catch (Exception e) {
                                 throw new RuntimeException(e);
                             }
                         });
                    }
                }

                // Run createrepo on repoRoot so repodata references the `src/` subfolder
                boolean created = runCreateRepoOnDir(repoRoot);
                if (!created) {
                    System.err.println("Warning: createrepo did not succeed. The repo may be invalid.");
                }

                // Create snapshots directory and zip repoRoot as snapshot containing repoName/{repodata,src}
                Path snapshotsDir = java.nio.file.Paths.get("snapshots");
                if (!Files.exists(snapshotsDir)) Files.createDirectories(snapshotsDir);
                String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                String zipName = "snapshot_" + timestamp + ".zip";
                Path zipPath = snapshotsDir.resolve(zipName);
                System.out.println("Creating snapshot zip: " + zipPath);
                createSnapshotZipFromDir(repoRoot, zipPath);
                System.out.println("Snapshot created: " + zipPath.toAbsolutePath());

                // Cleanup temp
                try {
                    deleteDirectoryRecursively(tmp);
                } catch (Exception e) {
                    // ignore
                }
            }

            System.out.println("Complete!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Helper method to delete a directory and its contents recursively
    private static void deleteDirectoryRecursively(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (java.io.IOException ignored) {}
            });
        }
    }

    /**
     * Try to run createrepo or createrepo_c on the provided directory.
     * Returns true if any invocation returned exit code 0.
     */
    private static boolean runCreateRepoOnDir(Path dir) {
        String[] cmds = new String[] { "createrepo_c", "createrepo" };
        for (String cmd : cmds) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, dir.toString());
                pb.directory(dir.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) System.out.println("[" + cmd + "] " + line);
                }
                int rc = p.waitFor();
                System.out.println(cmd + " exit code: " + rc + " for " + dir);
                if (rc == 0) return true;
            } catch (Exception e) {
                // try next
            }
        }
        return false;
    }

    /**
     * Create a zip snapshot containing the given directory. The zip will contain a top-level
     * folder named after the directory's last segment and preserve relative paths.
     */
    private static void createSnapshotZipFromDir(Path dir, Path destZip) throws java.io.IOException {
        String baseName = dir.getFileName().toString();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(destZip.toFile()))) {
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                walk.forEach(p -> {
                    try {
                        Path rel = dir.relativize(p);
                        String entryName = baseName + "/" + rel.toString().replace('\\', '/');
                        if (Files.isDirectory(p)) {
                            if (rel.toString().isEmpty()) return; // skip root dir itself
                            if (!entryName.endsWith("/")) entryName = entryName + "/";
                            zos.putNextEntry(new ZipEntry(entryName));
                            zos.closeEntry();
                        } else {
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(p, zos);
                            zos.closeEntry();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    private static StatusListener getConsoleListener() {
        return new StatusListener() {

            @Override
            public void filesWritten(int _written, int _total) {
                System.out.println("" + _written + " of " + _total + " files written");
            }

            @Override
            public void fileStatusChange(RepoFile _f, FStatus _newStatus) {
                switch (_newStatus) {
                    case DOWNLOAD_COMPLETE:
                        System.out.println("**** Download complete: " + _f.getRelativeName());
                        break;
                    case DOWNLOADING:
                        System.out.println("**** Now downloading: " + _f.getRelativeName());
                        break;
                    case NOT_STARTED:
                        System.out.println("**** Not started: " + _f.getRelativeName());
                        break;
                    case QUEUED:
                        System.out.println("**** Queued: " + _f.getRelativeName());
                        break;
                }
            }

            @Override
            public void fileWritten(RepoFile _f) {
                System.out.println("Added: " + _f.getRelativeName() + " (" + _f.getSize() + " bytes)");
            }

            @Override
            public void error(RepoFile _f, Throwable _e) {
                System.err.println("Error with " + _f.getRelativeName() + ":");
                _e.printStackTrace(System.err);
            }

            @Override
            public void going(RepoFile _f) {
            }
        };
    }

}
