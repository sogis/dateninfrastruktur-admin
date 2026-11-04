package ch.so.agi.dateninfrastruktur.admin.legacy;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
/** Strict namespace-aware reader for the actual pinned PublishedCatalog 2.4 input; no network/entity resolution. */
public record LegacyCatalog(Path path,String fileSha256,List<Element> roots){
    public static final String MODEL="SO_AGI_DataCatalog_PublishedCatalog_20260602";
    public static LegacyCatalog read(Path path)throws Exception{
        byte[] bytes=Files.readAllBytes(path);if(bytes.length>50_000_000)throw new IllegalArgumentException("Legacy catalogue exceeds 50 MB");
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var document=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
        if(!document.getDocumentElement().getTextContent().contains(MODEL))throw new IllegalArgumentException("Unsupported legacy model");
        var roots=new ArrayList<Element>();for(String name:List.of("Dataset","DatasetSeries")){var nodes=document.getElementsByTagNameNS("http://www.interlis.ch/xtf/2.4/"+MODEL,name);for(int i=0;i<nodes.getLength();i++)roots.add((Element)nodes.item(i));}
        if(roots.isEmpty())throw new IllegalArgumentException("No known legacy catalogue entries");
        return new LegacyCatalog(path.toAbsolutePath(),sha(bytes),List.copyOf(roots));
    }
    public static String name(Element e){return e.getLocalName()==null?e.getTagName():e.getLocalName();}
    public static List<Element> children(Element parent,String local){var result=new ArrayList<Element>();if(parent==null)return result;for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e && (local==null || name(e).equals(local)))result.add(e);return result;}
    public static Element at(Element root,String path){var current=root;for(String part:path.split("/")){var list=children(current,part);if(list.size()>1)throw new IllegalArgumentException("Ambiguous legacy scalar "+path);if(list.isEmpty())return null;current=list.getFirst();}return current;}
    public static String text(Element root,String path){var element=at(root,path);return element==null?null:element.getTextContent().strip();}
    public static List<Element> issues(Element root){var list=new ArrayList<Element>();for(var wrapper:children(root,"issues"))list.addAll(children(wrapper,"DatasetIssue"));return list;}
    public static String canonical(Element e){var b=new StringBuilder();canonical(e,b);return b.toString();}
    private static void canonical(Element e,StringBuilder b){b.append('<').append(e.getNamespaceURI()).append(':').append(name(e)).append('>');
        for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling()){if(n instanceof Element child)canonical(child,b);else if(n.getNodeType()==Node.TEXT_NODE && !n.getTextContent().isBlank())b.append(n.getTextContent().strip().replace("&","&amp;").replace("<","&lt;"));}
        b.append("</").append(name(e)).append('>');}
    public static String rawXml(Element element){
        try{var factory=javax.xml.transform.TransformerFactory.newInstance();factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");var transformer=factory.newTransformer();transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION,"yes");var output=new java.io.StringWriter();transformer.transform(new javax.xml.transform.dom.DOMSource(element),new javax.xml.transform.stream.StreamResult(output));return output.toString();}
        catch(javax.xml.transform.TransformerException error){throw new IllegalArgumentException("Cannot retain legacy source XML",error);}
    }
    public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String hash(Element element){return sha(canonical(element).getBytes(StandardCharsets.UTF_8));}
    public static UUID stable(String kind,String identifier){
        try{var digest=MessageDigest.getInstance("SHA-1");digest.update(ByteBuffer.allocate(16).putLong(0x7f309b8e851d4bc1L).putLong(0xa9512c928a97d233L).array());byte[] bytes=digest.digest((kind+":"+identifier).getBytes(StandardCharsets.UTF_8));bytes[6]=(byte)((bytes[6]&15)|80);bytes[8]=(byte)((bytes[8]&63)|128);var buffer=ByteBuffer.wrap(bytes);return new UUID(buffer.getLong(),buffer.getLong());}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
