package com.github.theprez.repotool;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;

import java.nio.file.Files;
import java.nio.file.Path;

public class JettyServer {

    public int getPort() {
        if (server != null) {
            return server.getURI().getPort();
        }
        return port;
    }
    private final int port;
    private final Path rootDir;
    private Server server;
    private Thread serverThread;

    public JettyServer(int port, Path rootDir) {
        this.port = port;
        this.rootDir = rootDir.toAbsolutePath().normalize();
    }


    public void start() throws Exception {
        System.out.println("[HttpServer] Attempting to start HTTP server on port " + port + " with rootDir: " + rootDir);
        if (!Files.isDirectory(rootDir)) {
            System.err.println("[HttpServer] ERROR: Root dir does not exist: " + rootDir);
            throw new IllegalArgumentException("Root dir does not exist: " + rootDir);
        }
        server = new Server(port);
        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        ctx.setContextPath("/repo");
        ctx.setBaseResource(Resource.newResource(rootDir.toUri()));
        // Add AliasCheck to prevent path traversal outside rootDir
        ctx.clearAliasChecks();
        ctx.addAliasCheck((base, alias) -> {
            try {
                // base is the root directory, alias is the requested file path
                Path basePath = rootDir;
                Path aliasPath = Path.of(alias.toString()).toAbsolutePath().normalize();
                if (!aliasPath.startsWith(basePath)) {
                    System.err.println("[SECURITY] Path traversal attempt blocked: " + aliasPath);
                    return false;
                }
                return true;
            } catch (Exception e) {
                System.err.println("[SECURITY] Error in alias check: " + e.getMessage());
                return false;
            }
        });
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("dirAllowed", "true");
        holder.setInitParameter("redirectWelcome", "true");
        holder.setInitParameter("welcomeServlets", "true");
        ctx.addServlet(holder, "/*");
        server.setHandler(ctx);
        serverThread = new Thread(() -> {
            try {
                if (server == null) {
                    System.err.println("[HttpServer] Server is null in server thread. Not starting HTTP server.");
                    return;
                }
                System.out.println("[HttpServer] Starting HTTP server on port " + port + " with rootDir: " + rootDir);
                server.start();
                System.out.println("[HttpServer] SUCCESS: Serving from: " + rootDir);
                System.out.printf("Browse: http://localhost:%d/repo/%n", port);
                server.join();
            } catch (Exception e) {
                System.err.println("[HttpServer] ERROR: Exception during HTTP server startup:");
                e.printStackTrace();
            }
        });
        serverThread.start();
    }

    public void stop() throws Exception {
        System.out.println("[HttpServer] Stopping HTTP server on port " + port + " with rootDir: " + rootDir);
        if (server != null) {
            server.stop();
            server = null;
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
        System.out.println("[HttpServer] SUCCESS: HTTP server stopped for rootDir: " + rootDir);
    }
}
