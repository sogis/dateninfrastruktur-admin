package ch.so.agi.dateninfrastruktur.admin;

import ch.so.agi.dateninfrastruktur.admin.ingestion.CanonicalJson;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryG4BrowserIT {
    @Test void isolatedDelivery()throws Exception{
        var support=new DeliveryG3BrowserIT();var fixture=Path.of(System.getenv("DI_G4_BROWSER_COMMAND"));
        var command=CanonicalJson.JSON.readTree(Files.readAllBytes(fixture));String dataset=command.path("datasetId").asString();
        String base=support.base,app=support.app;
        try(var playwright=Playwright.create();var browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))){
            var anna=support.login(browser,"anna",OidcLoginIT.ANNA);
            var retryId=command.path("retryId").asString("");
            if(!retryId.isEmpty()){
                var detail=anna.navigate(base+app+"status?id="+retryId);
                assertEquals(200,detail.status());
                var retry=anna.locator("form[action$='/retry']");
                assertEquals(1,retry.count());
                retry.locator("input[name=reason]").fill("Isolierter Wiederanlauf nach Infrastrukturfehler");
                var posted=anna.waitForResponse(r->r.url().endsWith("/data-delivery/retry")&&r.request().method().equals("POST"),()->retry.getByRole(AriaRole.BUTTON).click());
                assertEquals(303,posted.status(),"Native browser retry submission");
                anna.waitForURL(url->url.contains("status?id="+retryId));
                return;
            }
            var reportId=command.path("reportId").asString("");
            if(!reportId.isEmpty()){
                var report=anna.request().get(base+app+"report?id="+reportId);
                assertEquals(200,report.status());assertTrue(report.text().contains(command.path("expectedCode").asString()));
                assertFalse(report.text().contains("privateSpoolReference"));assertFalse(report.text().contains("10.0."));
                var ben=support.login(browser,"ben",OidcLoginIT.BEN);assertEquals(403,ben.request().get(base+app+"report?id="+reportId).status());
                return;
            }
            assertEquals(3,anna.locator("form[action$='/prepare']").count(),"Only the three active automatic-published fixtures are eligible");
            assertEquals(0,anna.locator("input[name='datasetId'][value='"+command.path("pausedDatasetId").asString()+"']").count());
            assertEquals(0,anna.locator("input[name='datasetId'][value='"+command.path("draftDatasetId").asString()+"']").count());
            var form=anna.locator("form[action$='/prepare']:has(input[name='datasetId'][value='"+dataset+"'])");
            assertEquals(1,form.count());
            var fields=new LinkedHashMap<String,String>();
            for(String k:List.of("datasetId","issueId","configurationDigest"))fields.put(k,form.locator("input[name='"+k+"']").inputValue());
            fields.put("mode",command.path("mode").asString());fields.put("commandId",UUID.randomUUID().toString());fields.put("comment","Isolierter G4-Browsertest");
            if(command.path("verifyDisabled").asBoolean()){
                for(String denied:List.of("paused","draft")){
                    var forged=new HashMap<>(fields);forged.put("datasetId",command.path(denied+"DatasetId").asString());forged.put("issueId",command.path(denied+"IssueId").asString());
                    int status=support.prepare(anna,forged,base,true).status();
                    assertTrue(Set.of(403,409).contains(status),"Forged "+denied+" submission blocked");
                }
            }
            String location;
            if(command.path("actualBrowserUpload").asBoolean()){
                form.locator("select[name=mode]").selectOption(fields.get("mode"));
                form.locator("input[name=comment]").fill(fields.get("comment"));
                var prepared=anna.waitForResponse(r->r.url().endsWith("/data-delivery/prepare")&&r.request().method().equals("POST"),()->form.getByRole(AriaRole.BUTTON).click());
                assertEquals(303,prepared.status(),"Native browser delivery preparation");
                location=prepared.headerValue("location");
            }else{
                var prepared=support.prepare(anna,fields,base,true);assertEquals(303,prepared.status());location=prepared.headers().get("location");
            }
            UUID id=UUID.fromString(location.substring(location.indexOf('=')+1));
            Path csv=Path.of(command.path("csv").asString());assertTrue(csv.toRealPath().startsWith(fixture.getParent().toRealPath()));
            if(command.path("actualBrowserUpload").asBoolean()){
                anna.navigate(base+location);anna.locator("input[type=file]").setInputFiles(csv);
                var response=anna.waitForResponse(r->r.url().contains("/data-delivery/upload?id="+id),()->anna.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("CSV einreichen")).click());
                assertEquals(201,response.status(),response::text);
                anna.waitForURL(url->url.contains("/data-delivery/status?id="+id));
            }else{
                var response=support.multipart(anna,id,support.csv(csv.getFileName().toString(),Files.readAllBytes(csv)),base,true);
                assertEquals(201,response.status(),response::text);
            }
            Path result=fixture.resolveSibling("g4-browser-result.json");
            Files.writeString(result,CanonicalJson.encode(Map.of("submissionId",id)));
        }
    }
}
