package ch.so.agi.dateninfrastruktur.admin;
import org.junit.jupiter.api.Test;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetFilters;
import static org.junit.jupiter.api.Assertions.*;
import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
class ManagementViewsIT {
    @Test void auditAndGrantViewsAreBoundedAndRestrictedToCurrentAuthority(){
        try(var f=new ManagementFixture();var reader=ManagementFixture.source("di_audit_reader","DI_TEST_AUDIT_PASSWORD")){
            var root=f.published();var query=new ManagementQueryService(f.runtime,f.authority,reader);
            assertFalse(query.audit(f.manager,root,0).isEmpty());assertThrows(RuntimeException.class,()->query.audit(f.anna,root,0));
            assertThrows(RuntimeException.class,()->query.grants(f.anna,root));assertThrows(RuntimeException.class,()->query.detail(f.anna,root));
            assertTrue(query.datasets(f.anna,new DatasetFilters(null,null,null,null,0)).rows().isEmpty());assertTrue(query.requests(f.anna,true,0).isEmpty());
            var request=f.workflow.request(f.anna,command("request"),root,"Sichtprüfung",null);
            assertEquals(1,query.requests(f.anna,true,0).size());assertTrue(query.requests(f.ben,true,0).isEmpty());assertTrue(query.requests(f.anna,false,0).isEmpty());
            assertFalse(query.requests(f.manager,false,0).isEmpty());
            f.workflow.decide(f.manager,command("approve"),request,f.version("accessrequest",request),true,f.validity(),"Prüfung");
            assertEquals(1,query.grants(f.manager,root).size());assertEquals(1,query.effective(f.anna).size());
            var history=query.audit(f.manager,root,0);assertTrue(history.size()<=50);assertTrue(history.stream().anyMatch(r->"approve".equals(r.get("action_code"))));
            assertTrue(query.principals(f.manager,root,"anna-").size()<=20);assertThrows(RuntimeException.class,()->query.principals(f.anna,root,"claudia"));
        }
    }
}
