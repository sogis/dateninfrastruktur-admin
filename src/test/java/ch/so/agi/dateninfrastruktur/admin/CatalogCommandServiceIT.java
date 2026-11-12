package ch.so.agi.dateninfrastruktur.admin;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
class CatalogCommandServiceIT {
    @Test void metadataPreservedAndLastWriteWins(){
        try(var f=new ManagementFixture()){
            assertThrows(RuntimeException.class,()->f.catalog.createDraft(f.anna,command("create_draft"),new CatalogCommandService.Create(f.office,"forbidden","standalone",ManagementFixture.metadata("Fremd"))));
            var root=f.draft();f.unit.execute(f.manager,command("fixture.imported_metadata"),ctx->{ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(root)).selectOne(ctx).setSurveymethod("Importiertes Feld behalten");return null;});
            f.catalog.update(f.manager,command("save_draft"),root,ManagementFixture.metadata("Neuer Titel"),false);
            f.catalog.update(f.manager,command("save_draft"),root,ManagementFixture.metadata("Neuester Titel"),false);
            assertThrows(RuntimeException.class,()->f.catalog.update(f.anna,command("save_draft"),root,ManagementFixture.metadata("Fremd"),false));
            f.catalog.transition(f.manager,command("submit"),root,"submit","Prüfung");
            f.catalog.transition(f.manager,command("publish"),root,"publish","Prüfung abgeschlossen");
            assertThrows(RuntimeException.class,()->f.catalog.update(f.manager,command("save_draft"),root,ManagementFixture.metadata("Falsche Aktion"),false));
            f.catalog.update(f.manager,command("save_published"),root,ManagementFixture.metadata("Publizierter Titel"),true);
            f.catalog.changeAccessLevel(f.manager,command("change_access_level"),root,"open","Öffentlich freigegeben");
            var fresh=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(root)).selectOne(f.runtime.newContext());
            assertEquals("Importiertes Feld behalten",fresh.getSurveymethod());assertEquals("open",fresh.getAccesslevel());assertEquals("published",fresh.getPublicationstatus());
            assertEquals("published",fresh.getDatasetissues().getFirst().getPublicationstatus());assertEquals(1,fresh.getThemeentries().size());assertEquals(1,fresh.getKeywordentries().size());
            f.catalog.transition(f.manager,command("archive"),root,"archive","Angebot beendet");
            assertEquals(3,f.count("SELECT count(*) FROM di_management.catalogaction a JOIN di_management.dataset d ON d.t_id=a.dataset WHERE d.t_ili_tid=?",root));
        }
    }
    @Test void metadataAuthorityAloneCannotPublishOrChangeAccess(){
        try(var f=new ManagementFixture()){
            var root=f.draft();f.unit.execute(f.manager,command("operator.revoke_authority"),ctx->{
                var revoker = ObjectSelect.query(Principal.class).where(Principal.T_ILI_TID.eq(f.managerId)).selectOne(ctx);
                var assignments = ObjectSelect.query(Authorityassignment.class)
                    .where(Authorityassignment.PRINCIPAL1.dot(Principal.T_ILI_TID).eq(f.managerId)).select(ctx);
                for (var assignment : assignments) {
                    if (!"manage_metadata".equals(assignment.getCapability())) {
                        assignment.setRevokedat(ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql.now(ctx));
                        assignment.setRevokedby1(revoker);
                        assignment.setRevocationreason("Berechtigungen für Negativtest entzogen");
                    }
                }
                return null;
            });
            f.catalog.update(f.manager,command("save_draft"),root,ManagementFixture.metadata("Erlaubt"),false);
            assertThrows(RuntimeException.class,()->f.catalog.transition(f.manager,command("publish"),root,"publish","Nicht erlaubt"));
            assertThrows(RuntimeException.class,()->f.catalog.changeAccessLevel(f.manager,command("change_access_level"),root,"open","Nicht erlaubt"));
        }
    }
}
