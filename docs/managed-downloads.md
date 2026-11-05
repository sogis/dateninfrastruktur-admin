# Management-/Dateibetrieb

> MA-F: Der normale Betrieb und die Operatoren verwenden ausschliesslich den
> gemeinsamen [STACK-Composekontext](../../dateninfrastruktur-dev-stack/docs/operations.md).
> Frühere separate Python-/Host-JVM-Laufzeitbeispiele hier sind historisch; die
> fachlichen, Modell-, Mapping- und Auditverträge bleiben gültig.


## Lokale Laufzeit

Aus STACK `scripts/management/setup.sh` einmal ausdrücklich ausführen, dann
`up.sh`, `status.sh`, `stop.sh`, `restart.sh`. Java25/JVM ist der geprüfte Startweg.
Jeder Start verwendet denselben privaten Zustand unter ADMIN/.runtime/managed:
DBvolume, Container, Garage-Daten, Benutzer-IDs, OIDC-/Gateway-/Storage-Secrets.
Kein Demoimport oder Grant beim Start. Bestehende private aktuelle Schemas werden
von setup nur lesend gegen Fingerprint, Auditvertrag und physisches Mapping
geprüft. Ein abweichender Vertrag bleibt erhalten und verlangt einen expliziten
geprüften Upgrade; kein automatischer ili2pg-Neuimport. Apps haben keine DDLrechte.

`DI_MANAGEMENT_RUNTIME_DIR` kann auf einen **absoluten** Datenbankordner innerhalb
dieses Checkouts `.runtime` zeigen; sein Elternordner enthält die eigene Identity-
Laufzeit. Der bisherige B/C/D-Testharness bleibt standardmässig separat unter
`.runtime/management-db` bzw. `.runtime/identity`. Niemals fremde `.env` kopieren.

Zwei APISIX, zwei Admins und zwei Publicprozesse teilen einen PG16-Primärbestand.
Nur die beiden zufällig gewählten Gateway-Ports sind auf 127.0.0.1 veröffentlicht.
Das explizite Erstsetup benutzt kurz einen eigenen zufälligen PG-Loopbackport für
ili2pg/cdbimport. Vor dem Webstart wird derselbe überprüfte Datenbestand mit
demselben Volume ohne Hostport übernommen. Das alte eigene DBcontainerobjekt
wird erst nach erfolgreichem privaten Start entfernt, ohne Volumelöschung.

`stop` erhält Container/Volumes; `start` startet sie wieder. `cleanup` ist ein
separater destruktiver Testbefehl (`scripts/managed_runtime.py cleanup`) und prüft
IDs, Namen und Labels vor jedem Eingriff. Kein globales Docker-Cleanup.

Die lokale leere Installation benötigt für fachliche Verwaltung einen ausdrücklich
eingerichteten Zuständigkeitsbereich. `AuthorityBootstrapCommand` richtet einen
bekannten Manager ein, kein Datenrecht. Die vorhandene Realmvorlage behält
Anna/Ben/Claudias IDs; lokale Testpasswörter werden einmal zufällig generiert.
Die Passwort-Grantart ist ausschliesslich beim bezeichneten lokalen Testclient
aktiv, nicht beim Browserclient. Keine AIO-/LDAP-/Produktionsanbindung.

## Datei- und Identitätsgrenzen

APISIX3.19.0 benutzt unveränderte `openid-connect`- und `forward-auth`-Plugins.
OIDC läuft vor forward-auth; Access-Token im Authorization-Header. Java prüft
Signatur, issuer, audience, Gültigkeit und Tokentyp erneut. Browser-Codeflow mit
HttpOnly/SameSite=Lax-Sessioncookie und gemeinsamem erhaltenem Sessionkey.
Beide expliziten Gateway-Origins sind im Backend zugelassen; CSRF bleibt trotz
Cookie→Bearer erhalten. Kein Wildcard-Origin. Produktion verlangt TLS/Secure
und entsprechende externe URL-/Credentialkonfiguration; lokales HTTP ist nur
Loopback-/isolierter Demobetrieb.

Wichtig bei der tatsächlich eingesetzten APISIX-Version: `ctx.var.request_uri`
ist bereits normalisiert (apisix/init.lua); original ist `real_request_uri`.
Deshalb setzt `forward-auth.extra_headers.X-Forwarded-Uri` ausdrücklich
`$real_request_uri`, unabhängig von Client-Forwarded-Headern. Servicecredential
wird ebenfalls serverseitig überschrieben. Nur Authorization und Request-ID
werden aus dem Request übernommen. Die Java-API entscheidet read-only mit einer
frischen primären SQLabfrage, ohne Cache oder Policyrollout.

Ein globaler **Standard**-`fault-injection.abort` mit 100%-Bedingung über
`real_request_uri` verwirft nichtkanonische Download-/mehrdeutige Pfade mit400,
bevor Normalisierung sie z.B. auf die Portalroute lenken könnte. Das ist ein
fester syntaktischer Rohpfadschutz, keine zweite fachliche ACL. Kein Lua-Code,
keine Casbin-Datei, keine APISIX-DBverbindung. Autorisierung bleibt Java/PG.

Downloads bleiben ohne Loginredirect: optionales OIDC, open anonym, restricted
401 ohne Identität bzw.403 ohne Recht. Ungültiger expliziter Bearer401.
Registrierte Pfade exakt `/downloads/<uuid>/<ASCII-Dateiname>`, keine Querys,
Kodierungen, zusätzlichen Segmente oder Alternativnamen. GET/HEAD, Range206,
Content-Length/-Type/-Disposition, Accept-Ranges und ETag/304 sind real getestet.
Jede neue HEAD-/304-Anfrage wird vor Auslieferung frisch autorisiert. Direkte
und historische verfügbare Repräsentationen erben denselben Rootzugang.

`forward-auth.upstream_headers` entfernt nach erfolgreicher Prüfung nicht von
Authz zurückgegebene Authorization, Cookie, X-Access-Token, X-ID-Token,
X-Userinfo und X-DI-Gateway-Credential. Der Test beobachtet tatsächlich die
Headernamen am Originübergang mit einem transparenten Testproxy, ohne Werte zu
loggen oder Header selbst zu bereinigen. Im Standardbetrieb geht APISIX direkt
zu rclone; der Probeproxy gehört ausschliesslich zur Testsuite.

Origin ist rclone1.75.2 `serve webdav --read-only --disable-dir-list --disable-zip`
über `managed:di-managed-private/downloads`, Basis `/downloads`, ohne Pfadwechsel.
WebDAV liefert Standard-ETags/Range/HEAD; Gateway erlaubt keine DAVschreib- oder
Listingmethoden. Kein Admin-/Sodata-Dateistreaming oder Presigned-Redirect.
Kein VFSdateicache, Directorycache0, begrenzte S3-/Gatewaytimeouts und Retries.
Der Origin erhält ein eigenes read-only-Bucketkonto; Garage bietet Bucket-ACLs,
keine native Prefix-IAMpolicy. Prefixbegrenzung erfolgt zusätzlich durch den
festen Originpfad. Archive/Prüfbelege liegen ausserhalb davon. Keine Website-
oder anonymen S3-Rechte. RW-Operatorcredential nur im lokalen Operatorpfad.

Eine Standard-Nginx-add_header-always-Direktive erfasst auch frühe OpenResty400
vor dem Pluginlauf (z.B. Prozent-NUL). Die gesamte lokale Managementgateway-Laufzeit setzt Cache-Control:no-store
(auch Fehler/304). Keine Proxycache-Policy. Grant/Revoke und open↔restricted
wirken auf nach Commit neu begonnene Checks mit demselben JWT auf beiden
Instanzen. Bereits laufende/heruntergeladene Kopien sind nicht rückholbar.
Bei PG-/Authzausfall kein Inhalt, auch open nicht. S3-/Originausfall liefert
einen Fehler, keinen vorgetäuschten Download200. Nach Wiederherstellung sind
positive Referenzbytes erneut geprüft.

## Lieferung und kontrollierter Cutover

`DeliveryRegistrationService` registriert bestätigte Versionreferenzen und
vorbereitete Distributionen, kein neuer Lader/Uploadendpoint. Lokale Aktivierung:
`STACK/scripts/management/activate-distribution.sh <dataset-uuid>
<expected-version> <distribution-uuid>`. Der fest konfigurierte lokale
Serviceactor `urn:di:operator / managed-delivery` benötigt ausdrücklich die
zuständigen Capabilities. Das ist kein ungeschütztes Webendpoint.

`ManagedDeliveryCommand` verwendet denselben RuntimeContractVerifier und dieselbe
AuditedUnitOfWork wie die Webservices. `RclonePrivateObjectVerifier` liest mit
einem fest zugelassenen Profil/Remote den kanonischen privaten Key, prüft zuerst ein tatsächlich vorhandenes Dateiobjekt per lsjson --stat und streamt
anschliessend SHA256/Bytes bei begrenzter Laufzeit (Grössenvergleich) und aktiviert erst nach Erfolg atomar.
Keine URL aus Benutzereingabe, keine Secrets als CLIargumente. Der Operator muss
private Objektkeys unveränderlich halten; RWcredentials bleiben administrativ.

Die vollständige Suite erstellt synthetische CSV-/gültige XLSX-Dateien mit
bekannten Hashes, zunächst prepared. Vor Aktivierung kontrollierter Testdateien
wird ein **zuvor wirklich öffentlicher** Canary über beide alten Gatewaywege
nachgewiesen, in den privaten Bereich kopiert/verifiziert, die eigene alte
Website deaktiviert und alte Routen gesperrt. Das Inventar liegt bereinigt im
eigenen Runtimeordner `canary-evidence.json`. Nur die eigene synthetische Kopie
wird verändert. Externe Originale und bestehende Nutzerbuckets bleiben unberührt;
deren Schutz wird nicht behauptet. Die Herkunftskopie liegt ausserhalb downloads.

## Test und Diagnose

STACK `scripts/management/test.sh --phase MA-E` bzw. ADMIN
`python3 scripts/test_downloads_all.py` führt genau die neue abschliessende
Kette M26–M33 aus. Frischer eigener Bereich `.runtime/managed-test`, echte
PG-/Garage-/Keycloak-/APISIX-/JVMprozesse. Fehlende/geskippte JUnitklassen stoppen
den Lauf. Rohlogs/JUnit unter `.runtime/download-evidence/<UTC>/`, sanitisiert.
`--keep-runtime` behält die erfolgreiche explizite Pilotdemo; der optionale
Diagnoseparameter `--keep-failed-runtime` behält auch fehlgeschlagene eigene
Ressourcen zur Untersuchung. Ein bestehender Bereich wird nie automatisch ersetzt.

`managed_control.py ports|storage|snapshot|logs` prüft ausschliesslich den durch
private Zustandsdateien gebundenen eigenen Bereich. Keine Credentialwerte in
Ausgabe. `pause/resume` sind gezielte eigene Testfehler, keine Betriebsaktion
gegen fremde Container. `identity/state.json` enthält URLs und lokale Credentials
und ist privat/ignoriert. Gatewaylogs zeigen Methode/URI/Status, keine JWTs oder SQLbindings. Garage läuft
mit RUST_LOG=warn, weil sein Info-Requestlog Access-Key-IDs ausgeben würde. Der
Logtest umfasst auch diese IDs zusätzlich zu Secrets/Passwörtern/JWTs.

Java25, Boot4.1.2, Cayenne5.0-M3, ili2pg5.5.2 und die abgenommenen Modell-/Mapping-
Pins bleiben erhalten. Garage2.4.1, rclone1.75.2, APISIX3.19.0, Keycloak26.8.1;
PostGIS16-3.5 ist per Digest im DBharness gepinnt. Der ausschliesslich im Test
verwendete Python-Probecontainer ist ebenfalls per Digest gepinnt.
Kein Native-Kompatibilitätsnachweis, keine Rechtszertifizierung und keine
persönliche ClickHouse-/Superset-Rechtesynchronisierung.
