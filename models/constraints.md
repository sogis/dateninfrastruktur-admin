# Managementmodelle

Organization, Catalog, Access und Delivery sind gemeinsam INTERLIS 2.4.
Die Modellgeneration `20261112` hat je Topic UUID-Baskets. Organization
verwendet Text-OIDs bis 200 Zeichen. Catalog, Access und Delivery verwenden
UUID-OIDs. Das Bootstrap-XTF enthält vier getrennte BIDs und ein Amt mit
einer Text-TID von genau 200 Zeichen. Basket-IDs, Objekt-TIDs und
PostgreSQL-Schlüssel sind unterschiedliche Identitäten.

Office und ContactPoint stehen unabhängig in Organization. Der Catalog
referenziert beide über externe Rollen. ThemeEntry und KeywordEntry sind
eingebettete Strukturen. Die kantonalen Codes und Anzeigenamen sind im Catalog definiert.

Ein Dataset ist der Rechteanker für alle Ausgaben, Versionen und Dateien.
Die Modelle prüfen u. a. eindeutige Ausgabe- und Versionsschlüssel, Scope,
Zeitintervalle, Gruppentyp, Widerruf und die Zugehörigkeit von Submission,
Ausgabe, Basis- und Ergebnisversion. Die Anwendung prüft aktuelle Rechte,
die Zusammengehörigkeit von Antrag, Entscheidung und Grant sowie
Zustandswechsel und Veröffentlichung in ihren jeweiligen Transaktionen.

Die CSV-Struktur liegt in DataSchema, AttributeDefinition, UniqueDefinition
und UniqueField. Ein geordneter mehrteiliger Schlüssel wird als
`UNIQUE` ausgegeben. Ein vorhandenes `minimumObjectCount` erzeugt
`SET CONSTRAINT (BASKET)`. `DataVersion.objectCount` ist Statistik.
Die XTF-Datei unter `tests/fixtures/` relativ zum Repo ist synthetisch.
Darin enthaltene Dateimetadaten dienen der Modellprüfung.

```sh
python3 scripts/test_models.py management
python3 scripts/test_models.py csv
```

Die erste Prüfung kompiliert alle Managementmodelle in einem Aufruf und
prüft positive und negative Transfers. Die zweite erzeugt ein Testmodell
aus den TEXT-Attributen und der mehrteiligen UNIQUE-Regel des Bootstrap-XTF.
Sie prüft das Modell mit ili2c und die Datenregeln mit ilivalidator.
