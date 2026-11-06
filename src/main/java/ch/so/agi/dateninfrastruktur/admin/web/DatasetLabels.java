package ch.so.agi.dateninfrastruktur.admin.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetOffice;

public final class DatasetLabels {
    public static final List<Choice> PUBLICATION_STATUSES = List.of(
        new Choice("draft", "Entwurf"),
        new Choice("in_review", "In Prüfung"),
        new Choice("published", "Publiziert"),
        new Choice("archived", "Archiviert"));
    public static final List<Choice> ACCESS_LEVELS = List.of(
        new Choice("open", "Frei zugänglich"),
        new Choice("restricted", "Eingeschränkt"));
    public static final List<Choice> KINDS = List.of(
        new Choice("standalone", "Einzelangebot"),
        new Choice("series", "Serie"));

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT);

    private DatasetLabels() {}

    public static String publicationStatus(String code) {
        return label(PUBLICATION_STATUSES, code);
    }

    public static String accessLevel(String code) {
        return label(ACCESS_LEVELS, code);
    }

    public static String kind(String code) {
        return label(KINDS, code);
    }

    public static String officeCode(DatasetOffice office) {
        return office.abbreviation() + " (" + office.identifier() + ")";
    }

    public static String modifiedDate(LocalDate value) {
        return DATE.format(value);
    }

    private static String label(List<Choice> choices, String code) {
        for (var choice : choices) {
            if (choice.code().equals(code)) {
                return choice.label();
            }
        }
        return "—";
    }

    public record Choice(String code, String label) {}
}
