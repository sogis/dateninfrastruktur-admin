@import ch.so.agi.dateninfrastruktur.admin.web.DatasetIssueController.VersionView
@import ch.so.agi.dateninfrastruktur.admin.web.DatasetLabels
@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@param VersionView versionOverview
!{
    var dataset = versionOverview.dataset();
    var issue = versionOverview.issue();
    var path = "/management/datasets/" + dataset.id() + "/issues";
}
@template.layout.application(title = "Datenversionen", section = NavigationSection.DATASETS, content = @`
    <p class="intro">Datensatz <strong>${dataset.title()}</strong> · Ausgabe <strong>${issue.label()}</strong> (${issue.key()})</p>
    <p><a href="${path}">Zu den Ausgaben</a></p>
    @if(versionOverview.versions().isEmpty())
        <p class="notice">Für diese Ausgabe sind noch keine Datenversionen vorhanden.</p>
    @else
        <p class="intro" id="version-date-help">Das Datum zeigt die Annahme der Version oder, falls diese fehlt, die letzte Validierung.</p>
        <p class="dataset-scroll-hint">Die Tabelle lässt sich seitlich scrollen.</p>
        <div class="dataset-table-scroll" role="region" aria-label="Versionsliste" tabindex="0">
            <table class="dataset-table" aria-label="Datenversionen" aria-describedby="version-date-help">
                <thead>
                    <tr>
                        <th scope="col">Version</th>
                        <th scope="col">Verfügbarkeit</th>
                        <th scope="col">Validierung</th>
                        <th scope="col">Datum</th>
                        <th scope="col">Aktuell</th>
                    </tr>
                </thead>
                <tbody>
                    @for(var version : versionOverview.versions())
                        <tr>
                            <th scope="row">${version.key()}</th>
                            <td>${DatasetLabels.versionAvailability(version.availability())}</td>
                            <td>${DatasetLabels.versionValidation(version.validationStatus())}</td>
                            <td>
                                @if(version.date() != null)
                                    <time datetime="${version.date().toString()}">${DatasetLabels.modifiedDate(version.date())}</time>
                                @else
                                    —
                                @endif
                            </td>
                            <td>${version.current() ? "Ja" : "Nein"}</td>
                        </tr>
                    @endfor
                </tbody>
            </table>
        </div>
    @endif
`)
