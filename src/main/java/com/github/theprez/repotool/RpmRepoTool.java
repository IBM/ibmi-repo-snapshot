package com.github.theprez.repotool;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
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

    public static void main(String[] args) {
        try {
            Path extractedDir = null;

            Options opts = new Options();

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

            int modeCount = 0;
            if (hasConfig) modeCount++;
            if (adminGuiView) modeCount++;
            if (userGuiView) modeCount++;
            if (useServeExisting) modeCount++;
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
                String zipName = timestamp + "_repo.zip";
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
                        int installed = RepoUtils.installRepoFiles(extractedDir, Path.of(target));
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
                    Path zipPath = Path.of(dirArg).toAbsolutePath().normalize();
                    Path parentDir = zipPath.getParent();
                    if (parentDir == null) {
                        parentDir = Path.of(System.getProperty("user.dir"));
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
                    extractedDir = Path.of(dirArg).toAbsolutePath().normalize();
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
                        int installed = RepoUtils.installRepoFiles(extractedDir, Path.of(target));
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
