package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.cayenne.ObjectContext;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;

/** One winning run per dispatch. All effects remain isolated by execution UUID. */
public final class ExecutionService {
    public record Claim(UUID dispatchId,String jenkinsInstance,String jobName,String runId){}
    public record Binding(long fence,String leaseSecret){}
    public record Claimed(UUID executionId,long fence,String leaseSecret,String planDigest,CsvExecutionPlanFactory.Plan plan){}
    public record Heartbeat(Instant leaseExpiresAt){}
    private static final List<String> STEPS=List.of("INPUT_VERIFIED","INCOMING_VALIDATED","SNAPSHOT_VALIDATED","CANDIDATE_VERIFIED","ARTIFACTS_VERIFIED");
    private final AuditedUnitOfWork unit;private final SubmissionAuthorizationService users;private final IngestionRunnerAuthorization runners;private final CsvExecutionPlanFactory plans;private final String instance;private final byte[] leaseKey;
    private final SubmissionRepository repository=new SubmissionRepository();private final ResultEvidenceValidator evidence=new ResultEvidenceValidator();
    public ExecutionService(AuditedUnitOfWork unit,SubmissionAuthorizationService users,IngestionRunnerAuthorization runners,CsvExecutionPlanFactory plans,String instance,byte[] leaseKey){
        if(instance==null || !instance.matches("[0-9a-f]{32}") || leaseKey.length<32)throw new IllegalArgumentException("Bound instance and persistent private lease key required");
        this.unit=unit;this.users=users;this.runners=runners;this.plans=plans;this.instance=instance;this.leaseKey=leaseKey.clone();
    }
    public Claimed claim(ActorContext actor,CommandContext command,Claim request){
        action(command,"ingestion.claim");
        if(request.dispatchId()==null || !instance.equals(request.jenkinsInstance()) || !DispatchService.JOB.equals(request.jobName()) || request.runId()==null || !request.runId().matches("[0-9]{1,20}"))throw IngestionProblem.forbidden();
        var claimed=unit.execute(actor,command,context->{
            long runner=runners.require(context,actor);
            var dispatch=dispatch(context,request.dispatchId());var initial=repository.find(context,SubmissionRepository.uuid(dispatch,"submission_id"),false);
            repository.lockDataset(context,SubmissionRepository.uuid(initial,"dataset_uuid"));var sub=repository.find(context,SubmissionRepository.uuid(initial,"id"),true);
            dispatch=dispatch(context,request.dispatchId());
            if(!instance.equals(dispatch.get("jenkins_instance")) || runner!=SubmissionRepository.number(dispatch,"reserved_by"))throw IngestionProblem.forbidden();
            var old=CayenneSql.rows(context,"SELECT * FROM di_ingest.execution WHERE dispatch_id=CAST(#bind($id) AS uuid) FOR UPDATE",Map.of("id",request.dispatchId().toString()));
            if(!old.isEmpty()){
                var row=old.getFirst();if(runner!=SubmissionRepository.number(row,"runner_principal") || !request.runId().equals(row.get("jenkins_run_id")) || !"ACTIVE".equals(row.get("state")))throw IngestionProblem.conflict("STALE_EXECUTION");
                var binding=new Binding(SubmissionRepository.number(row,"fence"),lease(row));requireCurrent(context,actor,row,binding,true);requireOriginal(context,sub);return claimed(row);
            }
            if(!Set.of("RESERVED","QUEUED").contains(dispatch.get("state")) || !"QUEUED".equals(sub.get("status")))throw IngestionProblem.conflict("INVALID_STATE");
            var slot=CayenneSql.rows(context,"SELECT * FROM di_ingest.dataset_execution_slot WHERE dataset_id=#bind($dataset) FOR UPDATE",Map.of("dataset",SubmissionRepository.number(sub,"dataset_id")));
            if(slot.size()!=1 || !request.dispatchId().equals(SubmissionRepository.uuid(slot.getFirst(),"dispatch_id")) || !"RESERVED".equals(slot.getFirst().get("state")))throw IngestionProblem.conflict("STALE_EXECUTION");
            try{requireOriginal(context,sub);}catch(IngestionProblem denied){
                repository.transition(context,SubmissionRepository.uuid(sub,"id"),"QUEUED","REJECTED_AUTHORIZATION",denied.code(),"Lieferrecht oder Vertrag vor Ausführung nicht mehr gültig.");
                finishDispatch(context,request.dispatchId(),SubmissionRepository.uuid(sub,"id"));repository.event(context,SubmissionRepository.uuid(sub,"id"),null,"REJECTED_AUTHORIZATION","Ausführung nach aktueller Rechteprüfung verweigert.");return null;
            }
            UUID id=UUID.randomUUID();long fence=((Number)CayenneSql.rows(context,"SELECT coalesce(max(fence),0)+1 AS fence FROM di_ingest.execution WHERE submission_id=CAST(#bind($id) AS uuid)",Map.of("id",sub.get("id").toString())).getFirst().get("fence")).longValue();
            var plan=plans.create(context,sub,id,fence);String digest=CanonicalJson.digest(plan);
            var p=new HashMap<String,Object>();p.put("id",id.toString());p.put("sub",sub.get("id").toString());p.put("dispatch",request.dispatchId().toString());p.put("attempt",dispatch.get("attempt_number"));p.put("fence",fence);p.put("runner",runner);p.put("run",request.runId());p.put("plan",CanonicalJson.encode(plan));p.put("digest",digest);
            String secret=lease(id,request.dispatchId(),runner,request.runId(),fence);p.put("secret",CanonicalJson.sha256(secret.getBytes(StandardCharsets.UTF_8)));
            SubmissionRepository.execute(context,"""
                INSERT INTO di_ingest.execution(id,submission_id,dispatch_id,attempt_number,fence,runner_principal,jenkins_job,jenkins_run_id,state,heartbeat_at,lease_expires_at,lease_secret_hash,plan,plan_digest)
                VALUES(CAST(#bind($id) AS uuid),CAST(#bind($sub) AS uuid),CAST(#bind($dispatch) AS uuid),#bind($attempt),#bind($fence),#bind($runner),'di-csv-ingest',#bind($run),'ACTIVE',clock_timestamp(),clock_timestamp()+interval '5 minutes',#bind($secret),CAST(#bind($plan) AS jsonb),#bind($digest))
                """,p);
            SubmissionRepository.execute(context,"UPDATE di_ingest.dispatch SET state='CLAIMED' WHERE id=CAST(#bind($dispatch) AS uuid)",p);
            SubmissionRepository.execute(context,"UPDATE di_ingest.dataset_execution_slot SET state='RUNNING' WHERE dispatch_id=CAST(#bind($dispatch) AS uuid)",p);
            repository.transition(context,SubmissionRepository.uuid(sub,"id"),"QUEUED","VALIDATING",null,"Eingangsprüfung läuft.");repository.event(context,SubmissionRepository.uuid(sub,"id"),id,"CLAIMED","Ausführung exklusiv gebunden.");
            return new Claimed(id,fence,secret,digest,plan);
        });
        if(claimed==null)throw IngestionProblem.forbidden();return claimed;
    }
    public Heartbeat heartbeat(ActorContext actor,CommandContext command,UUID id,Binding binding){
        action(command,"ingestion.heartbeat");return unit.execute(actor,command,context->{var row=lock(context,id);requireCurrent(context,actor,row,binding,true);
            SubmissionRepository.execute(context,"UPDATE di_ingest.execution SET heartbeat_at=clock_timestamp(),lease_expires_at=clock_timestamp()+interval '5 minutes' WHERE id=CAST(#bind($id) AS uuid)",Map.of("id",id.toString()));
            return new Heartbeat(time(execution(context,id).get("lease_expires_at")));});
    }
    public void checkpoint(ActorContext actor,CommandContext command,UUID id,Binding binding,String checkpoint,Object typedEvidence){
        action(command,"ingestion.checkpoint");int sequence=STEPS.indexOf(checkpoint)+1;if(sequence==0)throw IngestionProblem.invalid("INVALID_INPUT");
        String digest=CanonicalJson.digest(typedEvidence);
        unit.execute(actor,command,context->{
            var row=lock(context,id);requireCurrent(context,actor,row,binding,false);var plan=plan(row);var sub=repository.find(context,plan.submissionId(),false);
            var previous=CayenneSql.rows(context,"SELECT * FROM di_ingest.execution_checkpoint WHERE execution_id=CAST(#bind($id) AS uuid) ORDER BY sequence",Map.of("id",id.toString()));
            if(previous.size()>=sequence){if(!Set.of("ACTIVE","COMMITTED","FINISHED").contains(row.get("state")))throw IngestionProblem.conflict("STALE_EXECUTION");if("ACTIVE".equals(row.get("state")))requireCurrent(context,actor,row,binding,true);if(!digest.equals(previous.get(sequence-1).get("digest")))throw IngestionProblem.conflict("IDEMPOTENCY_CONFLICT");return null;}
            requireCurrent(context,actor,row,binding,true);
            if(previous.size()!=sequence-1)throw IngestionProblem.conflict("INVALID_STATE");
            ResultEvidenceValidator.ValidationEvidence snapshot=null;if(sequence>=4)snapshot=CanonicalJson.JSON.readValue(previous.get(2).get("evidence").toString(),ResultEvidenceValidator.ValidationEvidence.class);
            switch(sequence){
                case 1 -> {if(!(typedEvidence instanceof ResultEvidenceValidator.InputEvidence e))throw IngestionProblem.invalid("INVALID_INPUT");evidence.input(plan,e);}
                case 2,3 -> {if(!(typedEvidence instanceof ResultEvidenceValidator.ValidationEvidence e))throw IngestionProblem.invalid("INVALID_INPUT");evidence.validation(plan,e,sequence==3);
                    if(sequence==3){var incoming=CanonicalJson.JSON.readValue(previous.get(1).get("evidence").toString(),ResultEvidenceValidator.ValidationEvidence.class);long expected=incoming.rowCount()+("append".equals(plan.mode())?plan.baseSnapshot().rowCount():0);if(expected!=e.rowCount() || ("replace".equals(plan.mode()) && !incoming.dataSha256().equals(e.dataSha256())))throw IngestionProblem.conflict("VALIDATION_FAILED");}}
                case 4 -> {if(!(typedEvidence instanceof ResultEvidenceValidator.CandidateEvidence e))throw IngestionProblem.invalid("INVALID_INPUT");evidence.candidate(plan,snapshot,e);}
                case 5 -> {if(!(typedEvidence instanceof ResultEvidenceValidator.ArtifactsEvidence e))throw IngestionProblem.invalid("INVALID_INPUT");evidence.artifacts(plan,snapshot,e);
                    for(var a:e.artifacts())SubmissionRepository.execute(context,"""
                        INSERT INTO di_ingest.artifact_evidence(execution_id,distribution_id,format,object_key,media_type,byte_size,sha256,snapshot_digest)
                        VALUES(CAST(#bind($execution) AS uuid),CAST(#bind($distribution) AS uuid),#bind($format),#bind($key),#bind($media),#bind($size),#bind($sha),#bind($snapshot))
                        """,Map.of("execution",id.toString(),"distribution",a.distributionId().toString(),"format",a.format(),"key",a.objectKey(),"media",a.mediaType(),"size",a.byteSize(),"sha",a.sha256(),"snapshot",a.snapshotDigest()));}
                default -> throw IngestionProblem.invalid("INVALID_INPUT");
            }
            SubmissionRepository.execute(context,"INSERT INTO di_ingest.execution_checkpoint(execution_id,checkpoint,sequence,digest,evidence) VALUES(CAST(#bind($id) AS uuid),#bind($checkpoint),#bind($sequence),#bind($digest),CAST(#bind($evidence) AS jsonb))",Map.of("id",id.toString(),"checkpoint",checkpoint,"sequence",sequence,"digest",digest,"evidence",CanonicalJson.encode(typedEvidence)));
            if(sequence==2)repository.transition(context,plan.submissionId(),"VALIDATING","STAGING",null,"Validierter Eingang; privater vollständiger Snapshot wird aufgebaut.");
            if(sequence==5)repository.transition(context,plan.submissionId(),"STAGING","READY_TO_COMMIT",null,"Alle Belege vollständig; Annahme steht aus.");
            repository.event(context,plan.submissionId(),id,checkpoint,"Gebundenen Verarbeitungsschritt bestätigt.");return null;
        });
    }
    public void fail(ActorContext actor,CommandContext command,UUID id,Binding binding,String code){
        action(command,"ingestion.fail");
        if(!Set.of("DEPENDENCY_UNAVAILABLE","INPUT_MISMATCH","VALIDATION_FAILED","ARTIFACT_CONFLICT").contains(code))throw IngestionProblem.invalid("INVALID_INPUT");
        unit.execute(actor,command,context->{var row=lock(context,id);requireCurrent(context,actor,row,binding,false);var sub=repository.find(context,SubmissionRepository.uuid(row,"submission_id"),false);
            if("FAILED".equals(row.get("state")) && code.equals(sub.get("last_error_code")) && Set.of("FAILED_RETRYABLE","REJECTED_VALIDATION").contains(sub.get("status")))return null;
            requireCurrent(context,actor,row,binding,true);
            String next=Set.of("INPUT_MISMATCH","VALIDATION_FAILED").contains(code)?"REJECTED_VALIDATION":"FAILED_RETRYABLE";
            repository.transition(context,SubmissionRepository.uuid(sub,"id"),sub.get("status").toString(),next,code,"Verarbeitung ohne fachliche Annahme beendet.");
            SubmissionRepository.execute(context,"UPDATE di_ingest.execution SET state='FAILED',finished_at=clock_timestamp() WHERE id=CAST(#bind($id) AS uuid)",Map.of("id",id.toString()));
            finishDispatch(context,SubmissionRepository.uuid(row,"dispatch_id"),SubmissionRepository.uuid(sub,"id"));repository.event(context,SubmissionRepository.uuid(sub,"id"),id,next,"Verarbeitung ohne Annahme beendet; keine Rohfehler gespeichert.");return null;});
    }
    Map<String,Object> lock(ObjectContext context,UUID id){var initial=execution(context,id);var sub=repository.find(context,SubmissionRepository.uuid(initial,"submission_id"),false);repository.lockDataset(context,SubmissionRepository.uuid(sub,"dataset_uuid"));repository.find(context,SubmissionRepository.uuid(sub,"id"),true);return CayenneSql.rows(context,"SELECT * FROM di_ingest.execution WHERE id=CAST(#bind($id) AS uuid) FOR UPDATE",Map.of("id",id.toString())).getFirst();}
    void requireCurrent(ObjectContext context,ActorContext actor,Map<String,Object> row,Binding binding,boolean active){
        long runner=runners.require(context,actor);
        if(binding==null || binding.leaseSecret()==null || binding.leaseSecret().length()>128 || runner!=SubmissionRepository.number(row,"runner_principal") || binding.fence()!=SubmissionRepository.number(row,"fence") || !MessageDigest.isEqual(row.get("lease_secret_hash").toString().getBytes(StandardCharsets.US_ASCII),CanonicalJson.sha256(binding.leaseSecret().getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII)))throw IngestionProblem.conflict("STALE_EXECUTION");
        var dispatch=dispatch(context,SubmissionRepository.uuid(row,"dispatch_id"));if(!instance.equals(dispatch.get("jenkins_instance")))throw IngestionProblem.forbidden();
        if(active){
            if(!"ACTIVE".equals(row.get("state")))throw IngestionProblem.conflict("STALE_EXECUTION");
            var valid=CayenneSql.rows(context,"SELECT 1 AS valid FROM di_ingest.execution e JOIN di_ingest.dataset_execution_slot s ON s.dispatch_id=e.dispatch_id AND s.submission_id=e.submission_id AND s.state='RUNNING' WHERE e.id=CAST(#bind($id) AS uuid) AND e.lease_expires_at>clock_timestamp()",Map.of("id",row.get("id").toString()));
            if(valid.size()!=1)throw IngestionProblem.conflict("STALE_EXECUTION");
        }
    }
    void requireOriginal(ObjectContext context,Map<String,Object> sub){var actor=new ActorContext(sub.get("issuer_snapshot").toString(),sub.get("subject_snapshot").toString(),"user",SubmissionRepository.uuid(sub,"submitted_by_uuid"));var permitted=users.requireSubmit(context,actor,SubmissionRepository.uuid(sub,"dataset_uuid"),SubmissionRepository.uuid(sub,"issue_uuid"));users.requireMode(permitted.configuration(),sub.get("mode").toString());if(!permitted.configuration().digest().equals(sub.get("configuration_digest")))throw IngestionProblem.conflict("CONFIGURATION_CHANGED");}
    static CsvExecutionPlanFactory.Plan plan(Map<String,Object> row){return CanonicalJson.JSON.readValue(row.get("plan").toString(),CsvExecutionPlanFactory.Plan.class);}
    private Claimed claimed(Map<String,Object> row){return new Claimed(SubmissionRepository.uuid(row,"id"),SubmissionRepository.number(row,"fence"),lease(row),row.get("plan_digest").toString(),plan(row));}
    private String lease(Map<String,Object> row){return lease(SubmissionRepository.uuid(row,"id"),SubmissionRepository.uuid(row,"dispatch_id"),SubmissionRepository.number(row,"runner_principal"),row.get("jenkins_run_id").toString(),SubmissionRepository.number(row,"fence"));}
    private String lease(UUID id,UUID dispatch,long runner,String run,long fence){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(leaseKey,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(("di-lease-v1\n"+id+"\n"+dispatch+"\n"+runner+"\n"+run+"\n"+fence).getBytes(StandardCharsets.UTF_8)));}catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}}
    private static Map<String,Object> execution(ObjectContext context,UUID id){var rows=CayenneSql.rows(context,"SELECT * FROM di_ingest.execution WHERE id=CAST(#bind($id) AS uuid)",Map.of("id",id.toString()));if(rows.size()!=1)throw IngestionProblem.forbidden();return rows.getFirst();}
    private static Map<String,Object> dispatch(ObjectContext context,UUID id){var rows=CayenneSql.rows(context,"SELECT * FROM di_ingest.dispatch WHERE id=CAST(#bind($id) AS uuid)",Map.of("id",id.toString()));if(rows.size()!=1)throw IngestionProblem.forbidden();return rows.getFirst();}
    private static void finishDispatch(ObjectContext context,UUID dispatch,UUID sub){SubmissionRepository.execute(context,"UPDATE di_ingest.dispatch SET state='FINISHED' WHERE id=CAST(#bind($id) AS uuid)",Map.of("id",dispatch.toString()));SubmissionRepository.execute(context,"UPDATE di_ingest.dataset_execution_slot SET state='IDLE',submission_id=NULL,dispatch_id=NULL WHERE dispatch_id=CAST(#bind($id) AS uuid) AND submission_id=CAST(#bind($sub) AS uuid) AND state<>'COMMITTED'",Map.of("id",dispatch.toString(),"sub",sub.toString()));}
    private static Instant time(Object value){if(value instanceof java.util.Date date)return date.toInstant();if(value instanceof OffsetDateTime date)return date.toInstant();return Instant.parse(value.toString());}
    private static void action(CommandContext command,String expected){if(!expected.equals(command.actionCode()))throw IngestionProblem.invalid("INVALID_INPUT");}
}
