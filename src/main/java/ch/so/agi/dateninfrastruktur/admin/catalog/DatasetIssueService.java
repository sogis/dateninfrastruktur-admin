package ch.so.agi.dateninfrastruktur.admin.catalog;
import java.util.*;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.runtime.CayenneRuntime;
import ch.so.agi.dateninfrastruktur.admin.command.CommandContext;
import ch.so.agi.dateninfrastruktur.admin.identity.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import ch.so.agi.dateninfrastruktur.admin.access.*;
public final class DatasetIssueService {
    private final CayenneRuntime runtime;private final AuthorityService authority;

    public DatasetIssueService(CayenneRuntime runtime,AuthorityService authority){this.runtime=runtime;this.authority=authority;}
    private UUID execute(ActorContext actor,CommandContext command,String action,UUID dataset,java.util.function.BiFunction<ObjectContext,Dataset,UUID> work){
        if(!action.equals(command.actionCode()))throw BusinessException.invalid("Ungültige Ausgabeaktion.");
        return ManagementTransactions.write(runtime,ctx->{
            authority.require(ctx,actor,dataset,"manage_metadata");ManagementTransactions.lockDataset(ctx,dataset);authority.require(ctx,actor,dataset,"manage_metadata");
            var root=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(dataset)).selectOne(ctx);
            if("published".equals(root.getPublicationstatus()))authority.require(ctx,actor,dataset,"publish");
            return work.apply(ctx,root);});
    }
    public UUID createIssue(ActorContext actor,CommandContext command,UUID dataset,String key,String label){
        if(key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || label==null || label.isBlank() || label.length()>255)throw BusinessException.invalid("Bitte Ausgabeschlüssel und Bezeichnung angeben.");
        return execute(actor,command,"create_issue",dataset,(ctx,root)->{
            requireSeries(root);
            if (ObjectSelect.query(Datasetissue.class)
                .where(Datasetissue.DATASET1.eq(root).andExp(Datasetissue.ISSUEKEY.eq(key))).selectCount(ctx) > 0) {
                throw new BusinessException(409, "DUPLICATE_ISSUE_KEY", "Dieser Ausgabeschlüssel ist bereits vergeben.");
            }
            var row=ctx.newObject(Datasetissue.class);row.setTBasket(root.getTBasket());row.setTIliTid(UUID.randomUUID());row.setDataset1(root);row.setIssuekey(key);row.setLabel(label);row.setPublicationstatus(root.getPublicationstatus());row.setModified(CayenneSql.now(ctx).toLocalDate());return row.getTIliTid();
        });
    }
    public UUID updateIssueMetadata(ActorContext actor,CommandContext command,UUID dataset,UUID issue,String label,String note,String status){
        if(label==null || label.isBlank() || label.length()>255 || (note!=null && note.length()>100000) || !Set.of("draft","in_review","published","archived").contains(status))throw BusinessException.invalid("Ungültige Ausgabemetadaten.");
        return execute(actor,command,"update_issue",dataset,(ctx,root)->{
            requireSeries(root);
            ManagementTransactions.lockIssue(ctx,issue);
            var row=ObjectSelect.query(Datasetissue.class).where(Datasetissue.T_ILI_TID.eq(issue)).selectOne(ctx);
            if(row==null || !root.getTIliTid().equals(row.getDataset1().getTIliTid()))throw BusinessException.invalid("Die Ausgabe gehört nicht zu diesem Datensatz.");
            if(Set.of("published","archived").contains(status) || "published".equals(row.getPublicationstatus()))authority.require(ctx,actor,dataset,"publish");
            if(("archived".equals(row.getPublicationstatus()) && !"archived".equals(status)) || ("published".equals(row.getPublicationstatus()) && !Set.of("published","archived").contains(status)))throw BusinessException.conflict("Unzulässiger Statuswechsel der Ausgabe.");
            row.setLabel(label);row.setNote(note);row.setPublicationstatus(status);row.setModified(CayenneSql.now(ctx).toLocalDate());return dataset;
        });
    }
    public UUID setCurrentIssue(ActorContext actor,CommandContext command,UUID dataset,UUID issue){
        return execute(actor,command,"set_current_issue",dataset,(ctx,root)->{
            requireSeries(root);
            var row=ObjectSelect.query(Datasetissue.class).where(Datasetissue.T_ILI_TID.eq(issue)).selectOne(ctx);
            if(row==null || !root.getTIliTid().equals(row.getDataset1().getTIliTid()))throw BusinessException.invalid("Die Ausgabe gehört nicht zu diesem Datensatz.");root.setCurrentissue1(row);return dataset;
        });
    }
    private static void requireSeries(Dataset root) {
        if (!"series".equals(root.getAkind())) {
            throw BusinessException.conflict("Einzelangebote behalten ihre interne Standardausgabe.");
        }
    }

}
