package ch.so.agi.dateninfrastruktur.admin;

import ch.so.agi.dateninfrastruktur.admin.persistence.RuntimeContractVerifier;

/** Invokes the same packaged verifier as normal Admin startup using an owned Compose fixture. */
public final class SchemaRuntimeProbe {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("DI_COMPOSE_TEST_FIXTURE"))) {
            throw new IllegalStateException("Isolated schema fixture required");
        }
        try (var source = ManagementFixture.source("di_writer", "DI_TEST_WRITER_PASSWORD")) {
            RuntimeContractVerifier.require(source);
        }
        System.out.println("Packaged Java verifier accepted current physical schema and artifact digest");
    }
}
