package ch.so.agi.dateninfrastruktur.admin.ingestion;
import ch.so.agi.dateninfrastruktur.admin.persistence.AuditedUnitOfWork;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration @EnableScheduling
@ConditionalOnProperty(name={"di.ingestion.enabled","di.ingestion.projection-enabled"},havingValue="true")
class ProjectionConfiguration {
    @Bean PublicationReconciler publicationReconciler(AuditedUnitOfWork unit,@Value("${DI_CH_PROJECTOR_PASSWORD}") String password){
        return new PublicationReconciler(unit,new ClickHousePublicationProjector(password));
    }
}
