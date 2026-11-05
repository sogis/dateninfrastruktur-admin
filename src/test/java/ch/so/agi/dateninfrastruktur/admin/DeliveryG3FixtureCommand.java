package ch.so.agi.dateninfrastruktur.admin;

import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import ch.so.agi.dateninfrastruktur.admin.ingestion.*;
import ch.so.agi.dateninfrastruktur.admin.operator.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Explicit synthetic G3 fixture, absent from the normal image. */
public final class DeliveryG3FixtureCommand {
    public static void main(String[] args)throws Exception{
        if(!"true".equals(System.getenv("DI_COMPOSE_TEST_FIXTURE")) || args.length!=1)throw new IllegalStateException("Isolated test fixture required");
        String issuer=System.getenv("DI_TEST_PILOT_ISSUER");String serviceSubject=args[0];
        try(var f=new ManagementFixture(issuer,OidcLoginIT.CLAUDIA,OidcLoginIT.ANNA,OidcLoginIT.BEN)){
            var identities=new IdentityProvisioningService(f.unit);UUID anna=identities.ensureUser(f.anna);identities.ensureUser(f.ben);
            var operator=new ActorContext("urn:di:test-operator","g3-fixture","service",null);
            DeliveryServiceIdentityCommand.register(f.unit,operator,new DeliveryServiceIdentityCommand.Request(1,UUID.randomUUID(),issuer,serviceSubject),issuer);
            DeliveryAuthorityBootstrapCommand.bootstrap(f.unit,operator,new DeliveryAuthorityBootstrapCommand.Request(1,UUID.randomUUID(),f.office,null,issuer,f.manager.subject(),"Isolated G3 fixture"));
            var registry=new CsvDefinitionRegistry();var users=new SubmissionAuthorizationService(f.runtime,f.authority,new DeliveryConfigurationQuery(registry));var grants=new SubmitterAdministrationService(f.unit,f.authority,users);
            long officeRevision=f.unit.read(ctx->((Number)CayenneSql.rows(ctx,"SELECT row_version FROM di_management.office WHERE t_ili_tid=CAST(#bind($id) AS uuid)",Map.of("id",f.office.toString())).getFirst().get("row_version")).longValue());
            UUID assignment=grants.grant(f.manager,ManagementFixture.command("submitter.grant"),new SubmitterAdministrationService.Grant(new SubmitterAdministrationService.Scope(f.office,null),anna,officeRevision,null,null,"Synthetic G3 CSV deliveries"));
            var datasets=new ArrayList<Map<String,Object>>();
            for(String name:List.of("A","B")){
                String title="G3 Lieferung "+name+(name.equals("A")?" <img src=x onerror=window.diXss=1>":"");
                UUID id=f.catalog.createDraft(f.manager,ManagementFixture.command("create_draft"),new CatalogCommandService.Create(f.office,"g3-"+name.toLowerCase()+"-"+UUID.randomUUID(),"standalone",ManagementFixture.metadata(title)));
                f.catalog.transition(f.manager,ManagementFixture.command("publish"),id,f.version("dataset",id),"publish","Isolated G3 dataset");
                new DeliveryConfigurationService(f.unit,f.authority,registry).update(f.manager,ManagementFixture.command("csv.configure"),id,new DeliveryConfigurationService.Update(f.version("dataset",id),true,true,"population-v1","replace_only",null));
                datasets.add(Map.of("id",id,"title",title));
            }
            var result=Map.of("datasets",datasets,"grant",assignment,"office",f.office);
            Path output=Path.of("/runtime/g3-fixture.json");Files.writeString(output,CanonicalJson.encode(result));Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rw-------"));
            System.out.println("Explicit synthetic G3 fixture ready; two datasets, one scoped supplier, verified executor identity");
        }
    }
}
