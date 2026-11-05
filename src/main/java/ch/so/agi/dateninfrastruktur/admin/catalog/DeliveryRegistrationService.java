package ch.so.agi.dateninfrastruktur.admin.catalog;
import java.time.Instant;
import java.util.*;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.access.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
/** Single validated CSV acceptance core and explicit withdrawal of existing files. */
public final class DeliveryRegistrationService {
    private final AuditedUnitOfWork unit;private final AuthorityService authority;
    private final AggregateWriteGuard guard=new AggregateWriteGuard();private final CommandReceiptRepository receipts=new CommandReceiptRepository();
    public DeliveryRegistrationService(AuditedUnitOfWork unit,AuthorityService authority){this.unit=unit;this.authority=authority;}
    private UUID execute(ActorContext actor,CommandContext command,String action,UUID dataset,long expected,Object payload,java.util.function.BiFunction<ObjectContext,Dataset,UUID> work){
        if(!action.equals(command.actionCode()))throw BusinessException.invalid("Ungültige Registrierungsaktion.");
        return unit.execute(actor,command,ctx->{var prior=receipts.findOrReserve(ctx,actor,command,CommandReceiptRepository.payloadHash(dataset,expected,payload));if(prior!=null)return prior.reference();
            authority.require(ctx,actor,dataset,"manage_metadata");guard.lockDataset(ctx,dataset,expected);authority.require(ctx,actor,dataset,"manage_metadata");
            var root=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(dataset)).selectOne(ctx);
            if("published".equals(root.getPublicationstatus()))authority.require(ctx,actor,dataset,"publish");
            var id=work.apply(ctx,root);receipts.complete(ctx,actor,command,new CommandReceiptRepository.Receipt(id,"COMMITTED"));return id;});
    }
    /** The central CSV acceptance core, called inside PublicationService's audited transaction. */
    public UUID acceptValidatedCsv(ObjectContext ctx,ActorContext executor,CommandContext command,
            ch.so.agi.dateninfrastruktur.admin.ingestion.ResultEvidenceValidator.VerifiedResult evidence,UUID schemaId){
        if(!"service".equals(executor.kind()) || !"ingestion.complete".equals(command.actionCode()) || org.apache.cayenne.tx.BaseTransaction.getThreadTransaction()==null)throw BusinessException.forbidden();
        var plan=evidence.plan();var result=evidence.manifest();
        var issue=ObjectSelect.query(Datasetissue.class).where(Datasetissue.T_ILI_TID.eq(plan.issueId())).selectOne(ctx);
        var schema=ObjectSelect.query(Dataschema.class).where(Dataschema.T_ILI_TID.eq(schemaId)).selectOne(ctx);
        var initiator=new PrincipalRepository().findByOid(ctx,plan.initiatorPrincipalId());
        if(issue==null || schema==null || initiator==null || !plan.datasetId().equals(issue.getDataset1().getTIliTid()))throw BusinessException.forbidden();
        long next=((Number)CayenneSql.rows(ctx,"SELECT coalesce(max(v.asequence),0)+1 AS next FROM di_management.dataversion v JOIN di_management.datasetissue i ON i.t_id=v.issue WHERE i.t_ili_tid=CAST(#bind($issue) AS uuid)",Map.of("issue",plan.issueId().toString())).getFirst().get("next")).longValue();
        if(next>Integer.MAX_VALUE)throw BusinessException.conflict("Versionsfolge erschöpft.");
        var now=CayenneSql.now(ctx);var version=ctx.newObject(Dataversion.class);version.setTIliTid(plan.reservedVersionId());version.setIssue1(issue);version.setAschema1(schema);version.setVersionkey(plan.reservedVersionId().toString());version.setAsequence((int)next);version.setAcceptedat(now);version.setSourcesha256(plan.input().sha256());version.setSourcereference("urn:di:submission:"+plan.submissionId());version.setValidationstatus("success");version.setValidationat(now);version.setValidationerrors(0L);version.setObjectcount(result.snapshot().rowCount());version.setAttributecount((long)plan.orderedColumns().size());version.setAvailability("available");
        for(var output:plan.outputs()){
            var attestation=result.outputs().artifacts().stream().filter(a->a.distributionId().equals(output.distributionId())).findFirst().orElseThrow();
            var distribution=ctx.newObject(Distribution.class);distribution.setTIliTid(output.distributionId());distribution.setVersion1(version);distribution.setAformat(output.format());distribution.setFilename(output.fileName());distribution.setMediatype(output.mediaType());distribution.setObjectkey(output.objectKey());distribution.setBytesize(attestation.byteSize());distribution.setSha256(attestation.sha256());distribution.setAvailability("available");
        }
        issue.setCurrentversion1(version);issue.setModified(now.toLocalDate());
        var action=ctx.newObject(Catalogaction.class);action.setTIliTid(UUID.randomUUID());action.setDataset1(issue.getDataset1());action.setAffectedversion1(version);action.setActor1(new PrincipalRepository().requireActive(ctx,executor));action.setInitiator1(initiator);action.setActioncode("accept_delivery");action.setCommandid(command.commandId());action.setReason("Validierte CSV-Lieferung "+plan.submissionId());action.setPerformedat(now);
        return version.getTIliTid();
    }
    public UUID withdraw(ActorContext actor,CommandContext command,UUID dataset,long expected,UUID distribution,String reason){
        if(reason==null || reason.isBlank() || reason.length()>4000)throw BusinessException.invalid("Bitte eine Begründung angeben.");
        return execute(actor,command,"withdraw_distribution",dataset,expected,List.of(distribution,reason),(ctx,root)->{
            authority.require(ctx,actor,dataset,"publish");var row=ObjectSelect.query(Distribution.class).where(Distribution.T_ILI_TID.eq(distribution)).selectOne(ctx);
            if(row==null || !dataset.equals(row.getVersion1().getIssue1().getDataset1().getTIliTid()) || !"available".equals(row.getAvailability()))throw BusinessException.conflict("Diese Datei ist nicht verfügbar.");
            row.setAvailability("withdrawn");var action=ctx.newObject(Catalogaction.class);action.setTIliTid(UUID.randomUUID());action.setDataset1(root);action.setAffecteddistribution1(row);action.setActor1(new PrincipalRepository().requireActive(ctx,actor));action.setActioncode("withdraw_distribution");action.setCommandid(command.commandId());action.setReason(reason);action.setPerformedat(CayenneSql.now(ctx));return distribution;
        });
    }
}
