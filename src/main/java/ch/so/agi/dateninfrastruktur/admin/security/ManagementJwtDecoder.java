package ch.so.agi.dateninfrastruktur.admin.security;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
public final class ManagementJwtDecoder {
    private ManagementJwtDecoder(){}
    public static JwtDecoder create(String issuer,String jwks,String audience){
            var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
            OAuth2TokenValidator<Jwt> access=jwt->{
                if(jwt.getSubject()==null || jwt.getSubject().isBlank() || jwt.getExpiresAt()==null || !jwt.getAudience().contains(audience) || !"Bearer".equals(jwt.getClaimAsString("typ")))
                    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Expected API access token",null));
                return OAuth2TokenValidatorResult.success();
            };
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),access));return decoder;
    }
}
