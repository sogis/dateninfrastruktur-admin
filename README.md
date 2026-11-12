# Admin – Katalog, Rechte und Anlieferung

Admin verwaltet den Managementkatalog, entscheidet über fachliche Rechte
und verwaltet CSV-Submissions. Die Anwendung verwendet Java 25,
Spring Boot und Apache Cayenne. INTERLIS ist die Quelle des Fachschemas.

Der [gemeinsame Compose-Betrieb](../dateninfrastruktur-dev-stack/docs/operations.md)
richtet die Management-Datenbank explizit ein. Das Setup importiert die
vier Modelle in einem ili2pg-Aufruf. Die freigegebenen Sichten entstehen
im PostScript. Eine vorhandene Management-Datenbank wird lesend geprüft.
Der Normalstart führt keine Schemaänderungen aus.

```sh
./gradlew test
python3 scripts/test_models.py management
python3 scripts/test_models.py csv
```

Der Stack stellt die isolierten Integrationsprüfungen für Lieferrechte,
HTTP-Sicherheit und konkurrierende Veröffentlichungen bereit. Modellerzeugung und
Datenbanktests benötigen die im jeweiligen Einstieg beschriebenen Werkzeuge.

- [Modellinvarianten](models/constraints.md)
- [Schema und XTF-Bootstrap](docs/schema-lifecycle.md)
- [Lieferrechte und Submission](docs/delivery-authorization.md)
- [Management und Dateizugang](docs/management-web.md)

## Oberfläche lokal entwickeln

Der Dev-Stack schaltet Admin mit `./scripts/dev.sh admin on` auf Gradle
`bootRun` um. CSS unter `src/main/resources/static/assets` und JTE
unter `src/main/jte` bearbeiten und die Seite im Gateway neu laden.
Der Entwicklungsmodus kompiliert Java-Änderungen und startet danach den
Spring-Anwendungskontext neu. Die [Stack-Bedienung](../dateninfrastruktur-dev-stack/docs/operations.md#admin-aus-lokalen-quellen-entwickeln)
beschreibt den Wechsel und die Rückkehr zum bisherigen Admin-Image.
