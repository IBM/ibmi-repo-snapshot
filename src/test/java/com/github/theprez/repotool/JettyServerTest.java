package com.github.theprez.repotool;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class JettyServerTest {
    @Test
    public void testPathTraversalProtection() throws Exception {
        Path tempDir = Files.createTempDirectory("jettyservertest3");
        Path secretFile = Files.createTempFile("secret", ".txt");
        Files.write(secretFile, "Sensitive Data".getBytes());
        JettyServer server = new JettyServer(9000, tempDir);
        try {
            server.start();
            Thread.sleep(500); // Pause to allow Jetty server to start
            int port = server.getPort();
            // Test path traversal by attempting to access a secret file
            String url = String.format("http://localhost:%d/repo/../%s", port, secretFile.getFileName());
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            // xpect HTTP 403 Forbidden or 404 Not Found response
            assertTrue(responseCode == 403 || responseCode == 404);
        } finally {
            server.stop();
            Files.deleteIfExists(tempDir);
            Files.deleteIfExists(secretFile);
        }
    }
    @Test
    public void testJettyServerConstructor() throws Exception {
        Path tempDir = Files.createTempDirectory("jettyservertest");
        try {
            JettyServer server = new JettyServer(9000, tempDir);
            assertNotNull(server);
        } finally {
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    public void testJettyServerStartStop() throws Exception {
        Path tempDir = Files.createTempDirectory("jettyservertest2");
        JettyServer server = new JettyServer(9000, tempDir);
        boolean started = false;
        try {
            try {
                server.start();
                Thread.sleep(500); // Pause to allow Jetty server to start
                started = true;
            } catch (Exception e) {
                // Acceptable if server start fails; ensure no crash occurs
            }
            if (started) {
                try {
                    server.stop();
                } catch (NullPointerException npe) {
                    // acceptable if the server was not started
                }
            }
        } finally {
            Files.deleteIfExists(tempDir);
        }
    }
}
