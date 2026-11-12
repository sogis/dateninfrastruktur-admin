package ch.so.agi.dateninfrastruktur.admin.model;

import ch.so.agi.dateninfrastruktur.admin.model.auto._Dataset;

public class Dataset extends _Dataset {

    private static final long serialVersionUID = 1L;

    public Contactpoint primaryContact() {
        return getDatasetcontacts().stream().map(Datasetcontact::getContactpoints1)
            .min(java.util.Comparator.comparing(Contactpoint::getTIliTid)).orElse(null);
    }
}
