package ch.so.agi.dateninfrastruktur.admin;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import ch.so.agi.dateninfrastruktur.admin.security.ManagementJwtDecoder;
import static org.junit.jupiter.api.Assertions.*;
class JwtValidationTest {
    String token(RSAKey key,String issuer,String audience,String type,Instant expires)throws Exception{
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject("verified-user").audience(audience).claim("typ",type).issueTime(Date.from(Instant.now().minusSeconds(120))).expirationTime(Date.from(expires)).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),claims);jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    @Test void signedAccessTokensRequireIssuerAudienceTimeSignatureAndAccessType()throws Exception{
        var key=new RSAKeyGenerator(2048).keyID("test-key").generate();var other=new RSAKeyGenerator(2048).keyID("test-key").generate();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);byte[] jwks=new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/jwks",exchange->{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,jwks.length);exchange.getResponseBody().write(jwks);exchange.close();});server.start();
        try{
            var decoder=ManagementJwtDecoder.create("https://issuer.example.invalid","http://127.0.0.1:"+server.getAddress().getPort()+"/jwks","management-api");var future=Instant.now().plusSeconds(300);
            assertEquals("verified-user",decoder.decode(token(key,"https://issuer.example.invalid","management-api","Bearer",future)).getSubject());
            assertThrows(JwtException.class,()->decoder.decode(token(key,"https://other.example.invalid","management-api","Bearer",future)));
            assertThrows(JwtException.class,()->decoder.decode(token(key,"https://issuer.example.invalid","other-api","Bearer",future)));
            assertThrows(JwtException.class,()->decoder.decode(token(key,"https://issuer.example.invalid","management-api","ID",future)));
            assertThrows(JwtException.class,()->decoder.decode(token(key,"https://issuer.example.invalid","management-api","Bearer",Instant.now().minusSeconds(120))));
            assertThrows(JwtException.class,()->decoder.decode(token(other,"https://issuer.example.invalid","management-api","Bearer",future)));
        }finally{server.stop(0);}
    }
}
