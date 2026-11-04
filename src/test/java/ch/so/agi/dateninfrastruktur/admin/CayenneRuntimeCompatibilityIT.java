package ch.so.agi.dateninfrastruktur.admin;

import com.zaxxer.hikari.HikariDataSource;

import org.apache.cayenne.query.SQLExec;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.runtime.CayenneRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import java.sql.Connection;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CayenneRuntimeCompatibilityIT {
    @Test void sameConnectionTransaction() throws Exception {
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));
            pool.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));
            pool.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
            pool.setMaximumPoolSize(1);
            try (Connection c = pool.getConnection(); var s = c.createStatement()) {
                s.execute("CREATE TABLE probe(id bigint primary key, message varchar(255))");
                s.execute("CREATE TABLE actor_binding(actor text, pid int, tx text)");
                s.execute("CREATE TABLE observed(actor text, pid int, tx text)");
                s.execute("CREATE FUNCTION record_actor() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                    "IF nullif(current_setting('di.actor_subject',true),'') IS NULL THEN RAISE EXCEPTION 'actor required'; END IF; " +
                    "INSERT INTO observed VALUES(current_setting('di.actor_subject'),pg_backend_pid(),pg_current_xact_id()::text); RETURN NEW; END $$");
                s.execute("CREATE TRIGGER actor_probe AFTER INSERT ON probe FOR EACH ROW EXECUTE FUNCTION record_actor()");
            }
            var runtime = CayenneRuntime.of().addConfig("cayenne/cayenne-spike.xml").defaultDataNode(pool).build();
            try {
                for (long id=1; id<=2; id++) {
                    final long key=id;
                    String actor = id==1 ? "anna" : "ben";
                    runtime.performInTransaction(() -> {
                        var ctx = runtime.newContext();
                        // SQLExec is transactional in M3, unlike an ordinary SQLSelect.
                        SQLExec.query("SELECT set_config('di.actor_subject', #bind($actor), true)").params("actor",actor).execute(ctx);
                        SQLExec.query("INSERT INTO actor_binding VALUES(current_setting('di.actor_subject'),pg_backend_pid(),pg_current_xact_id()::text)").execute(ctx);
                        var row = ctx.newObject(Probe.class);
                        row.writeProperty("id", key); row.writeProperty("message", "Unicode: Grüezi " + actor);
                        ctx.commitChanges(); return null;
                    });
                }
                assertThrows(RuntimeException.class, () -> runtime.performInTransaction(() -> {
                    var ctx=runtime.newContext();
                    SQLExec.query("SELECT set_config('di.actor_subject', #bind($actor), true)").params("actor","rollback").execute(ctx);
                    var row=ctx.newObject(Probe.class); row.writeProperty("id",3L); row.writeProperty("message","rollback");
                    ctx.commitChanges(); throw new IllegalStateException("after flush");
                }));
                assertThrows(RuntimeException.class, () -> runtime.performInTransaction(() -> {
                    var ctx=runtime.newContext(); var row=ctx.newObject(Probe.class);
                    row.writeProperty("id",4L); row.writeProperty("message","missing actor"); ctx.commitChanges(); return null;
                }));
                var loaded = ObjectSelect.query(Probe.class).select(runtime.newContext());
                assertEquals(2, loaded.size());
                assertTrue(loaded.stream().anyMatch(row -> "Unicode: Grüezi anna".equals(row.readProperty("message"))));
                assertTrue(loaded.stream().anyMatch(row -> "Unicode: Grüezi ben".equals(row.readProperty("message"))));
            } finally { runtime.shutdown(); }
            try (Connection c=pool.getConnection(); var s=c.createStatement()) {
                try(var rs=s.executeQuery("SELECT count(*) FROM probe")){rs.next();assertEquals(2,rs.getInt(1));}
                try(var rs=s.executeQuery("SELECT count(*) FROM observed o JOIN actor_binding b USING(actor,pid,tx)")){rs.next();assertEquals(2,rs.getInt(1));}
                try(var rs=s.executeQuery("SELECT count(DISTINCT actor),count(DISTINCT tx),count(DISTINCT pid) FROM observed")){
                    rs.next();assertEquals(2,rs.getInt(1));assertEquals(2,rs.getInt(2));assertEquals(1,rs.getInt(3));
                }
            }
        }
    }
}
