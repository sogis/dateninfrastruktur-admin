package ch.so.agi.dateninfrastruktur.admin;

import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import ch.so.agi.dateninfrastruktur.admin.catalog.DatasetIssueService;
import ch.so.agi.dateninfrastruktur.admin.ingestion.*;
import ch.so.agi.dateninfrastruktur.admin.operator.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Explicit synthetic fixture; all catalog changes use the product command services. */
public final class DeliveryG4FixtureCommand {
    record Case(String key,String label,String definition,boolean open,boolean published,boolean automatic){}
    public static void main(String[] args)throws Exception{
        if(!"true".equals(System.getenv("DI_COMPOSE_TEST_FIXTURE")))throw new IllegalStateException("Isolated G4 fixture only");
        String issuer=System.getenv("DI_TEST_PILOT_ISSUER");
        if(args.length==2&&"--archive".equals(args[0])){archive(issuer,UUID.fromString(args[1]));return;}
        boolean withoutGrants=args.length==2&&"--without-grants".equals(args[1]);
        if(args.length!=1&&!withoutGrants)throw new IllegalStateException("Explicit synthetic executor subject required");
        try(var f=new ManagementFixture(issuer,OidcLoginIT.CLAUDIA,OidcLoginIT.ANNA,OidcLoginIT.BEN)){
            var identities=new IdentityProvisioningService(f.unit);UUID anna=identities.ensureUser(f.anna);identities.ensureUser(f.ben);
            var operator=new ActorContext("urn:di:test-operator","g4-fixture","service",null);
            DeliveryServiceIdentityCommand.register(f.unit,operator,new DeliveryServiceIdentityCommand.Request(1,UUID.randomUUID(),issuer,args[0]),issuer);
            DeliveryAuthorityBootstrapCommand.bootstrap(f.unit,operator,new DeliveryAuthorityBootstrapCommand.Request(1,UUID.randomUUID(),f.office,null,issuer,f.manager.subject(),"Isolated G4 fixture"));
            var registry=new CsvDefinitionRegistry();
            var users=new SubmissionAuthorizationService(f.runtime,f.authority,new DeliveryConfigurationQuery(registry));
            var grants=new SubmitterAdministrationService(f.unit,f.authority,users);
            var configurations=new DeliveryConfigurationService(f.unit,f.authority,registry);
            var datasets=new TreeMap<String,Object>();
            for(var sample:List.of(new Case("population","A Altersstruktur","population-v1",true,true,true),
                new Case("municipality","B Gemeinde und Geschlecht","population-municipality-v1",false,true,true),
                new Case("types","C Typenfixture","csv-type-probe-v1",false,true,true),
                new Case("paused","D pausiert","population-v1",true,true,false),
                new Case("draft","E Entwurf","population-v1",true,false,true))){
                String identifier="g4-"+sample.key()+"-"+UUID.randomUUID();
                var id=f.catalog.createDraft(f.manager,ManagementFixture.command("create_draft"),new CatalogCommandService.Create(f.office,identifier,"standalone",ManagementFixture.metadata(sample.label())));
                if(sample.open())f.catalog.changeAccessLevel(f.manager,ManagementFixture.command("change_access_level"),id,f.version("dataset",id),"open","Isolated G4 open dataset");
                if(sample.published())f.catalog.transition(f.manager,ManagementFixture.command("publish"),id,f.version("dataset",id),"publish","Isolated G4 published dataset");
                configurations.update(f.manager,ManagementFixture.command("csv.configure"),id,new DeliveryConfigurationService.Update(f.version("dataset",id),true,sample.automatic(),sample.definition(),"replace_and_append",null));
                if(!withoutGrants)grants.grant(f.manager,ManagementFixture.command("submitter.grant"),new SubmitterAdministrationService.Grant(new SubmitterAdministrationService.Scope(null,id),anna,f.version("dataset",id),null,null,"Isolated G4 dataset delivery only"));
                var issue=f.unit.read(ctx->CayenneSql.rows(ctx,"SELECT i.t_ili_tid FROM di_management.datasetissue i JOIN di_management.dataset d ON d.t_id=i.dataset WHERE d.t_ili_tid=CAST(#bind($id) AS uuid)",Map.of("id",id.toString())).getFirst().get("t_ili_tid").toString());
                datasets.put(sample.key(),Map.of("datasetId",id,"issueId",issue,"label",sample.label(),"identifier",identifier));
            }
            datasets.put("anna",anna);
            if(withoutGrants){
                String identifier="g5-reihenprobe-"+UUID.randomUUID();
                var series=f.catalog.createDraft(f.manager,ManagementFixture.command("create_draft"),
                    new CatalogCommandService.Create(f.office,identifier,"series",ManagementFixture.metadata("G5 Reihenprobe")));
                f.catalog.changeAccessLevel(f.manager,ManagementFixture.command("change_access_level"),series,f.version("dataset",series),"open","Isolierte G5-Reihe");
                var issueCommands=new DatasetIssueService(f.unit,f.authority);
                var historical=issueCommands.createIssue(f.manager,ManagementFixture.command("create_issue"),series,f.version("dataset",series),"2025","G5 Ausgabe 2025");
                var current=issueCommands.createIssue(f.manager,ManagementFixture.command("create_issue"),series,f.version("dataset",series),"2026","G5 Ausgabe 2026");
                f.catalog.transition(f.manager,ManagementFixture.command("publish"),series,f.version("dataset",series),"publish","Isolierte G5-Reihe");
                issueCommands.updateIssueMetadata(f.manager,ManagementFixture.command("update_issue"),series,f.version("dataset",series),historical,"G5 Ausgabe 2025","Isolierte historische Ausgabe","published");
                issueCommands.updateIssueMetadata(f.manager,ManagementFixture.command("update_issue"),series,f.version("dataset",series),current,"G5 Ausgabe 2026","Isolierte aktuelle Ausgabe","published");
                issueCommands.setCurrentIssue(f.manager,ManagementFixture.command("set_current_issue"),series,f.version("dataset",series),current);
                datasets.put("seriesIdentifier",identifier);
            }
            Path output=Path.of("/runtime/g4-fixture.json");
            Files.writeString(output,CanonicalJson.encode(datasets));Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rw-------"));
            System.out.println("Explicit synthetic G4 fixture ready; two real model schemas, one type fixture and two ineligible datasets");
        }
    }
    private static void archive(String issuer,UUID id)throws Exception{
        var fixture=CanonicalJson.JSON.readTree(Files.readAllBytes(Path.of("/runtime/g4-fixture.json")));
        if(!id.toString().equals(fixture.path("municipality").path("datasetId").asString()))throw new IllegalStateException("Only the bound synthetic dataset may be archived");
        try(var writer=ManagementFixture.source("di_writer","DI_TEST_WRITER_PASSWORD")){
            var runtime=ch.so.agi.dateninfrastruktur.admin.persistence.ManagementRuntime.create(writer);
            try{
                var unit=new ch.so.agi.dateninfrastruktur.admin.persistence.AuditedUnitOfWork(runtime);
                var authority=new ch.so.agi.dateninfrastruktur.admin.access.AuthorityService(runtime);
                var manager=new ActorContext(issuer,OidcLoginIT.CLAUDIA,"user",null);
                long revision=unit.read(ctx->((Number)CayenneSql.rows(ctx,"SELECT row_version FROM di_management.dataset WHERE t_ili_tid=CAST(#bind($id) AS uuid)",Map.of("id",id.toString())).getFirst().get("row_version")).longValue());
                new CatalogCommandService(unit,authority).transition(manager,ManagementFixture.command("archive"),id,revision,"archive","Isolierte G4 Rücknahme");
            }finally{runtime.shutdown();}
        }
    }

}
