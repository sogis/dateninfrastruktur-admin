package ch.so.agi.dateninfrastruktur.admin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService.Create;
import ch.so.agi.dateninfrastruktur.admin.catalog.DatasetIssueService;
import ch.so.agi.dateninfrastruktur.admin.model.Authorityassignment;
import ch.so.agi.dateninfrastruktur.admin.model.Dataset;
import ch.so.agi.dateninfrastruktur.admin.model.Datasetissue;
import ch.so.agi.dateninfrastruktur.admin.model.Dataversion;
import ch.so.agi.dateninfrastruktur.admin.model.Principal;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
import ch.so.agi.dateninfrastruktur.admin.web.DatasetIssueController;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.ObjectSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = DatasetIssueWebIT.WebFixture.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "server.address=127.0.0.1",
        "di.web.public-origin=http://management.test",
        "di.web.gateway-secret=synthetic-gateway-credential-for-tests",
        "management.endpoint.health.validate-group-membership=false"
    }
)
class DatasetIssueWebIT {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Import({DatasetCreationWebIT.WebFixture.class, DatasetIssueController.class})
    static class WebFixture {
        @Bean DatasetIssueService issueService(ManagementFixture fixture) {
            return new DatasetIssueService(fixture.runtime, fixture.authority);
        }
    }

    @LocalServerPort int port;
    @MockitoBean(name = "managementJwtDecoder") JwtDecoder jwtDecoder;

    @BeforeEach
    void stubJwtVerification() {
        when(jwtDecoder.decode(anyString())).thenAnswer(call -> {
            String subject = call.getArgument(0);
            return Jwt.withTokenValue(subject).header("alg", "RS256").issuer("https://identity.example.invalid")
                .subject(subject).build();
        });
    }

    @Test
    void seriesIssuesCanBeCreatedEditedAndSelectedWhileVersionsRemainReadOnlyAndScoped() throws Exception {
        try (var fixture = new ManagementFixture(); var foreign = new ManagementFixture();
             var playwright = driver(); var chromium = playwright.chromium().launch();
             var context = chromium.newContext(browserOptions(fixture.manager))) {
            UUID series = series(fixture);
            UUID foreignSeries = series(foreign);
            var foreignService = new DatasetIssueService(foreign.runtime, foreign.authority);
            UUID foreignIssue = foreignService.createIssue(foreign.manager, command("create_issue"), foreignSeries, "foreign-issue", "Fremde Ausgabe");
            seedVersions(foreign, foreignIssue, "foreign");

            String path = issues(series);
            var page = context.newPage();
            assertEquals(200, page.navigate(uri(path).toString()).status());
            assertEquals(1, page.locator("a[href='" + path + "/new']").count());
            assertEquals(0, page.locator("form[method=post]").count());

            var creation = form(page, path + "/new", fixture.manager);
            assertEquals(1, page.locator("form[method=post][action='" + path + "']").count());
            assertRedirect(post(path, fixture.manager, creation, Map.of("key", "2026", "label", "Jahr 2026")), path);
            var created = dataset(fixture, series).getDatasetissues().getFirst();
            UUID issue = created.getTIliTid();
            assertEquals("2026", created.getIssuekey());
            assertEquals("draft", created.getPublicationstatus());
            String item = path + "/" + issue;

            show(page, get(item + "/versions", fixture.manager), 200);
            assertTrue(page.getByRole(AriaRole.MAIN).innerText().contains("noch keine Datenversionen"));
            assertEquals(0, page.locator("form").count());

            var edit = form(page, item + "/edit", fixture.manager);
            assertEquals(0, page.locator("form [name=key]").count());
            assertEquals("Jahr 2026", page.locator("form input[name=label]").inputValue());
            String label = "Ausgabe 2026 <img id=issue-injection>";
            assertRedirect(post(item + "/edit", fixture.manager, edit,
                Map.of("label", label, "note", "Interner Prüfhinweis", "status", "in_review", "key", "forged")), path);
            var updated = issue(fixture, issue);
            assertEquals("2026", updated.getIssuekey());
            assertEquals(label, updated.getLabel());
            assertEquals("Interner Prüfhinweis", updated.getNote());
            assertEquals("in_review", updated.getPublicationstatus());

            var stale = form(page, item + "/edit", fixture.manager);
            var current = form(page, item + "/current", fixture.manager);
            assertNull(dataset(fixture, series).getCurrentissue1());
            assertRedirect(post(item + "/current", fixture.manager, current, Map.of()), path);
            assertEquals(issue, dataset(fixture, series).getCurrentissue1().getTIliTid());

            assertRedirect(post(item + "/edit", fixture.manager, stale,
                Map.of("label", label, "note", "Neueste Eingabe", "status", "in_review")), path);
            assertEquals("Neueste Eingabe", issue(fixture, issue).getNote());

            var forged = new Form(creation.cookie(), creation.csrf(), UUID.randomUUID().toString());
            assertEquals(400, post(path + "/" + foreignIssue + "/edit", fixture.manager, forged,
                Map.of("label", "Fremde Änderung", "note", "", "status", "draft")).statusCode());
            assertEquals(400, post(path + "/" + foreignIssue + "/current", fixture.manager, forged, Map.of()).statusCode());
            assertEquals(issue, dataset(fixture, series).getCurrentissue1().getTIliTid());
            assertEquals("Fremde Ausgabe", issue(foreign, foreignIssue).getLabel());

            show(page, get(path, fixture.manager), 200);
            assertEquals(0, page.locator("#issue-injection").count());
            var issueRow = page.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText(label));
            assertTrue(issueRow.innerText().contains("In Prüfung"));
            assertTrue(issueRow.innerText().contains("Ja"));
            assertEquals(1, issueRow.locator("a[href='" + item + "/versions']").count());
            screenshot(page, "dataset-issues-series.png");

            seedVersions(fixture, issue, "eigene");
            show(page, get(item + "/versions", fixture.manager), 200);
            assertEquals(0, page.locator("form").count());
            var rows = page.getByRole(AriaRole.ROW).allTextContents();
            assertEquals(3, rows.size());
            assertTrue(rows.get(1).contains("eigene-current"));
            assertTrue(rows.get(1).contains("Verfügbar"));
            assertTrue(rows.get(1).contains("Erfolgreich"));
            assertTrue(rows.get(1).contains("Ja"));
            assertTrue(rows.get(2).contains("eigene-old"));
            assertTrue(rows.get(2).contains("Fehlgeschlagen"));
            assertTrue(rows.get(2).contains("Nein"));
            assertEquals(1, page.locator("table time[datetime]").count());
            screenshot(page, "dataset-issue-versions.png");
            assertEquals(403, get(item + "/versions", fixture.ben).statusCode());
            assertEquals(404, get(path + "/" + foreignIssue + "/versions", fixture.manager).statusCode());
            assertEquals(405, post(item + "/versions", fixture.manager, forged, Map.of()).statusCode());
        }
    }

    @Test
    void standaloneIssuesStayReadOnlyAndPublishedSeriesRequiresPublishPermission() throws Exception {
        try (var fixture = new ManagementFixture(); var playwright = driver();
             var chromium = playwright.chromium().launch();
             var context = chromium.newContext(browserOptions(fixture.manager))) {
            UUID series = series(fixture);
            UUID standalone = fixture.draft();
            UUID standardIssue = dataset(fixture, standalone).getDatasetissues().getFirst().getTIliTid();
            String standalonePath = issues(standalone);
            var page = context.newPage();
            assertEquals(200, page.navigate(uri(standalonePath).toString()).status());
            assertEquals(0, page.locator("a[href='" + standalonePath + "/new']").count());
            assertEquals(0, page.locator("a[href$='/edit'], a[href$='/current']").count());
            assertEquals(1, page.locator("a[href='" + standalonePath + "/" + standardIssue + "/versions']").count());

            var csrf = form(page, issues(series) + "/new", fixture.manager);
            var forged = new Form(csrf.cookie(), csrf.csrf(), UUID.randomUUID().toString());
            assertEquals(409, post(standalonePath, fixture.manager, forged,
                Map.of("key", "unexpected", "label", "Zweite Ausgabe")).statusCode());
            assertEquals(409, post(standalonePath + "/" + standardIssue + "/edit", fixture.manager, forged,
                Map.of("label", "Unzulässig", "note", "", "status", "draft")).statusCode());
            assertEquals(409, post(standalonePath + "/" + standardIssue + "/current", fixture.manager, forged,
                Map.of()).statusCode());
            assertEquals(1, dataset(fixture, standalone).getDatasetissues().size());

            var service = new DatasetIssueService(fixture.runtime, fixture.authority);
            UUID issue = service.createIssue(fixture.manager, command("create_issue"), series, "initial", "Erste Ausgabe");
            fixture.catalog.transition(fixture.manager, command("publish"), series, "publish", "Serienmetadaten geprüft");
            grantMetadataOnly(fixture, series);

            String publishedPath = issues(series);
            show(page, get(publishedPath, fixture.anna), 200);
            assertEquals(0, page.locator("a[href$='/issues/new'], a[href$='/edit'], a[href$='/current']").count());
            assertEquals(1, page.locator("a[href='" + publishedPath + "/" + issue + "/versions']").count());
            assertEquals(403, get(publishedPath + "/new", fixture.anna).statusCode());

            var requester = form(page, "/access/requests/new?dataset=" + series, fixture.anna);
            var denied = new Form(requester.cookie(), requester.csrf(), UUID.randomUUID().toString());
            assertEquals(403, post(publishedPath, fixture.anna, denied,
                Map.of("key", "metadata-only", "label", "Unberechtigt")).statusCode());
            assertEquals(1, dataset(fixture, series).getDatasetissues().size());
        }
    }

    private static UUID series(ManagementFixture fixture) {
        return fixture.catalog.createDraft(fixture.manager, command("create_draft"),
            new Create(fixture.office, "series-" + fixture.suffix, "series", ManagementFixture.metadata("Prüfreihe")));
    }

    private static Dataset dataset(ManagementFixture fixture, UUID id) {
        return ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(fixture.runtime.newContext());
    }

    private static Datasetissue issue(ManagementFixture fixture, UUID id) {
        return issue(fixture.runtime.newContext(), id);
    }

    private static Datasetissue issue(ObjectContext context, UUID id) {
        return ObjectSelect.query(Datasetissue.class).where(Datasetissue.T_ILI_TID.eq(id)).selectOne(context);
    }

    private static void seedVersions(ManagementFixture fixture, UUID id, String prefix) {
        fixture.unit.execute(fixture.manager, command("operator.register_fixture"), context -> {
            var parent = issue(context, id);
            addVersion(context, parent, prefix + "-old", 1, "prepared", "fail");
            var current = addVersion(context, parent, prefix + "-current", 2, "available", "success");
            current.setAcceptedat(CayenneSql.now(context));
            parent.setCurrentversion1(current);
            return null;
        });
    }

    private static Dataversion addVersion(ObjectContext context, Datasetissue parent, String key, int sequence,
                                          String availability, String validation) {
        var version = context.newObject(Dataversion.class);
        version.setTIliTid(UUID.randomUUID());
        version.setIssue1(parent);
        version.setVersionkey(key);
        version.setAsequence(sequence);
        version.setAvailability(availability);
        version.setValidationstatus(validation);
        return version;
    }

    private static void grantMetadataOnly(ManagementFixture fixture, UUID id) {
        fixture.unit.execute(fixture.manager, command("operator.bootstrap"), context -> {
            var actor = fixture.anna;
            var principal = context.newObject(Principal.class);
            principal.setTIliTid(UUID.randomUUID());
            principal.setAissuer(actor.issuer());
            principal.setAsubject(actor.subject());
            principal.setAkind("user");
            principal.setAactive(true);
            principal.setProvisionedat(CayenneSql.now(context));

            var assigner = ObjectSelect.query(Principal.class).where(Principal.T_ILI_TID.eq(fixture.managerId))
                .selectOne(context);
            var assignment = context.newObject(Authorityassignment.class);
            assignment.setTIliTid(UUID.randomUUID());
            assignment.setPrincipal1(principal);
            assignment.setDataset1(ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(context));
            assignment.setAssignedby1(assigner);
            assignment.setCapability("manage_metadata");
            assignment.setReason("Testsetup");
            assignment.setValidfrom(CayenneSql.now(context).minusDays(1));
            return null;
        });
    }

    private static Playwright driver() {
        return Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC", "1")));
    }

    private static Browser.NewContextOptions browserOptions(ActorContext actor) {
        return new Browser.NewContextOptions().setViewportSize(1024, 900)
            .setExtraHTTPHeaders(Map.of("Authorization", "Bearer " + actor.subject()));
    }

    private static String issues(UUID id) {
        return "/management/datasets/" + id + "/issues";
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpRequest.Builder request(String path, ActorContext actor) {
        return HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + actor.subject()).header("Accept", "text/html");
    }

    private HttpResponse<String> get(String path, ActorContext actor) throws Exception {
        return HTTP.send(request(path, actor).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private Form form(Page page, String path, ActorContext actor) throws Exception {
        var response = get(path, actor);
        show(page, response, 200);
        String cookie = response.headers().allValues("set-cookie").stream()
            .filter(value -> value.startsWith("XSRF-TOKEN=")).map(value -> value.split(";", 2)[0]).findFirst().orElseThrow();
        return new Form(cookie, page.locator("form input[name=_csrf]").inputValue(),
            page.locator("form input[name=commandId]").inputValue());
    }

    private HttpResponse<String> post(String path, ActorContext actor, Form form, Map<String, String> values) throws Exception {
        String fields = "_csrf=" + encode(form.csrf()) + "&commandId=" + encode(form.commandId())
            + values.entrySet().stream()
                .map(entry -> "&" + entry.getKey() + "=" + encode(entry.getValue())).collect(Collectors.joining());
        var request = request(path, actor).header("Origin", "http://management.test")
            .header("Cookie", form.cookie()).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(fields)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void show(Page page, HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), () -> response.uri() + " returned an unexpected status");
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("text/html"));
        page.setContent(response.body());
    }

    private static void assertRedirect(HttpResponse<String> response, String path) {
        assertEquals(303, response.statusCode(), () -> response.uri() + " returned an unexpected status");
        assertEquals(path, response.uri().resolve(response.headers().firstValue("location").orElseThrow()).getPath());
    }

    private static void screenshot(Page page, String filename) {
        String directory = System.getenv("DI_BROWSER_SCREENSHOT_DIR");
        if (directory != null) {
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(directory, filename)));
        }
    }

    private record Form(String cookie, String csrf, String commandId) {}
}
