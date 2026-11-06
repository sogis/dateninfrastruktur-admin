@import ch.so.agi.dateninfrastruktur.admin.web.DatasetController.CreationView
@import ch.so.agi.dateninfrastruktur.admin.web.DatasetLabels
@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@import org.springframework.security.web.csrf.CsrfToken
@param CreationView creation
@param CsrfToken csrf
@template.layout.application(title = "Neuer Datensatz", section = NavigationSection.DATASETS, content = @`
    @if(creation.message() != null || (!creation.offices().isEmpty() && !creation.errors().isEmpty()))
        <div class="notice notice-error">
            @if(creation.message() != null)
                <p>${creation.message()}</p>
            @endif
            @if(!creation.errors().isEmpty() && !creation.offices().isEmpty())
                <p>Prüfen Sie die folgenden Angaben.</p>
                <ul>
                    @for(var error : creation.errors().entrySet())
                        <li><a href="#${error.getKey()}">${error.getValue()}</a></li>
                    @endfor
                </ul>
            @endif
        </div>
    @endif
    @if(creation.offices().isEmpty())
        <p class="notice">Sie können keinen Datensatz erstellen, weil Sie für kein Amt zur Metadatenpflege berechtigt sind.</p>
        <p><a href="/management/datasets">Zu den Datensätzen</a></p>
    @else
        <p class="intro">Neue Datensätze beginnen als Entwurf mit eingeschränktem Datenzugang. Felder ohne den Zusatz «optional» sind Pflichtfelder.</p>
        <form class="management-form" method="post" action="/management/datasets">
            @template.fragment.command-fields(commandId = creation.commandId(), csrf = csrf)
            @if(creation.offices().size() == 1)
                <div class="form-field">
                    <input type="hidden" name="ownerOffice" value="${creation.offices().getFirst().id().toString()}">
                    <p class="office-value" id="ownerOffice" tabindex="-1"
                       aria-describedby="${creation.errors().containsKey("ownerOffice") ? "ownerOffice-error" : null}">Zuständiges Amt<br>
                        ${creation.offices().getFirst().name()} — ${DatasetLabels.officeCode(creation.offices().getFirst())}
                    </p>
                    <p class="field-help">Das zuständige Amt ist auch für die Herausgabe verantwortlich.</p>
                    @template.fragment.field-error(id = "ownerOffice-error", message = creation.errors().get("ownerOffice"))
                </div>
            @else
                <div class="form-field">
                    <label for="ownerOffice">Zuständiges Amt</label>
                    <select id="ownerOffice" name="ownerOffice" required
                            aria-invalid="${creation.errors().containsKey("ownerOffice") ? "true" : null}"
                            aria-describedby="${creation.errors().containsKey("ownerOffice") ? "ownerOffice-help ownerOffice-error" : "ownerOffice-help"}">
                        <option value="">Amt auswählen</option>
                        @for(var office : creation.offices())
                            <option value="${office.id().toString()}" selected="${office.id().toString().equals(creation.ownerOffice())}">${office.name()} — ${DatasetLabels.officeCode(office)}</option>
                        @endfor
                    </select>
                    <p class="field-help" id="ownerOffice-help">Das gewählte Amt ist auch für die Herausgabe verantwortlich.</p>
                    @template.fragment.field-error(id = "ownerOffice-error", message = creation.errors().get("ownerOffice"))
                </div>
            @endif
            <div class="form-field">
                <label for="identifier">Kennung</label>
                <input id="identifier" name="identifier" type="text" required maxlength="255"
                       pattern="[A-Za-z0-9][A-Za-z0-9._\-]{0,254}" value="${creation.identifier()}"
                       aria-invalid="${creation.errors().containsKey("identifier") ? "true" : null}"
                       aria-describedby="${creation.errors().containsKey("identifier") ? "identifier-help identifier-error" : "identifier-help"}">
                <p class="field-help" id="identifier-help">Beginnen Sie mit einem Buchstaben (A–Z) oder einer Ziffer. Punkte, Unterstriche und Bindestriche sind danach erlaubt. Die Kennung bleibt nach der Erstellung gleich.</p>
                @template.fragment.field-error(id = "identifier-error", message = creation.errors().get("identifier"))
            </div>
            <div class="form-field">
                <label for="kind">Angebotstyp</label>
                <select id="kind" name="kind" required
                        aria-invalid="${creation.errors().containsKey("kind") ? "true" : null}"
                        aria-describedby="${creation.errors().containsKey("kind") ? "kind-help kind-error" : "kind-help"}">
                    <option value="">Angebotstyp auswählen</option>
                    @for(var kind : DatasetLabels.KINDS)
                        <option value="${kind.code()}" selected="${kind.code().equals(creation.kind())}">${kind.label()}</option>
                    @endfor
                </select>
                <p class="field-help" id="kind-help">Ein Einzelangebot erhält automatisch eine Ausgabe. Bei einer Serie legen Sie die Ausgaben später an, zum Beispiel für jedes Jahr.</p>
                @template.fragment.field-error(id = "kind-error", message = creation.errors().get("kind"))
            </div>
            @template.dataset.metadata-fields(metadata = creation.metadata(), errors = creation.errors())
            <div class="form-actions">
                <button class="button" type="submit">Entwurf erstellen</button>
                <a href="/management/datasets">Abbrechen</a>
            </div>
        </form>
    @endif
`)
