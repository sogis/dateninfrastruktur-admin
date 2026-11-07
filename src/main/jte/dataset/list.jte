@import ch.so.agi.dateninfrastruktur.admin.web.DatasetController.ListView
@import ch.so.agi.dateninfrastruktur.admin.web.DatasetLabels
@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@param ListView list
@template.layout.application(title = "Datensätze verwalten", section = NavigationSection.DATASETS, content = @`
    <div class="dataset-overview">
        <p class="intro">Datensätze in Ihrem Zuständigkeitsbereich. Entwürfe sind nicht öffentlich.</p>
        @if(list.canCreate())
            <a class="button" href="/management/datasets/new">Neuer Datensatz</a>
        @endif
    </div>
    @if(list.hasDatasets())
        <form class="dataset-filters" method="get" action="/management/datasets" role="search" aria-label="Datensätze filtern">
            <div class="dataset-filter-fields">
                <label class="form-field">Titel oder Kennung
                    <input type="search" name="search" maxlength="100" value="${list.filters().search()}">
                </label>
                <label class="form-field">Publikationsstatus
                    <select name="publicationStatus">
                        <option value="">Alle</option>
                        @for(var status : DatasetLabels.PUBLICATION_STATUSES)
                            <option value="${status.code()}" selected="${status.code().equals(list.filters().publicationStatus())}">${status.label()}</option>
                        @endfor
                    </select>
                </label>
                <label class="form-field">Datenzugang
                    <select name="accessLevel">
                        <option value="">Alle</option>
                        @for(var access : DatasetLabels.ACCESS_LEVELS)
                            <option value="${access.code()}" selected="${access.code().equals(list.filters().accessLevel())}">${access.label()}</option>
                        @endfor
                    </select>
                </label>
                <label class="form-field">Zuständiges Amt
                    <select name="ownerOffice">
                        <option value="">Alle</option>
                        @for(var office : list.offices())
                            <option value="${office.id().toString()}" selected="${office.id().equals(list.filters().ownerOffice())}">${office.name()} — ${DatasetLabels.officeCode(office)}</option>
                        @endfor
                    </select>
                </label>
            </div>
            <div class="dataset-filter-actions">
                <button class="button button-secondary" type="submit">Suchen</button>
                <a href="/management/datasets">Filter zurücksetzen</a>
            </div>
        </form>
    @endif
    @if(!list.hasDatasets())
        <p class="notice">In Ihrem Zuständigkeitsbereich sind noch keine Datensätze vorhanden.</p>
    @elseif(list.rows().isEmpty())
        @if(list.firstHref() != null)
            <p class="notice">Auf dieser Seite sind keine Datensätze vorhanden. <a href="${list.firstHref()}">Zur ersten Seite</a></p>
        @else
            <p class="notice">Keine Datensätze entsprechen Ihrer Suche.</p>
        @endif
    @else
        <p class="dataset-scroll-hint">Die Tabelle lässt sich seitlich scrollen.</p>
        <div class="dataset-table-scroll" role="region" aria-label="Datensatzliste" tabindex="0">
            <table class="dataset-table" aria-label="Datensätze">
                <thead>
                    <tr>
                        <th scope="col">Datensatz</th>
                        <th scope="col">Zuständiges Amt</th>
                        <th scope="col">Angebotstyp</th>
                        <th scope="col">Publikationsstatus</th>
                        <th scope="col">Datenzugang</th>
                        <th scope="col">Geändert am</th>
                    </tr>
                </thead>
                <tbody>
                    @for(var row : list.rows())
                        <tr>
                            <th scope="row">
                                <a href="${row.href()}">${row.dataset().title()}</a>
                                <span class="dataset-code">${row.dataset().identifier()}</span>
                            </th>
                            <td>
                                ${row.dataset().office().name()}
                                <span class="dataset-code">${DatasetLabels.officeCode(row.dataset().office())}</span>
                            </td>
                            <td>${DatasetLabels.kind(row.dataset().kind())}</td>
                            <td>${DatasetLabels.publicationStatus(row.dataset().publicationStatus())}</td>
                            <td>${DatasetLabels.accessLevel(row.dataset().accessLevel())}</td>
                            <td>
                                @if(row.dataset().modified() != null)
                                    <time datetime="${row.dataset().modified().toString()}">${DatasetLabels.modifiedDate(row.dataset().modified())}</time>
                                @else
                                    —
                                @endif
                            </td>
                        </tr>
                    @endfor
                </tbody>
            </table>
        </div>
    @endif
    @if(list.hasDatasets() && (list.previousHref() != null || list.nextHref() != null))
        <nav class="dataset-pagination" aria-label="Datensatzseiten">
            @if(list.previousHref() != null)
                <a href="${list.previousHref()}" rel="prev">Vorherige Seite</a>
            @endif
            <span>Seite ${list.filters().page() + 1}</span>
            @if(list.nextHref() != null)
                <a href="${list.nextHref()}" rel="next">Nächste Seite</a>
            @endif
        </nav>
    @endif
`)
