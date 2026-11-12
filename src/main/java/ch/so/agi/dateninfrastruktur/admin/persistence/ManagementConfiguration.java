package ch.so.agi.dateninfrastruktur.admin.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.cayenne.runtime.CayenneRuntime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@ConditionalOnProperty(name="di.management.enabled",havingValue="true",matchIfMissing=true)
public class ManagementConfiguration {
    @Bean(destroyMethod="close") @Primary
    @ConfigurationProperties("di.management.writer")
    public HikariDataSource managementWriterDataSource() {
        var source=new HikariDataSource();
        source.setMaximumPoolSize(8);source.setMinimumIdle(0);source.setPoolName("management-writer");
        return source;
    }
    @Bean(destroyMethod="shutdown")
    public CayenneRuntime managementRuntime(
            @Qualifier("managementWriterDataSource") HikariDataSource source) {
        return ManagementRuntime.create(source);
    }
    @Bean
    public AuditedUnitOfWork auditedUnitOfWork(CayenneRuntime runtime) {
        return new AuditedUnitOfWork(runtime);
    }
    @Bean
    public ManagementSchemaHealth managementSchema(
            @Qualifier("managementWriterDataSource") HikariDataSource source) {
        return new ManagementSchemaHealth(source);
    }
}
