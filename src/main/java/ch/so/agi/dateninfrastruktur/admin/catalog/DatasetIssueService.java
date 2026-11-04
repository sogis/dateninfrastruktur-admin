package ch.so.agi.dateninfrastruktur.admin.catalog;
import java.util.*;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.ObjectContext;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import ch.so.agi.dateninfrastruktur.admin.access.*;
public final class DatasetIssueService {
    private final AuditedUnitOfWork unit;private final AuthorityService authority;
    private final AggregateWriteGuard guard=new AggregateWriteGuard();private final CommandReceiptRepository receipts=new CommandReceiptRepository();
    public DatasetIssueService(AuditedUnitOfWork unit,AuthorityService authority){this.unit=unit;this.authority=authority;}
    private UUID execute(ActorContext actor,CommandContext command,String action,UUID dataset,long version,String hash,java.util.function.BiFunction<ObjectContext,Dataset,UUID> work){
        if(!action.equals(command.actionCode()))throw BusinessException.invalid("Ungültige Ausgabeaktion.");
        return unit.execute(actor,command,ctx->{var previous=receipts.findOrReserve(ctx,actor,command,hash);if(previous!=null)return previous.reference();
            authority.require(ctx,actor,dataset,"manage_metadata");guard.lockDataset(ctx,dataset,version);authority.require(ctx,actor,dataset,"manage_metadata");
            var root=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(dataset)).selectOne(ctx);
            if("published".equals(root.getPublicationstatus()))authority.require(ctx,actor,dataset,"publish");
            var result=work.apply(ctx,root);receipts.complete(ctx,actor,command,new CommandReceiptRepository.Receipt(result,"COMMITTED"));return result;});
    }
    public UUID createIssue(ActorContext actor,CommandContext command,UUID dataset,long version,String key,String label){
        if(key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || label==null || label.isBlank() || label.length()>255)throw BusinessException.invalid("Bitte Ausgabeschlüssel und Bezeichnung angeben.");
        return execute(actor,command,"create_issue",dataset,version,CommandReceiptRepository.payloadHash(dataset,version,key,label),(ctx,root)->{
            if(!"series".equals(root.getAkind()))throw BusinessException.conflict("Einzelangebote behalten ihre interne Standardausgabe.");
            var row=ctx.newObject(Datasetissue.class);row.setTIliTid(UUID.randomUUID());row.setDataset1(root);row.setIssuekey(key);row.setLabel(label);row.setPublicationstatus(root.getPublicationstatus());row.setModified(CayenneSql.now(ctx).toLocalDate());return row.getTIliTid();
        });
    }
    public UUID updateIssueMetadata(ActorContext actor,CommandContext command,UUID dataset,long version,UUID issue,String label,String note,String status){
        if(label==null || label.isBlank() || label.length()>255 || (note!=null && note.length()>100000) || !Set.of("draft","in_review","published","archived").contains(status))throw BusinessException.invalid("Ungültige Ausgabemetadaten.");
        return execute(actor,command,"update_issue",dataset,version,CommandReceiptRepository.payloadHash(dataset,version,issue,label,note,status),(ctx,root)->{
            var row=ObjectSelect.query(Datasetissue.class).where(Datasetissue.T_ILI_TID.eq(issue)).selectOne(ctx);
            if(row==null || !root.getTIliTid().equals(row.getDataset1().getTIliTid()))throw BusinessException.invalid("Die Ausgabe gehört nicht zu diesem Datensatz.");
            if(Set.of("published","archived").contains(status) || "published".equals(row.getPublicationstatus()))authority.require(ctx,actor,dataset,"publish");
            if(("archived".equals(row.getPublicationstatus()) && !"archived".equals(status)) || ("published".equals(row.getPublicationstatus()) && !Set.of("published","archived").contains(status)))throw BusinessException.conflict("Unzulässiger Statuswechsel der Ausgabe.");
            row.setLabel(label);row.setNote(note);row.setPublicationstatus(status);row.setModified(CayenneSql.now(ctx).toLocalDate());return dataset;
        });
    }
    public UUID setCurrentIssue(ActorContext actor,CommandContext command,UUID dataset,long version,UUID issue){
        return execute(actor,command,"set_current_issue",dataset,version,CommandReceiptRepository.payloadHash(dataset,version,issue),(ctx,root)->{
            var row=ObjectSelect.query(Datasetissue.class).where(Datasetissue.T_ILI_TID.eq(issue)).selectOne(ctx);
            if(row==null || !root.getTIliTid().equals(row.getDataset1().getTIliTid()))throw BusinessException.invalid("Die Ausgabe gehört nicht zu diesem Datensatz.");root.setCurrentissue1(row);return dataset;
        });
    }
    public UUID setCurrentVersion(ActorContext actor,CommandContext command,UUID dataset,long version,UUID issue,UUID dataVersion){
        return execute(actor,command,"set_current_version",dataset,version,CommandReceiptRepository.payloadHash(dataset,version,issue,dataVersion),(ctx,root)->{
            var row=ObjectSelect.query(Datasetissue.class).where(Datasetissue.T_ILI_TID.eq(issue)).selectOne(ctx);
            var candidate=ObjectSelect.query(Dataversion.class).where(Dataversion.T_ILI_TID.eq(dataVersion)).selectOne(ctx);
            if(row==null || candidate==null || !root.getTIliTid().equals(row.getDataset1().getTIliTid()) || !issue.equals(candidate.getIssue1().getTIliTid()) || !"available".equals(candidate.getAvailability()))throw BusinessException.invalid("Die aktuelle Version muss verfügbar sein und zur Ausgabe gehören.");
            row.setCurrentversion1(candidate);return dataset;
        });
    }
}
