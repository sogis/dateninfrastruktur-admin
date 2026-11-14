package ch.so.agi.dateninfrastruktur.admin;

import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import ch.so.agi.dateninfrastruktur.admin.access.SubmitterAdministrationService.Scope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubmitterScopeTest {
    @Test
    void acceptsAnOfficeTidAtTheLimitAndRejectsOneOverIt() {
        String tid = "x".repeat(200);
        assertEquals(tid, new Scope(tid, null).officeId());
        var error = assertThrows(BusinessException.class, () -> new Scope(tid + "x", null));
        assertEquals(400, error.status());
    }
}
