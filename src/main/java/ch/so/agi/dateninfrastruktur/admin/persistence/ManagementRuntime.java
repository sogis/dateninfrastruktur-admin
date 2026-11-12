package ch.so.agi.dateninfrastruktur.admin.persistence;
import javax.sql.DataSource;
import org.apache.cayenne.runtime.CayenneRuntime;
public final class ManagementRuntime {
    private ManagementRuntime(){}
    public static CayenneRuntime create(DataSource source) {
        ManagementDatabase.require(source);
        var runtime=CayenneRuntime.of().addConfig("cayenne/cayenne-management.xml").defaultDataNode(source).build();
        if(runtime.getDataDomain().isSharedCacheEnabled()) {runtime.shutdown();throw new IllegalStateException("Shared management cache must be disabled");}
        return runtime;
    }
}
