package ch.so.agi.dateninfrastruktur.admin;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real two-gateway / two-authorization / private S3 journey. No direct host JDBC. */
class ManagedDownloadsIT {
    final ObjectMapper json=new ObjectMapper();
    final Path directory=Path.of(Objects.requireNonNull(System.getenv("DI_MANAGED_DIRECTORY")));
    final Map<String,Object> state=read(directory.resolve("identity/state.json")),fixture=read(directory.resolve("fixture.json"));
    final List<String> gateways=(List<String>)state.get("gateways");
    final List<Map<String,Object>> files=(List<Map<String,Object>>)fixture.get("files");
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    Map<String,Object> read(Path path){try{return json.readValue(Files.readString(path),Map.class);}catch(Exception e){throw new RuntimeException(e);}}
    String text(Map<String,Object> map,String key){return map.get(key).toString();}
    String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    HttpResponse<byte[]> request(String gateway,String path,String method,String token,Map<String,String> headers)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(gateway+path)).timeout(Duration.ofSeconds(12)).method(method,HttpRequest.BodyPublishers.noBody());
        if(token!=null)b.header("Authorization","Bearer "+token);headers.forEach(b::header);
        return http.send(b.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    String token(String user)throws Exception{
        var body="grant_type=password&client_id=management-test&username="+encode(user)+"&password="+encode(text(state,"password"));
        var r=http.send(HttpRequest.newBuilder(URI.create(text(state,"keycloak")+"/realms/dateninfrastruktur/protocol/openid-connect/token")).timeout(Duration.ofSeconds(10)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,r.statusCode(),"Real Keycloak access token issuance");return text(json.readValue(r.body(),Map.class),"access_token");
    }
    void status(int expected,HttpResponse<byte[]> response){assertEquals(expected,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));}
    void bytes(Map<String,Object> file,HttpResponse<byte[]> response)throws Exception{
        status(200,response);assertEquals(((Number)file.get("size")).longValue(),response.body().length);
        assertEquals(file.get("sha256"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body())));
    }
    String control(String... arguments)throws Exception{
        var command=new ArrayList<String>(List.of("python3","scripts/managed_control.py"));command.addAll(List.of(arguments));
        var p=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(0,p.waitFor(),"Owned control failed: "+String.join(" ",arguments));return output.strip();
    }
    void login(Page page,String gateway,String path,String user){
        page.navigate(gateway+path);page.locator("#username").fill(user);page.locator("#password").fill(text(state,"password"));page.locator("#kc-login").click();page.waitForURL(gateway+"/**");
    }
    void submit(Locator form){form.locator("button[type=submit]").click();}
    List<Map<String,Object>> restricted(){return files.stream().filter(f->f.get("kind").equals("restricted")).toList();}
    void access(Page manager,String level){
        manager.navigate(gateways.getFirst()+"/management/datasets/"+fixture.get("restricted"));
        var form=manager.locator("form[action$='/access']");form.locator("select[name=level]").selectOption(level);form.locator("textarea[name=reason]").fill("MA-E root access "+level);submit(form);manager.waitForURL("**/management/datasets/"+fixture.get("restricted"));
    }
    void rawInvalid(String gateway,String path)throws Exception{
        var uri=URI.create(gateway);
        try(var socket=new Socket()){
            socket.connect(new InetSocketAddress(uri.getHost(),uri.getPort()),3000);socket.setSoTimeout(12000);
            socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: "+uri.getRawAuthority()+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            var response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.ISO_8859_1);assertTrue(response.startsWith("HTTP/1.1 400 "),"Raw ambiguous path must be 400: "+path);assertTrue(response.toLowerCase().contains("cache-control: no-store"));
        }
    }
    @Test void M26throughM33_realManagedDownloadJourney()throws Exception{
        String annaToken=token("anna"),benToken=token("ben");
        Files.writeString(directory.resolve("test-sensitive.json"),json.writeValueAsString(Map.of("anna",annaToken,"ben",benToken)));directory.resolve("test-sensitive.json").toFile().setReadable(false,false);directory.resolve("test-sensitive.json").toFile().setReadable(true,true);
        String restrictedPath=text(restricted().getFirst(),"path");var open=files.stream().filter(f->f.get("kind").equals("open")).findFirst().orElseThrow();
        for(String g:gateways){
            bytes(open,request(g,text(open,"path"),"GET",null,Map.of()));
            status(401,request(g,restrictedPath,"GET",null,Map.of()));status(403,request(g,restrictedPath,"GET",annaToken,Map.of()));status(403,request(g,restrictedPath,"GET",benToken,Map.of()));
            status(401,request(g,text(open,"path"),"GET","not-a-token",Map.of()));
            status(403,request(g,restrictedPath,"GET",benToken,Map.of("X-Forwarded-Uri",text(open,"path"),"X-Forwarded-Method","GET","X-DI-Gateway-Credential","forged","X-Verified-Subject",OidcLoginIT.ANNA)));
            assertEquals(403,request(g,"/internal/authorization/check","GET",annaToken,Map.of()).statusCode());
        }
        control("ports");control("storage");assertEquals(200,read(directory.resolve("canary-evidence.json")).get("previousStatus"));
        System.out.println("M26/M29 PASS: real private/open bytes, anonymous restricted401, valid-token403, private ports/S3/old-website boundaries");
        try(var playwright=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC","1")));var browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));var anna=browser.newContext();var claudia=browser.newContext()){
            var page=anna.newPage();page.navigate(gateways.getFirst()+"/?q=MA-E");assertTrue(page.locator("main").innerText().contains("MA-E Pilot restricted"));
            page.navigate(gateways.getFirst()+"/datasets/ma-e-restricted");assertTrue(page.locator("a[href^='/downloads/']").count()>0,"Managed restricted links must remain usable after login/grant");
            page.locator("a[href^='/access/requests/new']").first().click();page.locator("#username").fill("anna");page.locator("#password").fill(text(state,"password"));page.locator("#kc-login").click();page.waitForURL("**/access/requests/new?*");
            var request=page.locator("form[action='/access/requests']");request.locator("textarea[name=reason]").fill("MA-E durchgängiger Browserantrag");submit(request);page.waitForURL("**/access/me");assertTrue(page.locator("main").innerText().contains("Offen"));
            var manager=claudia.newPage();login(manager,gateways.getFirst(),"/management/requests","claudia");var approval=manager.locator("form[action$='/approve']").first();approval.locator("textarea[name=reason]").fill("MA-E freigegeben");
            // Actual form created by Admin A is accepted by Admin B with the same stateless CSRF cookie and verified Origin A.
            approval.evaluate("(form, origin) => form.action = origin + form.getAttribute('action')",gateways.get(1));submit(approval);manager.waitForURL(gateways.get(1)+"/management/requests");
            assertEquals(200,claudia.request().get(gateways.getFirst()+"/management").status(),"Same OIDC session across gateways");
            for(String g:gateways)for(var f:restricted())bytes(f,request(g,text(f,"path"),"GET",annaToken,Map.of()));
            for(String g:gateways)status(403,request(g,restrictedPath,"GET",benToken,Map.of()));
            page.navigate(gateways.get(1)+"/datasets/ma-e-restricted");var link=page.locator("a[href^='/downloads/']").first();String href=link.getAttribute("href");var downloaded=page.waitForDownload(()->link.click());
            var selected=restricted().stream().filter(f->f.get("path").equals(href)).findFirst().orElseThrow();assertEquals(selected.get("sha256"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(downloaded.path()))));
            var original=request(gateways.getFirst(),restrictedPath,"GET",annaToken,Map.of());String etag=original.headers().firstValue("etag").orElseThrow();
            var head=request(gateways.getFirst(),restrictedPath,"HEAD",annaToken,Map.of());status(200,head);assertEquals(0,head.body().length);assertEquals(original.headers().firstValue("content-length"),head.headers().firstValue("content-length"));
            assertEquals("bytes",original.headers().firstValue("accept-ranges").orElseThrow());assertTrue(original.headers().firstValue("content-disposition").orElseThrow().contains("attachment"));assertTrue(original.headers().firstValue("content-type").orElseThrow().startsWith("text/csv"));
            var range=request(gateways.get(1),restrictedPath,"GET",annaToken,Map.of("Range","bytes=2-7"));status(206,range);assertArrayEquals(Arrays.copyOfRange(original.body(),2,8),range.body());assertTrue(range.headers().firstValue("content-range").orElseThrow().startsWith("bytes 2-7/"));
            status(304,request(gateways.getFirst(),restrictedPath,"GET",annaToken,Map.of("If-None-Match",etag)));status(416,request(gateways.getFirst(),restrictedPath,"GET",annaToken,Map.of("Range","bytes=999999-")));
            for(String g:gateways){
                status(404,request(g,restrictedPath.replace("pilot-v1.csv","wrong.csv"),"GET",annaToken,Map.of()));status(405,request(g,restrictedPath,"POST",annaToken,Map.of()));
                for(String bad:List.of(restrictedPath+"?x=1",restrictedPath.replace("/pilot","/%00pilot"),restrictedPath.replace("/pilot","/%FFpilot"),restrictedPath+"/extra",restrictedPath.replace("downloads/","downloads//"),restrictedPath.replace("/pilot","/%70ilot"),"/downloads/../",restrictedPath.replace("/pilot","/%252e%252e/pilot"),restrictedPath.replace("/pilot","/../pilot"),restrictedPath.replace("/pilot","/%2e%2e/pilot"),restrictedPath.replace("/pilot","/pilot\\")))rawInvalid(g,bad);
            }
            System.out.println("M27/M28/M31/M32 partial PASS: same JWT all historical formats, real cross-instance browser/CSRF, download/HEAD/Range/ETag and raw paths");
            manager.navigate(gateways.getFirst()+"/management/datasets/"+fixture.get("restricted"));var edit=manager.locator("form[action$='/save-published']");
            var fields=(Map<String,String>)edit.evaluate("form => Object.fromEntries(new FormData(form))");var forged=FormData.create();fields.forEach(forged::set);
            assertEquals(403,claudia.request().post(gateways.getFirst()+edit.getAttribute("action"),RequestOptions.create().setForm(forged).setHeader("Origin","http://evil.example.invalid")).status(),"Valid CSRF with foreign Origin must fail");
            edit.locator("input[name=title]").fill("MA-E Metadaten sofort aktuell");submit(edit);manager.waitForURL("**/management/datasets/"+fixture.get("restricted"));
            for(String g:gateways)assertTrue(new String(request(g,"/datasets/ma-e-restricted","GET",null,Map.of()).body(),StandardCharsets.UTF_8).contains("MA-E Metadaten sofort aktuell"));
            manager.navigate(gateways.getFirst()+"/management/datasets/"+fixture.get("restricted")+"/grants");var revoke=manager.locator("form[action$='/revoke']").first();revoke.locator("textarea[name=reason]").fill("MA-E Entzug mit gleichem JWT");submit(revoke);manager.waitForURL("**/grants");
            for(String g:gateways)for(var f:restricted()){
                status(403,request(g,text(f,"path"),"GET",annaToken,Map.of()));status(403,request(g,text(f,"path"),"HEAD",annaToken,Map.of()));status(403,request(g,text(f,"path"),"GET",annaToken,Map.of("If-None-Match",etag)));
            }
            access(manager,"open");for(String g:gateways)for(var f:restricted())bytes(f,request(g,text(f,"path"),"GET",null,Map.of()));
            access(manager,"restricted");for(String g:gateways)for(var f:restricted()){status(401,request(g,text(f,"path"),"GET",null,Map.of()));status(403,request(g,text(f,"path"),"GET",annaToken,Map.of()));}
            manager.navigate(gateways.getFirst()+"/management/datasets/"+fixture.get("restricted")+"/grants?search="+OidcLoginIT.BEN);
            var direct=manager.locator("form[action='/management/datasets/"+fixture.get("restricted")+"/grants']");direct.locator("textarea[name=reason]").fill("MA-E Direktvergabe ohne Antrag");submit(direct);manager.waitForURL("**/grants");
            for(String g:gateways)for(var f:restricted()){bytes(f,request(g,text(f,"path"),"GET",benToken,Map.of()));status(403,request(g,text(f,"path"),"GET",annaToken,Map.of()));}
            manager.navigate(gateways.getFirst()+"/management/datasets/"+fixture.get("restricted")+"/audit");String audit=manager.locator("main").innerText();assertTrue(audit.contains("approve"));assertTrue(audit.contains("revoke"));assertTrue(audit.contains(OidcLoginIT.CLAUDIA));
            System.out.println("M27/M32 PASS: request→decision→file→revoke→audit, root open↔restricted affects every historical file, same valid JWT");
            control("probe-on");try{
                for(String g:gateways)bytes(open,request(g,text(open,"path"),"GET",annaToken,Map.of("Cookie","unrelated=probe","X-Access-Token","probe","X-ID-Token","probe","X-Userinfo","probe","X-DI-Gateway-Credential","probe")));
                control("probe-result");
            }finally{control("probe-off");}
            for(String dependency:List.of("admin-a","database","garage","origin")){
                control("pause",dependency);try{
                    var r=request(gateways.getFirst(),text(open,"path"),"GET",null,Map.of());
                    if(Set.of("garage","origin").contains(dependency))assertTrue(r.statusCode()>=500 && r.statusCode()<600,"S3/Origin outage must return a server error, never download200");else status(503,r);
                    if(dependency.equals("database"))status(503,request(gateways.get(1),text(open,"path"),"GET",null,Map.of()));
                    if(dependency.equals("admin-a"))bytes(open,request(gateways.get(1),text(open,"path"),"GET",null,Map.of()));
                }finally{control("resume",dependency);}
                for(String g:gateways)bytes(open,request(g,text(open,"path"),"GET",null,Map.of()));
            }
            control("logs");String before=control("snapshot");control("restart");String after=control("snapshot");assertEquals(before,after,"Data/IDs/grants/decisions/audit and secrets must survive restart unchanged");
            assertEquals(200,claudia.request().get(gateways.get(1)+"/management").status(),"Browser session persists restart");
            for(String g:gateways){bytes(open,request(g,text(open,"path"),"GET",null,Map.of()));status(403,request(g,restrictedPath,"GET",annaToken,Map.of()));bytes(restricted().getFirst(),request(g,restrictedPath,"GET",benToken,Map.of()));}
            control("logs");System.out.println("M30/M33 PASS: credential-free Origin, Authz/DB/S3 failure closed, both gateways, persistent restart and OIDC session");
        }
    }
}
