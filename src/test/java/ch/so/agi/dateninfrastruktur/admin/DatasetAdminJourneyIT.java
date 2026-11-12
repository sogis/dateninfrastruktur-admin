package ch.so.agi.dateninfrastruktur.admin;

import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.so.agi.dateninfrastruktur.admin.model.Authorityassignment;
import ch.so.agi.dateninfrastruktur.admin.model.Dataset;
import ch.so.agi.dateninfrastruktur.admin.model.Office;
import ch.so.agi.dateninfrastruktur.admin.model.Principal;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import org.apache.cayenne.query.ObjectSelect;
import org.junit.jupiter.api.Test;

import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
import static org.junit.jupiter.api.Assertions.*;

class DatasetAdminJourneyIT {
    private static final String DATASETS = "/management/datasets";
    private static final String NEW_DATASET = DATASETS + "/new";

    @Test
    void managersMaintainSeriesAndApplicantsUseThePublicAccessRequestThroughOidc() throws Exception {
        String gateway = requiredEnvironment("DI_TEST_GATEWAY");
        String issuer = requiredEnvironment("DI_TEST_OIDC_ISSUER");
        try (var fixture = new ManagementFixture(issuer, OidcLoginIT.CLAUDIA, OidcLoginIT.ANNA, OidcLoginIT.BEN);
             var playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC", "1")));
             var chromium = playwright.chromium().launch();
             var managerContext = chromium.newContext(new Browser.NewContextOptions().setViewportSize(1440, 900));
             var concurrentContext = chromium.newContext(new Browser.NewContextOptions().setViewportSize(1440, 900));
             var applicantContext = chromium.newContext(new Browser.NewContextOptions().setViewportSize(1024, 800))) {
            UUID publicId = publishedRestrictedDataset(fixture);
            String publicIdentifier = dataset(fixture, publicId).getIdentifier();
            var manager = managerContext.newPage();
            manager.navigate(gateway + "/management");
            finishLogin(manager, "claudia", gateway + "/management");
            assertTrue(managerContext.cookies().stream()
                .anyMatch(cookie -> cookie.name.equals("di_management_session") && cookie.httpOnly));
            assertTrue(manager.getByRole(AriaRole.TABLE).isVisible());
            screenshot(manager, "admin-journey-list.png");
            link(manager, "Neuer Datensatz").click();
            manager.waitForURL(gateway + NEW_DATASET);
            var create = form(manager, DATASETS);
            assertEquals(1, create.count());
            assertEquals(fixture.office.toString(), create.locator("input[type=hidden][name=ownerOffice]").inputValue());

            String secondOffice = grantSameNameOffice(fixture);
            assertEquals(200, manager.reload().status());
            assertEquals(1, create.count());
            var office = create.locator("select[name=ownerOffice]");
            assertEquals("", office.inputValue());
            assertEquals(3, office.locator("option").count());
            assertEquals("Prüfamt — " + fixture.suffix.substring(0, 8) + " (office-" + fixture.suffix + ")",
                office.locator("option[value='" + fixture.office + "']").innerText());
            assertEquals("Prüfamt — J" + fixture.suffix.substring(0, 7) + " (office-journey-" + fixture.suffix + ")",
                office.locator("option[value='" + secondOffice + "']").innerText());
            assertEquals(0, create.locator("input[name=themes]:checked").count());

            String identifier = "browserreise-" + fixture.suffix;
            office.selectOption(secondOffice.toString());
            create.locator("input[name=identifier]").fill(identifier);
            create.locator("select[name=kind]").selectOption("series");
            create.locator("input[name=title]").fill("Browserreise Zeitreihe");
            create.locator("textarea[name=description]").fill("Beschreibung aus dem echten Browser");
            create.locator("input[name=contactEmail]").fill("daten@example.invalid");
            screenshot(manager, "admin-journey-new.png");
            UUID createCommand = commandId(create);
            URI created = followRedirect(manager, submit(manager, create), 303);
            UUID series = UUID.fromString(created.getPath().substring(DATASETS.length() + 1));
            String detailPath = DATASETS + "/" + series;
            assertEquals(detailPath, created.getPath());
            assertEquals("created=true", created.getQuery());
            assertTrue(manager.getByText("Der Datensatz wurde als Entwurf erstellt.").isVisible());
            assertStatus(manager, "Entwurf", "Eingeschränkt");
            assertEquals(0, manager.locator("form").count());
            var draft = dataset(fixture, series);
            assertEquals(secondOffice, draft.getOwneroffice1().getTIliTid());
            assertEquals(secondOffice, draft.getPublisheroffice1().getTIliTid());
            assertEquals(identifier, draft.getIdentifier());
            assertEquals("series", draft.getAkind());
            assertTrue(draft.getDatasetissues().isEmpty());

            seedImportedField(fixture, series);
            link(manager, "Metadaten bearbeiten").click();
            manager.waitForURL(gateway + detailPath + "/edit");
            var edit = form(manager, detailPath + "/edit");
            assertEquals("daten@example.invalid", edit.locator("input[name=contactEmail]").inputValue());
            assertEquals(0, edit.locator("[name=identifier], [name=ownerOffice], [name=kind]").count());
            // Use a separately authenticated browser for the concurrent edit.
            var stale = concurrentContext.newPage();
            stale.navigate(gateway + detailPath + "/edit");
            finishLogin(stale, "claudia", gateway + detailPath + "/edit");
            var staleForm = form(stale, detailPath + "/edit");
            UUID staleCommand = commandId(staleForm);

            edit.locator("input[name=title]").fill("Zeitreihe aus der Browserprüfung");
            edit.locator("input[name=contactEmail]").fill("pflege@example.invalid");
            edit.locator("input[name=themes][value=Energie]").check();
            edit.locator("textarea[name=keywords]").fill("erste Zeile\nzweite Zeile");
            screenshot(manager, "admin-journey-edit.png");
            UUID editCommand = commandId(edit);
            assertEquals(detailPath, followRedirect(manager, submit(manager, edit), 303).getPath());

            staleForm.locator("input[name=title]").fill("Neueste Browser-Eingabe");
            staleForm.locator("input[name=contactEmail]").fill("pflege@example.invalid");
            staleForm.locator("input[name=themes][value=Energie]").check();
            staleForm.locator("textarea[name=keywords]").fill("erste Zeile\nzweite Zeile");
            assertEquals(detailPath, followRedirect(stale, submit(stale, staleForm), 303).getPath());
            stale.close();

            var saved = dataset(fixture, series);
            assertEquals("Neueste Browser-Eingabe", saved.getTitle());
            assertEquals("mailto:pflege@example.invalid", saved.primaryContact().getEmail());
            assertEquals("Importierter Erhebungsvermerk", saved.getSurveymethod());
            assertEquals("draft", saved.getPublicationstatus());
            assertEquals("restricted", saved.getAccesslevel());
            assertEquals(2, saved.getKeywordentries().size());
            assertEquals("Energie", saved.getThemeentries().getFirst().getAcode());

            String issuePath = detailPath + "/issues";
            link(manager, "Ausgaben").click();
            manager.waitForURL(gateway + issuePath);
            link(manager, "Neue Ausgabe").click();
            manager.waitForURL(gateway + issuePath + "/new");
            var issueForm = form(manager, issuePath);
            issueForm.locator("input[name=key]").fill("2026");
            issueForm.locator("input[name=label]").fill("Ausgabe 2026");
            UUID issueCommand = commandId(issueForm);
            assertEquals(issuePath, followRedirect(manager, submit(manager, issueForm), 303).getPath());
            assertTrue(manager.getByRole(AriaRole.ROW)
                .filter(new Locator.FilterOptions().setHasText("Ausgabe 2026")).isVisible());
            screenshot(manager, "admin-journey-issues.png");
            assertEquals("2026", dataset(fixture, series).getDatasetissues().getFirst().getIssuekey());

            assertEquals(200, manager.navigate(gateway + detailPath).status());
            UUID publishCommand = confirmAction(manager, detailPath, "/publish", "Publizieren", "Fachlich geprüft");
            assertStatus(manager, "Publiziert", "Eingeschränkt");
            assertEquals("restricted", dataset(fixture, series).getAccesslevel());
            UUID accessCommand = confirmAction(manager, detailPath, "/access", "Datenzugang ändern",
                "Öffentliche Daten aus der Prüfreihe");
            assertStatus(manager, "Publiziert", "Frei zugänglich");
            assertEquals("published", dataset(fixture, series).getPublicationstatus());
            assertEquals("open", dataset(fixture, series).getAccesslevel());
            screenshot(manager, "admin-journey-desktop.png");

            manager.setViewportSize(390, 844);
            assertEquals(200, manager.reload().status());
            assertNoOverflow(manager);
            manager.keyboard().press("Tab");
            assertEquals("#main-content", manager.locator(":focus").getAttribute("href"));
            manager.keyboard().press("Enter");
            assertEquals("main-content", manager.locator(":focus").getAttribute("id"));
            screenshot(manager, "admin-journey-narrow.png");
            manager.evaluate("document.documentElement.style.fontSize = '200%'");
            assertNoOverflow(manager);

            var applicant = applicantContext.newPage();
            assertEquals(200, applicant.navigate(gateway + "/datasets/" + publicIdentifier).status());
            var accessLink = link(applicant, "Datenzugang anfragen");
            String requestPath = "/access/requests/new?dataset=" + publicId;
            assertEquals(requestPath, accessLink.getAttribute("href"));
            screenshot(applicant, "admin-journey-sodata.png");
            accessLink.click();
            finishLogin(applicant, "anna", gateway + requestPath);
            var request = form(applicant, "/access/requests");
            assertEquals(publicId.toString(), request.locator("input[name=dataset]").inputValue());
            screenshot(applicant, "admin-journey-request.png");
            assertForgedCreateForbidden(applicant, request, fixture, secondOffice);
            assertEquals(200, applicant.reload().status());
            UUID requestCommand = commandId(request);
            request.locator("textarea[name=reason]").fill("Öffentlicher Katalogzugang über OIDC");
            assertEquals("/access/me", followRedirect(applicant, submit(applicant, request), 302).getPath());
            assertAccessRequest(fixture, publicId);

            assertEquals(200, applicant.navigate(gateway + NEW_DATASET).status());
            assertEquals(0, form(applicant, DATASETS).count());
            assertTrue(applicant.getByRole(AriaRole.MAIN).innerText().contains("für kein Amt"));
        }
    }

    private static void finishLogin(Page page, String username, String destination) {
        page.locator("#username").fill(username);
        page.locator("#password").fill(requiredEnvironment("DI_TEST_DEMO_PASSWORD"));
        page.locator("#kc-login").click();
        page.waitForURL(destination);
    }

    private static Locator form(Page page, String action) {
        return page.locator("form[method=post][action='" + action + "']");
    }

    private static Locator link(Page page, String name) {
        return page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    private static UUID commandId(Locator form) {
        return UUID.fromString(form.locator("input[name=commandId]").inputValue());
    }

    private static Response submit(Page page, Locator form) {
        String action = form.getAttribute("action");
        return page.waitForResponse(response -> response.request().method().equals("POST")
            && URI.create(response.url()).getPath().equals(action), () -> form.locator("button[type=submit]").click());
    }

    private static URI followRedirect(Page page, Response response, int status) {
        assertEquals(status, response.status(), () -> URI.create(response.url()).getPath()
            + " did not redirect (content type: " + response.headerValue("content-type")
            + ", Admin error page: " + response.text().contains("notice notice-error") + ")");
        URI redirect = URI.create(response.url()).resolve(response.headerValue("location"));
        page.waitForURL(redirect.toString());
        return redirect;
    }

    private static UUID confirmAction(Page page, String detailPath, String suffix, String label, String reason) {
        link(page, label).click();
        var form = form(page, detailPath + suffix);
        UUID commandId = commandId(form);
        form.locator("textarea[name=reason]").fill(reason);
        assertEquals(detailPath, followRedirect(page, submit(page, form), 303).getPath());
        return commandId;
    }

    private static void assertStatus(Page page, String publication, String access) {
        assertTrue(page.getByRole(AriaRole.REGION, new Page.GetByRoleOptions().setName("Publikationsstatus"))
            .getByText(publication, new Locator.GetByTextOptions().setExact(true)).isVisible());
        assertTrue(page.getByRole(AriaRole.REGION, new Page.GetByRoleOptions().setName("Datenzugang"))
            .getByText(access, new Locator.GetByTextOptions().setExact(true)).isVisible());
    }

    private static void assertNoOverflow(Page page) {
        assertEquals(Boolean.TRUE, page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
    }

    private static void assertForgedCreateForbidden(Page page, Locator request, ManagementFixture fixture, String office) {
        UUID id = UUID.randomUUID();
        String identifier = "unbefugt-" + fixture.suffix;
        var reply = (Map<?, ?>) page.evaluate("""
            async fields => {
                const response = await fetch('/management/datasets', {
                    method: 'POST', body: new URLSearchParams(fields)
                });
                return { status: response.status, type: response.headers.get('content-type'), body: await response.text() };
            }
            """, Map.of(
                "_csrf", request.locator("input[name=_csrf]").inputValue(),
                "commandId", id.toString(),
                "ownerOffice", office.toString(),
                "identifier", identifier,
                "kind", "series",
                "title", "Unberechtigter Browserversuch",
                "description", "Test",
                "contactEmail", "anna@example.invalid"
            ));
        assertEquals(403, ((Number) reply.get("status")).intValue());
        assertTrue(reply.get("type").toString().startsWith("text/html"));
        assertTrue(reply.get("body").toString().contains("für kein Amt"));
        assertEquals(0, ObjectSelect.query(Dataset.class).where(Dataset.IDENTIFIER.eq(identifier))
            .selectCount(fixture.runtime.newContext()));
    }

    private static String grantSameNameOffice(ManagementFixture fixture) {
        return fixture.unit.execute(fixture.manager, command("operator.bootstrap"), context -> {
            var principal = ObjectSelect.query(Principal.class).where(Principal.T_ILI_TID.eq(fixture.managerId))
                .selectOne(context);
            var office = context.newObject(Office.class);
            office.setTIliTid("office-" + UUID.randomUUID());
            office.setIdentifier("office-journey-" + fixture.suffix);
            office.setAbbreviation("J" + fixture.suffix.substring(0, 7));
            office.setAname("Prüfamt");
            office.setAactive(true);
            for (String capability : List.of("manage_metadata", "publish", "manage_access")) {
                var assignment = context.newObject(Authorityassignment.class);
                assignment.setTIliTid(UUID.randomUUID());
                assignment.setPrincipal1(principal);
                assignment.setOffice1(office);
                assignment.setAssignedby1(principal);
                assignment.setCapability(capability);
                assignment.setReason("Testsetup");
                assignment.setValidfrom(CayenneSql.now(context).minusDays(1));
            }
            return office.getTIliTid();
        });
    }

    private static UUID publishedRestrictedDataset(ManagementFixture fixture) {
        UUID id = fixture.published();
        DownloadAuthorizationRepositoryIT.file(fixture, id, 1);
        fixture.unit.execute(fixture.manager, command("operator.register_fixture"), context -> {
            var root = ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(context);
            var issue = root.getDatasetissues().getFirst();
            issue.setCurrentversion1(issue.getDataversions().getFirst());
            return null;
        });
        return id;
    }

    private static void seedImportedField(ManagementFixture fixture, UUID id) {
        fixture.unit.execute(fixture.manager, command("operator.import_fixture"), context -> {
            var root = ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(context);
            root.setSurveymethod("Importierter Erhebungsvermerk");
            return null;
        });
    }

    private static Dataset dataset(ManagementFixture fixture, UUID id) {
        return ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(id)).selectOne(fixture.runtime.newContext());
    }

    private static void assertAccessRequest(ManagementFixture fixture, UUID dataset) throws SQLException {
        try (var connection = fixture.admin.getConnection();
             var statement = connection.prepareStatement("""
                 SELECT r.astatus, p.aissuer, p.asubject
                 FROM di_management.accessrequest r
                 JOIN di_management.dataset d ON d.t_id=r.dataset
                 JOIN di_management.principal p ON p.t_id=r.applicant
                 WHERE d.t_ili_tid=?
                 """)) {
            statement.setObject(1, dataset);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("pending", result.getString("astatus"));
                assertEquals(fixture.anna.issuer(), result.getString("aissuer"));
                assertEquals(OidcLoginIT.ANNA, result.getString("asubject"));
                assertFalse(result.next());
            }
        }
    }

    private static void screenshot(Page page, String filename) {
        String directory = System.getenv("DI_BROWSER_SCREENSHOT_DIR");
        if (directory != null) {
            page.evaluate("window.scrollTo(0, 0)");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(directory, filename)));
        }
    }

    private static String requiredEnvironment(String name) {
        var value = System.getenv(name);
        assertNotNull(value, "Run with the owned final browser fixture (" + name + ")");
        return value;
    }
}
