package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.time.*;
import java.util.*;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.access.*;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;

/** Delegates only submit_data; manage_submitters has no delegation endpoint. */
public final class SubmitterAdministrationService {
    public record Scope(String officeId,UUID datasetId){public Scope{if((officeId==null)==(datasetId==null))throw IngestionProblem.invalid("INVALID_SCOPE");}}
    public record Grant(Scope scope,UUID beneficiary,long expectedScopeVersion,Instant validFrom,Instant validUntil,String reason){}
    private final AuditedUnitOfWork unit;private final AuthorityService authority;private final SubmissionAuthorizationService authorization;
    private final CommandReceiptRepository receipts=new CommandReceiptRepository();private final PrincipalRepository principals=new PrincipalRepository();
    public SubmitterAdministrationService(AuditedUnitOfWork unit,AuthorityService authority,SubmissionAuthorizationService authorization){this.unit=unit;this.authority=authority;this.authorization=authorization;}
    public void requireManage(ObjectContext context,ActorContext actor,Scope scope){
        authorization.requireUser(context,actor);
        if(scope.datasetId()!=null){if(!authority.allowed(context,actor,scope.datasetId(),"manage_submitters"))throw IngestionProblem.forbidden();}
        else try{authority.requireOffice(context,actor,scope.officeId(),"manage_submitters");}catch(BusinessException error){throw IngestionProblem.forbidden();}
    }
    private static void lockScope(ObjectContext context,Scope scope,Long expected){
        String table=scope.datasetId()!=null?"dataset":"office";String id=scope.datasetId()!=null?scope.datasetId().toString():scope.officeId();
        var rows=CayenneSql.rows(context,"SELECT row_version FROM di_management."+table+" WHERE t_ili_tid=#bind($id)::"+(scope.datasetId()!=null?"uuid":"text")+" FOR UPDATE",Map.of("id",id));
        if(rows.size()!=1)throw IngestionProblem.forbidden();
        if(expected!=null && ((Number)rows.getFirst().get("row_version")).longValue()!=expected)throw IngestionProblem.conflict("STALE_REVISION");
    }
    private static String reason(String reason){if(reason==null || reason.isBlank() || reason.length()>4000)throw IngestionProblem.invalid("INVALID_INPUT");return reason;}
    private static void action(CommandContext command,String action){if(!action.equals(command.actionCode()))throw IngestionProblem.invalid("INVALID_INPUT");}
    public UUID grant(ActorContext actor,CommandContext command,Grant input){
        action(command,"submitter.grant");reason(input.reason());if(input.scope()==null || input.beneficiary()==null)throw IngestionProblem.invalid("INVALID_INPUT");
        if(input.validFrom()!=null && input.validUntil()!=null && !input.validFrom().isBefore(input.validUntil()))throw IngestionProblem.invalid("INVALID_INTERVAL");
        return unit.execute(actor,command,context->{
            requireManage(context,actor,input.scope());
            var old=receipts.findOrReserve(context,actor,command,CanonicalJson.digest(input));if(old!=null)return old.reference();
            lockScope(context,input.scope(),input.expectedScopeVersion());requireManage(context,actor,input.scope());
            var actorRow=principals.requireActive(context,actor);var beneficiary=principals.findByOid(context,input.beneficiary());
            if(beneficiary==null || !beneficiary.isAactive() || !Set.of("user","group","service").contains(beneficiary.getAkind()))throw IngestionProblem.forbidden();
            var now=CayenneSql.now(context);var from=input.validFrom()==null?now:UtcTime.toDatabase(input.validFrom());var until=input.validUntil()==null?null:UtcTime.toDatabase(input.validUntil());
            if(until!=null && (!from.isBefore(until) || !now.isBefore(until)))throw IngestionProblem.invalid("INVALID_INTERVAL");
            var assignment=context.newObject(Authorityassignment.class);UUID id=UUID.randomUUID();assignment.setTIliTid(id);
            assignment.setCapability("submit_data");assignment.setReason(input.reason());assignment.setValidfrom(from);assignment.setValiduntil(until);assignment.setPrincipal1(beneficiary);assignment.setAssignedby1(actorRow);
            if(input.scope().datasetId()!=null)assignment.setDataset1(ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(input.scope().datasetId())).selectOne(context));
            else assignment.setOffice1(ObjectSelect.query(Office.class).where(Office.T_ILI_TID.eq(input.scope().officeId())).selectOne(context));
            receipts.complete(context,actor,command,new CommandReceiptRepository.Receipt(id,"COMMITTED"));return id;
        });
    }
    public UUID revoke(ActorContext actor,CommandContext command,UUID assignmentId,long expectedAssignmentVersion,String reason){
        action(command,"submitter.revoke");reason(reason);
        return unit.execute(actor,command,context->{
            authorization.requireUser(context,actor);
            var assignment=ObjectSelect.query(Authorityassignment.class).where(Authorityassignment.T_ILI_TID.eq(assignmentId)).selectOne(context);
            if(assignment==null || !"submit_data".equals(assignment.getCapability()))throw IngestionProblem.forbidden();
            var scope=assignment.getDataset1()!=null?new Scope(null,assignment.getDataset1().getTIliTid()):new Scope(assignment.getOffice1().getTIliTid(),null);
            requireManage(context,actor,scope);
            var old=receipts.findOrReserve(context,actor,command,CanonicalJson.digest(Map.of("assignmentId",assignmentId,"reason",reason)));if(old!=null)return old.reference();
            lockScope(context,scope,null);requireManage(context,actor,scope);
            var version=CayenneSql.rows(context,"SELECT row_version FROM di_management.authorityassignment WHERE t_ili_tid=CAST(#bind($id) AS uuid) FOR UPDATE",Map.of("id",assignmentId.toString()));
            if(version.size()!=1 || ((Number)version.getFirst().get("row_version")).longValue()!=expectedAssignmentVersion)throw IngestionProblem.conflict("STALE_REVISION");
            if(assignment.getRevokedat()==null){assignment.setRevokedat(CayenneSql.now(context));assignment.setRevokedby1(principals.requireActive(context,actor));assignment.setRevocationreason(reason);}
            receipts.complete(context,actor,command,new CommandReceiptRepository.Receipt(assignmentId,"COMMITTED"));return assignmentId;
        });
    }
}
