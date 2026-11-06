package ch.so.agi.dateninfrastruktur.admin.web;

public enum NavigationSection {
    DATASETS("/management", "Datensätze"),
    CREATE_DATASET("/management/datasets/new", "Neuer Datensatz"),
    REQUESTS("/management/requests", "Offene Anfragen"),
    DELIVERY("/management/delivery", "Anlieferung verwalten"),
    MY_ACCESS("/access/me", "Meine Zugänge");

    private final String href;
    private final String label;

    NavigationSection(String href, String label) {
        this.href = href;
        this.label = label;
    }

    public String href() {
        return href;
    }

    public String label() {
        return label;
    }
}
