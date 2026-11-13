package ch.so.agi.dateninfrastruktur.admin.delivery;

import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Verifies files in a trusted, read-only HTTP origin before the publication transaction. */
public final class CandidateFiles {
    private static final long MAX_BYTES = 104857600;
    private final URI origin;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5)).build();

    public CandidateFiles(URI origin) { this.origin = origin; }

    public static boolean isHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    static void validate(DeliveryService.Complete input) {
        if (input == null || input.schemaId() == null || !isCsvFileName(input.fileName())
                || input.byteSize() < 1 || input.byteSize() > MAX_BYTES
                || input.objectCount() < 0 || input.objectCount() > 999999999999999999L
                || !isHash(input.sha256())) throw BusinessException.invalid("Ungültige Dateiangaben.");
    }

    static boolean isCsvFileName(String fileName) {
        return fileName != null && fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,123}\\.csv");
    }

    public String verify(UUID distributionId, DeliveryService.Complete input) {
        String path = distributionId + "/" + input.fileName();
        if (origin == null) throw unavailable();
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(15)).GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var body = response.body(); InputStream stream = new DigestInputStream(body, digest)) {
                if (response.statusCode() == 404) throw mismatch();
                if (response.statusCode() != 200) throw unavailable();
                byte[] buffer = new byte[8192];
                long count = 0;
                for (int read; (read = stream.read(buffer)) != -1;) {
                    count += read;
                    if (count > input.byteSize()) throw mismatch();
                }
                if (count != input.byteSize() || !HexFormat.of().formatHex(digest.digest()).equals(input.sha256())) throw mismatch();
            }
            return "downloads/" + path;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException error) {
            throw unavailable();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(503, "CANDIDATES_UNAVAILABLE", "Kandidatenspeicher ist nicht erreichbar.");
    }

    private static BusinessException mismatch() {
        return BusinessException.conflict("Unveröffentlichte Kandidatendatei fehlt oder ist inkonsistent.");
    }
}
