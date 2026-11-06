package ch.so.agi.dateninfrastruktur.admin.access;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.cayenne.runtime.CayenneRuntime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.AuditedUnitOfWork;
import ch.so.agi.dateninfrastruktur.admin.authorization.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
@Configuration @ConditionalOnProperty(name="di.web.enabled",havingValue="true",matchIfMissing=true)
public class ManagementServicesConfiguration {
    @Bean AuthorityService authorityService(CayenneRuntime runtime){return new AuthorityService(runtime);}
    @Bean AccessWorkflowService accessWorkflowService(AuditedUnitOfWork unit,AuthorityService authority){return new AccessWorkflowService(unit,authority);}
    @Bean ch.so.agi.dateninfrastruktur.admin.catalog.DatasetIssueService datasetIssueService(AuditedUnitOfWork unit,AuthorityService authority){return new ch.so.agi.dateninfrastruktur.admin.catalog.DatasetIssueService(unit,authority);}
    @Bean CatalogCommandService catalogCommandService(AuditedUnitOfWork unit,AuthorityService authority){return new CatalogCommandService(unit,authority);}
    @Bean(destroyMethod="close") @ConfigurationProperties("di.management.audit")
    HikariDataSource managementAuditDataSource(){var ds=new HikariDataSource();ds.setMaximumPoolSize(3);ds.setMinimumIdle(0);ds.setReadOnly(true);ds.setPoolName("management-audit-reader");return ds;}
    @Bean ManagementQueryService managementQueryService(CayenneRuntime runtime,AuthorityService authority,@Qualifier("managementAuditDataSource")HikariDataSource audit){return new ManagementQueryService(runtime,authority,audit);}
    @Bean org.springframework.boot.ApplicationRunner verifyWebContract(@Qualifier("managementAuditDataSource")HikariDataSource audit,@Qualifier("managementWriterDataSource")HikariDataSource writer){
        return args->{
            try(var c=audit.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT current_user")){
                if(!r.next() || !"di_audit_reader".equals(r.getString(1)))throw new IllegalStateException("Audit views require their dedicated read-only role");
            }
            try(var c=writer.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT di_ops.management_web_contract()")){
                if(!r.next() || !r.getBoolean(1))throw new IllegalStateException("Current management web contract is required before web startup");
            }
        };
    }
    @Bean DownloadAuthorizationService downloadAuthorizationService(@Qualifier("managementAuthorizationDataSource")HikariDataSource source){return new DownloadAuthorizationService(new JdbcAuthorizationRepository(source));}
}
