package ch.so.agi.dateninfrastruktur.admin.web;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.DatasetIssueService;
import ch.so.agi.dateninfrastruktur.admin.security.*;
import ch.so.agi.dateninfrastruktur.admin.web.UiPage.*;
@Controller @ConditionalOnProperty(name="di.web.enabled",havingValue="true",matchIfMissing=true)
public final class ManagementIssueController {
    private final ManagementQueryService query;private final DatasetIssueService service;
    public ManagementIssueController(ManagementQueryService query,DatasetIssueService service){this.query=query;this.service=service;}
    private ActorContext actor(Authentication auth){return new VerifiedActorResolver().resolve(auth);}
    private CommandContext command(HttpServletRequest req,UUID id,String code){return new CommandContext(id,(UUID)req.getAttribute(RequestCorrelationFilter.ATTRIBUTE),code,null);}
    @GetMapping("/management/datasets/{dataset}/issues") String list(Authentication auth,@PathVariable UUID dataset,Model model,HttpServletRequest req){
        var root=query.detail(actor(auth),dataset);String base="/management/datasets/"+dataset+"/issues";
        var entries=query.issues(actor(auth),dataset).stream().map(row->new Entry(row.get("label").toString(),null,row,List.of(new Form("Aktuelle Ausgabe",base+"/current",List.of(new Field("expectedVersion","","hidden",root.revision()),new Field("issue","","hidden",row.get("id"))),"Als aktuelle Ausgabe wählen"),new Form("Ausgabemetadaten",base+"/"+row.get("id"),List.of(new Field("expectedVersion","","hidden",root.revision()),new Field("label","Bezeichnung","text",row.get("label")),new Field("note","Bemerkung","textarea",row.get("note")),new Field("status","Publikationsstatus","select",row.get("status").toString(),List.of("draft","in_review","published","archived"))),"Ausgabe speichern")))).toList();
        var forms="series".equals(root.kind())?List.of(new Form("Ausgabe ergänzen",base,List.of(new Field("expectedVersion","","hidden",root.revision()),new Field("key","Stabiler Ausgabeschlüssel","text",""),new Field("label","Bezeichnung","text","")),"Ausgabe erstellen")):List.<Form>of();
        model.addAttribute("page", new UiPage("Ausgaben", root.title(), entries, forms));
        model.addAttribute("section", NavigationSection.DATASETS);
        model.addAttribute("csrf", req.getAttribute("_csrf"));
        return "page";
    }
    @PostMapping("/management/datasets/{dataset}/issues")String create(Authentication auth,HttpServletRequest req,@PathVariable UUID dataset,@RequestParam UUID commandId,@RequestParam long expectedVersion,@RequestParam String key,@RequestParam String label){service.createIssue(actor(auth),command(req,commandId,"create_issue"),dataset,expectedVersion,key,label);return "redirect:/management/datasets/"+dataset+"/issues";}
    @PostMapping("/management/datasets/{dataset}/issues/current")String current(Authentication auth,HttpServletRequest req,@PathVariable UUID dataset,@RequestParam UUID commandId,@RequestParam long expectedVersion,@RequestParam UUID issue){service.setCurrentIssue(actor(auth),command(req,commandId,"set_current_issue"),dataset,expectedVersion,issue);return "redirect:/management/datasets/"+dataset+"/issues";}
    @PostMapping("/management/datasets/{dataset}/issues/{issue}")String update(Authentication auth,HttpServletRequest req,@PathVariable UUID dataset,@PathVariable UUID issue,@RequestParam UUID commandId,@RequestParam long expectedVersion,@RequestParam String label,@RequestParam String note,@RequestParam String status){service.updateIssueMetadata(actor(auth),command(req,commandId,"update_issue"),dataset,expectedVersion,issue,label,note,status);return "redirect:/management/datasets/"+dataset+"/issues";}
}
