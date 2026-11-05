# Management-Schema und Audit-Lebenszyklus

> MA-F: Der normale Betrieb und die Operatoren verwenden ausschliesslich den
> gemeinsamen [STACK-Composekontext](../../dateninfrastruktur-dev-stack/docs/operations.md).
> Frühere separate Python-/Host-JVM-Laufzeitbeispiele hier sind historisch; die
> fachlichen, Modell-, Mapping- und Auditverträge bleiben gültig.


Der folgende Grundvertrag stammt aus MA-B. MA-C ergänzt Receipt-/Webvertrag
(V007), MA-D die technische Legacyherkunft (V008); siehe management-web.md und
legacy-import.md. Beide Schritte sind explizite gepinnte additive Upgrades.

INTERLIS ist die Fachquelle. Stock-ili2pg5.5.2 erzeugt `di_management`; der
reale Mappingstand steht in `db/model-mapping.json`, Pins/Optionen/Modellhashes
in `db/mapping-profile.json`. Keinen physischen Namen aus einem ILI-Bezeichner
erraten: kind→akind, description→adescription und mehrere andere Felder wurden
vom realen Mapper umbenannt. Assoziationen sind in diesem Profil eingebettet;
49 Modellklassen/-assoziationen ergeben16 physische Fachtabellen. Es gibt keine
ungeprüfte Pflicht, für jede Assoziation eine eigene Tabelle zu erfinden.

UUIDOID→`t_ili_tid uuid`, technischer PK→`t_id bigint` mit der gemeinsamen echten
`di_management.t_ili2db_seq`. Cayenne M3 cdbimport erkennt diese als generated,
was beim verbundenen Insert nicht funktioniert hat. `IliPkGenerator` reserviert
deshalb einen Wert mit nextval auf der **bereits bestehenden Cayenne-
Transaktionsconnection**, ohne PK-Cache, eigene Sequenz, AUTO_PK_SUPPORT oder
Runtime-DDL. Ein Einerpool und reale verknüpfte Inserts belegen den Vertrag.

M3 cdbimport lässt PostgreSQL-UUID-Objektattribute zunächst ohne Java-Typ.
`normalize_cayenne_uuid.py` setzt ausschliesslich gegen aufgezeichnete echte
UUID-Metadaten `java.util.UUID`. Unbekannte Typen/PK-Defaults brechen ab. Von DB-
Triggern gepflegte Standard-/Versionsspalten werden nicht als schreibbare
Objektattribute angeboten. Ihre NOT NULL-/Triggerregeln in PostgreSQL bleiben
bestehen. cgen-Basisklassen und handgeschriebene Unterklassen sind getrennt;
Unterklassen werden nicht überschrieben. Ein zweiter cdbimport/cgen-Lauf muss
byte-identische Dateien liefern; Gradles unzutreffendes UP-TO-DATE wird für
cgen explizit ausgeschaltet. Runtime und Buildtools sind jeweils5.0-M3.

## Explizites Setup

Aus ADMIN: `python3 scripts/setup.py`, aus STACK:
`./scripts/management/setup.sh`.

Ein neuer eigener nativer PG16/PostGIS-Container erhält eine eigene Datenbank
`dateninfrastruktur_management`, zufälligen Loopbackport, eigene Credentials
und eigene Volumes. Die vorhandene Nutzer-PostgreSQL-Instanz bleibt unverändert.
Verwendetes Image mit Manifestdigest ist in `management_db.py` gepinnt; die
reale Version ist PostgreSQL16.9/PostGIS3.5.2 auf aarch64. Stock-ili2pg benötigt
auch geometriefrei PostGIS. Keine Behauptung, diese Einrichtung sei Vanilla-PG.

Top-Level-Setup/Test sind pro Runtimepfad serialisiert. Jede weitere Ressource
wird gegen die gespeicherte Containeridentität und das MA-B-Label geprüft.
Eine vorhandene bekannte DB wird geprüft und **nicht** neu importiert. Unbekanntes
Schema bzw. abweichender Vertragsfingerprint stoppt; kein automatischer Reset.
Eigene Credentials bleiben in `.runtime/management-db/` mit Modus0600 und werden
nicht rotiert oder in App-/Reviewdateien kopiert. Vorhandene Rollen werden nicht
neu angelegt. Migrationen sind explizite bekannte Additivschritte, keine Web-DDL.

`V001` installiert Audit/Ownerrechte, `V002` zusätzliche fachliche Checks und
Schutztrigger, `V003` öffentliche Views, `V004` lesende Laufzeitprüfung, `V005`
Rootrevision bei Childänderungen, `V006` Vertragsfingerprint. Initialschema-
Metadaten plus explizite Migrationen sind gemeinsam der Vertrag. Neue ILI-
Revisionen brauchen einen neuen bewusst geprüften Upgradepfad; diese erste
Etappe bietet keinen beliebigen Schema-Update-Generator. Ein abweichender
bestehender Stand wird ehrlich abgelehnt.

`persistence-contract.properties` bindet Profil, physisches Mapping, DataMap und
alle SQL-Migrationen. Der Fingerprint liegt in der DB. Der Bootstart vergleicht
die gepackte DataMap/Vertragsdatei und den DB-Stand. Readiness prüft aktuelle
Auditabdeckung lesend; ein deaktivierter Trigger führt nachweislich503, nach
Wiederherstellung ohne Restart wieder200. Liveness ist davon getrennt.

## Konten und Transaktionen

`di_owner` und `di_audit_owner` sind NOLOGIN. `di_migrator` ist nur für explizite
Migration vorbereitet; keine Webcredentials. Der lokale Containerbootstrap
verwendet den getrennten technischen Operator für Extensions/Erstaufbau.
`di_writer` hat benötigte DML/Sequenzrechte, kein Owner/DDL/TRUNCATE/CREATEROLE/
BYPASSRLS und kein SET ROLE zum Owner. `di_authz` liest nur die benötigten
Autorisierungsbeziehungen, insbesondere keine Antragsbegründungen oder Audit.
`di_public` liest ausschliesslich benannte `api_public`-Views. Optionales
`di_audit_reader` ist vorbereitet, noch nicht als allgemeiner Audit-Webzugang
freigeschaltet; Scope/UI folgen MA-C.

Neue ObjectContexts pro Command, Shared-Snapshot-Cache aus. Autorisierungs-
entscheidungen folgen erst MA-C als frische JDBC-Abfragen über di_authz.
`AuditedUnitOfWork` startet die äussere Cayenne-Transaktion, setzt Actor/Command
mit transaktionalem SQLExec, führt die Fachoperation/Flush aus und liefert erst
nach erfolgreichem äusserem Commit zurück. Kein @Transactional-Beweisersatz.
Tests vergleichen PostgreSQL-Transaktions-ID, echten Actor und session_user,
belegen Rollback nach erfolgreichem ORM-Flush und die fehlende Actorleckage.
Ein absichtlich unmöglicher Auditinsert rollt auch die Fachmutation zurück.
FOR UPDATE/expectedVersion verhindern nachweislich ein veraltetes Update.

ILI EventTime liegt physisch als timestamp without time zone vor. Fachwerte
sind über UtcTime explizit UTC-normalisierte LocalDateTime, keine Hostlokalzeit.
Der reale JDBC-/ORM-Roundtrip ist mit PG-Sessionzeitzone Pacific/Auckland geprüft. DATE bleibt DATE;
Auditzeit ist timestamptz/clock_timestamp. Fachservices müssen Instant↔UTC
explizit umrechnen. Weder Sequenz noch txid/recorded_at behaupten eine globale
Commitreihenfolge. Technisches t_user bleibt di_writer; volle Actoridentität
steht separat im Audit, ohne Trunkierung auf eine kurze DB-Spalte.

## Audit und Grenzen

`audit_setup.py plan|apply|verify` löst di.audit-/Redaction-Metaattribute über
echte ili2db-Mappings auf, einschliesslich eingebetteter Beziehungen. Unaufgelöste
annotierte Elemente/physische Fachtabellen brechen ab. `db/audit-policy.json`
ist die konkret aufgelöste Sollmenge. BEFORE-Stempel und AFTER-I/U/D-Audit haben
SECURITY DEFINER mit festem search_path und kontrolliertem NOLOGIN-Owner.
Coverage prüft Solltabellen, Timing, Events, Enabledstatus, Funktion und
Redaction. Ein deaktivierter Membershiptrigger wird real erkannt.

Old/New, geänderte Spalten, command/request, echte Actoridentität und technischer
DBuser bleiben zusammen. Datasetbezug wird aus tatsächlichen Elternpfaden
aufgelöst; frühere Parent-Auditzeilen derselben Transaktion erhalten den Bezug
auch bei Parent-vor-Child-Löschung. Principal-/Membership-/Officeereignisse werden nicht einem beliebigen
Dataset zugeschrieben. Anzeigenamen sind redacted; Gründe/Entscheide bleiben
fachlich nachvollziehbar. No-op erzeugt keine künstliche Historie. Rootrevision
wird bei Childänderungen invalidiert. Entscheidungen sind immutable; Grantbasis
kann nicht umgedeutet, ein Widerruf nur einmal unmittelbar gesetzt werden.

GUC-Werte sind kein kryptografischer Identitätsbeweis: wer Writercredentials
besitzt, könnte Actorwerte selbst setzen. OIDC-/Serviceprüfungen folgen MA-C;
DBowner/Superuser können Historie manipulieren. Audit bildet weder SELECTs noch
rollbackte Versuche oder vollständige DDLhistorie ab. Keine Rechtszertifizierung.

## Reproduzierbare Prüfung und Restore

`python3 scripts/test_persistence_all.py` erstellt einen frischen eigenen
Container, echte Rollen/Schema/ORMdaten, führt M07–M13 aus und entfernt nur
seine eigenen Ressourcen. Bei bereits vorhandenem Runtimezustand stoppt der
Test und erhält ihn; kein implizites Löschen. Migrations-/Installer-Re-run
bewahrt UUIDs und Audit. Eine begrenzte echte pg_dump/pg_restore-Probe in einer
separaten DB erhält Daten/Audit und Publicrechte; kein allgemeines Backupkonzept
oder vollständiger historischer Zustandsrestore wird behauptet.

Rohbelege unter `.runtime/management-db/` und `.runtime/persistence-all.log`.
Kein H2. Keine Foundation-/Keycloak-/Superset-Vollsuite für diese Phase.
