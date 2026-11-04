package ch.so.agi.dateninfrastruktur.admin.audit;
import java.util.Map;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.SQLExec;
public final class AuditContextWriter {
    public void bind(ObjectContext context, ActorContext actor, CommandContext command) {
        // SQLExec participates in the OUTER Cayenne transaction in M3.
        SQLExec.query("SELECT set_config('di.actor_issuer',#bind($issuer),true)," +
            "set_config('di.actor_subject',#bind($subject),true),set_config('di.actor_kind',#bind($kind),true)," +
            "set_config('di.actor_principal_oid',#bind($principal),true),set_config('di.request_id',#bind($request),true)," +
            "set_config('di.command_id',#bind($command),true),set_config('di.action',#bind($action),true)")
            .params(Map.of("issuer",actor.issuer(),"subject",actor.subject(),"kind",actor.kind(),
                "principal",actor.principalOid()==null?"":actor.principalOid().toString(),
                "request",command.requestId().toString(),"command",command.commandId().toString(),"action",command.actionCode()))
            .execute(context);
    }
}
