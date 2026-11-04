package ch.so.agi.dateninfrastruktur.admin.persistence;
import javax.sql.DataSource;
import org.apache.cayenne.runtime.CayenneRuntime;
public final class ManagementRuntime {
    private ManagementRuntime(){}
    public static CayenneRuntime create(DataSource source) {
        try(var connection=source.getConnection();var statement=connection.createStatement();
            var result=statement.executeQuery(
                "SELECT current_user='di_writer' AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole " +
                "AND NOT has_table_privilege(current_user,'di_management.dataset','TRUNCATE') " +
                "FROM pg_roles WHERE rolname=current_user")) {
            if(!result.next() || !result.getBoolean(1))
                throw new IllegalStateException("Management runtime requires the restricted di_writer account");
        } catch(java.sql.SQLException e) {
            throw new IllegalStateException("Unable to verify management writer boundary",e);
        }
        var runtime=CayenneRuntime.of().addConfig("cayenne/cayenne-management.xml").defaultDataNode(source).build();
        for(var node:runtime.getDataDomain().getDataNodes()) node.setPkGenerator(new IliPkGenerator(node.getAdapter()));
        if(runtime.getDataDomain().isSharedCacheEnabled()) {runtime.shutdown();throw new IllegalStateException("Shared management cache must be disabled");}
        return runtime;
    }
}
