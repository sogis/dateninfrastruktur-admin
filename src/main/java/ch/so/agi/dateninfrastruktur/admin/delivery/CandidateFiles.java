package ch.so.agi.dateninfrastruktur.admin.delivery;

import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Verifies files in a trusted, read-only candidate mount before the publication transaction. */
public final class CandidateFiles {
    private static final long MAX_BYTES = 104857600;
    private final Path root;

    public CandidateFiles(Path root) { this.root = root; }

    public static boolean isHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    static void validate(DeliveryService.Complete input) {
        if (input == null || input.schemaId() == null || input.fileName() == null
                || !input.fileName().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,123}\\.csv")
                || input.byteSize() < 1 || input.byteSize() > MAX_BYTES
                || input.objectCount() < 0 || input.objectCount() > 999999999999999999L
                || !isHash(input.sha256())) throw BusinessException.invalid("Ungültige Dateiangaben.");
    }

    public String verify(UUID distributionId, DeliveryService.Complete input) {
        String key = "downloads/" + distributionId + "/" + input.fileName();
        if (root == null) throw new BusinessException(503, "CANDIDATES_UNAVAILABLE", "Kandidatenspeicher fehlt.");
        try {
            Path directory = root.toRealPath();
            Path path = directory.resolve(key);
            if (!path.equals(path.toRealPath()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) != input.byteSize()) throw mismatch();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = new DigestInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS), digest)) {
                byte[] buffer = new byte[8192];
                long count = 0;
                for (int read; (read = stream.read(buffer)) != -1;) {
                    count += read;
                    if (count > input.byteSize()) throw mismatch();
                }
                if (count != input.byteSize() || !HexFormat.of().formatHex(digest.digest()).equals(input.sha256())) throw mismatch();
            }
            return key;
        } catch (IOException error) {
            throw mismatch();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static BusinessException mismatch() {
        return BusinessException.conflict("Unveröffentlichte Kandidatendatei fehlt oder ist inkonsistent.");
    }
}
