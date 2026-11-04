package ch.so.agi.dateninfrastruktur.admin.access;
import java.util.*;
import javax.sql.DataSource;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.runtime.CayenneRuntime;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;

/** Bounded read models. Every invocation creates a fresh context and checks current authority. */
public final class ManagementQueryService {
    public static final String SCOPE="""
        EXISTS(SELECT 1 FROM di_management.authorityassignment aa JOIN eligible e ON e.t_id=aa.principal CROSS JOIN di_now n
          WHERE (aa.dataset=d.t_id OR aa.office=d.owneroffice) AND aa.revokedat IS NULL
            AND aa.validfrom<=n.observed_at AND (aa.validuntil IS NULL OR n.observed_at<aa.validuntil))
        """;
    private final CayenneRuntime runtime;private final AuthorityService authority;private final DataSource audit;
    public ManagementQueryService(CayenneRuntime runtime,AuthorityService authority,DataSource audit){this.runtime=runtime;this.authority=authority;this.audit=audit;}
    private Map<String,Object> params(ActorContext actor){return new HashMap<>(Map.of("issuer",actor.issuer(),"subject",actor.subject()));}
    private List<Map<String,Object>> rows(ObjectContext ctx,String sql,Map<String,?> p){
        return CayenneSql.rows(ctx,sql,p).stream().map(r->Collections.unmodifiableMap(new HashMap<String,Object>(r))).toList();
    }
    private int offset(int page){if(page<0 || page>10000)throw BusinessException.invalid("Ungültige Seite.");return page*50;}
    public List<Map<String,Object>> datasets(ActorContext actor,int page){return runtime.performInTransaction(()->{
        var p=params(actor);p.put("offset",offset(page));
        return rows(runtime.newContext(),AuthorityService.ELIGIBLE+"SELECT d.t_ili_tid AS id,d.title,d.publicationstatus AS status,d.accesslevel AS access,d.row_version AS revision FROM di_management.dataset d WHERE "+SCOPE+" ORDER BY d.title,d.t_id LIMIT 50 OFFSET #bind($offset)",p);
    });}
    public Map<String,Object> detail(ActorContext actor,UUID id){return runtime.performInTransaction(()->{
        var ctx=runtime.newContext();authority.requireAny(actor,id);
        var result=rows(ctx,"SELECT t_ili_tid AS id,title,adescription AS description,contactname,contactemail,publicationstatus AS status,accesslevel AS access,row_version AS revision,akind AS kind FROM di_management.dataset WHERE t_ili_tid=CAST(#bind($id) AS uuid)",Map.of("id",id.toString()));
        if(result.isEmpty())throw BusinessException.missing();var data=new HashMap<>(result.getFirst());
        data.put("themes",rows(ctx,"SELECT theme FROM di_management.datasettheme t JOIN di_management.dataset d ON d.t_id=t.dataset WHERE d.t_ili_tid=CAST(#bind($id) AS uuid) ORDER BY theme",Map.of("id",id.toString())).stream().map(r->r.get("theme").toString()).toList());
        data.put("keywords",rows(ctx,"SELECT keyword FROM di_management.datasetkeyword k JOIN di_management.dataset d ON d.t_id=k.dataset WHERE d.t_ili_tid=CAST(#bind($id) AS uuid) ORDER BY keyword",Map.of("id",id.toString())).stream().map(r->r.get("keyword").toString()).toList());
        return Collections.unmodifiableMap(data);
    });}
    public Map<String,Object> requestDataset(UUID id){return runtime.performInTransaction(()->{
        var result=rows(runtime.newContext(),"SELECT t_ili_tid AS id,title,accesslevel AS access FROM di_management.dataset WHERE t_ili_tid=CAST(#bind($id) AS uuid) AND publicationstatus='published'",Map.of("id",id.toString()));
        if(result.isEmpty())throw BusinessException.missing();return result.getFirst();
    });}
    public List<Map<String,Object>> requests(ActorContext actor,boolean own,int page){return runtime.performInTransaction(()->{
        var p=params(actor);p.put("offset",offset(page));
        String filter=own?"p.aissuer=#bind($issuer) AND p.asubject=#bind($subject)":SCOPE+" AND r.astatus='pending'";
        return rows(runtime.newContext(),AuthorityService.ELIGIBLE+"""
          SELECT r.t_ili_tid AS id,d.t_ili_tid AS dataset,d.title,r.astatus AS status,r.reason,r.requestedat,r.requestedvaliduntil,
                 r.row_version AS revision,p.t_ili_tid AS applicant,p.displayname,p.asubject AS subject
          FROM di_management.accessrequest r JOIN di_management.dataset d ON d.t_id=r.dataset
          JOIN di_management.principal p ON p.t_id=r.applicant WHERE
          """+filter+" ORDER BY r.requestedat DESC,r.t_id DESC LIMIT 50 OFFSET #bind($offset)",p);
    });}
    public List<Map<String,Object>> offices(ActorContext actor){return runtime.performInTransaction(()->rows(runtime.newContext(),AuthorityService.ELIGIBLE+"""
        SELECT DISTINCT o.t_ili_tid AS id,o.aname AS title FROM di_management.office o
        JOIN di_management.authorityassignment aa ON aa.office=o.t_id JOIN eligible e ON e.t_id=aa.principal CROSS JOIN di_now n
        WHERE o.aactive AND aa.capability='manage_metadata' AND aa.revokedat IS NULL AND aa.validfrom<=n.observed_at
          AND (aa.validuntil IS NULL OR n.observed_at<aa.validuntil) ORDER BY title LIMIT 50
        """,params(actor)));}
    public List<Map<String,Object>> principals(ActorContext actor,UUID dataset,String term){
        if(term==null || term.length()<2 || term.length()>100)throw BusinessException.invalid("Bitte 2 bis 100 Suchzeichen eingeben.");
        return runtime.performInTransaction(()->{var ctx=runtime.newContext();authority.require(ctx,actor,dataset,"manage_access");
            return rows(ctx,"SELECT t_ili_tid AS id,akind AS kind,displayname,aissuer AS issuer,asubject AS subject FROM di_management.principal WHERE aactive AND (position(lower(#bind($term)) in lower(coalesce(displayname,'')||' '||coalesce(asubject,'')))>0) ORDER BY displayname NULLS LAST,t_id LIMIT 20",Map.of("term",term));});
    }
    public List<Map<String,Object>> issues(ActorContext actor,UUID dataset){
        authority.requireAny(actor,dataset);
        return runtime.performInTransaction(()->rows(runtime.newContext(),"SELECT i.t_ili_tid AS id,i.label,i.issuekey,i.publicationstatus AS status,i.note,d.row_version AS revision FROM di_management.datasetissue i JOIN di_management.dataset d ON d.t_id=i.dataset WHERE d.t_ili_tid=CAST(#bind($id) AS uuid) ORDER BY i.issuekey LIMIT 100",Map.of("id",dataset.toString())));
    }
    public List<Map<String,Object>> grants(ActorContext actor,UUID dataset){return runtime.performInTransaction(()->{
        var ctx=runtime.newContext();authority.require(ctx,actor,dataset,"manage_access");
        return rows(ctx,"""
          SELECT g.t_ili_tid AS id,g.row_version AS revision,p.t_ili_tid AS beneficiary,p.akind AS kind,p.displayname,p.asubject AS subject,
            g.validfrom,g.validuntil,g.revokedat,CASE WHEN g.revokedat IS NOT NULL THEN 'entzogen'
            WHEN g.validfrom>timezone('UTC',clock_timestamp()) THEN 'zukünftig'
            WHEN g.validuntil<=timezone('UTC',clock_timestamp()) THEN 'abgelaufen' ELSE 'wirksam' END AS status
          FROM di_management.accessgrant g JOIN di_management.dataset d ON d.t_id=g.dataset
          JOIN di_management.principal p ON p.t_id=g.beneficiary WHERE d.t_ili_tid=CAST(#bind($id) AS uuid)
          ORDER BY g.t_id DESC LIMIT 100
          """,Map.of("id",dataset.toString()));
    });}
    public List<Map<String,Object>> effective(ActorContext actor){return runtime.performInTransaction(()->rows(runtime.newContext(),AuthorityService.ELIGIBLE+"""
        SELECT d.t_ili_tid AS dataset,d.title,p.akind AS via_kind,p.displayname,p.t_ili_tid AS via_principal,g.t_ili_tid AS grant_id,g.validuntil
        FROM di_management.accessgrant g JOIN eligible e ON e.t_id=g.beneficiary
        JOIN di_management.principal p ON p.t_id=e.t_id JOIN di_management.dataset d ON d.t_id=g.dataset CROSS JOIN di_now n
        WHERE d.publicationstatus='published' AND g.revokedat IS NULL AND g.validfrom<=n.observed_at
          AND (g.validuntil IS NULL OR n.observed_at<g.validuntil) ORDER BY d.title,g.t_id LIMIT 100
        """,params(actor)));}
    public List<Map<String,Object>> audit(ActorContext actor,UUID dataset,long before){
        authority.requireAny(actor,dataset);
        var result=new ArrayList<Map<String,Object>>();
        try(var connection=audit.getConnection();var statement=connection.prepareStatement("SELECT change_id,recorded_at,actor_subject,action_code,operation,table_name,command_id,request_id,old_values,new_values FROM audit.row_change WHERE aggregate_dataset_oid=? AND change_id<? ORDER BY change_id DESC LIMIT 50")){
            statement.setString(1,dataset.toString());statement.setLong(2,before<=0?Long.MAX_VALUE:before);
            try(var r=statement.executeQuery()){var md=r.getMetaData();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=md.getColumnCount();i++)row.put(md.getColumnLabel(i),r.getObject(i)==null?"":r.getObject(i).toString());result.add(Collections.unmodifiableMap(row));}}
            return List.copyOf(result);
        }catch(java.sql.SQLException error){throw new BusinessException(503,"AUDIT_UNAVAILABLE","Die Historie ist vorübergehend nicht verfügbar.");}
    }
}
