package ch.so.agi.dateninfrastruktur.admin.security;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
public class ManagementSecurityConfiguration {
    @Bean @Order(0) SecurityFilterChain health(HttpSecurity http)throws Exception{
        return http.securityMatcher("/actuator/health","/actuator/health/**").authorizeHttpRequests(a->a.anyRequest().permitAll()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).build();
    }
    @Bean @Order(99) SecurityFilterChain denyUnmapped(HttpSecurity http)throws Exception{
        return http.authorizeHttpRequests(a->a.anyRequest().denyAll()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).build();
    }
    @Configuration @ConditionalOnProperty(name="di.web.enabled",havingValue="true",matchIfMissing=true)
    static class Web {
        @Bean JwtDecoder managementJwtDecoder(@Value("${di.web.issuer}")String issuer,@Value("${di.web.jwks-uri}")String jwks,@Value("${di.web.audience}")String audience){
            return ManagementJwtDecoder.create(issuer,jwks,audience);
        }
        private BearerTokenAuthenticationFilter bearer(JwtDecoder decoder){
            return new BearerTokenAuthenticationFilter(new ProviderManager(new JwtAuthenticationProvider(decoder)));
        }
        @Bean @Order(8) SecurityFilterChain deliveries(HttpSecurity http, JwtDecoder decoder)throws Exception {
            return http.securityMatcher("/internal/deliveries", "/internal/deliveries/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((req,res,error) -> res.setStatus(401)))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterAfter(bearer(decoder), SecurityContextHolderFilter.class).build();
        }
        @Bean @Order(10) SecurityFilterChain internal(HttpSecurity http,JwtDecoder decoder,@Value("${di.web.gateway-secret}")String secret)throws Exception{
            return http.securityMatcher("/internal/**").authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/authorization/check").permitAll().anyRequest().denyAll())
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new GatewayCredentialFilter(secret),SecurityContextHolderFilter.class)
                .addFilterAfter(bearer(decoder),SecurityContextHolderFilter.class).build();
        }
        @Bean @Order(20) SecurityFilterChain browser(HttpSecurity http,JwtDecoder decoder,@Value("${di.web.public-origin}")String origin)throws Exception{
            var cookie=new CookieCsrfTokenRepository();cookie.setCookiePath("/");
            cookie.setCookieCustomizer(c->c.httpOnly(true).sameSite("Lax").secure(origin.startsWith("https://")));
            // The ResourceServer configurer's bearer CSRF exclusion must not apply to cookie-authenticated gateway UI requests.
            return http.securityMatcher("/management","/management/**","/access/**","/assets/**")
                .authorizeHttpRequests(a->a.requestMatchers("/assets/**").permitAll().anyRequest().authenticated())
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c->c.csrfTokenRepository(cookie).csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
                .exceptionHandling(e->e.authenticationEntryPoint((req,res,exception)->res.setStatus(401)))
                .addFilterBefore(new BrowserOriginFilter(origin),CsrfFilter.class)
                .addFilterBefore(new RequestCorrelationFilter(),SecurityContextHolderFilter.class)
                .addFilterAfter(bearer(decoder),SecurityContextHolderFilter.class).build();
        }
    }
}
