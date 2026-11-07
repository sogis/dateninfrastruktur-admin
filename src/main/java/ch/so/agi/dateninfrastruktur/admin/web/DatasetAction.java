package ch.so.agi.dateninfrastruktur.admin.web;

import java.util.Locale;

import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService.Capabilities;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetDetail;

public enum DatasetAction {
    SUBMIT("Zur Prüfung einreichen", "Der Entwurf wechselt in den Status «In Prüfung»."),
    PUBLISH("Publizieren", "Der Datensatz wird im öffentlichen Katalog sichtbar. Der Datenzugang bleibt bestehen."),
    ARCHIVE("Archivieren", "Der Datensatz wird aus dem öffentlichen Katalog genommen. Seine Daten bleiben erhalten."),
    ACCESS("Datenzugang ändern", "Die Zugangsregel gilt für alle verfügbaren Versionen des Datensatzes.");

    private final String title;
    private final String description;

    DatasetAction(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    boolean allowed(DatasetDetail dataset, Capabilities capabilities) {
        String status = dataset.publicationStatus();
        return switch (this) {
            case SUBMIT -> capabilities.metadata() && status.equals("draft");
            case PUBLISH -> capabilities.publish() && dataset.hasIssues() && (status.equals("draft") || status.equals("in_review"));
            case ARCHIVE -> capabilities.publish() && status.equals("published");
            case ACCESS -> capabilities.publish() && capabilities.access();
        };
    }
}
