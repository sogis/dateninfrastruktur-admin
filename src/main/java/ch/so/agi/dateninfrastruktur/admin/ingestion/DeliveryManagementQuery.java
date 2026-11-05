package ch.so.agi.dateninfrastruktur.admin.ingestion;
import java.util.*;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;

/** Scope-limited management reads; no unfiltered principal/authority export. */
public final class DeliveryManagementQuery {
    private final AuditedUnitOfWork unit;private final SubmitterAdministrationService managers;private final SubmissionAuthorizationService users;private final AuthorityService authority;
    public DeliveryManagementQuery(AuditedUnitOfWork unit,SubmitterAdministrationService managers,SubmissionAuthorizationService users,AuthorityService authority){this.unit=unit;this.managers=managers;this.users=users;this.authority=authority;}
    public List<Map<String,Object>> scopes(ActorContext actor,boolean offices){return unit.read(ctx->{users.requireUser(ctx,actor);String sql=offices?"""
        SELECT o.t_ili_tid AS id,o.aname AS title,'office' AS kind FROM di_management.office o CROSS JOIN di_now n
        WHERE o.aactive AND EXISTS(SELECT 1 FROM di_management.authorityassignment aa JOIN eligible e ON e.t_id=aa.principal WHERE aa.office=o.t_id AND aa.capability='manage_submitters' AND aa.revokedat IS NULL AND aa.validfrom<=n.observed_at AND (aa.validuntil IS NULL OR n.observed_at<aa.validuntil)) ORDER BY o.identifier LIMIT 100
        """:"""
        SELECT d.t_ili_tid AS id,d.title,'dataset' AS kind FROM di_management.dataset d JOIN di_management.office o ON o.t_id=d.owneroffice CROSS JOIN di_now n
        WHERE o.aactive AND EXISTS(SELECT 1 FROM di_management.authorityassignment aa JOIN eligible e ON e.t_id=aa.principal WHERE (aa.dataset=d.t_id OR aa.office=d.owneroffice) AND aa.capability IN ('manage_submitters','manage_metadata') AND aa.revokedat IS NULL AND aa.validfrom<=n.observed_at AND (aa.validuntil IS NULL OR n.observed_at<aa.validuntil)) ORDER BY d.identifier LIMIT 100
        """;return CayenneSql.rows(ctx,AuthorityService.ELIGIBLE+sql,Map.of("issuer",actor.issuer(),"subject",actor.subject()));});}
    public Map<String,Object> scope(ActorContext actor,SubmitterAdministrationService.Scope scope){return unit.read(ctx->{managers.requireManage(ctx,actor,scope);boolean dataset=scope.datasetId()!=null;return CayenneSql.rows(ctx,"SELECT t_ili_tid AS id,row_version AS revision,"+(dataset?"title":"aname")+" AS title FROM di_management."+(dataset?"dataset":"office")+" WHERE t_ili_tid=CAST(#bind($id) AS uuid)",Map.of("id",(dataset?scope.datasetId():scope.officeId()).toString())).getFirst();});}
    public List<Map<String,Object>> assignments(ActorContext actor,SubmitterAdministrationService.Scope scope){return unit.read(ctx->{managers.requireManage(ctx,actor,scope);String column=scope.datasetId()!=null?"dataset":"office",table=scope.datasetId()!=null?"dataset":"office";UUID id=scope.datasetId()!=null?scope.datasetId():scope.officeId();return CayenneSql.rows(ctx,"""
        SELECT aa.t_ili_tid AS id,aa.row_version AS revision,p.t_ili_tid AS beneficiary,p.displayname,p.aissuer AS issuer,p.asubject AS subject,p.akind AS kind,aa.validfrom,aa.validuntil,aa.revokedat,aa.revocationreason AS reason
        FROM di_management.authorityassignment aa JOIN di_management.principal p ON p.t_id=aa.principal
        """+" JOIN di_management."+table+" s ON s.t_id=aa."+column+" WHERE s.t_ili_tid=CAST(#bind($id) AS uuid) AND aa.capability='submit_data' ORDER BY aa.t_id DESC LIMIT 100",Map.of("id",id.toString()));});}
    public List<Map<String,Object>> principals(ActorContext actor,SubmitterAdministrationService.Scope scope,String search){if(search==null || search.length()<2 || search.length()>100)return List.of();return unit.read(ctx->{managers.requireManage(ctx,actor,scope);return CayenneSql.rows(ctx,"SELECT t_ili_tid AS id,aissuer AS issuer,asubject AS subject,displayname,akind AS kind FROM di_management.principal WHERE aactive AND (displayname ILIKE '%'||#bind($q)||'%' OR asubject ILIKE '%'||#bind($q)||'%') ORDER BY t_id LIMIT 25",Map.of("q",search));});}
    public Map<String,Object> configuration(ActorContext actor,UUID dataset){return unit.read(ctx->{users.requireUser(ctx,actor);authority.require(ctx,actor,dataset,"manage_metadata");return CayenneSql.rows(ctx,"""
        SELECT d.title,d.row_version AS revision,c.enabled,c.automaticpublication,c.definitionkey,c.allowedmodes,s.t_ili_tid AS schema_id
        FROM di_management.dataset d LEFT JOIN di_management.csvdeliveryconfiguration c ON c.dataset=d.t_id LEFT JOIN di_management.dataschema s ON s.t_id=c.aschema WHERE d.t_ili_tid=CAST(#bind($id) AS uuid)
        """,Map.of("id",dataset.toString())).getFirst();});}
}
