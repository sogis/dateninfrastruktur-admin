package ch.so.agi.dateninfrastruktur.admin.access;
import java.util.Map;
import java.util.UUID;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.runtime.CayenneRuntime;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;

public final class AuthorityService {
    public static final String ELIGIBLE="""
        WITH di_now AS MATERIALIZED (SELECT timezone('UTC',clock_timestamp()) AS observed_at),
        actor AS (SELECT t_id FROM di_management.principal WHERE aissuer=#bind($issuer)
          AND asubject=#bind($subject) AND aactive AND akind IN ('user','service')),
        eligible AS (SELECT t_id FROM actor UNION
          SELECT gm.agroup FROM di_management.groupmembership gm JOIN actor a ON gm.amember=a.t_id
          JOIN di_management.principal g ON g.t_id=gm.agroup CROSS JOIN di_now n
          WHERE g.aactive AND g.akind='group' AND gm.revokedat IS NULL
          AND gm.validfrom<=n.observed_at AND (gm.validuntil IS NULL OR n.observed_at<gm.validuntil))
        """;
    private final CayenneRuntime runtime;
    public AuthorityService(CayenneRuntime runtime){this.runtime=runtime;}
    public boolean allowed(ObjectContext context,ActorContext actor,UUID dataset,String capability){
        var row=CayenneSql.rows(context,ELIGIBLE+"""
            SELECT EXISTS(SELECT 1 FROM di_management.authorityassignment aa
            JOIN eligible e ON e.t_id=aa.principal JOIN di_management.dataset d
              ON d.t_ili_tid=CAST(#bind($dataset) AS uuid) CROSS JOIN di_now n
            WHERE aa.capability=#bind($capability) AND (aa.dataset=d.t_id OR (aa.office=d.owneroffice
                AND EXISTS (SELECT 1 FROM di_management.office o WHERE o.t_id=aa.office AND o.aactive)))
            AND aa.revokedat IS NULL AND aa.validfrom<=n.observed_at
            AND (aa.validuntil IS NULL OR n.observed_at<aa.validuntil)) AS allowed
            """,Map.of("issuer",actor.issuer(),"subject",actor.subject(),"dataset",dataset.toString(),"capability",capability)).getFirst();
        return Boolean.TRUE.equals(row.get("allowed"));
    }
    public void requireOffice(ObjectContext context,ActorContext actor,String office,String capability){
        boolean allowed=Boolean.TRUE.equals(CayenneSql.rows(context,ELIGIBLE+"""
            SELECT EXISTS(SELECT 1 FROM di_management.authorityassignment aa JOIN eligible e ON e.t_id=aa.principal
            JOIN di_management.office o ON o.t_id=aa.office CROSS JOIN di_now n
            WHERE o.t_ili_tid=#bind($office) AND o.aactive AND aa.capability=#bind($capability)
            AND aa.revokedat IS NULL AND aa.validfrom<=n.observed_at
            AND (aa.validuntil IS NULL OR n.observed_at<aa.validuntil)) AS allowed
            """,Map.of("issuer",actor.issuer(),"subject",actor.subject(),"office",office,"capability",capability)).getFirst().get("allowed"));
        if(!allowed)throw BusinessException.forbidden();
    }
    public void require(ObjectContext context,ActorContext actor,UUID dataset,String capability){
        if(!allowed(context,actor,dataset,capability))throw BusinessException.forbidden();
    }
    public void requireAny(ActorContext actor,UUID dataset){
        boolean permitted=runtime.performInTransaction(()->{
            var context=runtime.newContext();
            return allowed(context,actor,dataset,"manage_metadata")||allowed(context,actor,dataset,"publish")||
                allowed(context,actor,dataset,"manage_access");
        });
        if(!permitted)throw BusinessException.forbidden();
    }
    public record Capabilities(boolean metadata,boolean publish,boolean access,boolean submit,boolean submitters){}
    public Capabilities describe(ActorContext actor,UUID dataset){
        return runtime.performInTransaction(()->{
            var context=runtime.newContext();
            return new Capabilities(allowed(context,actor,dataset,"manage_metadata"),
                allowed(context,actor,dataset,"publish"),allowed(context,actor,dataset,"manage_access"),
                allowed(context,actor,dataset,"submit_data"),allowed(context,actor,dataset,"manage_submitters"));
        });
    }
}
