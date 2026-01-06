package com.github.theprez.repotool;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RepoPickerPanel extends JPanel {
    private static final String BASE = "https://public.dhe.ibm.com/software/ibmi/products/pase/rpms/";

    private final DefaultListModel<String> availableModel = new DefaultListModel<>();
    private final DefaultListModel<String> selectedModel  = new DefaultListModel<>();
    private final JList<String> availableList = new JList<>(availableModel);
    private final JList<String> selectedList  = new JList<>(selectedModel);
    private final JButton btnFetch = new JButton("Fetch Repos");
    private final JButton btnAdd = new JButton(">");
    private final JButton btnAddAll = new JButton(">>");
    private final JButton btnRemove = new JButton("<");
    private final JButton btnRemoveAll = new JButton("<<");
    private final JLabel lblBaseUrl = new JLabel();

    public RepoPickerPanel() {
        setLayout(new BorderLayout(10,10));
        setBorder(new CompoundBorder(new EmptyBorder(10,10,10,10),
                BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Select Repositories")));

        // Left & right lists
        availableList.setVisibleRowCount(12);
        selectedList.setVisibleRowCount(12);
        availableList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        selectedList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JPanel center = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5,5,5,5);

        // Available (left)
        g.gridx = 0; g.gridy = 0; g.weightx = 0.5; g.weighty = 1; g.fill = GridBagConstraints.BOTH;
        center.add(wrap("Available", new JScrollPane(availableList)), g);

        // Buttons (middle)
        JPanel mid = new JPanel(new GridLayout(4,1,5,5));
        mid.add(btnAdd);
        mid.add(btnAddAll);
        mid.add(btnRemove);
        mid.add(btnRemoveAll);
        g.gridx = 1; g.gridy = 0; g.weightx = 0; g.fill = GridBagConstraints.NONE; g.anchor = GridBagConstraints.CENTER;
        center.add(mid, g);

        // Selected (right)
        g.gridx = 2; g.gridy = 0; g.weightx = 0.5; g.weighty = 1; g.fill = GridBagConstraints.BOTH;
        center.add(wrap("Selected", new JScrollPane(selectedList)), g);

        // Top bar: Fetch + manual add
        JPanel top = new JPanel(new BorderLayout(8,8));
        top.add(btnFetch, BorderLayout.WEST);
        JPanel manual = new JPanel(new BorderLayout(6,6));
        manual.add(new JLabel("Base URL:"), BorderLayout.WEST);
        lblBaseUrl.setText("https://public.dhe.ibm.com/software/ibmi/products/pase/rpms/");
        manual.add(lblBaseUrl, BorderLayout.CENTER);
        top.add(manual, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);

        // Wire buttons
        btnFetch.addActionListener(e -> fetchInBackground());
        btnAdd.addActionListener(e -> {
            List<String> selected = availableList.getSelectedValuesList();
            moveSelected(availableList, availableModel, selectedModel);
        });
        btnRemove.addActionListener(e -> moveSelected(selectedList, selectedModel, availableModel));
        btnAddAll.addActionListener(e -> moveAll(availableModel, selectedModel));
        btnRemoveAll.addActionListener(e -> moveAll(selectedModel, availableModel));

        // Double-click to move
        availableList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) moveSelected(availableList, availableModel, selectedModel);
            }
        });
        selectedList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) moveSelected(selectedList, selectedModel, availableModel);
            }
        });

        // ...removed manual add logic...
    }

    // Fetch the directory listing, parse folder names, and populate Available
    private void fetchInBackground() {
        btnFetch.setEnabled(false);
        availableModel.clear();

        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                // Fetch HTML directory listing
                HttpURLConnection conn = (HttpURLConnection) new URL(BASE).openConnection();
                conn.setRequestProperty("User-Agent", "RepoSnapshotTool/1.0");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    // Parse href="..." for both files and folders
                    Pattern p = Pattern.compile("href\\s*=\\s*\"([^\"]+)\"");
                    Matcher m = p.matcher(sb.toString());
                    Set<String> names = new HashSet<>();
                    while (m.find()) {
                        String href = m.group(1);
                        if (href == null) continue;
                        // Skip parent directory links and query/sort links
                        if (href.equals("../")) continue;
                        if (href.startsWith("?")) continue;
                        // Only top-level (not containing another /)
                        String name = href.endsWith("/") ? href.substring(0, href.length()-1) : href;
                        if (!name.contains("/")) {
                            names.add(name);
                        }
                    }
                    List<String> out = new ArrayList<>(names);
                    Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
                    return out;
                }
            }

            @Override protected void done() {
                btnFetch.setEnabled(true);
                try {
                    List<String> names = get();
                    for (String n : names) if (!contains(selectedModel, n)) availableModel.addElement(n);
                    sortModel(availableModel);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(RepoPickerPanel.this,
                            "Failed to fetch repos:\n" + ex.getMessage(),
                            "Fetch Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static JPanel wrap(String title, JComponent comp) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new CompoundBorder(new EmptyBorder(0,0,0,0),
                BorderFactory.createTitledBorder(title)));
        p.add(comp, BorderLayout.CENTER);
        return p;
    }

    private static boolean contains(DefaultListModel<String> m, String s) {
        for (int i=0;i<m.size();i++) if (m.get(i).equalsIgnoreCase(s)) return true;
        return false;
    }
    private static void moveSelected(JList<String> srcList, DefaultListModel<String> src, DefaultListModel<String> dst) {
        List<String> vals = srcList.getSelectedValuesList();
        for (String v : vals) {
            if (!contains(dst, v)) dst.addElement(v);
            for (int i=0;i<src.size();i++) if (src.get(i).equals(v)) { src.remove(i); break; }
        }
        sortModel(dst);
    }
    private static void moveAll(DefaultListModel<String> src, DefaultListModel<String> dst) {
        List<String> vals = new ArrayList<>();
        for (int i=0;i<src.size();i++) vals.add(src.get(i));
        for (String v : vals) if (!contains(dst, v)) dst.addElement(v);
        src.clear();
        sortModel(dst);
    }
    private static void sortModel(DefaultListModel<String> m) {
        List<String> vals = new ArrayList<>();
        for (int i=0;i<m.size();i++) vals.add(m.get(i));
        Collections.sort(vals, String.CASE_INSENSITIVE_ORDER);
        m.clear();
        for (String v : vals) m.addElement(v);
    }

    // Returns full URLs for the selected repos 
    public List<String> getSelectedRepoUrls() {
        List<String> urls = new ArrayList<>();
        for (int i=0;i<selectedModel.size();i++) {
            String s = selectedModel.get(i);
            if (s.startsWith("http://") || s.startsWith("https://")) {
                urls.add(s.endsWith("/") ? s : (s + "/"));
            } else {
                urls.add(BASE + s + "/");
            }
        }
        return urls;
    }

    // Returns repo IDs (folder names) for selected entries
    public List<String> getSelectedRepoIds() {
        List<String> ids = new ArrayList<>();
        for (int i=0;i<selectedModel.size();i++) {
            String s = selectedModel.get(i);
            if (s.startsWith("repo-")) {
                ids.add(s);
            } else if (s.startsWith("http")) {
                // try to extract repo-<ver> from URL
                String tail = s.replaceAll(".*/(repo-[0-9.]+)/?$", "$1");
                ids.add(tail.equals(s) ? "custom" : tail);
            } else {
                ids.add(s); // whatever user typed, use as ID
            }
        }
        return ids;
    }
}
