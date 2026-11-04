package ch.so.agi.dateninfrastruktur.admin.persistence;
import java.util.function.Function;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.runtime.CayenneRuntime;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
/** A command completes only after the physical outer transaction commits. */
public final class AuditedUnitOfWork {
    private final CayenneRuntime runtime;
    private final AuditContextWriter writer = new AuditContextWriter();
    public AuditedUnitOfWork(CayenneRuntime runtime) { this.runtime=runtime; }
    public <T> T execute(ActorContext actor, CommandContext command, Function<ObjectContext,T> work) {
        return runtime.performInTransaction(() -> {
            ObjectContext context=runtime.newContext();
            writer.bind(context,actor,command);
            T result=work.apply(context);
            context.commitChanges();
            return result;
        });
    }
}
