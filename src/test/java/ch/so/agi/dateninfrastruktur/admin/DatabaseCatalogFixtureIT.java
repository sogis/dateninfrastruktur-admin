package ch.so.agi.dateninfrastruktur.admin;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.catalog.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
import static org.junit.jupiter.api.Assertions.*;
/** Deliberately executable fixture producer for the sibling Public integration contract. */
class DatabaseCatalogFixtureIT {
    @Test void createPublicHiddenAndSeriesFixtures()throws Exception{
        try(var f=new ManagementFixture()){
            var open=f.published();f.catalog.update(f.manager,command("save_published"),open,f.version("dataset",open),ManagementFixture.metadata("MA-D Offene Geodaten"),true);f.catalog.changeAccessLevel(f.manager,command("change_access_level"),open,f.version("dataset",open),"open","Öffentliche Testdaten");
            var openFile=DownloadAuthorizationRepositoryIT.file(f,open,1);
            var draft=f.draft();f.catalog.update(f.manager,command("save_draft"),draft,f.version("dataset",draft),new CatalogCommandService.Metadata("MA-D Verborgener Entwurf","Geheime Beschreibung","Test","mailto:test@example.invalid",new TreeSet<>(Set.of("Gesundheit")),new TreeSet<>(Set.of("DRAFT_ONLY_SECRET"))),false);
            var series=f.catalog.createDraft(f.manager,command("create_draft"),new CatalogCommandService.Create(f.office,"series-"+UUID.randomUUID(),"series",ManagementFixture.metadata("MA-D Eingeschränkte Zeitreihe")));
            var service=new DatasetIssueService(f.unit,f.authority);var first=service.createIssue(f.manager,command("create_issue"),series,f.version("dataset",series),"2025","Jahr 2025");var second=service.createIssue(f.manager,command("create_issue"),series,f.version("dataset",series),"2026","Jahr 2026");var hidden=service.createIssue(f.manager,command("create_issue"),series,f.version("dataset",series),"hidden","Unveröffentlichte Ausgabe");
            f.catalog.transition(f.manager,command("publish"),series,f.version("dataset",series),"publish","Serienmetadaten geprüft");
            service.updateIssueMetadata(f.manager,command("update_issue"),series,f.version("dataset",series),first,"Jahr 2025","Ausgabehinweis erhalten","published");service.updateIssueMetadata(f.manager,command("update_issue"),series,f.version("dataset",series),second,"Jahr 2026","Nur die Ausgabe, keine Kopie der Rootbeschreibung","published");service.setCurrentIssue(f.manager,command("set_current_issue"),series,f.version("dataset",series),second);
            var empty=f.catalog.createDraft(f.manager,command("create_draft"),new CatalogCommandService.Create(f.office,"empty-series-"+UUID.randomUUID(),"series",ManagementFixture.metadata("Serie ohne publizierte Ausgabe")));
            service.createIssue(f.manager,command("create_issue"),empty,f.version("dataset",empty),"draft-only","Noch nicht veröffentlicht");
            f.catalog.transition(f.manager,command("publish"),empty,f.version("dataset",empty),"publish","Nur Serienmetadaten publiziert");
            // Add a real mapped schema to the standalone current version.
            f.unit.execute(f.manager,command("operator.schema_fixture"),ctx->{
                var schema=ctx.newObject(Dataschema.class);schema.setTIliTid(UUID.randomUUID());schema.setIdentifier("schema-"+UUID.randomUUID());schema.setModelname("Testmodell");
                var attr=ctx.newObject(Attributedefinition.class);attr.setTIliTid(UUID.randomUUID());attr.setAschema1(schema);attr.setAname("gemeindename");attr.setDatatype("TEXT");attr.setOrdinal(1);attr.setMandatory(true);attr.setAdescription("Name der Gemeinde");
                ctx.commitChanges(); // Flush complete schema before its first accepted use; outer transaction remains atomic.
                // This is a new available version; an already accepted version/schema is never rewritten.
                var root=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(open)).selectOne(ctx);var issue=root.getDatasetissues().getFirst();
                var v=ctx.newObject(Dataversion.class);v.setTIliTid(UUID.randomUUID());v.setIssue1(issue);v.setVersionkey("schema-v2");v.setAsequence(2);v.setAschema1(schema);v.setAvailability("available");v.setValidationstatus("unknown");v.setAcceptedat(CayenneSql.now(ctx));v.setObjectcount(5L);v.setAttributecount(1L);issue.setCurrentversion1(v);return null;
            });
            for(int number=1;number<=13;number++){
                var probe=f.catalog.createDraft(f.manager,command("create_draft"),new CatalogCommandService.Create(f.office,"pagination-"+UUID.randomUUID(),"standalone",ManagementFixture.metadata("Paginationprobe "+String.format("%02d",number))));
                f.catalog.transition(f.manager,command("publish"),probe,f.version("dataset",probe),"publish","Pagination-Gegenprobe");
            }
            var ctx=f.runtime.newContext();var root=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(open)).selectOne(ctx);var seriesRoot=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(series)).selectOne(ctx);var draftRoot=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(draft)).selectOne(ctx);
            var values=new LinkedHashMap<String,String>();values.put("openId",open.toString());values.put("openIdentifier",root.getIdentifier());values.put("draftId",draft.toString());values.put("draftIdentifier",draftRoot.getIdentifier());values.put("seriesId",series.toString());values.put("seriesIdentifier",seriesRoot.getIdentifier());values.put("officeIdentifier",root.getOwneroffice1().getIdentifier());values.put("openFile",openFile.toString());values.put("emptySeriesIdentifier",ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(empty)).selectOne(ctx).getIdentifier());values.put("managerIssuer",f.manager.issuer());values.put("managerSubject",f.manager.subject());
            StringBuilder json=new StringBuilder("{\n");for(var e:values.entrySet()){if(json.length()>2)json.append(",\n");json.append("  \"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");}json.append("\n}\n");Path file=Path.of(".runtime/portal-fixture.json");Files.writeString(file,json);file.toFile().setReadable(false,false);file.toFile().setReadable(true,true);file.toFile().setWritable(false,false);file.toFile().setWritable(true,true);
            assertEquals("restricted",seriesRoot.getAccesslevel());
        }
    }
}
