package ch.so.agi.dateninfrastruktur.admin;
import com.microsoft.playwright.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
/** Canonical Compose only: no lifecycle helper and no legacy runtime state. */
class ComposeJourneyIT {
 @Test void realOidcRequestApprovalAndDownload()throws Exception {
  String gateway=Objects.requireNonNull(System.getenv("DI_COMPOSE_GATEWAY"));
  String password=Objects.requireNonNull(System.getenv("DI_COMPOSE_DEMO_PASSWORD"));
  var fixture=new ObjectMapper().readValue(Files.readString(Path.of(System.getenv("DI_COMPOSE_FIXTURE"))),Map.class);
  try(var p=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC","1")));var browser=p.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));var applicant=browser.newContext();var manager=browser.newContext()){
   var page=applicant.newPage();page.navigate(gateway+"/datasets/ma-e-restricted");
   assertTrue(page.locator("a[href^='/downloads/']").count()>0);
   page.locator("a[href^='/access/requests/new']").first().click();
   page.locator("#username").fill("anna");page.locator("#password").fill(password);page.locator("#kc-login").click();page.waitForURL("**/access/requests/new?*");
   var request=page.locator("form[action='/access/requests']");request.locator("textarea[name=reason]").fill("Compose F07 echter Benutzerantrag");request.locator("button[type=submit]").click();page.waitForURL("**/access/me");assertTrue(page.locator("main").innerText().contains("Offen"));
   var admin=manager.newPage();admin.navigate(gateway+"/management/requests");admin.locator("#username").fill("claudia");admin.locator("#password").fill(password);admin.locator("#kc-login").click();admin.waitForURL("**/management/requests");
   var approval=admin.locator("form[action$='/approve']").first();approval.locator("textarea[name=reason]").fill("Compose F07 Freigabe");approval.locator("button[type=submit]").click();admin.waitForURL("**/management/requests");
   page.navigate(gateway+"/datasets/ma-e-restricted");var link=page.locator("a[href^='/downloads/']").first();var download=page.waitForDownload(()->link.click());assertTrue(Files.size(download.path())>0);
   admin.navigate(gateway+"/management/datasets/"+fixture.get("restricted")+"/audit");assertTrue(admin.locator("main").innerText().contains("approve"));
  }
 }
}
