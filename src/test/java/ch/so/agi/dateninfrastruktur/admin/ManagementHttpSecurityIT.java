package ch.so.agi.dateninfrastruktur.admin;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class ManagementHttpSecurityIT {
    static final HttpClient HTTP=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static Map<?,?> tokens(String user)throws Exception{
        var issuer=System.getenv("DI_TEST_OIDC_ISSUER");String body="grant_type=password&client_id=management-test&scope=openid&username="+user+"&password="+URLEncoder.encode(System.getenv("DI_TEST_DEMO_PASSWORD"),StandardCharsets.UTF_8);
        var response=HTTP.send(HttpRequest.newBuilder(URI.create(issuer+"/protocol/openid-connect/token")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),"Own Keycloak test-client token flow");return new ObjectMapper().readValue(response.body(),Map.class);
    }
    HttpRequest.Builder request(String path,String token){var r=HttpRequest.newBuilder(URI.create(System.getenv("DI_TEST_BACKEND")+path));if(token!=null)r.header("Authorization","Bearer "+token);return r;}
    int send(HttpRequest.Builder r)throws Exception{return HTTP.send(r.build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
    String encoded(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    @Test void realAccessTokenCsrfSpoofedIdentityAndInternalBoundary()throws Exception{
        var pair=tokens("anna");String token=pair.get("access_token").toString(),idToken=pair.get("id_token").toString();
        try(var f=new ManagementFixture()){
            var root=f.published();var file=DownloadAuthorizationRepositoryIT.file(f,root,1);var path="/downloads/"+file+"/data.csv";
            assertEquals(401,send(request("/access/me",null).header("X-User","claudia").header("X-Verified-Subject",OidcLoginIT.CLAUDIA).GET()));
            assertEquals(401,send(request("/access/me",idToken).GET()));assertEquals(401,send(request("/access/me",token.substring(0,token.length()-8)+"AAAAAAAA").GET()));
            assertEquals(403,send(request("/management/datasets/"+root+"/grants",token).header("X-User",f.manager.subject()).GET()));
            String body="dataset="+root+"&reason=HTTP-Test&commandId="+UUID.randomUUID();
            assertEquals(403,send(request("/access/requests",token).header("Origin",System.getenv("DI_TEST_GATEWAY")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))));
            var form=HTTP.send(request("/access/requests/new?dataset="+root,token).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,form.statusCode());
            var matcher=java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(form.body());assertTrue(matcher.find());String csrf=matcher.group(1);
            String cookie=form.headers().allValues("set-cookie").stream().filter(s->s.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";",2)[0];
            assertEquals(403,send(request("/access/requests",token).header("Cookie",cookie).header("Origin","https://evil.example.invalid").header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body+"&_csrf="+encoded(csrf)))));
            assertEquals(403,send(request("/access/requests",token).header("Cookie",cookie).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body+"&_csrf="+encoded(csrf)))));
            assertEquals(400,send(request("/access/requests",token).header("Cookie",cookie).header("Origin",System.getenv("DI_TEST_GATEWAY")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body+"&until=not-a-date&_csrf="+encoded(csrf)))));
            assertEquals(302,send(request("/access/requests",token).header("Cookie",cookie).header("Origin",System.getenv("DI_TEST_GATEWAY")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body+"&_csrf="+encoded(csrf)))));
            UUID requestId;
            try(var connection=f.admin.getConnection();var ps=connection.prepareStatement("SELECT r.t_ili_tid FROM di_management.accessrequest r JOIN di_management.dataset d ON d.t_id=r.dataset WHERE d.t_ili_tid=?")){ps.setObject(1,root);try(var rs=ps.executeQuery()){assertTrue(rs.next());requestId=rs.getObject(1,UUID.class);}}
            var decision=f.workflow.decide(f.manager,ManagementFixture.command("approve"),requestId,f.version("accessrequest",requestId),true,f.validity(),"HTTP-Freigabe");
            assertEquals(204,send(request("/internal/authorization/check",token).header("X-DI-Gateway-Credential",System.getenv("DI_TEST_GATEWAY_SECRET")).header("X-Forwarded-Uri",path).header("X-Forwarded-Method","GET").GET()));
            UUID grantId;
            try(var connection=f.admin.getConnection();var ps=connection.prepareStatement("SELECT g.t_ili_tid FROM di_management.accessgrant g JOIN di_management.accessdecision d ON d.t_id=g.grantingdecision WHERE d.t_ili_tid=?")){ps.setObject(1,decision);try(var rs=ps.executeQuery()){assertTrue(rs.next());grantId=rs.getObject(1,UUID.class);}}
            f.workflow.revoke(f.manager,ManagementFixture.command("revoke"),grantId,f.version("accessgrant",grantId),"HTTP-Entzug");
            assertEquals(403,send(request("/internal/authorization/check",token).header("X-DI-Gateway-Credential",System.getenv("DI_TEST_GATEWAY_SECRET")).header("X-Forwarded-Uri",path).header("X-Forwarded-Method","GET").GET()));
            assertEquals(405,send(request("/management/datasets/"+root+"/publish",token).GET()));
            assertEquals(403,send(request("/internal/authorization/check",null).header("X-Forwarded-Uri",path).header("X-Forwarded-Method","GET").GET()));
            assertEquals(401,send(request("/internal/authorization/check",null).header("X-DI-Gateway-Credential",System.getenv("DI_TEST_GATEWAY_SECRET")).header("X-Forwarded-Uri",path).header("X-Forwarded-Method","GET").GET()));
            assertEquals(400,send(request("/internal/authorization/check",token).header("X-DI-Gateway-Credential",System.getenv("DI_TEST_GATEWAY_SECRET")).header("X-Forwarded-Uri",path+"?override=1").header("X-Forwarded-Method","GET").GET()));
            assertEquals(405,send(request("/internal/authorization/check",token).header("X-DI-Gateway-Credential",System.getenv("DI_TEST_GATEWAY_SECRET")).header("X-Forwarded-Uri",path).header("X-Forwarded-Method","POST").GET()));
            assertEquals(403,HTTP.send(HttpRequest.newBuilder(URI.create(System.getenv("DI_TEST_GATEWAY")+"/internal/authorization/check")).header("X-DI-Gateway-Credential",System.getenv("DI_TEST_GATEWAY_SECRET")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}
