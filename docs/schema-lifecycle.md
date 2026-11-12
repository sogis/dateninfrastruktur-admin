# Managementschema und XTF

Das explizite Compose-Setup legt auf einer leeren Management-Datenbank
`di_management` mit den vier Managementmodellen `20261112` an. Das
Werkzeugimage verwendet die in `db/toolchain.json` gepinnten Werkzeuge.
Ein ili2pg-Schemaimport legt Fach- und Basket-Tabellen an. Sein
[`--postScript`](../sql/public_views.sql) korrigiert die Text-TID-Breite,
erzwingt Pflicht und Eindeutigkeit von Objekt-TIDs und erstellt die
read-only Sichten in `di_api_public`. Der PostgreSQL-Treiber von ili2pg
5.5.2 fragt beim Start PostGIS ab. `--setupPgExt` installiert die
benötigten Erweiterungen für diese neue Datenbank.

Wiederholtes Setup prüft den vorhandenen Stand lesend. Fremde,
unvollständige und frühere Managementschemas werden abgewiesen.
Die normalen Dienste installieren oder migrieren keine Tabellen.

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

Die Anwendung startet eine CayenneRuntime für den eingeschränkten
`di_writer`. Die Start- und Readiness-Prüfung lesen die Rolle und die
erforderlichen Basisrelationen. Schreibvorgänge erhalten einen eigenen
ObjectContext in einer Cayenne-Transaktion. Neue Fachobjekte erhalten
die Text- oder UUID-TID und den einzigen vorhandenen Basket ihres Topics.
Fehlende oder mehrdeutige Baskets verhindern den Schreibvorgang.

Für das Mapping führt `cdbimport` den expliziten JDBC-Zugriff auf eine
eigene Management-Datenbank aus. `DI_MAPPING_URL`, `DI_MAPPING_USER`
und `DI_MAPPING_PASSWORD` binden dieses Ziel. `cgen` erzeugt die
Klassen aus der DataMap. Die UUID-Normalisierung ergänzt ausschliesslich
Typen für PostgreSQL-UUID-Spalten, die Cayenne als JDBC OTHER liest.

Im Dev-Stack-Checkout prüft der folgende Befehl die Reproduzierbarkeit
beider Generatoren in einem eigenen Compose-Projekt, den Start der
Persistence-Konfiguration und den Export der über Cayenne eingefügten
Objekte:

```sh
python3 scripts/test_cayenne_mapping.py
```
