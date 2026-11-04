package ch.so.agi.dateninfrastruktur.admin.legacy;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.w3c.dom.Element;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.*;
import org.apache.cayenne.runtime.CayenneRuntime;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import static ch.so.agi.dateninfrastruktur.admin.legacy.LegacyCatalog.*;

/** Explicit one-time import: plan is read-only, apply only selected conflict-free roots, never overwrite admin changes. */
public final class LegacyCatalogImportService {
    public record EntryPlan(String identifier,String kind,String status,List<String> conflicts,List<String> warnings){}
    public record Plan(String sourcePath,String sourceSha256,String sourceModel,List<EntryPlan> entries){}
    private final CayenneRuntime runtime;private final AuditedUnitOfWork unit;
    private static final Set<String> ACCESS=Set.of("open","public_with_conditions","restricted","internal","confidential");
    private static final List<String> SHARED=List.of("publisher/Office/officeUri","creator/Office/officeUri","accessRights/AccessRights/localAccessLevel","contactPoint/ContactPoint/name","contactPoint/ContactPoint/organizationUnit","contactPoint/ContactPoint/email","contactPoint/ContactPoint/phone","contactPoint/ContactPoint/url","licenseUri","origin","accrualPeriodicity/AccrualPeriodicity/localFrequency");
    public LegacyCatalogImportService(CayenneRuntime runtime){this.runtime=runtime;this.unit=new AuditedUnitOfWork(runtime);}
    private Map<String,Object> origin(ObjectContext ctx,String kind,String identifier){var rows=CayenneSql.rows(ctx,"SELECT target_oid,object_sha256 FROM di_ops.legacy_origin WHERE kind=#bind($kind) AND source_identifier=#bind($id)",Map.of("kind",kind,"id",identifier));return rows.isEmpty()?null:rows.getFirst();}
    public Plan dryRun(LegacyCatalog source){return runtime.performInTransaction(()->{
        var ctx=runtime.newContext();var plans=new ArrayList<EntryPlan>();var identifiers=new HashSet<String>();
        var officeHashes=new HashMap<String,Set<String>>();
        for(var root:source.roots()){var nodes=root.getElementsByTagNameNS("*","Office");for(int i=0;i<nodes.getLength();i++){var office=(Element)nodes.item(i);String key=text(office,"officeUri");if(key!=null)officeHashes.computeIfAbsent(key,k->new HashSet<>()).add(hash(office));}}
        var distributionOwners=new HashMap<String,Set<String>>();
        for(var root:source.roots()){String owner=text(root,"identifier");var nodes=root.getElementsByTagNameNS("*","Distribution");for(int n=0;n<nodes.getLength();n++){String key=text((Element)nodes.item(n),"distributionUri");if(key!=null)distributionOwners.computeIfAbsent(key,k->new HashSet<>()).add(owner);}}
        for(var root:source.roots()){
            var conflicts=new ArrayList<String>();var warnings=new ArrayList<String>();String identifier=null,kind=name(root).equals("DatasetSeries")?"series":"standalone";
            try {
            identifier=text(root,"identifier");
            var resources=new ArrayList<Element>();resources.add(root);resources.addAll(issues(root));
            for(var resource:resources){
                if(distinctBag(resource,"creator","Office").size()>1)conflicts.add("MULTIPLE_CREATORS_REQUIRE_EXPLICIT_OWNER: "+text(resource,"identifier"));
                if(distinctBag(resource,"contactPoint","ContactPoint").size()>1)conflicts.add("MULTIPLE_CONTACT_POINTS_REQUIRE_EXPLICIT_CONTACT: "+text(resource,"identifier"));
            }
            if(!conflicts.isEmpty()){plans.add(new EntryPlan(identifier,kind,"CONFLICT",List.copyOf(conflicts),List.copyOf(warnings)));continue;}
            if(identifier==null || identifier.isBlank() || identifier.length()>255 || !identifiers.add(identifier))conflicts.add("MISSING_OR_DUPLICATE_IDENTIFIER");
            String level=text(root,"accessRights/AccessRights/localAccessLevel");if(!ACCESS.contains(level==null?"":level))conflicts.add("UNKNOWN_ACCESS_CODE: "+level);else if(!level.equals("open"))warnings.add("ACCESS_MAPPED_TO_RESTRICTED: "+level);
            for(String required:List.of("title","description","modified","contactPoint/ContactPoint/email","publisher/Office/officeUri","creator/Office/officeUri"))if(value(root,required)==null || value(root,required).isBlank())conflicts.add("MISSING_REQUIRED: "+required);
            if("series".equals(kind))for(String field:SHARED){var values=new TreeSet<String>();String own=value(root,field);if(own!=null && !own.isBlank())values.add(own);for(var issue:issues(root)){String value=value(issue,field);if(value!=null && !value.isBlank())values.add(value);}if(values.size()>1)conflicts.add("SERIES_SHARED_CONFLICT: "+field+"="+values);}
            for(var issue:issues(root)){String childLevel=text(issue,"accessRights/AccessRights/localAccessLevel");if(!ACCESS.contains(childLevel==null?"":childLevel))conflicts.add("UNKNOWN_ISSUE_ACCESS_CODE: "+childLevel);}
            String publication=text(root,"publicationStatus");if(!Set.of("draft","in_review","published","archived").contains(publication==null?"":publication))conflicts.add("UNKNOWN_PUBLICATION_CODE: "+publication);
            var issueKeys=new HashSet<String>();int currentIssues=0;
            for(var issue:issues(root)){String key=text(issue,"identifier");if(key==null || key.length()>128 || !issueKeys.add(key))conflicts.add("INVALID_OR_DUPLICATE_ISSUE_IDENTIFIER");if(Boolean.parseBoolean(text(issue,"isCurrentIssue")))currentIssues++;}
            if(currentIssues>1)conflicts.add("MULTIPLE_CURRENT_ISSUES");
            var officeNodes=root.getElementsByTagNameNS("*","Office");for(int i=0;i<officeNodes.getLength();i++){
                var office=(Element)officeNodes.item(i);String key=text(office,"officeUri");
                if(key==null || text(office,"name")==null || text(office,"abbreviation")==null){conflicts.add("INVALID_OFFICE");continue;}
                if(officeHashes.get(key).size()>1)conflicts.add("CONFLICTING_OFFICE_DEFINITION: "+key);
                var mapped=origin(ctx,"office",key);if(mapped!=null && !hash(office).equals(mapped.get("object_sha256")))conflicts.add("OFFICE_SOURCE_CHANGED_AFTER_IMPORT: "+key);
                if(mapped==null && !ObjectSelect.query(Office.class).where(Office.IDENTIFIER.eq(key)).select(ctx).isEmpty())conflicts.add("OFFICE_EXISTS_WITHOUT_PROVENANCE: "+key);
            }
            var seenDistributions=new HashSet<String>();var distributionNodes=root.getElementsByTagNameNS("*","Distribution");
            for(int n=0;n<distributionNodes.getLength();n++){
                String key=text((Element)distributionNodes.item(n),"distributionUri");
                if(key==null || !seenDistributions.add(key)){conflicts.add("MISSING_OR_DUPLICATE_DISTRIBUTION_IDENTIFIER");continue;}
                if(distributionOwners.get(key).size()>1)conflicts.add("DISTRIBUTION_ASSIGNED_TO_MULTIPLE_DATASETS: "+key);
                var mapped=origin(ctx,"distribution",key);if(mapped!=null){var file=ObjectSelect.query(Distribution.class).where(Distribution.T_ILI_TID.eq(UUID.fromString(mapped.get("target_oid").toString()))).selectOne(ctx);
                    if(file==null || !Objects.equals(identifier,file.getVersion1().getIssue1().getDataset1().getIdentifier()))conflicts.add("DISTRIBUTION_ALREADY_ASSIGNED_ELSEWHERE: "+key);}
            }
            for(var t:themes(root))if(!CatalogCommandService.THEMES.contains(t))conflicts.add("UNKNOWN_THEME: "+t);
            var existing=identifier==null?null:origin(ctx,"dataset",identifier);
            if(existing!=null && !hash(root).equals(existing.get("object_sha256")))conflicts.add("SOURCE_CHANGED_AFTER_IMPORT: explicit reconciliation required");
            if(existing==null && identifier!=null && !ObjectSelect.query(Dataset.class).where(Dataset.IDENTIFIER.eq(identifier)).select(ctx).isEmpty())conflicts.add("TARGET_IDENTIFIER_ALREADY_EXISTS_WITHOUT_LEGACY_MAPPING");
            String status=!conflicts.isEmpty()?"CONFLICT":existing!=null?"SKIP":"NEW";
            plans.add(new EntryPlan(identifier,kind,status,List.copyOf(conflicts),List.copyOf(warnings)));
            } catch(IllegalArgumentException invalid){
                conflicts.add("AMBIGUOUS_OR_UNREPRESENTABLE_LEGACY_FIELD: "+invalid.getMessage());
                plans.add(new EntryPlan(identifier,kind,"CONFLICT",List.copyOf(conflicts),List.copyOf(warnings)));
            }
        }
        return new Plan(source.path().toString(),source.fileSha256(),MODEL,List.copyOf(plans));
    });}
    private List<Element> bag(Element root,String wrapper,String item){var result=new ArrayList<Element>();for(var e:children(root,wrapper))result.addAll(children(e,item));return result;}
    private List<Element> distinctBag(Element root,String wrapper,String item){var unique=new LinkedHashMap<String,Element>();for(var element:bag(root,wrapper,item))unique.putIfAbsent(canonical(element),element);return List.copyOf(unique.values());}
    private Element oneBag(Element root,String wrapper,String item){var unique=distinctBag(root,wrapper,item);if(unique.size()>1)throw new IllegalArgumentException("Several distinct "+wrapper+" values require explicit resolution");return unique.isEmpty()?null:unique.getFirst();}
    private String value(Element root,String path){
        if(path.startsWith("creator/Office/"))return text(oneBag(root,"creator","Office"),path.substring("creator/Office/".length()));
        if(path.startsWith("contactPoint/ContactPoint/"))return text(oneBag(root,"contactPoint","ContactPoint"),path.substring("contactPoint/ContactPoint/".length()));
        return text(root,path);
    }
    private SortedSet<String> themes(Element root){var result=new TreeSet<String>();for(var assignment:bag(root,"themes","ThemeAssignment")){String t=text(assignment,"localTheme");if(t!=null)result.add(t);}return result;}
    private SortedSet<String> keywords(Element root){var result=new TreeSet<String>();for(var e:children(root,"keywords"))result.add(e.getTextContent().strip());return result;}
    private LocalDate date(Element e,String field){String value=text(e,field);return value==null?null:LocalDate.parse(value);}
    private String shared(Element root,String path){String own=value(root,path);if(own!=null && !own.isBlank())return own;var values=new TreeSet<String>();for(var issue:issues(root)){String value=value(issue,path);if(value!=null && !value.isBlank())values.add(value);}if(values.size()>1)throw new IllegalArgumentException("Unresolved shared metadata");return values.isEmpty()?null:values.first();}
    private String level(Element e){String value=text(e,"accessRights/AccessRights/localAccessLevel");if(!ACCESS.contains(value==null?"":value))throw new IllegalArgumentException("Unknown access code");return value.equals("open")?"open":"restricted";}
    private String publication(Element e){String value=text(e,"publicationStatus");if(!Set.of("draft","in_review","published","archived").contains(value==null?"":value))throw new IllegalArgumentException("Unknown publication code");return value;}
    private void remember(ObjectContext ctx,LegacyCatalog source,CommandContext command,String kind,String identifier,UUID target,Element xml){
        SQLExec.query("INSERT INTO di_ops.legacy_origin(kind,source_identifier,target_oid,source_model,object_sha256,source_file_sha256,original_xml,command_id) VALUES(#bind($kind),#bind($id),CAST(#bind($target) AS uuid),#bind($model),#bind($objectHash),#bind($fileHash),#bind($xml),CAST(#bind($command) AS uuid))")
            .params(Map.of("kind",kind,"id",identifier,"target",target.toString(),"model",MODEL,"objectHash",hash(xml),"fileHash",source.fileSha256(),"xml",rawXml(xml),"command",command.commandId().toString())).execute(ctx);
    }
    public List<UUID> apply(ActorContext actor,CommandContext command,LegacyCatalog source,Set<String> selected){
        if(!"service".equals(actor.kind()) || !"legacy.import".equals(command.actionCode()) || selected==null || selected.isEmpty())throw new IllegalArgumentException("Explicit operator actor/action/selection required");
        return unit.execute(actor,command,ctx->{
            var receipts=new CommandReceiptRepository();var ordered=new TreeSet<>(selected);
            String payload=CommandReceiptRepository.payloadHash(source.fileSha256(),ordered);
            var prior=receipts.findOrReserve(ctx,actor,command,payload);
            if(prior!=null)return ordered.stream().map(id->{var row=origin(ctx,"dataset",id);if(row==null)throw new IllegalStateException("Completed import mapping missing");return UUID.fromString(row.get("target_oid").toString());}).toList();
            // One advisory lock serializes explicit imports; no concurrent first-wins UUID/Office assignment.
            CayenneSql.rows(ctx,"SELECT pg_advisory_xact_lock(291104,23)",Map.of());var plan=dryRun(source);var byId=new HashMap<String,EntryPlan>();plan.entries().forEach(e->byId.put(e.identifier(),e));
            for(String id:selected)if(!byId.containsKey(id) || byId.get(id).status().equals("CONFLICT"))throw new IllegalArgumentException("Selected legacy entry has unresolved conflicts: "+id);
            var imported=new ArrayList<UUID>();var offices=new HashMap<String,Office>();var schemas=new HashMap<String,Dataschema>();
            for(var root:source.roots()){String id=text(root,"identifier");if(!selected.contains(id))continue;
                if(byId.get(id).status().equals("SKIP")){imported.add(UUID.fromString(origin(ctx,"dataset",id).get("target_oid").toString()));continue;}
                imported.add(importRoot(ctx,actor,command,source,root,offices,schemas));
            }
            receipts.complete(ctx,actor,command,new CommandReceiptRepository.Receipt(stable("import-batch",payload),"IMPORTED"));
            return List.copyOf(imported);
        });
    }
    private Office office(ObjectContext ctx,LegacyCatalog source,CommandContext command,Element xml,Map<String,Office> cache){
        String identifier=text(xml,"officeUri");if(cache.containsKey(identifier))return cache.get(identifier);UUID id=stable("office",identifier);
        var mapping=origin(ctx,"office",identifier);
        Office office;
        if(mapping!=null){
            if(!hash(xml).equals(mapping.get("object_sha256")))throw new IllegalArgumentException("Conflicting legacy Office metadata: "+identifier);
            office=ObjectSelect.query(Office.class).where(Office.T_ILI_TID.eq(UUID.fromString(mapping.get("target_oid").toString()))).selectOne(ctx);
            if(office==null)throw new IllegalStateException("Mapped Office no longer exists; no implicit recreation");
        }else{
            if(!ObjectSelect.query(Office.class).where(Office.IDENTIFIER.eq(identifier)).select(ctx).isEmpty())throw new IllegalArgumentException("Office identifier exists without provenance");
            office=ctx.newObject(Office.class);office.setTIliTid(id);office.setIdentifier(identifier);office.setAname(text(xml,"name"));office.setAbbreviation(text(xml,"abbreviation"));office.setEmail(text(xml,"email"));office.setWebsite(text(xml,"officeAtWeb"));office.setAactive(true);remember(ctx,source,command,"office",identifier,id,xml);
        }
        cache.put(identifier,office);return office;
    }
    private Dataschema schema(ObjectContext ctx,LegacyCatalog source,CommandContext command,Element resource,Map<String,Dataschema> cache){
        var attributes=new ArrayList<Element>();for(var wrapper:children(resource,"attributes"))attributes.addAll(children(wrapper,"DatasetAttribute"));
        String model=text(resource,"model");if(attributes.isEmpty() && model==null)return null;
        String definition=(model==null?"":model)+attributes.stream().map(LegacyCatalog::canonical).collect(java.util.stream.Collectors.joining());String key=sha(definition.getBytes(StandardCharsets.UTF_8));
        if(cache.containsKey(key))return cache.get(key);UUID id=stable("schema",key);var existing=origin(ctx,"schema",key);Dataschema row;
        if(existing!=null){row=ObjectSelect.query(Dataschema.class).where(Dataschema.T_ILI_TID.eq(id)).selectOne(ctx);if(row==null)throw new IllegalStateException("Mapped schema missing");}
        else{
            row=ctx.newObject(Dataschema.class);row.setTIliTid(id);row.setIdentifier("legacy-"+key);row.setModelname(model);
            int ordinal=0;for(var attribute:attributes){var a=ctx.newObject(Attributedefinition.class);a.setTIliTid(stable("attribute",key+":"+(++ordinal)));a.setAschema1(row);a.setAname(text(attribute,"name"));a.setDatatype(text(attribute,"dataType"));a.setOrdinal(ordinal);a.setMandatory(Boolean.parseBoolean(text(attribute,"mandatory")));a.setAdescription(text(attribute,"description"));a.setUnit(text(attribute,"unit"));}
            remember(ctx,source,command,"schema",key,id,resource);
        }
        cache.put(key,row);return row;
    }
    private UUID importRoot(ObjectContext ctx,ActorContext actor,CommandContext command,LegacyCatalog source,Element xml,Map<String,Office> offices,Map<String,Dataschema> schemas){
        String identifier=text(xml,"identifier");UUID id=stable("dataset",identifier);var root=ctx.newObject(Dataset.class);root.setTIliTid(id);root.setIdentifier(identifier);root.setAkind(name(xml).equals("DatasetSeries")?"series":"standalone");root.setTitle(text(xml,"title"));root.setAdescription(text(xml,"description"));
        root.setOwneroffice1(office(ctx,source,command,oneBag(xml,"creator","Office"),offices));root.setPublisheroffice1(office(ctx,source,command,at(xml,"publisher/Office"),offices));
        root.setContactname(shared(xml,"contactPoint/ContactPoint/name"));root.setContactunit(shared(xml,"contactPoint/ContactPoint/organizationUnit"));root.setContactemail(value(xml,"contactPoint/ContactPoint/email"));root.setContactphone(shared(xml,"contactPoint/ContactPoint/phone"));root.setContacturl(shared(xml,"contactPoint/ContactPoint/url"));root.setAccesslevel(level(xml));root.setPublicationstatus(publication(xml));root.setOrigin(shared(xml,"origin"));root.setFrequency(shared(xml,"accrualPeriodicity/AccrualPeriodicity/localFrequency"));root.setIssued(date(xml,"issued"));root.setModified(date(xml,"modified"));root.setLicenseuri(shared(xml,"licenseUri"));root.setSurveymethod(text(xml,"surveyMethod"));root.setFurtheruses(text(xml,"furtherUses"));root.setAuxiliarydata(text(xml,"auxiliaryData"));root.setDataavailablefrom(text(xml,"dataAvailableFrom"));
        for(String theme:themes(xml)){var row=ctx.newObject(Datasettheme.class);row.setTIliTid(stable("theme",identifier+":"+theme));row.setDataset1(root);row.setTheme(theme);}
        for(String keyword:keywords(xml)){var row=ctx.newObject(Datasetkeyword.class);row.setTIliTid(stable("keyword",identifier+":"+keyword));row.setDataset1(root);row.setKeyword(keyword);}
        if("standalone".equals(root.getAkind())){var issue=issue(ctx,source,command,root,xml,"default",schemas);root.setCurrentissue1(issue);}
        else{
            int current=0;for(var issueXml:issues(xml)){var issue=issue(ctx,source,command,root,issueXml,text(issueXml,"identifier"),schemas);
                if(Boolean.parseBoolean(text(issueXml,"isCurrentIssue"))){current++;root.setCurrentissue1(issue);}}
            if(current>1)throw new IllegalArgumentException("Several explicit current issues; no first-wins");
        }
        remember(ctx,source,command,"dataset",identifier,id,xml);return id;
    }
    private Datasetissue issue(ObjectContext ctx,LegacyCatalog source,CommandContext command,Dataset root,Element xml,String key,Map<String,Dataschema> schemas){
        String sourceId=root.getIdentifier()+"/"+key;var issue=ctx.newObject(Datasetissue.class);issue.setTIliTid(stable("issue",sourceId));issue.setDataset1(root);issue.setIssuekey(key);issue.setLabel("default".equals(key)?"Einzelangebot":text(xml,"issueLabel"));issue.setPublicationstatus(publication(xml));issue.setIssued(date(xml,"issued"));issue.setModified(date(xml,"modified"));issue.setReferencedate(date(xml,"temporalCoverage/TemporalCoverage/referenceDate"));issue.setStartdate(date(xml,"temporalCoverage/TemporalCoverage/startDate"));issue.setEnddate(date(xml,"temporalCoverage/TemporalCoverage/endDate"));
        if(!"default".equals(key)){
            var notes=new ArrayList<String>();String description=text(xml,"description");if(description!=null && !description.strip().equals(root.getAdescription().strip()))notes.add(description);
            String note=text(xml,"note");if(note!=null && !notes.contains(note))notes.add(note);
            var additional=keywords(xml);additional.removeAll(root.getDatasetkeywords().stream().map(Datasetkeyword::getKeyword).toList());if(!additional.isEmpty())notes.add("Zusätzliche Stichwörter dieser Ausgabe: "+String.join(", ",additional));
            if(!notes.isEmpty())issue.setNote(String.join("\n\n",notes));
        }
        var schema=schema(ctx,source,command,xml,schemas);var version=ctx.newObject(Dataversion.class);version.setTIliTid(stable("version",sourceId));version.setIssue1(issue);version.setAschema1(schema);version.setVersionkey("legacy");version.setAsequence(1);version.setAvailability("prepared");version.setValidationstatus("unknown");version.setSourcereference(text(xml,"resourceUri"));
        String objects=text(xml,"structureSummary/StructureSummary/objectCount"),attributes=text(xml,"structureSummary/StructureSummary/attributeCount");if(objects!=null)version.setObjectcount(Long.parseLong(objects));if(attributes!=null)version.setAttributecount(Long.parseLong(attributes));
        for(var wrapper:children(xml,"distributions"))for(var distribution:children(wrapper,"Distribution")){
            String identifier=text(distribution,"distributionUri");UUID id=stable("distribution",identifier);String format=text(distribution,"format");if(!Set.of("csv","xlsx","parquet","other").contains(format))throw new IllegalArgumentException("Unknown legacy distribution format");
            String legacy=text(distribution,"downloadURL");if(legacy==null)legacy=text(distribution,"accessURL");String filename=legacy==null?"":legacy.substring(legacy.lastIndexOf('/')+1).split("[?#]",2)[0];if(!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))filename="legacy."+format;
            var row=ctx.newObject(Distribution.class);row.setTIliTid(id);row.setVersion1(version);row.setAformat(format);row.setFilename(filename);row.setMediatype(switch(format){case "csv"->"text/csv";case "xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";default->"application/octet-stream";});row.setAvailability("prepared");row.setStorageprofile("legacy-pending");row.setObjectkey("downloads/"+id+"/"+filename);row.setLegacyaccessurl(legacy);
            remember(ctx,source,command,"distribution",identifier,id,distribution);
        }
        remember(ctx,source,command,"issue",sourceId,issue.getTIliTid(),xml);return issue;
    }
}
