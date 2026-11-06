# Admin – Katalog, Rechte und Anlieferung

Admin verwaltet den Managementkatalog, entscheidet über fachliche Rechte
und nimmt verifizierte CSV-Lieferungen an. Die Anwendung verwendet Java 25,
Spring Boot und Apache Cayenne. INTERLIS ist die Quelle des Fachschemas.

Der [gemeinsame Compose-Betrieb](../dateninfrastruktur-dev-stack/docs/operations.md)
richtet die Management-Datenbank explizit ein. Das Toolimage prüft bei einem
vorhandenen Stand Mapping, Audit und Schema-Vertrag. Der Normalstart ist
gegenüber dem Schema lesend. Die Konfiguration bleibt im gebundenen
Runtimeordner des Stack.

```sh
./gradlew test
python3 scripts/test_models.py
```

Der Stack stellt die isolierten Integrationsprüfungen für Lieferrechte,
HTTP-Sicherheit und Sodata-Katalogmutation bereit. Modellerzeugung und
Datenbanktests benötigen die im jeweiligen Einstieg beschriebenen Werkzeuge.

- [Modellinvarianten](models/constraints.md)
- [Schema und Audit](docs/schema-lifecycle.md)
- [Lieferrechte und CSV-Annahme](docs/delivery-authorization.md)
- [Management und Dateizugang](docs/management-web.md)
