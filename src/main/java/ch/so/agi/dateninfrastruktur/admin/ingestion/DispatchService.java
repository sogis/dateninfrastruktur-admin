package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.util.*;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import org.apache.cayenne.ObjectContext;

/** Durable at-least-once delivery; the dataset slot and winning claim carry authority. */
public final class DispatchService {
    public static final String JOB="di-csv-ingest";
    public record Request(UUID submissionId){}
    public record Reserved(UUID dispatchId,UUID submissionId,String jobName,String state,String queueId){}
    private final AuditedUnitOfWork unit;private final SubmissionAuthorizationService users;private final IngestionRunnerAuthorization runners;private final String instance;
    private final SubmissionRepository repository=new SubmissionRepository();
    public DispatchService(AuditedUnitOfWork unit,SubmissionAuthorizationService users,IngestionRunnerAuthorization runners,String instance){
        if(instance==null || !instance.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("Bound Jenkins instance required");this.unit=unit;this.users=users;this.runners=runners;this.instance=instance;
    }
    public Reserved reserve(ActorContext runner,CommandContext command,Request request){
        if(!"ingestion.reserve".equals(command.actionCode()))throw IngestionProblem.invalid("INVALID_INPUT");
        String digest=CanonicalJson.digest(request);
        return unit.execute(runner,command,context->{
            long principal=runners.require(context,runner);
            var prior=CayenneSql.rows(context,"SELECT * FROM di_ingest.dispatch WHERE reserved_by=#bind($runner) AND reservation_command_id=CAST(#bind($command) AS uuid)",Map.of("runner",principal,"command",command.commandId().toString()));
            if(!prior.isEmpty()){
                var row=prior.getFirst();if(!digest.equals(row.get("request_digest")) || !instance.equals(row.get("jenkins_instance")))throw IngestionProblem.conflict("IDEMPOTENCY_CONFLICT");return result(row);
            }
            String selection="""
                SELECT s.id,d.t_ili_tid AS dataset_uuid FROM di_ingest.submission s JOIN di_management.dataset d ON d.t_id=s.dataset_id
                WHERE s.status='READY'
                """;
            var params=new HashMap<String,Object>();
            if(request.submissionId()!=null){selection+=" AND s.id=CAST(#bind($id) AS uuid)";params.put("id",request.submissionId().toString());}
            else selection+=" AND NOT EXISTS(SELECT 1 FROM di_ingest.dataset_execution_slot slot WHERE slot.dataset_id=s.dataset_id AND slot.state<>'IDLE')";
            selection+=" ORDER BY s.created_at,s.id LIMIT 16";
            for(var candidate:CayenneSql.rows(context,selection,params)){
                UUID id=SubmissionRepository.uuid(candidate,"id");repository.lockDataset(context,SubmissionRepository.uuid(candidate,"dataset_uuid"));var row=repository.find(context,id,true);
                if(!"READY".equals(row.get("status")))continue;
                var slots=CayenneSql.rows(context,"SELECT state FROM di_ingest.dataset_execution_slot WHERE dataset_id=#bind($dataset) FOR UPDATE",Map.of("dataset",SubmissionRepository.number(row,"dataset_id")));
                if(!slots.isEmpty() && !"IDLE".equals(slots.getFirst().get("state"))){if(request.submissionId()!=null)throw IngestionProblem.conflict("DATASET_BUSY");continue;}
                try{
                    var original=new ActorContext(row.get("issuer_snapshot").toString(),row.get("subject_snapshot").toString(),"user",SubmissionRepository.uuid(row,"submitted_by_uuid"));
                    var authorized=users.requireSubmit(context,original,SubmissionRepository.uuid(row,"dataset_uuid"),SubmissionRepository.uuid(row,"issue_uuid"));users.requireMode(authorized.configuration(),row.get("mode").toString());
                    if(!authorized.configuration().digest().equals(row.get("configuration_digest")))throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
                }catch(IngestionProblem rejected){
                    repository.transition(context,id,"READY","REJECTED_AUTHORIZATION",rejected.code(),"Aktuelles Lieferrecht oder Liefervertrag erlaubt keine Verarbeitung.");repository.event(context,id,null,"REJECTED_AUTHORIZATION","Vor Dispatch erneut geprüft und verweigert.");continue;
                }
                var current=CayenneSql.rows(context,"SELECT v.t_ili_tid FROM di_management.datasetissue i LEFT JOIN di_management.dataversion v ON v.t_id=i.currentversion AND v.availability='available' WHERE i.t_id=#bind($issue)",Map.of("issue",SubmissionRepository.number(row,"issue_id"))).getFirst();
                String base=current.get("t_ili_tid")==null?null:current.get("t_ili_tid").toString();
                if("append".equals(row.get("mode")) && base==null)throw IngestionProblem.conflict("PUBLICATION_CONFLICT");
                long attempts=((Number)CayenneSql.rows(context,"SELECT coalesce(max(attempt_number),0)+1 AS next FROM di_ingest.dispatch WHERE submission_id=CAST(#bind($id) AS uuid)",Map.of("id",id.toString())).getFirst().get("next")).longValue();
                if(attempts>10000)throw IngestionProblem.conflict("INVALID_STATE");
                UUID dispatch=UUID.randomUUID();var p=new HashMap<String,Object>();p.put("dispatch",dispatch.toString());p.put("id",id.toString());p.put("runner",principal);p.put("command",command.commandId().toString());p.put("digest",digest);p.put("attempt",attempts);p.put("instance",instance);p.put("dataset",SubmissionRepository.number(row,"dataset_id"));p.put("base",base);
                SubmissionRepository.execute(context,"""
                    INSERT INTO di_ingest.dispatch(id,submission_id,reserved_by,reservation_command_id,request_digest,attempt_number,jenkins_instance,job_name,state)
                    VALUES(CAST(#bind($dispatch) AS uuid),CAST(#bind($id) AS uuid),#bind($runner),CAST(#bind($command) AS uuid),#bind($digest),#bind($attempt),#bind($instance),'di-csv-ingest','RESERVED')
                    """,p);
                SubmissionRepository.execute(context,"""
                    INSERT INTO di_ingest.dataset_execution_slot(dataset_id,submission_id,dispatch_id,state) VALUES(#bind($dataset),CAST(#bind($id) AS uuid),CAST(#bind($dispatch) AS uuid),'RESERVED')
                    ON CONFLICT(dataset_id) DO UPDATE SET submission_id=EXCLUDED.submission_id,dispatch_id=EXCLUDED.dispatch_id,state='RESERVED' WHERE di_ingest.dataset_execution_slot.state='IDLE'
                    """,p);
                SubmissionRepository.execute(context,"UPDATE di_ingest.submission SET base_version_id=CAST(#bind($base) AS uuid),status='QUEUED',status_version=status_version+1 WHERE id=CAST(#bind($id) AS uuid)",p);
                repository.event(context,id,null,"DISPATCH_RESERVED","Dauerhafter Dispatch und exklusiver Datensatzslot reserviert.");
                return new Reserved(dispatch,id,JOB,"RESERVED",null);
            }
            return null;
        });
    }
    public Reserved queued(ActorContext runner,CommandContext command,UUID dispatch,String queueId){
        if(!"ingestion.queued".equals(command.actionCode()) || queueId==null || !queueId.matches("[0-9]{1,20}"))throw IngestionProblem.invalid("INVALID_INPUT");
        return unit.execute(runner,command,context->{
            long principal=runners.require(context,runner);var initial=dispatch(context,dispatch,false);var submission=repository.find(context,SubmissionRepository.uuid(initial,"submission_id"),false);
            repository.lockDataset(context,SubmissionRepository.uuid(submission,"dataset_uuid"));repository.find(context,SubmissionRepository.uuid(submission,"id"),true);var row=dispatch(context,dispatch,true);
            if(SubmissionRepository.number(row,"reserved_by")!=principal || !instance.equals(row.get("jenkins_instance")))throw IngestionProblem.forbidden();
            if(row.get("queue_id")!=null && !queueId.equals(row.get("queue_id")))throw IngestionProblem.conflict("IDEMPOTENCY_CONFLICT");
            if(row.get("queue_id")==null)SubmissionRepository.execute(context,"UPDATE di_ingest.dispatch SET queue_id=#bind($queue),state=CASE WHEN state='RESERVED' THEN 'QUEUED' ELSE state END WHERE id=CAST(#bind($id) AS uuid)",Map.of("queue",queueId,"id",dispatch.toString()));
            return result(dispatch(context,dispatch,false));
        });
    }
    public List<Reserved> reconcile(ActorContext runner){
        // A read-only query; no hidden lease expiry or queue mutation on GET.
        return unitRead(runner);
    }
    private List<Reserved> unitRead(ActorContext runner){
        // Transaction context is deliberately explicit. No Fach mutation/audit event.
        return unit.read(context->{
            long principal=runners.require(context,runner);
            return CayenneSql.rows(context,"SELECT * FROM di_ingest.dispatch WHERE reserved_by=#bind($runner) AND jenkins_instance=#bind($instance) AND state IN ('RESERVED','QUEUED') ORDER BY created_at LIMIT 100",Map.of("runner",principal,"instance",instance)).stream().map(DispatchService::result).toList();
        });
    }
    Map<String,Object> dispatch(ObjectContext context,UUID id,boolean lock){
        var rows=CayenneSql.rows(context,"SELECT * FROM di_ingest.dispatch WHERE id=CAST(#bind($id) AS uuid)"+(lock?" FOR UPDATE":""),Map.of("id",id.toString()));
        if(rows.size()!=1)throw IngestionProblem.forbidden();return rows.getFirst();
    }
    private static Reserved result(Map<String,Object> row){return new Reserved(SubmissionRepository.uuid(row,"id"),SubmissionRepository.uuid(row,"submission_id"),JOB,row.get("state").toString(),row.get("queue_id")==null?null:row.get("queue_id").toString());}
}
