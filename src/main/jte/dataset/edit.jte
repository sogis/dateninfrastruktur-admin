@import ch.so.agi.dateninfrastruktur.admin.web.DatasetController.EditView
@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@import org.springframework.security.web.csrf.CsrfToken
@param EditView edit
@param CsrfToken csrf
!{
    var path = "/management/datasets/" + edit.dataset().id();
}
@template.layout.application(title = "Metadaten bearbeiten", section = NavigationSection.DATASETS, content = @`
    <p class="intro"><strong>${edit.dataset().title()}</strong> · ${edit.dataset().identifier()}</p>
    <p class="intro">Kennung, Angebotstyp und zuständiges Amt bleiben gleich. Felder ohne den Zusatz «optional» sind Pflichtfelder.</p>
    @if(edit.message() != null || edit.stale() || !edit.errors().isEmpty())
        <div class="notice notice-error">
            @if(edit.message() != null)
                <p>${edit.message()}</p>
            @endif
            @if(edit.stale())
                <p>Ihre Eingaben bleiben unten zum Kopieren sichtbar.</p>
                <p><a href="${path}/edit">Aktuellen Datensatz neu laden</a></p>
            @endif
            @if(!edit.errors().isEmpty())
                <p>Prüfen Sie die folgenden Angaben.</p>
                <ul>
                    @for(var error : edit.errors().entrySet())
                        <li><a href="#${error.getKey()}">${error.getValue()}</a></li>
                    @endfor
                </ul>
            @endif
        </div>
    @endif
    <form class="management-form" method="post" action="${path}/edit">
        @template.fragment.command-fields(commandId = edit.commandId(), csrf = csrf)
        <input type="hidden" name="expectedVersion" value="${edit.expectedVersion()}">
        <input type="hidden" name="published" value="${Boolean.toString(edit.published())}">
        @template.dataset.metadata-fields(metadata = edit.metadata(), errors = edit.errors())
        <div class="form-actions">
            <button class="button" type="submit" disabled="${edit.stale()}">Metadaten speichern</button>
            <a href="${path}">Abbrechen</a>
        </div>
    </form>
`)
