package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.util.*;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.runtime.CayenneRuntime;
import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;

public final class SubmissionAuthorizationService {
    public record PageRequest(int offset,int limit,String search){
        public PageRequest{if(offset<0 || offset>100000 || limit<1 || limit>100 || search==null || search.length()>100)throw IngestionProblem.invalid("INVALID_INPUT");}
    }
    public record Eligible(UUID datasetId,String identifier,String title,String office,UUID issueId,String issueKey,List<String> allowedModes,String configurationDigest,String definitionKey,String definitionSha256){}
    public record Authorized(long principalKey,long datasetKey,long issueKey,UUID datasetId,UUID issueId,DeliveryConfigurationQuery.Configuration configuration){}
    private final CayenneRuntime runtime;private final AuthorityService authority;private final DeliveryConfigurationQuery configurations;
    public SubmissionAuthorizationService(CayenneRuntime runtime,AuthorityService authority,DeliveryConfigurationQuery configurations){this.runtime=runtime;this.authority=authority;this.configurations=configurations;}
    public long requireUser(ObjectContext context,ActorContext actor){
        if(!"user".equals(actor.kind()))throw IngestionProblem.forbidden();
        var rows=CayenneSql.rows(context,"SELECT t_id,aactive,akind FROM di_management.principal WHERE aissuer=#bind($issuer) AND asubject=#bind($subject)",Map.of("issuer",actor.issuer(),"subject",actor.subject()));
        if(rows.size()!=1 || !"user".equals(rows.getFirst().get("akind")))throw IngestionProblem.forbidden();
        if(!Boolean.TRUE.equals(rows.getFirst().get("aactive")))throw new IngestionProblem(403,"PRINCIPAL_INACTIVE","Diese Identität ist nicht aktiv.");
        return ((Number)rows.getFirst().get("t_id")).longValue();
    }
    public List<Eligible> listEligible(ActorContext actor,PageRequest page){
        return new ch.so.agi.dateninfrastruktur.admin.persistence.AuditedUnitOfWork(runtime).read(context->{
            requireUser(context,actor);
            var rows=CayenneSql.rows(context,AuthorityService.ELIGIBLE+"""
                SELECT d.t_ili_tid AS dataset_id,d.identifier,d.title,o.aname AS office,i.t_ili_tid AS issue_id,i.issuekey
                FROM di_management.dataset d JOIN di_management.office o ON o.t_id=d.owneroffice
                JOIN di_management.datasetissue i ON i.dataset=d.t_id
                JOIN di_management.csvdeliveryconfiguration c ON c.dataset=d.t_id
                CROSS JOIN di_now n WHERE d.publicationstatus='published' AND i.publicationstatus='published'
                AND o.aactive AND c.enabled AND c.automaticpublication
                AND (d.title ILIKE '%'||#bind($search)||'%' OR d.identifier ILIKE '%'||#bind($search)||'%')
                AND EXISTS(SELECT 1 FROM di_management.authorityassignment aa JOIN eligible e ON e.t_id=aa.principal
                 WHERE aa.capability='submit_data' AND (aa.dataset=d.t_id OR aa.office=d.owneroffice)
                 AND aa.revokedat IS NULL AND aa.validfrom<=n.observed_at AND (aa.validuntil IS NULL OR n.observed_at<aa.validuntil))
                ORDER BY d.identifier,i.issuekey LIMIT #bind($limit) OFFSET #bind($offset)
                """,Map.of("issuer",actor.issuer(),"subject",actor.subject(),"search",page.search(),"limit",page.limit(),"offset",page.offset()));
            return rows.stream().map(r->{UUID dataset=UUID.fromString(r.get("dataset_id").toString());var c=configurations.resolve(context,dataset);
                return new Eligible(dataset,r.get("identifier").toString(),r.get("title").toString(),r.get("office").toString(),UUID.fromString(r.get("issue_id").toString()),r.get("issuekey").toString(),availableModes(context,UUID.fromString(r.get("issue_id").toString()),c),c.digest(),c.definitionKey(),c.definitionSha256());}).filter(r->!r.allowedModes().isEmpty()).toList();
        });
    }
    public Authorized requireSubmit(ObjectContext context,ActorContext actor,UUID dataset,UUID issue){
        long principal=requireUser(context,actor);
        if(!authority.allowed(context,actor,dataset,"submit_data"))throw IngestionProblem.forbidden();
        var rows=CayenneSql.rows(context,"""
            SELECT d.t_id AS dataset_key,i.t_id AS issue_key FROM di_management.dataset d
            JOIN di_management.datasetissue i ON i.dataset=d.t_id JOIN di_management.office o ON o.t_id=d.owneroffice
            WHERE d.t_ili_tid=CAST(#bind($dataset) AS uuid) AND i.t_ili_tid=CAST(#bind($issue) AS uuid)
            AND d.publicationstatus='published' AND i.publicationstatus='published' AND o.aactive
            """,Map.of("dataset",dataset.toString(),"issue",issue.toString()));
        if(rows.size()!=1)throw IngestionProblem.conflict("ISSUE_NOT_AVAILABLE");
        var configuration=configurations.resolve(context,dataset);
        if(!configuration.enabled() || !configuration.automaticPublication())throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
        var row=rows.getFirst();return new Authorized(principal,((Number)row.get("dataset_key")).longValue(),((Number)row.get("issue_key")).longValue(),dataset,issue,configuration);
    }
    public List<String> availableModes(ObjectContext context,UUID issue,DeliveryConfigurationQuery.Configuration c){
        var modes=new ArrayList<String>();if(!"append_only".equals(c.allowedModes()))modes.add("replace");
        if(!"replace_only".equals(c.allowedModes()) && canAppend(context,issue,c))modes.add("append");return List.copyOf(modes);
    }
    public void requireIssueMode(ObjectContext context,Authorized allowed,String mode){requireMode(allowed.configuration(),mode);if("append".equals(mode) && !canAppend(context,allowed.issueId(),allowed.configuration()))throw IngestionProblem.conflict("PUBLICATION_CONFLICT");}
    private boolean canAppend(ObjectContext context,UUID issue,DeliveryConfigurationQuery.Configuration c){
        return Boolean.TRUE.equals(CayenneSql.rows(context,"""
            SELECT EXISTS(SELECT 1 FROM di_management.datasetissue i JOIN di_management.dataversion v ON v.t_id=i.currentversion
             JOIN di_management.distribution d ON d.version=v.t_id AND d.aformat='csv' AND d.availability='available'
             JOIN di_ingest.submission s ON s.published_version_id=v.t_ili_tid AND s.status='PUBLISHED'
             JOIN di_ingest.execution e ON e.submission_id=s.id AND e.state IN ('COMMITTED','FINISHED')
             JOIN di_ingest.execution_checkpoint cp ON cp.execution_id=e.id AND cp.checkpoint='SNAPSHOT_VALIDATED'
             WHERE i.t_ili_tid=CAST(#bind($issue) AS uuid) AND v.availability='available' AND v.aschema=#bind($schema)
              AND s.definition_sha256=#bind($definition) AND s.model_set_sha256=#bind($model)
              AND cp.evidence->>'dataSha256' ~ '^[0-9a-f]{64}$' AND (cp.evidence->>'rowCount')::bigint>0) AS allowed
            """,Map.of("issue",issue.toString(),"schema",c.schemaKey(),"definition",c.definitionSha256(),"model",c.modelSetSha256())).getFirst().get("allowed"));
    }
    public void requireMode(DeliveryConfigurationQuery.Configuration configuration,String mode){
        if(!("replace".equals(mode)||"append".equals(mode)))throw IngestionProblem.invalid("MODE_NOT_ALLOWED");
        if(!"replace_and_append".equals(configuration.allowedModes()) && !(mode+"_only").equals(configuration.allowedModes()))throw IngestionProblem.conflict("MODE_NOT_ALLOWED");
    }
    public boolean canManage(ObjectContext context,ActorContext actor,UUID dataset){
        requireUser(context,actor);return authority.allowed(context,actor,dataset,"manage_submitters");
    }
}
