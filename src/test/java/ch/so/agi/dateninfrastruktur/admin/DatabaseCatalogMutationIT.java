package ch.so.agi.dateninfrastruktur.admin;
import java.util.*;
import java.nio.file.*;
import org.apache.cayenne.query.ObjectSelect;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import ch.so.agi.dateninfrastruktur.admin.access.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
class DatabaseCatalogMutationIT {
    @Test void committedAdminMetadataMutationWhileTwoPublicInstancesRun()throws Exception{
        Map<?,?> values=new ObjectMapper().readValue(Files.readString(Path.of(".runtime/portal-fixture.json")),Map.class);UUID id=UUID.fromString(values.get("openId").toString());
        try(var ds=ManagementFixture.source("di_writer","DI_TEST_WRITER_PASSWORD")){
            var runtime=ManagementRuntime.create(ds);
            try{
                var actor=new ActorContext(values.get("managerIssuer").toString(),values.get("managerSubject").toString(),"user",null);
                var row=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(runtime.newContext());long version=runtime.performInTransaction(()->((Number)CayenneSql.rows(runtime.newContext(),"SELECT row_version FROM di_management.dataset WHERE t_ili_tid=CAST(#bind($id) AS uuid)",Map.of("id",id.toString())).getFirst().get("row_version")).longValue());
                var patch=new CatalogCommandService.Metadata("MA-D Aktualisiert ohne Reload",row.getAdescription(),row.getContactname(),row.getContactemail(),new TreeSet<>(row.getDatasetthemes().stream().map(Datasettheme::getTheme).toList()),new TreeSet<>(row.getDatasetkeywords().stream().map(Datasetkeyword::getKeyword).toList()));
                new CatalogCommandService(new AuditedUnitOfWork(runtime),new AuthorityService(runtime)).update(actor,ManagementFixture.command("save_published"),id,version,patch,true);
            }finally{runtime.shutdown();}
        }
    }
}
