# Management und Selbstbedienung – MA-C

> MA-F: Der normale Betrieb und die Operatoren verwenden ausschliesslich den
> gemeinsamen [STACK-Composekontext](../../dateninfrastruktur-dev-stack/docs/operations.md).
> Frühere separate Python-/Host-JVM-Laufzeitbeispiele hier sind historisch; die
> fachlichen, Modell-, Mapping- und Auditverträge bleiben gültig.


MA-0, MA-A und MA-B sind unabhängig akzeptiert. MA-C implementiert Verwaltung,
Selbstbedienung und den Authorizationkern. Die Phasenfreigabe erfolgt nur über
den gebundenen unabhängigen OpenCode-Bericht im STACK. Sodata-DB-Betrieb und
privater Datei-Origin folgen hinter ihren eigenen MA-D-/MA-E-Gates.

## Reproduzierbarer lokaler JVM-Start

Java25, Docker und Python3 erforderlich. Alle Befehle aus ADMIN:

```sh
python3 scripts/setup.py
./gradlew bootJar
python3 scripts/identity_runtime.py start
```

Der letzte Befehl startet eine **eigene lokale Entwicklungs-/Testtopologie** mit
Keycloak26.8.1, APISIX3.19 und dem gebauten Java25-JVM-Jar. Er verbindet nur den
vorher explizit eingerichteten, gelabelten Management-Testcontainer mit einem
neuen eigenen Netz. Frische lokale Testsecrets und Ports stehen ausschliesslich
in `.runtime/identity/state.json` (0600, Elternordner0700). Keine Nutzer-.env,
keine fremden Dienste oder globalen Provideränderungen. Bestehender Runtime-
zustand wird nicht überschrieben. Loopbackports für direkte HTTP-Tests sind
Testinstrumente, keine produktive Netzwerkgrenze. Der endgültige private
Deployment-/Dateipfad wird in MA-E geliefert.

Die Gatewaybasis unter `gateway` in dieser privaten Zustandsdatei öffnen:
`/management`, `/management/datasets/new`, `/management/requests`, `/access/me`.
Der Codeflow führt zum eigenen Test-Keycloak; bekannte Benutzer Anna, Ben und
Claudia behalten die IDs aus dem bestehenden Realmtemplate. Ihr zufälliges
Testpasswort liegt ebenfalls nur in dieser Datei. Der öffentliche Loginpfad für
den späteren Downloadbetrieb folgt in MA-E. Noch kein Datei-Origin in MA-C.

Ein neues eigenes Setup enthält absichtlich keine allgemeinen Fachrechte.
`AuthorityBootstrapCommand` ist ein explizites **lokales Operatorprogramm**, kein
Webendpoint. Es benötigt Writer- und separat bezeichnete Operatoridentität per
Environment, keine Secrets in CLI-Argumenten. Es legt Office-Zuständigkeiten für
eine ausdrücklich angegebene externe Issuer-/Subject-Kombination an und erzeugt
keinen Daten-Grant. Beispiel mit bereits sicher gesetzten Environmentvariablen:

```sh
./gradlew operator --args='bootstrap-office https://issuer.example.invalid/realms/dateninfrastruktur subject-uuid agi Amt'
```

`DI_MANAGEMENT_WRITER_JDBCURL`, `DI_MANAGEMENT_WRITER_PASSWORD`,
`DI_OPERATOR_ISSUER` und `DI_OPERATOR_SUBJECT` müssen aus der jeweiligen eigenen
Runtimekonfiguration stammen. Niemals Owner-/Postgrescredentials als Appkonto.
Lokale Gruppenänderungen sind ausschliesslich über den nicht als Springbean
registrierten Operatorservice zugänglich, nicht über Browserrollen.

Stoppen erhält die Fach-DB:

```sh
python3 scripts/identity_runtime.py stop
```

Nur für eine ausdrücklich temporäre eigene Testdatenbank anschliessend
`python3 scripts/management_db.py cleanup`. Keine Nutzerinstanz löschen.

## Sicherheit und Fachaktionen

APISIX übernimmt echten OIDC-Codeflow; das Backend validiert RS256, festen
Issuer, API-Audience, exp/nbf und Access-Token-Typ. JWKS ist getrennt intern
adressiert, Issuer bleibt extern eindeutig. Realmrollen und X-User-Header sind
keine Rechtequelle. Anonyme Authorizationchecks registrieren keine Person.

UI-POSTs benötigen Standard-Spring-CSRF-Cookie/Formulartoken und passenden
Origin/Referer, auch wenn das Gateway ein Bearertoken weiterreicht. Eigene
Bearerkette vermeidet die CSRF-Ausnahme des ResourceServer-Configurers.
Sessions bleiben beim Gateway; CSRF ist Cookie/Form-basiert ohne Redis. Lokales
HTTP verwendet HttpOnly/SameSite=Lax; HTTPS setzt Secure. Produktions-TLS und
Secretsmanagement sind deploymentseitige Pflichten.

`/internal/authorization/check` verlangt ein internes Servicecredential und
optional ein gültiges Benutzertoken; öffentliche Gatewayrouten sperren
`/internal/*`, `/actuator/*` und `/admin/*`. Nur GET/HEAD-Dateimethoden, eindeutige
Originalheader und kanonische UUID-/ASCII-Pfade werden akzeptiert. Eine frische
parametrisierte SQL-Anweisung prüft zugleich Datei-/Versionsverfügbarkeit,
Root-/Ausgabepublikation, AccessLevel, aktive Identität, direkte/Gruppenwege und
DB-Zeit. Keine Rechtecaches, keine Dateien durch Admin. 204 erlaubt, 401/403/404/
400/405/503 verweigern entsprechend dem normativen Vertrag.

Cayenne schreibt mit neuem Context im AuditedUnitOfWork. Aktuelle Zuständigkeit
wird innerhalb derselben äusseren Transaktion geprüft. Sperrreihenfolge ist
Rootdatensatz, dann Anfrage/Grant; expectedVersion schützt veraltete Formulare.
Receipts sind an Issuer/Subject/Kommando, Aktion und strukturell eindeutig
kodierten Payload gebunden. Replay liefert die frühere Referenz und verlängert
keine Gültigkeit; anderer Payload mit demselben Schlüssel ergibt409. Fachaktion,
Grant, terminale Anfrage, Receipt und Audit committen atomar.

Metadatenformulare ändern nur Titel, Beschreibung, Kontaktname/-email, Themen
und Keywords; übrige importierte Felder bleiben erhalten. Publizierte Metadaten
benötigen manage_metadata+publish; AccessLevel benötigt manage_access+publish.
Neue Entwürfe beginnen restricted. Ausgaben und aktuelle Zeiger prüfen ihren
Parent und Rootrevision. MTEXT wird escaped ausgegeben; kein freies HTML.

DeliveryRegistrationService registriert bestätigte Lieferreferenzen und
vorbereitete Dateien. Aktivierung benötigt einen vertrauenswürdigen privaten
ObjectVerifier mit tatsächlich ermitteltem Hash/Bytes. MA-C prüft diesen Vertrag
an echten eigenen lokalen Testdateien; privates S3/Origin wird in MA-E angebunden.
Es gibt keinen HTTP-Upload/Fremd-URL-Fetch oder neue ClickHouse-Ladepipeline.

Auditansicht und Freigabenliste prüfen aktuellen Scope. Audit verwendet ein
separates readonly-Konto, Ausgabe ist begrenzt und modellseitig redacted.
Entzug eines direkten Grants lässt andere direkte/Gruppenwege bestehen.
Die Oberfläche verspricht keinen SQL-/Superset-Zugang.

## Tests und Migration

```sh
python3 scripts/test_management_all.py
```

Die Suite verlangt einen freien eigenen Runtimebereich, richtet alles mit
neuen Credentials ein und entfernt im finally nur eigene gelabelte Ressourcen.
JUnit-Berichte und bereinigte Schrittlogs liegen unter
`.runtime/management-c-evidence/`. Playwright verwendet
`PLAYWRIGHT_SKIP_BROWSER_GC=1`; kein automatisches Entfernen fremder Browser-
Caches. Keine übersprungenen/fehlenden Pflichtklassen als PASS.

MA-B-Regressionen zu UOW/Startup laufen neu; die zwei nicht blockierenden
Reviewhinweise haben eigene Drift-/Fingerprint-Gegenproben. Ein expliziter
gehashter B→C-Vorgänger erlaubt allein die additive V007. Modell-/Mapping-/alte
Migrationshashes müssen unverändert passen. Upgrade über nichtleere UUID-/
Auditdaten, unbekannter Fingerprint und unveränderte Quelldatei bei Ablehnung
werden tatsächlich geprüft. Appstart installiert kein Schema. Readiness prüft
Audit- und Webvertrag.
