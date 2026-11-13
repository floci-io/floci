package io.floci.aws.services.glue;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionProjectionTest {

    private static final String BUCKET = "s3://audit-logs";

    private static Column column(String name) {
        Column c = new Column();
        c.setName(name);
        c.setType("string");
        return c;
    }

    private static Table table(String name, Map<String, String> params, List<Column> partitionKeys) {
        Table t = new Table();
        t.setName(name);
        StorageDescriptor sd = new StorageDescriptor();
        sd.setLocation(BUCKET + "/");
        t.setStorageDescriptor(sd);
        t.setParameters(params);
        t.setPartitionKeys(partitionKeys);
        return t;
    }

    private static Map<String, String> projecting(String... extra) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("projection.enabled", "true");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            m.put(extra[i], extra[i + 1]);
        }
        return m;
    }

    // ---- read path ----

    /**
     * A workgroup writing results under the table's root is the case that breaks: reading the root
     * wholesale picks the results back up as though they were table data.
     */
    @Test
    void templateGivesAPartitionPathThatExcludesSiblingPrefixes() {
        Table t = table("audit_events", projecting(
                "storage.location.template", BUCKET + "/tenant=${tenant}/year=${year}/month=${month}/day=${day}"),
                List.of(column("tenant"), column("year"), column("month"), column("day")));

        String path = PartitionProjection.readPath(t, BUCKET);

        assertEquals(BUCKET + "/tenant=*/year=*/month=*/day=*", path);
        assertTrue(path.startsWith(BUCKET + "/tenant="), path);
    }

    /** Without a template the default column=value layout is built from the partition keys. */
    @Test
    void partitionKeysGiveTheDefaultLayoutWhenNoTemplateIsSet() {
        Table t = table("t", projecting(), List.of(column("tenant"), column("year")));

        assertEquals(BUCKET + "/tenant=*/year=*", PartitionProjection.readPath(t, BUCKET));
    }

    /** Projection off means the location is read as before. */
    @Test
    void withoutProjectionTheLocationIsUnchanged() {
        Table t = table("t", Map.of(), List.of(column("tenant")));

        assertEquals(BUCKET, PartitionProjection.readPath(t, BUCKET));
    }

    /** A projecting table declaring no partitions has no layout to build. */
    @Test
    void projectionWithoutPartitionKeysLeavesTheLocationUnchanged() {
        assertEquals(BUCKET, PartitionProjection.readPath(table("t", projecting(), null), BUCKET));
    }

    @Test
    void trailingSlashOnTheTemplateIsDropped() {
        Table t = table("t", projecting("storage.location.template", BUCKET + "/tenant=${tenant}/"),
                List.of(column("tenant")));

        assertEquals(BUCKET + "/tenant=*", PartitionProjection.readPath(t, BUCKET));
    }

    // ---- location template ----

    private static Table templated(String template, String... keys) {
        return table("audit_events", projecting("storage.location.template", template),
                Arrays.stream(keys).map(PartitionProjectionTest::column).toList());
    }

    /** Bare values, one folder per placeholder. */
    @Test
    void templateWithBareValuesCapturesOneSegmentPerKey() {
        PartitionProjection.LocationTemplate template = PartitionProjection.locationTemplate(
                templated(BUCKET + "/data/${region}/${dt}/", "region", "dt"));

        assertEquals("^s3://audit-logs/data/([^/]+)/([^/]+)/", template.regex());
        assertEquals(Map.of("region", List.of(1), "dt", List.of(2)), template.groups());
    }

    /** From the AWS docs' examples: literal {@code col=} prefixes and static folders around placeholders. */
    @Test
    void templateKeepsLiteralSegmentsAroundThePlaceholders() {
        PartitionProjection.LocationTemplate template = PartitionProjection.locationTemplate(
                templated(BUCKET + "/table_root/a=${a}/${b}/some_static_subdirectory/", "a", "b"));

        assertEquals("^s3://audit-logs/table_root/a=([^/]+)/([^/]+)/some_static_subdirectory/", template.regex());
        assertEquals(Map.of("a", List.of(1), "b", List.of(2)), template.groups());
    }

    /** Also from the AWS docs: a placeholder may repeat, and every occurrence is reported. */
    @Test
    void repeatedPlaceholderReportsEachOfItsGroups() {
        PartitionProjection.LocationTemplate template = PartitionProjection.locationTemplate(
                templated(BUCKET + "/${region}/${dt}/${region}/", "region", "dt"));

        assertEquals("^s3://audit-logs/([^/]+)/([^/]+)/([^/]+)/", template.regex());
        assertEquals(Map.of("region", List.of(1, 3), "dt", List.of(2)), template.groups());
    }

    /** Without a trailing slash the last placeholder still ends at a folder boundary, as readPath reads it. */
    @Test
    void templateWithoutTrailingSlashStillEndsAtAFolder() {
        PartitionProjection.LocationTemplate template = PartitionProjection.locationTemplate(
                templated(BUCKET + "/events/${dt}", "dt"));

        assertEquals("^s3://audit-logs/events/([^/]+)/", template.regex());
    }

    @Test
    void regexMetacharactersInTheTemplateAreLiteral() {
        PartitionProjection.LocationTemplate template = PartitionProjection.locationTemplate(
                templated("s3://data.lake/v1+(beta)/${region}/", "region"));

        assertEquals("^s3://data\\.lake/v1\\+\\(beta\\)/([^/]+)/", template.regex());
    }

    /**
     * A placeholder naming no partition key is still a wildcard in the read path, so it keeps its
     * group and the keys after it are numbered past it. Placeholder names match keys case-insensitively.
     */
    @Test
    void placeholderNamingNoKeyStillTakesAGroup() {
        PartitionProjection.LocationTemplate template = PartitionProjection.locationTemplate(
                templated(BUCKET + "/${zone}/${Region}/", "region"));

        assertEquals("^s3://audit-logs/([^/]+)/([^/]+)/", template.regex());
        assertEquals(Map.of("region", List.of(2)), template.groups());
    }

    @Test
    void noLocationTemplateWithoutATemplateProjectionOrAKeyItNames() {
        assertNull(PartitionProjection.locationTemplate(table("t", projecting(), List.of(column("region")))));
        assertNull(PartitionProjection.locationTemplate(table("t",
                Map.of("storage.location.template", BUCKET + "/${region}/"), List.of(column("region")))));
        assertNull(PartitionProjection.locationTemplate(templated(BUCKET + "/${zone}/", "region")));
        assertNull(PartitionProjection.locationTemplate(templated(BUCKET + "/${region}/")));
    }

    /**
     * The Firehose layout AWS documents: a date projection whose format holds slashes stands for that
     * many more folders behind one placeholder, and the group spans them all.
     */
    @Test
    void dateFormatWithSlashesWidensItsGroupToEveryFolderItSpans() {
        Table t = table("firehose", projecting(
                        "storage.location.template", BUCKET + "/prefix/${datehour}/",
                        "projection.datehour.type", "date",
                        "projection.datehour.format", "yyyy/MM/dd/HH"),
                List.of(column("datehour")));

        PartitionProjection.LocationTemplate template = PartitionProjection.locationTemplate(t);

        assertEquals("^s3://audit-logs/prefix/([^/]+(?:/[^/]+)(?:/[^/]+)(?:/[^/]+))/", template.regex());
        assertEquals(Map.of("datehour", List.of(1)), template.groups());
    }

    /**
     * A multi-folder date followed by more of the template: the read path needs a wildcard per folder
     * the date spans, or it looks for {@code region=} right after the year and matches no object.
     */
    @Test
    void readPathAndRegexGiveAMultiFolderDateTheSameFolders() {
        Table t = table("orders", projecting(
                        "storage.location.template", BUCKET + "/orders/${dt}/region=${region}/",
                        "projection.dt.type", "date",
                        "projection.dt.format", "yyyy/MM/dd"),
                List.of(column("dt"), column("region")));

        assertEquals(BUCKET + "/orders/*/*/*/region=*", PartitionProjection.readPath(t, BUCKET));
        assertEquals("^s3://audit-logs/orders/([^/]+(?:/[^/]+)(?:/[^/]+))/region=([^/]+)/",
                PartitionProjection.locationTemplate(t).regex());
    }

    /** Only a date projection's format describes the value's shape; other types keep one folder. */
    @Test
    void slashesInTheFormatOfANonDateProjectionDoNotWidenItsGroup() {
        Table t = table("t", projecting(
                        "storage.location.template", BUCKET + "/${region}/",
                        "projection.region.type", "enum",
                        "projection.region.format", "a/b"),
                List.of(column("region")));

        assertEquals("^s3://audit-logs/([^/]+)/", PartitionProjection.locationTemplate(t).regex());
    }

    // ---- incomplete location template ----

    @Test
    void templateOmittingAPartitionKeyIsIncompleteAndHasNoLocationTemplate() {
        Table t = templated(BUCKET + "/missing/${tenant}/", "tenant", "dt");

        assertTrue(PartitionProjection.hasIncompleteLocationTemplate(t));
        assertNull(PartitionProjection.locationTemplate(t));
    }

    @Test
    void templateNamingEveryKeyOrNoTemplateAtAllIsNotIncomplete() {
        assertFalse(PartitionProjection.hasIncompleteLocationTemplate(
                templated(BUCKET + "/${Tenant}/${dt}/", "tenant", "DT")));
        assertFalse(PartitionProjection.hasIncompleteLocationTemplate(
                table("t", projecting(), List.of(column("tenant")))));
        assertFalse(PartitionProjection.hasIncompleteLocationTemplate(
                table("t", Map.of("storage.location.template", BUCKET + "/x/"), List.of(column("tenant")))));
    }

    /** An Iceberg table is read through its metadata, so a template it carries is never checked. */
    @Test
    void icebergTableWithAnIncompleteTemplateIsNotIncomplete() {
        Map<String, String> params = projecting("storage.location.template", BUCKET + "/missing/${tenant}/");
        params.put("table_type", "ICEBERG");
        Table t = table("audit_events", params, List.of(column("tenant"), column("dt")));

        assertFalse(PartitionProjection.hasIncompleteLocationTemplate(t));
        assertDoesNotThrow(() -> PartitionProjection.assertLocationTemplatesComplete(
                "SELECT * FROM audit_events", List.of(t)));
    }

    /** Athena's message, as captured from engine v3, for a query reading the table. */
    @Test
    void queryReadingATableWithAnIncompleteTemplateFailsAsAthenaDoes() {
        Table t = templated(BUCKET + "/missing/${tenant}/", "tenant", "dt");
        t.setDatabaseName("audit");

        AwsException e = assertThrows(AwsException.class, () ->
                PartitionProjection.assertLocationTemplatesComplete(
                        "SELECT * FROM audit_events WHERE tenant = 'a'", List.of(t)));

        assertEquals("INVALID_TABLE_PROPERTY: The 'storage.location.template' table property must contain "
                + "templates for all partition columns! Table: audit.audit_events Template: "
                + BUCKET + "/missing/${tenant}/", e.getMessage());
    }

    /** Athena keeps the database's other tables queryable, so a query not reading the table passes. */
    @Test
    void queryNotReadingTheTableWithAnIncompleteTemplateIsAccepted() {
        Table t = templated(BUCKET + "/missing/${tenant}/", "tenant", "dt");

        assertDoesNotThrow(() -> PartitionProjection.assertLocationTemplatesComplete(
                "SELECT * FROM other_events WHERE note = 'audit_events'", List.of(t)));
    }

    // ---- injected columns ----

    private Table injectedTenantTable() {
        Table t = table("audit_events", projecting("projection.tenant.type", "injected"),
                List.of(column("tenant")));
        t.getStorageDescriptor().setColumns(List.of(column("id"), column("suffix"), column("other"),
                column("other_tenant")));
        return t;
    }

    @Test
    void queryWithoutAnyMentionOfAnInjectedColumnIsRejected() {
        AwsException e = assertThrows(AwsException.class, () ->
                PartitionProjection.assertInjectedColumnsFiltered(
                        "SELECT * FROM audit_events", List.of(injectedTenantTable())));

        assertTrue(e.getMessage().startsWith("CONSTRAINT_VIOLATION:"), e.getMessage());
        assertTrue(e.getMessage().contains("tenant"), e.getMessage());
    }

    @Test
    void queryConstrainingTheInjectedColumnIsAccepted() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = 'abc'", List.of(injectedTenantTable())));
    }

    @Test
    void inListOfLiteralsConstrainsTheInjectedColumn() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant IN ('abc', 'def')", List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = 'abc' OR tenant = 'def'", List.of(injectedTenantTable())));
    }

    @Test
    void aTableNameThatOnlyPrefixesAnotherTableIsNotAReferenceToIt() {
        Table daily = table("events_daily", Map.of(), List.of());
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM events_daily",
                List.of(table("events", projecting("projection.tenant.type", "injected"),
                        List.of(column("tenant"))), daily)));
    }

    @Test
    void anUnqualifiedColumnIsAcceptedInAJoinWhenOnlyOneSourceDeclaresIt() {
        Table orders = table("orders", Map.of(), List.of());
        orders.getStorageDescriptor().setColumns(List.of(column("id")));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events a JOIN orders o ON a.id = o.id WHERE tenant = 'abc'",
                List.of(injectedTenantTable(), orders)));
        Table ordersWithTenant = table("orders", Map.of(), List.of());
        ordersWithTenant.getStorageDescriptor().setColumns(List.of(column("tenant")));
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events a JOIN orders o ON a.id = o.id WHERE tenant = 'abc'",
                List.of(injectedTenantTable(), ordersWithTenant)));
    }

    @Test
    void aCommaSeparatedSourceNeedsItsOwnFilter() {
        Table orders = table("orders", Map.of(), List.of());
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM orders o, audit_events a WHERE o.id = 1", List.of(injectedTenantTable(), orders)));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM orders o, audit_events a WHERE a.tenant = 'abc'",
                List.of(injectedTenantTable(), orders)));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT o.id, audit_events FROM orders o WHERE o.id = 1", List.of(injectedTenantTable(), orders)));
    }

    @Test
    void aConstantInParenthesesIsStillAStaticEquality() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = ('abc')", List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = (('abc'))", List.of(injectedTenantTable())));
    }

    @Test
    void aComputedValueIsNotAStaticEquality() {
        for (String predicate : List.of("tenant = ('abc' || suffix)", "tenant = 'abc' || suffix",
                "tenant = ('abc') || suffix", "tenant = 1 + other", "tenant = 'abc'::varchar || suffix")) {
            assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                    "SELECT * FROM audit_events WHERE " + predicate, List.of(injectedTenantTable())), predicate);
        }
    }

    @Test
    void joinKeywordsAfterTheTableAreNotTakenForItsAlias() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events NATURAL JOIN other_table WHERE tenant = 'abc'",
                List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events TABLESAMPLE BERNOULLI (10) WHERE tenant = 'abc'",
                List.of(injectedTenantTable())));
    }

    @Test
    void notInAndSubqueryInListsDoNotConstrainTheInjectedColumn() {
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant NOT IN ('abc')", List.of(injectedTenantTable())));
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant IN (SELECT tenant FROM other_table)",
                List.of(injectedTenantTable())));
    }

    /** A table the query never names must not fail it. */
    @Test
    void unrelatedQueryAgainstAnotherTableIsUnaffected() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM other_table", List.of(injectedTenantTable())));
    }

    /** A non-injected projected column generates its own values and needs no filter. */
    @Test
    void nonInjectedPartitionColumnsNeedNoFilter() {
        Table t = table("t", projecting("projection.year.type", "integer"), List.of(column("year")));

        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered("SELECT * FROM t", List.of(t)));
    }

    /** Identifiers match on word boundaries, so a longer name that contains one does not count. */
    @Test
    void aLongerIdentifierContainingTheColumnNameDoesNotCountAsAMention() {
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant_id = 'abc'", List.of(injectedTenantTable())));
    }

    /**
     * The column names itself in the SELECT list and the GROUP BY, and filters nothing - the case
     * the check exists to catch, and the one a query-wide text match lets through.
     */
    @Test
    void mentionOutsideAWhereClauseDoesNotCount() {
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT tenant, count(*) FROM audit_events GROUP BY tenant",
                List.of(injectedTenantTable())));
    }

    /** A WHERE clause ends where the next one starts, so an ORDER BY mention is outside it. */
    @Test
    void mentionAfterTheWhereClauseEndsDoesNotCount() {
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE year = 2026 ORDER BY tenant",
                List.of(injectedTenantTable())));
    }

    /** A string that happens to spell the column name constrains nothing. */
    @Test
    void mentionInsideAStringLiteralDoesNotCount() {
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE note = 'tenant'", List.of(injectedTenantTable())));
    }

    /** Nor does one in a comment. */
    @Test
    void mentionInsideACommentDoesNotCount() {
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events -- filter by tenant one day\n WHERE year = 2026",
                List.of(injectedTenantTable())));
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events /* tenant */ WHERE year = 2026",
                List.of(injectedTenantTable())));
    }

    /** A subquery's WHERE is still a WHERE, and still constrains the read. */
    @Test
    void filterInASubqueryCounts() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT tenant, count(*) FROM (SELECT * FROM audit_events WHERE tenant = 'abc') "
                        + "GROUP BY tenant",
                List.of(injectedTenantTable())));
    }

    /** A predicate carrying its own subquery is not cut short at the inner parentheses. */
    @Test
    void aPredicateWithItsOwnSubqueryIsReadWhole() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE year IN (SELECT y FROM years) AND tenant = 'abc'",
                List.of(injectedTenantTable())));
    }

    /** Double quotes mark an identifier in Athena, so a quoted column reference counts. */
    @Test
    void aQuotedColumnReferenceCounts() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE \"tenant\" = 'abc'", List.of(injectedTenantTable())));
    }

    /** A column named after a clause keyword is quoted, and must not end the clause it sits in. */
    @Test
    void aQuotedIdentifierSpellingAClauseKeywordDoesNotEndTheClause() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE \"limit\" = 10 AND tenant = 'abc'",
                List.of(injectedTenantTable())));
    }

    @Test
    void injectedFiltersMustApplyToEveryJoinedTableAlias() {
        Table second = table("payments", projecting("projection.tenant.type", "injected"),
                List.of(column("tenant")));

        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events a JOIN payments p ON a.id = p.id WHERE a.tenant = 'abc'",
                List.of(injectedTenantTable(), second)));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events a JOIN payments p ON a.id = p.id "
                        + "WHERE a.tenant = 'abc' AND p.tenant = 'xyz'",
                List.of(injectedTenantTable(), second)));
    }

    @Test
    void injectedFilterMustUseStaticEquality() {
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant > 'abc'", List.of(injectedTenantTable())));
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant >= 'abc'", List.of(injectedTenantTable())));
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = other_tenant", List.of(injectedTenantTable())));
    }

    @Test
    void commaSeparatedSourceAfterJoinConditionIsChecked() {
        Table orders = table("orders", Map.of(), List.of());
        Table payments = table("payments", Map.of(), List.of());
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM orders o JOIN payments p ON o.id = p.id, audit_events a WHERE o.id = 1",
                List.of(injectedTenantTable(), orders, payments)));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM orders o JOIN payments p ON o.id = p.id, audit_events a WHERE a.tenant = 'abc'",
                List.of(injectedTenantTable(), orders, payments)));
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM orders o JOIN payments p USING (id), audit_events a WHERE o.id = 1",
                List.of(injectedTenantTable(), orders, payments)));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM orders o JOIN payments p USING (id), audit_events a WHERE a.tenant = 'abc'",
                List.of(injectedTenantTable(), orders, payments)));
    }

    @Test
    void nestedQueryDeclaringSameColumnDoesNotCreateAmbiguityInOuterFilter() {
        Table other = table("other_table", Map.of(), List.of());
        other.getStorageDescriptor().setColumns(List.of(column("tenant")));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = 'abc' AND id IN (SELECT id FROM other_table WHERE foo = 1)",
                List.of(injectedTenantTable(), other)));
    }

    @Test
    void quotedAliasIsPreservedAndMatched() {
        Table orders = table("orders", Map.of(), List.of());
        orders.getStorageDescriptor().setColumns(List.of(column("tenant")));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events AS \"order\" JOIN orders o ON \"order\".id = o.id "
                        + "WHERE \"order\".tenant = 'abc'",
                List.of(injectedTenantTable(), orders)));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events \"order\" JOIN orders o ON \"order\".id = o.id "
                        + "WHERE \"order\".tenant = 'abc'",
                List.of(injectedTenantTable(), orders)));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events AS `order` JOIN orders o ON `order`.id = o.id "
                        + "WHERE `order`.tenant = 'abc'",
                List.of(injectedTenantTable(), orders)));
    }

    @Test
    void arbitraryParenthesesDepthAroundConstantIsAccepted() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = ((('abc')))", List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = (((('abc'))))", List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE ((('abc'))) = tenant", List.of(injectedTenantTable())));
    }

    @Test
    void inListBoundaryEnforcesOneThousandItemLimit() {
        List<String> thousandItems = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            thousandItems.add("'val" + i + "'");
        }
        String validInListQuery = "SELECT * FROM audit_events WHERE tenant IN ("
                + String.join(", ", thousandItems) + ")";
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                validInListQuery, List.of(injectedTenantTable())));

        thousandItems.add("'val1000'");
        String exceededInListQuery = "SELECT * FROM audit_events WHERE tenant IN ("
                + String.join(", ", thousandItems) + ")";
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                exceededInListQuery, List.of(injectedTenantTable())));
    }

    @Test
    void nestedFilterInSubqueryDoesNotSatisfyOuterTableCheck() {
        Table other = table("other_table", Map.of(), List.of());
        other.getStorageDescriptor().setColumns(List.of(column("tenant")));
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE id IN (SELECT id FROM other_table WHERE tenant = 'abc')",
                List.of(injectedTenantTable(), other)));
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE id IN (SELECT id FROM other_table WHERE audit_events.tenant = 'abc')",
                List.of(injectedTenantTable(), other)));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = 'abc' AND id IN (SELECT id FROM other_table WHERE tenant = 'xyz')",
                List.of(injectedTenantTable(), other)));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM other_table WHERE id IN (SELECT id FROM audit_events WHERE tenant = 'abc')",
                List.of(injectedTenantTable(), other)));
    }

    @Test
    void expressionsEvaluatedWithoutReadingDataAreAccepted() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = lower('ABC')", List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = 'ab' || 'c'", List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = concat('a', 'b')", List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = CAST(123 AS varchar)", List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = ('ab' || 'c')", List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE lower('ABC') = tenant", List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant IN (lower('ABC'), 'def')", List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant IN (concat('a', 'b'), 'c')",
                List.of(injectedTenantTable())));
    }

    @Test
    void functionNameOrCastTypeMatchingSourceColumnIsNotTakenForAColumnReference() {
        Table tableWithCollidingCols = table("audit_events", projecting("projection.tenant.type", "injected"),
                List.of(column("tenant")));
        tableWithCollidingCols.getStorageDescriptor().setColumns(List.of(
                column("id"), column("lower"), column("varchar"), column("suffix")));

        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = lower('ABC')", List.of(tableWithCollidingCols)));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = CAST(123 AS varchar)", List.of(tableWithCollidingCols)));

        // A column reference inside the function must still be rejected
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = lower(suffix)", List.of(tableWithCollidingCols)));
    }

    @Test
    void cteColumnValueIsNotAcceptedAsAStaticFilter() {
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "WITH x AS (SELECT 1 AS key) SELECT * FROM audit_events a JOIN x ON a.id = x.key "
                        + "WHERE a.tenant = x.key", List.of(injectedTenantTable())));
    }

    @Test
    void multiPartFunctionCallsAreAcceptedAsStaticFilters() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = catalog.schema.fn('abc')",
                List.of(injectedTenantTable())));
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = schema.fn('abc')",
                List.of(injectedTenantTable())));
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = catalog.schema.fn(suffix)",
                List.of(injectedTenantTable())));
    }
}
