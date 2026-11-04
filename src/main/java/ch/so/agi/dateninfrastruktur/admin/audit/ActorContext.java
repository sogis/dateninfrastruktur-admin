package ch.so.agi.dateninfrastruktur.admin.audit;
import java.util.Objects;
import java.util.UUID;
/** Construct from verified identity only; never bind this record from request parameters. */
public record ActorContext(String issuer, String subject, String kind, UUID principalOid) {
    public ActorContext {
        Objects.requireNonNull(issuer); Objects.requireNonNull(subject);
        if (issuer.isBlank() || subject.isBlank() || !("user".equals(kind) || "service".equals(kind)))
            throw new IllegalArgumentException("Invalid verified actor");
    }
}
