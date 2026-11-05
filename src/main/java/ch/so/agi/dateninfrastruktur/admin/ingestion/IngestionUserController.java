package ch.so.agi.dateninfrastruktur.admin.ingestion;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/internal/ingestion")

public final class IngestionUserController {
    public record Mutation(long expectedStatusVersion,String reason){}
    public record Created(UUID submissionId){}
    private final SubmissionAuthorizationService authorization;private final SubmissionService submissions;private final SubmissionQueryService queries;private final IdentityProvisioningService identities;
    public IngestionUserController(SubmissionAuthorizationService authorization,SubmissionService submissions,SubmissionQueryService queries,IdentityProvisioningService identities){this.authorization=authorization;this.submissions=submissions;this.queries=queries;this.identities=identities;}
    public record IdentityContact(int schemaVersion){}
    @PostMapping("/identity") ResponseEntity<Void> identify(JwtAuthenticationToken jwt,HttpServletRequest request){
        IngestionHttp.command(request,"identity.ensure_user");if(IngestionHttp.body(request,IdentityContact.class).schemaVersion()!=1)throw IngestionProblem.invalid("INVALID_INPUT");
        identities.ensureUser(IngestionHttp.actor(jwt,false));return ResponseEntity.noContent().build();
    }
    @GetMapping("/datasets") Object datasets(JwtAuthenticationToken jwt,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="25")int limit,@RequestParam(defaultValue="")String search){return authorization.listEligible(IngestionHttp.actor(jwt,false),new SubmissionAuthorizationService.PageRequest(offset,limit,search));}
    @PostMapping("/submissions") ResponseEntity<Created> create(JwtAuthenticationToken jwt,HttpServletRequest request){var input=IngestionHttp.body(request,SubmissionService.Create.class);var command=IngestionHttp.command(request,"ingestion.create");var actor=IngestionHttp.actor(jwt,false);identities.ensureUser(actor);return ResponseEntity.status(201).body(new Created(submissions.create(actor,command,input)));}
    @GetMapping("/submissions/{id}/upload-permit") Object uploadPermit(JwtAuthenticationToken jwt,@PathVariable UUID id){return submissions.uploadPermit(IngestionHttp.actor(jwt,false),id);}
    @PostMapping("/submissions/{id}/seal") Created seal(JwtAuthenticationToken jwt,@PathVariable UUID id,HttpServletRequest request){return new Created(submissions.seal(IngestionHttp.actor(jwt,false),IngestionHttp.command(request,"ingestion.seal"),id,IngestionHttp.body(request,SubmissionService.Seal.class)));}
    @PostMapping("/submissions/{id}/cancel") Created cancel(JwtAuthenticationToken jwt,@PathVariable UUID id,HttpServletRequest request){var input=IngestionHttp.body(request,Mutation.class);return new Created(submissions.cancel(IngestionHttp.actor(jwt,false),IngestionHttp.command(request,"ingestion.cancel"),id,input.expectedStatusVersion(),input.reason()));}
    @PostMapping("/submissions/{id}/retry") Created retry(JwtAuthenticationToken jwt,@PathVariable UUID id,HttpServletRequest request){var input=IngestionHttp.body(request,Mutation.class);return new Created(submissions.retry(IngestionHttp.actor(jwt,false),IngestionHttp.command(request,"ingestion.retry"),id,input.expectedStatusVersion(),input.reason()));}
    @GetMapping("/submissions/{id}") Object detail(JwtAuthenticationToken jwt,@PathVariable UUID id){return queries.detail(IngestionHttp.actor(jwt,false),id);}
    @GetMapping("/submissions/{id}/report") Object report(JwtAuthenticationToken jwt,@PathVariable UUID id){return queries.report(IngestionHttp.actor(jwt,false),id);}
    @GetMapping("/submissions") Object list(JwtAuthenticationToken jwt,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="25")int limit,@RequestParam(required=false)UUID datasetId){return queries.list(IngestionHttp.actor(jwt,false),offset,limit,datasetId);}
}
