package ch.so.agi.dateninfrastruktur.admin;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
/** Explicit migration-only active/revoked grants and immutable decision snapshots. */
final class MigrationSentinelFixture {
 void seed()throws Exception{
  var data=new ObjectMapper().readValue(Files.readString(Path.of(System.getenv("DI_TEST_PILOT_FIXTURE"))),Map.class);
  UUID root=UUID.fromString(data.get("restricted").toString());
  try(var f=new ManagementFixture(System.getenv("DI_TEST_PILOT_ISSUER"),OidcLoginIT.CLAUDIA,OidcLoginIT.ANNA,OidcLoginIT.BEN)){
   var command=ManagementFixture.command("fixture.identity");
   UUID anna=f.unit.execute(f.anna,command,ctx->new PrincipalRepository().findOrRegisterVerifiedUser(ctx,f.anna,command).getTIliTid());
   UUID ben=f.unit.execute(f.ben,command,ctx->new PrincipalRepository().findOrRegisterVerifiedUser(ctx,f.ben,command).getTIliTid());
   f.workflow.grantDirect(f.manager,ManagementFixture.command("direct_grant"),root,f.version("dataset",root),anna,f.validity(),"MA-F active grant sentinel");
   UUID grant=f.workflow.grantDirect(f.manager,ManagementFixture.command("direct_grant"),root,f.version("dataset",root),ben,f.validity(),"MA-F revoked grant sentinel");
   f.workflow.revoke(f.manager,ManagementFixture.command("revoke"),grant,f.version("accessgrant",grant),"MA-F persistent revocation sentinel");
  }
 }
}
