package ch.so.agi.dateninfrastruktur.admin.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.cayenne.runtime.CayenneRuntime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
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
    @Bean(destroyMethod="close")
    @ConfigurationProperties("di.management.authorization")
    public HikariDataSource managementAuthorizationDataSource() {
        var source=new HikariDataSource();
        source.setMaximumPoolSize(8);source.setMinimumIdle(0);source.setReadOnly(true);
        source.setPoolName("management-authorization");
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
    public SchemaContractHealthIndicator managementSchema(
            @org.springframework.beans.factory.annotation.Value("${di.web.enabled:true}") boolean web,
            @Qualifier("managementWriterDataSource") HikariDataSource source) {
        return new SchemaContractHealthIndicator(source,web);
    }
    @Bean
    public ApplicationRunner verifyManagementContract(
            @Qualifier("managementWriterDataSource") HikariDataSource writer,
            @Qualifier("managementAuthorizationDataSource") HikariDataSource authorization) {
        return args -> {
            RuntimeContractVerifier.require(writer);
            try(var connection=authorization.getConnection();
                var statement=connection.createStatement();
                var result=statement.executeQuery("SELECT current_user")) {
                if(!result.next() || !"di_authz".equals(result.getString(1)))
                    throw new IllegalStateException("Authorization must use its dedicated read-only account");
            }
        };
    }
}
