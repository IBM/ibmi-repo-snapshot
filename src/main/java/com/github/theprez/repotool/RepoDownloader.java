package com.github.theprez.repotool;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.github.theprez.config.data.RepoParams;

public class RepoDownloader {

    // Progress callback interface
    // Now reports bytesDownloaded and totalBytes for overall progress
    public interface DownloadProgressListener {
        void onProgress(String repoId, String fileName, long bytesDownloaded, long totalBytesForRepo);
    }

    private DownloadProgressListener progressListener;

    public void setDownloadProgressListener(DownloadProgressListener listener) {
        this.progressListener = listener;
    }

    // Track overall progress
    private long totalBytesToDownload = -1;
    private long bytesDownloaded = 0;

    public long getTotalBytesToDownload() {
        return totalBytesToDownload;
    }

    // Precompute total bytes before starting download batch
    public void prepareDownload() {
        bytesDownloaded = 0;
        try {
            long total = 0;
            for (RepoFile f : getFiles()) {
                int sz = f.getSize();
                if (sz < 0) {
                    // Try to fetch size if not already set
                    try {
                        URLConnection conn = f.getFullURL().openConnection();
                        sz = conn.getContentLength();
                        f.m_size = sz;
                    } catch (Exception ex) { sz = 0; }
                }
                total += Math.max(sz, 0);
            }
            totalBytesToDownload = total;
        } catch (Exception ex) {
            totalBytesToDownload = 0;
        }
    }

    /**
     * Returns the original URL string for this downloader.
     */
    /**
     * Returns the original URL string for this downloader.
     */


    enum FStatus {
        DOWNLOAD_COMPLETE, DOWNLOADING, NOT_STARTED, QUEUED
    }
    public class RepoFile implements Comparable<RepoFile> {
        private volatile int m_bytesRead = 0;
        private final Stack<Runnable> m_cleanupTasks = new Stack<Runnable>();
        private File m_file = null;
        private final String m_relativeName;
        private volatile int m_size = -1;
        private volatile FStatus m_status = FStatus.NOT_STARTED;

        public RepoFile(final String m_relativeName) {
            super();
            this.m_relativeName = m_relativeName;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> cleanup()));
            fireFileStatusChange(this, m_status);
        }

        public void cleanup() {
            m_file = null;
            while (!m_cleanupTasks.isEmpty()) {
                m_cleanupTasks.pop().run();
            }
        }

        @Override
        public int compareTo(final RepoFile _o) {
            return m_relativeName.compareTo(_o.m_relativeName);
        }

        public File download() throws IOException {
            synchronized (this) {
                if (null != m_file) {
                    return m_file;
                }
            }
            m_status = FStatus.QUEUED;

            synchronized (this) {
                final File ret = File.createTempFile("dd78", ".dat");
                m_cleanupTasks.add(new Runnable() {
                    @Override
                    public void run() {
                        ret.delete();
                    }
                });
                URLConnection in = null;
                try (FileOutputStream fos = new FileOutputStream(ret)) {
                    in = getFullURL().openConnection();
                    m_size = in.getContentLength();
                    final InputStream src = in.getInputStream();
                    final byte[] buf = new byte[1024 * 1024];
                    int bytesRead;
                    boolean firstChunk = true;
                    while ((bytesRead = src.read(buf)) > 0) {
                        m_bytesRead += bytesRead;
                        fos.write(buf, 0, bytesRead);
                        if (firstChunk) {
                            m_status = FStatus.DOWNLOADING;
                            fireFileStatusChange(this, m_status);
                            firstChunk = false;
                        } else {
                            // Fire status for every chunk
                            fireFileStatusChange(this, FStatus.DOWNLOADING);
                        }
                        fireGoing(this);
                        // Progress callback (per file)
                        if (progressListener != null && m_size > 0) {
                            synchronized (RepoDownloader.this) {
                                RepoDownloader.this.bytesDownloaded += bytesRead;
                                long totalBytes = RepoDownloader.this.getTotalBytesToDownload();
                                long clampedDownloaded = Math.min(RepoDownloader.this.bytesDownloaded, totalBytes);
                                progressListener.onProgress(m_relativeName, m_relativeName, clampedDownloaded, totalBytes);
                            }
                        }
                    }
                    m_status = FStatus.DOWNLOAD_COMPLETE;
                    fireFileStatusChange(this, m_status);
                }
                return m_file = ret;
            }
        }

        @Override
        public boolean equals(final Object _o) {
            if (null == _o) {
                return false;
            }
            if (!(_o instanceof RepoFile)) {
                return false;
            }
            return m_relativeName.equals(((RepoFile) _o).m_relativeName);
        }

        public int getBytesRead() {
            return m_bytesRead;
        }

        public URL getFullURL() throws MalformedURLException {
            return new URL(m_url + "/" + m_relativeName);
        }

        public String getRelativeName() {
            return m_relativeName;
        }

        public int getSize() {
            return m_size;
        }

        @Override
        public int hashCode() {
            return m_relativeName.hashCode();
        }

        public void preDownload() {
            try {
                download();
            } catch (final IOException _e) {
                fireError(this, _e);
            }
        }

        @Override
        public String toString() {
            return m_relativeName;
        }
    }
    public interface StatusListener {
        public void error(RepoFile _f, Throwable _e);

        public void fileStatusChange(RepoFile _f, FStatus _newStatus);

        public void filesWritten(int _written, int _total);

        public void fileWritten(RepoFile _f);

        public void going(RepoFile _f);
    }
    private int m_concurrency;
    private final ThreadPoolExecutor m_downloadThreads;

    private List<StatusListener> m_listeners = new LinkedList<StatusListener>();

    //private final ThreadPoolExecutor m_listenerUpdateThreads;

    private String m_url;

    /**
     * Returns the original URL string for this downloader.
     */
    public String getUrl() {
        return m_url;
    }

    public RepoDownloader(final int _concurrency, final String _url) {
        m_concurrency = _concurrency;
        m_url = _url;
        m_downloadThreads = new ThreadPoolExecutor(
            m_concurrency, m_concurrency,
            40, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "download thread");
                    t.setDaemon(true);
                    return t;
                }
            }
        );
    }

    public RepoDownloader(RepoParams _params) {
        this(_params.getConcurrency(), _params.getUrl());
    }

    public RepoDownloader addStatusListener(StatusListener _l) {
        synchronized (m_listeners) {
            m_listeners.add(_l);
            return this;
        }
    }

    public void fireError(final RepoFile _f, Throwable _e) {
        synchronized (m_listeners) {
            for (StatusListener listener : m_listeners) {
                // Multi-threaded version:
                /*
                m_listenerUpdateThreads.execute(new Runnable() {
                    @Override
                    public void run() {
                        listener.error(_f, _e);
                    }
                });
                */
                // Single-threaded version:
                listener.error(_f, _e);
            }
        }
    }

    public void fireFileStatusChange(final RepoFile _file, final FStatus _status) {
        synchronized (m_listeners) {
            for (StatusListener listener : m_listeners) {
                // Multi-threaded version:
                /*
                m_listenerUpdateThreads.execute(new Runnable() {
                    @Override
                    public void run() {
                        listener.fileStatusChange(_file, _status);
                    }
                });
                */
                // Single-threaded version:
                listener.fileStatusChange(_file, _status);
            }
        }
    }

    public void fireFilesWritten(final int _written, int _total) {
        synchronized (m_listeners) {
            for (StatusListener listener : m_listeners) {
                // Multi-threaded version:
                /*
                m_listenerUpdateThreads.execute(new Runnable() {
                    @Override
                    public void run() {
                        listener.filesWritten(_written, _total);
                    }
                });
                */
                // Single-threaded version:
                listener.filesWritten(_written, _total);
            }
        }
    }

    public void fireFileWritten(final RepoFile _f) {
        synchronized (m_listeners) {
            for (StatusListener listener : m_listeners) {
                // Multi-threaded version:
                /*
                m_listenerUpdateThreads.execute(new Runnable() {
                    @Override
                    public void run() {
                        listener.fileWritten(_f);
                    }
                });
                */
                // Single-threaded version:
                listener.fileWritten(_f);
            }
        }
    }

    public void fireGoing(final RepoFile _f) {
        synchronized (m_listeners) {
            for (StatusListener listener : m_listeners) {
                // Multi-threaded version:
                /*
                m_listenerUpdateThreads.execute(new Runnable() {
                    @Override
                    public void run() {
                        listener.going(_f);
                    }
                });
                */
                // Single-threaded version:
                listener.going(_f);
            }
        }
    }

    public SortedSet<RepoFile> getFiles() throws IOException, SAXException, ParserConfigurationException {
        String url = m_url;
        final SortedSet<RepoFile> ret = new TreeSet<RepoFile>();
        try {
            final RepoFile repomd = new RepoFile("repodata/repomd.xml");
            final File repomdFile = repomd.download();
            ret.add(repomd);

            final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(repomdFile);
            final NodeList locationElements = doc.getElementsByTagName("location");
            RepoFile fileList = null;

            for (int i = 0; i < locationElements.getLength(); ++i) {
                final Node locationElement = locationElements.item(i);
                final NamedNodeMap attrs = locationElement.getAttributes();
                if (null == attrs) {
                    continue;
                }
                final Node href = attrs.getNamedItem("href");
                final RepoFile thisEntry = new RepoFile(href.getNodeValue());
                ret.add(thisEntry);
                if (href.getNodeValue().endsWith("-primary.xml.gz")) {
                    fileList = thisEntry;
                }
            }
            if (null == fileList) {
                throw new IOException("Can't determine file list");
            }
            try (GZIPInputStream fileListXmlStream = new GZIPInputStream(new FileInputStream(fileList.download()))) {
                final Document primaryXmlDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(fileListXmlStream);
                final NodeList children = primaryXmlDoc.getElementsByTagName("location");
                for (int i = 0; i < children.getLength(); ++i) {
                    final Node child = children.item(i);
                    final Node childHref = child.getAttributes().getNamedItem("href");
                    if (null != childHref) {
                        ret.add(new RepoFile(childHref.getNodeValue()));
                    }
                }
            }
        } 
        catch (Exception ex) {
        }
        return ret;

    }
    public void saveToZip(final File _dest) throws FileNotFoundException, IOException, SAXException, ParserConfigurationException {
        final SortedSet<RepoFile> files = getFiles();
        int numFiles = files.size();
        int numFilesWritten = 0;
        fireFilesWritten(numFilesWritten, numFiles);
        // Submit downloads in parallel
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (final RepoFile f : files) {
            futures.add(m_downloadThreads.submit(new Runnable() {
                public void run() {
                    f.preDownload();
                }
            }));
        }
        // Wait for all downloads to finish
        for (java.util.concurrent.Future<?> future : futures) {
            try { future.get(); } catch (Exception e) { throw new IOException(e); }
        }
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(_dest), 1024 * 1024 * 32))) {
            for (final RepoFile f : files) {
                ZipEntry ze = new ZipEntry(f.getRelativeName());
                out.putNextEntry(ze);
                try (FileInputStream fis = new FileInputStream(f.download())) {
                    final byte[] buf = new byte[5 * 1024 * 1024];
                    int bytesRead;
                    while ((bytesRead = fis.read(buf)) > 0) {
                        out.write(buf, 0, bytesRead);
                    }
                    f.cleanup();
                    numFilesWritten++;
                    fireFileWritten(f);
                    fireFilesWritten(numFilesWritten, numFiles);
                }
            }
        }
    }

    public static void saveMultipleToZip(List<RepoDownloader> downloaders, List<String> repoIds, File destZip) throws IOException, SAXException, javax.xml.parsers.ParserConfigurationException {
        if (downloaders.size() != repoIds.size()) throw new IllegalArgumentException("downloaders and repoIds must be same size");
        try (ZipOutputStream combinedZip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destZip), 1024 * 1024 * 32))) {
            for (int i = 0; i < downloaders.size(); i++) {
                RepoDownloader dl = downloaders.get(i);
                // Use last segment of URL path as folder name
                String repoFolder = "repo" + (i+1); // fallback
                try {
                    String urlStr = dl.getUrl();
                    if (urlStr != null && !urlStr.isEmpty()) {
                        java.net.URL urlObj = new java.net.URL(urlStr);
                        String path = urlObj.getPath();
                        if (path != null && !path.isEmpty()) {
                            String[] segments = path.split("/");
                            for (int j = segments.length - 1; j >= 0; j--) {
                                if (!segments[j].isEmpty()) {
                                    repoFolder = segments[j];
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    // fallback to default repoFolder
                }
                SortedSet<RepoFile> files = dl.getFiles();
                // Multithreaded download for each downloader
                java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
                for (RepoFile f : files) {
                    futures.add(dl.m_downloadThreads.submit(new Runnable() {
                        public void run() {
                            f.preDownload();
                        }
                    }));
                }
                for (java.util.concurrent.Future<?> future : futures) {
                    try { future.get(); } catch (Exception e) { throw new IOException(e); }
                }
                for (RepoFile f : files) {
                    File localFile = f.download();
                    String entryName = repoFolder + "/" + f.getRelativeName();
                    combinedZip.putNextEntry(new ZipEntry(entryName));
                    try (FileInputStream fis = new FileInputStream(localFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) > 0) {
                            combinedZip.write(buf, 0, n);
                        }
                    }
                    combinedZip.closeEntry();
                    f.cleanup();
                }
            }
        }
    }

    public static void unzip(Path zip, Path dest) throws IOException {
        // Create a subfolder named after the zip file (without extension)
        String zipBaseName = zip.getFileName().toString().replaceFirst("\\.zip$", "");
        Path extractDir = dest.resolve(zipBaseName);
        Files.createDirectories(extractDir);
        try (java.util.zip.ZipInputStream zis =
                 new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = extractDir.resolve(e.getName()).normalize();
                if (!out.startsWith(extractDir)) throw new IOException("Zip path invalid!");
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
