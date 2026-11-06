package ch.so.agi.dateninfrastruktur.admin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.DeliveryRegistrationService;
/** Withdrawal retains immutable history after the sole validated CSV acceptance path. */
class DeliveryRegistrationIT {
    @Test void withdrawAcceptedFileRetainsVersionAndOtherFormats(){try(var x=new SubmissionAuthorizationIT.Fixture()){
        var id=x.ready();var claim=x.claim(id);var manifest=x.validated(claim);var accepted=x.publications.complete(x.runner,SubmissionAuthorizationIT.command("ingestion.complete"),claim.executionId(),x.binding(claim),manifest);
        var file=claim.plan().outputs().getFirst().distributionId();new DeliveryRegistrationService(x.f.unit,x.f.authority).withdraw(x.f.manager,SubmissionAuthorizationIT.command("withdraw_distribution"),x.dataset,x.version("dataset",x.dataset),file,"Datei zurückgezogen");
        assertEquals(1,x.f.count("SELECT count(*) FROM di_management.distribution WHERE t_ili_tid=? AND availability='withdrawn'",file));assertEquals(1,x.f.count("SELECT count(*) FROM di_management.dataversion WHERE t_ili_tid=? AND availability='available'",accepted.versionId()));assertEquals(2,x.f.count("SELECT count(*) FROM di_management.distribution d JOIN di_management.dataversion v ON v.t_id=d.version WHERE v.t_ili_tid=? AND d.availability='available'",accepted.versionId()));
    }}
}
