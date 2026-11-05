package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.util.*;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.SQLExec;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;

/** Internal fixed parameterized SQL, participating in the audited Cayenne transaction. */
public final class SubmissionRepository {
    public static void execute(ObjectContext context,String sql,Map<String,?> params){SQLExec.query(sql).params(params).execute(context);}
    public Map<String,Object> find(ObjectContext context,UUID id,boolean lock){
        var rows=CayenneSql.rows(context,"""
            SELECT s.*,d.t_ili_tid AS dataset_uuid,d.title AS dataset_title,i.t_ili_tid AS issue_uuid,p.t_ili_tid AS submitted_by_uuid
            FROM di_ingest.submission s JOIN di_management.dataset d ON d.t_id=s.dataset_id
            JOIN di_management.datasetissue i ON i.t_id=s.issue_id JOIN di_management.principal p ON p.t_id=s.submitted_by
            WHERE s.id=CAST(#bind($id) AS uuid)
            """+(lock?" FOR UPDATE OF s":""),Map.of("id",id.toString()));
        if(rows.size()!=1)throw IngestionProblem.forbidden();return rows.getFirst();
    }
    public void lockDataset(ObjectContext context,UUID id){
        if(CayenneSql.rows(context,"SELECT t_id FROM di_management.dataset WHERE t_ili_tid=CAST(#bind($id) AS uuid) FOR UPDATE",Map.of("id",id.toString())).size()!=1)throw IngestionProblem.forbidden();
    }
    public void transition(ObjectContext context,UUID id,String expected,String next,String code,String safe){
        var p=new HashMap<String,Object>();p.put("id",id.toString());p.put("expected",expected);p.put("next",next);p.put("code",code);p.put("safe",safe);
        execute(context,"UPDATE di_ingest.submission SET status=#bind($next),status_version=status_version+1,last_error_code=#bind($code),safe_message=#bind($safe) WHERE id=CAST(#bind($id) AS uuid) AND status=#bind($expected)",p);
        if(!next.equals(find(context,id,false).get("status")))throw IngestionProblem.conflict("INVALID_STATE");
    }
    public void event(ObjectContext context,UUID submission,UUID execution,String type,String safe){
        var p=new HashMap<String,Object>();p.put("submission",submission.toString());p.put("execution",execution==null?null:execution.toString());p.put("type",type);p.put("safe",safe);
        execute(context,"""
            INSERT INTO di_ingest.event(submission_id,execution_id,command_id,actor_issuer,actor_subject,initiator_id,event_type,safe_message)
            VALUES(CAST(#bind($submission) AS uuid),CAST(#bind($execution) AS uuid),current_setting('di.command_id')::uuid,'','',0,#bind($type),#bind($safe))
            ON CONFLICT(submission_id,command_id,event_type) DO NOTHING
            """,p);
    }
    public static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    public static UUID uuid(Map<String,Object> row,String key){return row.get(key)==null?null:UUID.fromString(row.get(key).toString());}
}
