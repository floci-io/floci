package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.services.glue.model.Column;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class PartitionPrunerTest {
    private static final List<Column> DAY = List.of(new Column("day", "string"));
    private static final List<Column> YEAR_MONTH = List.of(new Column("year", "int"), new Column("month", "int"));
    private static final List<Column> DATE_KEY = List.of(new Column("dt", "date"));

    private static Optional<PartitionPruner.Filter> filter(String sql, List<Column> keys) {
        return PartitionPruner.filterFor(sql, "lake", "events", keys);
    }

    private static boolean keeps(Optional<PartitionPruner.Filter> filter, String... values) {
        return filter.orElseThrow().matches(List.of(values));
    }

    @Test
    void keepsOnlyThePartitionAnEqualityNames() {
        Optional<PartitionPruner.Filter> filter = filter("SELECT * FROM lake.events WHERE day = '2026-01-02'", DAY);

        assertThat(keeps(filter, "2026-01-02"), is(true));
        assertThat(keeps(filter, "2026-01-01"), is(false));
    }

    @Test
    void readsAnInListAndItsNegation() {
        Optional<PartitionPruner.Filter> in = filter("SELECT * FROM lake.events WHERE day IN ('a', 'b')", DAY);
        Optional<PartitionPruner.Filter> notIn = filter("SELECT * FROM lake.events WHERE day NOT IN ('a', 'b')", DAY);

        assertThat(keeps(in, "a"), is(true));
        assertThat(keeps(in, "c"), is(false));
        assertThat(keeps(notIn, "a"), is(false));
        assertThat(keeps(notIn, "c"), is(true));
    }

    @Test
    void comparesIntegerKeysByValueIncludingRangesAndBetween() {
        Optional<PartitionPruner.Filter> range = filter(
                "SELECT * FROM lake.events WHERE year >= 2021 AND year < 2023 AND month = '7'", YEAR_MONTH);
        Optional<PartitionPruner.Filter> between = filter(
                "SELECT * FROM lake.events WHERE year BETWEEN 2020 AND 2022 AND month = 1", YEAR_MONTH);

        assertThat(keeps(range, "2021", "7"), is(true));
        assertThat(keeps(range, "2022", "07"), is(true));
        assertThat(keeps(range, "2023", "7"), is(false));
        assertThat(keeps(range, "2020", "7"), is(false));
        assertThat(keeps(range, "2021", "8"), is(false));
        assertThat(keeps(between, "2022", "1"), is(true));
        assertThat(keeps(between, "2019", "1"), is(false));
    }

    @Test
    void comparesDateKeysByDate() {
        Optional<PartitionPruner.Filter> filter = filter(
                "SELECT * FROM lake.events WHERE dt > '2026-01-10' AND dt <= '2026-01-12'", DATE_KEY);

        assertThat(keeps(filter, "2026-01-11"), is(true));
        assertThat(keeps(filter, "2026-01-12"), is(true));
        assertThat(keeps(filter, "2026-01-10"), is(false));
        assertThat(keeps(filter, "2026-01-13"), is(false));
    }

    @Test
    void readsALiteralOnTheLeftAndNegativeNumbers() {
        Optional<PartitionPruner.Filter> filter = filter("SELECT * FROM lake.events WHERE 5 < year AND month = -1", YEAR_MONTH);

        assertThat(keeps(filter, "6", "-1"), is(true));
        assertThat(keeps(filter, "5", "-1"), is(false));
    }

    @Test
    void ignoresTermsItCannotReadAndKeepsTheRest() {
        Optional<PartitionPruner.Filter> filter = filter(
                "SELECT * FROM lake.events WHERE id = 7 AND lower(day) = 'x' AND day = 'a'", DAY);

        assertThat(keeps(filter, "a"), is(true));
        assertThat(keeps(filter, "b"), is(false));
    }

    @Test
    void doesNotPruneWhenNothingInTheWhereIsReadable() {
        assertThat(filter("SELECT * FROM lake.events WHERE id = 7", DAY).isPresent(), is(false));
        assertThat(filter("SELECT * FROM lake.events WHERE lower(day) = 'a'", DAY).isPresent(), is(false));
        assertThat(filter("SELECT * FROM lake.events", DAY).isPresent(), is(false));
    }

    @Test
    void treatsAWhereWithATopLevelOrAsOneTerm() {
        Optional<PartitionPruner.Filter> keysOnly = filter("SELECT * FROM lake.events WHERE day = 'a' OR day = 'b'", DAY);

        assertThat(keeps(keysOnly, "a"), is(true));
        assertThat(keeps(keysOnly, "b"), is(true));
        assertThat(keeps(keysOnly, "c"), is(false));
        assertThat(filter("SELECT * FROM lake.events WHERE day = 'a' OR id = 1 AND day = 'b'", DAY).isPresent(), is(false));
    }

    @Test
    void keepsAnAndInsideBetweenWithTheBetween() {
        Optional<PartitionPruner.Filter> filter = filter(
                "SELECT * FROM lake.events WHERE year BETWEEN 2020 AND 2021 AND month = 3", YEAR_MONTH);

        assertThat(keeps(filter, "2021", "3"), is(true));
        assertThat(keeps(filter, "2022", "3"), is(false));
    }

    @Test
    void readsNotAndParentheses() {
        Optional<PartitionPruner.Filter> filter = filter(
                "SELECT * FROM lake.events WHERE NOT (day = 'a' OR day = 'b')", DAY);

        assertThat(keeps(filter, "c"), is(true));
        assertThat(keeps(filter, "a"), is(false));
    }

    @Test
    void qualifiesColumnsByTheAliasWhenThereIsOne() {
        Optional<PartitionPruner.Filter> aliased = filter("SELECT * FROM lake.events AS e WHERE e.day = 'a'", DAY);
        Optional<PartitionPruner.Filter> bareAlias = filter("SELECT * FROM lake.events e WHERE e.day = 'a'", DAY);
        Optional<PartitionPruner.Filter> tableName = filter("SELECT * FROM lake.events WHERE events.day = 'a'", DAY);
        Optional<PartitionPruner.Filter> schemaAndTable = filter("SELECT * FROM lake.events WHERE lake.events.day = 'a'", DAY);

        assertThat(keeps(aliased, "b"), is(false));
        assertThat(keeps(bareAlias, "b"), is(false));
        assertThat(keeps(tableName, "b"), is(false));
        assertThat(keeps(schemaAndTable, "b"), is(false));
        assertThat(filter("SELECT * FROM lake.events e WHERE events.day = 'a'", DAY).isPresent(), is(false));
        assertThat(filter("SELECT * FROM lake.events e WHERE o.day = 'a'", DAY).isPresent(), is(false));
    }

    @Test
    void readsADatabaseQualifiedName() {
        Optional<PartitionPruner.Filter> filter = filter("SELECT * FROM dev.lake.events WHERE day = 'a'", DAY);

        assertThat(keeps(filter, "b"), is(false));
    }

    @Test
    void usesTheWhereOfTheBlockThatNamesTheTable() {
        Optional<PartitionPruner.Filter> inner = filter(
                "SELECT * FROM o WHERE o.id IN (SELECT id FROM lake.events WHERE day = 'a') AND o.x = 1", DAY);
        Optional<PartitionPruner.Filter> notInnerWhere = filter(
                "SELECT * FROM (SELECT * FROM lake.events) s WHERE s.day = 'a'", DAY);

        assertThat(keeps(inner, "b"), is(false));
        assertThat(keeps(inner, "a"), is(true));
        assertThat(notInnerWhere.isPresent(), is(false));
    }

    @Test
    void unionsEveryPlaceTheTableIsNamedAndGivesUpIfOneHasNoPredicate() {
        Optional<PartitionPruner.Filter> both = filter(
                "SELECT id FROM lake.events WHERE day = 'a' UNION ALL SELECT id FROM lake.events WHERE day = 'b'", DAY);

        assertThat(keeps(both, "a"), is(true));
        assertThat(keeps(both, "b"), is(true));
        assertThat(keeps(both, "c"), is(false));
        assertThat(filter("SELECT id FROM lake.events WHERE day = 'a' UNION ALL SELECT id FROM lake.events", DAY)
                .isPresent(), is(false));
        assertThat(filter("SELECT 1 FROM lake.events WHERE day = 'a'; SELECT 2 FROM lake.events", DAY).isPresent(),
                is(false));
    }

    @Test
    void stopsTheWhereAtTheClauseThatFollowsIt() {
        Optional<PartitionPruner.Filter> filter = filter(
                "SELECT day, count(*) FROM lake.events WHERE day = 'a' GROUP BY day HAVING count(*) > 1 ORDER BY 1 LIMIT 5", DAY);

        assertThat(keeps(filter, "a"), is(true));
        assertThat(keeps(filter, "b"), is(false));
    }

    @Test
    void dropsATermThatAcceptsNullBecauseTheTableMayBeOnTheNullableSideOfAnOuterJoin() {
        assertThat(filter("SELECT o.id FROM o LEFT JOIN lake.events e ON o.id = e.id WHERE e.day IS NULL", DAY)
                .isPresent(), is(false));
        assertThat(filter("SELECT o.id FROM o LEFT JOIN lake.events e ON o.id = e.id WHERE e.day IS NULL OR e.day = 'a'", DAY)
                .isPresent(), is(false));
        assertThat(filter("SELECT o.id FROM o LEFT JOIN lake.events e ON o.id = e.id WHERE NOT (e.day = 'a')", DAY)
                .isPresent(), is(true));
        assertThat(filter("SELECT o.id FROM o LEFT JOIN lake.events e ON o.id = e.id WHERE NOT (e.day IS NOT NULL)", DAY)
                .isPresent(), is(false));
    }

    @Test
    void keepsAnIsNotNullTermAndDropsTheDefaultPartition() {
        Optional<PartitionPruner.Filter> filter = filter("SELECT * FROM lake.events WHERE day IS NOT NULL", DAY);

        assertThat(keeps(filter, "a"), is(true));
        assertThat(keeps(filter, "__HIVE_DEFAULT_PARTITION__"), is(false));
    }

    @Test
    void readsTheHiveDefaultPartitionAndAValueWiderThanItsKeyAsNull() {
        Optional<PartitionPruner.Filter> equality = filter("SELECT * FROM lake.events WHERE day = 'a'", DAY);
        Optional<PartitionPruner.Filter> narrow = filter("SELECT * FROM lake.events WHERE day = 'abcd'",
                List.of(new Column("day", "varchar(3)")));

        assertThat(keeps(equality, "__HIVE_DEFAULT_PARTITION__"), is(false));
        assertThat(keeps(narrow, "abcd"), is(false));
        assertThat(keeps(narrow, "abc"), is(false));
    }

    @Test
    void keepsAPartitionWhoseValueItCannotCompare() {
        Optional<PartitionPruner.Filter> filter = filter("SELECT * FROM lake.events WHERE year = 2020 AND month = 1", YEAR_MONTH);

        assertThat(keeps(filter, "not-a-number", "1"), is(true));
    }

    @Test
    void readsOnlyEqualityOnStringKeysBecauseOrderingDependsOnTheCollation() {
        assertThat(filter("SELECT * FROM lake.events WHERE day > 'a'", DAY).isPresent(), is(false));
        assertThat(filter("SELECT * FROM lake.events WHERE day LIKE 'a%'", DAY).isPresent(), is(false));
        assertThat(filter("SELECT * FROM lake.events WHERE day = 1", DAY).isPresent(), is(false));
    }

    @Test
    void ignoresKeyTypesItDoesNotCompare() {
        assertThat(filter("SELECT * FROM lake.events WHERE p = 1.5", List.of(new Column("p", "double"))).isPresent(),
                is(false));
        assertThat(filter("SELECT * FROM lake.events WHERE p = '2026-01-01 00:00:00'",
                List.of(new Column("p", "timestamp"))).isPresent(), is(false));
        assertThat(filter("SELECT * FROM lake.events WHERE dt = '1/2/2026'", DATE_KEY).isPresent(), is(false));
    }

    @Test
    void ignoresCommentsAndQuotedTextThatLooksLikeSql() {
        Optional<PartitionPruner.Filter> filter = filter(
                "SELECT 'x) where' /* WHERE day = 'z' */ FROM lake.events -- ) WHERE\n WHERE \"day\" = 'a)b'", DAY);

        assertThat(keeps(filter, "a)b"), is(true));
        assertThat(keeps(filter, "z"), is(false));
    }

    @Test
    void givesUpOnTextWhoseQuotingItDoesNotRead() {
        assertThat(filter("SELECT E'a\\'' FROM lake.events WHERE day = 'a'", DAY).isPresent(), is(false));
        assertThat(filter("SELECT $$x$$ FROM lake.events WHERE day = 'a'", DAY).isPresent(), is(false));
        assertThat(filter("SELECT 'unterminated FROM lake.events WHERE day = 'a'", DAY).isPresent(), is(false));
        assertThat(filter("SELECT (1 FROM lake.events WHERE day = 'a'", DAY).isPresent(), is(false));
        assertThat(filter("SELECT 1) FROM lake.events WHERE day = 'a'", DAY).isPresent(), is(false));
    }

    @Test
    void ignoresAParameterAndKeepsTheOtherTerms() {
        Optional<PartitionPruner.Filter> filter = filter(
                "SELECT * FROM lake.events WHERE day = $1 AND day <> 'b'", DAY);

        assertThat(keeps(filter, "a"), is(true));
        assertThat(keeps(filter, "b"), is(false));
    }

    @Test
    void ignoresAnotherSchemaOrTable() {
        assertThat(filter("SELECT * FROM other.events WHERE day = 'a'", DAY).isPresent(), is(false));
        assertThat(filter("SELECT * FROM lake.other WHERE day = 'a'", DAY).isPresent(), is(false));
        assertThat(PartitionPruner.filterFor("SELECT * FROM lake.events WHERE day = 'a'", "lake", "events", List.of())
                .isPresent(), is(false));
        assertThat(PartitionPruner.filterFor(null, "lake", "events", DAY).isPresent(), is(false));
    }

    @Test
    void doesNotTreatAColumnQualifiedBySchemaAndTableAsAnotherReference() {
        Optional<PartitionPruner.Filter> filter = filter(
                "SELECT lake.events.id FROM lake.events WHERE lake.events.day = 'a'", DAY);

        assertThat(keeps(filter, "a"), is(true));
        assertThat(keeps(filter, "b"), is(false));
    }

    @Test
    void matchesQuotedNamesExactlyAndUnquotedOnesIgnoringCase() {
        Optional<PartitionPruner.Filter> upperUnquoted = filter("SELECT * FROM LAKE.EVENTS WHERE DAY = 'a'", DAY);
        Optional<PartitionPruner.Filter> quotedCase = PartitionPruner.filterFor(
                "SELECT * FROM \"Lake\".\"Events\" WHERE \"Day\" = 'a'", "Lake", "Events", List.of(new Column("Day", "string")));

        assertThat(keeps(upperUnquoted, "b"), is(false));
        assertThat(keeps(quotedCase, "b"), is(false));
        assertThat(PartitionPruner.filterFor("SELECT * FROM \"Lake\".\"Events\" WHERE \"day\" = 'a'", "Lake", "Events",
                List.of(new Column("Day", "string"))).isPresent(), is(false));
    }

    @Test
    void aWhereBeforeTheTableIsNamedDoesNotApplyToIt() {
        assertThat(filter("SELECT * FROM a WHERE a.x = 1 AND EXISTS (SELECT 1 FROM lake.events)", DAY).isPresent(),
                is(false));
        assertThat(filter("SELECT * FROM lake.events WHERE day = 'a'", DAY).isPresent(), is(true));
    }

    @Test
    void doesNotTreatAndInsideCaseAsATopLevelSeparator() {
        String sql = "SELECT * FROM lake.events WHERE CASE WHEN id = 7 AND day = 'a' AND id = 8 THEN TRUE ELSE TRUE END";
        assertThat(filter(sql, DAY).isPresent(), is(false));
    }

    @Test
    void keepsValidConjunctAlongsideACaseExpression() {
        String sql = "SELECT * FROM lake.events WHERE (CASE WHEN id = 1 THEN 'a' ELSE 'b' END) = 'a' AND day = '2026-01-01'";
        Optional<PartitionPruner.Filter> filter = filter(sql, DAY);

        assertThat(keeps(filter, "2026-01-01"), is(true));
        assertThat(keeps(filter, "2026-01-02"), is(false));
    }

    @Test
    void readsPredicatesPastNestedComments() {
        Optional<PartitionPruner.Filter> filter = filter(
                "SELECT * FROM lake.events WHERE /* outer /* nested */ */ day = '2026-01-01'", DAY);

        assertThat(keeps(filter, "2026-01-01"), is(true));
        assertThat(keeps(filter, "2026-01-02"), is(false));
    }

    @Test
    void ignoresDollarQuotedPredicatesAndFallsBackSafely() {
        String sql = "SELECT * FROM lake.events WHERE day = $tag$2026-01-01$tag$";
        assertThat(filter(sql, DAY).isPresent(), is(false));
    }

    @Test
    void handlesQuotedIdentifiersWithEscapesInPredicates() {
        Optional<PartitionPruner.Filter> filter = filter(
                "SELECT * FROM lake.events WHERE \"day\" = '2026-01-01'", DAY);

        assertThat(keeps(filter, "2026-01-01"), is(true));
        assertThat(keeps(filter, "2026-01-02"), is(false));
    }

    @Test
    void returnsEmptyFilterForUnterminatedLiteralsInPredicates() {
        assertThat(filter("SELECT * FROM lake.events WHERE day = 'unterminated", DAY).isPresent(), is(false));
        assertThat(filter("SELECT * FROM lake.events WHERE \"day = '2026-01-01'", DAY).isPresent(), is(false));
        assertThat(filter("SELECT * FROM lake.events WHERE /* unclosed day = '2026-01-01'", DAY).isPresent(), is(false));
    }
}
