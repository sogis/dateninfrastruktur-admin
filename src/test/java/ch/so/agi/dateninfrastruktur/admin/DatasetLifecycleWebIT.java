package ch.so.agi.dateninfrastruktur.admin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.model.Authorityassignment;
import ch.so.agi.dateninfrastruktur.admin.model.Dataset;
import ch.so.agi.dateninfrastruktur.admin.model.Principal;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.apache.cayenne.query.ObjectSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = DatasetCreationWebIT.WebFixture.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "server.address=127.0.0.1",
        "di.web.public-origin=http://management.test",
        "di.web.gateway-secret=synthetic-gateway-credential-for-tests",
        "management.endpoint.health.validate-group-membership=false"
    }
)
class DatasetLifecycleWebIT {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

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
    void publishedEditRequiresBothRightsAndAStaleFormCannotOverwriteTheSavedValues() throws Exception {
        try (var fixture = new ManagementFixture(); var playwright = chromiumDriver();
             var chromium = playwright.chromium().launch();
             var context = chromium.newContext(browserOptions(fixture.manager))) {
            UUID id = fixture.published();
            preparePublishedEdit(fixture, id);
            String path = path(id);
            var page = context.newPage();
            assertEquals(200, page.navigate(uri(path).toString()).status());
            show(page, get(path + "?search=50%25&page=2", fixture.manager), 200);
            assertEquals("/management/datasets?search=50%25&page=2",
                page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Zur Datensatzliste")).getAttribute("href"));

            for (var limited : List.of(fixture.anna, fixture.ben)) {
                show(page, get(path, limited), 200);
                assertEquals(0, page.locator("a[href='" + path + "/edit']").count());
                assertEquals(403, get(path + "/edit", limited).statusCode());
                var direct = loadForm(page, "/access/requests/new?dataset=" + id, limited, List.of("commandId"));
                direct.fields().put("expectedVersion", Long.toString(fixture.version("dataset", id)));
                direct.fields().put("published", "true");
                fillMetadata(direct, "Unzulässige Änderung");
                show(page, post(path + "/edit", limited, direct, List.of()), 403);
                assertEquals("Testdatensatz", dataset(fixture, id).getTitle());
            }

            var stale = loadForm(page, path + "/edit", fixture.manager,
                List.of("commandId", "expectedVersion", "published"));
            assertEquals("true", stale.fields().get("published"));
            assertEquals("test@example.invalid", page.locator("input[name=contactEmail]").inputValue());
            for (String field : List.of("identifier", "kind", "ownerOffice", "publisherOffice")) {
                assertEquals(0, page.locator("form [name='" + field + "']").count());
            }
            String previousIdentifier = dataset(fixture, id).getIdentifier();

            var update = loadForm(page, path + "/edit", fixture.manager,
                List.of("commandId", "expectedVersion", "published"));
            fillMetadata(update, "Speicherstand");
            update.fields().put("identifier", "unzulassige-kennung");
            update.fields().put("kind", "series");
            update.fields().put("ownerOffice", UUID.randomUUID().toString());
            assertRedirect(post(path + "/edit", fixture.manager, update, List.of("Energie", "Raum_und_Umwelt")), path);
            long savedVersion = fixture.version("dataset", id);
            assertRedirect(post(path + "/edit", fixture.manager, update, List.of("Energie", "Raum_und_Umwelt")), path);
            assertEquals(savedVersion, fixture.version("dataset", id));

            String lostTitle = "Nicht übernehmen <img id=stale-injection>";
            fillMetadata(stale, lostTitle);
            show(page, post(path + "/edit", fixture.manager, stale, List.of("Finanzen")), 409);
            assertEquals(lostTitle, page.locator("input[name=title]").inputValue());
            assertEquals("Messreihe\nZweite Zeile", page.locator("textarea[name=keywords]").inputValue());
            assertTrue(page.locator("input[name=themes][value=Finanzen]").isChecked());
            assertTrue(page.getByText("Aktuellen Datensatz neu laden").isVisible());
            assertEquals(0, page.locator("#stale-injection").count());
            screenshot(page, "dataset-edit-conflict.png");
            assertEquals(savedVersion, fixture.version("dataset", id));

            var saved = dataset(fixture, id);
            assertEquals("Speicherstand", saved.getTitle());
            assertEquals("mailto:pflege@example.invalid", saved.getContactemail());
            assertEquals("Importierter Wert", saved.getSurveymethod());
            assertEquals(previousIdentifier, saved.getIdentifier());
            assertEquals("standalone", saved.getAkind());
            assertEquals(fixture.office, saved.getOwneroffice1().getTIliTid());
            assertEquals(fixture.office, saved.getPublisheroffice1().getTIliTid());
            assertEquals("published", saved.getPublicationstatus());
            assertEquals("restricted", saved.getAccesslevel());
            assertEquals(List.of("Energie", "Raum_und_Umwelt"),
                saved.getDatasetthemes().stream().map(theme -> theme.getTheme()).sorted().toList());
            assertEquals(List.of("Messreihe", "Zweite Zeile"),
                saved.getDatasetkeywords().stream().map(word -> word.getKeyword()).sorted().toList());
        }
    }

    @Test
    void explicitConfirmationsKeepPublicationAndAccessSeparate() throws Exception {
        try (var fixture = new ManagementFixture(); var playwright = chromiumDriver();
             var chromium = playwright.chromium().launch();
             var context = chromium.newContext(browserOptions(fixture.manager))) {
            UUID id = fixture.draft();
            String path = path(id);
            var page = context.newPage();
            assertEquals(200, page.navigate(uri(path).toString()).status());
            assertReadOnlyDetail(page, "Entwurf", "Eingeschränkt");
            assertEquals(1, page.locator("a[href='" + path + "/submit']").count());

            confirm(page, fixture, id, "submit", "Entwurf zur Prüfung", null);
            assertEquals("in_review", dataset(fixture, id).getPublicationstatus());
            assertEquals("restricted", dataset(fixture, id).getAccesslevel());
            assertEquals(200, get(path + "/edit", fixture.manager).statusCode());

            confirm(page, fixture, id, "publish", "Fachlich geprüft", null);
            assertEquals("published", dataset(fixture, id).getPublicationstatus());
            assertEquals("restricted", dataset(fixture, id).getAccesslevel());
            show(page, get(path, fixture.manager), 200);
            assertReadOnlyDetail(page, "Publiziert", "Eingeschränkt");

            confirm(page, fixture, id, "access", "Öffentlich freigegeben", "open");
            assertEquals("published", dataset(fixture, id).getPublicationstatus());
            assertEquals("open", dataset(fixture, id).getAccesslevel());
            show(page, get(path, fixture.manager), 200);
            assertReadOnlyDetail(page, "Publiziert", "Frei zugänglich");
            screenshot(page, "dataset-detail-published.png");

            confirm(page, fixture, id, "archive", "Angebot abgeschlossen", null);
            assertEquals("archived", dataset(fixture, id).getPublicationstatus());
            assertEquals("open", dataset(fixture, id).getAccesslevel());
            assertEquals(3, fixture.count("""
                SELECT count(*) FROM di_management.catalogaction a
                JOIN di_management.dataset d ON d.t_id=a.dataset WHERE d.t_ili_tid=?
                """, id));
        }
    }

    private void confirm(Page page, ManagementFixture fixture, UUID id, String action, String reason, String level)
        throws Exception {
        String detail = path(id);
        String path = detail + "/" + action;
        long version = fixture.version("dataset", id);
        var names = level == null ? List.of("commandId", "expectedVersion") : List.of("commandId", "expectedVersion", "level");
        var form = loadForm(page, path, fixture.manager, names);
        assertEquals(1, page.locator("form[method=post][action='" + path + "']").count());
        assertTrue(page.getByLabel("Begründung").isVisible());
        assertEquals(Long.toString(version), form.fields().get("expectedVersion"));
        assertEquals(version, fixture.version("dataset", id));
        if (level != null) {
            assertEquals(level, form.fields().get("level"));
        }
        if (action.equals("publish")) {
            show(page, post(path, fixture.manager, form, List.of()), 400);
            assertEquals(version, fixture.version("dataset", id));
        }
        form.fields().put("reason", reason);
        assertRedirect(post(path, fixture.manager, form, List.of()), detail);
        assertTrue(fixture.version("dataset", id) > version);
    }

    private static void preparePublishedEdit(ManagementFixture fixture, UUID id) {
        fixture.unit.execute(fixture.manager, command("operator.bootstrap"), context -> {
            var root = ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(context);
            root.setSurveymethod("Importierter Wert");
            var assigner = ObjectSelect.query(Principal.class).where(Principal.T_ILI_TID.eq(fixture.managerId))
                .selectOne(context);
            for (var entry : Map.of(fixture.anna, "manage_metadata", fixture.ben, "publish").entrySet()) {
                var principal = context.newObject(Principal.class);
                principal.setTIliTid(UUID.randomUUID());
                principal.setAissuer(entry.getKey().issuer());
                principal.setAsubject(entry.getKey().subject());
                principal.setAkind("user");
                principal.setAactive(true);
                principal.setProvisionedat(CayenneSql.now(context));

                var assignment = context.newObject(Authorityassignment.class);
                assignment.setTIliTid(UUID.randomUUID());
                assignment.setPrincipal1(principal);
                assignment.setDataset1(root);
                assignment.setAssignedby1(assigner);
                assignment.setCapability(entry.getValue());
                assignment.setReason("Testsetup");
                assignment.setValidfrom(CayenneSql.now(context).minusDays(1));
            }
            return null;
        });
    }

    private static Dataset dataset(ManagementFixture fixture, UUID id) {
        return ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(fixture.runtime.newContext());
    }

    private static void fillMetadata(Form form, String title) {
        form.fields().putAll(Map.of(
            "title", title,
            "description", "Beschreibung für die Prüfung",
            "contactName", "Kontakt",
            "contactEmail", " mailto:pflege@example.invalid ",
            "keywords", "Messreihe\nZweite Zeile"
        ));
    }

    private static void assertReadOnlyDetail(Page page, String publicationStatus, String accessLevel) {
        assertEquals(0, page.locator("form").count());
        var main = page.getByRole(AriaRole.MAIN);
        assertTrue(main.getByText("Publikationsstatus", new Locator.GetByTextOptions().setExact(true)).isVisible());
        assertTrue(main.getByText(publicationStatus, new Locator.GetByTextOptions().setExact(true)).isVisible());
        assertTrue(main.getByText("Datenzugang", new Locator.GetByTextOptions().setExact(true)).isVisible());
        assertTrue(main.getByText(accessLevel, new Locator.GetByTextOptions().setExact(true)).isVisible());
    }

    private static void screenshot(Page page, String filename) {
        String directory = System.getenv("DI_BROWSER_SCREENSHOT_DIR");
        if (directory != null) {
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(directory, filename)));
        }
    }

    private static Playwright chromiumDriver() {
        return Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC", "1")));
    }

    private static Browser.NewContextOptions browserOptions(ActorContext actor) {
        return new Browser.NewContextOptions().setViewportSize(1024, 900)
            .setExtraHTTPHeaders(Map.of("Authorization", "Bearer " + actor.subject()));
    }

    private static String path(UUID id) {
        return "/management/datasets/" + id;
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpRequest.Builder request(String path, ActorContext actor) {
        return HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + actor.subject())
            .header("Accept", "text/html");
    }

    private HttpResponse<String> get(String path, ActorContext actor) throws Exception {
        return HTTP.send(request(path, actor).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private Form loadForm(Page page, String path, ActorContext actor, List<String> names) throws Exception {
        var response = get(path, actor);
        show(page, response, 200);
        String cookie = response.headers().allValues("set-cookie").stream()
            .filter(value -> value.startsWith("XSRF-TOKEN="))
            .map(value -> value.split(";", 2)[0])
            .findFirst().orElseThrow();
        var fields = new LinkedHashMap<String, String>();
        fields.put("_csrf", page.locator("form input[name=_csrf]").inputValue());
        for (String name : names) {
            fields.put(name, page.locator("form [name='" + name + "']").inputValue());
        }
        return new Form(cookie, fields);
    }

    private HttpResponse<String> post(String path, ActorContext actor, Form form, List<String> themes) throws Exception {
        var fields = new ArrayList<String>();
        form.fields().forEach((name, value) -> fields.add(name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        themes.forEach(theme -> fields.add("themes=" + URLEncoder.encode(theme, StandardCharsets.UTF_8)));
        var request = request(path, actor).header("Origin", "http://management.test")
            .header("Cookie", form.cookie()).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(String.join("&", fields))).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void show(Page page, HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), () -> response.uri() + " returned an unexpected status");
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("text/html"));
        page.setContent(response.body());
    }

    private static void assertRedirect(HttpResponse<String> response, String path) {
        assertEquals(303, response.statusCode(), () -> response.uri() + " returned an unexpected status");
        var redirect = response.uri().resolve(response.headers().firstValue("location").orElseThrow());
        assertEquals(path, redirect.getPath());
    }

    private record Form(String cookie, Map<String, String> fields) {}
}
