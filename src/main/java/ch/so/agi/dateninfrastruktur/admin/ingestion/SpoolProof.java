package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** The trusted bounded plugin stream attests exact bytes; the user never supplies this key. */
public final class SpoolProof {
    private final byte[] key;
    public SpoolProof(String secret){if(secret==null || secret.length()<32)throw new IllegalArgumentException("Explicit plugin credential required");key=secret.getBytes(StandardCharsets.UTF_8);}
    public String sign(UUID submission,long size,String sha256,String originalName,String reference){
        try{
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            var payload=Map.of("schemaVersion",1,"submissionId",submission,"inputSize",size,"inputSha256",sha256,"originalName",originalName,"privateSpoolReference",reference);
            return HexFormat.of().formatHex(mac.doFinal(("di-spool-v1\n"+CanonicalJson.encode(payload)).getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.GeneralSecurityException error){throw new IllegalStateException(error);}
    }
    public void verify(UUID submission,long size,String sha256,String originalName,String reference,String proof){
        if(proof==null || !proof.matches("[0-9a-f]{64}") || !MessageDigest.isEqual(sign(submission,size,sha256,originalName,reference).getBytes(StandardCharsets.US_ASCII),proof.getBytes(StandardCharsets.US_ASCII)))throw new IngestionProblem(403,"INPUT_MISMATCH","Der private Uploadnachweis ist ungültig.");
    }
}
