package com.github.theprez.config;


import com.github.theprez.config.data.Repo;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class RpmConfigParserTest {
    @Test
    public void testParseValidConfig() {
        // Example config file path; adjust as necessary
        String configPath = "config.yaml";
        RpmConfigParser parser = new RpmConfigParser(configPath);
        Map<Integer, Repo> repos = parser.get_repos();
        assertNotNull("Repos map should not be null", repos);
        assertFalse("Repos map should not be empty", repos.isEmpty());
    }

    @Test
    public void testParseInvalidConfig() {
        String invalidPath = "nonexistent.yaml";
        boolean threw = false;
        try {
            new RpmConfigParser(invalidPath);
        } catch (Exception e) {
            threw = true;
        }
        assertTrue("Expected exception for invalid config path", threw);
    }
}
