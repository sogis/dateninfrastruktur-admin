package ch.so.agi.dateninfrastruktur.admin;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ch.so.agi.dateninfrastruktur.admin.ingestion.CanonicalJson;

/** Real Chrome sessions against the canonical isolated APISIX/Jenkins/Keycloak/ADMIN stack. */
class DeliveryG3BrowserIT {
    static final byte[] CSV="Jahrgang;Auslaender;Auslaenderinnen;Schweizer;Schweizerinnen;Total\n2000;1;2;3;4;10\n".getBytes(StandardCharsets.UTF_8);
    final String base=System.getenv("DI_G3_GATEWAY");
    final String app="/jenkins/data-delivery/";
    record Crumb(String field,String value){}
    Page login(Browser browser,String username,String subject){
        var context=browser.newContext();var page=context.newPage();page.setDefaultTimeout(20000);
        var navigation=page.navigate(base+"/jenkins/securityRealm/commenceLogin?from=/jenkins/data-delivery/");
        assertEquals(200,navigation.status(),"OIDC authorization page after Jenkins redirect");
        URI location=URI.create(page.url());
        assertEquals("/realms/dateninfrastruktur/protocol/openid-connect/auth",location.getPath(),"Authorization endpoint");
        var query=Arrays.stream(location.getRawQuery().split("&")).map(part->part.split("=",2)).collect(java.util.stream.Collectors.toMap(x->x[0],x->URLDecoder.decode(x[1],StandardCharsets.UTF_8)));
        assertTrue("jenkins-delivery".equals(query.get("client_id")),"Dedicated client");
        assertTrue("S256".equals(query.get("code_challenge_method")),"PKCE S256");
        assertTrue((base+"/jenkins/securityRealm/finishLogin").equals(query.get("redirect_uri")),"Exact Jenkins callback");
        page.locator("#username").fill(username);page.locator("#password").fill(System.getenv("DI_G3_USER_PASSWORD"));page.locator("#kc-login").click();page.waitForURL(base+"/jenkins/**");
        var me=context.request().get(base+"/jenkins/me/api/json?tree=id,fullName");assertEquals(200,me.status());assertEquals(subject,CanonicalJson.JSON.readTree(me.body()).path("id").asString());
        var entry=page.navigate(base+"/anlieferung");assertNotNull(entry);assertEquals(200,entry.status());assertTrue(page.url().startsWith(base+app));return page;
    }
    Crumb crumb(Page page){var response=page.request().get(base+"/jenkins/crumbIssuer/api/json");assertEquals(200,response.status());var value=CanonicalJson.JSON.readTree(response.body());return new Crumb(value.path("crumbRequestField").asString(),value.path("crumb").asString());}
    Map<String,String> selection(Page page,int number){var form=page.locator("form[action$='/prepare']").nth(number);var out=new LinkedHashMap<String,String>();for(String name:List.of("datasetId","issueId","configurationDigest"))out.put(name,form.locator("input[name='"+name+"']").inputValue());out.put("mode","replace");out.put("comment","Browserprüfung");out.put("commandId",UUID.randomUUID().toString());return out;}
    APIResponse prepare(Page page,Map<String,String> fields,String origin,boolean useCrumb){var data=FormData.create();fields.forEach(data::set);var opts=RequestOptions.create().setForm(data).setHeader("Origin",origin).setMaxRedirects(0);if(useCrumb){var crumb=crumb(page);opts.setHeader(crumb.field(),crumb.value());}return page.request().post(base+app+"prepare",opts);}
    UUID prepared(Page page,int dataset){var response=prepare(page,selection(page,dataset),base,true);assertEquals(303,response.status());String path=response.headers().get("location");assertTrue(path.startsWith(app+"uploadForm?id="));return UUID.fromString(path.substring(path.indexOf('=')+1));}
    APIResponse multipart(Page page,UUID id,FormData data,String origin,boolean csrf){var opts=RequestOptions.create().setHeader("Origin",origin).setHeader("Idempotency-Key",UUID.randomUUID().toString()).setMultipart(data);if(csrf){var crumb=crumb(page);opts.setHeader(crumb.field(),crumb.value());}return page.request().post(base+app+"upload?id="+id,opts);}
    FormData csv(String name,byte[] content){return FormData.create().append("file",new FilePayload(name,"text/csv",content));}
    void hiddenJob(Page page,boolean hasRuns){
        String job=base+"/jenkins/job/di-csv-ingest/";var crumb=crumb(page);
        for(String path:List.of("build","buildWithParameters")){var r=page.request().post(job+path,RequestOptions.create().setHeader("Origin",base).setHeader(crumb.field(),crumb.value()).setForm(FormData.create().set("SUBMISSION_ID",UUID.randomUUID().toString())).setMaxRedirects(0));assertTrue(Set.of(403,404,405).contains(r.status()),"Direct build API must be denied: "+r.status());}
        for(String path:hasRuns?List.of("api/json","configure","ws/","1/consoleText","1/artifact/","1/replay"):List.of("api/json","configure","ws/")){int status=page.request().get(job+path,RequestOptions.create().setMaxRedirects(0)).status();assertTrue(Set.of(403,404).contains(status),"Technical job endpoint hidden: "+path+" status="+status);}
    }
    @Test void realBrowserStage()throws Exception{
        assertNotNull(base,"Use the isolated G3 harness");String stage=System.getenv("DI_G3_STAGE");Path fixture=Path.of(System.getenv("DI_G3_BROWSER_RESULT"));
        try(var playwright=Playwright.create();var browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))){
            var anna=login(browser,"anna",OidcLoginIT.ANNA);
            if("normal".equals(stage)){
                assertTrue(anna.getByText("Die CSV-Verarbeitung ist noch nicht freigegeben.").isVisible());assertEquals(0,anna.locator("form[action$='/prepare']").count());hiddenJob(anna,false);
                assertEquals(404,anna.request().get(base+"/jenkins/gretl-datenportal/",RequestOptions.create().setMaxRedirects(0)).status());
                System.out.println("PASS G30/G31/G32/G39 real PKCE subject, one protected technical entry, old route absent, no production probe");return;
            }
            if("upload".equals(stage)){
                assertEquals(2,anna.locator("form[action$='/prepare']").count());assertEquals(0,anna.locator("h3 img").count());assertEquals(null,anna.evaluate("window.diXss"));
                var ben=login(browser,"ben",OidcLoginIT.BEN);
                String firstName=CanonicalJson.JSON.readTree(anna.request().get(base+"/jenkins/me/api/json?tree=fullName").body()).path("fullName").asString();
                String secondName=CanonicalJson.JSON.readTree(ben.request().get(base+"/jenkins/me/api/json?tree=fullName").body()).path("fullName").asString();
                assertEquals("Gleicher Anzeigename",firstName);assertEquals(firstName,secondName);
                assertEquals(0,ben.locator("form[action$='/prepare']").count());
                assertEquals(403,prepare(ben,selection(anna,0),base,true).status());
                var bad=selection(anna,0);bad.put("datasetId",UUID.randomUUID().toString());assertEquals(403,prepare(anna,bad,base,true).status());
                bad=selection(anna,0);bad.put("issueId",selection(anna,1).get("issueId"));assertEquals(409,prepare(anna,bad,base,true).status());
                bad=selection(anna,0);bad.put("mode","append");assertEquals(409,prepare(anna,bad,base,true).status());
                assertEquals(403,prepare(anna,selection(anna,0),"https://wrong.example.invalid",true).status());assertEquals(403,prepare(anna,selection(anna,0),base,false).status());
                var first=prepared(anna,0);var second=prepared(anna,1);var waiting=prepared(anna,0);
                assertEquals(403,multipart(ben,first,csv("foreign.csv",CSV),base,true).status());
                assertEquals(403,multipart(anna,first,csv("wrong-origin.csv",CSV),"https://wrong.example.invalid",true).status());
                assertEquals(403,multipart(anna,first,csv("no-crumb.csv",CSV),base,false).status());
                assertEquals(413,multipart(anna,first,csv("large.csv","a".repeat(1025).getBytes(StandardCharsets.UTF_8)),base,true).status());
                assertEquals(400,multipart(anna,first,csv("../traversal.csv",CSV),base,true).status());
                assertEquals(400,multipart(anna,first,csv("one.csv",CSV).append("file",new FilePayload("two.csv","text/csv",CSV)),base,true).status());
                var control=multipart(anna,second,csv("data.csv",CSV),base,true);assertEquals(201,control.status(),()->control.text());
                anna.navigate(base+app+"uploadForm?id="+first);var file=Files.write(fixture.resolveSibling("<b>.csv"),CSV);anna.locator("input[type=file]").setInputFiles(file);var sent=anna.waitForResponse(response->response.url().contains("/data-delivery/upload?id="+first),()->anna.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("CSV einreichen")).click());assertTrue(("id="+first).equals(URI.create(sent.url()).getRawQuery()),"Only the bound upload ID may appear in the browser URL");assertEquals(201,sent.status(),()->sent.text());anna.waitForURL(url->url.contains("/data-delivery/status?id="+first));
                assertEquals(0,anna.locator("dd b").count());assertEquals(null,anna.evaluate("window.diXss"));
                for(UUID id:List.of(waiting))assertEquals(201,multipart(anna,id,csv("data.csv",CSV),base,true).status());
                assertEquals(403,ben.request().get(base+app+"status?id="+first).status());assertEquals(403,ben.request().get(base+app+"report?id="+first).status());
                Files.writeString(fixture,CanonicalJson.encode(Map.of("first",first,"second",second,"waiting",waiting,"inputSha256",CanonicalJson.sha256(CSV),"inputSize",CSV.length,"staleSelection",selection(login(browser,"anna",OidcLoginIT.ANNA),0))));
                System.out.println("PASS G30/G33/G34: real subjects and isolated eligibility; actual browser upload, size/CSRF/Origin/path/doublepart/XSS negatives, wrong target denied");return;
            }
            var ids=CanonicalJson.JSON.readTree(Files.readAllBytes(fixture));
            if("token-loss".equals(stage)){
                var crumb=crumb(anna);var cleared=anna.request().post(base+"/jenkins/di-g3-test-probe/forgetAccessToken",RequestOptions.create().setHeader(crumb.field(),crumb.value()).setHeader("Origin",base));
                assertEquals(204,cleared.status());
                var response=anna.navigate(base+app);assertEquals(401,response.status());
                assertTrue(anna.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Erneut über Keycloak anmelden")).isVisible());
                System.out.println("PASS G30 lost browser token requires a fresh login");return;
            }
            if("after-restart".equals(stage)){
                for(String name:List.of("first","second","waiting")){
                    String id=ids.path(name).asString();var status=anna.navigate(base+app+"status?id="+id);assertEquals(200,status.status());assertTrue(anna.getByText("Technischer Fehler; neuer Versuch möglich").isVisible());
                    var report=anna.request().get(base+app+"report?id="+id);assertEquals(200,report.status());assertTrue(report.text().contains("INPUT_VERIFIED"));assertFalse(report.text().contains("privateSpoolReference"));
                }
                hiddenJob(anna,true);
                assertTrue(Set.of(403,404).contains(anna.request().get(base+"/jenkins/credentials/store/system/domain/_/credential/di-ingestion-executor-client/api/json").status()));
                System.out.println("PASS G32/G35/G37: persisted deliveries after restart, safe own reports, technical build/replay/log/workspace/artifacts denied");return;
            }
            if("revoked".equals(stage)){
                var old=ids.path("staleSelection");var input=new HashMap<String,String>();old.properties().forEach(e->input.put(e.getKey(),e.getValue().asString()));assertEquals(403,prepare(anna,input,base,true).status());
                assertEquals(0,anna.locator("form[action$='/prepare']").count());System.out.println("PASS G34 fresh rights after rendered UI; old selection is rejected");return;
            }
            fail("Unknown isolated browser stage");
        }
    }
}
