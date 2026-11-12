# dateninfrastruktur-admin

Im gemeinsamen Dateninfrastruktur-Workspace vor repoübergreifender Arbeit
`../AGENTS.md` ausdrücklich lesen und die Repo-Grenzen verifizieren. Bei einem
Einzelclone gelten die lokalen Regeln und Bedienhinweise im README. Für
repoübergreifende Aufträge zuerst den verifizierten Workspace bereitstellen.

Dieses Repo besitzt Management, fachliche Autorisierung, CSV-Annahme,
Publikation und PostgreSQL-Verträge. Java, Spring Boot und Apache Cayenne
folgen den gepinnten Versionen. INTERLIS und gepinntes ili2pg erzeugen das
Fachschema. Das physische Mapping und die öffentlichen SQL-Sichten gehören
zum aktuellen Schema-Vertrag.

Fachentscheidungen und ihre DML laufen in einer kurzen Cayenne-Transaktion.
Der Abschluss einer Submission sperrt und vergleicht die Basisversion in
derselben Transaktion, die den neuen Stand freigibt. Aktuelle Fachrechte am ganzen
Datensatz in der jeweiligen Operation prüfen. OIDC/JWT mit Issuer/Audience,
Browser-CSRF und interne API-Grenzen erhalten. Die Authorization-API trifft
Entscheide, die Auslieferung übernimmt der Origin.

Setup und Normalstart gehören zum gemeinsamen Stack. Für Modell- und
Mappingprüfungen gelten die beschriebenen Befehle im README und die
gepinnten Werkzeuge im Repo.
