package ch.so.agi.dateninfrastruktur.admin.persistence;

import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import org.apache.cayenne.CayenneRuntimeException;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.runtime.CayenneRuntime;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public final class ManagementTransactions {
    private ManagementTransactions() {}

    public static <T> T write(CayenneRuntime runtime, Function<ObjectContext, T> operation) {
        try {
            return runtime.performInTransaction(() -> {
                var context = runtime.newContext();
                T result = operation.apply(context);
                context.commitChanges();
                return result;
            });
        } catch (CayenneRuntimeException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof BusinessException business) throw business;
            }
            throw error;
        }
    }

    public static void lockCommand(ObjectContext context, UUID commandId) {
        CayenneSql.rows(context, "SELECT pg_advisory_xact_lock(hashtextextended(#bind($id), 181))",
            Map.of("id", commandId.toString()));
    }

    public static void lockDataset(ObjectContext context, UUID id) {
        lock(context, "dataset", id);
    }

    public static void lockIssue(ObjectContext context, UUID id) {
        lock(context, "datasetissue", id);
    }

    private static void lock(ObjectContext context, String table, UUID id) {
        if (CayenneSql.rows(context, "SELECT t_id FROM di_management." + table
                + " WHERE t_ili_tid=CAST(#bind($id) AS uuid) FOR UPDATE", Map.of("id", id.toString())).isEmpty()) {
            throw BusinessException.missing();
        }
    }
}
