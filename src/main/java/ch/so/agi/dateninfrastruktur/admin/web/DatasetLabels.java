package ch.so.agi.dateninfrastruktur.admin.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetOffice;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;

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
    public static final List<Choice> THEMES = CatalogCommandService.THEMES.stream()
        .sorted().map(code -> new Choice(code, themeLabel(code))).toList();

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

    private static String themeLabel(String code) {
        return switch (code) {
            case "Arbeit_Erwerb" -> "Arbeit und Erwerb";
            case "Bau_und_Wohnungswesen" -> "Bau- und Wohnungswesen";
            case "Bevoelkerung" -> "Bevölkerung";
            case "Bildung_Wissenschaft" -> "Bildung und Wissenschaft";
            case "Energie" -> "Energie";
            case "Finanzen" -> "Finanzen";
            case "Geografie" -> "Geografie";
            case "Gesetzgebung" -> "Gesetzgebung";
            case "Gesundheit" -> "Gesundheit";
            case "Handel" -> "Handel";
            case "Industrie_und_Dienstleistungen" -> "Industrie und Dienstleistungen";
            case "Kriminalitaet_Strafrecht" -> "Kriminalität und Strafrecht";
            case "Kultur_Medien_Informationsgesellschaft_Sport" -> "Kultur, Medien, Informationsgesellschaft und Sport";
            case "Landwirtschaft_Forstwirtschaft" -> "Landwirtschaft und Forstwirtschaft";
            case "Mobilitaet_und_Verkehr" -> "Mobilität und Verkehr";
            case "Oeffentliche_Ordnung_und_Sicherheit" -> "Öffentliche Ordnung und Sicherheit";
            case "Politik" -> "Politik";
            case "Preise" -> "Preise";
            case "Raum_und_Umwelt" -> "Raum und Umwelt";
            case "Soziale_Sicherheit" -> "Soziale Sicherheit";
            case "Statistische_Grundlagen" -> "Statistische Grundlagen";
            case "Tourismus" -> "Tourismus";
            case "Verwaltung" -> "Verwaltung";
            case "Volkswirtschaft" -> "Volkswirtschaft";
            default -> throw new IllegalArgumentException("Kein Anzeigetext für Thema " + code);
        };
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
