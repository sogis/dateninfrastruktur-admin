# dateninfrastruktur-admin

Management-/Access-Anwendung: MA-0, MA-A und MA-B unabhängig abgenommen;
MA-C-Verwaltung/Identität/Authorizationkern implementiert, Gate im STACK.
Java25, Spring Boot4.1.2 GA, Apache Cayenne5.0-M3; kein JPA/Hibernate.
Gesamtauftrag im Schwesterrepo unter
`dateninfrastruktur-dev-stack/docs/implementation/management-and-access/`.

```sh
python3 scripts/setup.py
./gradlew bootJar
python3 scripts/identity_runtime.py start
```

Explizites Setup erhält eigene Daten/Secrets und importiert bekannte Schemata
nicht erneut. Details zum JVM-/Keycloak-/APISIX-Start, lokalen Operatorbootstrap,
URLs, Testcredentials, Grenzen und Cleanup: [Managementbetrieb](docs/management-web.md).
Noch keine Sodata-DB-Umschaltung oder geschützte Dateiauslieferung vor MA-D/MA-E.

```sh
python3 scripts/test_models.py
python3 scripts/test_persistence_all.py
python3 scripts/test_management_all.py
python3 scripts/compatibility.py
```

Die Persistenzsuite verwendet einen neuen eigenen nativen PG16/PostGIS-Container,
zufälligen Loopbackport und frische Testcredentials. Nur dieser Container und
seine eigenen anonymen Volumes werden entfernt. Bei vorhandenem persönlichem
Runtimezustand stoppt sie; kein impliziter Reset. Kein Zugriff auf Nutzer-.env
oder fremde Dienste. Java25/Docker/Python3 erforderlich; gepinnte Toolarchive
werden bei Bedarf mit SHA256 geprüft heruntergeladen.

Invarianten: `models/constraints.md`.
Betrieb: `docs/management-operations.md`.
Mapping/Transaktionen/Audit/Migration/Restore: `docs/schema-lifecycle.md`.
Rohbelege ausschliesslich im ignorierten `.runtime/`. Keine ungeprüfte Native-
oder Produktionszusage und keine persönliche ClickHouse-Rechteprojektion.
