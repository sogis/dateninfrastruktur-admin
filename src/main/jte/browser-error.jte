@param String title
@param String message
@template.layout.application(title = title, content = @`
    <p class="notice notice-error">${message}</p>
    <p>Prüfen Sie die Adresse oder kehren Sie zur vorherigen Seite zurück.</p>
    <p><a href="/management">Zu den Datensätzen</a></p>
`)
