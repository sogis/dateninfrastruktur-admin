# dateninfrastruktur-admin

Management-/Access-Anwendung: MA-0 und MA-A unabhängig abgenommen;
MA-B-Persistenz/Audit implementiert und zur unabhängigen Prüfung vorbereitet.
Java25, Spring Boot4.1.2 GA, Apache Cayenne5.0-M3; kein JPA/Hibernate.
Gesamtauftrag im Schwesterrepo unter
`dateninfrastruktur-dev-stack/docs/implementation/management-and-access/`.

```sh
python3 scripts/setup.py
python3 scripts/run.py
```

Explizites Setup erhält vorhandene eigene Daten/Secrets und importiert ein
bekanntes Schema nicht erneut. JVM-Start mit getrenntem Writer-/Authz-Konto,
lesender Vertragsprüfung und ohne DDL. Loopback-Readiness standardmässig unter
`http://127.0.0.1:18090/actuator/health/readiness` (real geprüft).
Admin-/Self-Service-UI, OIDC und geschützte Downloads folgen erst nach den
weiteren unabhängigen Phasengates.

```sh
python3 scripts/test_models.py
python3 scripts/test_persistence_all.py
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
