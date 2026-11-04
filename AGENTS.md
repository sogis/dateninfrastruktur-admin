# dateninfrastruktur-admin

Vor Arbeit `../AGENTS.md` und `../dateninfrastruktur-dev-stack/docs/implementation/management-and-access/EXECUTION_PLAN.md` sowie alle dort referenzierten Fach-/Testverträge und Rollenprompts lesen.

Java 25, Spring Boot 4.1.2 GA, Apache Cayenne 5.0-M3 (Runtime und Buildtools); kein JPA/Hibernate. Modelle in `models/`, Fachschema ausschliesslich durch unverändertes ili2pg. Zusatz-DDL/Audit als versionierte Migrationen in `db/`. Separate Management-DB und Writer/Authz/Public-Konten.

Audit-Actor, Command und DML in derselben physischen Connection und expliziten äusseren Cayenne-Transaktion. Frische Kontexte, keine gecachten Berechtigungen. Fachservices prüfen aktuelle Zuständigkeit innerhalb der Schreibtransaktion. Rechteanker ausschliesslich Dataset.

OIDC/JWT mit issuer/audience prüfen, Browser-CSRF erhalten, interne API-Grenze sichern. Keine Tokens, Secrets oder vertraulichen Bindings loggen. Authorization API entscheidet nur und liefert keine Dateien.

Reviewer ändern keinen Produktcode. Exakte Quell-SHAs, testfähige isolierte Ressourcen, Workspace-AGENTS-Inhalt/Hash im Handoff. Rohbelege und eigene Tests unter ignoriertem `.runtime/`. Keine fremden Änderungen oder Secrets committen; kein Push/Release.
