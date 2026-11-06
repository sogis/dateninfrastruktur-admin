package ch.so.agi.dateninfrastruktur.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetFilters;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetRow;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService.Create;
import org.junit.jupiter.api.Test;

import static ch.so.agi.dateninfrastruktur.admin.ManagementFixture.command;
import static org.junit.jupiter.api.Assertions.*;

class DatasetListQueryIT {
    @Test
    void paginationUsesAnUnambiguousOrderForIdenticalTitlesWithinScope() {
        try (var owned = new ManagementFixture(); var foreign = new ManagementFixture()) {
            var query = new ManagementQueryService(owned.runtime, owned.authority, owned.admin);
            String title = "Gleicher Prüftitel";
            var expected = new ArrayList<UUID>();
            for (int index = 0; index < 51; index++) {
                expected.add(create(owned, "paging-" + index, title, "series"));
            }
            create(foreign, "foreign", title, "series");
            expected.sort(Comparator.comparing(UUID::toString));

            var first = query.datasets(owned.manager, new DatasetFilters("gLEICHer Prüftitel", null, null, null, 0));
            var second = query.datasets(owned.manager, new DatasetFilters("gLEICHer Prüftitel", null, null, null, 1));
            assertEquals(expected.subList(0, 50), first.rows().stream().map(DatasetRow::id).toList());
            assertEquals(expected.subList(50, 51), second.rows().stream().map(DatasetRow::id).toList());
            assertTrue(first.hasNext());
            assertFalse(first.hasPrevious());
            assertFalse(second.hasNext());
            assertTrue(second.hasPrevious());
            assertTrue(second.rows().stream().allMatch(row -> row.title().equals(title)));

            var offices = first.offices();
            assertEquals(1, offices.size());
            var office = offices.getFirst();
            assertEquals(owned.office, office.id());
            assertEquals("Prüfamt", office.name());
            assertEquals("office-" + owned.suffix, office.identifier());
            assertEquals(owned.suffix.substring(0, 8), office.abbreviation());
            assertTrue(first.rows().stream().allMatch(row -> row.office().equals(office)));
        }
    }

    @Test
    void searchAndIndependentStatusAndOfficeFiltersStayWithinScope() {
        try (var owned = new ManagementFixture(); var foreign = new ManagementFixture()) {
            var query = new ManagementQueryService(owned.runtime, owned.authority, owned.admin);
            var emptyOffice = query.datasets(owned.manager, new DatasetFilters(null, null, null, null, 0));
            assertFalse(emptyOffice.hasDatasets());
            assertTrue(emptyOffice.canCreate());

            UUID open = create(owned, "MiXeD-Identifier", "Verkehr 50%_Planung", "standalone");
            UUID restricted = create(owned, "restricted", "Verkehr 50xyPlanung", "standalone");
            create(foreign, "foreign", "Verkehr 50%_Planung", "standalone");
            owned.catalog.transition(owned.manager, command("publish"), open,
                owned.version("dataset", open), "publish", "Testpublikation");
            owned.catalog.changeAccessLevel(owned.manager, command("change_access_level"), open,
                owned.version("dataset", open), "open", "Offene Prüfdaten");

            for (String literal : List.of("%", "_", "mixed-identifier")) {
                var matched = query.datasets(owned.manager, new DatasetFilters(literal, null, null, null, 0));
                assertEquals(List.of(open), matched.rows().stream().map(DatasetRow::id).toList(), literal);
            }

            var publication = query.datasets(owned.manager,
                new DatasetFilters("VERKEHR", "published", "open", owned.office, 0));
            assertEquals(List.of(open), publication.rows().stream().map(DatasetRow::id).toList());
            var row = publication.rows().getFirst();
            assertEquals("published", row.publicationStatus());
            assertEquals("open", row.accessLevel());
            assertEquals("standalone", row.kind());
            assertEquals("MiXeD-Identifier-" + owned.suffix, row.identifier());
            assertNotNull(row.modified());
            assertTrue(publication.canCreate());

            var restrictedDraft = query.datasets(owned.manager,
                new DatasetFilters(null, "draft", "restricted", owned.office, 0));
            assertEquals(List.of(restricted), restrictedDraft.rows().stream().map(DatasetRow::id).toList());
            assertTrue(query.datasets(owned.manager,
                new DatasetFilters(null, "published", "restricted", owned.office, 0)).rows().isEmpty());

            var foreignFilter = query.datasets(owned.manager,
                new DatasetFilters(null, null, null, foreign.office, 0));
            assertTrue(foreignFilter.rows().isEmpty());
            assertTrue(foreignFilter.hasDatasets());
            assertTrue(foreignFilter.canCreate());
            assertFalse(foreignFilter.hasNext());
            assertTrue(query.datasets(owned.manager,
                new DatasetFilters("' OR 1=1 --", null, null, null, 0)).rows().isEmpty());

            var emptyScope = query.datasets(owned.anna, new DatasetFilters(null, null, null, null, 0));
            assertTrue(emptyScope.rows().isEmpty());
            assertTrue(emptyScope.offices().isEmpty());
            assertFalse(emptyScope.hasDatasets());
            assertFalse(emptyScope.canCreate());
            assertThrows(BusinessException.class, () ->
                query.datasets(owned.manager, new DatasetFilters(null, null, null, null, -1)));
            assertThrows(BusinessException.class, () ->
                query.datasets(owned.manager, new DatasetFilters(null, null, null, null, Integer.MAX_VALUE)));
        }
    }

    private static UUID create(ManagementFixture fixture, String identifier, String title, String kind) {
        return fixture.catalog.createDraft(fixture.manager, command("create_draft"),
            new Create(fixture.office, identifier + "-" + fixture.suffix, kind, ManagementFixture.metadata(title)));
    }
}
