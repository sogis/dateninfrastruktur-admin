package ch.so.agi.dateninfrastruktur.admin;
import java.util.*;
import java.time.*;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.cayenne.runtime.CayenneRuntime;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.access.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.*;

final class ManagementFixture implements AutoCloseable {
    final HikariDataSource writer=source("di_writer","DI_TEST_WRITER_PASSWORD"),admin=source("postgres","DI_TEST_ADMIN_PASSWORD"),authz=source("di_authz","DI_TEST_AUTHZ_PASSWORD");
    final CayenneRuntime runtime=ManagementRuntime.create(writer);
    final AuditedUnitOfWork unit=new AuditedUnitOfWork(runtime);
    final AuthorityService authority=new AuthorityService(runtime);
    final AccessWorkflowService workflow=new AccessWorkflowService(unit,authority);
    final CatalogCommandService catalog=new CatalogCommandService(unit,authority);
    final String office=UUID.randomUUID().toString(); UUID managerId=UUID.randomUUID();
    final String suffix=UUID.randomUUID().toString();
    final ActorContext manager,anna,ben;
    static ActorContext actor(String subject){return new ActorContext("https://identity.example.invalid",subject,"user",null);}
    static CommandContext command(String action){return new CommandContext(UUID.randomUUID(),UUID.randomUUID(),action,"Integrationstest");}
    static HikariDataSource source(String user,String key){
        if(System.getenv("DI_TEST_URL")==null)throw new IllegalStateException("Use isolated Management harness");
        var ds=new HikariDataSource();ds.setJdbcUrl(System.getenv("DI_TEST_URL"));ds.setUsername(user);ds.setPassword(System.getenv(key));ds.setMaximumPoolSize(6);ds.setMinimumIdle(0);return ds;
    }
    ManagementFixture(){this("https://identity.example.invalid",null,null,null);}
    ManagementFixture(String issuer,String managerSubject,String annaSubject,String benSubject){
        manager=new ActorContext(issuer,managerSubject==null?"claudia-"+suffix:managerSubject,"user",null);
        anna=new ActorContext(issuer,annaSubject==null?"anna-"+suffix:annaSubject,"user",null);
        ben=new ActorContext(issuer,benSubject==null?"ben-"+suffix:benSubject,"user",null);
        unit.execute(manager,command("operator.bootstrap"),ctx->{
            var p=ObjectSelect.query(Principal.class).where(Principal.AISSUER.eq(manager.issuer()).andExp(Principal.ASUBJECT.eq(manager.subject()))).selectOne(ctx);
            if(p==null){p=ctx.newObject(Principal.class);p.setTIliTid(managerId);p.setAissuer(manager.issuer());p.setAsubject(manager.subject());p.setAkind("user");p.setAactive(true);p.setProvisionedat(CayenneSql.now(ctx));}else managerId=p.getTIliTid();
            var o=ctx.newObject(Office.class);o.setTIliTid(office);o.setIdentifier("office-"+suffix);o.setAbbreviation(suffix.substring(0,8));o.setAname("Prüfamt");o.setAactive(true);
            for(String capability:List.of("manage_metadata","publish","manage_access")){
                var a=ctx.newObject(Authorityassignment.class);a.setTIliTid(UUID.randomUUID());a.setPrincipal1(p);a.setOffice1(o);a.setAssignedby1(p);a.setCapability(capability);a.setReason("Testsetup");a.setValidfrom(CayenneSql.now(ctx).minusDays(1));
            }return null;
        });
    }
    static CatalogCommandService.Metadata metadata(String title){return new CatalogCommandService.Metadata(title,"Grüezi\n\nBeschreibung <script>kein HTML</script>","Testkontakt","mailto:test@example.invalid",new TreeSet<>(Set.of("Geografie")),new TreeSet<>(Set.of("Testdaten")));}
    UUID draft(){return catalog.createDraft(manager,command("create_draft"),new CatalogCommandService.Create(office,"data-"+UUID.randomUUID(),"standalone",metadata("Testdatensatz")));}
    UUID published(){var id=draft();catalog.transition(manager,command("publish"),id,version("dataset",id),"publish","Freigegeben für Test");return id;}
    long version(String table,UUID id){
        if(!Set.of("dataset","accessrequest","accessgrant").contains(table))throw new IllegalArgumentException();
        try(var c=admin.getConnection();var ps=c.prepareStatement("SELECT row_version FROM di_management."+table+" WHERE t_ili_tid=?")){
            ps.setObject(1,id);try(var r=ps.executeQuery()){if(!r.next())throw new IllegalArgumentException();return r.getLong(1);}
        }catch(java.sql.SQLException e){throw new RuntimeException(e);}
    }
    long count(String sql,UUID id){try(var c=admin.getConnection();var ps=c.prepareStatement(sql)){ps.setObject(1,id);try(var r=ps.executeQuery()){r.next();return r.getLong(1);}}catch(java.sql.SQLException e){throw new RuntimeException(e);}}
    Accessrequest request(UUID id){return ObjectSelect.query(Accessrequest.class).where(Accessrequest.T_ILI_TID.eq(id)).selectOne(runtime.newContext());}
    AccessWorkflowService.Validity validity(){return new AccessWorkflowService.Validity(Instant.now().minusSeconds(10),Instant.now().plusSeconds(3600));}
    public void close(){runtime.shutdown();writer.close();admin.close();authz.close();}
}
