package ch.so.agi.dateninfrastruktur.admin;

import ch.so.agi.dateninfrastruktur.admin.access.*;
import ch.so.agi.dateninfrastruktur.admin.identity.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.delivery.CandidateFiles;
import ch.so.agi.dateninfrastruktur.admin.delivery.DeliveryService;
import ch.so.agi.dateninfrastruktur.admin.persistence.ManagementRuntime;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import ch.so.agi.dateninfrastruktur.admin.authorization.DownloadPathResolver;
import ch.so.agi.dateninfrastruktur.admin.authorization.JdbcAuthorizationRepository;
import ch.so.agi.dateninfrastruktur.admin.model.Principal;
import org.apache.cayenne.query.ObjectSelect;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.cayenne.runtime.CayenneRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public final class DeliveryDomainProbe {
    private static final String ISSUER = "https://id.test.example";
    private static final ActorContext ANNA = actor("anna");
    private static final ActorContext CLAUDIA = actor("claudia");
    private static final ActorContext GRETL = actor("gretl");
    private static final byte[] CSV = "zone,code\nA,1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        assertEquals("true", System.getenv("DI_COMPOSE_TEST_FIXTURE"));
        assertEquals("jdbc:postgresql://postgres:5432/dateninfrastruktur_management", System.getenv("DI_TEST_URL"));
        try (var writer = source("di_writer", "DI_TEST_WRITER_PASSWORD"); var admin = source("postgres", "DI_TEST_ADMIN_PASSWORD")) {
            CayenneRuntime runtime = ManagementRuntime.create(writer);
            try {
                exercise(runtime, writer, admin, Path.of("/runtime/candidates"));
            } finally {
                runtime.shutdown();
            }
        }
    }

    private static void exercise(CayenneRuntime runtime, HikariDataSource writer, HikariDataSource admin, Path root) throws Exception {
        var authority = new AuthorityService(runtime);
        var delivery = new DeliveryService(runtime, authority, new CandidateFiles(root));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CSV));
        var eligibility = delivery.eligible(ANNA, "", 0, 30);
        assertEquals(1, eligibility.size());
        assertTrue(delivery.eligible(CLAUDIA, "", 0, 30).isEmpty());
        var target = eligibility.getFirst();
        DeliveryHttpProbe.exercise(target.datasetId(), target.issueId());
        var submitters = new SubmitterAdministrationService(runtime, authority);
        var scope = new SubmitterAdministrationService.Scope(null, target.datasetId());
        UUID team = ObjectSelect.query(Principal.class).where(Principal.ASUBJECT.eq("team"))
            .selectOne(runtime.newContext()).getTIliTid();
        UUID groupGrant = submitters.grant(ANNA, new SubmitterAdministrationService.Grant(scope, team, null, null, "Group probe"));
        execute(admin, "UPDATE di_management.authorityassignment SET revokedat=clock_timestamp() WHERE capability='submit_data' AND principal NOT IN (SELECT t_id FROM di_management.principal WHERE akind='group')");
        assertEquals(1, delivery.eligible(ANNA, "", 0, 30).size());
        submitters.revoke(ANNA, scope, groupGrant, "Group probe finished");
        assertTrue(delivery.eligible(ANNA, "", 0, 30).isEmpty());
        assertEquals(409, assertThrows(BusinessException.class,
            () -> submitters.revoke(ANNA, scope, groupGrant, "Duplicate revoke")).status());
        execute(admin, "UPDATE di_management.authorityassignment SET revokedat=NULL WHERE capability='submit_data' AND principal NOT IN (SELECT t_id FROM di_management.principal WHERE akind='group')");
        var input = new DeliveryService.Create(target.datasetId(), target.issueId(), "replace", "source.csv", (long) CSV.length, sha);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        assertEquals(403, assertThrows(BusinessException.class, () -> delivery.create(GRETL, first, input)).status());
        assertEquals(403, assertThrows(BusinessException.class, () -> delivery.create(CLAUDIA, first, input)).status());
        assertEquals(404, assertThrows(BusinessException.class, () -> delivery.get(ANNA, UUID.randomUUID())).status());
        assertEquals("pending", delivery.create(ANNA, first, input).status());
        assertEquals("pending", delivery.create(ANNA, first, input).status());
        var altered = new DeliveryService.Create(target.datasetId(), target.issueId(), "append", "source.csv", (long) CSV.length, sha);
        assertEquals(409, assertThrows(BusinessException.class, () -> delivery.create(ANNA, first, altered)).status());
        delivery.create(ANNA, second, input);
        assertEquals(403, assertThrows(BusinessException.class, () -> delivery.start(ANNA, first)).status());
        execute(admin, "UPDATE di_management.authorityassignment SET revokedat=clock_timestamp() WHERE capability='submit_data' AND principal NOT IN (SELECT t_id FROM di_management.principal WHERE akind='group')");
        assertEquals(403, assertThrows(BusinessException.class, () -> delivery.start(GRETL, first)).status());
        execute(admin, "UPDATE di_management.authorityassignment SET revokedat=NULL WHERE capability='submit_data' AND principal NOT IN (SELECT t_id FROM di_management.principal WHERE akind='group')");
        var startedFirst = delivery.start(GRETL, first);
        var startedSecond = delivery.start(GRETL, second);
        assertEquals("processing", startedFirst.status());
        assertEquals(startedFirst.baseVersionId(), startedSecond.baseVersionId());
        assertTrue(startedFirst.schemaModel().contains("UNIQUE zone, code;"));
        Files.writeString(Path.of("/runtime/DI_ExampleCsv.ili"), startedFirst.schemaModel());
        var command = new DeliveryService.Complete(startedFirst.schemaId(), startedFirst.baseVersionId(), "candidate.csv", CSV.length, sha, 1);
        assertEquals(409, assertThrows(BusinessException.class, () -> delivery.complete(GRETL, first, command)).status());
        prepare(root, startedFirst);
        prepare(root, startedSecond);
        execute(admin, "UPDATE di_management.authorityassignment SET revokedat=clock_timestamp() WHERE capability='submit_data' AND principal NOT IN (SELECT t_id FROM di_management.principal WHERE akind='group')");
        assertEquals(403, assertThrows(BusinessException.class, () -> delivery.complete(GRETL, first, command)).status());
        execute(admin, "UPDATE di_management.authorityassignment SET revokedat=NULL WHERE capability='submit_data' AND principal NOT IN (SELECT t_id FROM di_management.principal WHERE akind='group')");

        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> compete(delivery, first, command, barrier));
            var b = pool.submit(() -> compete(delivery, second, command, barrier));
            int resultA = a.get(30, TimeUnit.SECONDS);
            int resultB = b.get(30, TimeUnit.SECONDS);
            assertEquals(609, resultA + resultB);
            assertTrue(resultA == 200 || resultB == 200);
            UUID accepted = resultA == 200 ? first : second;
            UUID other = accepted.equals(first) ? second : first;
            assertEquals("processing", delivery.get(ANNA, other).status());
            var state = delivery.complete(GRETL, accepted, command);
            assertEquals(state, delivery.get(ANNA, accepted));
            var changed = new DeliveryService.Complete(command.schemaId(), command.baseVersionId(), command.fileName(), command.byteSize(), sha, 2);
            assertEquals(409, assertThrows(BusinessException.class, () -> delivery.complete(GRETL, accepted, changed)).status());
            assertEquals("failed", delivery.finish(GRETL, other, "failed", "Concurrent publication").status());
            var repository = new JdbcAuthorizationRepository(writer);
            UUID distribution = UUID.nameUUIDFromBytes(("distribution:" + accepted).getBytes(StandardCharsets.UTF_8));
            var download = new DownloadPathResolver.DownloadTarget(distribution, command.fileName(), "GET");
            assertEquals(204, repository.evaluate(Optional.empty(), download).status());
            var catalog = new CatalogCommandService(runtime, authority);
            var change = ManagementFixture.command("change_access_level");
            catalog.changeAccessLevel(ANNA, change, target.datasetId(), "restricted", "Private fixture");
            assertEquals(target.datasetId(), catalog.changeAccessLevel(ANNA, change, target.datasetId(), "restricted", "Private fixture"));
            assertEquals(409, assertThrows(BusinessException.class,
                () -> catalog.changeAccessLevel(ANNA, change, target.datasetId(), "open", "Private fixture")).status());
            assertEquals(401, repository.evaluate(Optional.empty(), download).status());
            assertEquals(403, repository.evaluate(Optional.of(CLAUDIA), download).status());
            var access = new AccessWorkflowService(runtime, authority);
            UUID claudia = ObjectSelect.query(Principal.class).where(Principal.ASUBJECT.eq("claudia"))
                .selectOne(runtime.newContext()).getTIliTid();
            UUID readGrant = access.grantDirect(ANNA, ManagementFixture.command("direct_grant"), target.datasetId(),
                claudia, new AccessWorkflowService.Validity(null, null), "Download probe");
            assertEquals(204, repository.evaluate(Optional.of(CLAUDIA), download).status());
            access.revoke(ANNA, ManagementFixture.command("revoke"), readGrant, "Download probe finished");
            assertEquals(403, repository.evaluate(Optional.of(CLAUDIA), download).status());
            assertEquals(404, repository.evaluate(Optional.of(CLAUDIA),
                new DownloadPathResolver.DownloadTarget(distribution, "other.csv", "GET")).status());
        }
        try (var conn = admin.getConnection(); var statement = conn.createStatement();
             var rows = statement.executeQuery("""
                SELECT count(*) FROM di_management.submission s
                JOIN di_management.datasetissue i ON i.currentversion=s.acceptedversion
                JOIN di_management.distribution d ON d.version=s.acceptedversion
                WHERE s.astatus='accepted' AND d.filename='candidate.csv'
                """)) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
        new CatalogCommandServiceIT().metadataPreservedAndLastWriteWins();
        new CatalogCommandServiceIT().metadataAuthorityAloneCannotPublishOrChangeAccess();
        new ConcurrentDecisionIT().twoConnectionsCannotCompleteTheSameRequestDifferently();
        new AccessWorkflowIT().requestApproveDenyDirectGrantRevokeAreAtomicAndCorrelated();
        new AccessWorkflowIT().ownRequestOnlyAndGrantFailureRollsBackDecision();
        System.out.println("ACCESS_CATALOG_CASES=5 SKIP=0");
        System.out.println("DELIVERY_DOMAIN_ASSERTIONS=38 SKIP=0");
    }

    private static int compete(DeliveryService service, UUID id, DeliveryService.Complete command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            service.complete(GRETL, id, command);
            return 200;
        } catch (BusinessException error) {
            return error.status();
        }
    }

    private static void prepare(Path root, DeliveryService.State state) throws Exception {
        Path target = root.resolve(state.candidatePrefix()).resolve("candidate.csv");
        Files.createDirectories(target.getParent());
        Files.write(target, CSV);
    }

    private static void execute(HikariDataSource source, String sql) throws Exception {
        try (var conn = source.getConnection(); var statement = conn.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static ActorContext actor(String subject) { return new ActorContext(ISSUER, subject, "user", null); }

    private static HikariDataSource source(String user, String password) {
        var source = new HikariDataSource();
        source.setJdbcUrl(System.getenv("DI_TEST_URL"));
        source.setUsername(user);
        source.setPassword(System.getenv(password));
        source.setMaximumPoolSize(4);
        return source;
    }
}
