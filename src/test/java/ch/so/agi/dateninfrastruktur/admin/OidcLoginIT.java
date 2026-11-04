package ch.so.agi.dateninfrastruktur.admin;
import java.util.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OidcLoginIT {
    static final String ANNA="0b7c9d5e-1f2a-4d3b-8c4e-5f6a7b8c9d01",BEN="1c8d0e6f-2a3b-4c5d-9e6f-7a8b9c0d1e02",CLAUDIA="2d9e1f70-3b4c-5d6e-af70-8b9c0d1e2f03";
    static void login(Page page,String path,String username){
        page.navigate(System.getenv("DI_TEST_GATEWAY")+path);page.locator("#username").fill(username);page.locator("#password").fill(System.getenv("DI_TEST_DEMO_PASSWORD"));page.locator("#kc-login").click();page.waitForURL(System.getenv("DI_TEST_GATEWAY")+"/**");
    }
    @Test void realBrowserCodeFlowRequestApproveAndScopedViews()throws Exception{
        assertNotNull(System.getenv("DI_TEST_GATEWAY"),"Run owned identity harness");
        try(var f=new ManagementFixture(System.getenv("DI_TEST_OIDC_ISSUER"),CLAUDIA,ANNA,BEN);var playwright=Playwright.create();var browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));var anna=browser.newContext();var claudia=browser.newContext()){
            var root=f.published();long principalsBefore;try(var c=f.admin.getConnection();var st=c.createStatement();var rs=st.executeQuery("SELECT count(*) FROM di_management.principal")){rs.next();principalsBefore=rs.getLong(1);}
            var page=anna.newPage();login(page,"/access/requests/new?dataset="+root,"anna");
            assertTrue(page.locator("h1").innerText().contains("Datenzugang anfragen"));
            try(var c=f.admin.getConnection();var ps=c.prepareStatement("SELECT count(*) FROM di_management.principal WHERE aissuer=? AND asubject=?")){ps.setString(1,f.anna.issuer());ps.setString(2,ANNA);try(var rs=ps.executeQuery()){rs.next();assertTrue(rs.getInt(1)<=1,"Identity uniqueness");}}
            try(var c=f.admin.getConnection();var st=c.createStatement();var rs=st.executeQuery("SELECT count(*) FROM di_management.principal")){rs.next();assertEquals(principalsBefore,rs.getLong(1),"GET must not register principals");}
            var form=page.locator("form[action='/access/requests']");form.locator("textarea[name=reason]").fill("Browseranalyse MA-C");form.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Anfrage senden")).click();page.waitForURL("**/access/me");assertTrue(page.locator("main").innerText().contains("Offen"));
            UUID request;
            try(var c=f.admin.getConnection();var ps=c.prepareStatement("SELECT r.t_ili_tid FROM di_management.accessrequest r JOIN di_management.dataset d ON d.t_id=r.dataset WHERE d.t_ili_tid=?")){ps.setObject(1,root);try(var rs=ps.executeQuery()){assertTrue(rs.next());request=rs.getObject(1,UUID.class);}}
            var manager=claudia.newPage();login(manager,"/management/requests","claudia");var approve=manager.locator("form[action='/management/requests/"+request+"/approve']");approve.locator("textarea[name=reason]").fill("Browserprüfung abgeschlossen");approve.getByRole(AriaRole.BUTTON).click();manager.waitForURL("**/management/requests");
            page.reload();assertTrue(page.locator("main").innerText().contains("Genehmigt"));assertTrue(page.locator("main").innerText().contains("Wirksamer Dateizugang"));
            manager.navigate(System.getenv("DI_TEST_GATEWAY")+"/management/datasets/"+root+"/audit");assertTrue(manager.locator("main").innerText().contains("approve"));
            var blocked=page.navigate(System.getenv("DI_TEST_GATEWAY")+"/management/datasets/"+root+"/grants");assertEquals(403,blocked.status());
            assertTrue(anna.cookies().stream().filter(c->c.name.equals("di_management_session")).allMatch(c->c.httpOnly && c.sameSite==com.microsoft.playwright.options.SameSiteAttribute.LAX));
            var output=java.nio.file.Path.of(".runtime/identity/browser-management.png");manager.screenshot(new Page.ScreenshotOptions().setPath(output).setFullPage(true));
        }
    }
}
