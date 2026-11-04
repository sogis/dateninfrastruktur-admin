package ch.so.agi.dateninfrastruktur.admin;
/** Explicit test-only Compose job; absent from the web image. */
public final class ComposeFixtureCommand {
    public static void main(String[] args)throws Exception{
        if(!"true".equals(System.getenv("DI_COMPOSE_TEST_FIXTURE")))throw new IllegalStateException("Explicit isolated test profile required");
        new ManagedDownloadFixtureIT().seedPreparedPrivateDeliveries();
        System.out.println("Explicit synthetic Compose fixture created; no ordinary startup seed");
    }
}
