package com.github.theprez.repotool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.nio.file.StandardCopyOption;

public class RepoUtils {

    /**
     * Ensure each top-level directory under 'root' has repodata. If missing and RPMs exist,
     * try to run createrepo or createrepo_c to generate metadata.
     */
    public static void ensureRepodata(Path root) {
        if (root == null) return;
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory).forEach(d -> {
                try {
                    if (hasRepodata(d)) {
                        System.out.println("Repodata present for: " + d);
                        return;
                    }
                    if (!containsRpmFiles(d)) {
                        System.out.println("No RPMs in: " + d + " - skipping createrepo");
                        return;
                    }
                    // Attempt createrepo, else createrepo_c
                    if (runCreateRepo(d, "createrepo", "--update")) return;
                    if (runCreateRepo(d, "createrepo_c", "--update")) return;
                    // Fallback: run createrepo without --update
                    if (runCreateRepo(d, "createrepo", d.toString())) return;
                    if (runCreateRepo(d, "createrepo_c", d.toString())) return;
                    System.out.println("Unable to run createrepo for " + d + ". Is createrepo installed?");
                } catch (Exception e) {
                    System.err.println("Error while creating repodata for " + d + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.println("Failed to list snapshots root: " + e.getMessage());
        }
    }

    /**
     * Generate per-repo .repo files under the snapshots root so clients can download them
     * and place into /etc/yum.repos.d. Files are written as <folder>.repo at the root.
     */
    public static void generateRepoFiles(Path root, String host, int port) {
        if (root == null) return;
        String baseHost = (host == null || host.isEmpty()) ? "localhost" : host;
        try {
            Files.walk(root)
                .filter(Files::isDirectory)
                .forEach(d -> {
                    try {
                        Path repodata = d.resolve("repodata");
                        if (Files.isDirectory(repodata)) {
                            String name = d.getFileName().toString();
                            // Try to get timestamp from parent directory if it matches snapshot_YYYYMMDD_HHMMSS
                            String timestamp = null;
                            Path parent = d.getParent();
                            if (parent != null) {
                                String parentName = parent.getFileName().toString();
                                if (parentName.matches("snapshot_\\d{8}_\\d{6}")) {
                                    timestamp = parentName.substring("snapshot_".length());
                                }
                            }
                            if (timestamp == null) {
                                // fallback: use current time
                                timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                            }
                                String repoFileName = "snapshot_" + timestamp + "_" + name + ".repo";
                                String repoId = repoFileName.replaceFirst("\\.repo$", "");
                                String baseurl = String.format("http://%s:%d/repo/%s/", baseHost, port, name);
                                String repoContent = "[" + repoId + "]\n"
                                    + "name=Repo " + name + "\n"
                                    + "baseurl=" + baseurl + "\n"
                                    + "enabled=1\n"
                                    + "gpgcheck=0\n";
                                Path out = root.resolve(repoFileName);
                                Files.write(out, repoContent.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                                System.out.println("Wrote repo file: " + out + " -> " + baseurl);
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to write .repo for " + d + ": " + e.getMessage());
                    }
                });
        } catch (IOException e) {
            System.err.println("Failed to walk snapshots root for repo files: " + e.getMessage());
        }
    }

    /**
     * Install generated .repo files from the snapshots root into the target yum repo directory.
     * Backs up existing files by moving them to <name>.repo.bak.<timestamp> before copying.
     * Returns number of files successfully installed.
     */
    public static int installRepoFiles(Path root, Path targetDir) {
        if (root == null || targetDir == null) return 0;
        int installed = 0;
        try {
            if (!Files.exists(targetDir)) {
                System.out.println("Target repo dir does not exist, attempting to create: " + targetDir);
                Files.createDirectories(targetDir);
            }
        } catch (IOException e) {
            System.err.println("Failed to ensure target dir exists: " + e.getMessage());
            return 0;
        }

        try (Stream<Path> children = Files.list(root)) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
            for (Path p : (Iterable<Path>) children::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String fn = p.getFileName().toString();
                if (!fn.toLowerCase().endsWith(".repo")) continue;
                Path dest = targetDir.resolve(fn);
                try {
                    if (Files.exists(dest)) {
                        String ts = LocalDateTime.now().format(fmt);
                        Path backup = targetDir.resolve(fn + ".bak." + ts);
                        Files.move(dest, backup, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("Backed up existing repo file: " + dest + " -> " + backup);
                    }
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Installed repo file: " + dest);
                    installed++;
                } catch (IOException e) {
                    System.err.println("Failed to install " + fn + " -> " + dest + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to list repo files in snapshots root: " + e.getMessage());
        }
        return installed;
    }

    private static boolean hasRepodata(Path dir) {
        Path repodata = dir.resolve("repodata");
        Path repomd = dir.resolve("repomd.xml");
        return Files.isDirectory(repodata) || Files.exists(repomd);
    }

    private static boolean containsRpmFiles(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".rpm"));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean runCreateRepo(Path dir, String cmd, String arg) {
        ProcessBuilder pb;
        if ("--update".equals(arg)) {
            // Note: Some createrepo variants accept the --update <dir> argument
            pb = new ProcessBuilder(cmd, "--update", dir.toString());
        } else {
            pb = new ProcessBuilder(cmd, arg);
        }
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) System.out.println("[" + cmd + "] " + line);
            }
            int rc = p.waitFor();
            System.out.println(cmd + " exit code: " + rc + " for " + dir);
            return rc == 0;
        } catch (IOException e) {
            // Handler for command not found or failed to start
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Detect whether the current JVM is running on an IBM i (AS/400) environment.
     * Checks common indicators such as `os.name` containing "os/400" or the presence
     * of the `PASE` environment variable.
     */
    public static boolean isIbmI() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("os/400") || osName.contains("os400") || System.getenv("PASE") != null;
    }

    /**
     * Best-effort check whether the process is running with root privileges.
     * On IBM i: Checks if the user has *ALLOBJ special authority.
     * On other Unix-like systems: Runs `id -u` and returns true if UID == 0.
     * Falls back to checking the `user.name` system property.
     */
    public static boolean isRunningAsRoot() {
        // Check if running on IBM i by looking for PASE environment
        String osName = System.getProperty("os.name", "").toLowerCase();
        System.out.println("[DEBUG] OS Name: " + osName);
        if (osName.contains("os/400") || osName.contains("os400") || System.getenv("PASE") != null) {
            System.out.println("[DEBUG] Detected IBM i environment. Checking *ALLOBJ authority...");
            boolean allobj = hasAllobjAuthority();
            System.out.println("[DEBUG] hasAllobjAuthority() returned: " + allobj);
            return allobj;
        }

        // Standard Unix/Linux check for UID 0
        try {
            Process p = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String s = r.readLine();
                p.waitFor();
                System.out.println("[DEBUG] id -u output: " + s);
                if (s != null) return "0".equals(s.trim());
            }
        } catch (Exception e) {
            System.out.println("[DEBUG] Exception running id -u: " + e.getMessage());
            // fallback to checking user.name
        }
        String userName = System.getProperty("user.name");
        System.out.println("[DEBUG] user.name property: " + userName);
        return "root".equals(userName);
    }
    
    /**
     * Check if the current user has *ALLOBJ special authority on IBM i.
     * Uses the system command to query user profile special authorities.
     */
    private static boolean hasAllobjAuthority() {
        String currentUser = System.getProperty("user.name");
        System.out.println("[DEBUG] Checking *ALLOBJ for user: " + currentUser);
        if (currentUser == null || currentUser.isEmpty()) {
            System.out.println("[DEBUG] user.name is null or empty");
            return false;
        }

        String sql = String.format(
            "SELECT SPECIAL_AUTHORITIES FROM QSYS2.USER_INFO WHERE AUTHORIZATION_NAME = '%s'", currentUser.toUpperCase()
        );
        System.out.println("[DEBUG] Running SQL via JDBC: " + sql);
        
        try {
            // Load IBM i JDBC driver
            Class.forName("com.ibm.as400.access.AS400JDBCDriver");
            
            // Connect to local database
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:as400:localhost");
                 java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery(sql)) {
                
                if (rs.next()) {
                    String authorities = rs.getString(1);
                    System.out.println("[DEBUG] JDBC result: " + authorities);
                    boolean hasAllobj = authorities != null && authorities.toUpperCase().contains("*ALLOBJ");
                    System.out.println("[DEBUG] Has *ALLOBJ: " + hasAllobj);
                    return hasAllobj;
                }
            }
        } catch (ClassNotFoundException e) {
            System.err.println("[DEBUG] IBM i JDBC driver not found. Install JTOpen (jt400.jar): " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[DEBUG] Failed to check *ALLOBJ authority via JDBC: " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }
}
