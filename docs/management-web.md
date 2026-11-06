# Management und Dateizugang

Der [gemeinsame Compose-Stack](../../dateninfrastruktur-dev-stack/docs/operations.md)
führt Admin hinter APISIX aus. Keycloak authentisiert Personen. Admin validiert
Issuer und Audience der signierten Tokens. Browseränderungen erfordern Origin-
und CSRF-Prüfungen. Die technischen Autorisierungsrouten sind intern.

Fachliche Zuständigkeiten gelten am ganzen logischen Datensatz. Metadatenpflege,
Publikation, Verwaltung des Lesezugangs und
[Lieferrechte](delivery-authorization.md) haben getrennte Capabilities.
Der Antrag, Entscheid, Grant und Entzug verwenden aktuelle Zuständigkeiten und
werden zusammen mit der Fachmutation auditiert. Ablauf und Entzug werden bei
der nächsten Autorisierungsanfrage geprüft.

Sodata verwendet die freigegebenen PostgreSQL-Katalogsichten und liefert Links.
Für `/downloads/<uuid>/<name>` prüft APISIX über `forward-auth` die
Admin-Authorization-API. Diese wertet Ziel, Version, Verfügbarkeit und
Zugangsstatus anhand des aktuellen DB-Stands aus. Ein autorisierter Request
gelangt an den internen rclone-Origin. Dieser liest mit seinem eigenen
Leseschlüssel aus dem privaten Garage-Bucket `ch.so.daten`. Fachliche Ablehnung
und ein Ausfall der Autorisierung führen beide zu keiner Dateiauslieferung.

Offene Datensätze können nach Prüfung ohne persönlichen Grant gelesen werden.
Geschützte Datensätze benötigen eine wirksame Leseberechtigung. Der Scope eines
Grants umfasst die zugeordneten Ausgaben und Dateiversionen des Datensatzes.
Ein Link oder die technische Bucketkonfiguration erteilt keine Fachfreigabe.
