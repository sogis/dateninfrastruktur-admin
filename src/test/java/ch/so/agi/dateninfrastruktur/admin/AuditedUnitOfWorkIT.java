package ch.so.agi.dateninfrastruktur.admin;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.cayenne.query.*;
import org.apache.cayenne.runtime.CayenneRuntime;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditedUnitOfWorkIT {
    HikariDataSource source(String user,String password) {
        var ds=new HikariDataSource();ds.setJdbcUrl(System.getenv("DI_TEST_URL"));ds.setUsername(user);ds.setPassword(password);ds.setMaximumPoolSize(1);ds.setConnectionInitSql("SET TIME ZONE 'Pacific/Auckland'");return ds;
    }
    CommandContext command(String action) {return new CommandContext(UUID.randomUUID(),UUID.randomUUID(),action,"synthetic audit test");}
    ActorContext actor(String subject) {return new ActorContext("https://identity.example.invalid",subject,"user",null);}
    @Test void realMappingTransactionsAuditAndRedaction() throws Exception {
        assertNotNull(System.getenv("DI_TEST_URL"),"Run the isolated persistence harness, not an unrelated database");
        try(var ds=source("di_writer",System.getenv("DI_TEST_WRITER_PASSWORD"));var admin=source("postgres",System.getenv("DI_TEST_ADMIN_PASSWORD"))) {
            var runtime=ManagementRuntime.create(ds);
            try {
                assertFalse(runtime.getDataDomain().isSharedCacheEnabled());
                var uow=new AuditedUnitOfWork(runtime);var cmd=command("mapping.create");UUID rootId=UUID.randomUUID(),officeId=UUID.randomUUID(),issueId=UUID.randomUUID();
                String[] tx={null};
                uow.execute(actor("anna"),cmd,ctx->{
                    var result=SQLExec.query("SELECT pg_current_xact_id()::text AS tx").execute(ctx).firstList();
                    tx[0]=((Map<?,?>)result.getFirst()).get("tx").toString();
                    var office=ctx.newObject(Office.class);office.setTIliTid(officeId);office.setIdentifier("office-"+officeId);office.setAname("Testamt");office.setAbbreviation(officeId.toString().substring(0,8));office.setAactive(true);
                    var root=ctx.newObject(Dataset.class);root.setTIliTid(rootId);root.setIdentifier("dataset-"+rootId);root.setAkind("standalone");root.setTitle("Statistik");root.setAdescription("Grüezi\n\nMehrzeilig — vollständig");root.setContactemail("mailto:demo@example.invalid");root.setAccesslevel("restricted");root.setPublicationstatus("draft");root.setModified(LocalDate.of(2026,11,4));root.setOwneroffice1(office);root.setPublisheroffice1(office);
                    var issue=ctx.newObject(Datasetissue.class);issue.setTIliTid(issueId);issue.setIssuekey("default");issue.setLabel("Einzelangebot");issue.setPublicationstatus("draft");issue.setModified(LocalDate.of(2026,11,4));issue.setDataset1(root);
                    return rootId;
                });
                try(var c=admin.getConnection();var ps=c.prepareStatement("SELECT count(*) FILTER (WHERE operation='I'),count(DISTINCT transaction_id),min(transaction_id),min(actor_subject),min(database_user) FROM audit.row_change WHERE command_id=?")){
                    ps.setObject(1,cmd.commandId());try(var rs=ps.executeQuery()){rs.next();assertEquals(3,rs.getInt(1));assertEquals(1,rs.getInt(2));assertEquals(tx[0],rs.getString(3));assertEquals("anna",rs.getString(4));assertEquals("di_writer",rs.getString(5));}
                }
                var fresh=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(rootId)).selectOne(runtime.newContext());
                assertEquals("Grüezi\n\nMehrzeilig — vollständig",fresh.getAdescription());assertEquals(officeId,fresh.getOwneroffice1().getTIliTid());assertEquals(1,fresh.getDatasetissues().size());
                var rollbackCommand=command("rollback.after_flush");
                var thrown=assertThrows(org.apache.cayenne.CayenneRuntimeException.class,()->uow.execute(actor("ben"),rollbackCommand,ctx->{
                    var row=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(rootId)).selectOne(ctx);row.setTitle("Must roll back");ctx.commitChanges();throw new IllegalStateException("after successful ORM flush");
                }));assertInstanceOf(IllegalStateException.class,thrown.getCause());assertEquals("after successful ORM flush",thrown.getCause().getMessage());
                try(var c=admin.getConnection();var ps=c.prepareStatement("SELECT count(*) FROM audit.row_change WHERE command_id=?")){ps.setObject(1,rollbackCommand.commandId());try(var rs=ps.executeQuery()){rs.next();assertEquals(0,rs.getInt(1));}}
                assertEquals("Statistik",ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(rootId)).selectOne(runtime.newContext()).getTitle());
                var update=command("metadata.update");uow.execute(actor("ben"),update,ctx->{var row=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(rootId)).selectOne(ctx);row.setTitle("Aktuell");return rootId;});
                try(var c=admin.getConnection();var ps=c.prepareStatement("SELECT actor_subject,operation,old_values->>'title',new_values->>'title',aggregate_dataset_oid FROM audit.row_change WHERE command_id=?")){
                    ps.setObject(1,update.commandId());try(var rs=ps.executeQuery()){assertTrue(rs.next());assertEquals("ben",rs.getString(1));assertEquals("U",rs.getString(2));assertEquals("Statistik",rs.getString(3));assertEquals("Aktuell",rs.getString(4));assertEquals(rootId.toString(),rs.getString(5));}
                }
                try(var c=ds.getConnection();var ps=c.prepareStatement("UPDATE di_management.dataset SET title='no actor' WHERE t_ili_tid=?")){
                    ps.setObject(1,rootId);SQLException e=assertThrows(SQLException.class,ps::executeUpdate);assertEquals("P0001",e.getSQLState());assertTrue(e.getMessage().contains("audit context"));
                }
                UUID principalId=UUID.randomUUID();var redaction=command("principal.register");uow.execute(actor("anna"),redaction,ctx->{
                    var p=ctx.newObject(Principal.class);p.setTIliTid(principalId);p.setAkind("user");p.setAissuer("urn:test");p.setAsubject(principalId.toString());p.setAactive(true);p.setDisplayname("Do not duplicate this name");p.setProvisionedat(UtcTime.toDatabase(Instant.parse("2026-11-04T12:00:00Z")));return principalId;
                });
                try(var c=admin.getConnection();var ps=c.prepareStatement("SELECT jsonb_exists(new_values,'displayname'),new_values->>'aissuer' FROM audit.row_change WHERE command_id=?")){ps.setObject(1,redaction.commandId());try(var rs=ps.executeQuery()){assertTrue(rs.next());assertFalse(rs.getBoolean(1));assertEquals("urn:test",rs.getString(2));}}
                var principal=ObjectSelect.query(Principal.class).where(Principal.T_ILI_TID.eq(principalId)).selectOne(runtime.newContext());
                assertEquals(Instant.parse("2026-11-04T12:00:00Z"),UtcTime.fromDatabase(principal.getProvisionedat()));
                var del=command("keyword.remove");UUID keywordId=UUID.randomUUID();uow.execute(actor("anna"),command("keyword.add"),ctx->{var k=ctx.newObject(Datasetkeyword.class);k.setTIliTid(keywordId);k.setKeyword("demo");k.setDataset1(ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(rootId)).selectOne(ctx));return keywordId;});
                uow.execute(actor("ben"),del,ctx->{ctx.deleteObject(ObjectSelect.query(Datasetkeyword.class).where(Datasetkeyword.T_ILI_TID.eq(keywordId)).selectOne(ctx));return keywordId;});
                try(var c=admin.getConnection();var ps=c.prepareStatement("SELECT operation,old_values->>'keyword',new_values IS NULL,aggregate_dataset_oid FROM audit.row_change WHERE table_name='datasetkeyword' AND command_id=?")){ps.setObject(1,del.commandId());try(var rs=ps.executeQuery()){assertTrue(rs.next());assertEquals("D",rs.getString(1));assertEquals("demo",rs.getString(2));assertTrue(rs.getBoolean(3));assertEquals(rootId.toString(),rs.getString(4));}}
                long version;
                try(var c=admin.getConnection();var ps=c.prepareStatement("SELECT row_version FROM di_management.dataset WHERE t_ili_tid=?")){
                    ps.setObject(1,rootId);try(var rs=ps.executeQuery()){assertTrue(rs.next());version=rs.getLong(1);}
                }
                var guard=new AggregateWriteGuard();
                uow.execute(actor("ben"),command("guarded.metadata"),ctx->{
                    guard.lockDataset(ctx,rootId,version);
                    ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(rootId)).selectOne(ctx).setTitle("Guarded update");
                    return rootId;
                });
                var stale=assertThrows(org.apache.cayenne.CayenneRuntimeException.class,()->
                    uow.execute(actor("anna"),command("stale.metadata"),ctx->{
                        guard.lockDataset(ctx,rootId,version);return rootId;
                    }));
                assertInstanceOf(AggregateWriteGuard.StaleAggregateException.class,stale.getCause());
                assertEquals("Guarded update",ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(rootId)).selectOne(runtime.newContext()).getTitle());
            } finally {runtime.shutdown();}
        }
    }
}
