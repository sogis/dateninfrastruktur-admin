package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.util.*;
import org.apache.cayenne.ObjectContext;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;

/** Safe provenance API. read_data alone never opens submission history. */
public final class SubmissionQueryService {
    public record Status(UUID submissionId,UUID datasetId,UUID issueId,String state,long statusVersion,String originalName,Long inputSize,String mode,UUID publishedVersionId,String errorCode,String message,String datasetTitle,String createdAt){}
    public record Report(UUID submissionId,String state,String errorCode,String message,List<Step> steps,List<ExecutionService.ValidationIssue> issues){}
    public record Step(String checkpoint){}
    private final AuditedUnitOfWork unit;private final SubmissionAuthorizationService users;
    private final SubmissionRepository repository=new SubmissionRepository();
    public SubmissionQueryService(AuditedUnitOfWork unit,SubmissionAuthorizationService users){this.unit=unit;this.users=users;}
    public Status detail(ActorContext actor,UUID id){return unit.read(context->status(visible(context,actor,id)));}
    public Report report(ActorContext actor,UUID id){return unit.read(context->{var row=visible(context,actor,id);
        // Checkpoint names are the server's finite vocabulary. No raw runner JSON/logs.
        var steps=CayenneSql.rows(context,"SELECT DISTINCT cp.sequence,cp.checkpoint FROM di_ingest.execution_checkpoint cp JOIN di_ingest.execution e ON e.id=cp.execution_id WHERE e.submission_id=CAST(#bind($id) AS uuid) ORDER BY cp.sequence",Map.of("id",id.toString())).stream().map(r->new Step(r.get("checkpoint").toString())).toList();
        var issues=CayenneSql.rows(context,"SELECT safe_message FROM di_ingest.event WHERE submission_id=CAST(#bind($id) AS uuid) AND event_type='VALIDATION_DETAIL' ORDER BY id DESC LIMIT 10",Map.of("id",id.toString())).stream()
            .map(r->CanonicalJson.JSON.readValue(r.get("safe_message").toString(),ExecutionService.ValidationIssue.class)).toList();
        return new Report(id,row.get("status").toString(),text(row,"last_error_code"),text(row,"safe_message"),steps,issues);});}
    public List<Status> list(ActorContext actor,int offset,int limit,UUID dataset){
        if(offset<0 || offset>100000 || limit<1 || limit>100)throw IngestionProblem.invalid("INVALID_INPUT");
        return unit.read(context->{long principal=users.requireUser(context,actor);var p=new HashMap<String,Object>();p.put("issuer",actor.issuer());p.put("subject",actor.subject());p.put("principal",principal);p.put("limit",limit);p.put("offset",offset);
            String filter="";if(dataset!=null){filter=" AND d.t_ili_tid=CAST(#bind($dataset) AS uuid)";p.put("dataset",dataset.toString());}
            var rows=CayenneSql.rows(context,AuthorityService.ELIGIBLE+"""
                SELECT s.id FROM di_ingest.submission s JOIN di_management.dataset d ON d.t_id=s.dataset_id CROSS JOIN di_now n
                WHERE (s.submitted_by=#bind($principal) OR EXISTS(
                  SELECT 1 FROM di_management.authorityassignment aa JOIN eligible e ON e.t_id=aa.principal
                  WHERE aa.capability='manage_submitters' AND (aa.dataset=d.t_id OR aa.office=d.owneroffice)
                  AND aa.revokedat IS NULL AND aa.validfrom<=n.observed_at AND (aa.validuntil IS NULL OR n.observed_at<aa.validuntil)))
                """+filter+" ORDER BY s.created_at DESC,s.id LIMIT #bind($limit) OFFSET #bind($offset)",p);
            return rows.stream().map(r->status(repository.find(context,SubmissionRepository.uuid(r,"id"),false))).toList();});
    }
    private Map<String,Object> visible(ObjectContext context,ActorContext actor,UUID id){long principal=users.requireUser(context,actor);var row=repository.find(context,id,false);if(principal!=SubmissionRepository.number(row,"submitted_by") && !users.canManage(context,actor,SubmissionRepository.uuid(row,"dataset_uuid")))throw IngestionProblem.forbidden();return row;}
    private static String text(Map<String,Object> row,String key){return row.get(key)==null?null:row.get(key).toString();}
    private static String utc(Object time){if(time instanceof java.util.Date date)return date.toInstant().toString();if(time instanceof java.time.OffsetDateTime date)return date.toInstant().toString();return time.toString();}
    private static Status status(Map<String,Object> row){return new Status(SubmissionRepository.uuid(row,"id"),SubmissionRepository.uuid(row,"dataset_uuid"),SubmissionRepository.uuid(row,"issue_uuid"),text(row,"status"),SubmissionRepository.number(row,"status_version"),text(row,"original_name"),row.get("input_size")==null?null:SubmissionRepository.number(row,"input_size"),text(row,"mode"),SubmissionRepository.uuid(row,"published_version_id"),text(row,"last_error_code"),text(row,"safe_message"),text(row,"dataset_title"),utc(row.get("created_at")));}
}
