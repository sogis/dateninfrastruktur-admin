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
            byte[] contract;
            try(var stream=ManagementConfiguration.class.getResourceAsStream("/management/persistence-contract.properties")) {
                if(stream==null)throw new IllegalStateException("Missing packaged persistence contract");
                contract=stream.readAllBytes();
            }
            var properties=new java.util.Properties();
            properties.load(new java.io.ByteArrayInputStream(contract));
            try(var map=ManagementConfiguration.class.getResourceAsStream("/cayenne/management.map.xml")) {
                if(map==null || !properties.getProperty("datamap.sha256").equals(
                    java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(map.readAllBytes()))))
                    throw new IllegalStateException("Packaged Cayenne mapping differs from persistence contract");
            }
            String fingerprint=java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(contract));
            try(var connection=writer.getConnection();
                var statement=connection.createStatement();
                var result=statement.executeQuery("SELECT di_ops.runtime_contract(),di_ops.contract_fingerprint()")) {
                if(!result.next() || !result.getBoolean(1) || !fingerprint.equals(result.getString(2)))
                    throw new IllegalStateException("Management schema/audit contract is not ready; run explicit setup");
            }
            try(var connection=authorization.getConnection();
                var statement=connection.createStatement();
                var result=statement.executeQuery("SELECT current_user")) {
                if(!result.next() || !"di_authz".equals(result.getString(1)))
                    throw new IllegalStateException("Authorization must use its dedicated read-only account");
            }
        };
    }
}
