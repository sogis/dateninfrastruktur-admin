package ch.so.agi.dateninfrastruktur.admin;
import java.util.concurrent.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
class ConcurrentDecisionIT {
    @Test void twoConnectionsCannotCompleteTheSameRequestDifferently()throws Exception{
        try(var f=new ManagementFixture();var executor=Executors.newFixedThreadPool(2)){
            var root=f.published();var request=f.workflow.request(f.anna,command("request"),root,"Analyse",null);long version=f.version("accessrequest",request);
            var start=new CountDownLatch(1);var first=command("approve");var second=command("deny");
            var approve=executor.submit(()->{start.await();try{f.workflow.decide(f.manager,first,request,version,true,f.validity(),"Genehmigt");return true;}catch(RuntimeException e){return false;}});
            var deny=executor.submit(()->{start.await();try{f.workflow.decide(f.manager,second,request,version,false,null,"Abgelehnt");return true;}catch(RuntimeException e){return false;}});
            start.countDown();boolean a=approve.get(20,TimeUnit.SECONDS),b=deny.get(20,TimeUnit.SECONDS);assertNotEquals(a,b);
            assertEquals(a?"approved":"denied",f.request(request).getAstatus());
            assertEquals(1,f.count("SELECT count(*) FROM di_management.accessdecision d JOIN di_management.accessrequest r ON r.t_id=d.request WHERE r.t_ili_tid=?",request));
            assertEquals(a?1:0,f.count("SELECT count(*) FROM di_management.accessgrant g JOIN di_management.dataset d ON d.t_id=g.dataset WHERE d.t_ili_tid=?",root));
        }
    }
}
