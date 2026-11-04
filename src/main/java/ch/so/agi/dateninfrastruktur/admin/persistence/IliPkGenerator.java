package ch.so.agi.dateninfrastruktur.admin.persistence;
import org.apache.cayenne.access.DataNode;
import org.apache.cayenne.dba.DbAdapter;
import org.apache.cayenne.dba.PkGenerator;
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.tx.BaseTransaction;
import java.sql.SQLException;
import java.util.List;
/** One allocation from ili2pg's verified shared sequence, on the existing command connection. */
public final class IliPkGenerator implements PkGenerator {
    private final DbAdapter adapter;
    public IliPkGenerator(DbAdapter adapter) { this.adapter=adapter; }
    public Object generatePk(DataNode node, DbAttribute attribute, Class<?> javaType) {
        if (!"t_id".equals(attribute.getName()) || !"di_management".equals(attribute.getEntity().getSchema()))
            throw new IllegalStateException("Unexpected ili2pg key mapping");
        var transaction=BaseTransaction.getThreadTransaction();
        if(transaction==null) throw new IllegalStateException("Explicit audited transaction required for PK allocation");
        try {
            // Do not close the transaction-owned connection; only its statement/result.
            var connection=transaction.getOrCreateConnection(node.getName(),node.getDataSource());
            try(var statement=connection.prepareStatement("SELECT nextval('di_management.t_ili2db_seq')");var rs=statement.executeQuery()) {
                if(!rs.next())throw new IllegalStateException("ili2pg sequence returned no key");return rs.getLong(1);
            }
        } catch(SQLException e) {throw new IllegalStateException("ili2pg key allocation failed",e);}
    }
    public DbAdapter getAdapter(){return adapter;}
    public void reset(){}
    public void createAutoPk(DataNode n,List<DbEntity> e){throw new UnsupportedOperationException("No runtime DDL");}
    public void dropAutoPk(DataNode n,List<DbEntity> e){throw new UnsupportedOperationException("No runtime DDL");}
    public List<String> createAutoPkStatements(List<DbEntity> e){throw new UnsupportedOperationException("ili2pg owns the sequence");}
    public List<String> dropAutoPkStatements(List<DbEntity> e){throw new UnsupportedOperationException("ili2pg owns the sequence");}
}
