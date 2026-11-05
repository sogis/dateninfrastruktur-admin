package ch.so.agi.dateninfrastruktur.admin;

import ch.so.agi.dateninfrastruktur.admin.security.ManagementJwtDecoder;
import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** G02 test-only receiver uses ADMIN's real cryptographic JWT decoder. No token leaves memory. */
class IngestionOidcSpikeIT {
    @Test void browserSessionYieldsSubjectBoundApiAccessToken() throws Exception {
        String issuer=System.getenv("G0_ISSUER");
        assertNotNull(issuer,"Run through STACK/scripts/test_ingestion_oidc_g0.py");
        JwtDecoder valid=ManagementJwtDecoder.create(issuer,issuer+"/protocol/openid-connect/certs","ingestion-api");
        JwtDecoder wrong=ManagementJwtDecoder.create(issuer,issuer+"/protocol/openid-connect/certs","unrelated-audience");
        AtomicInteger accepted=new AtomicInteger(),rejected=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("0.0.0.0",Integer.parseInt(System.getenv("G0_VERIFIER_PORT"))),0);
        server.createContext("/",exchange->{
            try {
                String header=exchange.getRequestHeaders().getFirst("Authorization");
                if(header==null || !header.startsWith("Bearer ")) {exchange.sendResponseHeaders(401,-1);return;}
                var decoder=exchange.getRequestURI().getPath().equals("/wrong-audience")?wrong:valid;
                var jwt=decoder.decode(header.substring(7));
                // All claims came from the verified signed token; no caller-supplied identity.
                String body="{\"sub\":\""+jwt.getSubject()+"\",\"iss\":\""+jwt.getIssuer()+"\"}";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);accepted.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } catch (Exception invalidToken) {rejected.incrementAndGet();exchange.sendResponseHeaders(401,-1);}
            finally {exchange.close();}
        });
        server.start();
        try(var playwright=Playwright.create();var browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            var context=browser.newContext();var page=context.newPage();
            String base=System.getenv("G0_JENKINS_URL");
            page.navigate(base+"securityRealm/commenceLogin");
            assertTrue(page.url().contains("code_challenge_method=S256"),"PKCE must be active");
            page.locator("#username").fill("g0-supplier");page.locator("#password").fill(System.getenv("G0_USER_PASSWORD"));
            page.locator("#kc-login").click();page.waitForURL(base+"**");
            var response=context.request().get(base+"g0-token-probe/");
            assertEquals(200,response.status());assertTrue(response.text().contains("\"subjectBound\":true"));
            assertEquals(401,context.request().get(base+"g0-token-probe/?mode=wrong-audience").status());
            assertEquals(403,context.request().get(base+"g0-token-probe/?mode=wrong-subject").status());
            assertEquals(2,accepted.get());assertEquals(1,rejected.get());
            System.out.println("PASS G02: real Keycloak PKCE browser flow, OicCredentials access token, ADMIN signature/issuer/audience validation, wrong audience and subject rejected; no tokens emitted");
        } finally {server.stop(0);}
    }
}
