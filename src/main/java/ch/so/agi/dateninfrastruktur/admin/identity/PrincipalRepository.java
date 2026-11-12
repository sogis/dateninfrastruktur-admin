package ch.so.agi.dateninfrastruktur.admin.identity;
import java.util.Map;
import java.util.UUID;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.model.Principal;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
import ch.so.agi.dateninfrastruktur.admin.persistence.ManagementBaskets;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
public final class PrincipalRepository {
    public Principal findByExternalIdentity(ObjectContext context,String issuer,String subject){
        return ObjectSelect.query(Principal.class).where(Principal.AISSUER.eq(issuer).andExp(Principal.ASUBJECT.eq(subject))).selectOne(context);
    }
    public Principal findByOid(ObjectContext context,UUID oid){
        return ObjectSelect.query(Principal.class).where(Principal.T_ILI_TID.eq(oid)).selectOne(context);
    }
    public Principal requireActive(ObjectContext context,ActorContext actor){
        var principal=findByExternalIdentity(context,actor.issuer(),actor.subject());
        if(principal==null || !principal.isAactive() || "group".equals(principal.getAkind()))throw BusinessException.forbidden();
        return principal;
    }
    public Principal findOrRegisterVerifiedUser(ObjectContext context,ActorContext actor){
        if(!"user".equals(actor.kind()))throw BusinessException.forbidden();
        // Only registration of the same external identity is serialized, not unrelated commands.
        CayenneSql.rows(context,"SELECT pg_advisory_xact_lock(hashtextextended(jsonb_build_array("+
            "#bind($issuer),#bind($subject))::text,0))",Map.of("issuer",actor.issuer(),"subject",actor.subject()));
        var found=findByExternalIdentity(context,actor.issuer(),actor.subject());
        if(found!=null){if(!found.isAactive() || !"user".equals(found.getAkind()))throw BusinessException.forbidden();return found;}
        var created=context.newObject(Principal.class);created.setTIliTid(UUID.randomUUID());
        created.setTBasket(ManagementBaskets.require(context, ManagementBaskets.Topic.Access));
        created.setAissuer(actor.issuer());created.setAsubject(actor.subject());created.setAkind("user");
        created.setAactive(true);created.setProvisionedat(CayenneSql.now(context));
        return created;
    }
}
