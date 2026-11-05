package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CsvContractTest {
    private byte[] resource(String name){try(var in=getClass().getResourceAsStream("/ingestion/"+name)){assertNotNull(in);return in.readAllBytes();}catch(java.io.IOException error){throw new RuntimeException(error);}}
    @Test void canonicalVectorMatchesIndependentUtf8Contract()throws Exception{
        var vector=(Map<?,?>)((List<?>)CanonicalJson.JSON.readValue(resource("contracts/protocol/canonical-json-v1-vectors.json"),Map.class).get("vectors")).getFirst();
        var value=new LinkedHashMap<String,Object>();value.put("b",Arrays.asList(null,true,"Grüezi\n"));value.put("a",new BigDecimal("1.2300"));
        assertEquals(vector.get("canonical"),CanonicalJson.encode(value));assertEquals(vector.get("sha256"),CanonicalJson.digest(value));
        assertThrows(IllegalArgumentException.class,()->CanonicalJson.encode(1.23d));
        var vectors=(List<?>)CanonicalJson.JSON.readValue(resource("contracts/protocol/canonical-json-v1-vectors.json"),Map.class).get("vectors");
        var unicode=(Map<?,?>)vectors.get(1);var names=Map.of("\uE000",1,"\uD800\uDC00",2);assertEquals(unicode.get("canonical"),CanonicalJson.encode(names));assertEquals(unicode.get("sha256"),CanonicalJson.digest(names));
        assertThrows(IllegalArgumentException.class,()->CanonicalJson.encode("\uD800"));
    }
    @Test void actualPinnedModelAndCompiledAttributeOrderAndBusinessKeyAreVerified(){
        var definition=new CsvDefinitionRegistry().resolve("population-v1");
        assertEquals(6,definition.definition().columns().size());assertEquals(List.of("Jahrgang"),definition.definition().businessKey());assertEquals("Jahrgang",definition.definition().columns().getFirst().header());
        assertThrows(IngestionProblem.class,()->new CsvDefinitionRegistry().resolve("population-v1","0".repeat(64)));
    }
    @Test @SuppressWarnings("unchecked") void rehashedDefinitionCannotContradictCompiledInterlisBounds(){
        var definition=CanonicalJson.JSON.readValue(resource("contracts/population-v1.json"),Map.class);
        ((Map<String,Object>)((List<?>)definition.get("columns")).getFirst()).put("minimum",1901);
        byte[] bad=CanonicalJson.JSON.writeValueAsBytes(definition);
        byte[] index=CanonicalJson.JSON.writeValueAsBytes(Map.of("schemaVersion",1,"definitions",List.of(Map.of("relativePath","contracts/population-v1.json","sha256",CanonicalJson.sha256(bad)))));
        assertThrows(IllegalStateException.class,()->new CsvDefinitionRegistry(path->path.equals("contracts/index.json")?index:path.equals("contracts/population-v1.json")?bad:resource(path)));
    }
    @Test void missingModelCannotBecomeAnApprovedConfiguration(){
        assertThrows(IllegalStateException.class,()->new CsvDefinitionRegistry(path->path.startsWith("models/")?new byte[0]:resource(path)));
    }
}
