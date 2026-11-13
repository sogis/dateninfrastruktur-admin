package ch.so.agi.dateninfrastruktur.admin.delivery;

import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import ch.so.agi.dateninfrastruktur.admin.model.*;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Render the CSV validation model directly from its bound catalog schema. */
public final class CsvSchemaModel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");
    private static final Set<String> KEYWORDS = Set.of("MODEL","TOPIC","CLASS","STRUCTURE","END","UNIQUE",
        "MANDATORY","CONSTRAINT","SET","DOMAIN","BOOLEAN","DATE","DATETIME","TEXT","MTEXT","ALL","INTERLIS");

    private CsvSchemaModel() {}

    public static String render(Dataschema schema) {
        String name = identifier(schema.getModelname());
        if (!"simple_csv".equals(schema.getAkind())) throw invalid();
        var attributes = orderedAttributes(schema);
        if (attributes.isEmpty()) throw invalid();
        var identifiers = new HashSet<String>();
        var positions = new HashSet<Integer>();
        var source = new StringBuilder("INTERLIS 2.4;\nMODEL ").append(name)
            .append(" (en) AT \"https://geo.so.ch/models/AGI\" VERSION \"1\" =\n")
            .append("  TOPIC Snapshot =\n    CLASS Row =\n");
        for (var attribute : attributes) {
            String field = identifier(attribute.getAname());
            if (!identifiers.add(field) || !positions.add(attribute.getOrdinal())) throw invalid();
            source.append("      ").append(field).append(" : ");
            if (attribute.isMandatory()) source.append("MANDATORY ");
            source.append(type(attribute)).append(";\n");
        }
        for (var rule : schema.getUniquedefinitions().stream().sorted(Comparator.comparing(Uniquedefinition::getAname)).toList()) {
            var seen = new HashSet<String>();
            var order = new HashSet<Integer>();
            var fields = rule.getUniquefields().stream().sorted(Comparator.comparingInt(Uniquefield::getOrdinal)).map(field -> {
                var attribute = field.getAattribute1();
                if (attribute == null || !attributes.contains(attribute)
                    || !seen.add(attribute.getAname()) || !order.add(field.getOrdinal())) throw invalid();
                return identifier(attribute.getAname());
            }).toList();
            if (fields.isEmpty()) throw invalid();
            source.append("      UNIQUE ").append(String.join(", ", fields)).append(";\n");
        }
        Integer minimum = schema.getMinimumobjectcount();
        if (minimum != null) {
            if (minimum < 1) throw invalid();
            source.append("      SET CONSTRAINT (BASKET) Minimum: INTERLIS.objectCount(ALL) >= ")
                .append(minimum).append(";\n");
        }
        return source.append("    END Row;\n  END Snapshot;\nEND ").append(name).append(".\n").toString();
    }

    static List<Attributedefinition> orderedAttributes(Dataschema schema) {
        return schema.getAttributedefinitions().stream()
            .sorted(Comparator.comparingInt(Attributedefinition::getOrdinal)).toList();
    }

    private static String type(Attributedefinition attribute) {
        return switch (attribute.getDatatype()) {
            case "text", "mtext" -> {
                var length = attribute.getMaxlength();
                if (length == null || length < 1 || length > 32767) throw invalid();
                yield attribute.getDatatype().equals("text") ? "TEXT*" + length : "MTEXT*" + length;
            }
            case "integer", "decimal" -> {
                var minimum = attribute.getNumericminimum();
                var maximum = attribute.getNumericmaximum();
                var precision = attribute.getDecimalplaces();
                if (minimum == null || maximum == null || precision == null || precision < 0 || precision > 9
                    || minimum.compareTo(maximum) > 0 || (precision == 0) != "integer".equals(attribute.getDatatype())) throw invalid();
                try {
                    yield minimum.setScale(precision).toPlainString() + " .. " + maximum.setScale(precision).toPlainString();
                } catch (ArithmeticException error) {
                    throw invalid();
                }
            }
            case "boolean" -> "BOOLEAN";
            case "date" -> {
                if (attribute.getDateminimum() == null && attribute.getDatemaximum() == null) yield "DATE";
                String minimum = attribute.getDateminimum() == null ? "1582-01-01" : attribute.getDateminimum().toString();
                String maximum = attribute.getDatemaximum() == null ? "2999-12-31" : attribute.getDatemaximum().toString();
                if (minimum.compareTo(maximum) > 0) throw invalid();
                yield "FORMAT INTERLIS.XMLDate \"" + minimum + "\" .. \"" + maximum + "\"";
            }
            case "datetime" -> {
                String minimum = attribute.getDatetimeminimum() == null ? "1582-01-01T00:00:00.000" : TIME.format(attribute.getDatetimeminimum());
                String maximum = attribute.getDatetimemaximum() == null ? "2999-12-31T23:59:59.999" : TIME.format(attribute.getDatetimemaximum());
                if (minimum.compareTo(maximum) > 0) throw invalid();
                yield "FORMAT INTERLIS.XMLDateTime \"" + minimum + "\" .. \"" + maximum + "\"";
            }
            case "enumeration" -> {
                var codes = attribute.getEnumvalueses().stream()
                    .sorted(Comparator.comparing(AttributedefinitionEnumvalues::getTSeq, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(AttributedefinitionEnumvalues::getEnumvalues).map(CsvSchemaModel::identifier).toList();
                if (codes.isEmpty() || codes.size() != new HashSet<>(codes).size()) throw invalid();
                yield codes.stream().collect(Collectors.joining(", ", "(", ")"));
            }
            default -> throw invalid();
        };
    }

    static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{0,62}") || KEYWORDS.contains(value.toUpperCase(java.util.Locale.ROOT))) {
            throw invalid();
        }
        return value;
    }

    private static BusinessException invalid() { return BusinessException.invalid("Die CSV-Schemadefinition ist ungültig."); }
}
