# dateninfrastruktur-admin

Bei repoübergreifender Arbeit auch die Workspace-AGENTS lesen und die
Gitgrenzen prüfen. Bestehende Arbeit und Daten schützen. Gradle und bestehende
APIs verwenden. Obsolete Pfade löschen und auf spekulative Abstraktionen,
Kompatibilitätsschichten und eigene Build- oder Reviewinfrastruktur verzichten.

Admin verwendet Java 25, Spring Boot 4, Gradle Groovy DSL und Apache Cayenne.
Die vier Managementmodelle in `models/` sind die Quelle des Fachschemas.
ili2pg erzeugt das physische Schema. Das SQL-PostScript ergänzt notwendige
TID-Constraints und die Sichten, über die Sodata ausschliesslich liest.
Schema-Setup erfolgt explizit. Der Normalstart verändert das Schema nicht.
Python-Skripte müssen mit Python 3.9 funktionieren.

OIDC/JWT mit Issuer und Audience, Browser-CSRF, interne API-Grenzen und
eingeschränkte PostgreSQL-Rollen erhalten. Pro Operation die aktuellen
Fachrechte am ganzen Datensatz prüfen. Fachentscheidung und DML gehören
in dieselbe kurze Cayenne-Transaktion. Beim Submission-Abschluss die
Basisversion innerhalb dieser Transaktion sperren und vergleichen.
Sichere XTF- und Uploadpfade, Grössenlimits und private Dateirechte erhalten.

Das CSV-Validierungsmodell aus der an die Submission gebundenen
Schemafassung erzeugen. Admin misst Dateigrösse und SHA-256 am privaten
Origin. Der GRETL-Job validiert und verarbeitet die CSV.

Vor grösseren Änderungen `.agents/skills/simplify-change/SKILL.md` anwenden.
Vor PR-Abschluss unabhängig mit `.agents/skills/review-change/SKILL.md`
prüfen. Tests nach dem betroffenen Fachverhalten auswählen.
