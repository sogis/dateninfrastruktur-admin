package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.*;

/** di-json-v1: sorted UTF-8 object names, ordered arrays, explicit nulls, exact numbers. */
public final class CanonicalJson {
    public static final ObjectMapper JSON=new ObjectMapper(tools.jackson.core.json.JsonFactory.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).rebuild()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private CanonicalJson(){}
    public static String encode(Object value){
        if(value==null)return "null";
        if(value instanceof String || value instanceof UUID || value instanceof Enum<?> || value instanceof java.time.temporal.TemporalAccessor){String text=value.toString();unicode(text);return JSON.writeValueAsString(text);}
        if(value instanceof Boolean)return value.toString();
        if(value instanceof BigDecimal number)return number.stripTrailingZeros().toPlainString();
        if(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger)return value.toString();
        if(value instanceof Map<?,?> map){
            var fields=new TreeMap<String,Object>((a,b)->Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)));
            for(var e:map.entrySet()){if(!(e.getKey() instanceof String key))throw new IllegalArgumentException("String JSON keys required");unicode(key);fields.put(key,e.getValue());}
            return fields.entrySet().stream().map(e->JSON.writeValueAsString(e.getKey())+":"+encode(e.getValue())).collect(java.util.stream.Collectors.joining(",","{","}"));
        }
        if(value instanceof Collection<?> values)return values.stream().map(CanonicalJson::encode).collect(java.util.stream.Collectors.joining(",","[","]"));
        if(value.getClass().isRecord()){
            var fields=new TreeMap<String,Object>((a,b)->Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)));
            try{for(var component:value.getClass().getRecordComponents())fields.put(component.getName(),component.getAccessor().invoke(value));}
            catch(ReflectiveOperationException error){throw new IllegalArgumentException("Unsupported record",error);}
            return encode(fields);
        }
        throw new IllegalArgumentException("Unsupported canonical JSON type");
    }
    private static void unicode(String value){
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i)))throw new IllegalArgumentException("Invalid Unicode scalar");}
            else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Invalid Unicode scalar");
        }
    }
    public static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String digest(Object value){return sha256(("di-json-v1\n"+encode(value)).getBytes(StandardCharsets.UTF_8));}
}
