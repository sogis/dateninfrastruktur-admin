package ch.so.agi.dateninfrastruktur.admin.access;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;

/** Request, decision, grant, receipt and audit share one physical outer transaction. */
public final class AccessWorkflowService {
    public record Validity(Instant from,Instant until){}
    private final AuditedUnitOfWork unit;
    private final AuthorityService authority;
    private final PrincipalRepository principals=new PrincipalRepository();
    private final CommandReceiptRepository receipts=new CommandReceiptRepository();
    private final AggregateWriteGuard guard=new AggregateWriteGuard();
    public AccessWorkflowService(AuditedUnitOfWork unit,AuthorityService authority){this.unit=unit;this.authority=authority;}
    private UUID execute(ActorContext actor,CommandContext command,String action,String hash,
                         Function<ObjectContext,CommandReceiptRepository.Receipt> work){
        if(!action.equals(command.actionCode()))throw BusinessException.invalid("Ungültige Aktion.");
        return unit.execute(actor,command,context->{
            var previous=receipts.findOrReserve(context,actor,command,hash);
            if(previous!=null)return previous.reference();
            var result=work.apply(context);receipts.complete(context,actor,command,result);return result.reference();
        });
    }
    private CommandReceiptRepository.Receipt result(UUID id,String code){return new CommandReceiptRepository.Receipt(id,code);}
    private Dataset dataset(ObjectContext context,UUID id){
        var row=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(context);
        if(row==null)throw BusinessException.missing();return row;
    }
    // Every request/grant mutation acquires the root first, then its own row. Consistent order avoids deadlocks.
    private Dataset lockRoot(ObjectContext context,UUID id){
        var rows=CayenneSql.rows(context,"SELECT t_id FROM di_management.dataset WHERE t_ili_tid=CAST(#bind($id) AS uuid) FOR UPDATE",Map.of("id",id.toString()));
        if(rows.isEmpty())throw BusinessException.missing();return dataset(context,id);
    }
    private void restrictedPublished(Dataset dataset){
        if(!"published".equals(dataset.getPublicationstatus()))throw BusinessException.missing();
        if("open".equals(dataset.getAccesslevel()))throw new BusinessException(409,"NO_GRANT_REQUIRED","Dieser Datensatz ist frei zugänglich; eine Freigabe ist nicht nötig.");
    }
    private String reason(String value){
        if(value==null || value.isBlank() || value.length()>4000)throw BusinessException.invalid("Bitte eine Begründung mit höchstens 4000 Zeichen angeben.");
        return value.strip();
    }
    private Validity validity(Validity validity,LocalDateTime now){
        if(validity!=null && validity.from()==null)validity=new Validity(UtcTime.fromDatabase(now),validity.until());
        if(validity==null || validity.from()==null || (validity.until()!=null &&
            (!validity.from().isBefore(validity.until()) || !UtcTime.toDatabase(validity.until()).isAfter(now))))
            throw BusinessException.invalid("Das Gültigkeitsintervall ist ungültig oder bereits beendet.");
        return validity;
    }
    private boolean effective(ObjectContext context,ActorContext actor,UUID dataset){
        return Boolean.TRUE.equals(CayenneSql.rows(context,AuthorityService.ELIGIBLE+"""
          SELECT EXISTS(SELECT 1 FROM di_management.accessgrant g JOIN eligible e ON e.t_id=g.beneficiary
            JOIN di_management.dataset d ON d.t_id=g.dataset CROSS JOIN di_now n
            WHERE d.t_ili_tid=CAST(#bind($id) AS uuid) AND g.permission='read_data' AND g.revokedat IS NULL
            AND g.validfrom<=n.observed_at AND (g.validuntil IS NULL OR n.observed_at<g.validuntil)) AS allowed
          """,Map.of("issuer",actor.issuer(),"subject",actor.subject(),"id",dataset.toString())).getFirst().get("allowed"));
    }
    public UUID request(ActorContext actor,CommandContext command,UUID datasetId,String explanation,Instant until){
        String clean=reason(explanation);
        return execute(actor,command,"request",CommandReceiptRepository.payloadHash(datasetId,clean,until),ctx->{
            var root=lockRoot(ctx,datasetId);restrictedPublished(root);
            var applicant=principals.findOrRegisterVerifiedUser(ctx,actor,command);
            var now=CayenneSql.now(ctx);
            if(until!=null && !UtcTime.toDatabase(until).isAfter(now))throw BusinessException.invalid("Das gewünschte Enddatum liegt in der Vergangenheit.");
            if(effective(ctx,actor,datasetId))throw BusinessException.conflict("Ein Datenzugang ist bereits wirksam.");
            var pending=ObjectSelect.query(Accessrequest.class).where(Accessrequest.DATASET1.eq(root)
                .andExp(Accessrequest.APPLICANT1.dot(Principal.T_ILI_TID).eq(applicant.getTIliTid())).andExp(Accessrequest.ASTATUS.eq("pending"))).selectOne(ctx);
            if(pending!=null)throw BusinessException.conflict("Es besteht bereits eine offene Anfrage.");
            var request=ctx.newObject(Accessrequest.class);request.setTIliTid(UUID.randomUUID());
            request.setApplicant1(applicant);request.setDataset1(root);request.setAstatus("pending");
            request.setReason(clean);request.setRequestedat(now);request.setRequestedvaliduntil(until==null?null:UtcTime.toDatabase(until));
            return result(request.getTIliTid(),"REQUESTED");
        });
    }
    private Accessrequest lockedRequest(ObjectContext ctx,ActorContext actor,UUID id,long expected,boolean deciding){
        // Resolve only the immutable parent identifier before acquiring locks; no decision uses this initial snapshot.
        var rows=CayenneSql.rows(ctx,"SELECT d.t_ili_tid AS dataset,p.aissuer,p.asubject FROM di_management.accessrequest r JOIN di_management.dataset d ON d.t_id=r.dataset JOIN di_management.principal p ON p.t_id=r.applicant WHERE r.t_ili_tid=CAST(#bind($id) AS uuid)",Map.of("id",id.toString()));
        if(rows.isEmpty())throw BusinessException.missing();
        UUID dataset=UUID.fromString(rows.getFirst().get("dataset").toString());
        if(deciding)authority.require(ctx,actor,dataset,"manage_access");
        else if(!actor.issuer().equals(rows.getFirst().get("aissuer")) || !actor.subject().equals(rows.getFirst().get("asubject")))throw BusinessException.forbidden();
        lockRoot(ctx,dataset);
        if(deciding)authority.require(ctx,actor,dataset,"manage_access");
        guard.lockRequest(ctx,id,expected);
        var request=ObjectSelect.query(Accessrequest.class).where(Accessrequest.T_ILI_TID.eq(id)).selectOne(ctx);
        if(!"pending".equals(request.getAstatus()))throw BusinessException.conflict("Diese Anfrage ist bereits abgeschlossen.");
        return request;
    }
    public UUID cancel(ActorContext actor,CommandContext command,UUID requestId,long expected){
        return execute(actor,command,"cancel_request",CommandReceiptRepository.payloadHash(requestId,expected),ctx->{
            var request=lockedRequest(ctx,actor,requestId,expected,false);var person=principals.requireActive(ctx,actor);
            if(!person.getTIliTid().equals(request.getApplicant1().getTIliTid()))throw BusinessException.forbidden();
            request.setAstatus("cancelled");return result(requestId,"CANCELLED");
        });
    }
    public UUID decide(ActorContext actor,CommandContext command,UUID requestId,long expected,boolean approve,Validity validity,String explanation){
        String clean=reason(explanation);String action=approve?"approve":"deny";
        return execute(actor,command,action,CommandReceiptRepository.payloadHash(requestId,expected,approve,validity,clean),ctx->{
            var request=lockedRequest(ctx,actor,requestId,expected,true);var root=request.getDataset1();
            authority.require(ctx,actor,root.getTIliTid(),"manage_access");restrictedPublished(root);
            var applicant=request.getApplicant1();if(!applicant.isAactive())throw BusinessException.conflict("Das anfragende Konto ist deaktiviert.");
            var now=CayenneSql.now(ctx);var checked=approve?validity(validity,now):null;
            var decision=decision(ctx,actor,command,root,applicant,action,clean,now);decision.setRequest1(request);
            request.setAstatus(approve?"approved":"denied");
            if(approve)grant(ctx,root,applicant,decision,checked);
            return result(decision.getTIliTid(),approve?"APPROVED":"DENIED");
        });
    }
    private Accessdecision decision(ObjectContext ctx,ActorContext actor,CommandContext command,Dataset root,Principal beneficiary,String kind,String reason,LocalDateTime now){
        var row=ctx.newObject(Accessdecision.class);row.setTIliTid(UUID.randomUUID());row.setAkind(kind);
        row.setCommandid(command.commandId());row.setDecidedat(now);row.setReason(reason);
        row.setActor1(principals.requireActive(ctx,actor));row.setBeneficiary1(beneficiary);row.setDataset1(root);return row;
    }
    private Accessgrant grant(ObjectContext ctx,Dataset root,Principal beneficiary,Accessdecision decision,Validity validity){
        var row=ctx.newObject(Accessgrant.class);row.setTIliTid(UUID.randomUUID());row.setDataset1(root);
        row.setBeneficiary1(beneficiary);row.setGrantingdecision1(decision);row.setPermission("read_data");
        row.setValidfrom(UtcTime.toDatabase(validity.from()));row.setValiduntil(validity.until()==null?null:UtcTime.toDatabase(validity.until()));return row;
    }
    public UUID grantDirect(ActorContext actor,CommandContext command,UUID datasetId,long expected,UUID beneficiaryId,Validity validity,String explanation){
        String clean=reason(explanation);
        return execute(actor,command,"direct_grant",CommandReceiptRepository.payloadHash(datasetId,expected,beneficiaryId,validity,clean),ctx->{
            authority.require(ctx,actor,datasetId,"manage_access");guard.lockDataset(ctx,datasetId,expected);var root=dataset(ctx,datasetId);
            authority.require(ctx,actor,datasetId,"manage_access");restrictedPublished(root);
            var beneficiary=principals.findByOid(ctx,beneficiaryId);
            if(beneficiary==null || !beneficiary.isAactive())throw BusinessException.invalid("Die gewählte bekannte Identität ist nicht aktiv.");
            var now=CayenneSql.now(ctx);var checked=validity(validity,now);
            var decision=decision(ctx,actor,command,root,beneficiary,"direct_grant",clean,now);
            return result(grant(ctx,root,beneficiary,decision,checked).getTIliTid(),"GRANTED");
        });
    }
    public UUID revoke(ActorContext actor,CommandContext command,UUID grantId,long expected,String explanation){
        String clean=reason(explanation);
        return execute(actor,command,"revoke",CommandReceiptRepository.payloadHash(grantId,expected,clean),ctx->{
            var rows=CayenneSql.rows(ctx,"SELECT d.t_ili_tid AS dataset FROM di_management.accessgrant g JOIN di_management.dataset d ON d.t_id=g.dataset WHERE g.t_ili_tid=CAST(#bind($id) AS uuid)",Map.of("id",grantId.toString()));
            if(rows.isEmpty())throw BusinessException.missing();
            var root=lockRoot(ctx,UUID.fromString(rows.getFirst().get("dataset").toString()));
            authority.require(ctx,actor,root.getTIliTid(),"manage_access");
            var lock=CayenneSql.rows(ctx,"SELECT row_version FROM di_management.accessgrant WHERE t_ili_tid=CAST(#bind($id) AS uuid) FOR UPDATE",Map.of("id",grantId.toString())).getFirst();
            if(((Number)lock.get("row_version")).longValue()!=expected)throw new AggregateWriteGuard.StaleAggregateException();
            var grant=ObjectSelect.query(Accessgrant.class).where(Accessgrant.T_ILI_TID.eq(grantId)).selectOne(ctx);
            if(grant.getRevokedat()!=null)throw BusinessException.conflict("Diese Freigabe wurde bereits entzogen.");
            var now=CayenneSql.now(ctx);var decision=decision(ctx,actor,command,root,grant.getBeneficiary1(),"revoke",clean,now);
            grant.setRevokedat(now);grant.setRevocationdecision1(decision);return result(grantId,"REVOKED");
        });
    }
}
