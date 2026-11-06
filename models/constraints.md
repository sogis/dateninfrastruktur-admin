# Modellinvarianten und ausführbare Zuständigkeiten

Die Modelle Catalog und Access `20261105` verwenden ein Storagebackend.
`Distribution.objectKey` ist eindeutig.

Zwei INTERLIS2.4-Modelle, keine zusätzliche Base-Familie. `Dataset(kind=series)`
ist der einzige Rechteanker. Issue/Version/Distribution sind keine ACL-Ziele.
Alle fachlichen Objekte besitzen UUIDOID; technische Schlüssel entstehen erst
aus dem tatsächlichen ili2pg-Mapping. Keine Tabellen-/Sequenznamen raten.

| Invariante | INTERLIS und Validator | PostgreSQL | Admin-Anwendung |
|---|---|---|---|
| Pflichtwerte, Domänen und Beziehungen | reale Pflichtattribute/Rollenkardinalitäten | generierte NOT NULL/FK prüfen und ergänzen | DTO-/Inputprüfung |
| stabile UUID-OID | Topic UUIDOID; deterministische Test-UUIDs | TID-UUID, technische PK getrennt; Mapping real prüfen | UUID einmal erzeugen, niemals umdeuten |
| Dataset.identifier/Office.identifier/abbreviation/Schema.identifier/Principal(issuer,subject) eindeutig | UNIQUE; negativer Identifierfall | reale Unique-Indizes prüfen | Konflikt verständlich melden |
| Issue(dataset,issueKey), Version(issue,versionKey)/(issue,sequence), Attribut(schema,name)/(schema,ordinal), Thema/Keyword(dataset,value), Distribution(objectKey) eindeutig | UNIQUE über echte Rollen | reale Indizes ergänzen, falls Mapper nicht vollständig erzeugt | keine stillen Duplikate |
| einzig Dataset AccessLevel; keine gemeinsamen Metadatenkopien auf Child | Strukturprüfung am erzeugten IMD16 | keine zusätzlichen Child-ACL-Spalten/Tabellen | alle verfügbaren Dateien erben Rootrecht |
| Authority genau ein Scope | echter XOR mit DEFINED, negativer Fall | CHECK office/dataset XOR | aktuelle Zuständigkeit innerhalb Schreibtransaktion |
| gültige halb offene Intervalle | validFrom < validUntil, negative Probe; Requestende nach Requestzeit | CHECKs für Zeiten, DB-Zeit zur Auswertung | serverseitige Zeit, kein frei gesetztes asOf |
| Stichtag XOR Intervall; Start<=Ende | MANDATORY CONSTRAINT, offene/fehlende Abdeckung erlaubt | CHECKs ergänzen | keine erfundenen Zeitwerte |
| Gruppe/Member-Typ, keine Verschachtelung, Antragsteller user | Constraints über Principal.kind, negativer Gruppenfall | kontrollierte Constrainttrigger | gruppenadministrative Operatorbefugnis getrennt |
| RevokedAt zusammen mit Revoker/RevocationDecision | Constraints | Schutztrigger: Enddaten nur einmal setzen | kein Reaktivieren, kein vorgemerkter Revoke |
| approve/deny braucht Request; direct_grant/revoke ohne Request | Constraint; positive direkte Vergabe | Schutztrigger | Request/Dataset/Beneficiary konsistent, atomarer Decision/Grant/Audit-Commit |
| Grant stammt von approve/direct_grant | Constraint | FK plus Schutztrigger | ursprüngliche Basis/Gültigkeit nicht umdeuten |
| immutable Decision/CatalogAction und bereitgestellte Lieferungen/Schema | fachliche Struktur; kein Änderungszeitpunkt als zweite Wahrheit | Runtimeprivilegien und Schutztrigger | neuer Entscheid/Version statt UPDATE/DELETE |
| pending Request höchstens einmal | nicht statisch pro Status erzwingen | partieller Unique-Index | Idempotenz/Rowlock/Revision, terminale Entscheidungen atomar |
| currentIssue/currentVersion gehört zum Parent, Version available | Beziehungen vorhanden; regelübergreifender Schutz noch offen | kontrollierte Constrainttrigger | Parent-/Verfügbarkeitsprüfung unter Rootlock |
| standalone genau eine interne default-Ausgabe | kind/issueKey darstellbar | kontrollierter Constrainttrigger bei relevanten Übergängen | keine künstliche Serie im UI; Erstaufbau darf vor Issue existieren |
| available-Version akzeptiert und validationStatus!=fail | Modellconstraints | CHECKs | keine erfundenen Liefer-/Prüfwerte |
| available-Distribution hat Hash und Grösse | Constraint + negative Probe | CHECKs plus Elternstatusschutz | tatsächlichen privaten Storageinhalt/Hash/Bytes bestätigen |
| Sha256 exakt64 kleine Hexzeichen; SafeFileName ASCII, keine Pfade | Längendomänen, keine Behauptung einer Regexvalidierung | CHECK regex SHA256/Filename | gleiche Regeln für Registrierung und kanonischen URL-Parser |
| Distribution.objectKey exakt downloads/UUID/fileName; kein Austausch derselben ID | Pflichtfeld/Unique, kein erfundener Inhaltsbeweis | Schutztrigger für immutable Identität/Inhalt | VerifyAndActivate, Originpfad und Autorisierung identisch |
| active Principals, aktuelle Authority/Grants/Gruppen | darstellbar; keine Loginbehauptung | echte aktuelle JDBC-Abfrage gegen Primärbestand | Login/JWTprüfung; Authority kein implizites READ |
| Audit auf Root/Relationships/Hilfstabellen | di.audit=full auf sämtlichen Klassen und Assoziationen, IMD-Prüfung | externer Installer über tatsächliche Mappings, Coverage | Actor/DML gleiche physische Transaktion |

Das [Schema-Manifest](../db/schema/manifest.json) bindet das ili2pg-Mapping
und die technischen SQL-Ergänzungen. PostgreSQL- und Anwendungsprüfungen
decken Regeln ab, die über den Modellvalidator hinausgehen.

## EventTime und Modellwerkzeuge

EventTime ist ein expliziter `FORMAT INTERLIS.XMLDateTime`-Wertebereich, semantisch
UTC. XTF-Werte enthalten keine lokale Zeitzone; Anwendung/Import müssen UTC
normalisieren. Das physische Mapping bindet die SQL-Typen. DATE bleibt ein fachliches Datum.

Reproduzierter Validator1.15.0-/iox1.24.4-Befund: `EventTime = DATETIME` erzeugt
einen verschachtelten Alias. Beim Vergleich zweier belegter Grantzeiten entsteht
`incompatible values`, obwohl Compiler5.6.9 den Ausdruck akzeptiert. Die konkrete
Formatbereichsdefinition vermeidet das Problem; der ungültige Intervallfall
wird danach wirklich als verletzte Mandatory Constraint erkannt. Keine
Constraints deaktiviert und kein Tool gepatcht. Compiler5.6.9 sowie eingebettete
Compiler5.6.8 von ili2pg/Validator sind dokumentiert und unverändert.

`Principal.displayName` trägt bewusst `di.audit.redact=true`: Anzeigenamen sind
keine Identitätsquelle und müssen nicht in jeder generischen Änderungszeile
wiederholt werden. Verifizierter issuer/subject des Actors bleiben im
Audit vollständig; fachliche Gründe/Entscheide bleiben nachvollziehbar.

## Vokabulare, Fixtures und Aufruf

Origin, Theme und MaintenanceFrequencyCode wurden wortgleich aus dem gepinnten
alten Base-Modell übernommen; Quellkopie in `vendor/`, SHA256
`fbb1bccc0e84795284f9fb1a3c0be9e6db516c3c4b8ae43ad2f856cf70948431`.
Diese Kopie ist Referenz, kein drittes neues Fachmodell und keine zweite
schreibende Quelle. Kein bisheriger AccessLevel-Code als open geraten.

```sh
python3 scripts/test_models.py
```

Der Aufruf lädt fehlende gepinnte Toolarchive mit SHA256-Prüfung, kompiliert in
Abhängigkeitsreihenfolge, erzeugt IMD16, validiert echte XTF2.4-Dateien ohne
abgeschaltete Constraints und prüft die vorgesehenen negativen Fehlermeldungen.
Rohbelege unter ignoriertem `.runtime/model-tests/`. Kein Dienststart nötig.

Das positive XTF enthält synthetische AFIN/AGI/fremdes Amt; Anna/Ben/Claudia/Dora,
Bootstrapservice und lokale Gruppe; open-Einzelangebot, restricted-Serie mit
zwei Ausgaben, Draft; verfügbare/vorbereitete/zurückgezogene Stände/Formate;
Genehmigung/Ablehnung/Direktvergabe/Widerruf sowie aktive, künftige und
abgelaufene Grants. Diese Datei ist **kein produktives Seedkommando**. Sie
behauptet keine existierenden Dateien oder fachlich historische Testereignisse.
`model_fixtures.py` regeneriert genau diese Fixtures, wird beim Test nicht
aufgerufen: ein geänderter Sollwert kann nicht die Negativdateien still heilen.
