package ch.so.agi.dateninfrastruktur.admin.security;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
public final class GatewayCredentialFilter extends OncePerRequestFilter {
    private final byte[] secret;
    public GatewayCredentialFilter(String secret){if(secret==null || secret.length()<32)throw new IllegalArgumentException("Gateway credential must contain at least 32 characters");this.secret=secret.getBytes(StandardCharsets.UTF_8);}
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException{
        var headers=java.util.Collections.list(req.getHeaders("X-DI-Gateway-Credential"));
        if(headers.size()!=1 || !MessageDigest.isEqual(secret,headers.getFirst().getBytes(StandardCharsets.UTF_8))){res.sendError(403);return;}
        chain.doFilter(req,res);
    }
}
