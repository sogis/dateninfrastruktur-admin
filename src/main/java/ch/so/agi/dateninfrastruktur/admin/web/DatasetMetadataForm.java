package ch.so.agi.dateninfrastruktur.admin.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService.Metadata;

public record DatasetMetadataForm(String title, String description, String contactName,
                                  String contactEmail, List<String> themes, String keywords) {
    public DatasetMetadataForm {
        title = text(title);
        description = text(description);
        contactName = text(contactName);
        contactEmail = text(contactEmail);
        themes = themes == null ? List.of() : themes.stream().map(DatasetMetadataForm::text).toList();
        keywords = text(keywords);
    }

    static DatasetMetadataForm empty() {
        return new DatasetMetadataForm("", "", "", "", List.of(), "");
    }

    Map<String, String> errors() {
        var errors = new LinkedHashMap<String, String>();
        if (title.isBlank() || title.length() > 255) {
            errors.put("title", "Geben Sie einen Titel mit höchstens 255 Zeichen ein.");
        }
        if (description.isBlank() || description.length() > 100_000) {
            errors.put("description", "Geben Sie eine Beschreibung mit höchstens 100 000 Zeichen ein.");
        }
        if (contactName.length() > 255) {
            errors.put("contactName", "Der Kontaktname darf höchstens 255 Zeichen enthalten.");
        }
        String address = emailAddress(contactEmail);
        if (!address.matches("(?U)[^\\s@<>:?#]+@[^\\s@<>:?#]+") || address.length() > 1016) {
            errors.put("contactEmail", "Geben Sie eine gültige E-Mail-Adresse ein.");
        }
        if (!CatalogCommandService.THEMES.containsAll(themes)) {
            errors.put("themes", "Wählen Sie Themen aus der angezeigten Liste.");
        }
        var words = keywordValues();
        if (words.size() > 50 || words.stream().anyMatch(word -> word.length() > 255)) {
            errors.put("keywords", "Erlaubt sind bis zu 50 Schlagwörter mit je höchstens 255 Zeichen.");
        }
        return errors;
    }

    Metadata metadata() {
        return new Metadata(title.strip(), description, contactName.isBlank() ? null : contactName.strip(),
            "mailto:" + emailAddress(contactEmail), new TreeSet<>(themes), keywordValues());
    }

    static String emailAddress(String value) {
        String address = text(value).strip();
        return address.regionMatches(true, 0, "mailto:", 0, 7) ? address.substring(7) : address;
    }

    private SortedSet<String> keywordValues() {
        var values = new TreeSet<String>();
        keywords.lines().map(String::strip).filter(word -> !word.isEmpty()).forEach(values::add);
        return values;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
