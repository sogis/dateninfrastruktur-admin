package ch.so.agi.dateninfrastruktur.admin.access;

import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.runtime.CayenneRuntime;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SubmitterAdministrationService {
    public record Scope(String officeId, UUID datasetId) {
        public Scope {
            if ((officeId == null) == (datasetId == null) || (officeId != null && (officeId.isBlank() || officeId.length() > 1024))) {
                throw BusinessException.invalid("Genau einen gültigen Geltungsbereich angeben.");
            }
        }
    }
    public record Grant(Scope scope, UUID beneficiary, Instant validFrom, Instant validUntil, String reason) {}
    private final CayenneRuntime runtime;
    private final AuthorityService authority;
    private final PrincipalRepository principals = new PrincipalRepository();

    public SubmitterAdministrationService(CayenneRuntime runtime, AuthorityService authority) {
        this.runtime = runtime;
        this.authority = authority;
    }

    public void requireManage(ObjectContext context, ActorContext actor, Scope scope) {
        if (!"user".equals(principals.requireActive(context, actor).getAkind())) throw BusinessException.forbidden();
        if (scope.datasetId() != null) authority.require(context, actor, scope.datasetId(), "manage_submitters");
        else authority.requireOffice(context, actor, scope.officeId(), "manage_submitters");
    }

    public UUID grant(ActorContext actor, Grant input) {
        checkReason(input.reason());
        return ManagementTransactions.write(runtime, context -> {
            requireManage(context, actor, input.scope());
            var beneficiary = principals.findByOid(context, input.beneficiary());
            if (beneficiary == null || !beneficiary.isAactive() || !Set.of("user", "group").contains(beneficiary.getAkind())) {
                throw BusinessException.forbidden();
            }
            var now = CayenneSql.now(context);
            var from = input.validFrom() == null ? now : UtcTime.toDatabase(input.validFrom());
            var until = input.validUntil() == null ? null : UtcTime.toDatabase(input.validUntil());
            if (until != null && (!from.isBefore(until) || !now.isBefore(until))) throw BusinessException.invalid("Ungültiges Zeitintervall.");
            var assignment = context.newObject(Authorityassignment.class);
            assignment.setTIliTid(UUID.randomUUID());
            assignment.setTBasket(ManagementBaskets.require(context, ManagementBaskets.Topic.Access));
            assignment.setCapability("submit_data");
            assignment.setReason(input.reason().strip());
            assignment.setValidfrom(from);
            assignment.setValiduntil(until);
            assignment.setPrincipal1(beneficiary);
            assignment.setAssignedby1(principals.requireActive(context, actor));
            if (input.scope().datasetId() != null) {
                assignment.setDataset1(ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(input.scope().datasetId())).selectOne(context));
            } else {
                assignment.setOffice1(ObjectSelect.query(Office.class).where(Office.T_ILI_TID.eq(input.scope().officeId())).selectOne(context));
            }
            return assignment.getTIliTid();
        });
    }

    public UUID revoke(ActorContext actor, Scope scope, UUID assignmentId, String reason) {
        checkReason(reason);
        return ManagementTransactions.write(runtime, context -> {
            requireManage(context, actor, scope);
            var rows = CayenneSql.rows(context, """
                SELECT t_id FROM di_management.authorityassignment WHERE t_ili_tid=CAST(#bind($id) AS uuid) FOR UPDATE
                """, Map.of("id", assignmentId.toString()));
            if (rows.isEmpty()) throw BusinessException.missing();
            var assignment = ObjectSelect.query(Authorityassignment.class).where(Authorityassignment.T_ILI_TID.eq(assignmentId)).selectOne(context);
            if (!"submit_data".equals(assignment.getCapability())) throw BusinessException.forbidden();
            Scope actual = assignment.getDataset1() != null ? new Scope(null, assignment.getDataset1().getTIliTid())
                : new Scope(assignment.getOffice1().getTIliTid(), null);
            if (!scope.equals(actual)) throw BusinessException.forbidden();
            requireManage(context, actor, actual);
            if (assignment.getRevokedat() != null) throw BusinessException.conflict("Das Lieferrecht ist bereits widerrufen.");
            assignment.setRevokedat(CayenneSql.now(context));
            assignment.setRevokedby1(principals.requireActive(context, actor));
            assignment.setRevocationreason(reason.strip());
            return assignmentId;
        });
    }

    private static void checkReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 4000) throw BusinessException.invalid("Ungültige Begründung.");
    }
}
