package ch.so.agi.dateninfrastruktur.admin;
import java.util.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.catalog.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
class DeliveryRegistrationIT {
    @Test void seriesIssueRegistrationUsesRealPrivateFileHashAndParentGuards()throws Exception{
        try(var f=new ManagementFixture()){
            UUID root=f.catalog.createDraft(f.manager,command("create_draft"),new CatalogCommandService.Create(f.office,"series-"+UUID.randomUUID(),"series",ManagementFixture.metadata("Zeitreihe")));
            var issues=new DatasetIssueService(f.unit,f.authority);var first=issues.createIssue(f.manager,command("create_issue"),root,f.version("dataset",root),"2025","Jahr 2025");var second=issues.createIssue(f.manager,command("create_issue"),root,f.version("dataset",root),"2026","Jahr 2026");
            issues.setCurrentIssue(f.manager,command("set_current_issue"),root,f.version("dataset",root),second);
            f.catalog.transition(f.manager,command("publish"),root,f.version("dataset",root),"publish","Serie geprüft");
            issues.updateIssueMetadata(f.manager,command("update_issue"),root,f.version("dataset",root),second,"Jahr 2026","Bestätigte Lieferung","published");
            Path base=Files.createTempDirectory(Path.of(".runtime"),"registration-");UUID fileId=UUID.randomUUID();Path file=base.resolve("downloads/"+fileId+"/data.csv");Files.createDirectories(file.getParent());byte[] content="name,value\nGrüezi,12\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(file,content);
            var registration=new DeliveryRegistrationService(f.unit,f.authority,(profile,key)->{
                try{if(!"isolated-test".equals(profile))throw new IllegalArgumentException();Path target=base.resolve(key).normalize();if(!target.startsWith(base) || Files.isSymbolicLink(target))throw new IllegalArgumentException();byte[] bytes=Files.readAllBytes(target);return new DeliveryRegistrationService.ObjectEvidence(bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));}
                catch(Exception error){throw new IllegalStateException("Private fixture not verified",error);}
            });
            var version=registration.registerAcceptedVersion(f.manager,command("register_version"),root,f.version("dataset",root),new DeliveryRegistrationService.AcceptedVersion(second,null,"v1",1,Instant.now(),null,"test confirmed reference"));
            registration.registerPrepared(f.manager,command("register_distribution"),root,f.version("dataset",root),new DeliveryRegistrationService.PreparedDistribution(version,fileId,"csv","data.csv","text/csv","isolated-test"));
            registration.verifyAndActivate(f.manager,command("activate_distribution"),root,f.version("dataset",root),fileId);
            var row=ObjectSelect.query(Distribution.class).where(Distribution.T_ILI_TID.eq(fileId)).selectOne(f.runtime.newContext());assertEquals(content.length,row.getBytesize());assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),row.getSha256());
            issues.setCurrentVersion(f.manager,command("set_current_version"),root,f.version("dataset",root),second,version);
            assertThrows(RuntimeException.class,()->issues.setCurrentVersion(f.manager,command("set_current_version"),root,f.version("dataset",root),first,version));
            registration.withdraw(f.manager,command("withdraw_distribution"),root,f.version("dataset",root),fileId,"Lieferung zurückgezogen");assertEquals("withdrawn",ObjectSelect.query(Distribution.class).where(Distribution.T_ILI_TID.eq(fileId)).selectOne(f.runtime.newContext()).getAvailability());
        }
    }
}
