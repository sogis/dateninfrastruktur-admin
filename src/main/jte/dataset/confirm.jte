@import ch.so.agi.dateninfrastruktur.admin.web.DatasetAction
@import ch.so.agi.dateninfrastruktur.admin.web.DatasetLabels
@import ch.so.agi.dateninfrastruktur.admin.web.DatasetLifecycleController.ConfirmationView
@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@import org.springframework.security.web.csrf.CsrfToken
@param ConfirmationView confirmation
@param CsrfToken csrf
!{
    var dataset = confirmation.dataset();
    var path = "/management/datasets/" + dataset.id();
    var action = confirmation.action();
    boolean invalidReason = confirmation.error() != null && !confirmation.stale()
        && (confirmation.reason() == null || confirmation.reason().isBlank() || confirmation.reason().length() > 4000);
}
@template.layout.application(title = action.title(), section = NavigationSection.DATASETS, content = @`
    <p class="intro"><strong>${dataset.title()}</strong> · ${dataset.identifier()}</p>
    <p class="intro">${action.description()}</p>
    @if(action == DatasetAction.ACCESS)
        <dl class="record-details">
            <dt>Bisheriger Datenzugang</dt>
            <dd>${DatasetLabels.accessLevel(dataset.accessLevel())}</dd>
            <dt>Neuer Datenzugang</dt>
            <dd>${DatasetLabels.accessLevel(confirmation.targetAccess())}</dd>
        </dl>
    @endif
    @if(confirmation.error() != null || confirmation.stale())
        <div class="notice notice-error" id="action-error">
            @if(confirmation.error() != null)
                <p>${confirmation.error()}</p>
            @endif
            @if(confirmation.stale())
                <p>Ihre Begründung bleibt unten zum Kopieren sichtbar.</p>
                <p><a href="${path}">Aktuellen Datensatz neu laden</a></p>
            @endif
        </div>
    @endif
    <form class="management-form" method="post" action="${path}/${action.path()}">
        @template.fragment.command-fields(commandId = confirmation.commandId(), csrf = csrf)
        @if(action == DatasetAction.ACCESS)
            <input type="hidden" name="level" value="${confirmation.targetAccess()}">
        @endif
        <div class="form-field">
            <label for="reason">Begründung</label>
            <textarea id="reason" name="reason" rows="4" maxlength="4000" required
                      aria-invalid="${invalidReason ? "true" : null}"
                      aria-describedby="${invalidReason ? "action-error" : null}">
${confirmation.reason()}</textarea>
        </div>
        <div class="form-actions">
            <button class="button" type="submit" disabled="${confirmation.stale()}">${action.title()}</button>
            <a href="${path}">Abbrechen</a>
        </div>
    </form>
`)
