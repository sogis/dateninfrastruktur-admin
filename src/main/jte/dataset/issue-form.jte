@import ch.so.agi.dateninfrastruktur.admin.web.DatasetIssueController.IssueFormView
@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@import org.springframework.security.web.csrf.CsrfToken
@param IssueFormView issueForm
@param CsrfToken csrf
!{
    var dataset = issueForm.dataset();
    var issue = issueForm.issue();
    boolean creating = issue == null;
    var path = "/management/datasets/" + dataset.id() + "/issues";
    var formPath = creating ? path : path + "/" + issue.id() + "/edit";
    var reloadPath = creating ? path + "/new" : formPath;
    var errors = issueForm.errors();
}
@template.layout.application(title = creating ? "Neue Ausgabe" : "Ausgabe bearbeiten", section = NavigationSection.DATASETS, content = @`
    <p class="intro"><strong>${dataset.title()}</strong> · ${dataset.identifier()}</p>
    @if(creating)
        <p class="intro">Die Ausgabe übernimmt beim Erstellen den Publikationsstatus des Datensatzes.</p>
    @endif
    @if(issueForm.message() != null || issueForm.stale() || !errors.isEmpty())
        <div class="notice notice-error">
            @if(issueForm.message() != null)
                <p>${issueForm.message()}</p>
            @endif
            @if(issueForm.stale())
                <p>Ihre Eingaben bleiben unten zum Kopieren sichtbar.</p>
                <p><a href="${reloadPath}">Aktuellen Stand neu laden</a></p>
            @endif
            @if(!errors.isEmpty())
                <p>Prüfen Sie die folgenden Angaben.</p>
                <ul>
                    @for(var error : errors.entrySet())
                        <li><a href="#${error.getKey()}">${error.getValue()}</a></li>
                    @endfor
                </ul>
            @endif
        </div>
    @endif
    <form class="management-form" method="post" action="${formPath}">
        @template.fragment.command-fields(commandId = issueForm.commandId(), csrf = csrf)
        @if(creating)
            <div class="form-field">
                <label for="key">Ausgabeschlüssel</label>
                <input id="key" name="key" type="text" required maxlength="128"
                       pattern="[A-Za-z0-9][A-Za-z0-9._\-]{0,127}" value="${issueForm.key()}"
                       aria-invalid="${errors.containsKey("key") ? "true" : null}"
                       aria-describedby="${errors.containsKey("key") ? "key-help key-error" : "key-help"}">
                <p class="field-help" id="key-help">Beginnen Sie mit einem Buchstaben (A–Z) oder einer Ziffer. Punkte, Unterstriche und Bindestriche sind danach erlaubt. Der Schlüssel bleibt nach der Erstellung gleich.</p>
                @template.fragment.field-error(id = "key-error", message = errors.get("key"))
            </div>
        @else
            <p><strong>Ausgabeschlüssel</strong><br>${issue.key()}</p>
        @endif
        <div class="form-field">
            <label for="label">Bezeichnung</label>
            <input id="label" name="label" type="text" required maxlength="255" value="${issueForm.label()}"
                   aria-invalid="${errors.containsKey("label") ? "true" : null}"
                   aria-describedby="${errors.containsKey("label") ? "label-error" : null}">
            @template.fragment.field-error(id = "label-error", message = errors.get("label"))
        </div>
        @if(!creating)
            <div class="form-field">
                <label for="note">Bemerkung <span class="field-optional">(optional)</span></label>
                <textarea id="note" name="note" rows="4" maxlength="100000"
                          aria-invalid="${errors.containsKey("note") ? "true" : null}"
                          aria-describedby="${errors.containsKey("note") ? "note-error" : null}">
${issueForm.note()}</textarea>
                @template.fragment.field-error(id = "note-error", message = errors.get("note"))
            </div>
            <div class="form-field">
                <label for="status">Publikationsstatus</label>
                <select id="status" name="status" required
                        aria-invalid="${errors.containsKey("status") ? "true" : null}"
                        aria-describedby="${errors.containsKey("status") ? "status-error" : null}">
                    <option value="">Status auswählen</option>
                    @for(var choice : issueForm.statuses())
                        <option value="${choice.code()}" selected="${choice.code().equals(issueForm.status())}">${choice.label()}</option>
                    @endfor
                </select>
                @template.fragment.field-error(id = "status-error", message = errors.get("status"))
            </div>
        @endif
        <div class="form-actions">
            <button class="button" type="submit" disabled="${issueForm.stale()}">${creating ? "Ausgabe erstellen" : "Ausgabe speichern"}</button>
            <a href="${path}">Abbrechen</a>
        </div>
    </form>
`)
