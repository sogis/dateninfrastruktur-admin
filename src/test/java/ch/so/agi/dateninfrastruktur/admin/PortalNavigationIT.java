package ch.so.agi.dateninfrastruktur.admin;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import com.microsoft.playwright.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class PortalNavigationIT {
    @Test void portalRequestLinkActuallyPassesGatewayOidcToTheAdminSelfService()throws Exception{
        Map<?,?> fixture=new ObjectMapper().readValue(Files.readString(Path.of(".runtime/portal-fixture.json")),Map.class);
        try(var playwright=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC","1")));var browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))){
            var page=browser.newPage();String gateway=System.getenv("DI_TEST_GATEWAY");page.navigate(gateway+"/series/"+fixture.get("seriesIdentifier"));
            var link=page.locator("a",new Page.LocatorOptions().setHasText("Datenzugang anfragen"));assertEquals("/access/requests/new?dataset="+fixture.get("seriesId"),link.getAttribute("href"));link.click();
            page.locator("#username").fill("anna");page.locator("#password").fill(System.getenv("DI_TEST_DEMO_PASSWORD"));page.locator("#kc-login").click();page.waitForURL(gateway+"/access/requests/new?dataset="+fixture.get("seriesId"));
            assertEquals("Datenzugang anfragen",page.locator("h1").innerText());assertEquals(fixture.get("seriesId").toString(),page.locator("form input[name=dataset]").inputValue());
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(".runtime/portal-to-admin-browser.png")).setFullPage(true));
        }
    }
}
