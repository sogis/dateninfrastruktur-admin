package ch.so.agi.dateninfrastruktur.admin.persistence;

import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class ManagementSchemaHealth implements HealthIndicator {
    private final DataSource source;

    public ManagementSchemaHealth(DataSource source) {
        this.source = source;
    }

    @Override
    public Health health() {
        try {
            ManagementDatabase.require(source);
            return Health.up().build();
        } catch (IllegalStateException error) {
            return Health.down().build();
        }
    }
}
