package ch.so.agi.dateninfrastruktur.admin.persistence;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
/** ili2pg EventTime is timestamp without time zone; its meaning is always UTC. */
public final class UtcTime {
    private UtcTime(){}
    public static LocalDateTime toDatabase(Instant instant){return LocalDateTime.ofInstant(instant,ZoneOffset.UTC);}
    public static Instant fromDatabase(LocalDateTime value){return value.toInstant(ZoneOffset.UTC);}
}
