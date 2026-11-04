package ch.so.agi.dateninfrastruktur.admin.authorization;
import java.util.Optional;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
public final class DownloadAuthorizationService {
    private final JdbcAuthorizationRepository repository;
    public DownloadAuthorizationService(JdbcAuthorizationRepository repository){this.repository=repository;}
    public JdbcAuthorizationRepository.Decision decide(Optional<ActorContext> actor,DownloadPathResolver.DownloadTarget target){
        return repository.evaluate(actor,target);
    }
}
