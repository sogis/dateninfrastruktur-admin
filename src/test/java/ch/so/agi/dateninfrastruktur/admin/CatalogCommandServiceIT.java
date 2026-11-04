package ch.so.agi.dateninfrastruktur.admin;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
class CatalogCommandServiceIT {
    @Test void metadataPreservedCapabilitiesAndStaleRevisionEnforced(){
        try(var f=new ManagementFixture()){
            assertThrows(RuntimeException.class,()->f.catalog.createDraft(f.anna,command("create_draft"),new CatalogCommandService.Create(f.office,"forbidden","standalone",ManagementFixture.metadata("Fremd"))));
            var root=f.draft();f.unit.execute(f.manager,command("fixture.imported_metadata"),ctx->{ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(root)).selectOne(ctx).setSurveymethod("Importiertes Feld behalten");return null;});
            long revision=f.version("dataset",root);
            f.catalog.update(f.manager,command("save_draft"),root,revision,ManagementFixture.metadata("Neuer Titel"),false);
            assertThrows(RuntimeException.class,()->f.catalog.update(f.manager,command("save_draft"),root,revision,ManagementFixture.metadata("Veraltet"),false));
            assertThrows(RuntimeException.class,()->f.catalog.update(f.anna,command("save_draft"),root,f.version("dataset",root),ManagementFixture.metadata("Fremd"),false));
            f.catalog.transition(f.manager,command("submit"),root,f.version("dataset",root),"submit","Prüfung");
            f.catalog.transition(f.manager,command("publish"),root,f.version("dataset",root),"publish","Prüfung abgeschlossen");
            assertThrows(RuntimeException.class,()->f.catalog.update(f.manager,command("save_draft"),root,f.version("dataset",root),ManagementFixture.metadata("Falsche Aktion"),false));
            f.catalog.update(f.manager,command("save_published"),root,f.version("dataset",root),ManagementFixture.metadata("Publizierter Titel"),true);
            f.catalog.changeAccessLevel(f.manager,command("change_access_level"),root,f.version("dataset",root),"open","Öffentlich freigegeben");
            var fresh=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(root)).selectOne(f.runtime.newContext());
            assertEquals("Importiertes Feld behalten",fresh.getSurveymethod());assertEquals("open",fresh.getAccesslevel());assertEquals("published",fresh.getPublicationstatus());
            assertEquals("published",fresh.getDatasetissues().getFirst().getPublicationstatus());assertEquals(1,fresh.getDatasetthemes().size());assertEquals(1,fresh.getDatasetkeywords().size());
            f.catalog.transition(f.manager,command("archive"),root,f.version("dataset",root),"archive","Angebot beendet");
            assertEquals(3,f.count("SELECT count(*) FROM di_management.catalogaction a JOIN di_management.dataset d ON d.t_id=a.dataset WHERE d.t_ili_tid=?",root));
        }
    }
    @Test void metadataAuthorityAloneCannotPublishOrChangeAccess(){
        try(var f=new ManagementFixture()){
            var root=f.draft();f.unit.execute(f.manager,command("operator.revoke_authority"),ctx->{
                for(var assignment:ObjectSelect.query(Authorityassignment.class).where(Authorityassignment.PRINCIPAL1.dot(Principal.T_ILI_TID).eq(f.managerId)).select(ctx))
                    if(!"manage_metadata".equals(assignment.getCapability()))assignment.setRevokedat(ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql.now(ctx));return null;
            });
            f.catalog.update(f.manager,command("save_draft"),root,f.version("dataset",root),ManagementFixture.metadata("Erlaubt"),false);
            assertThrows(RuntimeException.class,()->f.catalog.transition(f.manager,command("publish"),root,f.version("dataset",root),"publish","Nicht erlaubt"));
            assertThrows(RuntimeException.class,()->f.catalog.changeAccessLevel(f.manager,command("change_access_level"),root,f.version("dataset",root),"open","Nicht erlaubt"));
        }
    }
}
