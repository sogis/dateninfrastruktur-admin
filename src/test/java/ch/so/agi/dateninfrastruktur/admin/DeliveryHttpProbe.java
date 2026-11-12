package ch.so.agi.dateninfrastruktur.admin;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class DeliveryHttpProbe {
    private static final String ISSUER = "https://id.test.example";
    private static final String AUDIENCE = "management-api";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static void exercise(UUID dataset, UUID issue) throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("fixture").generate();
        var unrelated = new RSAKeyGenerator(2048).keyID("fixture").generate();
        var jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks", exchange -> {
            byte[] body = ("{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        jwks.start();
        try (var app = new SpringApplicationBuilder(ManagementApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0",
                "--di.management.writer.jdbc-url=" + System.getenv("DI_TEST_URL"),
                "--di.management.writer.username=di_writer",
                "--di.management.writer.password=" + System.getenv("DI_TEST_WRITER_PASSWORD"),
                "--di.web.issuer=" + ISSUER,
                "--di.web.jwks-uri=http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks",
                "--di.web.audience=" + AUDIENCE, "--di.web.gateway-secret=fixture-only-random-sealed-value-for-test",
                "--di.web.public-origin=http://management.test")) {
            int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
            String base = "http://127.0.0.1:" + port + "/internal/deliveries";
            String eligible = base + "/eligible";
            var user = token(key, "anna", AUDIENCE, ISSUER);
            var gretl = token(key, "gretl", AUDIENCE, ISSUER);
            var unknown = token(key, "unregistered", AUDIENCE, ISSUER);
            assertEquals(401, call("GET", eligible, null, null));
            assertEquals(401, call("GET", eligible, token(unrelated, "anna", AUDIENCE, ISSUER), null));
            assertEquals(401, call("GET", eligible, token(key, "anna", "foreign", ISSUER), null));
            assertEquals(401, call("GET", eligible, token(key, "anna", AUDIENCE, "https://wrong.example"), null));
            assertEquals(403, call("GET", eligible, unknown, null));
            assertEquals(200, call("GET", eligible, user, null));
            String submission = base + "/" + UUID.randomUUID();
            assertEquals(404, call("GET", submission, user, null));
            String input = "{\"datasetId\":\"" + dataset + "\",\"issueId\":\"" + issue + "\",\"mode\":\"replace\"}";
            assertEquals(403, call("PUT", submission, gretl, input));
            assertEquals(200, call("PUT", submission, user, input));
            assertEquals(403, call("POST", submission + "/start", user, null));
            assertEquals(403, call("POST", submission + "/start", unknown, null));
            assertEquals(200, call("POST", submission + "/start", gretl, null));
            assertEquals(409, call("POST", submission + "/cancel", user, null));
            assertEquals(200, call("POST", submission + "/reject", gretl, "{\"message\":\"synthetic rejected\"}"));
            assertEquals(409, call("POST", submission + "/start", gretl, null));
        } catch (Exception error) {
            error.printStackTrace(System.err);
            throw error;
        } finally {
            jwks.stop(0);
        }
        System.out.println("DELIVERY_HTTP_ASSERTIONS=15 SKIP=0");
    }

    private static String token(com.nimbusds.jose.jwk.RSAKey key, String subject, String audience, String issuer) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture").type(JOSEObjectType.JWT).build(),
            new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience).claim("typ", "Bearer")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(180))).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static int call(String method, String url, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
