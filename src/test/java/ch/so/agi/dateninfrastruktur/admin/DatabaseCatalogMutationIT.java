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
        Map<?,?> values=new ObjectMapper().readValue(Files.readString(Path.of(System.getenv("DI_PORTAL_FIXTURE"))),Map.class);UUID id=UUID.fromString(values.get("openId").toString());
        try(var ds=ManagementFixture.source("di_writer","DI_TEST_WRITER_PASSWORD")){
            var runtime=ManagementRuntime.create(ds);
            try{
                var actor=new ActorContext(values.get("managerIssuer").toString(),values.get("managerSubject").toString(),"user",null);
                var row=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(runtime.newContext());
                var patch=new CatalogCommandService.Metadata("Katalog aktualisiert ohne Reload",row.getAdescription(),row.primaryContact().getAname(),row.primaryContact().getEmail(),new TreeSet<>(row.getThemeentries().stream().map(Themeentry::getAcode).toList()),new TreeSet<>(row.getKeywordentries().stream().map(Keywordentry::getAvalue).toList()));
                new CatalogCommandService(runtime,new AuthorityService(runtime)).update(actor,ManagementFixture.command("save_published"),id,patch,true);
            }finally{runtime.shutdown();}
        }
    }
}
