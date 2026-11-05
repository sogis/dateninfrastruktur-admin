# dateninfrastruktur-admin

Management-/Access-Anwendung mit Java25, Spring Boot4.1.2 und Apache Cayenne5.0-M3;
kein JPA/Hibernate. Modelle, Transaktionen, Audit und fachliche Autorisierung aus
MA-0–E bleiben erhalten. Aktueller Auftrag ist MA-G im Schwesterrepo
`dateninfrastruktur-dev-stack/docs/implementation/storage-and-delivery/`.

Der normale Start gehört ausschliesslich zum gemeinsamen Compose-Stack:

```sh
cd ../dateninfrastruktur-dev-stack
./scripts/setup.sh
./scripts/up.sh --skip-build
./scripts/status.sh
```

Bestehende Daten benötigen zuerst die kontrollierte Migration, keinen leeren
Neuaufbau. Siehe [Betrieb](../dateninfrastruktur-dev-stack/docs/operations.md),
[Übernahme/Rückfall](../dateninfrastruktur-dev-stack/docs/migration-ma-f.md) und
[aktuellen Abnahmestand](../dateninfrastruktur-dev-stack/docs/implementation/storage-and-delivery/WORK_QUEUE.md).
Konfiguration und Secrets liegen nur im aktiven STACK/.runtime-Bereich.
`setup.py`, `managed_setup.py`, `managed_runtime.py`, `identity_runtime.py` und
`run.py` delegieren; sie erzeugen keine eigenen Container oder Host-JVM-Instanzen.

Schema-/Auditwerkzeuge benötigen einen expliziten PostgreSQL-Toolkontext und laufen
über Compose. Bei Bestand werden Vertrag, Mapping und Audit nur geprüft.
Der explizite Operator `STACK/scripts/compose_operator.py bootstrap-submitters`
vergibt ausschliesslich den ersten `manage_submitters`-Auftrag im angegebenen
Scope. Er erwartet eine private, begrenzte JSON-Datei. Manuelle
Versionsregistrierung und Dateiverifikation/-aktivierung sind entfernt. Neue
CSV-Versionen entstehen ausschliesslich über den validierten Ingestionabschluss.
Kein automatischer Fachseed.

Javafachtests und bestehende JVM-/Native-Gradle-Buildoptionen bleiben erhalten.
Die früheren separaten Python-Containerharnesses sind retired; aktuelle isolierte
Integrationstests für MA-G2: `STACK/scripts/test_ingestion_schema_g2.py` und
`STACK/scripts/test_ingestion_backend_g2.py`. Frühere MA-F-Harnesses dokumentieren
den Vorgänger und sind kein aktueller API-Vertrag.
Die aktuellen Modelle lassen sich ausdrücklich mit
`python3 scripts/test_models.py` kompilieren/validieren; das ist kein Normalstart.

Technische Verträge: [Modellinvarianten](models/constraints.md),
[Schema/Audit/Mapping](docs/schema-lifecycle.md), [Legacyimport](docs/legacy-import.md).
Historische Laufzeitbeispiele in den MA-A–E-Dokumenten sind keine zweite aktive
Betriebsarchitektur. Rohbelege privat unter .runtime; keine Produktions-/Native-
Zusage und keine persönliche ClickHouse-Rechteprojektion.
