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
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.model.Authorityassignment;
import ch.so.agi.dateninfrastruktur.admin.model.Dataset;
import ch.so.agi.dateninfrastruktur.admin.model.Office;
import ch.so.agi.dateninfrastruktur.admin.model.Principal;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
import org.apache.cayenne.query.ObjectSelect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.core.NestedExceptionUtils;

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

    @Test
    void managementListingsFollowCurrentDetailAndDecisionPermissions() {
        try (var fixture = new ManagementFixture()) {
            var query = new ManagementQueryService(fixture.runtime, fixture.authority, fixture.admin);
            UUID dataset = fixture.published();
            UUID request = fixture.workflow.request(fixture.anna, command("request"), dataset, "Sichtprüfung", null);
            long revision = fixture.version("accessrequest", request);
            var filters = new DatasetFilters(null, null, null, fixture.office, 0);
            assertEquals(List.of(request.toString()),
                query.requests(fixture.anna, true, 0).stream().map(row -> row.get("id").toString()).toList());
            assertTrue(query.requests(fixture.anna, false, 0).isEmpty());

            for (String capability : List.of("submit_data", "manage_submitters", "publish", "manage_metadata", "manage_access")) {
                var actor = ManagementFixture.actor(capability + "-" + fixture.suffix);
                var principals = new PrincipalRepository();
                UUID assignmentId = fixture.unit.execute(fixture.manager, command("operator.bootstrap"), context -> {
                    var manager = principals.requireActive(context, fixture.manager);
                    var principal = context.newObject(Principal.class);
                    principal.setTIliTid(UUID.randomUUID());
                    principal.setAissuer(actor.issuer());
                    principal.setAsubject(actor.subject());
                    principal.setAkind("user");
                    principal.setAactive(true);
                    principal.setProvisionedat(CayenneSql.now(context));

                    var assignment = context.newObject(Authorityassignment.class);
                    assignment.setTIliTid(UUID.randomUUID());
                    assignment.setPrincipal1(principal);
                    assignment.setAssignedby1(manager);
                    if ("manage_metadata".equals(capability)) {
                        assignment.setOffice1(ObjectSelect.query(Office.class)
                            .where(Office.T_ILI_TID.eq(fixture.office)).selectOne(context));
                    } else {
                        assignment.setDataset1(ObjectSelect.query(Dataset.class)
                            .where(Dataset.T_ILI_TID.eq(dataset)).selectOne(context));
                    }
                    assignment.setCapability(capability);
                    assignment.setReason("Testsetup");
                    assignment.setValidfrom(CayenneSql.now(context).minusDays(1));
                    return assignment.getTIliTid();
                });

                boolean visible = List.of("manage_metadata", "publish", "manage_access").contains(capability);
                var page = query.datasets(actor, filters);
                assertEquals(visible ? List.of(dataset) : List.of(),
                    page.rows().stream().map(DatasetRow::id).toList(), capability);
                assertEquals(visible ? List.of(fixture.office) : List.of(),
                    page.offices().stream().map(ManagementQueryService.DatasetOffice::id).toList(), capability);
                assertEquals("manage_metadata".equals(capability), page.canCreate(), capability);
                if (visible) {
                    assertDoesNotThrow(() -> query.detail(actor, dataset), capability);
                } else {
                    assertForbidden(() -> query.detail(actor, dataset));
                }

                var pending = query.requests(actor, false, 0).stream().map(row -> row.get("id").toString()).toList();
                if ("manage_access".equals(capability)) {
                    assertEquals(List.of(request.toString()), pending);
                    fixture.workflow.decide(actor, command("approve"), request, revision, true, fixture.validity(), "Prüfung");
                    assertEquals("approved", fixture.request(request).getAstatus());
                    assertFalse(query.effective(fixture.anna).isEmpty());
                    var applicantPage = query.datasets(fixture.anna, filters);
                    assertTrue(applicantPage.rows().isEmpty());
                    assertTrue(applicantPage.offices().isEmpty());
                    assertForbidden(() -> query.detail(fixture.anna, dataset));
                    fixture.unit.execute(fixture.manager, command("operator.bootstrap"), context -> {
                        var assignment = ObjectSelect.query(Authorityassignment.class)
                            .where(Authorityassignment.T_ILI_TID.eq(assignmentId)).selectOne(context);
                        assignment.setRevokedat(CayenneSql.now(context));
                        assignment.setRevokedby1(principals.requireActive(context, fixture.manager));
                        assignment.setRevocationreason("Testentzug");
                        return null;
                    });
                    assertTrue(query.datasets(actor, filters).rows().isEmpty());
                    assertTrue(query.datasets(actor, filters).offices().isEmpty());
                    assertForbidden(() -> query.detail(actor, dataset));
                } else {
                    assertTrue(pending.isEmpty(), capability);
                    assertForbidden(() ->
                        fixture.workflow.decide(actor, command("approve"), request, revision, true, fixture.validity(), "Prüfung"));
                    assertEquals("pending", fixture.request(request).getAstatus());
                    assertEquals(revision, fixture.version("accessrequest", request));
                }
            }
        }
    }

    private static void assertForbidden(Executable action) {
        var error = assertThrows(RuntimeException.class, action);
        var forbidden = assertInstanceOf(BusinessException.class, NestedExceptionUtils.getMostSpecificCause(error));
        assertEquals(403, forbidden.status());
    }

    private static UUID create(ManagementFixture fixture, String identifier, String title, String kind) {
        return fixture.catalog.createDraft(fixture.manager, command("create_draft"),
            new Create(fixture.office, identifier + "-" + fixture.suffix, kind, ManagementFixture.metadata(title)));
    }
}
