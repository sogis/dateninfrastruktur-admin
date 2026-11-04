package ch.so.agi.dateninfrastruktur.admin.operator;
import java.time.Instant;
import java.util.*;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
/** Constructed only by the explicit local operator, never registered as a browser service. */
public final class GroupMembershipService {
    private final AuditedUnitOfWork unit;private final ActorContext operator;
    public GroupMembershipService(AuditedUnitOfWork unit,ActorContext configuredOperator){this.unit=unit;this.operator=configuredOperator;}
    private void requireOperator(ActorContext actor){if(!operator.issuer().equals(actor.issuer()) || !operator.subject().equals(actor.subject()) || !"service".equals(actor.kind()))throw BusinessException.forbidden();}
    public UUID addMember(ActorContext actor,CommandContext command,UUID group,UUID member,Instant from,Instant until,String reason){
        requireOperator(actor);if(!"operator.add_member".equals(command.actionCode()) || from==null || (until!=null && !from.isBefore(until)) || reason==null || reason.isBlank() || reason.length()>4000)throw BusinessException.invalid("Ungültige Mitgliedschaft.");
        return unit.execute(actor,command,ctx->{var repo=new PrincipalRepository();repo.requireActive(ctx,actor);var g=repo.findByOid(ctx,group);var m=repo.findByOid(ctx,member);
            if(g==null || m==null || !g.isAactive() || !m.isAactive() || !"group".equals(g.getAkind()) || "group".equals(m.getAkind()) || !"urn:di:local-group".equals(g.getAissuer()))throw BusinessException.invalid("Nur aktive lokale Gruppen mit Personen oder Serviceidentitäten sind zulässig.");
            var row=ctx.newObject(Groupmembership.class);row.setTIliTid(UUID.randomUUID());row.setAgroup1(g);row.setAmember1(m);row.setValidfrom(UtcTime.toDatabase(from));row.setValiduntil(until==null?null:UtcTime.toDatabase(until));row.setReason(reason);return row.getTIliTid();});
    }
    public UUID revokeMembership(ActorContext actor,CommandContext command,UUID membership,long expected,String reason){
        requireOperator(actor);if(!"operator.revoke_membership".equals(command.actionCode()) || reason==null || reason.isBlank())throw BusinessException.invalid("Ungültiger Entzug.");
        return unit.execute(actor,command,ctx->{var rows=CayenneSql.rows(ctx,"SELECT row_version FROM di_management.groupmembership WHERE t_ili_tid=CAST(#bind($id) AS uuid) FOR UPDATE",Map.of("id",membership.toString()));
            if(rows.isEmpty())throw BusinessException.missing();if(((Number)rows.getFirst().get("row_version")).longValue()!=expected)throw new AggregateWriteGuard.StaleAggregateException();
            var row=ObjectSelect.query(Groupmembership.class).where(Groupmembership.T_ILI_TID.eq(membership)).selectOne(ctx);if(row.getRevokedat()!=null)throw BusinessException.conflict("Mitgliedschaft bereits beendet.");
            row.setRevokedby1(new PrincipalRepository().requireActive(ctx,actor));row.setRevokedat(CayenneSql.now(ctx));return membership;});
    }
}
