package ch.so.agi.dateninfrastruktur.admin;

import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.ManagementBaskets;
import ch.so.agi.dateninfrastruktur.admin.persistence.ManagementConfiguration;
import ch.so.agi.dateninfrastruktur.admin.persistence.ManagementSchemaHealth;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.runtime.CayenneRuntime;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static ch.so.agi.dateninfrastruktur.admin.persistence.ManagementBaskets.Topic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executed only by the owned Compose mapping fixture. */
public final class CayenneMappingProbe {
    private static final String OFFICE = "ch.so.test.cayenne." + "long-office-identity-".repeat(14);
    private static final UUID PRINCIPAL = UUID.fromString("31a3ed65-194e-42a6-994f-4d137ea113a1");
    private static final UUID DATASET = UUID.fromString("43d011ed-0615-4678-ad95-a2bb3ceec8ca");
    private static final UUID ISSUE = UUID.fromString("e76580b3-7f50-4e7f-b582-28499da3df45");
    private static final UUID SUBMISSION = UUID.fromString("959788e9-0d55-4036-ab1f-830ddde77be8");
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-12T09:00:00");
    private static final LocalDate TODAY = NOW.toLocalDate();

    public static void main(String[] args) {
        assertEquals("true", System.getenv("DI_COMPOSE_TEST_FIXTURE"));
        var url = System.getenv("DI_TEST_URL");
        assertEquals("jdbc:postgresql://postgres:5432/dateninfrastruktur_management", url);
        try (var spring = new AnnotationConfigApplicationContext()) {
            spring.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "di.management.writer.jdbc-url", url,
                "di.management.writer.username", "di_writer",
                "di.management.writer.password", System.getenv("DI_TEST_WRITER_PASSWORD"))));
            ConfigurationPropertiesBindingPostProcessor.register(spring);
            spring.register(ManagementConfiguration.class);
            spring.refresh();
            var runtime = spring.getBean(CayenneRuntime.class);
            assertEquals("UP", spring.getBean(ManagementSchemaHealth.class).health().getStatus().getCode());
            if ("missing-basket".equals(args[0])) {
                RuntimeException error = assertThrows(RuntimeException.class, () -> runtime.performInTransaction(() -> {
                    insertPrincipal(runtime, "missing");
                    return null;
                }));
                assertTrue(hasCause(error, "Expected exactly one management basket"), error::toString);
                assertEquals(0, ObjectSelect.query(Principal.class).selectCount(runtime.newContext()));
                System.out.println("CAYENNE_MISSING_BASKET=PASS");
            } else {
                assertEquals("insert", args[0]);
                assertBootstrap(runtime);
                runtime.performInTransaction(() -> {
                    insert(runtime);
                    return null;
                });
                assertInsert(runtime);
                RuntimeException error = assertThrows(RuntimeException.class, () -> runtime.performInTransaction(() -> {
                    insertPrincipal(runtime, "duplicate");
                    return null;
                }));
                assertTrue(hasCause(error, "duplicate key"), error::toString);
                assertEquals(0, ObjectSelect.query(Principal.class).where(Principal.ASUBJECT.eq("duplicate"))
                    .selectCount(runtime.newContext()));
                System.out.println("CAYENNE_INSERTS=PASS");
            }
        }
        try (var untrusted = new HikariDataSource()) {
            untrusted.setJdbcUrl(url);
            untrusted.setUsername("postgres");
            untrusted.setPassword(System.getenv("DI_TEST_ADMIN_PASSWORD"));
            assertEquals("DOWN", new ManagementSchemaHealth(untrusted).health().getStatus().getCode());
        }
        System.out.println("CAYENNE_WRITER_BOUNDARY=PASS");
    }

    private static boolean hasCause(Throwable error, String part) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(part)) return true;
        }
        return false;
    }

    private static void assertBootstrap(CayenneRuntime runtime) {
        var context = runtime.newContext();
        var dataset = ObjectSelect.query(Dataset.class).where(Dataset.IDENTIFIER.eq("test-open")).selectOne(context);
        assertNotNull(dataset);
        assertTrue(dataset.getOwneroffice1().getTIliTid().length() > 255);
        assertInstanceOf(UUID.class, dataset.getTIliTid());
        assertEquals(2, dataset.getThemeentries().size());
        assertEquals(2, dataset.getKeywordentries().size());
        assertNotNull(dataset.primaryContact());
        var anna = ObjectSelect.query(Principal.class).where(Principal.ASUBJECT.eq("anna")).selectOne(context);
        assertInstanceOf(UUID.class, anna.getTIliTid());
    }

    private static void insert(CayenneRuntime runtime) {
        var context = runtime.newContext();
        long catalog = ManagementBaskets.require(context, Catalog);
        var office = context.newObject(Office.class);
        office.setTIliTid(OFFICE);
        office.setTBasket(ManagementBaskets.require(context, Organization));
        office.setIdentifier("cayenne-office");
        office.setAname("Synthetisches Cayenne-Testamt");
        office.setAbbreviation("CAY");
        office.setAactive(true);

        var principal = context.newObject(Principal.class);
        setPrincipal(principal, ManagementBaskets.require(context, Access), "cayenne-user");

        var dataset = context.newObject(Dataset.class);
        dataset.setTIliTid(DATASET);
        dataset.setTBasket(catalog);
        dataset.setIdentifier("cayenne-dataset");
        dataset.setTitle("Synthetischer Cayenne-Datensatz");
        dataset.setAdescription("Roundtrip");
        dataset.setAkind("standalone");
        dataset.setAccesslevel("restricted");
        dataset.setPublicationstatus("draft");
        dataset.setModified(TODAY);
        dataset.setOwneroffice1(office);
        dataset.setPublisheroffice1(office);

        var issue = context.newObject(Datasetissue.class);
        issue.setTIliTid(ISSUE);
        issue.setTBasket(catalog);
        issue.setIssuekey("default");
        issue.setLabel("Standardausgabe");
        issue.setPublicationstatus("draft");
        issue.setModified(TODAY);
        issue.setDataset1(dataset);

        var submission = context.newObject(Submission.class);
        submission.setTIliTid(SUBMISSION);
        submission.setTBasket(ManagementBaskets.require(context, Delivery));
        submission.setCreatedat(NOW);
        submission.setAstatus("pending");
        submission.setAmode("replace");
        submission.setSubmittedby1(principal);
        submission.setDataset1(dataset);
        submission.setIssue1(issue);
        context.commitChanges();
    }

    private static void insertPrincipal(CayenneRuntime runtime, String subject) {
        var context = runtime.newContext();
        var basket = ManagementBaskets.require(context, Access);
        setPrincipal(context.newObject(Principal.class), basket, subject);
        context.commitChanges();
    }

    private static void setPrincipal(Principal principal, long basket, String subject) {
        principal.setTIliTid(PRINCIPAL);
        principal.setTBasket(basket);
        principal.setAissuer("https://id.test.example");
        principal.setAsubject(subject);
        principal.setAkind("user");
        principal.setAactive(true);
        principal.setProvisionedat(NOW);
    }

    private static void assertInsert(CayenneRuntime runtime) {
        var context = runtime.newContext();
        var submission = ObjectSelect.query(Submission.class).where(Submission.T_ILI_TID.eq(SUBMISSION)).selectOne(context);
        assertNotNull(submission);
        assertEquals(ISSUE, submission.getIssue1().getTIliTid());
        assertEquals(DATASET, submission.getDataset1().getTIliTid());
        assertEquals(PRINCIPAL, submission.getSubmittedby1().getTIliTid());
        assertEquals(OFFICE, submission.getDataset1().getOwneroffice1().getTIliTid());
        assertEquals(ManagementBaskets.require(context, Delivery), submission.getTBasket());
        assertEquals(ManagementBaskets.require(context, Catalog), submission.getDataset1().getTBasket());
        assertEquals(ManagementBaskets.require(context, Access), submission.getSubmittedby1().getTBasket());
        assertEquals(ManagementBaskets.require(context, Organization), submission.getDataset1().getOwneroffice1().getTBasket());
    }
}
