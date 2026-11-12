package ch.so.agi.dateninfrastruktur.admin.access;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.cayenne.runtime.CayenneRuntime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import ch.so.agi.dateninfrastruktur.admin.authorization.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
@Configuration @ConditionalOnProperty(name="di.web.enabled",havingValue="true",matchIfMissing=true)
public class ManagementServicesConfiguration {
    @Bean AuthorityService authorityService(CayenneRuntime runtime){return new AuthorityService(runtime);}
    @Bean AccessWorkflowService accessWorkflowService(CayenneRuntime runtime,AuthorityService authority){return new AccessWorkflowService(runtime,authority);}
    @Bean ch.so.agi.dateninfrastruktur.admin.catalog.DatasetIssueService datasetIssueService(CayenneRuntime runtime,AuthorityService authority){return new ch.so.agi.dateninfrastruktur.admin.catalog.DatasetIssueService(runtime,authority);}
    @Bean CatalogCommandService catalogCommandService(CayenneRuntime runtime,AuthorityService authority){return new CatalogCommandService(runtime,authority);}
    @Bean ManagementQueryService managementQueryService(CayenneRuntime runtime, AuthorityService authority) {
        return new ManagementQueryService(runtime, authority);
    }
    @Bean DownloadAuthorizationService downloadAuthorizationService(@Qualifier("managementWriterDataSource")HikariDataSource source){return new DownloadAuthorizationService(new JdbcAuthorizationRepository(source));}
}
