package ch.so.agi.dateninfrastruktur.admin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.so.agi.dateninfrastruktur.admin.access.AccessWorkflowService;
import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService;
import ch.so.agi.dateninfrastruktur.admin.identity.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import ch.so.agi.dateninfrastruktur.admin.model.Authorityassignment;
import ch.so.agi.dateninfrastruktur.admin.model.Office;
import ch.so.agi.dateninfrastruktur.admin.model.Principal;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
import ch.so.agi.dateninfrastruktur.admin.security.ManagementSecurityConfiguration;
import ch.so.agi.dateninfrastruktur.admin.web.DatasetController;
import ch.so.agi.dateninfrastruktur.admin.web.DatasetLifecycleController;
import ch.so.agi.dateninfrastruktur.admin.web.ManagementController;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.apache.cayenne.query.ObjectSelect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.ControllerAdvice;

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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasetCreationWebIT {
    private static final String ORIGIN = "http://management.test";
    private static final String DATASETS = "/management/datasets";
    private static final String NEW_DATASET = DATASETS + "/new";
    private static final String FORM = "form[method=post][action='" + DATASETS + "']";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({ManagementSecurityConfiguration.class, DatasetController.class, DatasetLifecycleController.class, ManagementController.class})
    @ComponentScan(
        basePackageClasses = DatasetController.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = ControllerAdvice.class)
    )
    static class WebFixture {
        @Bean(destroyMethod = "close")
        ManagementFixture managementFixture() {
            return new ManagementFixture();
        }

        @Bean ManagementQueryService managementQuery(ManagementFixture fixture) {
            return new ManagementQueryService(fixture.runtime, fixture.authority);
        }

        @Bean AuthorityService authority(ManagementFixture fixture) {
            return fixture.authority;
        }

        @Bean CatalogCommandService catalog(ManagementFixture fixture) {
            return fixture.catalog;
        }

        @Bean AccessWorkflowService workflow(ManagementFixture fixture) {
            return fixture.workflow;
        }
    }

    @LocalServerPort int port;
    @Autowired ManagementFixture fixture;
    @MockitoBean(name = "managementJwtDecoder") JwtDecoder jwtDecoder;
    private Playwright playwright;
    private Browser chromium;

    @BeforeAll
    void launchChromium() {
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC", "1")));
        chromium = playwright.chromium().launch();
    }

    @AfterAll
    void closeChromium() {
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void stubJwtVerification() {
        when(jwtDecoder.decode(anyString())).thenAnswer(call -> {
            String subject = call.getArgument(0);
            return Jwt.withTokenValue(subject).header("alg", "RS256").issuer(fixture.manager.issuer())
                .subject(subject).build();
        });
    }

    @Test
    void oneCreateFormDistinguishesEligibleOfficesAndKeepsInvalidValues() throws Exception {
        try (var noAuthority = chromium.newContext(browserOptions(fixture.anna))) {
            var page = noAuthority.newPage();
            assertEquals(200, page.navigate(uri(NEW_DATASET).toString()).status());
            assertEquals(0, page.locator(FORM).count());
            assertTrue(page.getByRole(AriaRole.MAIN).innerText().contains("für kein Amt"));
        }

        try (var context = chromium.newContext(browserOptions(fixture.manager))) {
            var page = context.newPage();
            assertEquals(200, page.navigate(uri(NEW_DATASET).toString()).status());
            var form = page.locator(FORM);
            assertEquals(1, form.count());
            assertEquals(0, form.locator("select[name=ownerOffice]").count());
            assertEquals(fixture.office.toString(), form.locator("input[type=hidden][name=ownerOffice]").inputValue());
            assertTrue(form.innerText().contains("Prüfamt — " + fixture.suffix.substring(0, 8)
                + " (office-" + fixture.suffix + ")"));

            String otherOffice = grantSecondOfficeWithSameName();
            assertEquals(200, page.reload().status());
            assertEquals(1, form.count());
            assertEquals(0, form.locator("input[name=ownerOffice]").count());
            var office = form.locator("select[name=ownerOffice]");
            assertEquals("", office.inputValue());
            assertEquals(3, office.locator("option").count());
            assertEquals("Prüfamt — " + fixture.suffix.substring(0, 8) + " (office-" + fixture.suffix + ")",
                office.locator("option[value='" + fixture.office + "']").innerText());
            assertEquals("Prüfamt — T" + fixture.suffix.substring(0, 7) + " (office-second-" + fixture.suffix + ")",
                office.locator("option[value='" + otherOffice + "']").innerText());
            assertEquals(0, form.locator("input[name=themes]:checked").count());
            screenshot(page);

            String csrfCookie = fetchPostForm(page, fixture.manager);
            String identifier = "invalid-" + fixture.suffix;
            String title = "Prüftitel \"><img id=unsafe src=x>";
            var submitted = payload(page, otherOffice, identifier, "series");
            submitted.put("title", title);
            submitted.put("contactEmail", "keine-adresse");
            submitted.put("keywords", "erstes Wort\nzweites Wort");
            var invalid = post(csrfCookie, fixture.manager, submitted, List.of("Energie", "Raum_und_Umwelt"));
            show(page, invalid, 400);
            assertEquals(1, form.count());
            assertEquals(otherOffice.toString(), office.inputValue());
            assertEquals(identifier, form.locator("input[name=identifier]").inputValue());
            assertEquals("series", form.locator("select[name=kind]").inputValue());
            assertEquals(title, form.locator("input[name=title]").inputValue());
            assertEquals("keine-adresse", form.locator("input[name=contactEmail]").inputValue());
            assertEquals("erstes Wort\nzweites Wort", form.locator("textarea[name=keywords]").inputValue());
            assertEquals(2, form.locator("input[name=themes]:checked").count());
            assertTrue(form.locator("input[value='Energie']").isChecked());
            assertTrue(form.locator("input[value='Raum_und_Umwelt']").isChecked());
            assertEquals("true", form.locator("input[name=contactEmail]").getAttribute("aria-invalid"));
            assertTrue(form.locator("#contactEmail-error").isVisible());
            assertEquals(submitted.get("commandId"), form.locator("input[name=commandId]").inputValue());
            assertEquals(0, page.locator("#unsafe").count());
            assertEquals(0, countDatasets(identifier));
        }
    }

    @Test
    void createRejectsAForeignOfficeAndCommitsStandaloneAndSeriesViaRedirect() throws Exception {
        try (var owned = new ManagementFixture(); var foreign = new ManagementFixture();
             var context = chromium.newContext(browserOptions(owned.manager))) {
            var page = context.newPage();
            assertEquals(200, page.navigate(uri(NEW_DATASET).toString()).status());
            String csrfCookie = fetchPostForm(page, owned.manager);
            String identifier = "standalone-" + owned.suffix;
            var forbidden = payload(page, foreign.office, identifier, "standalone");
            var rejected = post(csrfCookie, owned.manager, forbidden, List.of());
            show(page, rejected, 403);
            assertEquals(identifier, page.locator(FORM + " input[name=identifier]").inputValue());
            assertFalse(page.content().contains(foreign.office.toString()));
            assertEquals(0, countDatasets(identifier));

            assertEquals(200, page.reload().status());
            csrfCookie = fetchPostForm(page, owned.manager);
            var standalone = payload(page, owned.office, identifier, "standalone");
            standalone.put("contactEmail", " mailto:kontakt@example.invalid ");
            URI created = redirect(post(csrfCookie, owned.manager, standalone, List.of()));
            UUID id = UUID.fromString(created.getPath().substring(DATASETS.length() + 1));
            assertEquals(created, redirect(post(csrfCookie, owned.manager, standalone, List.of())));
            assertEquals(1, countDatasets(identifier));
            assertEquals(new Created(owned.office, owned.office, "standalone", "draft", "restricted",
                "mailto:kontakt@example.invalid", 1, "default"), dataset(id));
            assertEquals(200, page.navigate(created.toString()).status());
            assertTrue(page.getByRole(AriaRole.MAIN).innerText().contains("Der Datensatz wurde als Entwurf erstellt."));

            assertEquals(200, page.navigate(uri(NEW_DATASET).toString()).status());
            csrfCookie = fetchPostForm(page, owned.manager);
            var duplicate = payload(page, owned.office, identifier, "standalone");
            show(page, post(csrfCookie, owned.manager, duplicate, List.of()), 409);
            assertEquals("true", page.locator(FORM + " input[name=identifier]").getAttribute("aria-invalid"));
            assertEquals(1, countDatasets(identifier));

            assertEquals(200, page.reload().status());
            csrfCookie = fetchPostForm(page, owned.manager);
            String seriesIdentifier = "series-" + owned.suffix;
            var series = payload(page, owned.office, seriesIdentifier, "series");
            URI seriesLocation = redirect(post(csrfCookie, owned.manager, series, List.of("Energie")));
            UUID seriesId = UUID.fromString(seriesLocation.getPath().substring(DATASETS.length() + 1));
            assertEquals(new Created(owned.office, owned.office, "series", "draft", "restricted",
                "mailto:kontakt@example.invalid", 0, null), dataset(seriesId));
            assertEquals(1, countDatasets(seriesIdentifier));
        }
    }

    private String grantSecondOfficeWithSameName() {
        return fixture.unit.execute(fixture.manager, command("operator.bootstrap"), context -> {
            var principal = ObjectSelect.query(Principal.class).where(Principal.T_ILI_TID.eq(fixture.managerId))
                .selectOne(context);
            var office = context.newObject(Office.class);
            office.setTIliTid("office-" + UUID.randomUUID());
            office.setIdentifier("office-second-" + fixture.suffix);
            office.setAbbreviation("T" + fixture.suffix.substring(0, 7));
            office.setAname("Prüfamt");
            office.setAactive(true);
            var assignment = context.newObject(Authorityassignment.class);
            assignment.setTIliTid(UUID.randomUUID());
            assignment.setPrincipal1(principal);
            assignment.setOffice1(office);
            assignment.setAssignedby1(principal);
            assignment.setCapability("manage_metadata");
            assignment.setReason("Testsetup");
            assignment.setValidfrom(CayenneSql.now(context).minusDays(1));
            return office.getTIliTid();
        });
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static Browser.NewContextOptions browserOptions(ActorContext actor) {
        return new Browser.NewContextOptions().setViewportSize(960, 900)
            .setExtraHTTPHeaders(Map.of("Authorization", "Bearer " + actor.subject()));
    }

    private static Map<String, String> payload(Page page, String office, String identifier, String kind) {
        var form = page.locator(FORM);
        return new LinkedHashMap<>(Map.of(
            "commandId", form.locator("input[name=commandId]").inputValue(),
            "_csrf", form.locator("input[name=_csrf]").inputValue(),
            "ownerOffice", office.toString(),
            "identifier", identifier,
            "kind", kind,
            "title", "Testdatensatz",
            "description", "Beschreibung der Prüfdaten",
            "contactName", "",
            "contactEmail", "kontakt@example.invalid",
            "keywords", ""
        ));
    }

    private String fetchPostForm(Page page, ActorContext actor) throws Exception {
        var request = HttpRequest.newBuilder(uri(NEW_DATASET))
            .header("Authorization", "Bearer " + actor.subject())
            .header("Accept", "text/html")
            .GET();
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        show(page, response, 200);
        return response.headers().allValues("set-cookie").stream()
            .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
            .map(cookie -> cookie.split(";", 2)[0])
            .findFirst().orElseThrow(() -> new AssertionError("Create form GET did not issue the CSRF cookie"));
    }

    private HttpResponse<String> post(String csrfCookie, ActorContext actor, Map<String, String> fields, List<String> themes)
        throws Exception {
        var parameters = new ArrayList<String>();
        fields.forEach((name, value) -> parameters.add(name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        themes.forEach(theme -> parameters.add("themes=" + URLEncoder.encode(theme, StandardCharsets.UTF_8)));
        var request = HttpRequest.newBuilder(uri(DATASETS))
            .header("Origin", ORIGIN)
            .header("Authorization", "Bearer " + actor.subject())
            .header("Cookie", csrfCookie)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "text/html")
            .POST(HttpRequest.BodyPublishers.ofString(String.join("&", parameters)));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void show(Page page, HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("text/html"));
        page.setContent(response.body());
    }

    private static URI redirect(HttpResponse<String> response) {
        assertEquals(303, response.statusCode());
        var destination = response.uri().resolve(response.headers().firstValue("location").orElseThrow());
        assertEquals("created=true", destination.getQuery());
        assertTrue(destination.getPath().startsWith(DATASETS + "/"));
        return destination;
    }

    private int countDatasets(String identifier) throws SQLException {
        try (var connection = fixture.admin.getConnection();
             var query = connection.prepareStatement("SELECT count(*) FROM di_management.dataset WHERE identifier=?")) {
            query.setString(1, identifier);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private Created dataset(UUID id) throws SQLException {
        try (var connection = fixture.admin.getConnection();
             var query = connection.prepareStatement("""
                 SELECT own.t_ili_tid AS owner, pub.t_ili_tid AS publisher, d.akind, d.publicationstatus, d.accesslevel,
                        d.contactemail, i.issuekey,
                        (SELECT count(*) FROM di_management.datasetissue issue WHERE issue.dataset=d.t_id) AS issues
                 FROM di_management.dataset d
                 JOIN di_management.office own ON own.t_id=d.owneroffice
                 JOIN di_management.office pub ON pub.t_id=d.publisheroffice
                 LEFT JOIN di_management.datasetissue i ON i.t_id=d.currentissue
                 WHERE d.t_ili_tid=?
                 """)) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return new Created(rows.getString("owner"), rows.getString("publisher"),
                    rows.getString("akind"), rows.getString("publicationstatus"), rows.getString("accesslevel"),
                    rows.getString("contactemail"), rows.getInt("issues"), rows.getString("issuekey"));
            }
        }
    }

    private static void screenshot(Page page) {
        String directory = System.getenv("DI_BROWSER_SCREENSHOT_DIR");
        if (directory != null) {
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(directory, "dataset-new-offices.png")));
        }
    }

    private record Created(String owner, String publisher, String kind, String publicationStatus, String access,
                           String contactEmail, int issues, String currentIssue) {}
}
