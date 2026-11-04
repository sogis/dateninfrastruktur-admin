package ch.so.agi.dateninfrastruktur.admin;
import org.junit.jupiter.api.Test;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import org.apache.cayenne.query.ObjectSelect;
import static org.junit.jupiter.api.Assertions.*;
import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
class AccessWorkflowIT {
    @Test void requestApproveDenyDirectGrantRevokeAreAtomicAndCorrelated(){
        try(var f=new ManagementFixture()){
            var root=f.published();var request=f.workflow.request(f.anna,command("request"),root,"Benötigt für Analyse",null);
            assertEquals("pending",f.request(request).getAstatus());
            var approve=command("approve");var decision=f.workflow.decide(f.manager,approve,request,f.version("accessrequest",request),true,f.validity(),"Freigabe geprüft");
            assertEquals("approved",f.request(request).getAstatus());
            var grant=ObjectSelect.query(Accessgrant.class).where(Accessgrant.GRANTINGDECISION1.dot(Accessdecision.T_ILI_TID).eq(decision)).selectOne(f.runtime.newContext());
            assertNotNull(grant);assertEquals("read_data",grant.getPermission());
            assertEquals(3,f.count("SELECT count(*) FROM audit.row_change WHERE command_id=? AND table_name IN ('accessrequest','accessdecision','accessgrant')",approve.commandId()));
            assertEquals(1,f.count("SELECT count(DISTINCT transaction_id) FROM audit.row_change WHERE command_id=?",approve.commandId()));
            var revoke=command("revoke");f.workflow.revoke(f.manager,revoke,grant.getTIliTid(),f.version("accessgrant",grant.getTIliTid()),"Zweck beendet");
            var fresh=ObjectSelect.query(Accessgrant.class).where(Accessgrant.T_ILI_TID.eq(grant.getTIliTid())).selectOne(f.runtime.newContext());assertNotNull(fresh.getRevokedat());assertEquals("revoke",fresh.getRevocationdecision1().getAkind());
            var denied=f.workflow.request(f.ben,command("request"),root,"Andere Anfrage",null);
            var deny=f.workflow.decide(f.manager,command("deny"),denied,f.version("accessrequest",denied),false,null,"Nicht erforderlich");
            assertEquals("denied",f.request(denied).getAstatus());
            assertEquals(0,f.count("SELECT count(*) FROM di_management.accessgrant g JOIN di_management.accessdecision d ON d.t_id=g.grantingdecision WHERE d.t_ili_tid=?",deny));
            var direct=f.workflow.grantDirect(f.manager,command("direct_grant"),root,f.version("dataset",root),f.request(denied).getApplicant1().getTIliTid(),f.validity(),"Direkt geprüft");
            var directRow=ObjectSelect.query(Accessgrant.class).where(Accessgrant.T_ILI_TID.eq(direct)).selectOne(f.runtime.newContext());assertNull(directRow.getGrantingdecision1().getRequest1());
        }
    }
    @Test void ownRequestOnlyAndAuditFailureRollsBackDecisionAndReceipt()throws Exception{
        try(var f=new ManagementFixture()){
            var root=f.published();var request=f.workflow.request(f.anna,command("request"),root,"Analyse",null);
            assertThrows(RuntimeException.class,()->f.workflow.cancel(f.ben,command("cancel_request"),request,f.version("accessrequest",request)));
            assertThrows(RuntimeException.class,()->f.workflow.decide(f.anna,command("approve"),request,f.version("accessrequest",request),true,f.validity(),"Selbstfreigabe"));
            var command=command("approve");
            try(var c=f.admin.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE audit.row_change ADD CONSTRAINT ma_c_fault CHECK(command_id <> '"+command.commandId()+"'::uuid) NOT VALID");}
            try{assertThrows(RuntimeException.class,()->f.workflow.decide(f.manager,command,request,f.version("accessrequest",request),true,f.validity(),"Audit muss funktionieren"));}
            finally{try(var c=f.admin.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE audit.row_change DROP CONSTRAINT ma_c_fault");}}
            assertEquals("pending",f.request(request).getAstatus());
            assertEquals(0,f.count("SELECT count(*) FROM di_ops.command_receipt WHERE command_id=?",command.commandId()));
            assertEquals(0,f.count("SELECT count(*) FROM di_management.accessdecision WHERE commandid=?",command.commandId()));
        }
    }
}
