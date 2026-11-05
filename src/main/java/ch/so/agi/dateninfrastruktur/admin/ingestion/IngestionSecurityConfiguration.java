package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.*;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import ch.so.agi.dateninfrastruktur.admin.security.ManagementJwtDecoder;

@Configuration @ConditionalOnProperty(name="di.web.enabled",havingValue="true",matchIfMissing=true)
public class IngestionSecurityConfiguration {
    private static void problem(HttpServletResponse response,int status,String code)throws IOException{
        response.setStatus(status);response.setContentType("application/problem+json");response.getWriter().write("{\"status\":"+status+",\"code\":\""+code+"\"}");
    }
    private static boolean profile(org.springframework.security.core.Authentication auth,boolean execution,String role){
        if(!(auth instanceof JwtAuthenticationToken token) || !auth.isAuthenticated())return false;
        var jwt=token.getToken();if(!(execution?"di-ingestion-executor":"jenkins-delivery").equals(jwt.getClaimAsString("azp")))return false;
        if(!execution)return true;
        Object access=jwt.getClaims().get("resource_access");if(!(access instanceof Map<?,?> clients))return false;
        Object client=clients.get("di-ingestion-executor");if(!(client instanceof Map<?,?> claims))return false;
        Object roles=claims.get("roles");return roles instanceof Collection<?> values && values.contains(role);
    }
    private static org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter bearer(org.springframework.security.oauth2.jwt.JwtDecoder decoder){
        var provider=new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider(decoder);
        var filter=new org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter(new org.springframework.security.authentication.ProviderManager(provider));
        filter.setAuthenticationFailureHandler((req,res,error)->problem(res,error instanceof org.springframework.security.authentication.AuthenticationServiceException?503:401,error instanceof org.springframework.security.authentication.AuthenticationServiceException?"DEPENDENCY_UNAVAILABLE":"NOT_AUTHENTICATED"));return filter;
    }
    @Bean @Order(4) SecurityFilterChain ingestionExecution(HttpSecurity http,@Value("${di.web.issuer}")String issuer,@Value("${di.web.jwks-uri}")String jwks)throws Exception{
        return http.securityMatcher("/internal/ingestion/dispatch/**","/internal/ingestion/executions/**")
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).csrf(c->c.disable())
            .authorizeHttpRequests(a->a.anyRequest().access((auth,ctx)->new AuthorizationDecision(profile(auth.get(),true,ctx.getRequest().getRequestURI().startsWith("/internal/ingestion/dispatch/")?"dispatch":"execute"))))
            .addFilterAfter(bearer(ManagementJwtDecoder.create(issuer,jwks,"di-ingestion-executor")),SecurityContextHolderFilter.class)
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,error)->problem(res,401,"NOT_AUTHENTICATED")).accessDeniedHandler((req,res,error)->problem(res,403,"NOT_AUTHORIZED"))).build();
    }
    @Bean @Order(5) SecurityFilterChain ingestionUser(HttpSecurity http,@Value("${di.web.issuer}")String issuer,@Value("${di.web.jwks-uri}")String jwks,@Value("${di.ingestion.plugin-secret}")String secret)throws Exception{
        return http.securityMatcher("/internal/ingestion/**")
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).csrf(c->c.disable())
            .addFilterBefore(new PluginCredential(secret),SecurityContextHolderFilter.class)
            .authorizeHttpRequests(a->a.anyRequest().access((auth,ctx)->new AuthorizationDecision(profile(auth.get(),false,null))))
            .addFilterAfter(bearer(ManagementJwtDecoder.create(issuer,jwks,"di-ingestion-user")),SecurityContextHolderFilter.class)
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,error)->problem(res,401,"NOT_AUTHENTICATED")).accessDeniedHandler((req,res,error)->problem(res,403,"NOT_AUTHORIZED"))).build();
    }
    static final class PluginCredential extends OncePerRequestFilter {
        private final byte[] credential;
        PluginCredential(String secret){if(secret==null || secret.length()<32)throw new IllegalArgumentException("Explicit private plugin credential required");credential=secret.getBytes(StandardCharsets.UTF_8);}
        protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
            var headers=Collections.list(req.getHeaders("X-DI-Plugin-Credential"));
            if(headers.size()!=1 || headers.getFirst().length()>1024 || !MessageDigest.isEqual(credential,headers.getFirst().getBytes(StandardCharsets.UTF_8))){problem(res,403,"NOT_AUTHORIZED");return;}
            chain.doFilter(req,res);
        }
    }
}
