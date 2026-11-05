# dateninfrastruktur-admin

Vor Arbeit `../AGENTS.md` und `../dateninfrastruktur-dev-stack/docs/implementation/management-and-access/EXECUTION_PLAN.md` sowie alle dort referenzierten Fach-/Testverträge und Rollenprompts lesen.

Java 25, Spring Boot 4.1.2 GA, Apache Cayenne 5.0-M3 (Runtime und Buildtools); kein JPA/Hibernate. Modelle in `models/`, Fachschema ausschliesslich durch unverändertes ili2pg. Zusatz-DDL/Audit als versionierte Migrationen in `db/`. Separate Management-DB und Writer/Authz/Public-Konten.

Audit-Actor, Command und DML in derselben physischen Connection und expliziten äusseren Cayenne-Transaktion. Frische Kontexte, keine gecachten Berechtigungen. Fachservices prüfen aktuelle Zuständigkeit innerhalb der Schreibtransaktion. Rechteanker ausschliesslich Dataset.

OIDC/JWT mit issuer/audience prüfen, Browser-CSRF erhalten, interne API-Grenze sichern. Keine Tokens, Secrets oder vertraulichen Bindings loggen. Authorization API entscheidet nur und liefert keine Dateien.

Reviewer ändern keinen Produktcode. Exakte Quell-SHAs, testfähige isolierte Ressourcen, Workspace-AGENTS-Inhalt/Hash im Handoff. Rohbelege und eigene Tests unter ignoriertem `.runtime/`. Keine fremden Änderungen oder Secrets committen; kein Push/Release.

## Aktiver Folgeauftrag MA-F0–MA-F4

Vor Arbeit den vollständigen Auftrag unter
`../dateninfrastruktur-dev-stack/docs/implementation/compose-consolidation/CODEX_STARTPROMPT.md`
und alle dort referenzierten Spezifikationen lesen. Workspace-AGENTS ist bindend.
Die früheren Betriebs-/Grundinstallationsgrenzen oben sind historische Regeln,
keine Verbote dieser ausdrücklich beauftragten Konsolidierung. Fachliche MA-A–E-
Verträge bleiben erhalten. MA-F erlaubt genau einen Compose-Verbund, einen PG16/
PostGIS-Server und eine Instanz pro Dauer-Service. Kein HA, keine parallele
Managementorchestrierung. APISIX wird aus Jenkins' Netzwerkraum gelöst.

Bestehende identifizierte eigene Daten/Volumes/Identitäten/Secrets kontrolliert
übernehmen, nicht durch leere Seeds ersetzen. Retire erst nach geprüftem Backup
auch der Container-Schreibschichten. Keine fremden Ressourcen ändern. Phasen
strikt nacheinander, unabhängige OpenCode2-/DeepSeek-Flash1017-Abnahme je Phase.
Berichte unter compose-consolidation/, Rohbelege unter STACK/.runtime/compose-review/.

## Geltung der historischen Aufträge

Die vorstehenden MA-0–F-/Grundinstallationsgrenzen gelten nur für ihre damaligen
Aufträge. Für den ausdrücklich aktivierten MA-G-Auftrag gilt `../AGENTS.md` und
vollständig `../dateninfrastruktur-dev-stack/docs/implementation/storage-and-delivery/`.
Aktive Altverträge werden gemäss MA-G ersetzt; Sicherheits- und Datenerhaltungsregeln
bleiben bindend. Historische Dokumente und Nutzerarbeit nicht umschreiben.
