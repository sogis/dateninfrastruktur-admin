package ch.so.agi.dateninfrastruktur.admin;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StackManagementIT {
    private final String gateway = Objects.requireNonNull(System.getenv("DI_TEST_GATEWAY"));
    private static final String DATASETS = "/management/datasets";
    private static final String DELIVERY = "/management/delivery/datasets/";
    private static final Pattern UUID_TEXT = Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");

    @Test
    void publishesNewDatasetWithConfiguredSchemaAndSubmitterThroughRealForms() {
        try (var playwright = Playwright.create(new Playwright.CreateOptions()
                     .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC", "1")));
             var browser = playwright.chromium().launch();
             var context = browser.newContext()) {
            var page = context.newPage();
            OidcLoginIT.login(page, DATASETS + "/new", "anna");
            assertTrue(context.cookies().stream().anyMatch(cookie ->
                    cookie.name.equals("di_management_session") && cookie.httpOnly));
            var create = form(page, DATASETS);
            var ownerOffice = create.locator("[name=ownerOffice]");
            String officeTid = ownerOffice.inputValue();
            assertEquals(200, officeTid.length());
            create.locator("[name=identifier]").fill("s1f-browser");
            create.locator("[name=kind]").selectOption("standalone");
            create.locator("[name=title]").fill("S1 Browserangebot");
            create.locator("[name=description]").fill("Synthetischer Browser-Datensatz");
            create.locator("[name=contactName]").fill("Browserkontakt");
            create.locator("[name=contactEmail]").fill("kontakt@example.invalid");
            ownerOffice.evaluate("""
                (field, tid) => {
                    if (field instanceof HTMLSelectElement) field.add(new Option(tid, tid));
                    field.value = tid;
                }
                """, officeTid + "x");
            post(page, create, 400);
            assertTrue(page.locator("#ownerOffice-error").isVisible());
            create = form(page, DATASETS);
            create.locator("[name=ownerOffice]").evaluate("(field, tid) => field.value = tid", officeTid);
            post(page, create, 303);
            page.waitForURL("**/management/datasets/*?created=true");
            String detail = URI.create(page.url()).getPath();
            String dataset = detail.substring(DATASETS.length() + 1);
            assertTrue(UUID_TEXT.matcher(dataset).matches());
            assertTrue(page.locator("main").innerText().contains("Entwurf"));

            assertEquals(200, page.navigate(gateway + detail + "/issues").status());
            String versions = page.locator("a[href$='/versions']").all().stream()
                    .map(link -> link.getAttribute("href"))
                    .filter(path -> path.startsWith(detail + "/issues/"))
                    .findFirst().orElseThrow();
            String issue = versions.substring((detail + "/issues/").length(), versions.length() - "/versions".length());
            assertTrue(UUID_TEXT.matcher(issue).matches());

            String delivery = DELIVERY + dataset;
            assertEquals(200, page.navigate(gateway + delivery + "/configuration").status());
            var createSchema = form(page, delivery + "/schema");
            createSchema.locator("[name=columns]").fill("zone,code");
            createSchema.locator("[name=unique]").fill("zone,code");
            post(page, createSchema, 200);
            var schemaMatch = UUID_TEXT.matcher(page.locator("main").innerText());
            assertTrue(schemaMatch.find());
            String schema = schemaMatch.group();

            assertEquals(200, page.navigate(gateway + delivery + "/configuration").status());
            var configure = form(page, delivery + "/configuration");
            configure.locator("[name=issue]").fill(issue);
            configure.locator("[name=schema]").fill(schema);
            configure.locator("[name=enabled]").check();
            post(page, configure, 302);

            assertEquals(200, page.navigate(gateway + delivery).status());
            var grant = form(page, delivery + "/grant");
            grant.locator("[name=beneficiary]").fill("98ea50b1-eb3f-5dd0-82d8-e1c5a6be0889");
            grant.locator("[name=reason]").fill("Browserprüfung Lieferrecht");
            post(page, grant, 302);
            assertTrue(page.locator("main").innerText().contains("Lieferrecht"));

            assertEquals(200, page.navigate(gateway + detail + "/publish").status());
            var publish = form(page, detail + "/publish");
            publish.locator("[name=reason]").fill("Synthetische Veröffentlichung");
            post(page, publish, 303);

            assertEquals(200, page.navigate(gateway + DELIVERY + "a36af55d-396d-507c-bab3-acb239717bb8").status());
            assertTrue(page.locator("main").innerText().contains("Submission"));
        }
    }

    private static Locator form(Page page, String path) {
        return page.locator("form[action='" + path + "']");
    }

    private static void post(Page page, Locator form, int expected) {
        assertEquals(1, form.locator("[name=_csrf]").count());
        assertEquals(0, form.locator("[name=expectedVersion]").count());
        var response = page.waitForResponse(r -> r.request().method().equals("POST"),
                () -> form.locator("button[type=submit]").click());
        assertEquals(expected, response.status(), URI.create(response.url()).getPath());
    }
}
