package ch.so.agi.dateninfrastruktur.admin;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HostDevelopmentProfileTest {
    @Test
    void bindsOnlyToLoopback() throws IOException {
        var profile = new Properties();
        try (var stream = getClass().getResourceAsStream("/application-host-dev.properties")) {
            assertNotNull(stream);
            profile.load(stream);
        }
        assertEquals("127.0.0.1", profile.getProperty("server.address"));
    }
}
