package ch.so.agi.dateninfrastruktur.admin.persistence;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
public final class SchemaContractHealthIndicator implements HealthIndicator {
    private final DataSource source;private final boolean web;
    public SchemaContractHealthIndicator(DataSource source){this(source,false);}
    public SchemaContractHealthIndicator(DataSource source,boolean web){this.source=source;this.web=web;}
    public Health health() {
        try(var connection=source.getConnection();var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT di_ops.runtime_contract()"+(web?" AND di_ops.management_web_contract()":""))) {
            return result.next() && result.getBoolean(1) ? Health.up().build() : Health.down().build();
        } catch(java.sql.SQLException error) {
            return Health.down().build();
        }
    }
}
