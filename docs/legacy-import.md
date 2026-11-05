# Explizite Legacyübernahme – MA-D

> MA-F: Der normale Betrieb und die Operatoren verwenden ausschliesslich den
> gemeinsamen [STACK-Composekontext](../../dateninfrastruktur-dev-stack/docs/operations.md).
> Frühere separate Python-/Host-JVM-Laufzeitbeispiele hier sind historisch; die
> fachlichen, Modell-, Mapping- und Auditverträge bleiben gültig.


Einmaliger **Java**-Importer über Cayenne, keine laufende zweite Änderungsquelle.
`LegacyCatalogImportCommand`/`LegacyCatalogImportService` lesen die lokale
PublishedCatalog-Datei namespace-aware und ohne DTD-/externe Entitäten.
Datei- und Objekthashes, alte Identifikatoren, dauerhaft gespeicherte Ziel-UUIDs
und ursprüngliches XML stehen in der nur für den Writer les-/einfügbaren
technischen Herkunftstabelle. Sie ist kein öffentliches Katalogartefakt und keine
zweite fachliche Editierquelle.

## Tatsächlich verwendete Quellen

Im PUBLIC-Schwesterrepo:

| Datei | SHA256 |
|---|---|
| `spec/fixtures/published_catalog_full_62_entries.xtf` | `aa542ade01129de9017f4b730e41b5b0292e9cf92e42aab925d2003d6be238a1` |
| `spec/fixtures/published_catalog_examples_only.xtf` | `b112ca832797d5736fa69d5fe78bbf9fcf2ae455ab0528d90556a029d466d06a` |
| `dev/stack/seed/object-storage/ch.so.datenportal/models/SO_AGI_DataCatalog_PublishedCatalog_20260602.ili` | `19ec074f5d976c40685ca733fea1d059e59cf1c5525cbc8d63b2389890711a09` |

Header-Modell `SO_AGI_DataCatalog_PublishedCatalog_20260602`, Basecodes aus der
bereits gepinnten `SO_AGI_DataCatalog_Base_20260529`. Die Quellen sind vorhandene
synthetische Testkataloge, kein behaupteter externer Download/Produktionsbestand.
Vollständiger Dry-run des62-Root-Katalogs; tatsächlicher repräsentativer Apply
von `ch.so.wasserqualitaet_grundwasser` und
`ch.so.bevoelkerung.altersstruktur` aus der konfliktfreien Beispieldatei.

## Nutzung im eigenen Runtime

Zuerst explizites Setup: `python3 scripts/setup.py`. Dry-run schreibt nur den
privaten lokalen Bericht, keine Fach-/Herkunfts-/Auditdaten:

```sh
python3 scripts/legacy_import.py ../dateninfrastruktur-sodata/spec/fixtures/published_catalog_full_62_entries.xtf --report=.runtime/legacy-full-report.json
```

Apply verlangt ausdrückliche Auswahl und technische Actorbezeichnung:

```sh
python3 scripts/legacy_import.py ../dateninfrastruktur-sodata/spec/fixtures/published_catalog_examples_only.xtf --apply --select=ch.so.wasserqualitaet_grundwasser,ch.so.bevoelkerung.altersstruktur --actor-issuer=urn:di:operator --actor-subject=local-legacy-import --report=.runtime/legacy-apply-report.json
```

Der Pythonwrapper setzt nur Credentials aus dem eigenen identitätsgeprüften
Managementruntime und startet den Java-CLI. Ohne diesen Wrapper kann der
Gradle-JavaExec-Task `legacyImport` mit sicher gesetzten
`DI_MANAGEMENT_WRITER_JDBCURL`, `DI_MANAGEMENT_WRITER_PASSWORD` und für Apply
`DI_OPERATOR_ISSUER`/`DI_OPERATOR_SUBJECT` verwendet werden. Keine Secrets als
CLI-Argument. Keine SQLbindings im Operatorlog.

## Normalisierung und Konflikte

- Identifier bleiben erhalten; bestehende Herkunftszuordnung wird wiederbenutzt.
  Gleicher Import ist ein Skip und überschreibt spätere Adminbearbeitung nicht.
  Geänderter Quellinhalt oder Zielidentifier ohne Herkunft erzeugt Konflikt,
  keine automatische Umdeutung/Übernahme.
- `open` bleibt `open`. Bekannte übrige Codes `public_with_conditions`,
  `restricted`, `internal`, `confidential` werden mit Warnung restricted.
  Unbekannte Codes stoppen den betroffenen Apply; niemals open raten.
- Rootserie besitzt gemeinsame Metadaten. Nichtleere gemeinsame Kontakt-/
  Lizenz-/Office-/Zugangswerte müssen übereinstimmen. Ein optional fehlender
  Rootwert darf nur aus einem über alle Ausgaben eindeutigen Wert ergänzt
  werden. Widersprüche werden **nicht** per first-wins, Nullsetzung oder pauschal
  root-wins beseitigt. Die betroffene Auswahl wird nicht angewendet.
- Der volle62-Katalog enthält reale synthetische Konflikte zwischen Serien-
  und Ausgabenkontakten sowie teils unterschiedlichen Officebeschreibungen.
  Zusätzlich kann ein Dry-run nach vorherigem Apply Quellabweichungen gegenüber
  dem importierten Herkunftsstand melden. Diese Befunde bleiben im vollständigen
  Bericht; es wird nicht global die Modellregel abgeschwächt. Die vorhandene
  Beispieldatei enthält konfliktfreie repräsentative Serien.
- Identische Offices nach URI und identische Schemadefinitionen nach Modell/
  geordneter Attributdefinition werden zusammengeführt. Widersprüchliche Office-
  Definitionen stoppen statt erster Treffer gewinnt.
- Einzelangebot erhält eine interne `default`-Ausgabe. Die alte Ausgabe-ID einer
  Serie bleibt Ausgabeschlüssel. Reine Kopie der Rootbeschreibung erzeugt keine
  künstliche Note. Tatsächlich abweichende Ausgabenerläuterungen und zusätzliche
  Ausgabenstichwörter bleiben als Note erhalten; originales XML bleibt zusätzlich
  nachvollziehbar. Datenabdeckung, Ausgabenlabel und aktueller Zeiger bleiben
  aus der Quelle erhalten; mehrere Current-Zeiger sind Konflikt.
- Eine neue vorbereitete Legacyversion trägt das originale Schema/Attribute und
  vorhandene Zählwerte. Unbekannte acceptedAt/Prüfwerte bleiben unbekannt.
  Keine Importzeit als fachliches Annahmedatum und kein Modellname als
  Validierungsbeleg.
- Alte Datei-URL bleibt ausschliesslich `legacyAccessUrl`. Alle übernommenen
  Versionen/Dateien bleiben **prepared**, ohne erfundene Bytes/Hashes. Neuer
  kanonischer privater Schlüssel; kein HTTPfetch, kein produktiver S3-Write.
  Erst eigene Übernahme/Prüfung in MA-E kann eine Datei verfügbar machen.
- Apply sperrt den expliziten Import, prüft Plan erneut und schreibt Fachobjekte,
  Herkunft, Actor-Audit und Kommandoquittung atomar. Replay desselben Kommandos
  liefert dieselben UUIDs; andere Auswahl mit demselben Schlüssel wird abgelehnt.

V008 ist allein der technische additive Herkunftsschritt. Der erlaubte bekannte
B→C→D-/C→D-Pfad ist mit Vorgängerfingerprints und unveränderten alten Modell-/
Mapping-/Migrationshashes gepinnt; unbekannte DBstände stoppen vor DDL/Quellwrite.
Reale Upgrades über nichtleere UUID-/Auditdaten sind getestet. Appstart kein DDL.

`LegacyCatalogImportIT` prüft vollständigen Dry-run, echte repräsentative
Standalone-/Serienmigration, Attribute/Assoziationen, unbekannten Code,
Konfliktverweigerung, stabile UUIDs, Replay und erhaltene spätere Adminänderung.
CLI-Dry-run und ausgewählter CLI-Apply werden zusätzlich wirklich ausgeführt.

Nach Review MA-D-REV-02: Mehrere ThemeAssignment-Strukturen in einem
kanonischen themes-BAG werden vollständig gelesen und nach localTheme
dedupliziert. Identische Creator-/Kontaktstrukturen werden ebenfalls
zusammengeführt. Unterschiedliche Creator beziehungsweise Kontakte benötigen
eine explizite Owner-/Kontaktauswahl und ergeben einen strukturierten
Konfliktbericht, keinen abgebrochenen Dry-run. Auch mehrdeutige übrige skalare
Felder werden je Root als Konflikt berichtet; kein erster Treffer gewinnt.
Distributionskennungen mit mehreren Eltern werden vor dem Apply als Konflikt
gemeldet. Die neuen Tests verwenden vollständige temporäre XTF-Dateien.
