package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Immutable approved bundle from TOPICS, verified before any command transaction. */
public final class CsvDefinitionRegistry {
    public static final long HARD_MAX_BYTES=268435456L;
    public record FileHash(String relativePath,String sha256){}
    public record Index(Integer schemaVersion,List<FileHash> definitions){}
    public record CompiledModel(String relativePath,String sha256,String compilerVersion){}
    public record Dialect(String encoding,Boolean header,String separator,String quote,String lineEnding,String bom,String nullLiteral,String decimalSeparator){}
    public record Column(String header,String attribute,String type,Boolean mandatory,BigDecimal minimum,BigDecimal maximum,String clickhouseType,String exportType){}
    public record Limits(Long maxBytes,Long maxRows,Integer maxCellBytes,Integer timeoutSeconds){}
    public record Definition(Integer schemaVersion,String definitionKey,Integer contractVersion,String modelName,String modelVersion,String qualifiedTableName,List<FileHash> modelFiles,String modelSetSha256,CompiledModel compiledModel,Dialect dialect,List<Column> columns,List<String> businessKey,Limits limits,Boolean allowEmptyReplacement){
        public Definition {modelFiles=List.copyOf(modelFiles);columns=List.copyOf(columns);businessKey=List.copyOf(businessKey);}
    }
    public record Approved(Definition definition,String sha256,String fullSchemaDigest){}
    public List<String> keys(){return definitions.keySet().stream().sorted().toList();}
    private static final String META="http://www.interlis.ch/xtf/2.4/IlisMeta16",ILI="http://www.interlis.ch/xtf/2.4/INTERLIS";
    private final Map<String,Approved> definitions;
    public CsvDefinitionRegistry(){this(CsvDefinitionRegistry::resource);}
    CsvDefinitionRegistry(Function<String,byte[]> resource){
        try{
            var index=CanonicalJson.JSON.readValue(resource.apply("contracts/index.json"),Index.class);
            require(Integer.valueOf(1).equals(index.schemaVersion()) && index.definitions()!=null && !index.definitions().isEmpty() && index.definitions().size()<=64);
            var result=new TreeMap<String,Approved>();
            for(var file:index.definitions()){
                require(file.relativePath().matches("contracts/[a-z][a-z0-9_-]{0,127}\\.json") && hash(file.sha256()));
                byte[] bytes=resource.apply(file.relativePath());require(CanonicalJson.sha256(bytes).equals(file.sha256()));
                Definition definition=CanonicalJson.JSON.readValue(bytes,Definition.class);
                verify(definition,resource);
                String digest=CanonicalJson.digest(Map.of("schemaVersion",1,"modelSetSha256",definition.modelSetSha256(),"table",definition.qualifiedTableName(),"dialect",definition.dialect(),"columns",definition.columns(),"businessKey",definition.businessKey()));
                require(result.put(definition.definitionKey(),new Approved(definition,file.sha256(),digest))==null);
            }
            definitions=Collections.unmodifiableMap(result);
        }catch(RuntimeException e){throw new IllegalStateException("Approved CSV bundle failed verification; no delivery is permitted",e);}
    }
    public List<Approved> list(){return List.copyOf(definitions.values());}
    public Approved resolve(String key,String expectedHash){
        Approved value=definitions.get(key);
        if(value==null || !value.sha256().equals(expectedHash))throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
        return value;
    }
    public Approved resolve(String key){
        var value=definitions.get(key);if(value==null)throw IngestionProblem.invalid("CONFIGURATION_CHANGED");return value;
    }
    private static byte[] resource(String path){
        try(var in=CsvDefinitionRegistry.class.getResourceAsStream("/ingestion/"+path)){
            if(in==null)throw new IllegalStateException("Missing approved bundle resource");
            byte[] bytes=in.readNBytes(2097153);require(bytes.length<=2097152);return bytes;
        }catch(IOException error){throw new IllegalStateException("Approved bundle unavailable",error);}
    }
    private static boolean hash(String value){return value!=null && value.matches("[0-9a-f]{64}");}
    private static void require(boolean condition){if(!condition)throw new IllegalArgumentException("Invalid approved CSV contract");}
    private static void verify(Definition d,Function<String,byte[]> source){
        require(Integer.valueOf(1).equals(d.schemaVersion()) && d.contractVersion()!=null && d.contractVersion()>0);
        require(d.definitionKey()!=null && d.definitionKey().matches("[a-z][a-z0-9_-]{0,127}"));
        require(d.modelName()!=null && d.modelName().matches("[A-Za-z][A-Za-z0-9_]*") && d.modelVersion()!=null && !d.modelVersion().isBlank());
        require(d.qualifiedTableName()!=null && d.qualifiedTableName().matches("[A-Za-z][A-Za-z0-9_]*\\.[A-Za-z][A-Za-z0-9_]*\\.[A-Za-z][A-Za-z0-9_]*") && d.qualifiedTableName().startsWith(d.modelName()+"."));
        require(d.modelFiles()!=null && !d.modelFiles().isEmpty() && d.modelFiles().size()<=32 && hash(d.modelSetSha256()));
        var paths=new HashSet<String>();String previous="";
        for(var file:d.modelFiles()){
            require(file.relativePath()!=null && file.relativePath().matches("models/[A-Za-z][A-Za-z0-9_]*\\.ili") && paths.add(file.relativePath()) && file.relativePath().compareTo(previous)>0 && hash(file.sha256()));
            require(CanonicalJson.sha256(source.apply(file.relativePath())).equals(file.sha256()));previous=file.relativePath();
        }
        require(paths.contains("models/"+d.modelName()+".ili"));
        require(CanonicalJson.sha256(CanonicalJson.encode(Map.of("schemaVersion",1,"models",d.modelFiles())).getBytes(StandardCharsets.UTF_8)).equals(d.modelSetSha256()));
        var c=d.compiledModel();require(c!=null && c.relativePath()!=null && c.relativePath().matches("contracts/compiled/[a-z][a-z0-9_-]*\\.imd") && hash(c.sha256()) && "5.6.9".equals(c.compilerVersion()));
        byte[] imd=source.apply(c.relativePath());require(CanonicalJson.sha256(imd).equals(c.sha256()));
        var dialect=d.dialect();require(dialect!=null && "UTF-8".equals(dialect.encoding()) && Boolean.TRUE.equals(dialect.header()) && Set.of(";",",").contains(dialect.separator()) && "\"".equals(dialect.quote()) && "LF".equals(dialect.lineEnding()) && "reject".equals(dialect.bom()) && "".equals(dialect.nullLiteral()) && ".".equals(dialect.decimalSeparator()));
        var limits=d.limits();require(limits!=null && limits.maxBytes()!=null && limits.maxBytes()>0 && limits.maxBytes()<=HARD_MAX_BYTES && limits.maxRows()!=null && limits.maxRows()>0 && limits.maxRows()<=10000000 && limits.maxCellBytes()!=null && limits.maxCellBytes()>0 && limits.maxCellBytes()<=32767 && limits.timeoutSeconds()!=null && limits.timeoutSeconds()>0 && limits.timeoutSeconds()<=7200 && d.allowEmptyReplacement()!=null);
        require(d.columns()!=null && !d.columns().isEmpty() && d.columns().size()<=256 && d.businessKey()!=null);
        verifyCompiled(d,imd);
    }
    private static Element child(Element element,String name){
        for(Node n=element.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e && META.equals(e.getNamespaceURI()) && name.equals(e.getLocalName()))return e;
        return null;
    }
    private static String text(Element e,String name){Element x=child(e,name);return x==null?null:x.getTextContent();}
    private static String reference(Element e,String name){Element x=child(e,name);return x==null?null:x.getAttributeNS(ILI,"ref");}
    private static void verifyCompiled(Definition d,byte[] bytes){
        try{
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            var ids=new HashMap<String,Element>();NodeList all=document.getElementsByTagNameNS(META,"*");
            for(int i=0;i<all.getLength();i++){var element=(Element)all.item(i);String id=element.getAttributeNS(ILI,"tid");if(!id.isEmpty())require(ids.put(id,element)==null);}
            var model=ids.get(d.modelName());require(model!=null && d.modelVersion().equals(text(model,"Version")));
            var table=ids.get(d.qualifiedTableName());require(table!=null && "Class".equals(table.getLocalName()) && "Class".equals(text(table,"Kind")));
            var attributes=new TreeMap<Integer,Element>();
            for(var e:ids.values())if("AttrOrParam".equals(e.getLocalName()) && d.qualifiedTableName().equals(reference(e,"AttrParent"))){
                int position=Integer.parseInt(child(e,"AttrParent").getAttributeNS(ILI,"order_pos"));require(attributes.put(position,e)==null);
            }
            require(attributes.size()==d.columns().size());var names=new HashSet<String>();
            for(int i=0;i<d.columns().size();i++){
                var column=d.columns().get(i);var attribute=attributes.get(i+1);require(attribute!=null && column.header()!=null && column.header().matches("[A-Za-z][A-Za-z0-9_]{0,127}") && names.add(column.header()) && column.header().equals(column.attribute()) && column.attribute().equals(text(attribute,"Name")));
                var type=ids.get(reference(attribute,"Type"));require(type!=null && Boolean.valueOf(text(type,"Mandatory")).equals(column.mandatory()));
                // The approved initial pilot is numeric. Unsupported model types fail
                // here before configuration/acceptance; G4 adds only proven adapters.
                require("integer".equals(column.type()) && "NumType".equals(type.getLocalName()) && column.minimum()!=null && column.maximum()!=null && column.minimum().stripTrailingZeros().scale()<=0 && column.maximum().stripTrailingZeros().scale()<=0);
                require(column.minimum().compareTo(new BigDecimal(text(type,"Min")))==0 && column.maximum().compareTo(new BigDecimal(text(type,"Max")))==0 && "Int32".equals(column.clickhouseType()) && "integer".equals(column.exportType()) && column.minimum().compareTo(BigDecimal.valueOf(Integer.MIN_VALUE))>=0 && column.maximum().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE))<=0);
            }
            require(new HashSet<>(d.businessKey()).size()==d.businessKey().size() && names.containsAll(d.businessKey()));
            var uniqueKeys=new ArrayList<List<String>>();
            for(var e:ids.values())if("UniqueConstraint".equals(e.getLocalName()) && d.qualifiedTableName().equals(reference(e,"ToClass"))){
                var key=new ArrayList<String>();var refs=e.getElementsByTagNameNS(META,"Ref");
                for(int i=0;i<refs.getLength();i++){String ref=((Element)refs.item(i)).getAttributeNS(ILI,"ref");require(ref.startsWith(d.qualifiedTableName()+"."));key.add(ref.substring(d.qualifiedTableName().length()+1));}
                uniqueKeys.add(key);
            }
            require(d.businessKey().isEmpty()?uniqueKeys.isEmpty():uniqueKeys.contains(d.businessKey()));
        }catch(RuntimeException e){throw e;}catch(Exception error){throw new IllegalArgumentException("Compiled approved model verification failed",error);}
    }
}
