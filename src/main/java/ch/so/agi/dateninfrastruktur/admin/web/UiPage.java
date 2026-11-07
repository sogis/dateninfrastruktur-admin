package ch.so.agi.dateninfrastruktur.admin.web;
import java.util.*;
/** Escaped presentation data only; no persistence entities or policy expressions. */
public record UiPage(String title,String introduction,List<Entry> entries,List<Form> forms){
    public static String label(String key){
        return switch(key){
            case "id"->"Kennung";case "title"->"Titel";
            case "status"->"Status";case "revision"->"Bearbeitungsstand";case "kind"->"Typ";
            case "reason"->"Begründung";case "requestedat"->"Angefragt am (UTC)";case "requestedvaliduntil"->"Gewünschtes Ende (UTC)";case "subject"->"Identitätskennung";case "displayname"->"Anzeigename";
            case "applicant"->"Anfragende Person";case "dataset"->"Datensatz";case "beneficiary"->"Berechtigte Identität";case "validfrom"->"Gültig ab (UTC)";case "validuntil"->"Gültig bis (UTC)";case "revokedat"->"Entzogen am (UTC)";
            case "via_kind"->"Zugangsweg";case "via_principal"->"Vermittelnde Identität";case "grant_id"->"Freigabe";case "issuer"->"Identitätsanbieter";
            default->key;
        };
    }
    public static String display(String key,Object value){
        if(value==null)return "—";String text=value.toString();
        if(Set.of("status","kind","via_kind").contains(key))return switch(text){
            case "pending"->"Offen";case "approved"->"Genehmigt";case "denied"->"Abgelehnt";case "cancelled"->"Zurückgezogen";
            case "user"->"Person";case "group"->"Lokale Gruppe";case "service"->"Serviceidentität";default->text;
        };return text;
    }
    public record Entry(String title,String href,Map<String,Object> details,List<Form> forms){}
    public record Form(String title,String action,List<Field> fields,String button,UUID commandId){
        public Form(String title,String action,List<Field> fields,String button){this(title,action,List.copyOf(fields),button,UUID.randomUUID());}
    }
    public record Field(String name,String label,String type,String value,List<String> options){
        public Field(String name,String label,String type,Object value){this(name,label,type,value==null?"":value.toString(),List.of());}
    }
}
