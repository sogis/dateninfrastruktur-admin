package ch.so.agi.dateninfrastruktur.admin;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.authorization.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
import static org.junit.jupiter.api.Assertions.*;
import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
class DownloadAuthorizationRepositoryIT {
    static UUID file(ManagementFixture f,UUID root,int sequence){return f.unit.execute(f.manager,command("operator.register_fixture"),ctx->{
        var dataset=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(root)).selectOne(ctx);
        var version=ctx.newObject(Dataversion.class);version.setTIliTid(UUID.randomUUID());version.setIssue1(dataset.getDatasetissues().getFirst());version.setVersionkey("v"+sequence);version.setAsequence(sequence);version.setAvailability("available");version.setValidationstatus("success");version.setAcceptedat(CayenneSql.now(ctx));
        var distribution=ctx.newObject(Distribution.class);UUID id=UUID.randomUUID();distribution.setTIliTid(id);distribution.setVersion1(version);distribution.setAformat("csv");distribution.setFilename("data.csv");distribution.setMediatype("text/csv");distribution.setAvailability("available");distribution.setBytesize(10L);distribution.setSha256("0".repeat(64));distribution.setStorageprofile("private-test");distribution.setObjectkey("downloads/"+id+"/data.csv");return id;
    });}
    @Test void freshSingleStatementEvaluatesAllAvailableVersionsGroupsValidityAndRevocation(){
        try(var f=new ManagementFixture()){
            UUID root=f.published(),oldFile=file(f,root,1),newFile=file(f,root,2);var repo=new JdbcAuthorizationRepository(f.authz);var parser=new DownloadPathResolver();
            var oldTarget=parser.parse("/downloads/"+oldFile+"/data.csv","GET");var newTarget=parser.parse("/downloads/"+newFile+"/data.csv","HEAD");
            assertEquals(401,repo.evaluate(Optional.empty(),oldTarget).status());assertEquals(403,repo.evaluate(Optional.of(f.anna),newTarget).status());
            UUID request=f.workflow.request(f.anna,command("request"),root,"Analyse",null);UUID person=f.request(request).getApplicant1().getTIliTid();
            var groupId=f.unit.execute(f.manager,command("operator.group"),ctx->{
                var group=ctx.newObject(Principal.class);var id=UUID.randomUUID();group.setTIliTid(id);group.setAkind("group");group.setAissuer("urn:di:local-group");group.setAsubject(id.toString());group.setAactive(true);group.setProvisionedat(CayenneSql.now(ctx));group.setDisplayname("Lokale Testgruppe");
                var membership=ctx.newObject(Groupmembership.class);membership.setTIliTid(UUID.randomUUID());membership.setAgroup1(group);membership.setAmember1(ObjectSelect.query(Principal.class).where(Principal.T_ILI_TID.eq(person)).selectOne(ctx));membership.setValidfrom(CayenneSql.now(ctx).minusMinutes(1));membership.setReason("Operator eingerichtet");return id;
            });
            var direct=f.workflow.grantDirect(f.manager,command("direct_grant"),root,f.version("dataset",root),person,f.validity(),"Direkter Weg");
            var group=f.workflow.grantDirect(f.manager,command("direct_grant"),root,f.version("dataset",root),groupId,f.validity(),"Gruppenweg");
            assertEquals(204,repo.evaluate(Optional.of(f.anna),oldTarget).status());assertEquals(204,repo.evaluate(Optional.of(f.anna),newTarget).status());
            f.workflow.revoke(f.manager,command("revoke"),direct,f.version("accessgrant",direct),"Direkter Weg endet");assertEquals(204,repo.evaluate(Optional.of(f.anna),oldTarget).status());
            f.unit.execute(f.manager,command("operator.disable_group"),ctx->{ObjectSelect.query(Principal.class).where(Principal.T_ILI_TID.eq(groupId)).selectOne(ctx).setAactive(false);return null;});
            assertEquals(403,repo.evaluate(Optional.of(f.anna),oldTarget).status());
            f.unit.execute(f.manager,command("operator.enable_group"),ctx->{ObjectSelect.query(Principal.class).where(Principal.T_ILI_TID.eq(groupId)).selectOne(ctx).setAactive(true);return null;});
            assertEquals(204,repo.evaluate(Optional.of(f.anna),oldTarget).status());
            f.unit.execute(f.manager,command("operator.expire_membership"),ctx->{var m=ObjectSelect.query(Groupmembership.class).where(Groupmembership.AGROUP1.dot(Principal.T_ILI_TID).eq(groupId)).selectOne(ctx);m.setValidfrom(CayenneSql.now(ctx).minusDays(1));m.setValiduntil(CayenneSql.now(ctx).minusSeconds(1));return null;});
            assertEquals(403,repo.evaluate(Optional.of(f.anna),oldTarget).status());
            f.workflow.revoke(f.manager,command("revoke"),group,f.version("accessgrant",group),"Letzter Weg endet");assertEquals(403,repo.evaluate(Optional.of(f.anna),oldTarget).status());assertEquals(403,repo.evaluate(Optional.of(f.anna),newTarget).status());
            f.workflow.grantDirect(f.manager,command("direct_grant"),root,f.version("dataset",root),person,new ch.so.agi.dateninfrastruktur.admin.access.AccessWorkflowService.Validity(java.time.Instant.now().plusSeconds(600),java.time.Instant.now().plusSeconds(1200)),"Zukünftiger Weg");
            assertEquals(403,repo.evaluate(Optional.of(f.anna),newTarget).status());
            f.catalog.changeAccessLevel(f.manager,command("change_access_level"),root,f.version("dataset",root),"open","Öffentlich");assertEquals(204,repo.evaluate(Optional.empty(),oldTarget).status());
            assertEquals(404,repo.evaluate(Optional.empty(),parser.parse("/downloads/"+oldFile+"/other.csv","GET")).status());
            f.catalog.transition(f.manager,command("archive"),root,f.version("dataset",root),"archive","Beendet");assertEquals(404,repo.evaluate(Optional.of(f.anna),newTarget).status());
        }
    }
}
