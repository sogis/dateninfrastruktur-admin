package ch.so.agi.dateninfrastruktur.admin;
import org.junit.jupiter.api.Test;
import ch.so.agi.dateninfrastruktur.admin.access.AccessWorkflowService;
import ch.so.agi.dateninfrastruktur.admin.persistence.CommandReceiptRepository;
import static org.junit.jupiter.api.Assertions.*;
import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
class IdempotencyIT {
    @Test void requestAndApprovalReplayDoNotDuplicateOrExtendAndChangedPayloadConflicts(){
        try(var f=new ManagementFixture()){
            var root=f.published();var requestCommand=command("request");
            var request=f.workflow.request(f.anna,requestCommand,root,"Analyse",null);
            assertEquals(request,f.workflow.request(f.anna,requestCommand,root,"Analyse",null));
            assertThrows(RuntimeException.class,()->f.workflow.request(f.anna,requestCommand,root,"Anderer Zweck",null));
            long revision=f.version("accessrequest",request);var approve=command("approve");var interval=new AccessWorkflowService.Validity(null,null);
            var id=f.workflow.decide(f.manager,approve,request,revision,true,interval,"Datenzugang geprüft");
            assertEquals(id,f.workflow.decide(f.manager,approve,request,revision,true,interval,"Datenzugang geprüft"));
            assertEquals(1,f.count("SELECT count(*) FROM di_management.accessgrant g JOIN di_management.accessdecision d ON d.t_id=g.grantingdecision WHERE d.t_ili_tid=?",id));
            assertEquals(1,f.count("SELECT count(DISTINCT transaction_id) FROM audit.row_change WHERE command_id=?",approve.commandId()));
            assertNotEquals(CommandReceiptRepository.payloadHash(java.util.List.of("a, b")),CommandReceiptRepository.payloadHash(java.util.List.of("a","b")));
            assertNotEquals(CommandReceiptRepository.payloadHash((Object)null),CommandReceiptRepository.payloadHash("<null>"));
        }
    }
}
