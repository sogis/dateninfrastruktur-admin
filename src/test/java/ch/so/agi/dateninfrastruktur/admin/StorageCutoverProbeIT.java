package ch.so.agi.dateninfrastruktur.admin;

import com.microsoft.playwright.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only probe of existing registrations; never creates data, grants or audit events. */
class StorageCutoverProbeIT {
    @Test void sameTokensAndRegisteredBytes() throws Exception {
        var journey=new ComposeAcceptanceIT();
        var output=journey.fixturePath.resolveSibling("g1-download-probes.json");
        boolean capture="capture".equals(System.getenv("DI_STORAGE_PROBE_MODE"));
        Map<String,Object> evidence;
        if(capture){
            var tokens=new LinkedHashMap<String,String>();tokens.put("anonymous","");
            try(var pw=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_GC","1")));var browser=pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))){
                tokens.put("anna",journey.token(browser,"anna"));tokens.put("ben",journey.token(browser,"ben"));
            }
            evidence=new LinkedHashMap<>();evidence.put("tokens",tokens);evidence.put("observations",new ArrayList<>());
        }else evidence=journey.read(output);
        var observations=new ArrayList<Map<String,Object>>();int readable=0;
        var tokens=(Map<String,String>)evidence.get("tokens");
        for(var file:journey.files)for(var entry:tokens.entrySet()){
            String token=entry.getValue();
            if(!token.isEmpty()){
                var claims=journey.json.readValue(Base64.getUrlDecoder().decode(token.split("\\.")[1]),Map.class);
                assertTrue(((Number)claims.get("exp")).longValue()>java.time.Instant.now().getEpochSecond(),"Same-token probe expired; source must be retained");
            }
            var response=journey.get(file.get("path").toString(),"GET",token.isEmpty()?null:token,Map.of());
            int status=response.statusCode();assertTrue(Set.of(200,401,403,404,410).contains(status),"Unexpected baseline/runtime status");
            var observation=new LinkedHashMap<String,Object>();observation.put("path",file.get("path"));observation.put("identity",entry.getKey());observation.put("status",status);
            if(status==200){
                readable++;journey.status(200,response);
                String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body()));
                assertEquals(file.get("sha256"),sha);assertEquals(((Number)file.get("size")).longValue(),response.body().length);
                observation.put("sha256",sha);observation.put("size",response.body().length);
            }
            observations.add(observation);
        }
        assertTrue(readable>0,"No positive registered download control");
        if(capture){evidence.put("observations",observations);Files.writeString(output,journey.json.writeValueAsString(evidence));Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rw-------"));}
        else assertEquals(journey.json.writeValueAsString(evidence.get("observations")),journey.json.writeValueAsString(observations),"Existing allow/deny and bytes changed");
        System.out.println("PASS G1 read-only existing registrations, byte hashes and same-token allow/deny probe");
    }
}
