package com.github.theprez.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import com.github.theprez.config.data.Repo;

public class RpmConfigParser {

    private String m_config;
    private int m_repoCount;
    private Map<Integer, Repo> m_repos;

    public int get_repoCount() {
        return m_repoCount;
    }

    public Map<Integer, Repo> get_repos() {
        return m_repos;
    }

    public String get_config() {
        return m_config;
    }

    public RpmConfigParser(final String _config) {
        this.m_config = _config;
        this.m_repos = new HashMap<Integer, Repo>();
        try {
            this.parseConfig();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse config: " + _config, e);
        }
    }

    
    private void parseConfig() throws Exception {
        Yaml configYaml = new Yaml(new Constructor(Repo.class));
        InputStream inputStream = new FileInputStream(new File(m_config));
        m_repoCount = 0;
        for (Object repo : configYaml.loadAll(inputStream)) {
            if (repo == null) {
                continue;
            }
            m_repos.put(m_repoCount, (Repo) repo);
            m_repoCount++;
        }

    }
    
}
