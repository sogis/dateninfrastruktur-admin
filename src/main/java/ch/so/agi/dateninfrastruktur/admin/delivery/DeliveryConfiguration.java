package ch.so.agi.dateninfrastruktur.admin.delivery;

import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;
import ch.so.agi.dateninfrastruktur.admin.access.SubmitterAdministrationService;
import org.apache.cayenne.runtime.CayenneRuntime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="di.web.enabled",havingValue="true",matchIfMissing=true)
public class DeliveryConfiguration {
    @Bean
    SubmitterAdministrationService submitters(CayenneRuntime runtime, AuthorityService authority) {
        return new SubmitterAdministrationService(runtime, authority);
    }

    @Bean
    DeliveryManagement deliveryManagement(CayenneRuntime runtime, AuthorityService authority, SubmitterAdministrationService submitters) {
        return new DeliveryManagement(runtime, authority, submitters);
    }

    @Bean
    DeliveryService deliveryService(CayenneRuntime runtime, AuthorityService authority,
            @Value("${di.delivery.candidate-root:}") String directory) {
        return new DeliveryService(runtime, authority, new CandidateFiles(directory.isBlank() ? null : Path.of(directory)));
    }
}
