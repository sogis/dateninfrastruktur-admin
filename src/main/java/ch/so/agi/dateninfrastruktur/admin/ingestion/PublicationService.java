package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.util.*;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.DeliveryRegistrationService;

/** Short PG acceptance transaction. Network verification belongs to the trusted runner. */
public final class PublicationService {
    public record Accepted(UUID submissionId,UUID versionId,long publicationRevision,String state){}
    private record Completion(Accepted accepted,IngestionProblem failure){}
    private final AuditedUnitOfWork unit;private final ExecutionService executions;private final DeliveryRegistrationService catalog;
    private final SubmissionRepository repository=new SubmissionRepository();private final ResultEvidenceValidator validator=new ResultEvidenceValidator();
    public PublicationService(AuditedUnitOfWork unit,ExecutionService executions,DeliveryRegistrationService catalog){this.unit=unit;this.executions=executions;this.catalog=catalog;}
    public Accepted complete(ActorContext actor,CommandContext request,UUID id,ExecutionService.Binding binding,ResultEvidenceValidator.Manifest manifest){
        if(!"ingestion.complete".equals(request.actionCode()))throw IngestionProblem.invalid("INVALID_INPUT");
        String resultDigest=CanonicalJson.digest(manifest);
        var outcome=unit.executeFinalization(actor,request,context->{
            var execution=executions.lock(context,id);executions.requireCurrent(context,actor,execution,binding,false);var plan=ExecutionService.plan(execution);var sub=repository.find(context,plan.submissionId(),true);
            if(sub.get("published_version_id")!=null){
                if(!resultDigest.equals(sub.get("result_digest")) || !Set.of("COMMITTED","FINISHED").contains(execution.get("state")))throw IngestionProblem.conflict("IDEMPOTENCY_CONFLICT");
                return new Completion(new Accepted(plan.submissionId(),SubmissionRepository.uuid(sub,"published_version_id"),SubmissionRepository.number(sub,"publication_revision"),sub.get("status").toString()),null);
            }
            executions.requireCurrent(context,actor,execution,binding,true);
            if(!"READY_TO_COMMIT".equals(sub.get("status")))throw IngestionProblem.conflict("INVALID_STATE");
            var verified=validator.validate(plan,execution.get("plan_digest").toString(),manifest);
            var checkpoints=CayenneSql.rows(context,"SELECT sequence,digest FROM di_ingest.execution_checkpoint WHERE execution_id=CAST(#bind($id) AS uuid) ORDER BY sequence",Map.of("id",id.toString()));
            var attestations=List.of(manifest.input(),manifest.incoming(),manifest.snapshot(),manifest.candidate(),manifest.outputs());
            if(checkpoints.size()!=5)throw IngestionProblem.conflict("INVALID_STATE");
            for(int i=0;i<5;i++)if(!CanonicalJson.digest(attestations.get(i)).equals(checkpoints.get(i).get("digest")))throw IngestionProblem.conflict("ARTIFACT_CONFLICT");
            try{executions.requireOriginal(context,sub);}catch(IngestionProblem denied){
                reject(context,sub,execution,"REJECTED_AUTHORIZATION",denied.code());return new Completion(null,denied);
            }
            String decision=decisionEvidence(context);
            var current=CayenneSql.rows(context,"SELECT v.t_ili_tid FROM di_management.datasetissue i LEFT JOIN di_management.dataversion v ON v.t_id=i.currentversion WHERE i.t_id=#bind($issue)",Map.of("issue",SubmissionRepository.number(sub,"issue_id"))).getFirst();
            if(!Objects.equals(SubmissionRepository.uuid(current,"t_ili_tid"),plan.baseVersionId())){reject(context,sub,execution,"CONFLICT","PUBLICATION_CONFLICT");return new Completion(null,IngestionProblem.conflict("PUBLICATION_CONFLICT"));}
            var configuration=CanonicalJson.JSON.readValue(sub.get("configuration_snapshot").toString(),DeliveryConfigurationQuery.Configuration.class);
            // Stable business command ID survives lost HTTP acknowledgements/restarts.
            var command=new CommandContext(plan.submissionId(),request.requestId(),"ingestion.complete","Validierte CSV-Lieferung");new AuditContextWriter().bind(context,actor,command);
            UUID version=catalog.acceptValidatedCsv(context,actor,command,verified,configuration.schemaId());
            context.commitChanges(); // Flush only. The outer physical transaction still owns every lock.
            long revision=((Number)CayenneSql.rows(context,"SELECT row_version FROM di_management.dataset WHERE t_id=#bind($dataset)",Map.of("dataset",SubmissionRepository.number(sub,"dataset_id"))).getFirst().get("row_version")).longValue();
            SubmissionRepository.execute(context,"""
                UPDATE di_ingest.submission SET status='COMMITTED',status_version=status_version+1,published_version_id=CAST(#bind($version) AS uuid),publication_revision=#bind($revision),result_digest=#bind($digest),last_error_code=NULL,safe_message='Angenommen; SQL-Aktualisierung ausstehend.' WHERE id=CAST(#bind($sub) AS uuid)
                """,Map.of("version",version.toString(),"revision",revision,"digest",resultDigest,"sub",plan.submissionId().toString()));
            SubmissionRepository.execute(context,"UPDATE di_ingest.execution SET state='COMMITTED',finished_at=clock_timestamp() WHERE id=CAST(#bind($id) AS uuid)",Map.of("id",id.toString()));
            SubmissionRepository.execute(context,"UPDATE di_ingest.dispatch SET state='FINISHED' WHERE id=CAST(#bind($id) AS uuid)",Map.of("id",execution.get("dispatch_id").toString()));
            SubmissionRepository.execute(context,"UPDATE di_ingest.dataset_execution_slot SET state='COMMITTED' WHERE dispatch_id=CAST(#bind($id) AS uuid)",Map.of("id",execution.get("dispatch_id").toString()));
            repository.event(context,plan.submissionId(),id,"COMMITTED","Fachlich angenommen; "+decision+"; SQL-Projektion ausstehend.");return new Completion(new Accepted(plan.submissionId(),version,revision,"COMMITTED"),null);
        });
        if(outcome.failure()!=null)throw outcome.failure();return outcome.accepted();
    }
    private static String decisionEvidence(org.apache.cayenne.ObjectContext context){
        var row=CayenneSql.rows(context,"SELECT revision,timezone('UTC',clock_timestamp()) AS checked_at FROM di_ops.authorization_guard WHERE singleton",Map.of()).getFirst();
        return "Autorisierungsrevision "+row.get("revision")+", Prüfzeitpunkt "+row.get("checked_at")+" UTC";
    }
    private void reject(org.apache.cayenne.ObjectContext context,Map<String,Object> sub,Map<String,Object> execution,String state,String code){
        repository.transition(context,SubmissionRepository.uuid(sub,"id"),"READY_TO_COMMIT",state,code,"Abschlussprüfung verweigert; bisheriger Stand bleibt gültig.");
        SubmissionRepository.execute(context,"UPDATE di_ingest.execution SET state='FAILED',finished_at=clock_timestamp() WHERE id=CAST(#bind($id) AS uuid)",Map.of("id",execution.get("id").toString()));
        SubmissionRepository.execute(context,"UPDATE di_ingest.dispatch SET state='FINISHED' WHERE id=CAST(#bind($id) AS uuid)",Map.of("id",execution.get("dispatch_id").toString()));
        SubmissionRepository.execute(context,"UPDATE di_ingest.dataset_execution_slot SET state='IDLE',submission_id=NULL,dispatch_id=NULL WHERE dispatch_id=CAST(#bind($id) AS uuid) AND state='RUNNING'",Map.of("id",execution.get("dispatch_id").toString()));
        repository.event(context,SubmissionRepository.uuid(sub,"id"),SubmissionRepository.uuid(execution,"id"),state,"Fachcommit nach erneuter Prüfung verweigert; "+decisionEvidence(context));
    }
}
