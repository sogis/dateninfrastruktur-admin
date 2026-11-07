@import ch.so.agi.dateninfrastruktur.admin.web.NavigationSection
@import ch.so.agi.dateninfrastruktur.admin.web.ManagementController.AuditEntry
@import java.util.List
@param List<AuditEntry> entries
@template.layout.application(title = "Änderungshistorie", section = NavigationSection.DATASETS, content = @`
    <p class="intro">Technisches Audit und fachliche Aktionen mit Vorgangs- und Anfragekennung.</p>
    @for(var entry : entries)
        <article class="record">
            <h2>Änderung ${entry.id()}</h2>
            <dl class="record-details">
                <dt>Auditkennung</dt><dd>${entry.id()}</dd>
                <dt>Aufgezeichnet am</dt><dd>${entry.recordedAt()}</dd>
                <dt>Ausführende Identität</dt><dd>${entry.actor()}</dd>
                <dt>Aktion</dt><dd>${entry.action()}</dd>
                <dt>Änderung</dt><dd>${entry.operation()}</dd>
                <dt>Objekttyp</dt><dd>${entry.table()}</dd>
                <dt>Vorgangskennung</dt><dd>${entry.commandId()}</dd>
                <dt>Anfragekennung</dt><dd>${entry.requestId()}</dd>
                <dt>Vorherige Werte</dt>
                <dd><details><summary>Vorherige Werte anzeigen</summary>${entry.previousValues()}</details></dd>
                <dt>Neue Werte</dt>
                <dd><details><summary>Neue Werte anzeigen</summary>${entry.newValues()}</details></dd>
            </dl>
        </article>
    @endfor
`)
