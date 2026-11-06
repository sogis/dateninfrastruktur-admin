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
richtet eine leere Management-Datenbank explizit ein. Die Modelle kommen vor
den technischen SQL-Definitionen für Audit, Constraints, öffentliche Sichten,
Berechtigungszustand, Receipt- und Anlieferungstabellen. Für einen bereits
eingerichteten Stand überprüft das Setup den Vertrag lesend.

`db/persistence-contract.properties` bindet Profil, Mapping, DataMap und
die aktuell benötigten SQL-Inputs. Der Java-Start vergleicht die verpackten
Properties und den aktuellen DB-Marker. Ein unerwarteter Vertrag verhindert
den Start. Die Readiness prüft den aktiven Vertrag, die Liveness bleibt davon
getrennt. Der normale Start führt keine DDL, Migration oder Fachseeds aus.

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
