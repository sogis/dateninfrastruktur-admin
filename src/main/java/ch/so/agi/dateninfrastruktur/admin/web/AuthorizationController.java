package ch.so.agi.dateninfrastruktur.admin.web;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ch.so.agi.dateninfrastruktur.admin.authorization.*;
import ch.so.agi.dateninfrastruktur.admin.identity.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.security.VerifiedActorResolver;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
@RestController @ConditionalOnProperty(name="di.web.enabled",havingValue="true",matchIfMissing=true)
public final class AuthorizationController {
    private final DownloadAuthorizationService authorization;
    public AuthorizationController(DownloadAuthorizationService authorization){this.authorization=authorization;}
    private String single(HttpServletRequest request,String name){var values=Collections.list(request.getHeaders(name));if(values.size()!=1)throw BusinessException.invalid("Mehrdeutige Originalanfrage.");return values.getFirst();}
    @GetMapping("/internal/authorization/check") ResponseEntity<Void> check(HttpServletRequest request,Authentication authentication){
        var target=new DownloadPathResolver().parse(single(request,"X-Forwarded-Uri"),single(request,"X-Forwarded-Method"));
        var identity=authentication==null?Optional.<ActorContext>empty():Optional.of(new VerifiedActorResolver().resolve(authentication));
        var result=authorization.decide(identity,target);return ResponseEntity.status(result.status()).header("Cache-Control","no-store").build();
    }
}
