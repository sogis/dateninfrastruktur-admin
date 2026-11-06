package ch.so.agi.dateninfrastruktur.admin;

import ch.so.agi.dateninfrastruktur.admin.ingestion.CanonicalJson;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

class CsvPublicationBrowserIT {
    @Test void browserJourney() throws Exception {
        var helper = new DeliveryQueueBrowserIT();
        var input = Path.of(System.getenv("DI_G5_BROWSER_COMMAND"));
        var command = CanonicalJson.JSON.readTree(Files.readAllBytes(input));
        var fixture = CanonicalJson.JSON.readTree(Files.readAllBytes(input.resolveSibling("g4-fixture.json")));
        try (var playwright = Playwright.create(); var browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true))) {
            String action = command.path("action").asString();
            if (action.startsWith("grant-") || action.startsWith("revoke-")) {
                var page = browser.newContext().newPage();
                for (var dataset : command.path("datasets")) {
                    var id = UUID.fromString(fixture.path(dataset.asString()).path("datasetId").asString());
                    manage(page, helper.base, action, id, fixture.path("anna").asString());
                }
            } else if (action.equals("no-grants")) {
                var page = helper.login(browser, "anna", OidcLoginIT.ANNA);
                assertEquals(0, page.locator("form[action$='/prepare']").count());
                assertEquals(404, page.request().get(helper.base+"/jenkins/gretl-datenportal/").status());
            } else if (action.equals("submit")) {
                var page = helper.login(browser, "anna", OidcLoginIT.ANNA);
                var result = new ArrayList<UUID>();
                for (var job : command.path("jobs")) {
                    page.navigate(helper.base+"/anlieferung");
                    var id = UUID.fromString(fixture.path(job.path("dataset").asString()).path("datasetId").asString());
                    var form = page.locator("form[action$='/prepare']:has(input[name='datasetId'][value='"+id+"'])");
                    assertEquals(1, form.count());
                    if (job.path("attack").asBoolean()) {
                        helper.hiddenJob(page, true);
                        var fields = new LinkedHashMap<String,String>();
                        for (String field : List.of("datasetId","issueId","configurationDigest")) {
                            fields.put(field, form.locator("input[name='"+field+"']").inputValue());
                        }
                        fields.put("mode","replace");fields.put("commandId",UUID.randomUUID().toString());
                        fields.put("comment","Isolierter G5-Request");
                        var bad = new LinkedHashMap<>(fields);
                        bad.put("issueId",fixture.path("municipality").path("issueId").asString());
                        int rejected = helper.prepare(page,bad,helper.base,true).status();
                        assertTrue(Set.of(403,409).contains(rejected), "Foreign issue returned "+rejected);
                        var first = helper.prepare(page,fields,helper.base,true);
                        var duplicate = helper.prepare(page,fields,helper.base,true);
                        assertEquals(303,first.status());assertEquals(303,duplicate.status());
                        assertEquals(first.headers().get("location"),duplicate.headers().get("location"));
                        page.navigate(helper.base+first.headers().get("location"));
                    } else {
                        form.locator("select[name=mode]").selectOption(job.path("mode").asString("replace"));
                        form.locator("input[name=comment]").fill("Isolierter G5-Fachpfad");
                        var prepared = page.waitForResponse(r -> r.url().endsWith("/data-delivery/prepare") &&
                                r.request().method().equals("POST"), () -> form.getByRole(AriaRole.BUTTON).click());
                        assertEquals(303,prepared.status());
                        page.waitForURL("**/uploadForm?id=*");
                    }
                    var submission = UUID.fromString(page.url().substring(page.url().indexOf("id=")+3));
                    var csv = Path.of(job.path("csv").asString()).toRealPath();
                    assertEquals(input.getParent().toRealPath(),csv.getParent());
                    page.locator("input[type=file]").setInputFiles(csv);
                    var uploaded = page.waitForResponse(r -> r.url().contains("/data-delivery/upload?id="+submission),
                            () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("CSV einreichen")).click());
                    assertEquals(201,uploaded.status(),uploaded::text);
                    page.waitForURL("**/status?id="+submission);
                    result.add(submission);
                }
                Files.writeString(input.resolveSibling("g5-browser-result.json"),CanonicalJson.encode(result));
            } else if (action.equals("not-eligible")) {
                var page = helper.login(browser, "anna", OidcLoginIT.ANNA);
                var id = fixture.path(command.path("datasets").get(0).asString()).path("datasetId").asString();
                assertEquals(0,page.locator("input[name=datasetId][value='"+id+"']").count());
            } else {
                throw new IllegalArgumentException("Unknown isolated browser step");
            }
        }
    }

    private static void manage(Page page, String base, String action, UUID dataset, String anna) {
        boolean submit = action.endsWith("submit"), grant = action.startsWith("grant");
        String route = submit ? "/management/delivery/datasets/"+dataset : "/management/datasets/"+dataset+"/grants";
        String query = grant ? "?search="+OidcLoginIT.ANNA : "";
        page.navigate(base+route+query);
        if (page.locator("#username").count()==1) {
            page.locator("#username").fill("claudia");
            page.locator("#password").fill(System.getenv("DI_G3_USER_PASSWORD"));
            page.locator("#kc-login").click();
            page.waitForURL(base+"/management/**");
        }
        String endpoint = submit ? route + (grant?"/grant":"/revoke") : route;
        Locator form;
        if (grant) {
            form = page.locator("form[action='"+endpoint+"']:has(input[name=beneficiary][value='"+anna+"'])");
        } else {
            form = submit ? page.locator("form[action='"+endpoint+"']") : page.locator("form[action^='/management/grants/'][action$='/revoke']");
        }
        assertEquals(1,form.count(),() -> "Missing scoped administration form: "+action);
        form.locator("textarea[name=reason]").fill("Isolierter G5-Rechtenachweis");
        var response = page.waitForResponse(r -> r.request().method().equals("POST") &&
                (submit || grant ? r.url().equals(base+endpoint) : r.url().startsWith(base+"/management/grants/")),
                () -> form.getByRole(AriaRole.BUTTON).click());
        assertTrue(Set.of(302,303).contains(response.status()),() -> "Administrative command failed: "+response.status());
        page.waitForURL(base+route);
    }
}
