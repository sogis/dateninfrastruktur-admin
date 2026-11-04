package ch.so.agi.dateninfrastruktur.admin;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.legacy.*;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import static org.junit.jupiter.api.Assertions.*;
class LegacyCatalogImportIT {
    @Test void fullRealDryRunConflictsAndRepresentativeApplyRemainIdempotentWithoutOverwritingAdminChanges()throws Exception{
        try(var f=new ManagementFixture()){
            var importer=new LegacyCatalogImportService(f.runtime);var base=Path.of("../dateninfrastruktur-sodata/spec/fixtures");
            var full=LegacyCatalog.read(base.resolve("published_catalog_full_62_entries.xtf"));long auditBefore=f.count("SELECT count(*) FROM audit.row_change WHERE ?::uuid IS NOT NULL",UUID.randomUUID());long rootsBefore=f.count("SELECT count(*) FROM di_management.dataset WHERE ?::uuid IS NOT NULL",UUID.randomUUID());var plan=importer.dryRun(full);assertEquals(62,plan.entries().size());assertTrue(plan.entries().stream().anyMatch(e->e.conflicts().stream().anyMatch(c->c.startsWith("SERIES_SHARED_CONFLICT"))));
            assertEquals(auditBefore,f.count("SELECT count(*) FROM audit.row_change WHERE ?::uuid IS NOT NULL",UUID.randomUUID()));assertEquals(rootsBefore,f.count("SELECT count(*) FROM di_management.dataset WHERE ?::uuid IS NOT NULL",UUID.randomUUID()));
            var source=LegacyCatalog.read(base.resolve("published_catalog_examples_only.xtf"));String standalone="ch.so.wasserqualitaet_grundwasser",series="ch.so.bevoelkerung.altersstruktur";
            var clean=importer.dryRun(source);assertTrue(clean.entries().stream().filter(e->Set.of(standalone,series).contains(e.identifier())).allMatch(e->Set.of("NEW","SKIP").contains(e.status())),clean.toString());
            var operator=new ActorContext("urn:di:operator","legacy-integration","service",null);var command=ManagementFixture.command("legacy.import");
            var ids=importer.apply(operator,command,source,Set.of(standalone,series));assertEquals(2,ids.size());
            assertEquals(new HashSet<>(ids),new HashSet<>(importer.apply(operator,command,source,Set.of(standalone,series))));
            assertThrows(RuntimeException.class,()->importer.apply(operator,command,source,Set.of(standalone)));
            UUID id=LegacyCatalog.stable("dataset",standalone);var root=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(f.runtime.newContext());
            assertEquals(standalone,root.getIdentifier());assertEquals("open",root.getAccesslevel());assertEquals(1,root.getDatasetissues().size());assertEquals("default",root.getDatasetissues().getFirst().getIssuekey());
            var version=root.getDatasetissues().getFirst().getDataversions().getFirst();assertEquals("prepared",version.getAvailability());assertNull(version.getAcceptedat());assertFalse(version.getAschema1().getAttributedefinitions().isEmpty());
            for(var file:version.getDistributions()){assertEquals("prepared",file.getAvailability());assertNull(file.getSha256());assertNull(file.getBytesize());assertNotNull(file.getLegacyaccessurl());}
            f.unit.execute(operator,ManagementFixture.command("fixture.admin_edit_after_import"),ctx->{ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(ctx).setAdescription("Spätere Adminbeschreibung muss erhalten bleiben");return null;});
            var replay=importer.apply(operator,ManagementFixture.command("legacy.import"),source,Set.of(standalone,series));assertEquals(new HashSet<>(ids),new HashSet<>(replay));
            assertEquals("Spätere Adminbeschreibung muss erhalten bleiben",ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(f.runtime.newContext()).getAdescription());
            assertThrows(RuntimeException.class,()->importer.apply(operator,ManagementFixture.command("legacy.import"),full,Set.of(series)));
            assertEquals(2,f.count("SELECT count(*) FROM di_management.dataset WHERE t_ili_tid=? OR identifier LIKE 'ch.so.bevoelkerung.altersstruktur'",id));
            // Unknown access codes never become open.
            var bad=LegacyCatalog.read(base.resolve("published_catalog_examples_only.xtf"));var level=LegacyCatalog.at(bad.roots().getFirst(),"accessRights/AccessRights/localAccessLevel");level.setTextContent("unknown-never-open");assertTrue(importer.dryRun(bad).entries().stream().anyMatch(e->e.conflicts().stream().anyMatch(c->c.startsWith("UNKNOWN_ACCESS_CODE"))));
            Path evidence=Path.of(".runtime/legacy-full-dry-run.json");Files.writeString(evidence,new tools.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(plan));
        }
    }
    @Test void canonicalBagsPreserveAllThemesAndReportMultipleCreatorsAndContacts()throws Exception{
        try(var f=new ManagementFixture()){
            var importer=new LegacyCatalogImportService(f.runtime);var source=LegacyCatalog.read(Path.of("../dateninfrastruktur-sodata/spec/fixtures/published_catalog_examples_only.xtf"));
            var root=source.roots().stream().filter(e->LegacyCatalog.name(e).equals("Dataset")).findFirst().orElseThrow();
            String id="ch.so.canonical-bag."+UUID.randomUUID();LegacyCatalog.at(root,"identifier").setTextContent(id);LegacyCatalog.at(root,"resourceUri").setTextContent("https://example.invalid/dataset/"+id);
            var distributions=root.getElementsByTagNameNS("*","Distribution");for(int n=0;n<distributions.getLength();n++){var distribution=(org.w3c.dom.Element)distributions.item(n);LegacyCatalog.at(distribution,"distributionUri").setTextContent("https://example.invalid/dataset/"+id+"/distribution/"+LegacyCatalog.text(distribution,"format"));}
            var wrapper=LegacyCatalog.children(root,"themes").getFirst();var original=LegacyCatalog.children(wrapper,"ThemeAssignment").getFirst();var second=(org.w3c.dom.Element)original.cloneNode(true);LegacyCatalog.at(second,"localTheme").setTextContent("Geografie");wrapper.appendChild(second);wrapper.appendChild(original.cloneNode(true));
            var initialCreator=LegacyCatalog.at(root,"creator");initialCreator.appendChild(LegacyCatalog.children(initialCreator,"Office").getFirst().cloneNode(true));
            var initialContact=LegacyCatalog.at(root,"contactPoint");initialContact.appendChild(LegacyCatalog.children(initialContact,"ContactPoint").getFirst().cloneNode(true));
            Path input=Path.of(".runtime/legacy-multitheme-"+UUID.randomUUID()+".xtf");Files.writeString(input,LegacyCatalog.rawXml(root.getOwnerDocument().getDocumentElement()));var multiple=LegacyCatalog.read(input);
            var plan=importer.dryRun(multiple);assertEquals("NEW",plan.entries().stream().filter(e->id.equals(e.identifier())).findFirst().orElseThrow().status());
            importer.apply(new ActorContext("urn:di:operator","bag-integration","service",null),ManagementFixture.command("legacy.import"),multiple,Set.of(id));
            var stored=ObjectSelect.query(Dataset.class).where(Dataset.IDENTIFIER.eq(id)).selectOne(f.runtime.newContext());assertEquals(Set.of("Geografie","Raum_und_Umwelt"),stored.getDatasetthemes().stream().map(Datasettheme::getTheme).collect(java.util.stream.Collectors.toSet()));assertEquals(2,stored.getDatasetthemes().size());
            var creator=LegacyCatalog.at(root,"creator");var foreign=(org.w3c.dom.Element)LegacyCatalog.children(creator,"Office").getFirst().cloneNode(true);LegacyCatalog.at(foreign,"officeUri").setTextContent("https://example.invalid/agent/foreign");creator.appendChild(foreign);
            Files.writeString(input,LegacyCatalog.rawXml(root.getOwnerDocument().getDocumentElement()));var creatorInput=LegacyCatalog.read(input);var creatorPlan=importer.dryRun(creatorInput);assertTrue(creatorPlan.entries().stream().filter(e->id.equals(e.identifier())).findFirst().orElseThrow().conflicts().stream().anyMatch(c->c.startsWith("MULTIPLE_CREATORS_REQUIRE_EXPLICIT_OWNER")));
            assertThrows(RuntimeException.class,()->importer.apply(new ActorContext("urn:di:operator","bag-integration","service",null),ManagementFixture.command("legacy.import"),creatorInput,Set.of(id)));
            creator.removeChild(creator.getLastChild());var contacts=LegacyCatalog.at(root,"contactPoint");var otherContact=(org.w3c.dom.Element)LegacyCatalog.children(contacts,"ContactPoint").getFirst().cloneNode(true);LegacyCatalog.at(otherContact,"email").setTextContent("mailto:other@example.invalid");contacts.appendChild(otherContact);
            Files.writeString(input,LegacyCatalog.rawXml(root.getOwnerDocument().getDocumentElement()));
            assertTrue(importer.dryRun(LegacyCatalog.read(input)).entries().stream().filter(e->id.equals(e.identifier())).findFirst().orElseThrow().conflicts().stream().anyMatch(c->c.startsWith("MULTIPLE_CONTACT_POINTS_REQUIRE_EXPLICIT_CONTACT")));
        }
    }
}
