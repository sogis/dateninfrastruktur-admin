package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.util.UUID;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.persistence.AuditedUnitOfWork;

/** Called only after cryptographic user-token verification, never from body/header identity. */
public final class IdentityProvisioningService {
    private final AuditedUnitOfWork unit;private final PrincipalRepository principals=new PrincipalRepository();
    public IdentityProvisioningService(AuditedUnitOfWork unit){this.unit=unit;}
    public UUID ensureUser(ActorContext verified){
        if(!"user".equals(verified.kind()))throw IngestionProblem.forbidden();
        var command=new CommandContext(UUID.randomUUID(),UUID.randomUUID(),"identity.ensure_user","verified identity contact");
        return unit.execute(verified,command,context->principals.findOrRegisterVerifiedUser(context,verified,command).getTIliTid());
    }
}
