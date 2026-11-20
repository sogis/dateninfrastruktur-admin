# Admin – Katalog, Rechte und Anlieferung

Admin verwaltet den Managementkatalog, entscheidet über fachliche Rechte
und verwaltet CSV-Submissions. Die Anwendung verwendet Java 25,
Spring Boot 4 und Apache Cayenne 5.0-M3. INTERLIS ist die Quelle des Fachschemas.

Der [gemeinsame Compose-Betrieb](../dateninfrastruktur-dev-stack/docs/operations.md)
richtet die Management-Datenbank explizit ein. Das Setup importiert die
vier Modelle in einem ili2pg-Aufruf. Die freigegebenen Sichten entstehen
im PostScript. Eine vorhandene Management-Datenbank wird lesend geprüft.
Der Normalstart führt keine Schemaänderungen aus.

Java 25 genügt für den Build und die schnellen Tests. Die Modellprüfungen
verwenden zusätzlich Python 3.9 oder neuer sowie ili2c und ilivalidator.

```sh
./gradlew test
./gradlew modelTest
./gradlew bootJar
```

`./gradlew integrationTest --tests '*CayenneRuntimeCompatibilityIT'` wählt
eine einzelne Integrationstestklasse aus. Dieser Test benötigt eine eigene
PostgreSQL-Testdatenbank über `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` und `SPRING_DATASOURCE_PASSWORD`. Der Dev-Stack
stellt isolierte Prüfungen für Schema, Mapping, Lieferrechte, HTTP-Sicherheit
und konkurrierende Veröffentlichungen bereit.

- [Modellinvarianten](models/constraints.md)
- [Schema und XTF-Bootstrap](docs/schema-lifecycle.md)
- [Lieferrechte und Submission](docs/delivery-authorization.md)
- [Management und Dateizugang](docs/management-web.md)

## Oberfläche lokal entwickeln

Starte Admin mit Java 25 im Admin-Checkout. Verwende das Profil `host-dev`
und die private `admin-host.properties` aus der vorbereiteten Instanz.

```sh
SPRING_PROFILES_ACTIVE=host-dev SPRING_CONFIG_ADDITIONAL_LOCATION="file:$admin_config" ./gradlew bootRun
```

In der IDE gelten dieselben Umgebungsvariablen und das gleiche
Arbeitsverzeichnis. Spring Boot DevTools übernimmt kompilierte Java-Änderungen.
JTE unter `src/main/jte` und CSS unter `src/main/resources/static/assets`
werden beim Neuladen im Browser aus dem Checkout gelesen. Anmeldung und Zugriff
auf die Oberfläche laufen über das Gateway. Die [Stack-Bedienung](../dateninfrastruktur-dev-stack/docs/operations.md)
zeigt die Ersteinrichtung und den Pfad für `admin_config`.

## CSV-Anlieferung

Jenkins erstellt die Submission mit dem Benutzer-JWT. Der registrierte
GRETL-Service-Principal startet sie und lädt die dabei gebundene Schemafassung
als INTERLIS-Datei über `GET /internal/deliveries/{id}/model.ili`. Admin erzeugt
die Datei aus der Datenbank und kennzeichnet die Antwort als `no-store`.

GRETL meldet beim Abschluss `objectCount`. Admin liest `snapshot.csv` vom
privaten Origin, ermittelt Grösse und SHA-256 und prüft Rechte, Schema und
Basisversion vor dem atomaren Wechsel der aktuellen Version. Der Dev-Stack
prüft den Vertrag mit `python3 scripts/test_delivery_domain.py`.
