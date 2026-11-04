package ch.so.agi.dateninfrastruktur.admin.persistence;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.SQLExec;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;

public final class CommandReceiptRepository {
    public record Receipt(UUID reference,String resultCode){}
    public static String payloadHash(Object... values) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(Object value:values)encode(digest,value);
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void bytes(MessageDigest digest,String text){
        byte[] data=text.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(data.length).array());digest.update(data);
    }
    private static void encode(MessageDigest digest,Object value){
        if(value==null){digest.update((byte)0);return;}
        digest.update((byte)1);bytes(digest,value.getClass().getName());
        if(value instanceof java.util.Collection<?> collection){
            digest.update(ByteBuffer.allocate(4).putInt(collection.size()).array());
            for(Object item:collection)encode(digest,item);
        }else if(value.getClass().isRecord()){
            try{for(var field:value.getClass().getRecordComponents()){bytes(digest,field.getName());encode(digest,field.getAccessor().invoke(value));}}
            catch(ReflectiveOperationException error){throw new IllegalArgumentException("Unsupported command record",error);}
        }else bytes(digest,value.toString());
    }
    private Map<String,Object> key(ActorContext actor,CommandContext command){
        return Map.of("issuer",actor.issuer(),"subject",actor.subject(),"command",command.commandId().toString());
    }
    public Receipt findOrReserve(ObjectContext context,ActorContext actor,CommandContext command,String payloadHash){
        var params=new java.util.HashMap<String,Object>(key(actor,command));
        params.put("action",command.actionCode());params.put("payload",payloadHash);
        SQLExec.query("INSERT INTO di_ops.command_receipt(actor_issuer,actor_subject,command_id,action_code,payload_hash) "+
            "VALUES(#bind($issuer),#bind($subject),CAST(#bind($command) AS uuid),#bind($action),#bind($payload)) "+
            "ON CONFLICT(actor_issuer,actor_subject,command_id) DO NOTHING").params(params).execute(context);
        var row=CayenneSql.rows(context,"SELECT action_code,payload_hash,result_ref,result_code,completed_at "+
            "FROM di_ops.command_receipt WHERE actor_issuer=#bind($issuer) AND actor_subject=#bind($subject) "+
            "AND command_id=CAST(#bind($command) AS uuid) FOR UPDATE",params).getFirst();
        if(!command.actionCode().equals(row.get("action_code")) || !payloadHash.equals(row.get("payload_hash")))
            throw BusinessException.conflict("Dieser Vorgangsschlüssel wurde bereits mit anderen Angaben verwendet.");
        return row.get("completed_at")==null?null:new Receipt(UUID.fromString(row.get("result_ref").toString()),row.get("result_code").toString());
    }
    public void complete(ObjectContext context,ActorContext actor,CommandContext command,Receipt receipt){
        var params=new java.util.HashMap<String,Object>(key(actor,command));
        params.put("reference",receipt.reference().toString());params.put("result",receipt.resultCode());
        SQLExec.query("UPDATE di_ops.command_receipt SET result_ref=CAST(#bind($reference) AS uuid),"+
            "result_code=#bind($result),completed_at=clock_timestamp() WHERE actor_issuer=#bind($issuer) "+
            "AND actor_subject=#bind($subject) AND command_id=CAST(#bind($command) AS uuid)")
            .params(params).execute(context);
    }
}
