# Verwaltung der CSV-Lieferrechte

`manage_submitters` delegiert `submit_data` mit Zeit, Begründung und fachlichem
Scope. Ein Amtsrecht umfasst auch künftige Datensätze des Amtes. Die Oberfläche
fordert dafür eine ausdrückliche Bestätigung. `read_data`, Publikation,
Metadatenpflege und SQL-Rechte werden getrennt vergeben.

Der lokale Betreiber vergibt den ersten Lieferantenmanager nach externer Prüfung
von Issuer und Subject. Aus dem Stack wird `python3 scripts/compose_operator.py
bootstrap-submitters <private-anfrage.json>` ausgeführt. Der Request liegt im
gebundenen Runtimeordner, gehört dem aufrufenden Benutzer und hat Modus 0600.
Genau einer der beiden Scopes muss gesetzt sein.

```json
{
  "schemaVersion": 1,
  "commandId": "<UUID>",
  "officeId": "<UUID>",
  "datasetId": null,
  "verifiedIssuer": "<geprüfter Issuer>",
  "verifiedSubject": "<geprüftes sub>",
  "reason": "<Begründung>"
}
```

Eine inaktive Identität wird dabei nicht reaktiviert. Admin prüft aktuelle
Lieferberechtigung an den servergebundenen Submissions und Ausführungen. Der
Jenkinsjob verarbeitet den gebundenen Plan. Technische Jobparameter begründen
keine Fachrechte.

Der Admin-Fachcommit erfordert die Integritätsbelege der Verarbeitung und die
erneute Prüfung von Scope und Basisversion. ClickHouse-Projektion und deren
Wiederanlauf erfolgen nach der PostgreSQL-Annahme. Die aktiven Definitionen
liegen im [Themenrepo](../../dateninfrastruktur-themenrepo/README.md).
