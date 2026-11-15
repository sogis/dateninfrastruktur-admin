# Managementschema und XTF

Das explizite Compose-Setup legt auf einer leeren Management-Datenbank
`di_management` mit den vier Managementmodellen `20261112` an. Das
Werkzeugimage verwendet die in `db/toolchain.json` gepinnten Werkzeuge.
ili2pg 5.5.2 legt Fach- und Basket-Tabellen an. Der Schemaimport verwendet
`--createBasketCol`, `--createTidCol`, `--createFk`, `--createFkIdx`,
`--createUnique`, `--createNumChecks`, `--createTextChecks`,
`--createDateTimeChecks` und `--createMandatoryChecks`.
Das [PreScript](../sql/schema_import.sql) begrenzt die Sonderbehandlung
von Backslashes auf die ili2pg-Transaktion. So prüft PostgreSQL
Steuerzeichen in einzeiligem TEXT als Steuerzeichen.
Das [PostScript](../sql/public_views.sql) entfernt die von ili2pg 5.5.2
fälschlich für optionale Rollen erzeugten Mandatory-Checks. Die
Fremdschlüssel bleiben wirksam. Das Skript erzwingt Pflicht und Eindeutigkeit
von Objekt-TIDs und erstellt die read-only Sichten in `di_api_public`.
Fachliche INTERLIS-Bedingungen prüft weiterhin ilivalidator 1.15.1. Der PostgreSQL-Treiber von ili2pg
5.5.2 fragt beim Start PostGIS ab. `--setupPgExt` installiert die
benötigten Erweiterungen für diese neue Datenbank.

Sodata liest die Sichten mit `di_public`. Eine Distribution erscheint
mit einem Gateway-Pfad, wenn Dataset und Ausgabe publiziert, Version und
Datei verfügbar sowie Prüfsumme und Grösse vorhanden sind. Der gespeicherte
private Key muss zum Pfad `downloads/<distribution-tid>/<dateiname>`
passen. APISIX holt die Freigabe für jeden Abruf beim Admin ein.

Setup und Admin-Start prüfen die vorhandenen Text-TID-Spalten für Office
und ContactPoint lesend. Beide müssen `varchar(200)` sein. Das Setup
weist auch fremde, unvollständige und frühere Managementschemas ab.
Diese Schemafassung ist für neue Instanzen vorgesehen.

Für einen neuen, ausdrücklich gewählten Compose-Kontext eine private
XTF-Datei im gebundenen Runtimeordner bereitstellen. Die
[synthetische Vorlage](../tests/fixtures/management-bootstrap.xtf) dient
isolierten Tests. Aus dem Dev-Stack-Checkout ausführen:

```sh
python3 scripts/compose_operator.py import "$runtime/management-bootstrap.xtf" --project "$project"
python3 scripts/compose_operator.py export "$runtime/management-export.xtf" --project "$project"
```

Der Import ist auf einen leeren Management-Basket-Bestand begrenzt.
Er prüft den Transfer und die BIDs an Referenzen gegen die tatsächlichen
Zielobjekte, validiert ihn mit ilivalidator und importiert mit
`--importBid --importTid`. Der Export schreibt in eine neue private Datei
und erhält die Objekt-TIDs und die Basket-BIDs.

Die PostgreSQL-Qualifikation startet ein eigenes Compose-Projekt,
führt den echten Import/Export aus und räumt dessen Volumes am Schluss
auf. Aus dem Dev-Stack-Checkout:

```sh
python3 scripts/test_management_bootstrap.py
```

## Cayenne-Mapping

Die Anwendung startet eine CayenneRuntime (5.0-M3) für den eingeschränkten
`di_writer`. Die Start- und Readiness-Prüfung lesen die Rolle und die
erforderlichen Basisrelationen. Schreibvorgänge erhalten einen eigenen
ObjectContext in einer Cayenne-Transaktion. Neue Fachobjekte erhalten
die Text- oder UUID-TID und den einzigen vorhandenen Basket ihres Topics.
Fehlende oder mehrdeutige Baskets verhindern den Schreibvorgang.

Für das Mapping führt `cdbimport` den expliziten JDBC-Zugriff auf eine
eigene Management-Datenbank aus. `DI_MAPPING_URL`, `DI_MAPPING_USER`
und `DI_MAPPING_PASSWORD` binden dieses Ziel. `cgen` erzeugt die
Klassen aus der DataMap. `./gradlew cdbimport cgen` liest die Datenbank und generiert die Klassen.
`./gradlew cgen` allein verwendet die vorhandene DataMap. Die zwischen
Import und Codegenerierung ausgeführte UUID-Normalisierung ergänzt
`java.util.UUID` bei fehlenden Typen für `t_ili_tid` und `commandid`,
wenn Cayenne die Spalten als JDBC OTHER liest. Office und ContactPoint
behalten ihre Text-TIDs. Die Zeitattribute verwenden `LocalDateTime` in
UTC. Der Management-Shared-Cache bleibt deaktiviert. Cayenne 5.0-RC1
liefert beim Reverse Engineering weiterhin untypisierte UUID-Attribute.
Sein verändertes DataMap-Format und seine geänderten Laufzeit-APIs
erfordern zusätzliche Anpassungen, daher verwendet Admin 5.0-M3.

Im Dev-Stack-Checkout prüft der folgende Befehl die Reproduzierbarkeit
beider Generatoren in einem eigenen Compose-Projekt, den Start der
Persistence-Konfiguration und den Export der über Cayenne eingefügten
Objekte:

```sh
python3 scripts/test_cayenne_mapping.py
```
