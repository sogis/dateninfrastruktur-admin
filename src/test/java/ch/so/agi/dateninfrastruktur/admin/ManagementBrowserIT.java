package ch.so.agi.dateninfrastruktur.admin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import ch.so.agi.dateninfrastruktur.admin.access.AccessWorkflowService;
import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetFilters;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetOffice;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetPage;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetRow;
import ch.so.agi.dateninfrastruktur.admin.authorization.DownloadAuthorizationService;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import ch.so.agi.dateninfrastruktur.admin.persistence.AggregateWriteGuard.StaleAggregateException;
import ch.so.agi.dateninfrastruktur.admin.security.ManagementSecurityConfiguration;
import ch.so.agi.dateninfrastruktur.admin.web.AuthorizationController;
import ch.so.agi.dateninfrastruktur.admin.web.DatasetController;
import ch.so.agi.dateninfrastruktur.admin.web.ManagementController;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.ControllerAdvice;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(
    classes = ManagementBrowserIT.WebFixture.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "server.address=127.0.0.1",
        "di.web.public-origin=http://management.test",
        "di.web.gateway-secret=" + ManagementBrowserIT.GATEWAY,
        "management.endpoint.health.validate-group-membership=false"
    }
)
class ManagementBrowserIT {
    static final String GATEWAY = "synthetic-gateway-credential-for-tests";
    private static final String ORIGIN = "http://management.test";
    private static final String TOKEN = "synthetic-management-bearer";
    private static final String AUTHORIZATION = "Bearer " + TOKEN;
    private static final UUID DATASET = UUID.fromString("73e1475c-d22e-4512-8ac2-0a4e7f54b31f");
    private static final String REQUEST_FORM = "/access/requests/new?dataset=" + DATASET;
    private static final String DATASET_TITLE = "Prüfangebot <img id=unsafe src=x>";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({ManagementSecurityConfiguration.class, ManagementController.class, DatasetController.class, AuthorizationController.class})
    @ComponentScan(
        basePackageClasses = ManagementController.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = ControllerAdvice.class)
    )
    static class WebFixture {}

    @LocalServerPort int port;
    @MockitoBean(name = "managementJwtDecoder") JwtDecoder jwtDecoder;
    @MockitoBean ManagementQueryService query;
    @MockitoBean AccessWorkflowService workflow;
    @MockitoBean CatalogCommandService catalog;
    @MockitoBean AuthorityService authority;
    @MockitoBean DownloadAuthorizationService downloadAuthorization;

    @BeforeEach
    void stubVerifiedUserAndDataset() {
        when(jwtDecoder.decode(TOKEN)).thenReturn(Jwt.withTokenValue(TOKEN)
            .header("alg", "RS256")
            .issuer("https://identity.example.invalid")
            .subject("browser-tester")
            .build());
        when(query.requestDataset(DATASET)).thenReturn(Map.of("title", DATASET_TITLE, "access", "restricted"));
        when(query.datasets(any(), eq(new DatasetFilters(null, null, null, null, 0))))
            .thenReturn(new DatasetPage(List.of(), false, false, false, List.of(), false));
    }

    @Test
    void requestFormRejectsMissingCsrfAndForeignOriginAndShowsHtmlValidation() throws Exception {
        var form = send(browser(REQUEST_FORM).GET());
        assertHtml(form, 200);
        String cookie = form.headers().allValues("set-cookie").stream()
            .filter(value -> value.startsWith("XSRF-TOKEN="))
            .findFirst().orElseThrow().split(";", 2)[0];
        String csrf = hiddenValue(form.body(), "_csrf");
        String commandId = hiddenValue(form.body(), "commandId");
        UUID command = UUID.fromString(commandId);

        String fields = "dataset=" + DATASET + "&reason=Testanfrage&commandId=" + commandId;
        assertEquals(403, send(post("/access/requests", ORIGIN, cookie, fields)).statusCode());
        String validCsrf = fields + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        assertEquals(403, send(post("/access/requests", "https://foreign.example.invalid", cookie, validCsrf)).statusCode());

        var invalidDate = send(post("/access/requests", ORIGIN, cookie, validCsrf + "&until=invalid"));
        assertHtml(invalidDate, 400);
        assertFalse(invalidDate.body().contains("TEMPORARILY_UNAVAILABLE"));
        assertFalse(invalidDate.body().contains("DateTimeParseException"));
        verifyNoInteractions(workflow);

        var success = send(post("/access/requests", ORIGIN, cookie, validCsrf));
        assertEquals(302, success.statusCode());
        URI redirect = success.uri().resolve(success.headers().firstValue("location").orElseThrow());
        assertEquals(uri("/access/me"), redirect);
        verify(workflow).request(argThat(actor -> actor.subject().equals("browser-tester")),
            argThat(context -> context.commandId().equals(command) && context.requestId() != null),
            eq(DATASET), eq("Testanfrage"), isNull());

        UUID accessRequest = UUID.randomUUID();
        when(workflow.cancel(any(), any(), eq(accessRequest), eq(2L))).thenThrow(new StaleAggregateException());
        String staleFields = "commandId=" + UUID.randomUUID() + "&expectedVersion=2&_csrf="
            + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        var stale = send(post("/access/requests/" + accessRequest + "/cancel", ORIGIN, cookie, staleFields));
        assertHtml(stale, 409);
        assertTrue(stale.body().contains("inzwischen verändert"));
    }

    @Test
    void browserFailuresKeepTheirStatusAndInternalAuthorizationKeepsItsJsonContract() throws Exception {
        when(query.grants(any(), eq(DATASET))).thenThrow(BusinessException.forbidden());

        assertHtml(send(browser("/access/requests/new?dataset=invalid").GET()), 400);
        assertHtml(send(browser("/management/datasets/" + DATASET + "/grants").GET()), 403);
        assertHtml(send(browser("/management/unknown/route").GET()), 404);
        var method = send(browser("/access/requests").GET());
        assertHtml(method, 405);
        assertEquals("POST", method.headers().firstValue("allow").orElseThrow());

        var api = send(HttpRequest.newBuilder(uri("/internal/authorization/check"))
            .header("Accept", "text/html, application/json;q=0.9")
            .header("X-DI-Gateway-Credential", GATEWAY)
            .header("X-Forwarded-Uri", "/downloads/" + DATASET + "/data.csv?override=1")
            .header("X-Forwarded-Method", "GET")
            .GET());
        assertEquals(400, api.statusCode());
        assertTrue(api.headers().firstValue("content-type").orElseThrow().startsWith("application/json"));
        assertEquals(
            Map.of("code", "INVALID_INPUT", "message", "Der Dateipfad ist nicht eindeutig oder nicht kanonisch."),
            new ObjectMapper().readValue(api.body(), Map.class)
        );
        verifyNoInteractions(downloadAuthorization);
    }

    @Test
    void auditKeepsItsDisclosureAndEscapesRecordedValues() throws Exception {
        when(query.audit(any(), eq(DATASET), eq(0L))).thenReturn(List.of(Map.of(
            "change_id", 17, "old_values", "<img id=audit-injection>", "new_values", "Neue Metadaten"
        )));

        var audit = send(browser("/management/datasets/" + DATASET + "/audit").GET());
        assertHtml(audit, 200);
        assertTrue(audit.body().contains("<summary>Vorherige Werte anzeigen</summary>&lt;img id=audit-injection&gt;"));
        assertTrue(audit.body().contains("<summary>Neue Werte anzeigen</summary>Neue Metadaten"));
    }

    @Test
    void sharedShellRendersAKeyboardSkipLinkAndEscapesCatalogTextInChromium() {
        try (var playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC", "1")));
             var chromium = playwright.chromium().launch();
             var context = chromium.newContext(new Browser.NewContextOptions()
                .setViewportSize(1440, 900)
                .setExtraHTTPHeaders(Map.of("Authorization", AUTHORIZATION)))) {
            var page = context.newPage();
            assertEquals(200, page.navigate(uri("/management").toString()).status());
            assertEquals(1, page.getByRole(AriaRole.BANNER).count());
            assertEquals(1, page.getByRole(AriaRole.MAIN).count());
            assertEquals(1, page.locator("h1").count());
            assertEquals(1, page.locator("a[href='/management'][aria-current='page']").count());
            assertEquals(Boolean.TRUE, page.evaluate("""
                document.fonts.ready.then(() => Array.from(document.fonts).some(
                    font => font.family === 'Source Sans 3' && font.status === 'loaded'
                ))
                """));

            page.keyboard().press("Tab");
            var focused = page.locator(":focus");
            assertEquals("#main-content", focused.getAttribute("href"));
            assertEquals("solid", focused.evaluate("node => getComputedStyle(node).outlineStyle"));
            page.keyboard().press("Enter");
            assertEquals("main-content", page.locator(":focus").getAttribute("id"));

            assertEquals(200, page.navigate(uri(REQUEST_FORM).toString()).status());
            assertEquals("Datenzugang anfragen", page.locator("h1").innerText());
            assertTrue(page.getByRole(AriaRole.MAIN).innerText().contains(DATASET_TITLE));
            assertEquals(0, page.locator("#unsafe").count());
            assertEquals(DATASET.toString(), page.locator("form input[name=dataset]").inputValue());
            screenshot(page, "request-desktop.png");

            page.setViewportSize(390, 844);
            assertEquals(Boolean.TRUE, page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
            screenshot(page, "request-mobile.png");
            page.evaluate("document.documentElement.style.fontSize = '200%'");
            assertEquals(Boolean.TRUE, page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
        }
    }

    @Test
    void datasetListRendersScopedRowsAndPreservesFiltersOnPageAndDetailLinks() throws Exception {
        var office = new DatasetOffice("4080f38d-9f4d-46c0-ad5e-e9835c891c59",
            "Prüfamt", "AGI", "agi-pruefung");
        String search = "50%_ + &";
        String queryString = "search=" + URLEncoder.encode(search, StandardCharsets.UTF_8)
            + "&publicationStatus=published&accessLevel=open&ownerOffice=" + office.id();
        String list = "/management/datasets?" + queryString + "&page=1";

        assertEquals(401, send(HttpRequest.newBuilder(uri(list)).header("Accept", "text/html").GET()).statusCode());
        verifyNoInteractions(query);
        var filters = new DatasetFilters(search, "published", "open", office.id(), 1);
        var row = new DatasetRow(DATASET, "angebot-1", DATASET_TITLE, office, "series",
            "published", "open", LocalDate.of(2026, 11, 6));
        when(query.datasets(any(), eq(filters)))
            .thenReturn(new DatasetPage(List.of(row), false, true, true, List.of(office), false));
        when(query.datasets(any(), eq(new DatasetFilters(search, "published", "open", office.id(), 0))))
            .thenReturn(new DatasetPage(List.of(), false, false, true, List.of(office), false));

        try (var playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC", "1")));
             var chromium = playwright.chromium().launch();
             var context = chromium.newContext(new Browser.NewContextOptions()
                .setViewportSize(390, 844)
                .setExtraHTTPHeaders(Map.of("Authorization", AUTHORIZATION)))) {
            var page = context.newPage();
            assertEquals(200, page.navigate(uri(list).toString()).status());
            assertEquals(search, page.locator("input[name=search]").inputValue());
            assertEquals(office.id().toString(), page.locator("select[name=ownerOffice]").inputValue());
            assertEquals(1, page.getByRole(AriaRole.TABLE).count());
            assertEquals(0, page.locator("#unsafe").count());
            var cells = page.getByRole(AriaRole.CELL).allTextContents();
            assertTrue(cells.stream().anyMatch(cell -> cell.contains("Prüfamt")
                && cell.contains("AGI") && cell.contains("agi-pruefung")));
            assertTrue(cells.stream().anyMatch(cell -> cell.strip().equals("Publiziert")));
            assertTrue(cells.stream().anyMatch(cell -> cell.strip().equals("Frei zugänglich")));
            assertEquals(0, page.locator("a[href='/management/datasets/new']").count());
            assertEquals(Boolean.TRUE, page.evaluate("document.documentElement.scrollWidth <= innerWidth"));

            var detail = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(DATASET_TITLE));
            assertEquals("/management/datasets/" + DATASET, detail.evaluate("link => new URL(link.href).pathname"));
            assertEquals(Map.of("search", search, "publicationStatus", "published", "accessLevel", "open",
                "ownerOffice", office.id().toString(), "page", "1"),
                detail.evaluate("link => Object.fromEntries(new URL(link.href).searchParams)"));
            var previous = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Vorherige Seite"));
            previous.click();
            assertTrue(page.getByRole(AriaRole.MAIN).innerText().contains("Keine Datensätze entsprechen Ihrer Suche."));
            assertEquals(search, page.locator("input[name=search]").inputValue());
        }
        verify(query).datasets(argThat(actor -> actor.subject().equals("browser-tester")), eq(filters));
    }

    private static void screenshot(Page page, String filename) {
        String directory = System.getenv("DI_BROWSER_SCREENSHOT_DIR");
        if (directory != null) {
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(directory, filename)));
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpRequest.Builder browser(String path) {
        return HttpRequest.newBuilder(uri(path))
            .header("Authorization", AUTHORIZATION)
            .header("Accept", "text/html");
    }

    private HttpRequest.Builder post(String path, String origin, String cookie, String fields) {
        return browser(path)
            .header("Origin", origin)
            .header("Cookie", cookie)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(fields));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String hiddenValue(String html, String name) {
        var matcher = Pattern.compile("name=\"" + name + "\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(), () -> "Missing form input: " + name);
        return matcher.group(1);
    }

    private static void assertHtml(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), () -> response.uri() + " returned an unexpected status");
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("text/html"),
            () -> response.uri() + " did not return HTML");
    }
}
