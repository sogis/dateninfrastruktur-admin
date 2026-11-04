package ch.so.agi.dateninfrastruktur.admin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.cayenne.runtime.CayenneRuntime;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagementStartupIT {
    @DynamicPropertySource
    static void dataSources(DynamicPropertyRegistry properties) {
        properties.add("di.management.writer.jdbc-url",()->System.getenv("DI_TEST_URL"));
        properties.add("di.management.writer.username",()->"di_writer");
        properties.add("di.management.writer.password",()->System.getenv("DI_TEST_WRITER_PASSWORD"));
        properties.add("di.management.authorization.jdbc-url",()->System.getenv("DI_TEST_URL"));
        properties.add("di.management.authorization.username",()->"di_authz");
        properties.add("di.management.authorization.password",()->System.getenv("DI_TEST_AUTHZ_PASSWORD"));
    }
    @Autowired CayenneRuntime runtime;
    @org.springframework.beans.factory.annotation.Value("$"+"{local.server.port}") int port;
    @Test void bootStartsWithGeneratedMappingAndReadOnlyContractCheck() {
        assertFalse(runtime.getDataDomain().isSharedCacheEnabled());
        assertEquals(16,runtime.getDataDomain().getEntityResolver().getObjEntities().size());
    }
    @Test void readinessReflectsRealAuditCoverageWithoutRestart() throws Exception {
        var client=java.net.http.HttpClient.newHttpClient();
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(
            "http://127.0.0.1:"+port+"/actuator/health/readiness")).GET().build();
        assertEquals(200,client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        try(var connection=java.sql.DriverManager.getConnection(
                System.getenv("DI_TEST_URL"),"postgres",System.getenv("DI_TEST_ADMIN_PASSWORD"));
            var statement=connection.createStatement()) {
            statement.execute("ALTER TABLE di_management.groupmembership DISABLE TRIGGER di_audit_log");
            try {
                assertEquals(503,client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            } finally {
                statement.execute("ALTER TABLE di_management.groupmembership ENABLE TRIGGER di_audit_log");
            }
        }
        assertEquals(200,client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
