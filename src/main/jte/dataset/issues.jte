@import ch.so.agi.dateninfrastruktur.admin.web.DatasetIssueController.IssueListView
@import ch.so.agi.dateninfrastruktur.admin.web.DatasetLabels
@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@param IssueListView overview
!{
    var dataset = overview.dataset();
    var datasetPath = "/management/datasets/" + dataset.id();
    var issuesPath = datasetPath + "/issues";
}
@template.layout.application(title = "Ausgaben", section = NavigationSection.DATASETS, content = @`
    <div class="dataset-overview">
        <p class="intro"><strong>${dataset.title()}</strong> · ${dataset.identifier()}</p>
        @if(overview.canCreate())
            <a class="button" href="${issuesPath}/new">Neue Ausgabe</a>
        @endif
    </div>
    <p><a href="${datasetPath}">Zum Datensatz</a></p>
    @if(overview.notice() != null)
        <p class="notice" role="status">${overview.notice()}</p>
    @endif
    @if("standalone".equals(dataset.kind()))
        <p class="intro">Dieses Einzelangebot hat eine interne Standardausgabe.</p>
    @endif
    @if(overview.issues().isEmpty())
        <p class="notice">Für diesen Datensatz sind noch keine Ausgaben vorhanden.</p>
    @else
        <p class="dataset-scroll-hint">Die Tabelle lässt sich seitlich scrollen.</p>
        <div class="dataset-table-scroll" role="region" aria-label="Ausgabenliste" tabindex="0">
            <table class="dataset-table" aria-label="Ausgaben">
                <thead>
                    <tr>
                        <th scope="col">Ausgabe</th>
                        <th scope="col">Publikationsstatus</th>
                        <th scope="col">Aktuell</th>
                        <th scope="col">Geändert am</th>
                        <th scope="col">Aktionen</th>
                    </tr>
                </thead>
                <tbody>
                    @for(var row : overview.issues())
                        !{
                            var issue = row.issue();
                            var issuePath = issuesPath + "/" + issue.id();
                            var issueHeading = "issue-" + issue.id();
                        }
                        <tr>
                            <th scope="row" id="${issueHeading}">
                                ${issue.label()}
                                <span class="dataset-code">${issue.key()}</span>
                            </th>
                            <td>${DatasetLabels.publicationStatus(issue.publicationStatus())}</td>
                            <td>${issue.current() ? "Ja" : "Nein"}</td>
                            <td>
                                @if(issue.modified() != null)
                                    <time datetime="${issue.modified().toString()}">${DatasetLabels.modifiedDate(issue.modified())}</time>
                                @else
                                    —
                                @endif
                            </td>
                            <td>
                                <ul class="dataset-action-links">
                                    <li><a href="${issuePath}/versions" aria-describedby="${issueHeading}">Versionen</a></li>
                                    @if(row.canEdit())
                                        <li><a href="${issuePath}/edit" aria-describedby="${issueHeading}">Bearbeiten</a></li>
                                    @endif
                                    @if(row.canMakeCurrent())
                                        <li><a href="${issuePath}/current" aria-describedby="${issueHeading}">Als aktuelle Ausgabe wählen</a></li>
                                    @endif
                                </ul>
                            </td>
                        </tr>
                    @endfor
                </tbody>
            </table>
        </div>
    @endif
`)
