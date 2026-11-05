package ch.so.agi.dateninfrastruktur.admin;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Final single-Compose journey; only the explicitly namespaced fixture is changed. */
class ComposeAcceptanceIT {
    final ObjectMapper json=new ObjectMapper();
    final Path fixturePath=Path.of(Objects.requireNonNull(System.getenv("DI_COMPOSE_FIXTURE")));
    final Map<String,Object> fixture=read(fixturePath);
    final List<Map<String,Object>> files=(List<Map<String,Object>>)fixture.get("files");
    final String gateway=System.getenv("DI_COMPOSE_GATEWAY"),issuer=System.getenv("DI_COMPOSE_ISSUER"),password=System.getenv("DI_COMPOSE_DEMO_PASSWORD");
    final String slug=fixture.get("prefix")+"-restricted",title=fixture.get("prefix")+" sichtbar geändert";
    final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    Map<String,Object> read(Path path){try{return json.readValue(Files.readString(path),Map.class);}catch(Exception e){throw new RuntimeException(e);}}
    String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    HttpResponse<byte[]> get(String path,String method,String token,Map<String,String> headers)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(gateway+path)).timeout(Duration.ofSeconds(15)).method(method,HttpRequest.BodyPublishers.noBody());
        if(token!=null)b.header("Authorization","Bearer "+token);headers.forEach(b::header);return http.send(b.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    void status(int expected,HttpResponse<byte[]> r){assertEquals(expected,r.statusCode());assertEquals("no-store",r.headers().firstValue("Cache-Control").orElse(""));}
    void bytes(Map<String,Object> file,String token)throws Exception{
        var r=get(file.get("path").toString(),"GET",token,Map.of());status(200,r);
        assertEquals(((Number)file.get("size")).longValue(),r.body().length);
        assertEquals(file.get("sha256"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(r.body())));
    }
    void login(Page p,String path,String user){p.navigate(gateway+path);p.locator("#username").fill(user);p.locator("#password").fill(password);p.locator("#kc-login").click();p.waitForURL(gateway+"/**");}
    void submit(Locator form){form.locator("button[type=submit]").click();}
    String token(Browser browser,String user)throws Exception{
        // Use the normal confidential standard-flow client, with PKCE. The test
        // intercepts its own registered callback before APISIX consumes the code.
        String verifier=UUID.randomUUID().toString()+UUID.randomUUID(),state=UUID.randomUUID().toString();
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String redirect=gateway+"/oidc/callback";var callback=new AtomicReference<String>();
        try(var context=browser.newContext()){
            context.route("**/*",r->{
                if(r.request().method().equals("POST") && URI.create(r.request().url()).getPath().endsWith("/login-actions/authenticate")){
                    // Redirected requests are not independently routed by
                    // Playwright. Capture Location from the login response,
                    // before the callback can be consumed by another session.
                    var response=r.fetch(new Route.FetchOptions().setMaxRedirects(0));
                    try{String location=response.headers().get("location");
                        if(location!=null && location.startsWith(redirect+"?")){callback.set(location);r.fulfill(new Route.FulfillOptions().setStatus(200).setBody("Code captured for this test session"));}
                        else r.fulfill(new Route.FulfillOptions().setResponse(response));
                    }finally{response.dispose();}
                }else r.resume();
            });
            var page=context.newPage();page.navigate(issuer+"/protocol/openid-connect/auth?client_id=management-gateway&response_type=code&scope=openid&redirect_uri="+enc(redirect)+"&state="+enc(state)+"&code_challenge_method=S256&code_challenge="+enc(challenge));
            page.locator("#username").fill(user);page.locator("#password").fill(password);page.locator("#kc-login").click();
            try{page.waitForCondition(()->callback.get()!=null);}catch(com.microsoft.playwright.TimeoutError e){throw new AssertionError("Standard-flow callback not observed; page path="+URI.create(page.url()).getPath()+"; title="+page.title()+"; login errors="+page.locator("#input-error,.alert-error,#kc-error-message").allTextContents());}
            var params=new HashMap<String,String>();for(String pair:URI.create(callback.get()).getRawQuery().split("&")){var parts=pair.split("=",2);params.put(parts[0],URLDecoder.decode(parts[1],StandardCharsets.UTF_8));}
            assertEquals(state,params.get("state"));assertNotNull(params.get("code"));
            String body="grant_type=authorization_code&client_id=management-gateway&client_secret="+enc(System.getenv("DI_COMPOSE_OIDC_SECRET"))+"&redirect_uri="+enc(redirect)+"&code_verifier="+enc(verifier)+"&code="+enc(params.get("code"));
            var response=http.send(HttpRequest.newBuilder(URI.create(issuer+"/protocol/openid-connect/token")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode(),"Normal standard-flow token exchange");String jwt=json.readValue(response.body(),Map.class).get("access_token").toString();
            var claims=json.readValue(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),Map.class);assertEquals(issuer,claims.get("iss"));assertEquals(user.equals("anna")?OidcLoginIT.ANNA:OidcLoginIT.BEN,claims.get("sub"));return jwt;
        }
    }
    void rawInvalid(String path)throws Exception{
        var uri=URI.create(gateway);try(var s=new Socket()){s.connect(new InetSocketAddress(uri.getHost(),uri.getPort()),3000);s.setSoTimeout(15000);s.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: "+uri.getRawAuthority()+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));assertTrue(new String(s.getInputStream().readAllBytes(),StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 400 "),"Ambiguous raw path accepted");}
    }
    @Test void finalNormalComposeJourney()throws Exception{
        assertTrue(fixture.get("prefix").toString().startsWith("maf-f4-"),"Never mutate an existing user dataset");
        try(var pw=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC","1")));var browser=pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));var applicant=browser.newContext();var managerContext=browser.newContext()){
            String anna=token(browser,"anna"),ben=token(browser,"ben");
            Path sensitive=fixturePath.resolveSibling("f4-tokens.json");Files.writeString(sensitive,json.writeValueAsString(Map.of("anna",anna,"ben",ben)));Files.setPosixFilePermissions(sensitive,PosixFilePermissions.fromString("rw-------"));
            var restricted=files.stream().filter(f->f.get("kind").equals("restricted")).toList();var open=files.stream().filter(f->f.get("kind").equals("open")).findFirst().orElseThrow();String path=restricted.getFirst().get("path").toString();
            if("true".equals(System.getenv("DI_COMPOSE_VERIFY_ONLY"))){
                for(var file:restricted){status(403,get(file.get("path").toString(),"GET",anna,Map.of()));bytes(file,ben);}bytes(open,null);
                var manager=managerContext.newPage();login(manager,"/management/datasets/"+fixture.get("restricted")+"/audit","claudia");assertTrue(manager.locator("main").innerText().contains("revoke"));
                assertTrue(new String(get("/datasets/"+slug,"GET",null,Map.of()).body(),StandardCharsets.UTF_8).contains(title));
                manager.navigate(gateway+"/management/datasets/"+fixture.get("restricted"));var edit=manager.locator("form[action$='/save-published']");edit.locator("input[name=title]").fill(title+" nach Recreate");submit(edit);manager.waitForURL("**/management/datasets/"+fixture.get("restricted"));
                assertTrue(new String(get("/datasets/"+slug,"GET",null,Map.of()).body(),StandardCharsets.UTF_8).contains(title+" nach Recreate"));
                System.out.println("PASS F19: fresh normal OIDC login, same identities, active/revoked rights, historical bytes and changed metadata after true recreate");return;
            }
            bytes(open,null);status(401,get(path,"GET",null,Map.of()));status(403,get(path,"GET",anna,Map.of()));status(403,get(path,"GET",ben,Map.of()));
            var manager=managerContext.newPage();login(manager,"/management/datasets/"+fixture.get("restricted"),"claudia");var edit=manager.locator("form[action$='/save-published']");edit.locator("input[name=title]").fill(title);submit(edit);manager.waitForURL("**/management/datasets/"+fixture.get("restricted"));
            var page=applicant.newPage();page.navigate(gateway+"/datasets/"+slug);assertTrue(page.locator("main").innerText().contains(title));page.locator("a[href^='/access/requests/new']").first().click();page.locator("#username").fill("anna");page.locator("#password").fill(password);page.locator("#kc-login").click();page.waitForURL("**/access/requests/new?*");
            var request=page.locator("form[action='/access/requests']");request.locator("textarea[name=reason]").fill("Own final Compose acceptance "+slug);submit(request);page.waitForURL("**/access/me");assertTrue(page.locator("main").innerText().contains("Offen"));
            manager.navigate(gateway+"/management/requests");var article=manager.locator("article").filter(new Locator.FilterOptions().setHasText(title));assertEquals(1,article.count());var approve=article.locator("form[action$='/approve']");approve.locator("textarea[name=reason]").fill("Own acceptance approval");submit(approve);manager.waitForURL("**/management/requests");
            for(var file:restricted)bytes(file,anna);status(403,get(path,"GET",ben,Map.of()));
            page.navigate(gateway+"/datasets/"+slug);var link=page.locator("a[href^='/downloads/']").first();var downloaded=page.waitForDownload(()->link.click());assertTrue(Files.size(downloaded.path())>0);
            var original=get(path,"GET",anna,Map.of());String etag=original.headers().firstValue("ETag").orElseThrow();var head=get(path,"HEAD",anna,Map.of());status(200,head);assertEquals(0,head.body().length);assertEquals(original.headers().firstValue("content-length"),head.headers().firstValue("content-length"));
            var range=get(path,"GET",anna,Map.of("Range","bytes=2-7"));status(206,range);assertArrayEquals(Arrays.copyOfRange(original.body(),2,8),range.body());status(304,get(path,"GET",anna,Map.of("If-None-Match",etag)));status(416,get(path,"GET",anna,Map.of("Range","bytes=999999-")));
            status(403,get(path,"GET",ben,Map.of("X-Verified-Subject",OidcLoginIT.ANNA,"X-Forwarded-Uri",open.get("path").toString(),"X-DI-Gateway-Credential","forged")));assertEquals(403,get("/internal/authorization/check","GET",anna,Map.of()).statusCode());
            for(String bad:List.of(path+"?x=1",path.replace("/pilot","/%70ilot"),path.replace("/pilot","/%2e%2e/pilot"),path.replace("downloads/","downloads//"),path+"/extra"))rawInvalid(bad);
            manager.navigate(gateway+"/management/datasets/"+fixture.get("restricted")+"/grants");var revoke=manager.locator("form[action$='/revoke']");assertEquals(1,revoke.count());revoke.locator("textarea[name=reason]").fill("Same still-valid target JWT must lose access");submit(revoke);manager.waitForURL("**/grants");
            var claims=json.readValue(Base64.getUrlDecoder().decode(anna.split("\\.")[1]),Map.class);assertTrue(((Number)claims.get("exp")).longValue()>Instant.now().getEpochSecond(),"Revocation must use a still-valid exact same JWT");
            for(var file:restricted){status(403,get(file.get("path").toString(),"GET",anna,Map.of()));status(403,get(file.get("path").toString(),"HEAD",anna,Map.of()));}status(403,get(path,"GET",anna,Map.of("If-None-Match",etag)));
            manager.navigate(gateway+"/management/datasets/"+fixture.get("restricted")+"/grants?search="+OidcLoginIT.BEN);var direct=manager.locator("form[action='/management/datasets/"+fixture.get("restricted")+"/grants']");direct.locator("textarea[name=reason]").fill("Owned active grant retained across recreate");submit(direct);manager.waitForURL("**/grants");for(var file:restricted)bytes(file,ben);
            manager.navigate(gateway+"/management/datasets/"+fixture.get("restricted")+"/audit");String audit=manager.locator("main").innerText();for(String action:List.of("approve","revoke","save_published"))assertTrue(audit.contains(action),"Audited action missing: "+action);
            System.out.println("PASS F17/F18: normal PKCE codeflow; visible metadata→request→approval→historical bytes→same valid JWT revocation→audit; HEAD/Range/ETag, spoof/path/internal boundaries");
        }
    }
}
