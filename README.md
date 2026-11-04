# dateninfrastruktur-admin

Management-/Access-Anwendung, aktuell MA-0-Kompatibilitätsspike. Java25,
Spring Boot4.1.2 GA und Apache Cayenne5.0-M3. Kein JPA/Hibernate.
Der vollständige Auftrag liegt im Schwesterrepo unter
`dateninfrastruktur-dev-stack/docs/implementation/management-and-access/`.

Die gezielte echte PostgreSQL-Probe aus diesem Repository ausführen:

```sh
python3 scripts/compatibility.py
```

Sie startet ausschliesslich einen neuen eigenen PG16/PostGIS-Container mit
zufälligem Loopbackport und frischen Testcredentials. Sie entfernt danach nur
diesen Container inklusive seiner eigenen anonymen Volumes. Kein Zugriff auf
die Nutzer-`.env`, keine Mutation bestehender Container. Docker/Internet beim
Erstlauf und Java25 erforderlich. INTERLIS-Toolarchive werden gemäss
`db/toolchain.json` heruntergeladen und SHA256-geprüft.

Geprüft werden Bootstart, echte ORM-DML, identische Backend-PID/Transaktions-ID
bei Actorbindung und Trigger, äusserer Rollback nach Flush und Actortrennung
bei Poolgrösse1. Fachmodelle, produktiver DB-/Securityvertrag und UI folgen erst
nach unabhängigem MA-0-Gate. Kein ungeprüftes Native-Versprechen.

MA-A-Modelle und echte XTF-Validierung:

```sh
python3 scripts/test_models.py
```

Invarianten und noch offene DB-/Servicepflichten: `models/constraints.md`.
