@import ch.so.agi.dateninfrastruktur.admin.web.DatasetIssueController.IssueCurrentView
@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@import org.springframework.security.web.csrf.CsrfToken
@param IssueCurrentView currentForm
@param CsrfToken csrf
!{
    var dataset = currentForm.dataset();
    var issue = currentForm.issue();
    var path = "/management/datasets/" + dataset.id() + "/issues";
    var action = path + "/" + issue.id() + "/current";
}
@template.layout.application(title = "Aktuelle Ausgabe wählen", section = NavigationSection.DATASETS, content = @`
    <p class="intro"><strong>${dataset.title()}</strong> · ${dataset.identifier()}</p>
    <p>Die Ausgabe <strong>${issue.label()}</strong> (${issue.key()}) wird als aktuelle Ausgabe dieses Datensatzes markiert.</p>
    @if(currentForm.message() != null || currentForm.stale())
        <div class="notice notice-error">
            @if(currentForm.message() != null)
                <p>${currentForm.message()}</p>
            @endif
            @if(currentForm.stale())
                <p><a href="${path}">Aktuelle Ausgaben neu laden</a></p>
            @endif
        </div>
    @endif
    <form class="management-form" method="post" action="${action}">
        @template.fragment.command-fields(commandId = currentForm.commandId(), csrf = csrf)
        <input type="hidden" name="expectedVersion" value="${currentForm.expectedVersion()}">
        <div class="form-actions">
            <button class="button" type="submit" disabled="${currentForm.stale()}">Als aktuelle Ausgabe wählen</button>
            <a href="${path}">Abbrechen</a>
        </div>
    </form>
`)
