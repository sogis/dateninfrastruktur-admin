package ch.so.agi.dateninfrastruktur.admin.security;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
public final class VerifiedActorResolver {
    public ActorContext resolve(Authentication authentication){
        if(!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated())throw new BusinessException(401,"AUTHENTICATION_REQUIRED","Bitte anmelden.");
        var jwt=token.getToken();return new ActorContext(jwt.getIssuer().toString(),jwt.getSubject(),"user",null);
    }
}
