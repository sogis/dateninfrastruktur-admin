package ch.so.agi.dateninfrastruktur.admin.persistence;

import javax.sql.DataSource;
import java.sql.SQLException;

/** Read-only check of the writer boundary and the basic management schema. */
public final class ManagementDatabase {
    private ManagementDatabase() {}

    public static void require(DataSource source) {
        String sql = """
            SELECT current_user = 'di_writer'
               AND NOT (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication)
               AND NOT has_schema_privilege(current_user, 'di_management', 'CREATE')
               AND NOT has_table_privilege(current_user, 'di_management.dataset', 'TRUNCATE')
               AND to_regclass('di_management.t_ili2db_basket') IS NOT NULL
               AND to_regclass('di_management.submission') IS NOT NULL
            FROM pg_roles WHERE rolname = current_user
            """;
        try (var connection = source.getConnection()) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("Management requires the restricted writer and the installed schema");
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to verify the management database", error);
        }
    }
}
