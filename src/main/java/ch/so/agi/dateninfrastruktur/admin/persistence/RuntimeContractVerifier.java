package ch.so.agi.dateninfrastruktur.admin.persistence;
import java.util.*;
import java.security.*;
import javax.sql.DataSource;
/** Shared read-only startup gate for the web application and local write-capable operator CLIs. */
public final class RuntimeContractVerifier {
    private RuntimeContractVerifier(){}
    public static void require(DataSource source)throws Exception{
        byte[] contract;
        try(var stream=RuntimeContractVerifier.class.getResourceAsStream("/management/persistence-contract.properties")){
            if(stream==null)throw new IllegalStateException("Missing packaged persistence contract");contract=stream.readAllBytes();
        }
        var properties=new Properties();properties.load(new java.io.ByteArrayInputStream(contract));
        try(var map=RuntimeContractVerifier.class.getResourceAsStream("/cayenne/management.map.xml")){
            if(map==null || !properties.getProperty("datamap.sha256").equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(map.readAllBytes()))))throw new IllegalStateException("Packaged Cayenne mapping differs from persistence contract");
        }
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contract));
        try(var connection=source.getConnection();var statement=connection.createStatement();var result=statement.executeQuery("SELECT di_ops.runtime_contract(),di_ops.contract_fingerprint()")){
            if(!result.next() || !result.getBoolean(1) || !fingerprint.equals(result.getString(2)))throw new IllegalStateException("Management schema/audit contract is not ready; run explicit setup");
        }
    }
}
