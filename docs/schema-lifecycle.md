# Managementschema, Mapping und Audit

Die Fachquelle liegt in `models/`. Stock-ili2pg erzeugt das physische Schema
`di_management` mit den Optionen aus `db/mapping-profile.json`. Der reale
Mappingvertrag ist in `db/model-mapping.json` festgehalten. Physische Bezeichner
wie `akind` und `adescription` folgen der Mapperausgabe.

Das fachliche UUIDOID liegt in `t_ili_tid`, der technische Schlüssel in
`t_id`. Cayenne reserviert Werte aus der gemeinsamen `t_ili2db_seq` auf
derselben Transaktionsconnection. Die DataMap verwendet die geprüften
PostgreSQL-Typen. `normalize_cayenne_uuid.py` setzt den Java-Typ ausschliesslich
für die nachgewiesenen UUID-Spalten. Die generierten Basisklassen sind von
den handgeschriebenen Unterklassen getrennt. Der normale Build introspektiert
die Datenbank nicht.

## Explizites Setup und Prüfung

Der [Compose-Betrieb](../../dateninfrastruktur-dev-stack/docs/operations.md)
richtet die leere Management-Datenbank ausdrücklich ein. Die Baseline
`management-20261106` bindet die Modelle, das Mappingprofil, die
ili2pg-Referenzmetadaten und die technischen SQL-Schritte über
`db/schema/manifest.json`. Nach dem Modellimport installiert der Aufbau
Audit, Constraints, öffentliche Sichten, Anlieferungszustände und Rechte.

`python3 scripts/persistence_contract.py --check` prüft die kanonischen
Package-Properties aus dem Manifest. Gradle führt dieselbe Prüfung vor dem
Packen aus. Setup prüft zusätzlich den tatsächlich installierten Vertrag,
bevor es das erste Baseline-Ereignis und den Artefaktmarker einträgt. Beim
erneuten Setup werden Mapping, Modellmetadaten, Struktur, Rechte, Trigger
und Marker lesend verglichen. Ein nichtleerer unbekannter Zustand bricht ab.

Java vergleicht beim Start den verpackten Artefaktdigest und den
physischen Fingerprint. Letzterer umfasst die relevanten PostgreSQL-Kataloge
einschliesslich ACLs, Constraints, Views, Indizes, Triggern, Sequenzbindungen
und ili2pg-Metadaten. Er sortiert ACLs und berücksichtigt gleichwertige
Darstellungen der bekannten CHECK-Literalarrays. Fachzeilen, Auditdaten,
Sequenzstände und Importzeitpunkte gehören zum separaten Erhaltungsnachweis.

Der normale Start führt keine DDL, Migration oder Fachseeds aus. Der
Audit-Owner liest die Fachziele aus dem durch das Audit aufgelösten Pfad
und benötigt kein direktes Leserecht an der CSV-Konfiguration. Änderungen
am externen Principal-Issuer bleiben gesperrt.

## Rollen und Audit

`di_owner` und `di_audit_owner` sind NOLOGIN. Das Webbackend nutzt
`di_writer` mit den erforderlichen DML- und Sequenzrechten. `di_authz` liest
gezielt die Autorisierungsgrundlagen, `di_public` nur freigegebene
`api_public`-Sichten. Rollen für Setup und explizite technische Änderungen
sind vom Anwendungszugriff getrennt.

Fachoperationen verwenden neue Cayenne-Kontexte. Audit-Actor und
Command-Kontext werden in derselben physischen Verbindung wie die
Fachmutation an die Transaktion gebunden. Fehlende Auditkontexte verhindern
die Mutation. Freigaben und wirksame Zuständigkeiten werden für die
jeweilige Operation aus dem aktuellen PostgreSQL-Stand ermittelt.
