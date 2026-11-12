package ch.so.agi.dateninfrastruktur.admin.catalog;
import java.util.*;
import java.util.function.Function;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.runtime.CayenneRuntime;
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
    public record Create(String ownerOffice,String identifier,String kind,Metadata metadata){}
    private final CayenneRuntime runtime;
    private final AuthorityService authority;

    public CatalogCommandService(CayenneRuntime runtime, AuthorityService authority) {
        this.runtime = runtime;
        this.authority = authority;
    }

    private UUID execute(CommandContext command, String action, Function<ObjectContext, UUID> work) {
        if (!action.equals(command.actionCode())) throw BusinessException.invalid("Ungültige Aktion.");
        return ManagementTransactions.write(runtime, work);
    }

    private Dataset locked(ObjectContext context, ActorContext actor, UUID id, String capability) {
        ManagementTransactions.lockDataset(context, id);
        authority.require(context, actor, id, capability);
        return ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(context);
    }

    private boolean replay(ObjectContext context, ActorContext actor, CommandContext command, Dataset dataset,
                           String action, String reason) {
        ManagementTransactions.lockCommand(context, command.commandId());
        var previous = ObjectSelect.query(Catalogaction.class)
            .where(Catalogaction.COMMANDID.eq(command.commandId())).selectOne(context);
        if (previous == null) return false;
        var principal = new PrincipalRepository().requireActive(context, actor);
        if (!previous.getDataset1().getTIliTid().equals(dataset.getTIliTid())
                || !previous.getActor1().getTIliTid().equals(principal.getTIliTid())
                || !previous.getActioncode().equals(action) || !Objects.equals(previous.getReason(), reason)) {
            throw BusinessException.conflict("Aktions-ID bereits vergeben.");
        }
        return true;
    }

    private void metadata(ObjectContext ctx,Dataset root,Metadata value){
        root.setTitle(value.title());
        root.setAdescription(value.description());
        root.setModified(CayenneSql.now(ctx).toLocalDate());
        var current = root.primaryContact();
        String name = value.contactName() == null || value.contactName().isBlank() ? value.contactEmail() : value.contactName();
        if (current == null || !Objects.equals(current.getAname(), name)
                || !Objects.equals(current.getEmail(), value.contactEmail())) {
            var contact = ctx.newObject(Contactpoint.class);
            contact.setTBasket(ManagementBaskets.require(ctx, ManagementBaskets.Topic.Organization));
            contact.setTIliTid("contact-" + UUID.randomUUID());
            contact.setAname(name);
            contact.setEmail(value.contactEmail());
            if (current != null) {
                root.getDatasetcontacts().stream().filter(link -> link.getContactpoints1() == current)
                    .findFirst().ifPresent(ctx::deleteObject);
            }
            ctx.commitChanges();
            var link = ctx.newObject(Datasetcontact.class);
            link.setTBasket(root.getTBasket());
            link.setDatasets1(root);
            link.setContactpoints1(contact);
        }
        for (var entry : new ArrayList<>(root.getThemeentries())) {
            if (!value.themes().contains(entry.getAcode())) ctx.deleteObject(entry);
        }
        var existingThemes = root.getThemeentries().stream().map(Themeentry::getAcode).collect(java.util.stream.Collectors.toSet());
        for (String theme : value.themes()) {
            if (existingThemes.contains(theme)) continue;
            var entry = ctx.newObject(Themeentry.class);
            entry.setTBasket(root.getTBasket());
            entry.setAcode(theme);
            entry.setDatasetThemes(root);
        }
        for (var entry : new ArrayList<>(root.getKeywordentries())) {
            if (!value.keywords().contains(entry.getAvalue())) ctx.deleteObject(entry);
        }
        var existingKeywords = root.getKeywordentries().stream().map(Keywordentry::getAvalue).collect(java.util.stream.Collectors.toSet());
        for (String keyword : value.keywords()) {
            if (existingKeywords.contains(keyword)) continue;
            var entry = ctx.newObject(Keywordentry.class);
            entry.setTBasket(root.getTBasket());
            entry.setAvalue(keyword);
            entry.setDatasetKeywords(root);
        }
    }
    public UUID createDraft(ActorContext actor,CommandContext command,Create input){
        if(input.identifier()==null || !input.identifier().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}") || !Set.of("standalone","series").contains(input.kind()))throw BusinessException.invalid("Ungültiger Identifikator oder Datensatztyp.");
        return execute(command,"create_draft",ctx->{
            authority.requireOffice(ctx,actor,input.ownerOffice(),"manage_metadata");
            if (ObjectSelect.query(Dataset.class).where(Dataset.IDENTIFIER.eq(input.identifier())).selectCount(ctx) > 0) {
                throw new BusinessException(409, "DUPLICATE_DATASET_IDENTIFIER", "Diese Kennung ist bereits vergeben.");
            }
            var office=ObjectSelect.query(Office.class).where(Office.T_ILI_TID.eq(input.ownerOffice())).selectOne(ctx);
            var root=ctx.newObject(Dataset.class);root.setTBasket(ManagementBaskets.require(ctx, ManagementBaskets.Topic.Catalog));root.setTIliTid(UUID.randomUUID());root.setIdentifier(input.identifier());root.setAkind(input.kind());
            root.setOwneroffice1(office);root.setPublisheroffice1(office);root.setAccesslevel("restricted");root.setPublicationstatus("draft");
            root.setTitle(input.metadata().title());
            root.setAdescription(input.metadata().description());
            root.setModified(CayenneSql.now(ctx).toLocalDate());
            ctx.commitChanges();
            metadata(ctx, root, input.metadata());
            if("standalone".equals(input.kind())){var issue=ctx.newObject(Datasetissue.class);issue.setTBasket(root.getTBasket());issue.setTIliTid(UUID.randomUUID());issue.setDataset1(root);issue.setIssuekey("default");issue.setLabel("Einzelangebot");issue.setPublicationstatus("draft");issue.setModified(CayenneSql.now(ctx).toLocalDate());ctx.commitChanges();root.setCurrentissue1(issue);}
            return root.getTIliTid();
        });
    }
    public UUID update(ActorContext actor,CommandContext command,UUID id,Metadata patch,boolean published){
        return execute(command,published?"save_published":"save_draft",ctx->{
            var root=locked(ctx,actor,id,"manage_metadata");
            if(published){authority.require(ctx,actor,id,"publish");if(!"published".equals(root.getPublicationstatus()))throw BusinessException.conflict("Dieser Datensatz ist nicht publiziert.");}
            else if(!Set.of("draft","in_review").contains(root.getPublicationstatus()))throw BusinessException.conflict("Bitte die Aktion für publizierte Metadaten verwenden.");
            metadata(ctx,root,patch);return id;
        });
    }
    public UUID transition(ActorContext actor,CommandContext command,UUID id,String action,String reason){
        if(!Set.of("submit","publish","archive").contains(action))throw BusinessException.invalid("Ungültiger Statuswechsel.");
        return execute(command,action,ctx->{
            var root=locked(ctx,actor,id,"submit".equals(action)?"manage_metadata":"publish");String current=root.getPublicationstatus();
            authority.require(ctx,actor,id,"submit".equals(action)?"manage_metadata":"publish");
            if (!"submit".equals(action) && replay(ctx, actor, command, root, action, reason)) return id;
            if("submit".equals(action)){
                if(!"draft".equals(current))throw BusinessException.conflict("Nur Entwürfe können zur Prüfung eingereicht werden.");root.setPublicationstatus("in_review");
            }else if("publish".equals(action)){
                if(!Set.of("draft","in_review").contains(current))throw BusinessException.conflict("Dieser Status kann nicht publiziert werden.");
                new Metadata(root.getTitle(),root.getAdescription(),root.primaryContact() == null ? null : root.primaryContact().getAname(),root.primaryContact() == null ? null : root.primaryContact().getEmail(),new TreeSet<>(root.getThemeentries().stream().map(Themeentry::getAcode).toList()),new TreeSet<>(root.getKeywordentries().stream().map(Keywordentry::getAvalue).toList()));
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
    public UUID changeAccessLevel(ActorContext actor,CommandContext command,UUID id,String level,String reason){
        if(!Set.of("open","restricted").contains(level) || reason==null || reason.isBlank() || reason.length()>4000)throw BusinessException.invalid("Bitte Datenzugang und Begründung angeben.");
        return execute(command,"change_access_level",ctx->{
            var root=locked(ctx,actor,id,"manage_access");authority.require(ctx,actor,id,"publish");
            String decisionReason = level + ": " + reason.strip();
            if (replay(ctx, actor, command, root, "change_access_level", decisionReason)) return id;
            if(level.equals(root.getAccesslevel()))throw BusinessException.conflict("Dieser Datenzugang gilt bereits.");
            root.setAccesslevel(level);root.setModified(CayenneSql.now(ctx).toLocalDate());action(ctx,actor,command,root,"change_access_level",decisionReason);return id;
        });
    }
    public UUID withdrawVersion(ActorContext actor, CommandContext command, UUID dataset, UUID issueId, UUID versionId, String reason) {
        return execute(command, "withdraw_version", context -> {
            var root = locked(context, actor, dataset, "publish");
            ManagementTransactions.lockIssue(context, issueId);
            var version = ObjectSelect.query(Dataversion.class).where(Dataversion.T_ILI_TID.eq(versionId)).selectOne(context);
            if (version == null || !issueId.equals(version.getIssue1().getTIliTid())
                    || !dataset.equals(version.getIssue1().getDataset1().getTIliTid())) throw BusinessException.missing();
            if (reason == null || reason.isBlank()) throw BusinessException.invalid("Bitte eine Begründung angeben.");
            String description = versionId + ": " + reason;
            if (replay(context, actor, command, root, "withdraw_version", description)) return versionId;
            if (!"available".equals(version.getAvailability())) throw BusinessException.conflict("Version ist bereits zurückgezogen.");
            var issue = version.getIssue1();
            version.setAvailability("withdrawn");
            if (issue.getCurrentversion1() == version) issue.setCurrentversion1(null);
            action(context, actor, command, root, "withdraw_version", description).setAffectedversion1(version);
            return versionId;
        });
    }

    public UUID withdrawDistribution(ActorContext actor, CommandContext command, UUID dataset, UUID issueId,
                                     UUID distributionId, String reason) {
        return execute(command, "withdraw_distribution", context -> {
            var root = locked(context, actor, dataset, "publish");
            ManagementTransactions.lockIssue(context, issueId);
            var distribution = ObjectSelect.query(Distribution.class).where(Distribution.T_ILI_TID.eq(distributionId)).selectOne(context);
            if (distribution == null || !issueId.equals(distribution.getVersion1().getIssue1().getTIliTid())
                    || !dataset.equals(distribution.getVersion1().getIssue1().getDataset1().getTIliTid())) throw BusinessException.missing();
            if (reason == null || reason.isBlank()) throw BusinessException.invalid("Bitte eine Begründung angeben.");
            String description = distributionId + ": " + reason;
            if (replay(context, actor, command, root, "withdraw_distribution", description)) return distributionId;
            if (!"available".equals(distribution.getAvailability())) throw BusinessException.conflict("Datei ist bereits zurückgezogen.");
            distribution.setAvailability("withdrawn");
            var action = action(context, actor, command, root, "withdraw_distribution", description);
            action.setAffectedversion1(distribution.getVersion1());
            action.setAffecteddistribution1(distribution);
            return distributionId;
        });
    }

    private Catalogaction action(ObjectContext ctx,ActorContext actor,CommandContext command,Dataset root,String code,String reason){
        if(reason==null || reason.isBlank() || reason.length()>4000)throw BusinessException.invalid("Bitte eine Begründung angeben.");
        var row=ctx.newObject(Catalogaction.class);row.setTBasket(ManagementBaskets.require(ctx, ManagementBaskets.Topic.Access));row.setTIliTid(UUID.randomUUID());row.setDataset1(root);row.setActor1(new PrincipalRepository().requireActive(ctx,actor));row.setCommandid(command.commandId());row.setActioncode(code);row.setReason(reason);row.setPerformedat(CayenneSql.now(ctx));
        return row;
    }
}
