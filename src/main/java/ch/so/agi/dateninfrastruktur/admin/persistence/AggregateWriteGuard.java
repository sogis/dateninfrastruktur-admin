package ch.so.agi.dateninfrastruktur.admin.persistence;
import java.util.Map;
import java.util.UUID;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.SQLExec;
import org.apache.cayenne.tx.BaseTransaction;
public final class AggregateWriteGuard {
    public long lockDataset(ObjectContext context,UUID oid,long expectedVersion) {
        return lock(context,"dataset",oid,expectedVersion);
    }
    public long lockRequest(ObjectContext context,UUID oid,long expectedVersion) {
        return lock(context,"accessrequest",oid,expectedVersion);
    }
    private long lock(ObjectContext context,String table,UUID oid,long expectedVersion) {
        if(BaseTransaction.getThreadTransaction()==null)
            throw new IllegalStateException("Aggregate lock requires the explicit outer transaction");
        // table is exclusively selected by the two fixed methods above.
        var rows=SQLExec.query("SELECT t_id,row_version FROM di_management."+table+
            " WHERE t_ili_tid=CAST(#bind($oid) AS uuid) FOR UPDATE")
            .params("oid",oid.toString()).execute(context).firstList();
        if(rows.size()!=1)throw new MissingAggregateException();
        var row=(Map<?,?>)rows.getFirst();
        if(((Number)row.get("row_version")).longValue()!=expectedVersion)
            throw new StaleAggregateException();
        return ((Number)row.get("t_id")).longValue();
    }
    public static final class MissingAggregateException extends RuntimeException {
        public MissingAggregateException(){super("Aggregate not found");}
    }
    public static final class StaleAggregateException extends RuntimeException {
        public StaleAggregateException(){super("Aggregate revision changed");}
    }
}
