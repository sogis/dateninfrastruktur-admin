package ch.so.agi.dateninfrastruktur.admin.catalog;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.*;
import tools.jackson.databind.ObjectMapper;

/** Local operator only. Streams bytes from one configured private remote; never fetches user URLs. */
public final class RclonePrivateObjectVerifier implements DeliveryRegistrationService.PrivateObjectVerifier {
    private final Path executable,config;
    private final String remote="storage:ch.so.daten";
    public RclonePrivateObjectVerifier(Path executable,Path config){
        if(!executable.isAbsolute() || !config.isAbsolute())throw new IllegalArgumentException("Invalid fixed private storage configuration");
        this.executable=executable;this.config=config;
    }
    public DeliveryRegistrationService.ObjectEvidence verify(String key){
        if(!key.matches("downloads/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))throw new IllegalArgumentException("Unregistered private storage path");
        long expectedSize=stat(key);
        Process process=null;
        try(var timer=Executors.newSingleThreadScheduledExecutor()){
            process=new ProcessBuilder(executable.toString(),"--config",config.toString(),"cat",remote+"/"+key,"--retries","1","--low-level-retries","1","--contimeout","5s","--timeout","15s").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var running=process;var deadline=timer.schedule(()->running.destroyForcibly(),30,TimeUnit.SECONDS);
            try(var stream=process.getInputStream()){
                var digest=MessageDigest.getInstance("SHA-256");long size=0;byte[] buffer=new byte[65536];int length;
                while((length=stream.read(buffer))!=-1){digest.update(buffer,0,length);size=Math.addExact(size,length);}
                if(process.waitFor()!=0 || size!=expectedSize)throw new IllegalStateException("Private object verification failed or object size changed");
                return new DeliveryRegistrationService.ObjectEvidence(size,HexFormat.of().formatHex(digest.digest()));
            }finally{deadline.cancel(false);process.destroyForcibly();}
        }catch(Exception failure){if(process!=null)process.destroyForcibly();if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalStateException("Private object verification unavailable",failure);}
    }
    private long stat(String key){
        Process process=null;
        try(var timer=Executors.newSingleThreadScheduledExecutor()){
            process=new ProcessBuilder(executable.toString(),"--config",config.toString(),"lsjson",remote+"/"+key,"--stat","--retries","1","--low-level-retries","1","--contimeout","5s","--timeout","15s").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var running=process;var deadline=timer.schedule(()->running.destroyForcibly(),30,TimeUnit.SECONDS);
            try(var stream=process.getInputStream()){
                byte[] metadata=stream.readNBytes(16385);
                if(metadata.length>16384 || process.waitFor()!=0)throw new IllegalStateException("Private object stat failed");
                var info=new ObjectMapper().readValue(metadata,Map.class);
                if(!Boolean.FALSE.equals(info.get("IsDir")) || !key.substring(key.lastIndexOf('/')+1).equals(info.get("Name")) || !(info.get("Size") instanceof Number size) || size.longValue()<0)throw new IllegalStateException("Private file object not found");
                return size.longValue();
            }finally{deadline.cancel(false);process.destroyForcibly();}
        }catch(Exception failure){if(process!=null)process.destroyForcibly();if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalStateException("Private object stat unavailable",failure);}
    }
}
