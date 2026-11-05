package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.util.*;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;

public final class SubmissionService {
    public record Create(UUID datasetId,UUID issueId,String mode,String expectedConfigurationDigest,String comment){public Create{comment=comment==null?"":comment;}}
    public record Seal(long inputSize,String inputSha256,String originalName,String privateSpoolReference,String spoolProof){}
    private final AuditedUnitOfWork unit;private final SubmissionAuthorizationService authorization;private final SpoolProof spoolProof;private final long maxBytes;
    private final SubmissionRepository repository=new SubmissionRepository();private final CommandReceiptRepository receipts=new CommandReceiptRepository();
    public SubmissionService(AuditedUnitOfWork unit,SubmissionAuthorizationService authorization,SpoolProof proof,long maxBytes){
        if(maxBytes<=0 || maxBytes>CsvDefinitionRegistry.HARD_MAX_BYTES)throw new IllegalArgumentException("Invalid explicit upload bound");this.unit=unit;this.authorization=authorization;this.spoolProof=proof;this.maxBytes=maxBytes;
    }
    public UUID create(ActorContext actor,CommandContext command,Create input){
        if(!"ingestion.create".equals(command.actionCode()) || input.datasetId()==null || input.issueId()==null || input.mode()==null || input.expectedConfigurationDigest()==null || !input.expectedConfigurationDigest().matches("[0-9a-f]{64}") || input.comment().length()>4000)throw IngestionProblem.invalid("INVALID_INPUT");
        String digest=CanonicalJson.digest(input);
        return unit.execute(actor,command,context->{
            long user=authorization.requireUser(context,actor);repository.lockDataset(context,input.datasetId());
            var permitted=authorization.requireSubmit(context,actor,input.datasetId(),input.issueId());
            var existing=CayenneSql.rows(context,"SELECT id,request_digest FROM di_ingest.submission WHERE submitted_by=#bind($user) AND idempotency_key=CAST(#bind($key) AS uuid) FOR UPDATE",Map.of("user",user,"key",command.commandId().toString()));
            if(!existing.isEmpty()){
                if(!digest.equals(existing.getFirst().get("request_digest")))throw IngestionProblem.conflict("IDEMPOTENCY_CONFLICT");
                return SubmissionRepository.uuid(existing.getFirst(),"id");
            }
            var c=permitted.configuration();authorization.requireIssueMode(context,permitted,input.mode());
            if(!c.digest().equals(input.expectedConfigurationDigest()))throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
            UUID id=UUID.randomUUID();var p=new HashMap<String,Object>();
            p.put("id",id.toString());p.put("dataset",permitted.datasetKey());p.put("issue",permitted.issueKey());p.put("schema",c.schemaKey());p.put("user",user);p.put("issuer",actor.issuer());p.put("subject",actor.subject());p.put("key",command.commandId().toString());p.put("digest",digest);p.put("mode",input.mode());p.put("configuration",c.digest());p.put("definition",c.definitionKey());p.put("definitionHash",c.definitionSha256());p.put("modelHash",c.modelSetSha256());p.put("snapshot",CanonicalJson.encode(c));p.put("comment",input.comment());p.put("version",UUID.randomUUID().toString());
            SubmissionRepository.execute(context,"""
                INSERT INTO di_ingest.submission(id,dataset_id,issue_id,schema_id,submitted_by,issuer_snapshot,subject_snapshot,idempotency_key,request_digest,mode,configuration_digest,definition_key,definition_sha256,model_set_sha256,configuration_snapshot,comment,reserved_version_id)
                VALUES(CAST(#bind($id) AS uuid),#bind($dataset),#bind($issue),#bind($schema),#bind($user),#bind($issuer),#bind($subject),CAST(#bind($key) AS uuid),#bind($digest),#bind($mode),#bind($configuration),#bind($definition),#bind($definitionHash),#bind($modelHash),CAST(#bind($snapshot) AS jsonb),#bind($comment),CAST(#bind($version) AS uuid))
                """,p);
            repository.event(context,id,null,"CREATED","Auftrag angelegt; Eingangsdatei noch nicht versiegelt.");return id;
        });
    }
    public record UploadPermit(UUID submissionId,long maxBytes){}
    /** Fresh, read-only owner/grant/contract check before Jenkins consumes a multipart body. */
    public UploadPermit uploadPermit(ActorContext actor,UUID id){return unit.read(context->{
        long user=authorization.requireUser(context,actor);var row=repository.find(context,id,false);
        if(SubmissionRepository.number(row,"submitted_by")!=user)throw IngestionProblem.forbidden();
        var allowed=authorization.requireSubmit(context,actor,SubmissionRepository.uuid(row,"dataset_uuid"),SubmissionRepository.uuid(row,"issue_uuid"));
        if(!allowed.configuration().digest().equals(row.get("configuration_digest")))throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
        if(!"UPLOADING".equals(row.get("status")))throw IngestionProblem.conflict("INVALID_STATE");
        return new UploadPermit(id,Math.min(maxBytes,allowed.configuration().definition().limits().maxBytes()));
    });}
    public UUID seal(ActorContext actor,CommandContext command,UUID id,Seal input){
        if(!"ingestion.seal".equals(command.actionCode()) || input.inputSha256()==null || !input.inputSha256().matches("[0-9a-f]{64}") || input.originalName()==null || input.originalName().length()>255 || !input.originalName().matches("[^/\\\\\\p{Cntrl}]{1,255}\\.[cC][sS][vV]") || !("uploads/"+id+"/input.csv").equals(input.privateSpoolReference()))throw IngestionProblem.invalid("INPUT_MISMATCH");
        if(input.inputSize()<=0 || input.inputSize()>maxBytes)throw new IngestionProblem(413,"INPUT_TOO_LARGE","Die Eingangsdatei überschreitet die Uploadgrenze.");
        spoolProof.verify(id,input.inputSize(),input.inputSha256(),input.originalName(),input.privateSpoolReference(),input.spoolProof());
        return unit.execute(actor,command,context->{
            long user=authorization.requireUser(context,actor);var original=repository.find(context,id,false);
            if(SubmissionRepository.number(original,"submitted_by")!=user)throw IngestionProblem.forbidden();
            UUID dataset=SubmissionRepository.uuid(original,"dataset_uuid");repository.lockDataset(context,dataset);var row=repository.find(context,id,true);
            var permitted=authorization.requireSubmit(context,actor,dataset,SubmissionRepository.uuid(row,"issue_uuid"));
            if(!permitted.configuration().digest().equals(row.get("configuration_digest")))throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
            if(input.inputSize()>permitted.configuration().definition().limits().maxBytes())throw new IngestionProblem(413,"INPUT_TOO_LARGE","Die Datei überschreitet die Grenze dieses Datenvertrags.");
            var old=receipts.findOrReserve(context,actor,command,CanonicalJson.digest(Map.of("submissionId",id,"seal",input)));if(old!=null)return old.reference();
            if(row.get("sealed_at")!=null){
                if(SubmissionRepository.number(row,"input_size")!=input.inputSize() || !input.inputSha256().equals(row.get("input_sha256")) || !input.originalName().equals(row.get("original_name")) || !input.privateSpoolReference().equals(row.get("private_spool_reference")))throw IngestionProblem.conflict("INPUT_MISMATCH");
            }else{
                if(!"UPLOADING".equals(row.get("status")))throw IngestionProblem.conflict("INVALID_STATE");
                SubmissionRepository.execute(context,"""
                    UPDATE di_ingest.submission SET sealed_at=clock_timestamp(),input_size=#bind($size),input_sha256=#bind($sha),original_name=#bind($name),private_spool_reference=#bind($spool),status='READY',status_version=status_version+1
                    WHERE id=CAST(#bind($id) AS uuid)
                    """,Map.of("id",id.toString(),"size",input.inputSize(),"sha",input.inputSha256(),"name",input.originalName(),"spool",input.privateSpoolReference()));
                repository.event(context,id,null,"SEALED","Eingangsdatei gebunden; wartet auf Verarbeitung.");
            }
            receipts.complete(context,actor,command,new CommandReceiptRepository.Receipt(id,"READY"));return id;
        });
    }
    public UUID retry(ActorContext actor,CommandContext command,UUID id,long expectedVersion,String reason){return change(actor,command,id,expectedVersion,reason,true);}
    public UUID cancel(ActorContext actor,CommandContext command,UUID id,long expectedVersion,String reason){return change(actor,command,id,expectedVersion,reason,false);}
    private UUID change(ActorContext actor,CommandContext command,UUID id,long version,String reason,boolean retry){
        if(!(retry?"ingestion.retry":"ingestion.cancel").equals(command.actionCode()) || reason==null || reason.isBlank() || reason.length()>4000)throw IngestionProblem.invalid("INVALID_INPUT");
        return unit.execute(actor,command,context->{
            long user=authorization.requireUser(context,actor);var initial=repository.find(context,id,false);
            if(SubmissionRepository.number(initial,"submitted_by")!=user)throw IngestionProblem.forbidden();
            UUID dataset=SubmissionRepository.uuid(initial,"dataset_uuid");repository.lockDataset(context,dataset);var row=repository.find(context,id,true);
            if(retry){var permitted=authorization.requireSubmit(context,actor,dataset,SubmissionRepository.uuid(row,"issue_uuid"));if(!permitted.configuration().digest().equals(row.get("configuration_digest")))throw IngestionProblem.conflict("CONFIGURATION_CHANGED");}
            var old=receipts.findOrReserve(context,actor,command,CanonicalJson.digest(Map.of("submissionId",id,"expectedStatusVersion",version,"reason",reason)));if(old!=null)return old.reference();
            if(retry && !CayenneSql.rows(context,"SELECT id FROM di_ingest.execution WHERE submission_id=CAST(#bind($id) AS uuid) AND state='ACTIVE'",Map.of("id",id.toString())).isEmpty())throw IngestionProblem.conflict("STALE_EXECUTION");
            String status=row.get("status").toString();if(SubmissionRepository.number(row,"status_version")!=version || Set.of("COMMITTED","PUBLISHED","CANCELLED","REJECTED_VALIDATION","REJECTED_AUTHORIZATION","CONFLICT","UPLOAD_FAILED").contains(status) || (retry && !"FAILED_RETRYABLE".equals(status)))throw IngestionProblem.conflict("INVALID_STATE");
            repository.transition(context,id,status,retry?"READY":"CANCELLED",null,retry?"Neuer Versuch angefordert.":"Auftrag abgebrochen.");
            if(!retry){
                SubmissionRepository.execute(context,"UPDATE di_ingest.execution SET state='CANCELLED',finished_at=clock_timestamp() WHERE submission_id=CAST(#bind($id) AS uuid) AND state='ACTIVE'",Map.of("id",id.toString()));
                SubmissionRepository.execute(context,"UPDATE di_ingest.dispatch SET state='CANCELLED' WHERE submission_id=CAST(#bind($id) AS uuid) AND state IN ('RESERVED','QUEUED','CLAIMED')",Map.of("id",id.toString()));
            }
            SubmissionRepository.execute(context,"UPDATE di_ingest.dataset_execution_slot SET state='IDLE',submission_id=NULL,dispatch_id=NULL WHERE submission_id=CAST(#bind($id) AS uuid) AND state<>'COMMITTED'",Map.of("id",id.toString()));
            repository.event(context,id,null,retry?"RETRY_REQUESTED":"CANCELLED",retry?"Neuer Versuch nach aktueller Berechtigungsprüfung.":"Abgebrochen; frühere Ausführungen dürfen nicht mehr abschliessen.");
            receipts.complete(context,actor,command,new CommandReceiptRepository.Receipt(id,retry?"READY":"CANCELLED"));return id;
        });
    }
}
