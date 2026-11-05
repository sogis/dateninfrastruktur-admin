package ch.so.agi.dateninfrastruktur.admin;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.cayenne.query.ObjectSelect;
import tools.jackson.databind.ObjectMapper;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
/** Test-only historical unknown/prepared delivery import for predecessor migration tests.
 * No product registration/activation API exists; never invoked by normal start. */
class ManagedDownloadFixtureIT {
    @Test void seedPreparedPrivateDeliveries()throws Exception{
        String issuer=System.getenv("DI_TEST_PILOT_ISSUER");if(issuer==null)throw new IllegalStateException("Explicit pilot issuer required");
        try(var f=new ManagementFixture(issuer,OidcLoginIT.CLAUDIA,OidcLoginIT.ANNA,OidcLoginIT.BEN)){
            var operator=new ActorContext("urn:di:operator","managed-delivery","service",null);
            f.unit.execute(operator,ManagementFixture.command("operator.bootstrap"),ctx->{
                var p=ObjectSelect.query(Principal.class).where(Principal.AISSUER.eq(operator.issuer()).andExp(Principal.ASUBJECT.eq(operator.subject()))).selectOne(ctx);
                if(p==null){p=ctx.newObject(Principal.class);p.setTIliTid(UUID.randomUUID());p.setAissuer(operator.issuer());p.setAsubject(operator.subject());p.setAkind("service");p.setAactive(true);p.setProvisionedat(CayenneSql.now(ctx));}
                var office=ObjectSelect.query(Office.class).where(Office.T_ILI_TID.eq(f.office)).selectOne(ctx);
                for(String cap:List.of("manage_metadata","publish")){var a=ctx.newObject(Authorityassignment.class);a.setTIliTid(UUID.randomUUID());a.setPrincipal1(p);a.setAssignedby1(p);a.setOffice1(office);a.setCapability(cap);a.setReason("Explicit synthetic private delivery operator");a.setValidfrom(CayenneSql.now(ctx));}return null;
            });
            var registration=ManagementFixture.command("operator.known_identity");f.unit.execute(f.ben,registration,ctx->{new ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository().findOrRegisterVerifiedUser(ctx,f.ben,registration);return null;});
            String prefix=System.getenv().getOrDefault("DI_TEST_DATASET_PREFIX","ma-e");
            if(!prefix.matches("[a-z][a-z0-9-]{3,60}"))throw new IllegalArgumentException("Explicit safe fixture namespace required");
            var data=new LinkedHashMap<String,Object>();var files=new ArrayList<Map<String,String>>();data.put("prefix",prefix);
            for(String kind:List.of("open","restricted")){
                UUID root=f.catalog.createDraft(f.manager,ManagementFixture.command("create_draft"),new CatalogCommandService.Create(f.office,prefix+"-"+kind,"standalone",ManagementFixture.metadata(prefix.equals("ma-e")?"MA-E Pilot "+kind:prefix+" "+kind)));
                f.catalog.transition(f.manager,ManagementFixture.command("publish"),root,f.version("dataset",root),"publish","Synthetischer Pilot");
                if(kind.equals("open"))f.catalog.changeAccessLevel(f.manager,ManagementFixture.command("change_access_level"),root,f.version("dataset",root),"open","Anonymer Pilotzugang");
                var issue=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(root)).selectOne(f.runtime.newContext()).getCurrentissue1().getTIliTid();
                for(int sequence=1;sequence<=2;sequence++){
                    final int number=sequence;
                    UUID version=f.unit.execute(f.manager,ManagementFixture.command("test.import_history"),ctx->{var row=ctx.newObject(Dataversion.class);row.setTIliTid(UUID.randomUUID());row.setIssue1(ObjectSelect.query(Datasetissue.class).where(Datasetissue.T_ILI_TID.eq(issue)).selectOne(ctx));row.setVersionkey("pilot-v"+number);row.setAsequence(number);row.setAcceptedat(UtcTime.toDatabase(Instant.now().minusSeconds(3600L*(3-number))));row.setSourcereference("urn:di:synthetic-historical-delivery:"+number);row.setValidationstatus("unknown");row.setAvailability("available");return row.getTIliTid();});
                    for(String format:List.of("csv","xlsx")){
                        UUID id=UUID.randomUUID();String filename="pilot-v"+sequence+"."+format;
                        f.unit.execute(f.manager,ManagementFixture.command("test.import_history"),ctx->{var row=ctx.newObject(Distribution.class);row.setTIliTid(id);row.setVersion1(ObjectSelect.query(Dataversion.class).where(Dataversion.T_ILI_TID.eq(version)).selectOne(ctx));row.setAformat(format);row.setFilename(filename);row.setMediatype(format.equals("csv")?"text/csv":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");row.setObjectkey("downloads/"+id+"/"+filename);row.setAvailability("prepared");return null;});
                        files.add(Map.of("dataset",root.toString(),"kind",kind,"id",id.toString(),"filename",filename,"key","downloads/"+id+"/"+filename,"path","/downloads/"+id+"/"+filename));
                    }
                    new DatasetIssueService(f.unit,f.authority).setCurrentVersion(f.manager,ManagementFixture.command("set_current_version"),root,f.version("dataset",root),issue,version);
                }
                data.put(kind,root.toString());
            }
            data.put("files",files);var output=Path.of(System.getenv("DI_TEST_PILOT_FIXTURE"));Files.createDirectories(output.getParent());Files.writeString(output,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data));output.toFile().setReadable(false,false);output.toFile().setReadable(true,true);
        }
    }
}
