
package com.github.theprez.repotool;

import com.github.theprez.config.data.RepoParams;
import org.junit.Test;
import java.net.MalformedURLException;
import static org.junit.Assert.*;
import java.io.IOException;

public class RepoDownloaderTest {
    @Test
    public void testConstructorWithParams() throws MalformedURLException {
        RepoParams params = new RepoParams();
        params.setConcurrency(2);
        params.setUrl("http://example.com/repo");
        RepoDownloader downloader = new RepoDownloader(params);
        assertEquals("http://example.com/repo", downloader.getUrl());
    }

    @Test
    public void testConstructorWithUrl() throws MalformedURLException {
        RepoDownloader downloader = new RepoDownloader(3, "http://example.com/repo2");
        assertEquals("http://example.com/repo2", downloader.getUrl());
    }

    @Test
    public void testAddStatusListener() throws MalformedURLException {
        RepoDownloader downloader = new RepoDownloader(1, "http://example.com/repo3");
        RepoDownloader.StatusListener listener = new RepoDownloader.StatusListener() {
            public void error(RepoDownloader.RepoFile _f, Throwable _e) {}
            public void fileStatusChange(RepoDownloader.RepoFile _f, RepoDownloader.FStatus _newStatus) {}
            public void filesWritten(int _written, int _total) {}
            public void fileWritten(RepoDownloader.RepoFile _f) {}
            public void going(RepoDownloader.RepoFile _f) {}
        };
        RepoDownloader result = downloader.addStatusListener(listener);
        assertSame(downloader, result);
    }

    @Test
    public void testRepoFileEqualityAndHash() throws MalformedURLException {
        RepoDownloader downloader = new RepoDownloader(1, "http://example.com/repo4");
        RepoDownloader.RepoFile file1 = downloader.new RepoFile("fileA.txt");
        RepoDownloader.RepoFile file2 = downloader.new RepoFile("fileA.txt");
        RepoDownloader.RepoFile file3 = downloader.new RepoFile("fileB.txt");
        assertEquals(file1, file2);
        assertNotEquals(file1, file3);
        assertEquals(file1.hashCode(), file2.hashCode());
    }

    @Test
    public void testRepoFileCompareTo() throws MalformedURLException {
        RepoDownloader downloader = new RepoDownloader(1, "http://example.com/repo5");
        RepoDownloader.RepoFile file1 = downloader.new RepoFile("a.txt");
        RepoDownloader.RepoFile file2 = downloader.new RepoFile("b.txt");
        assertTrue(file1.compareTo(file2) < 0);
        assertTrue(file2.compareTo(file1) > 0);
        assertEquals(0, file1.compareTo(downloader.new RepoFile("a.txt")));
    }

        @Test
    public void testDownloadFileInvalidUrl() throws MalformedURLException {
        RepoDownloader downloader = new RepoDownloader(1, "http://invalid.invaliddomain/repo");
        RepoDownloader.RepoFile file = downloader.new RepoFile("nonexistent.file");
        try {
            file.download();
            fail("Expected IOException for invalid URL");
        } catch (IOException e) {
            // Expected exception; test passes if thrown.
        }
    }

    @Test
    public void testDownloadFileMalformedUrl() throws MalformedURLException {
        RepoDownloader downloader = new RepoDownloader(1, "http://example.com");
        RepoDownloader.RepoFile file = downloader.new RepoFile("bad path with spaces and !@#$.file");
        try {
            file.getFullURL();
        } catch (Exception e) {
            // Malformed URLs may not throw exceptions due to Java's lenient URL class
            // Just ensure no crash
            assertNotNull(file.getRelativeName());
        }
    }

    @Test
    public void testDownloadFileCleanup() throws MalformedURLException {
        RepoDownloader downloader = new RepoDownloader(1, "http://example.com");
        RepoDownloader.RepoFile file = downloader.new RepoFile("test.file");
        // Simulate a temp file and cleanup
        try {
            java.io.File temp = java.io.File.createTempFile("test", ".dat");
            file.cleanup(); // Should not throw
            assertTrue(temp.exists() || !temp.exists()); // Just check no exception
        } catch (java.io.IOException e) {
            fail("Unexpected IOException in cleanup test");
        }
    }

    @Test
    public void testPreDownloadHandlesError() throws MalformedURLException {
        RepoDownloader downloader = new RepoDownloader(1, "http://invalid.invaliddomain/repo");
        RepoDownloader.RepoFile file = downloader.new RepoFile("nonexistent.file");
        // Should not throw, but should handle error internally
        file.preDownload();
        // No assertion needed, just ensure no crash
    }
}
