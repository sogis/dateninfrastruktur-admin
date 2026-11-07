@import ch.so.agi.dateninfrastruktur.admin.web.DatasetAction
@import ch.so.agi.dateninfrastruktur.admin.web.DatasetController.DetailView
@import ch.so.agi.dateninfrastruktur.admin.web.DatasetLabels
@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@param DetailView detail
!{
    var dataset = detail.dataset();
    var path = "/management/datasets/" + dataset.id();
}
@template.layout.application(title = dataset.title(), section = NavigationSection.DATASETS, content = @`
    <div class="dataset-detail">
        <div class="dataset-overview">
            <a href="${detail.listHref()}">Zur Datensatzliste</a>
            @if(detail.canEdit())
                <a class="button" href="${path}/edit">Metadaten bearbeiten</a>
            @endif
        </div>
        @if(detail.notice() != null)
            <p class="notice" role="status">${detail.notice()}</p>
        @endif

        <div class="dataset-statuses">
            <section class="dataset-status" aria-labelledby="dataset-publication">
                <h2 id="dataset-publication">Publikationsstatus</h2>
                <p class="dataset-status-value">${DatasetLabels.publicationStatus(dataset.publicationStatus())}</p>
                @if(detail.actions().stream().anyMatch(action -> action != DatasetAction.ACCESS))
                    <ul class="dataset-action-links">
                        @for(var action : detail.actions())
                            @if(action != DatasetAction.ACCESS)
                                <li><a href="${path}/${action.path()}">${action.title()}</a></li>
                            @endif
                        @endfor
                    </ul>
                @endif
            </section>
            <section class="dataset-status" aria-labelledby="dataset-access">
                <h2 id="dataset-access">Datenzugang</h2>
                <p class="dataset-status-value">${DatasetLabels.accessLevel(dataset.accessLevel())}</p>
                @if(detail.actions().contains(DatasetAction.ACCESS))
                    <p><a href="${path}/${DatasetAction.ACCESS.path()}">${DatasetAction.ACCESS.title()}</a></p>
                @endif
            </section>
        </div>

        <section class="record" aria-labelledby="dataset-identity">
            <h2 id="dataset-identity">Datensatz</h2>
            <dl class="record-details">
                <dt>Kennung</dt><dd>${dataset.identifier()}</dd>
                <dt>Angebotstyp</dt><dd>${DatasetLabels.kind(dataset.kind())}</dd>
                <dt>Zuständiges Amt</dt>
                <dd>${dataset.ownerOffice().name()} — ${DatasetLabels.officeCode(dataset.ownerOffice())}</dd>
                <dt>Herausgebendes Amt</dt>
                <dd>${dataset.publisherOffice().name()} — ${DatasetLabels.officeCode(dataset.publisherOffice())}</dd>
                @if(dataset.modified() != null)
                    <dt>Geändert am</dt>
                    <dd><time datetime="${dataset.modified().toString()}">${DatasetLabels.modifiedDate(dataset.modified())}</time></dd>
                @endif
            </dl>
        </section>

        <section class="record" aria-labelledby="dataset-metadata">
            <h2 id="dataset-metadata">Metadaten</h2>
            <dl class="record-details">
                <dt>Beschreibung</dt>
                <dd class="dataset-description">${dataset.description()}</dd>
                <dt>Name des Kontakts</dt>
                <dd>${dataset.contactName() == null || dataset.contactName().isBlank() ? "—" : dataset.contactName()}</dd>
                <dt>E-Mail des Kontakts</dt>
                <dd>${detail.contactAddress() == null || detail.contactAddress().isBlank() ? "—" : detail.contactAddress()}</dd>
                <dt>Themen</dt>
                <dd>
                    @if(dataset.themes().isEmpty())
                        —
                    @else
                        <ul class="dataset-values">
                            @for(var theme : DatasetLabels.THEMES)
                                @if(dataset.themes().contains(theme.code()))
                                    <li>${theme.label()}</li>
                                @endif
                            @endfor
                        </ul>
                    @endif
                </dd>
                <dt>Schlagwörter</dt>
                <dd>
                    @if(dataset.keywords().isEmpty())
                        —
                    @else
                        <ul class="dataset-values">
                            @for(var keyword : dataset.keywords())
                                <li>${keyword}</li>
                            @endfor
                        </ul>
                    @endif
                </dd>
            </dl>
        </section>

        <nav class="record" aria-labelledby="dataset-related">
            <h2 id="dataset-related">Weitere Bereiche</h2>
            <ul class="dataset-action-links">
                <li><a href="${path}/issues">Ausgaben</a></li>
                <li><a href="${path}/audit">Änderungshistorie</a></li>
                @if(detail.canManageGrants())
                    <li><a href="${path}/grants">Freigaben verwalten</a></li>
                @endif
                @if(detail.canConfigureDelivery())
                    <li><a href="/management/delivery/datasets/${dataset.id().toString()}/configuration">CSV-Liefervertrag</a></li>
                @endif
            </ul>
        </nav>
    </div>
`)
