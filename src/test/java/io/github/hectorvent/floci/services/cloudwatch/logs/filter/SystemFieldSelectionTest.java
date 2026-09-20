package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemFieldSelectionTest {

    private static final String ACCOUNT = "@aws.account = \"111111111111\"";
    private static final String REGION = "@aws.region = \"eu-west-1\"";

    @Test
    void keywordOperatorsFollowTheirTruthTables() {
        for (String account : List.of("111111111111", "222222222222")) {
            for (String region : List.of("eu-west-1", "us-east-1")) {
                boolean a = account.equals("111111111111");
                boolean b = region.equals("eu-west-1");
                for (String and : List.of("AND", "&&")) {
                    assertEquals(a && b, SystemFieldSelection.parse(ACCOUNT + " " + and + " " + REGION)
                            .test(account, region));
                }
                for (String or : List.of("OR", "||")) {
                    assertEquals(a || b, SystemFieldSelection.parse(ACCOUNT + " " + or + " " + REGION)
                            .test(account, region));
                }
            }
        }
    }

    @Test
    void andBindsMoreTightlyThanOrAndParenthesesOverridePrecedence() {
        for (String account : List.of("111111111111", "222222222222")) {
            for (String region : List.of("eu-west-1", "us-east-1", "ap-south-1")) {
                boolean a = account.equals("111111111111");
                boolean b = region.equals("eu-west-1");
                boolean c = !region.equals("us-east-1");
                String third = "@aws.region != \"us-east-1\"";
                assertEquals(a || b && c, SystemFieldSelection.parse(ACCOUNT + " OR " + REGION + " AND " + third)
                        .test(account, region));
                assertEquals((a || b) && c, SystemFieldSelection.parse("(" + ACCOUNT + " OR " + REGION + ") AND " + third)
                        .test(account, region));
            }
        }
    }

    @Test
    void membershipComposesWithoutRewritingKeywordsInsideValues() {
        assertTrue(SystemFieldSelection.parse(
                "@aws.account IN [\"111111111111\", \"222222222222\"] AND "
                        + "(@aws.region NOT IN [\"us-east-1\"] OR @aws.region = \"AND OR\")")
                .test("111111111111", "AND OR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "AND", "OR", "&", "|", "&&&", "|||", "AND AND", "OR OR", "XOR", "ANDROID", "ORDER"
    })
    void malformedOperatorsAreRejected(String operator) {
        assertThrows(FilterPatternException.class, () -> SystemFieldSelection.parse(ACCOUNT + " " + operator));
        if (!operator.equals("AND") && !operator.equals("OR")) {
            assertThrows(FilterPatternException.class,
                    () -> SystemFieldSelection.parse(ACCOUNT + " " + operator + " " + REGION));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@aws.account == \"111111111111\"",
            "@aws.account = =",
            "@aws.account = ||",
            "@aws.account != &&",
            "@aws.account NOTIN [\"111111111111\"]",
            "@aws.account IN [\"111111111111\",]",
            "@aws.account IN []",
            "@aws.account = \"111111111111\" AND ()",
            "(@aws.account = \"111111111111\""
    })
    void malformedComparisonsAreRejected(String expression) {
        assertThrows(FilterPatternException.class, () -> SystemFieldSelection.parse(expression));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AND", "OR"})
    void keywordsDoNotBecomeJsonLogPatternOperators(String operator) {
        assertThrows(FilterPatternException.class,
                () -> FilterPattern.parse("{ $.a = 1 " + operator + " $.b = 2 }"));
    }
}
