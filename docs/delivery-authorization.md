# Verwaltung der CSV-Lieferrechte

`manage_submitters` delegiert `submit_data` mit Zeit, Begründung und fachlichem
Scope. Ein Amtsrecht umfasst auch künftige Datensätze des Amtes. Die Oberfläche
fordert dafür eine ausdrückliche Bestätigung. `read_data`, Publikation,
Metadatenpflege und SQL-Rechte werden getrennt vergeben.

Die erste begrenzte `manage_submitters`-Zuweisung erfolgt über den
[Management-XTF-Bootstrap](schema-lifecycle.md). Der Betreiber prüft
Issuer und Subject der Person und trägt genau einen Scope ein, ein Amt
oder einen Datensatz. Die OID eines Amtes ist eine Text-TID.

Admin prüft aktuelle
Lieferberechtigung an den servergebundenen Submissions und Ausführungen. Der
Jenkinsjob verarbeitet den gebundenen Plan. Technische Jobparameter begründen
keine Fachrechte.

Der Admin-Fachcommit erfordert die Integritätsbelege der Verarbeitung und die
erneute Prüfung von Scope und Basisversion. ClickHouse-Projektion und deren
Wiederanlauf erfolgen nach der PostgreSQL-Annahme. Die aktiven Definitionen
liegen im [Themenrepo](../../dateninfrastruktur-themenrepo/README.md).
