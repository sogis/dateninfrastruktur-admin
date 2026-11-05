package ch.so.agi.dateninfrastruktur.admin;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import ch.so.agi.dateninfrastruktur.admin.ingestion.CanonicalJson;

/** Real signed JWT -> real HTTP filter chains -> current PG; no mocked Authentication. */
public final class IngestionHttpG2IT {
    final HttpClient http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    static final String PLUGIN="g2-isolated-plugin-credential-000000000000000000000000";
    String base;RSAKey key;String subject,runner;
    String token(String issuer,String audience,String type,String client,String user,RSAKey signing,String role)throws Exception{
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject(user).audience(audience).issueTime(Date.from(Instant.now().minusSeconds(2))).expirationTime(Date.from(Instant.now().plusSeconds(120))).claim("typ",type).claim("azp",client).claim("resource_access",Map.of("di-ingestion-executor",Map.of("roles",role==null?List.of():List.of(role)))).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signing.getKeyID()).build(),claims);jwt.sign(new RSASSASigner(signing));return jwt.serialize();
    }
    HttpResponse<String> call(String path,String jwt,String credential,String method,String body,String spoof)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(10));if(jwt!=null)request.header("Authorization","Bearer "+jwt);if(credential!=null)request.header("X-DI-Plugin-Credential",credential);if(spoof!=null)request.header("X-User",spoof);
        if(method.equals("POST"))request.header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(body));else request.GET();return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void cryptographicProfilesAndStrictHttp()throws Exception{
        key=new RSAKeyGenerator(2048).keyID("g2-key").generate();var forged=new RSAKeyGenerator(2048).keyID("g2-key").generate();
        var jwks=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);jwks.createContext("/keys",exchange->{byte[] bytes=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});jwks.start();
        try(var x=new IngestionG2IT.Fixture()){
            String issuer=x.f.anna.issuer();subject=x.f.anna.subject();runner=x.runner.subject();
            var props=new HashMap<String,Object>();props.put("server.address","127.0.0.1");props.put("server.port",0);props.put("di.ingestion.enabled",true);props.put("di.ingestion.plugin-secret",PLUGIN);props.put("di.ingestion.lease-secret","g2-private-lease-secret-0000000000000000000000000");props.put("di.ingestion.jenkins-instance",x.instance);
            props.put("di.web.enabled",true);props.put("di.web.issuer",issuer);props.put("di.web.jwks-uri","http://127.0.0.1:"+jwks.getAddress().getPort()+"/keys");props.put("di.web.audience","management");props.put("di.web.gateway-secret","g2-private-gateway-000000000000000000000000");props.put("di.web.public-origin","http://127.0.0.1:12345");
            for(var role:Map.of("writer","WRITER","authorization","AUTHZ","audit","AUDIT").entrySet()){
                String prefix="di.management."+role.getKey()+".";props.put(prefix+"jdbc-url",System.getenv("DI_TEST_URL"));props.put(prefix+"username",switch(role.getValue()){case "WRITER"->"di_writer";case "AUTHZ"->"di_authz";default->"di_audit_reader";});props.put(prefix+"password",System.getenv("DI_TEST_"+role.getValue()+"_PASSWORD"));
            }
            try(var app=new SpringApplicationBuilder(ManagementApplication.class).properties(props).run()){
                base="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
                String user=token(issuer,"di-ingestion-user","Bearer","jenkins-delivery",subject,key,null),service=token(issuer,"di-ingestion-executor","Bearer","di-ingestion-executor",runner,key,"dispatch");
                assertEquals(200,call("/internal/ingestion/datasets",user,PLUGIN,"GET",null,null).statusCode());
                assertEquals(403,call("/internal/ingestion/datasets",user,null,"GET",null,null).statusCode());assertEquals(403,call("/internal/ingestion/datasets",user,"wrong","GET",null,null).statusCode());
                assertEquals(401,call("/internal/ingestion/datasets",null,PLUGIN,"GET",null,subject).statusCode());
                for(String bad:List.of(token(issuer,"other","Bearer","jenkins-delivery",subject,key,null),token("https://wrong.example.invalid","di-ingestion-user","Bearer","jenkins-delivery",subject,key,null),token(issuer,"di-ingestion-user","ID","jenkins-delivery",subject,key,null),token(issuer,"di-ingestion-user","Bearer","jenkins-delivery",subject,forged,null)))assertEquals(401,call("/internal/ingestion/datasets",bad,PLUGIN,"GET",null,null).statusCode());
                assertEquals(403,call("/internal/ingestion/datasets",token(issuer,"di-ingestion-user","Bearer","other-client",subject,key,null),PLUGIN,"GET",null,null).statusCode());
                assertEquals(401,call("/internal/ingestion/dispatch/reconciliation",user,PLUGIN,"GET",null,null).statusCode());assertEquals(401,call("/internal/ingestion/datasets",service,PLUGIN,"GET",null,null).statusCode());assertEquals(200,call("/internal/ingestion/dispatch/reconciliation",service,null,"GET",null,null).statusCode());
                assertEquals(403,call("/internal/ingestion/dispatch/reconciliation",token(issuer,"di-ingestion-executor","Bearer","di-ingestion-executor",runner,key,"execute"),null,"GET",null,null).statusCode());
                String digest=x.f.unit.read(ctx->x.configs.resolve(ctx,x.dataset).digest());String body=CanonicalJson.encode(new ch.so.agi.dateninfrastruktur.admin.ingestion.SubmissionService.Create(x.dataset,x.issue,"replace",digest,"HTTP"));
                assertEquals(201,call("/internal/ingestion/submissions",user,PLUGIN,"POST",body,null).statusCode());
                assertEquals(400,call("/internal/ingestion/submissions",user,PLUGIN,"POST",body.substring(0,body.length()-1)+",\"actor\":\"other\"}",null).statusCode());assertEquals(400,call("/internal/ingestion/submissions",user,PLUGIN,"POST",body+"{}",null).statusCode());assertEquals(400,call("/internal/ingestion/submissions",user,PLUGIN,"POST",body.substring(0,body.length()-1)+",\"mode\":\"append\"}",null).statusCode());
                assertEquals(413,call("/internal/ingestion/submissions",user,PLUGIN,"POST"," ".repeat(65537),null).statusCode());
                var ben=x.f.unit.read(ctx->new ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository().requireActive(ctx,x.f.ben).getTIliTid());
                x.f.workflow.grantDirect(x.f.manager,IngestionG2IT.command("direct_grant"),x.dataset,x.version("dataset",x.dataset),ben,x.f.validity(),"Read only, no delivery provenance");
                String foreign=token(issuer,"di-ingestion-user","Bearer","jenkins-delivery",x.f.ben.subject(),key,null);assertEquals(403,call("/internal/ingestion/submissions",foreign,PLUGIN,"POST",body,subject).statusCode());
                var id=x.ready();assertEquals(403,call("/internal/ingestion/submissions/"+id,foreign,PLUGIN,"GET",null,subject).statusCode());var status=call("/internal/ingestion/submissions/"+id,user,PLUGIN,"GET",null,null);assertEquals(200,status.statusCode());for(String forbidden:List.of("spool","leaseSecret","issuer_snapshot","jenkins_run_id","configuration_snapshot"))assertFalse(status.body().contains(forbidden));
                String manager=token(issuer,"management","Bearer","management-client",x.f.manager.subject(),key,null),supplierBrowser=token(issuer,"management","Bearer","management-client",subject,key,null);
                String route="/management/delivery/offices/"+x.f.office;var form=call(route+"?search="+subject,manager,null,"GET",null,null);assertEquals(200,form.statusCode());assertTrue(form.body().contains("künftigen Datensätze"));assertEquals(403,call(route,supplierBrowser,null,"GET",null,null).statusCode());
                var csrf=java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(form.body());assertTrue(csrf.find());String cookie=form.headers().allValues("set-cookie").stream().filter(v->v.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";",2)[0];
                String grantBody="commandId="+UUID.randomUUID()+"&beneficiary="+x.userId+"&expectedVersion="+x.version("office",x.f.office)+"&reason=UI-Test&officeConsent=true&_csrf="+URLEncoder.encode(csrf.group(1),StandardCharsets.UTF_8);
                var grantRequest=HttpRequest.newBuilder(URI.create(base+route+"/grant")).header("Authorization","Bearer "+manager).header("Cookie",cookie).header("Content-Type","application/x-www-form-urlencoded");
                assertEquals(403,http.send(grantRequest.copy().header("Origin","https://wrong.example.invalid").POST(HttpRequest.BodyPublishers.ofString(grantBody)).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(403,http.send(grantRequest.copy().header("Origin","http://127.0.0.1:12345").POST(HttpRequest.BodyPublishers.ofString(grantBody.replaceAll("&_csrf=.*$",""))).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(302,http.send(grantRequest.copy().header("Origin","http://127.0.0.1:12345").POST(HttpRequest.BodyPublishers.ofString(grantBody)).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(403,call("/management/delivery/datasets/"+x.dataset+"/configuration",supplierBrowser,null,"GET",null,null).statusCode());
                assertEquals(403,call("/internal/ingestion/dispatch/reconciliation",token(issuer,"di-ingestion-executor","Bearer","di-ingestion-executor",subject,key,"dispatch"),null,"GET",null,null).statusCode());
                assertEquals(403,call("/internal/ingestion/datasets",token(issuer,"di-ingestion-user","Bearer","jenkins-delivery",runner,key,null),PLUGIN,"GET",null,null).statusCode());
                assertEquals(405,call("/internal/ingestion/submissions/"+id+"/seal",user,PLUGIN,"GET",null,null).statusCode());
                jwks.stop(0);var unavailable=new RSAKeyGenerator(2048).keyID("unavailable-key").generate();assertEquals(503,call("/internal/ingestion/datasets",token(issuer,"di-ingestion-user","Bearer","jenkins-delivery",subject,unavailable,null),PLUGIN,"GET",null,null).statusCode());
                System.out.println("PASS G23 browser delivery-management scope, escaped form, CSRF and Origin, explicit office confirmation");
                System.out.println("PASS G24 real RSA/JWKS HTTP: issuer/audience/type/signature/client/role/credential isolation; spoofed user rejected; strict bounded JSON");
            }
        }finally{jwks.stop(0);}
    }
    public static void main(String[] args)throws Exception{if(!"true".equals(System.getenv("DI_COMPOSE_TEST_FIXTURE")))throw new IllegalStateException("Isolated fixture required");new IngestionHttpG2IT().cryptographicProfilesAndStrictHttp();}
}
