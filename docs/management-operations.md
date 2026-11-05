# Lokaler Managementbetrieb – MA-B-Persistenz

> MA-F: Der normale Betrieb und die Operatoren verwenden ausschliesslich den
> gemeinsamen [STACK-Composekontext](../../dateninfrastruktur-dev-stack/docs/operations.md).
> Frühere separate Python-/Host-JVM-Laufzeitbeispiele hier sind historisch; die
> fachlichen, Modell-, Mapping- und Auditverträge bleiben gültig.


Für den aktuellen MA-C-Web-/OIDC-Betrieb siehe [management-web.md](management-web.md).
Die folgenden Angaben beschreiben den separat testbaren Persistenzstand.

Voraussetzung: Java25, Docker, Python3 und Netzwerk beim ersten Tool-/Imagebuild.
Alle Befehle aus ADMIN, alternativ Setup/Testdispatcher im STACK-Schwesterrepo.

```sh
python3 scripts/setup.py
python3 scripts/run.py
```

Der JVM-Start verwendet getrennte Writer-/Authz-Konten aus dem eigenen
Runtimebereich; keine Owner-/Migrationscredentials in der Anwendung. Standard
ist Loopbackport18090, optional `DI_ADMIN_PORT`. Tatsächlich geprüft:
`http://127.0.0.1:18090/actuator/health/readiness` →200 `{"status":"UP"}`.
Noch keine Admin-/Self-Service-UI oder JWT-/Downloadbehauptung; diese folgen
hinter den MA-C/MA-D/MA-E-Gates. Es gibt keinen Datenreset beim Appstart.

Setup erneut ausführen erhält bestehende Daten und Secrets. Vertragsabweichung
stoppt; nicht mit schemaimport oder einem Demo-Neuimport kaschieren. Die Details
zu Modellabbildung, Migrationen, Audit, Konten und Grenzen stehen in
`schema-lifecycle.md`.

```sh
python3 scripts/audit_setup.py verify
python3 scripts/audit_setup.py plan
python3 scripts/test_models.py
```

Nur für einen **eigenen ausdrücklich temporären** MA-B-Testcontainer:
`python3 scripts/management_db.py cleanup`. Die Identitäts-/Labelprüfung muss
passen. Kein Löschen des vorhandenen Nutzerstacks, kein globales Docker-Prune.
Der vollständige Integrationstest benötigt einen freien eigenen Runtimebereich:

```sh
python3 scripts/test_persistence_all.py
```

Er verweigert den Start bei vorhandener `.runtime/management-db/connection.json`.
So kann ein Test keinen bereits eingerichteten persönlichen Arbeitsstand
stillschweigend ersetzen. Reviewcheckouts haben eigene Runtimepfade und erzeugen
neue Testcredentials statt die privaten Originaldateien zu kopieren.

Keine Native-/Produktionszusage. Der verpflichtende lokale Startweg ist JVM.
Der frühere ClickHouse-Lader, Jenkins und Superset bleiben unverändert.
