package ch.so.agi.dateninfrastruktur.admin.ingestion;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController @RequestMapping("/internal/ingestion")

public final class IngestionExecutionController {
    public record Queued(String queueId){}
    public record Interrupted(String runId){}
    public record Checkpoint(ExecutionService.Binding binding,String checkpoint,JsonNode evidence){}
    public record Complete(ExecutionService.Binding binding,ResultEvidenceValidator.Manifest manifest){}
    public record Fail(ExecutionService.Binding binding,String code,ExecutionService.ValidationIssue issue){}
    private final DispatchService dispatches;private final ExecutionService executions;private final PublicationService publications;
    public IngestionExecutionController(DispatchService dispatches,ExecutionService executions,PublicationService publications){this.dispatches=dispatches;this.executions=executions;this.publications=publications;}
    @PostMapping("/dispatch/reserve") ResponseEntity<?> reserve(JwtAuthenticationToken jwt,HttpServletRequest request){var value=dispatches.reserve(IngestionHttp.actor(jwt,true),IngestionHttp.command(request,"ingestion.reserve"),IngestionHttp.body(request,DispatchService.Request.class));return value==null?ResponseEntity.noContent().build():ResponseEntity.ok(value);}
    @PostMapping("/dispatch/{id}/queued") Object queued(JwtAuthenticationToken jwt,@PathVariable UUID id,HttpServletRequest request){return dispatches.queued(IngestionHttp.actor(jwt,true),IngestionHttp.command(request,"ingestion.queued"),id,IngestionHttp.body(request,Queued.class).queueId());}
    @PostMapping("/dispatch/spool/{id}/maintenance") Object maintainSpool(JwtAuthenticationToken jwt,@PathVariable UUID id,HttpServletRequest request){
        return dispatches.maintainSpool(IngestionHttp.actor(jwt,true),IngestionHttp.command(request,"ingestion.spool_maintenance"),id);
    }
    @GetMapping("/dispatch/reconciliation") Object reconciliation(JwtAuthenticationToken jwt){return dispatches.reconcile(IngestionHttp.actor(jwt,true));}
    @PostMapping("/executions/dispatch/{id}/interrupted") ResponseEntity<Void> interrupted(JwtAuthenticationToken jwt,@PathVariable UUID id,HttpServletRequest request){
        executions.interrupted(IngestionHttp.actor(jwt,true),IngestionHttp.command(request,"ingestion.interrupted"),id,IngestionHttp.body(request,Interrupted.class).runId());return ResponseEntity.noContent().build();
    }
    @PostMapping("/executions/claim") Object claim(JwtAuthenticationToken jwt,HttpServletRequest request){return executions.claim(IngestionHttp.actor(jwt,true),IngestionHttp.command(request,"ingestion.claim"),IngestionHttp.body(request,ExecutionService.Claim.class));}
    @PostMapping("/executions/{id}/heartbeat") Object heartbeat(JwtAuthenticationToken jwt,@PathVariable UUID id,HttpServletRequest request){return executions.heartbeat(IngestionHttp.actor(jwt,true),IngestionHttp.command(request,"ingestion.heartbeat"),id,IngestionHttp.body(request,ExecutionService.Binding.class));}
    @PostMapping("/executions/{id}/checkpoint") ResponseEntity<Void> checkpoint(JwtAuthenticationToken jwt,@PathVariable UUID id,HttpServletRequest request){
        var value=IngestionHttp.body(request,Checkpoint.class);if(value.checkpoint()==null || value.evidence()==null || value.evidence().isNull())throw IngestionProblem.invalid("INVALID_INPUT");Class<?> type=switch(value.checkpoint()){
            case "INPUT_VERIFIED" -> ResultEvidenceValidator.InputEvidence.class;
            case "INCOMING_VALIDATED","SNAPSHOT_VALIDATED" -> ResultEvidenceValidator.ValidationEvidence.class;
            case "CANDIDATE_VERIFIED" -> ResultEvidenceValidator.CandidateEvidence.class;
            case "ARTIFACTS_VERIFIED" -> ResultEvidenceValidator.ArtifactsEvidence.class;
            default -> throw IngestionProblem.invalid("INVALID_INPUT");};
        Object evidence;try{evidence=CanonicalJson.JSON.readValue(value.evidence().toString(),type);}catch(RuntimeException e){throw IngestionProblem.invalid("INVALID_INPUT");}
        executions.checkpoint(IngestionHttp.actor(jwt,true),IngestionHttp.command(request,"ingestion.checkpoint"),id,value.binding(),value.checkpoint(),evidence);return ResponseEntity.noContent().build();
    }
    @PostMapping("/executions/{id}/complete") ResponseEntity<PublicationService.Accepted> complete(JwtAuthenticationToken jwt,@PathVariable UUID id,HttpServletRequest request){var input=IngestionHttp.body(request,Complete.class);var result=publications.complete(IngestionHttp.actor(jwt,true),IngestionHttp.command(request,"ingestion.complete"),id,input.binding(),input.manifest());return ResponseEntity.status("PUBLISHED".equals(result.state())?200:202).body(result);}
    @PostMapping("/executions/{id}/fail") ResponseEntity<Void> fail(JwtAuthenticationToken jwt,@PathVariable UUID id,HttpServletRequest request){var input=IngestionHttp.body(request,Fail.class);executions.fail(IngestionHttp.actor(jwt,true),IngestionHttp.command(request,"ingestion.fail"),id,input.binding(),input.code(),input.issue());return ResponseEntity.noContent().build();}
}
