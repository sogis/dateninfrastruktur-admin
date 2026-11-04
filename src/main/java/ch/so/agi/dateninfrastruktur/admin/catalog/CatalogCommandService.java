package ch.so.agi.dateninfrastruktur.admin.catalog;
import java.util.*;
import java.util.function.Function;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.access.*;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;

public final class CatalogCommandService {
    public static final Set<String> THEMES=Set.of("Arbeit_Erwerb","Bau_und_Wohnungswesen","Bevoelkerung","Bildung_Wissenschaft","Energie","Finanzen","Geografie","Gesetzgebung","Gesundheit","Handel","Industrie_und_Dienstleistungen","Kriminalitaet_Strafrecht","Kultur_Medien_Informationsgesellschaft_Sport","Landwirtschaft_Forstwirtschaft","Mobilitaet_und_Verkehr","Oeffentliche_Ordnung_und_Sicherheit","Politik","Preise","Raum_und_Umwelt","Soziale_Sicherheit","Statistische_Grundlagen","Tourismus","Verwaltung","Volkswirtschaft");
    /** Complete allowlist of this form's fields. Other imported metadata is preserved. */
    public record Metadata(String title,String description,String contactName,String contactEmail,SortedSet<String> themes,SortedSet<String> keywords){
        public Metadata {
            themes=Collections.unmodifiableSortedSet(new TreeSet<>(themes==null?Set.of():themes));
            keywords=Collections.unmodifiableSortedSet(new TreeSet<>(keywords==null?Set.of():keywords));
            if(title==null || title.isBlank() || title.length()>255 || description==null || description.isBlank() || description.length()>100000 ||
                contactEmail==null || !contactEmail.matches("mailto:[^\\s@]+@[^\\s@]+") || contactEmail.length()>1023 ||
                (contactName!=null && contactName.length()>255) || !THEMES.containsAll(themes) || themes.size()>24 ||
                keywords.size()>50 || keywords.stream().anyMatch(k->k.isBlank() || k.length()>255))
                throw BusinessException.invalid("Bitte Titel, Beschreibung, gültigen E-Mail-Kontakt und erlaubte Themen/Schlagwörter angeben.");
        }
    }
    public record Create(UUID ownerOffice,String identifier,String kind,Metadata metadata){}
    private final AuditedUnitOfWork unit;private final AuthorityService authority;
    private final AggregateWriteGuard guard=new AggregateWriteGuard();
    private final CommandReceiptRepository receipts=new CommandReceiptRepository();
    public CatalogCommandService(AuditedUnitOfWork unit,AuthorityService authority){this.unit=unit;this.authority=authority;}
    private UUID execute(ActorContext actor,CommandContext command,String action,String hash,Function<ObjectContext,UUID> work){
        if(!action.equals(command.actionCode()))throw BusinessException.invalid("Ungültige Aktion.");
        return unit.execute(actor,command,ctx->{var old=receipts.findOrReserve(ctx,actor,command,hash);
            if(old!=null)return old.reference();var id=work.apply(ctx);
            receipts.complete(ctx,actor,command,new CommandReceiptRepository.Receipt(id,"COMMITTED"));return id;});
    }
    private Dataset locked(ObjectContext ctx,ActorContext actor,UUID id,long version,String capability){
        authority.require(ctx,actor,id,capability);guard.lockDataset(ctx,id,version);authority.require(ctx,actor,id,capability);return ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(ctx);
    }
    private void metadata(ObjectContext ctx,Dataset root,Metadata value){
        root.setTitle(value.title());root.setAdescription(value.description());root.setContactname(value.contactName());root.setContactemail(value.contactEmail());
        root.setModified(CayenneSql.now(ctx).toLocalDate());
        for(var theme:new ArrayList<>(root.getDatasetthemes()))if(!value.themes().contains(theme.getTheme()))ctx.deleteObject(theme);
        var existingThemes=root.getDatasetthemes().stream().map(Datasettheme::getTheme).collect(java.util.stream.Collectors.toSet());
        for(String theme:value.themes())if(!existingThemes.contains(theme)){var row=ctx.newObject(Datasettheme.class);row.setTIliTid(UUID.randomUUID());row.setTheme(theme);row.setDataset1(root);}
        for(var keyword:new ArrayList<>(root.getDatasetkeywords()))if(!value.keywords().contains(keyword.getKeyword()))ctx.deleteObject(keyword);
        var existingKeywords=root.getDatasetkeywords().stream().map(Datasetkeyword::getKeyword).collect(java.util.stream.Collectors.toSet());
        for(String keyword:value.keywords())if(!existingKeywords.contains(keyword)){var row=ctx.newObject(Datasetkeyword.class);row.setTIliTid(UUID.randomUUID());row.setKeyword(keyword);row.setDataset1(root);}
    }
    public UUID createDraft(ActorContext actor,CommandContext command,Create input){
        if(input.identifier()==null || !input.identifier().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}") || !Set.of("standalone","series").contains(input.kind()))throw BusinessException.invalid("Ungültiger Identifikator oder Datensatztyp.");
        return execute(actor,command,"create_draft",CommandReceiptRepository.payloadHash(input),ctx->{
            authority.requireOffice(ctx,actor,input.ownerOffice(),"manage_metadata");
            var office=ObjectSelect.query(Office.class).where(Office.T_ILI_TID.eq(input.ownerOffice())).selectOne(ctx);
            var root=ctx.newObject(Dataset.class);root.setTIliTid(UUID.randomUUID());root.setIdentifier(input.identifier());root.setAkind(input.kind());
            root.setOwneroffice1(office);root.setPublisheroffice1(office);root.setAccesslevel("restricted");root.setPublicationstatus("draft");metadata(ctx,root,input.metadata());
            if("standalone".equals(input.kind())){var issue=ctx.newObject(Datasetissue.class);issue.setTIliTid(UUID.randomUUID());issue.setDataset1(root);issue.setIssuekey("default");issue.setLabel("Einzelangebot");issue.setPublicationstatus("draft");issue.setModified(CayenneSql.now(ctx).toLocalDate());root.setCurrentissue1(issue);}
            return root.getTIliTid();
        });
    }
    public UUID update(ActorContext actor,CommandContext command,UUID id,long expected,Metadata patch,boolean published){
        return execute(actor,command,published?"save_published":"save_draft",CommandReceiptRepository.payloadHash(id,expected,patch,published),ctx->{
            var root=locked(ctx,actor,id,expected,"manage_metadata");
            if(published){authority.require(ctx,actor,id,"publish");if(!"published".equals(root.getPublicationstatus()))throw BusinessException.conflict("Dieser Datensatz ist nicht publiziert.");}
            else if(!Set.of("draft","in_review").contains(root.getPublicationstatus()))throw BusinessException.conflict("Bitte die Aktion für publizierte Metadaten verwenden.");
            metadata(ctx,root,patch);return id;
        });
    }
    public UUID transition(ActorContext actor,CommandContext command,UUID id,long expected,String action,String reason){
        if(!Set.of("submit","publish","archive").contains(action))throw BusinessException.invalid("Ungültiger Statuswechsel.");
        return execute(actor,command,action,CommandReceiptRepository.payloadHash(id,expected,reason),ctx->{
            var root=locked(ctx,actor,id,expected,"submit".equals(action)?"manage_metadata":"publish");String current=root.getPublicationstatus();
            authority.require(ctx,actor,id,"submit".equals(action)?"manage_metadata":"publish");
            if("submit".equals(action)){
                if(!"draft".equals(current))throw BusinessException.conflict("Nur Entwürfe können zur Prüfung eingereicht werden.");root.setPublicationstatus("in_review");
            }else if("publish".equals(action)){
                if(!Set.of("draft","in_review").contains(current))throw BusinessException.conflict("Dieser Status kann nicht publiziert werden.");
                new Metadata(root.getTitle(),root.getAdescription(),root.getContactname(),root.getContactemail(),new TreeSet<>(root.getDatasetthemes().stream().map(Datasettheme::getTheme).toList()),new TreeSet<>(root.getDatasetkeywords().stream().map(Datasetkeyword::getKeyword).toList()));
                if(root.getDatasetissues().isEmpty())throw BusinessException.invalid("Vor der Publikation ist mindestens eine Ausgabe nötig.");
                root.setPublicationstatus("published");
                // Only the internal standalone issue follows the root's initial publication.
                if("standalone".equals(root.getAkind())){var issue=root.getDatasetissues().getFirst();issue.setPublicationstatus("published");}
                action(ctx,actor,command,root,"publish",reason);
            }else{
                if(!"published".equals(current))throw BusinessException.conflict("Nur publizierte Datensätze können archiviert werden.");root.setPublicationstatus("archived");action(ctx,actor,command,root,"archive",reason);
            }
            root.setModified(CayenneSql.now(ctx).toLocalDate());return id;
        });
    }
    public UUID changeAccessLevel(ActorContext actor,CommandContext command,UUID id,long expected,String level,String reason){
        if(!Set.of("open","restricted").contains(level) || reason==null || reason.isBlank() || reason.length()>4000)throw BusinessException.invalid("Bitte Datenzugang und Begründung angeben.");
        return execute(actor,command,"change_access_level",CommandReceiptRepository.payloadHash(id,expected,level,reason),ctx->{
            var root=locked(ctx,actor,id,expected,"manage_access");authority.require(ctx,actor,id,"publish");
            if(level.equals(root.getAccesslevel()))throw BusinessException.conflict("Dieser Datenzugang gilt bereits.");
            root.setAccesslevel(level);root.setModified(CayenneSql.now(ctx).toLocalDate());action(ctx,actor,command,root,"change_access_level",reason);return id;
        });
    }
    private void action(ObjectContext ctx,ActorContext actor,CommandContext command,Dataset root,String code,String reason){
        if(reason==null || reason.isBlank() || reason.length()>4000)throw BusinessException.invalid("Bitte eine Begründung angeben.");
        var row=ctx.newObject(Catalogaction.class);row.setTIliTid(UUID.randomUUID());row.setDataset1(root);row.setActor1(new PrincipalRepository().requireActive(ctx,actor));row.setCommandid(command.commandId());row.setActioncode(code);row.setReason(reason);row.setPerformedat(CayenneSql.now(ctx));
    }
}
