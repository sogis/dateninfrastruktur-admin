# Lieferrechte und Submission

Die erste begrenzte `manage_submitters`-Zuweisung erfolgt über den
[Management-XTF-Bootstrap](schema-lifecycle.md). Der Betreiber prüft
Issuer und Subject und trägt genau einen Scope ein, ein Amt oder einen
Datensatz. Ein Amtsscope gilt für die aktuellen Datensätze des Amts.

Die interne REST-API unter `/internal/deliveries` prüft Keycloak-JWT und
den aktuellen Principal. Benutzer können berechtigte Ausgaben abfragen,
eine Submission per PUT anlegen, lesen und im Status pending abbrechen.
Die URL enthält die UUID der Submission. Beim Anlegen werden Dataset,
Issue, Modus und gegebenenfalls die Metadaten der Eingabedatei übergeben.
Der verifizierte Benutzer wird als Submitter gespeichert.

Der registrierte technische Principal ruft start, complete, reject oder
fail auf. Start prüft das aktuelle Lieferrecht des gespeicherten
Submitters und bindet Schema und Ausgangsversion. Das CSV-Validierungsmodell
entsteht direkt aus DataSchema, AttributeDefinition und UniqueDefinition.
Complete prüft die Kandidatendatei und kontrolliert in einer kurzen
PostgreSQL-Transaktion das Lieferrecht und die Ausgangsversion erneut.
Ein anderer inzwischen angenommener Snapshot führt zu 409. Ein identischer
Abschluss kann wiederholt werden.

Der Stack setzt `DI_DELIVERY_ORIGIN_URL` auf den internen, read-only
Origin. Admin ruft das serverseitig bestimmte Objekt per GET ab und
vergleicht Dateigrösse und SHA-256 in einem Streamdurchlauf vor der
Abschlusstransaktion. Start liefert bei Append den kanonischen Schlüssel
der verfügbaren Basis-CSV sowie bei beiden Liefermodi Modellname,
Eingabedatei-Metadaten und die geordneten Attribute des gebundenen Schemas.

Die API ist über das Gateway gesperrt. Browser-Formulare der Admin-UI
verwenden weiterhin JWT, Origin-Prüfung und CSRF-Schutz.
