package ch.so.agi.dateninfrastruktur.admin.command;
import java.util.Objects;
import java.util.UUID;
public record CommandContext(UUID commandId, UUID requestId, String actionCode, String reason) {
    public CommandContext {
        Objects.requireNonNull(commandId); Objects.requireNonNull(requestId); Objects.requireNonNull(actionCode);
        if (actionCode.isBlank() || actionCode.length()>80) throw new IllegalArgumentException("Invalid command action");
    }
}
