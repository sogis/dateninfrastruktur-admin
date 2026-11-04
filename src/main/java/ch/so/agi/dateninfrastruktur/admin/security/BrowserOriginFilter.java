package ch.so.agi.dateninfrastruktur.admin.security;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
/** Cookie-to-bearer forwarding does not remove the browser origin requirement. */
public final class BrowserOriginFilter extends OncePerRequestFilter {
    private final Set<String> origins;
    public BrowserOriginFilter(String origin){
        var values=new java.util.HashSet<String>();
        for(String item:origin.split(",",-1)){
        URI uri=URI.create(item.strip());
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null ||
            !Set.of("http","https").contains(uri.getScheme()) || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))throw new IllegalArgumentException("Public origin must be an absolute origin");
        values.add(uri.getScheme()+"://"+uri.getRawAuthority());
        }
        this.origins=Set.copyOf(values);
    }
    private boolean same(String raw,boolean originOnly){
        try{var uri=URI.create(raw);return uri.getUserInfo()==null && uri.getFragment()==null &&
            origins.contains(uri.getScheme()+"://"+uri.getRawAuthority()) && (!originOnly || (uri.getRawQuery()==null && uri.getPath().isEmpty()));}
        catch(IllegalArgumentException|NullPointerException invalid){return false;}
    }
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException{
        if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())){
            var origins=java.util.Collections.list(req.getHeaders("Origin"));var referers=java.util.Collections.list(req.getHeaders("Referer"));
            if(origins.size()>1 || referers.size()>1 || (origins.isEmpty() && referers.isEmpty()) ||
                (!origins.isEmpty() && !same(origins.getFirst(),true)) || (!referers.isEmpty() && !same(referers.getFirst(),false))){res.sendError(403);return;}
        }
        chain.doFilter(req,res);
    }
}
