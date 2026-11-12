package ch.so.agi.dateninfrastruktur.admin.persistence;

import java.util.Map;
import org.apache.cayenne.ObjectContext;

/** Resolve the single configured management basket for a model topic. */
public final class ManagementBaskets {
    public enum Topic { Organization, Catalog, Access, Delivery }

    private ManagementBaskets() {}

    public static long require(ObjectContext context, Topic topic) {
        String name = "SO_AGI_DataInfrastructure_" + topic + "_20261112." + topic;
        var rows = CayenneSql.rows(context, """
            SELECT t_id FROM di_management.t_ili2db_basket WHERE topic = #bind($topic)
            """, Map.of("topic", name));
        if (rows.size() != 1) {
            throw new IllegalStateException("Expected exactly one management basket for " + name);
        }
        return ((Number) rows.getFirst().get("t_id")).longValue();
    }
}
