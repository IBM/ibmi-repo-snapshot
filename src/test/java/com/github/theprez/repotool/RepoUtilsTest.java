package com.github.theprez.repotool;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class RepoUtilsTest {
    @Test
    public void testIsRunningAsRoot() {
        // Should be false on most test systems (unless run as admin/root)
        boolean isRoot = RepoUtils.isRunningAsRoot();
        assertFalse(isRoot);
    }

    @Test
    public void testEnsureRepodataNoException() throws Exception {
        // Create a temp directory to simulate a repo root
        Path tempDir = Files.createTempDirectory("repotest");
        try {
            RepoUtils.ensureRepodata(tempDir);
            // No exception = pass
        } finally {
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    public void testGenerateRepoFilesNoException() throws Exception {
        Path tempDir = Files.createTempDirectory("repotest2");
        try {
            RepoUtils.generateRepoFiles(tempDir, "localhost", 9000);
            // No exception = pass
        } finally {
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    public void testInstallRepoFilesNoRepoFiles() throws Exception {
        Path tempDir = Files.createTempDirectory("repotest3");
        Path targetDir = Files.createTempDirectory("repotarget");
        try {
            int installed = RepoUtils.installRepoFiles(tempDir, targetDir);
            assertEquals(0, installed);
        } finally {
            Files.deleteIfExists(tempDir);
            Files.deleteIfExists(targetDir);
        }
    }
}
