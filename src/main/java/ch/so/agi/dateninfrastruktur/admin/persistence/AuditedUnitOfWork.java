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
    public <T> T read(Function<ObjectContext,T> query) {
        if(org.apache.cayenne.tx.BaseTransaction.getThreadTransaction()!=null)throw new IllegalStateException("Read boundary must not nest in a write command");
        return transaction(()->{
            var context=runtime.newContext();
            org.apache.cayenne.query.SQLExec.query("SET TRANSACTION READ ONLY").execute(context);
            return query.apply(context);
        });
    }
    public <T> T execute(ActorContext actor, CommandContext command, Function<ObjectContext,T> work) {
        return execute(actor,command,false,work);
    }
    /** Only the central ingestion finalizer uses shared authorization ordering. */
    public <T> T executeFinalization(ActorContext actor, CommandContext command, Function<ObjectContext,T> work) {
        return execute(actor,command,true,work);
    }
    private <T> T transaction(java.util.function.Supplier<T> work){
        try{return runtime.performInTransaction(work::get);}
        catch(org.apache.cayenne.CayenneRuntimeException error){
            // Cayenne has already rolled back/marked rollback-only. Preserve typed domain failures.
            RuntimeException failure=error;
            while(failure instanceof org.apache.cayenne.CayenneRuntimeException && failure.getCause() instanceof RuntimeException cause)failure=cause;
            throw failure;
        }
    }
    private <T> T execute(ActorContext actor, CommandContext command, boolean finalization,Function<ObjectContext,T> work) {
        return transaction(() -> {
            ObjectContext context=runtime.newContext();
            writer.bind(context,actor,command);
            // Always first: authorization -> dataset -> submission/execution.
            // The same physical Cayenne transaction owns this row lock and flush.
            var guard=CayenneSql.rows(context,"SELECT revision FROM di_ops.authorization_guard WHERE singleton "+
                (finalization?"FOR SHARE":"FOR UPDATE"),java.util.Map.of());
            if(guard.size()!=1)throw new IllegalStateException("Explicit ingestion schema upgrade required");
            T result=work.apply(context);
            context.commitChanges();
            return result;
        });
    }
}
