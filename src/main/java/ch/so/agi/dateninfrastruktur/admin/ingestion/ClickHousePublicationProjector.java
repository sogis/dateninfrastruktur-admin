package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Only this service identity can change the stable views. There is no public projection endpoint. */
public final class ClickHousePublicationProjector {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String password;
    public ClickHousePublicationProjector(String password){
        if(password==null||!password.matches("[a-zA-Z0-9._~-]{32,128}"))throw new IllegalArgumentException("Explicit dedicated ClickHouse projector credential required");
        this.password=password;
    }
    public void project(String view,String candidate,List<String> attributes,long count)throws Exception{
        if(!view.matches("ds_[0-9a-f]{32}_i_[0-9a-f]{32}_current"))throw new IllegalArgumentException("Invalid projection target");
        String qualified="di_public_sql."+view;
        // A fixed ID per view also fences unknown still-running DDL across an Admin restart.
        String queryId="di-project-"+view;
        if(candidate==null){
            request("DROP VIEW IF EXISTS "+qualified,queryId);
            if(!"0".equals(request("EXISTS TABLE "+qualified,null)))throw new IOException("Projection not removed");
            return;
        }
        if(!candidate.matches("v_[0-9a-f]{32}_[0-9a-f]{32}")||count<1||attributes.isEmpty()||attributes.size()>256||
            new HashSet<>(attributes).size()!=attributes.size()||attributes.stream().anyMatch(a->!a.matches("[A-Za-z][A-Za-z0-9_]{0,127}")))throw new IllegalArgumentException("Invalid projection source");
        String selected=String.join(",",attributes.stream().map(a->"`"+a+"`").toList());
        String source="di_data."+candidate;
        if(!matches(qualified,source,attributes)){
            request("CREATE OR REPLACE VIEW "+qualified+" DEFINER = di_csv_projector SQL SECURITY DEFINER AS SELECT "+selected+" FROM "+source,queryId);
        }
        if(!matches(qualified,source,attributes) || !Long.toString(count).equals(request("SELECT count() FROM "+qualified,null)))throw new IOException("Projection read-back differs");
    }
    private boolean matches(String qualified,String source,List<String> attributes)throws Exception{
        if(!"1".equals(request("EXISTS TABLE "+qualified,null)))return false;
        String actual=request("SHOW CREATE TABLE "+qualified+" FORMAT TSVRaw",null).replace("`","");
        String normalized=actual.replaceAll("\\s+"," ").replaceAll("\\s*,\\s*",",");
        return normalized.endsWith("DEFINER = di_csv_projector SQL SECURITY DEFINER AS SELECT "+String.join(",",attributes)+" FROM "+source);
    }
    private String request(String query,String queryId)throws Exception{
        String url="http://clickhouse:8123/?wait_end_of_query=1&max_execution_time=10&replace_running_query=0"+(queryId==null?"":"&query_id="+queryId);
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("X-ClickHouse-User","di_csv_projector")
            .header("X-ClickHouse-Key",password).POST(HttpRequest.BodyPublishers.ofString(query,StandardCharsets.UTF_8)).build();
        var result=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var body=result.body()){
            if(result.statusCode()!=200)throw new IOException("ClickHouse projection unavailable");
            byte[] bytes=body.readNBytes(32769);if(bytes.length>32768)throw new IOException("Projection response exceeds bound");
            return new String(bytes,StandardCharsets.UTF_8).strip();
        }
    }
}
