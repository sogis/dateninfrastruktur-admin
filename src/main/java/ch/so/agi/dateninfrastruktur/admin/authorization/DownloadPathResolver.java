package ch.so.agi.dateninfrastruktur.admin.authorization;
import java.util.UUID;
import java.util.regex.Pattern;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
public final class DownloadPathResolver {
    private static final Pattern PATH=Pattern.compile("^/downloads/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/([A-Za-z0-9][A-Za-z0-9._-]{0,127})$");
    public record DownloadTarget(UUID distributionId,String fileName,String method){}
    public DownloadTarget parse(String rawUri,String method){
        if(!"GET".equals(method)&&!"HEAD".equals(method))throw new BusinessException(405,"METHOD_NOT_ALLOWED","Nur GET und HEAD sind erlaubt.");
        if(rawUri==null || rawUri.length()>200)throw BusinessException.invalid("Ungültiger Dateipfad.");
        var match=PATH.matcher(rawUri);
        if(!match.matches())throw BusinessException.invalid("Der Dateipfad ist nicht eindeutig oder nicht kanonisch.");
        return new DownloadTarget(UUID.fromString(match.group(1)),match.group(2),method);
    }
}
