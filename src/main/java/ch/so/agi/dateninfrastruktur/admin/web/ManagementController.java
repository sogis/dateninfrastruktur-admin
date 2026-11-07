package ch.so.agi.dateninfrastruktur.admin.web;
import java.time.Instant;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ch.so.agi.dateninfrastruktur.admin.access.*;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.security.*;
import ch.so.agi.dateninfrastruktur.admin.web.UiPage.*;

@Controller @ConditionalOnProperty(name="di.web.enabled",havingValue="true",matchIfMissing=true)
public final class ManagementController {
    private final ManagementQueryService query;
    private final AccessWorkflowService workflow;

    public ManagementController(ManagementQueryService query, AccessWorkflowService workflow) {
        this.query = query;
        this.workflow = workflow;
    }
    private ActorContext actor(Authentication auth){return new VerifiedActorResolver().resolve(auth);}
    private CommandContext command(HttpServletRequest request,UUID id,String action,String reason){return new CommandContext(id,(UUID)request.getAttribute(RequestCorrelationFilter.ATTRIBUTE),action,reason);}
    private Field field(String name,String label,String type,Object value){return new Field(name,label,type,value);}
    private Field hidden(String name,Object value){return field(name,"","hidden",value);}
    private Form form(String title,String path,String button,Field... fields){return new Form(title,path,List.of(fields),button);}
    private String page(Model model,NavigationSection section,String title,String intro,List<Entry> entries,List<Form> forms,HttpServletRequest request){
        model.addAttribute("page", new UiPage(title, intro, entries, forms));
        model.addAttribute("section", section);
        model.addAttribute("csrf", request.getAttribute("_csrf"));
        return "page";
    }
    private Entry entry(String title,String link,Map<String,Object> details,Form... forms){return new Entry(title,link,details,List.of(forms));}
    @GetMapping("/access/requests/new") String requestForm(Authentication auth,@RequestParam UUID dataset,Model model,HttpServletRequest req){var data=query.requestDataset(dataset);return page(model,NavigationSection.MY_ACCESS,"Datenzugang anfragen",data.get("title").toString(),List.of(),"open".equals(data.get("access"))?List.of():List.of(form("Anfrage für Ihre angemeldete Identität","/access/requests","Anfrage senden",hidden("dataset",dataset),field("reason","Begründung","textarea",""),field("until","Gewünschtes Ende (UTC, optional)","text",""))),req);}
    private Instant instant(String text){return text==null || text.isBlank()?null:Instant.parse(text);}
    @PostMapping("/access/requests") String request(Authentication auth,HttpServletRequest req,@RequestParam UUID commandId,@RequestParam UUID dataset,@RequestParam String reason,@RequestParam(defaultValue="")String until){workflow.request(actor(auth),command(req,commandId,"request",reason),dataset,reason,instant(until));return "redirect:/access/me";}
    @GetMapping("/access/me") String mine(Authentication auth,Model model,HttpServletRequest req,@RequestParam(defaultValue="0")int page){
        var entries=new ArrayList<Entry>();for(var r:query.requests(actor(auth),true,page)){var forms=new ArrayList<Form>();if("pending".equals(r.get("status")))forms.add(form("Anfrage zurückziehen","/access/requests/"+r.get("id")+"/cancel","Zurückziehen",hidden("expectedVersion",r.get("revision"))));entries.add(new Entry(r.get("title").toString(),null,r,forms));}
        for(var r:query.effective(actor(auth)))entries.add(entry("Wirksamer Dateizugang: "+r.get("title"),null,r));
        return page(model,NavigationSection.MY_ACCESS,"Meine Anfragen und Zugänge","Aufgeführt sind aktuelle Dateifreigaben. Ein entzogener direkter Grant kann durch einen Gruppenweg ergänzt bleiben.",entries,List.of(),req);
    }
    @PostMapping("/access/requests/{id}/cancel") String cancel(Authentication auth,HttpServletRequest req,@PathVariable UUID id,@RequestParam UUID commandId,@RequestParam long expectedVersion){workflow.cancel(actor(auth),command(req,commandId,"cancel_request",null),id,expectedVersion);return "redirect:/access/me";}
    @GetMapping("/management/requests") String pending(Authentication auth,Model model,HttpServletRequest req,@RequestParam(defaultValue="0")int page){
        var entries=query.requests(actor(auth),false,page).stream().map(r->entry(r.get("title").toString(),null,r,form("Anfrage genehmigen","/management/requests/"+r.get("id")+"/approve","Genehmigen",hidden("expectedVersion",r.get("revision")),field("until","Gültig bis (UTC, optional)","text",""),field("reason","Begründung","textarea","")),form("Anfrage ablehnen","/management/requests/"+r.get("id")+"/deny","Ablehnen",hidden("expectedVersion",r.get("revision")),field("reason","Begründung","textarea","")))).toList();
        return page(model,NavigationSection.REQUESTS,"Offene Zugangsanfragen","Nur Anfragen in Ihrem aktuellen Zuständigkeitsbereich.",entries,List.of(),req);
    }
    @PostMapping("/management/requests/{id}/{action:approve|deny}") String decide(Authentication auth,HttpServletRequest req,@PathVariable UUID id,@PathVariable String action,@RequestParam UUID commandId,@RequestParam long expectedVersion,@RequestParam String reason,@RequestParam(defaultValue="")String until){boolean approve="approve".equals(action);workflow.decide(actor(auth),command(req,commandId,action,reason),id,expectedVersion,approve,approve?new AccessWorkflowService.Validity(null,instant(until)):null,reason);return "redirect:/management/requests";}
    @GetMapping("/management/datasets/{id}/grants") String grants(Authentication auth,@PathVariable UUID id,Model model,HttpServletRequest req,@RequestParam(defaultValue="")String search){
        var entries=new ArrayList<Entry>();
        for(var r:query.grants(actor(auth),id)){
            var forms=r.get("revokedat")==null?List.of(new Form("Direkte Freigabe entziehen","/management/grants/"+r.get("id")+"/revoke",List.of(hidden("expectedVersion",r.get("revision")),hidden("dataset",id),field("reason","Begründung","textarea","")),"Diesen Grant entziehen")):List.<Form>of();
            entries.add(new Entry("Freigabe "+r.get("id"),null,r,forms));
        }
        if (!search.isBlank()) {
            var actor = actor(auth);
            var principals = query.principals(actor, id, search);
            if (!principals.isEmpty()) {
                long revision = query.detail(actor, id).revision();
                for (var principal : principals) {
                    entries.add(entry("Bekannte Identität " + principal.get("id"), null, principal,
                        form("Direkt freigeben", "/management/datasets/" + id + "/grants", "Direktfreigabe erteilen",
                            hidden("beneficiary", principal.get("id")), hidden("expectedVersion", revision),
                            field("until", "Gültig bis (UTC, optional)", "text", ""),
                            field("reason", "Begründung", "textarea", ""))));
                }
            }
        }
        model.addAttribute("identitySearch", true);
        return page(model,NavigationSection.DATASETS,"Freigaben verwalten","Ein Entzug betrifft diesen Grant; andere direkte oder Gruppenwege können fortbestehen.",entries,List.of(),req);
    }
    @PostMapping("/management/datasets/{id}/grants") String direct(Authentication auth,HttpServletRequest req,@PathVariable UUID id,@RequestParam UUID commandId,@RequestParam UUID beneficiary,@RequestParam long expectedVersion,@RequestParam String reason,@RequestParam(defaultValue="")String until){workflow.grantDirect(actor(auth),command(req,commandId,"direct_grant",reason),id,expectedVersion,beneficiary,new AccessWorkflowService.Validity(null,instant(until)),reason);return "redirect:/management/datasets/"+id+"/grants";}
    @PostMapping("/management/grants/{id}/revoke") String revoke(Authentication auth,HttpServletRequest req,@PathVariable UUID id,@RequestParam UUID commandId,@RequestParam UUID dataset,@RequestParam long expectedVersion,@RequestParam String reason){workflow.revoke(actor(auth),command(req,commandId,"revoke",reason),id,expectedVersion,reason);return "redirect:/management/datasets/"+dataset+"/grants";}
    @GetMapping("/management/datasets/{id}/audit")
    String audit(Authentication auth, @PathVariable UUID id, Model model,
                 @RequestParam(defaultValue = "0") long before) {
        model.addAttribute("entries", query.audit(actor(auth), id, before).stream().map(AuditEntry::from).toList());
        return "audit";
    }

    public record AuditEntry(String id, String recordedAt, String actor, String action, String operation,
                             String table, String commandId, String requestId, String previousValues, String newValues) {
        private static AuditEntry from(Map<String, Object> row) {
            return new AuditEntry(auditValue(row, "change_id"), auditValue(row, "recorded_at"),
                auditValue(row, "actor_subject"), auditValue(row, "action_code"), auditValue(row, "operation"),
                auditValue(row, "table_name"), auditValue(row, "command_id"), auditValue(row, "request_id"),
                auditValue(row, "old_values"), auditValue(row, "new_values"));
        }

        private static String auditValue(Map<String, Object> row, String key) {
            return Objects.toString(row.get(key), "—");
        }
    }
}
