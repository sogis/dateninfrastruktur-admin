package ch.so.agi.dateninfrastruktur.admin.ingestion;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.apache.cayenne.runtime.CayenneRuntime;
import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;
import ch.so.agi.dateninfrastruktur.admin.persistence.AuditedUnitOfWork;
import ch.so.agi.dateninfrastruktur.admin.catalog.DeliveryRegistrationService;

@Configuration
public class IngestionConfiguration {
    @Bean CsvDefinitionRegistry csvDefinitions(){return new CsvDefinitionRegistry();}
    @Bean DeliveryConfigurationQuery deliveryConfigurations(CsvDefinitionRegistry registry){return new DeliveryConfigurationQuery(registry);}
    @Bean SubmissionAuthorizationService submissionAuthorization(CayenneRuntime runtime,AuthorityService authority,DeliveryConfigurationQuery configurations){return new SubmissionAuthorizationService(runtime,authority,configurations);}
    @Bean IdentityProvisioningService ingestionIdentities(AuditedUnitOfWork unit){return new IdentityProvisioningService(unit);}
    @Bean SubmissionService submissions(AuditedUnitOfWork unit,SubmissionAuthorizationService users,@Value("${di.ingestion.plugin-secret}")String secret,@Value("${di.ingestion.max-upload-bytes:104857600}")long limit){return new SubmissionService(unit,users,new SpoolProof(secret),limit);}
    @Bean SubmissionQueryService submissionQueries(AuditedUnitOfWork unit,SubmissionAuthorizationService users){return new SubmissionQueryService(unit,users);}
    @Bean IngestionRunnerAuthorization ingestionRunners(){return new IngestionRunnerAuthorization();}
    @Bean CsvExecutionPlanFactory executionPlans(){return new CsvExecutionPlanFactory();}
    @Bean DispatchService dispatches(AuditedUnitOfWork unit,SubmissionAuthorizationService users,IngestionRunnerAuthorization runners,@Value("${di.ingestion.jenkins-instance}")String instance){return new DispatchService(unit,users,runners,instance);}
    @Bean ExecutionService executions(AuditedUnitOfWork unit,SubmissionAuthorizationService users,IngestionRunnerAuthorization runners,CsvExecutionPlanFactory plans,@Value("${di.ingestion.jenkins-instance}")String instance,@Value("${di.ingestion.lease-secret}")String secret){return new ExecutionService(unit,users,runners,plans,instance,secret.getBytes(StandardCharsets.UTF_8));}
    @Bean PublicationService publications(AuditedUnitOfWork unit,ExecutionService executions,AuthorityService authority){return new PublicationService(unit,executions,new DeliveryRegistrationService(unit,authority));}
    @Bean DeliveryConfigurationService deliveryConfigurationCommands(AuditedUnitOfWork unit,AuthorityService authority,CsvDefinitionRegistry registry){return new DeliveryConfigurationService(unit,authority,registry);}
    @Bean DeliveryManagementQuery deliveryManagementQuery(AuditedUnitOfWork unit,SubmitterAdministrationService managers,SubmissionAuthorizationService users,AuthorityService authority){return new DeliveryManagementQuery(unit,managers,users,authority);}
    @Bean SubmitterAdministrationService submitterAdministration(AuditedUnitOfWork unit,AuthorityService authority,SubmissionAuthorizationService users){return new SubmitterAdministrationService(unit,authority,users);}
}
