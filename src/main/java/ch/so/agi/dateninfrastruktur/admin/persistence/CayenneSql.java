package ch.so.agi.dateninfrastruktur.admin.persistence;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.SQLExec;
/** Internal fixed SQL only. SQLExec participates in the current outer transaction. */
public final class CayenneSql {
    private CayenneSql(){}
    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> rows(ObjectContext context,String sql,Map<String,?> parameters) {
        var result=SQLExec.query(sql).params(parameters).execute(context).firstList();
        return (List<Map<String,Object>>)(List<?>)result;
    }
    public static LocalDateTime now(ObjectContext context) {
        var rows=rows(context,"SELECT to_char(timezone('UTC',clock_timestamp()),'YYYY-MM-DD\"T\"HH24:MI:SS.US') AS now_utc",Map.of());
        return LocalDateTime.parse(rows.getFirst().get("now_utc").toString());
    }
}
