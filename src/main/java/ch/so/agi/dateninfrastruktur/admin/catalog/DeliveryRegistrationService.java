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
/** Operator/backend registration of confirmed deliveries; no loader, upload endpoint or arbitrary URL fetch. */
public final class DeliveryRegistrationService {
    public record AcceptedVersion(UUID issue,UUID schema,String versionKey,int sequence,Instant acceptedAt,String sourceSha256,String sourceReference){}
    public record PreparedDistribution(UUID version,UUID distributionId,String format,String fileName,String mediaType,String storageProfile){}
    public record ObjectEvidence(long byteSize,String sha256){}
    @FunctionalInterface public interface PrivateObjectVerifier {ObjectEvidence verify(String storageProfile,String canonicalKey);}
    private final AuditedUnitOfWork unit;private final AuthorityService authority;private final PrivateObjectVerifier verifier;
    private final AggregateWriteGuard guard=new AggregateWriteGuard();private final CommandReceiptRepository receipts=new CommandReceiptRepository();
    public DeliveryRegistrationService(AuditedUnitOfWork unit,AuthorityService authority,PrivateObjectVerifier verifier){this.unit=unit;this.authority=authority;this.verifier=verifier;}
    private UUID execute(ActorContext actor,CommandContext command,String action,UUID dataset,long expected,Object payload,java.util.function.BiFunction<ObjectContext,Dataset,UUID> work){
        if(!action.equals(command.actionCode()))throw BusinessException.invalid("Ungültige Registrierungsaktion.");
        return unit.execute(actor,command,ctx->{var prior=receipts.findOrReserve(ctx,actor,command,CommandReceiptRepository.payloadHash(dataset,expected,payload));if(prior!=null)return prior.reference();
            authority.require(ctx,actor,dataset,"manage_metadata");guard.lockDataset(ctx,dataset,expected);authority.require(ctx,actor,dataset,"manage_metadata");
            var root=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(dataset)).selectOne(ctx);
            if("published".equals(root.getPublicationstatus()))authority.require(ctx,actor,dataset,"publish");
            var id=work.apply(ctx,root);receipts.complete(ctx,actor,command,new CommandReceiptRepository.Receipt(id,"COMMITTED"));return id;});
    }
    public UUID registerAcceptedVersion(ActorContext actor,CommandContext command,UUID dataset,long expected,AcceptedVersion input){
        if(input.versionKey()==null || input.versionKey().isBlank() || input.versionKey().length()>128 || input.sequence()<1 || input.acceptedAt()==null || (input.sourceSha256()!=null && !input.sourceSha256().matches("[0-9a-f]{64}")))throw BusinessException.invalid("Ungültige bestätigte Lieferreferenz.");
        return execute(actor,command,"register_version",dataset,expected,input,(ctx,root)->{
            var issue=ObjectSelect.query(Datasetissue.class).where(Datasetissue.T_ILI_TID.eq(input.issue())).selectOne(ctx);
            if(issue==null || !dataset.equals(issue.getDataset1().getTIliTid()))throw BusinessException.invalid("Ausgabe gehört nicht zum Datensatz.");
            var schema=input.schema()==null?null:ObjectSelect.query(Dataschema.class).where(Dataschema.T_ILI_TID.eq(input.schema())).selectOne(ctx);
            if(input.schema()!=null && schema==null)throw BusinessException.invalid("Schema nicht bekannt.");
            var row=ctx.newObject(Dataversion.class);row.setTIliTid(UUID.randomUUID());row.setIssue1(issue);row.setAschema1(schema);row.setVersionkey(input.versionKey());row.setAsequence(input.sequence());row.setAcceptedat(UtcTime.toDatabase(input.acceptedAt()));row.setSourcesha256(input.sourceSha256());row.setSourcereference(input.sourceReference());row.setValidationstatus("unknown");row.setAvailability("available");return row.getTIliTid();
        });
    }
    public UUID registerPrepared(ActorContext actor,CommandContext command,UUID dataset,long expected,PreparedDistribution input){
        if(input.distributionId()==null || !Set.of("csv","xlsx","parquet","other").contains(input.format()) || input.fileName()==null || !input.fileName().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || input.mediaType()==null || input.mediaType().isBlank() || input.mediaType().length()>255 || input.storageProfile()==null || !input.storageProfile().matches("[A-Za-z0-9_-]{1,80}"))throw BusinessException.invalid("Ungültige Dateiregistrierung.");
        return execute(actor,command,"register_distribution",dataset,expected,input,(ctx,root)->{
            var version=ObjectSelect.query(Dataversion.class).where(Dataversion.T_ILI_TID.eq(input.version())).selectOne(ctx);
            if(version==null || !dataset.equals(version.getIssue1().getDataset1().getTIliTid()) || !"available".equals(version.getAvailability()))throw BusinessException.invalid("Version gehört nicht zum Datensatz oder ist nicht verfügbar.");
            var row=ctx.newObject(Distribution.class);row.setTIliTid(input.distributionId());row.setVersion1(version);row.setAformat(input.format());row.setFilename(input.fileName());row.setMediatype(input.mediaType());row.setStorageprofile(input.storageProfile());row.setObjectkey("downloads/"+input.distributionId()+"/"+input.fileName());row.setAvailability("prepared");return row.getTIliTid();
        });
    }
    public UUID verifyAndActivate(ActorContext actor,CommandContext command,UUID dataset,long expected,UUID distribution){
        return execute(actor,command,"activate_distribution",dataset,expected,distribution,(ctx,root)->{
            var row=ObjectSelect.query(Distribution.class).where(Distribution.T_ILI_TID.eq(distribution)).selectOne(ctx);
            if(row==null || !dataset.equals(row.getVersion1().getIssue1().getDataset1().getTIliTid()) || !"prepared".equals(row.getAvailability()))throw BusinessException.conflict("Nur vorbereitete Dateien dieses Datensatzes können aktiviert werden.");
            var evidence=verifier.verify(row.getStorageprofile(),row.getObjectkey());
            if(evidence==null || evidence.byteSize()<0 || evidence.sha256()==null || !evidence.sha256().matches("[0-9a-f]{64}"))throw BusinessException.invalid("Private Datei konnte nicht bestätigt werden.");
            row.setBytesize(evidence.byteSize());row.setSha256(evidence.sha256());row.setAvailability("available");return distribution;
        });
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
