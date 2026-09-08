package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The filter pattern syntax CloudWatch Logs documents for metric filters, subscription filters and
 * FilterLogEvents: plain terms, {@code %regex%}, JSON selectors and space-delimited fields. The
 * examples are the ones on the AWS reference pages, with the matches those pages state.
 */
class FilterPatternTest {

    private static boolean matches(String pattern, String message) {
        return FilterPattern.parse(pattern).match(message).matched();
    }

    private static FilterMatch match(String pattern, String message) {
        return FilterPattern.parse(pattern).match(message);
    }

    @Nested
    class Terms {

        @Test
        void aSingleTermMatchesMessagesContainingIt() {
            assertTrue(matches("ERROR", "[ERROR 400] BAD REQUEST"));
            assertTrue(matches("ERROR", "[ERROR 419] MISSING ARGUMENTS"));
            assertFalse(matches("ERROR", "[INFO 200] OK"));
        }

        @Test
        void termsAreCaseSensitiveSubstrings() {
            assertFalse(matches("ERROR", "an error happened"));
            assertTrue(matches("Exception", "[ERROR] Unhanded exception: InvalidQueryException"),
                    "the API reference matches Exception inside InvalidQueryException");
        }

        @Test
        void severalTermsMustAllBePresent() {
            assertTrue(matches("ERROR ARGUMENTS", "[ERROR 419] MISSING ARGUMENTS"));
            assertTrue(matches("ERROR ARGUMENTS", "[ERROR 420] INVALID ARGUMENTS"));
            assertFalse(matches("ERROR ARGUMENTS", "[ERROR 400] BAD REQUEST"));
            assertFalse(matches("ERROR ARGUMENTS", "[ERROR 401] UNAUTHORIZED REQUEST"));
        }

        @Test
        void questionMarkTermsMatchWhenAnyIsPresent() {
            for (String message : List.of("[ERROR 400] BAD REQUEST", "[ERROR 401] UNAUTHORIZED REQUEST",
                    "[ERROR 419] MISSING ARGUMENTS", "[ERROR 420] INVALID ARGUMENTS")) {
                assertTrue(matches("?ERROR ?ARGUMENTS", message), message);
            }
            assertFalse(matches("?ERROR ?ARGUMENTS", "[INFO 200] OK"));
        }

        /** The reference: combined with other terms, the question mark terms are ignored. */
        @Test
        void questionMarkTermsAreIgnoredNextToOtherTerms() {
            assertTrue(matches("?ERROR ?ARGUMENTS REQUEST", "[INFO] REQUEST FAILED"));
            assertTrue(matches("?ERROR ?ARGUMENTS REQUEST", "[WARN] UNAUTHORIZED REQUEST"));
            assertTrue(matches("?ERROR ?ARGUMENTS REQUEST", "[ERROR] 400 BAD REQUEST"));
            assertFalse(matches("?ERROR ?ARGUMENTS REQUEST", "[ERROR] MISSING ARGUMENTS"));
        }

        @Test
        void quotedPhrasesMatchExactly() {
            assertTrue(matches("\"INTERNAL SERVER ERROR\"", "[ERROR 500] INTERNAL SERVER ERROR"));
            assertFalse(matches("\"INTERNAL SERVER ERROR\"", "[ERROR 500] INTERNAL ERROR ON SERVER"));
            assertTrue(matches("\"[ERROR]\"", "02 May 2014 00:34:16,142 [ERROR] Unhanded exception"));
            assertFalse(matches("\"[ERROR]\"", "02 May 2014 00:34:12,525 [INFO] Starting the application"));
        }

        @Test
        void aQuotedPhraseAndATermCombine() {
            assertTrue(matches("\"[ERROR]\" Exception",
                    "02 May 2014 00:34:16,142 [ERROR] Unhanded exception: InvalidQueryException"));
            assertFalse(matches("\"[ERROR]\" Exception", "02 May 2014 00:34:16,224 [ERROR] Terminating the application"));
        }

        @Test
        void minusExcludesATerm() {
            assertTrue(matches("ERROR -ARGUMENTS", "[ERROR 400] BAD REQUEST"));
            assertTrue(matches("ERROR -ARGUMENTS", "[ERROR 401] UNAUTHORIZED REQUEST"));
            assertFalse(matches("ERROR -ARGUMENTS", "[ERROR 419] MISSING ARGUMENTS"));
            assertFalse(matches("ERROR -ARGUMENTS", "[ERROR 420] INVALID ARGUMENTS"));
        }

        @Test
        void aQuotedSpaceMatchesEverythingWithASpaceAndAnEmptyPatternMatchesEverything() {
            assertTrue(matches("\" \"", "two words"));
            assertTrue(matches("", "anything at all"));
            assertTrue(matches("   ", "anything at all"));
            assertTrue(matches("", ""));
        }

        @Test
        void aTermMatchHasNoExtractedValues() {
            FilterMatch m = match("ERROR", "[ERROR 400] BAD REQUEST");
            assertTrue(m.matched());
            assertEquals(Map.of(), m.extractedValues());
            assertNull(m.value("$.anything"));
        }

        @Test
        void aNullMessageMatchesNothingButTheEmptyPattern() {
            assertFalse(matches("ERROR", null));
            assertFalse(matches("%ERR%", null));
            assertTrue(matches("-ERROR", null), "an exclusion alone matches what lacks the term");
            assertTrue(matches("", null));
            assertFalse(matches("{ $.a = 1 }", null));
            assertFalse(matches("[a, b]", null));
        }

        @Test
        void anUnterminatedQuoteIsRejected() {
            assertThrows(FilterPatternException.class, () -> FilterPattern.parse("\"INTERNAL SERVER"));
        }
    }

    @Nested
    class Regex {

        @Test
        void aStandaloneRegexMatchesAnywhereInTheMessage() {
            assertTrue(matches("%AUTHORIZED%", "[ERROR 401] UNAUTHORIZED REQUEST"));
            assertTrue(matches("%AUTHORIZED%", "[SUCCESS 200] AUTHORIZED REQUEST"));
            assertFalse(matches("%AUTHORIZED%", "[SUCCESS 200] OK"));
        }

        @Test
        void theDocumentedOperatorsBehaveAsDocumented() {
            assertTrue(matches("%^[hc]at%", "hat trick"));
            assertFalse(matches("%^[hc]at%", "a hat"));
            assertTrue(matches("%[hc]at$%", "the cat"));
            assertTrue(matches("%colou?r%", "color"));
            assertTrue(matches("%colou?r%", "colour"));
            assertTrue(matches("%a{3,5}%", "aaaa"));
            assertFalse(matches("%^a{3,5}$%", "aa"));
            assertTrue(matches("%gra|ey%", "grey"));
            assertTrue(matches("%^starting|^initializing|^shutting down%", "shutting down now"));
            assertFalse(matches("%^starting|^initializing|^shutting down%", "skipping initializing ..."));
            assertTrue(matches("%\\[.\\]%", "value [7] here"));
            assertTrue(matches("%ab*c%", "ac"));
            assertTrue(matches("%ab+c%", "abbc"));
            assertFalse(matches("%ab+c%", "ac"));
            assertTrue(matches("%.at%", "4at"));
            assertTrue(matches("%\\d%", "port 80"));
            assertTrue(matches("%\\D%", "port 80"));
            assertTrue(matches("%\\s%", "a b"));
            assertTrue(matches("%\\w%", "_"));
            assertTrue(matches("%\\x3A%", "key:value"), "\\xhh matches the ASCII character");
            assertTrue(matches("%10\\.10\\.0\\.1%", "from 10.10.0.1"));
            assertFalse(matches("%10\\.10\\.0\\.1%", "from 10010,051"));
        }

        @Test
        void unsupportedCharactersAreRejected() {
            assertThrows(FilterPatternException.class, () -> FilterPattern.parse("%something!%"));
            assertThrows(FilterPatternException.class, () -> FilterPattern.parse("%(ab)+%"),
                    "parentheses are not supported");
            assertThrows(FilterPatternException.class, () -> FilterPattern.parse("%café%"),
                    "multi-byte characters are not supported");
            assertThrows(FilterPatternException.class, () -> FilterPattern.parse("%\\p%"),
                    "only the documented escapes are supported");
            assertThrows(FilterPatternException.class, () -> FilterPattern.parse("%\\(ab\\)+%"),
                    "escaping does not admit parentheses");
            assertThrows(FilterPatternException.class, () -> FilterPattern.parse("%\\!%"));
            assertTrue(matches("%a\\-b%", "a-b"), "an escaped symbol from the allowed list is fine");
            assertTrue(matches("%\\\\%", "back\\slash"), "an escaped backslash is a backslash");
            assertThrows(FilterPatternException.class, () -> FilterPattern.parse("%%"));
            assertThrows(FilterPatternException.class, () -> FilterPattern.parse("%abc"));
            assertThrows(FilterPatternException.class, () -> FilterPattern.parse("%a{3,%"));
        }

        @Test
        void aPatternMayHoldAtMostTwoRegexes() {
            FilterPattern.parse("[a=%x%, b=%y%, c]");
            assertThrows(FilterPatternException.class, () -> FilterPattern.parse("[a=%x%, b=%y%, c=%z%]"));
        }
    }

    @Nested
    class Json {

        private static final String EVENT = """
                {
                  "eventType": "UpdateTrail",
                  "sourceIPAddress": "111.111.111.111",
                  "arrayKey": ["value", "another value"],
                  "objectList": [{"name": "a", "id": 1}, {"name": "b", "id": 2}],
                  "SomeObject": null,
                  "cluster.name": "c"
                }
                """;

        private static final String COMPOUND = """
                {
                  "user": {"id": 1, "email": "John.Stiles@example.com"},
                  "users": [{"id": 2, "email": "John.Doe@example.com"}, {"id": 3, "email": "Jane.Doe@example.com"}],
                  "actions": ["GET", "PUT", "DELETE"],
                  "coordinates": [[0, 1, 2], [4, 5, 6], [7, 8, 9]]
                }
                """;

        @Test
        void theReferenceStringExamples() {
            assertTrue(matches("{ $.eventType = \"UpdateTrail\" }", EVENT));
            assertTrue(matches("{ $.sourceIPAddress != 123.123.* }", EVENT));
            assertFalse(matches("{ $.sourceIPAddress != 111.111.* }", EVENT));
            assertTrue(matches("{ $.arrayKey[0] = \"value\" }", EVENT));
            assertFalse(matches("{ $.arrayKey[1] = \"value\" }", EVENT));
            assertTrue(matches("{ $.eventType = %Trail% }", EVENT));
            assertTrue(matches("{ $.arrayKey[*] = %val.{2}% }", EVENT));
            assertTrue(matches("{ $.* = %111\\.111\\.111\\.1[0-9]{1,2}% }", EVENT));
            assertTrue(matches("{ $.['cluster.name'] = \"c\" }", EVENT));
            assertTrue(matches("{ $.SomeObject IS NULL }", EVENT));
            assertFalse(matches("{ $.eventType IS NULL }", EVENT));
            assertTrue(matches("{ $.SomeOtherObject NOT EXISTS }", EVENT));
            assertFalse(matches("{ $.eventType NOT EXISTS }", EVENT));
            assertTrue(matches("{ $.objectList[1].name = \"b\" }", EVENT));
            assertTrue(matches("{ $.objectList[*].id = 2 }", EVENT));
        }

        @Test
        void theReferenceNumericExamples() {
            assertTrue(matches("{ $.bandwidth > 75 }", "{\"bandwidth\": 80}"));
            assertFalse(matches("{ $.bandwidth > 75 }", "{\"bandwidth\": 75}"));
            assertTrue(matches("{ $.latency < 50 }", "{\"latency\": 49.5}"));
            assertTrue(matches("{ $.refreshRate >= 60 }", "{\"refreshRate\": 60}"));
            assertTrue(matches("{ $.responseTime <= 5 }", "{\"responseTime\": 5}"));
            assertTrue(matches("{ $.errorCode = 400}", "{\"errorCode\": 400}"));
            assertTrue(matches("{ $.errorCode != 500 }", "{\"errorCode\": 400}"));
            assertFalse(matches("{ $.errorCode != 500 }", "{\"errorCode\": 500}"));
            assertTrue(matches("{ $.number[0] = 1e+3 }", "{\"number\": [1000]}"));
            assertTrue(matches("{ $.number[0] != 1e-3 }", "{\"number\": [1000]}"));
            assertTrue(matches("{ $.errorCode = 4* }", "{\"errorCode\": 404}"), "the asterisk also matches numbers");
        }

        @Test
        void theReferenceCompoundExamples() {
            assertTrue(matches("{ ($.user.id = 1) && ($.users[0].email = \"John.Doe@example.com\") }", COMPOUND));
            assertTrue(matches("{ $.user.email = \"John.Stiles@example.com\" || $.coordinates[0][1] = \"nonmatch\""
                    + " && $.actions[2] = \"nonmatch\" }", COMPOUND));
            assertFalse(matches("{ ($.user.email = \"John.Stiles@example.com\" || $.coordinates[0][1] = \"nonmatch\")"
                    + " && $.actions[2] = \"nonmatch\" }", COMPOUND));
            assertFalse(matches("{ ($.user.id = 2 && $.users[0].email = \"nonmatch\") || $.actions[2] = \"GET\" }",
                    COMPOUND));
            assertTrue(matches("{ $.coordinates[1][2] = 6 }", COMPOUND));
        }

        @Test
        void aMessageThatIsNotJsonDoesNotMatch() {
            assertFalse(matches("{ $.eventType = \"UpdateTrail\" }", "eventType=UpdateTrail"));
            assertFalse(matches("{ $.eventType = \"UpdateTrail\" }", "2024-01-01 INFO {\"eventType\": \"UpdateTrail\"}"));
            assertFalse(matches("{ $.eventType NOT EXISTS }", "not json"), "an unparseable message matches nothing");
            assertFalse(matches("{ $.eventType = \"UpdateTrail\" }", ""));
        }

        @Test
        void aSelectorPointingAtAnObjectOrArrayDoesNotMatch() {
            assertFalse(matches("{ $.arrayKey = \"value\" }", EVENT));
            assertFalse(matches("{ $.objectList[0] != \"x\" }", EVENT));
            assertFalse(matches("{ $.missing != \"x\" }", EVENT), "a missing field satisfies no comparison");
        }

        @Test
        void bareWordsQuotedStringsAndBooleans() {
            assertTrue(matches("{ $.eventType = UpdateTrail }", EVENT), "alphanumeric strings may be unquoted");
            assertTrue(matches("{ $.flag IS TRUE }", "{\"flag\": true}"));
            assertTrue(matches("{ $.flag IS FALSE }", "{\"flag\": false}"));
            assertFalse(matches("{ $.flag IS TRUE }", "{\"flag\": \"true\"}"), "IS TRUE wants a boolean");
            assertTrue(matches("{ $.flag = true }", "{\"flag\": true}"));
            assertTrue(matches("{ $.eventType = \"*\" }", EVENT), "a wildcard string matches any value");
            assertFalse(matches("{ $.SomeObject = \"*\" }", EVENT), "but not null");
            assertTrue(matches("{ $.count = \"3\" }", "{\"count\": 3}"), "a number compares by its text too");
            assertTrue(matches("{ $.count = 3 }", "{\"count\": \"3\"}"), "and a numeric string by its value");
            assertFalse(matches("{ $.count > 2 }", "{\"count\": \"many\"}"));
        }

        @Test
        void extractedValuesAreTheSelectorsOfThePattern() {
            FilterMatch m = match("{ $.eventType = \"UpdateTrail\" && $.objectList[1].id = 2 }", EVENT);
            assertTrue(m.matched());
            assertEquals(Map.of("$.eventType", "UpdateTrail", "$.objectList[1].id", "2"), m.extractedValues());
            assertEquals("UpdateTrail", m.value("$.eventType"));
            assertEquals("111.111.111.111", m.value("$.sourceIPAddress"), "any field can be read off a match");
            assertEquals("c", m.value("$.['cluster.name']"));
            assertNull(m.value("$.missing"));
            assertNull(m.value("$.objectList"), "objects and arrays have no value");
            assertNull(m.value("$server"), "a space-delimited reference means nothing here");
        }

        @Test
        void malformedPatternsAreRejected() {
            for (String bad : List.of("{ $.a = }", "{ $.a }", "{ $.a = 1", "{ $.a = 1 } trailing", "{ a = 1 }",
                    "{ $.a == 1 }", "{ $.a = 1 && }", "{ ($.a = 1 }", "{ $.a IS MAYBE }", "{ $.a EXISTS }",
                    "{ $.a[x] = 1 }", "{ $.a = \"unterminated }")) {
                assertThrows(FilterPatternException.class, () -> FilterPattern.parse(bad), bad);
            }
        }
    }

    @Nested
    class SpaceDelimited {

        private static final String APACHE =
                "127.0.0.1 Prod frank [10/Oct/2000:13:25:15 -0700] \"GET /index.html HTTP/1.0\" 404 1534";
        private static final String ACCESS_200 =
                "127.0.0.1 - frank [10/Oct/2000:13:25:15 -0700] \"GET /apache_pb.gif HTTP/1.0\" 200 1534";

        @Test
        void quotedAndBracketedRunsAreSingleFields() {
            FilterMatch m = match("[ip, user, username, timestamp, request, status_code, bytes]", APACHE);
            assertTrue(m.matched());
            assertEquals("127.0.0.1", m.value("$ip"));
            assertEquals("Prod", m.value("$user"));
            assertEquals("10/Oct/2000:13:25:15 -0700", m.value("$timestamp"));
            assertEquals("GET /index.html HTTP/1.0", m.value("$request"));
            assertEquals("404", m.value("$status_code"));
            assertEquals("1534", m.value("$bytes"));
            assertEquals("1534", m.value("$7"), "positions can always be read");
        }

        /**
         * The reference says {@code [w1=ERROR, w2]} matches lines of any length and that a blank
         * indicator must follow the last term, which holds when the last field takes the rest of
         * the line. An ellipsis stands for any number of fields, before, between or after the named ones.
         */
        @Test
        void fieldCountRules() {
            FilterMatch rest = match("[ip, user, rest]", APACHE);
            assertTrue(rest.matched());
            assertEquals("frank [10/Oct/2000:13:25:15 -0700] \"GET /index.html HTTP/1.0\" 404 1534", rest.value("$rest"));
            assertFalse(matches("[w1=ERROR]", "ERROR 09/25/2014 12:00:02 Failed"), "without a blank indicator w1 is the whole line");
            assertFalse(matches("[a, b, c, d, e, f, g, h]", APACHE), "eight fields need eight words");
            assertFalse(matches("[w1=ERROR, w2]", "ERROR"), "a blank indicator still needs a word");
            assertTrue(matches("[..., bytes]", APACHE));
            assertTrue(matches("[ip, ..., bytes]", APACHE));
            assertTrue(matches("[ip, user, ...]", APACHE));
            assertFalse(matches("[a, b, c, d, e, f, g, h, ...]", APACHE), "eight named fields need eight fields");
            assertTrue(matches("[]", APACHE), "an empty field list matches any event");
            assertTrue(matches("[]", ""), "even an empty one");
            assertFalse(matches("[a]", ""));
        }

        @Test
        void theReferenceConditions() {
            assertTrue(matches("[ip=%127\\.0\\.0\\.[1-9]%, user, username, timestamp, request =*.html*,"
                    + " status_code = 4*, bytes]", APACHE));
            assertTrue(matches("[..., request =*.html*, status_code = 4*, bytes]", APACHE));
            assertTrue(matches("[ip, user, username, timestamp, request =*.html*, status_code = 404"
                    + " || status_code = 410, bytes]", APACHE));
            assertTrue(matches("[ip, server, username, timestamp, request, status_code, bytes > 1000]", APACHE));
            assertFalse(matches("[ip, server, username, timestamp, request, status_code, bytes > 2000]", APACHE));
            assertTrue(matches("[..., status_code=200, size]", ACCESS_200));
            assertFalse(matches("[..., status_code=200, size]", APACHE));
        }

        @Test
        void theReferencePatternMatchingExamples() {
            assertTrue(matches("[w1=ERROR, w2]", "ERROR 09/25/2014 12:00:02 Failed to process request"));
            assertFalse(matches("[w1=ERROR, w2]", "INFO 09/25/2014 12:00:00 GET /service/resource/67 1200"));
            assertTrue(matches("[w1=ERROR || w1=WARNING, w2]", "WARNING 09/25/2014 12:00:02 Invalid user request"));
            assertTrue(matches("[w1!=ERROR && w1!=WARNING, w2]", "INFO 09/25/2014 12:00:00 GET /service/resource/67 1200"));
            assertFalse(matches("[w1!=ERROR && w1!=WARNING, w2]", "ERROR 09/25/2014 12:00:02 Failed to process request"));
            assertTrue(matches("[logLevel, date, time, method, url=%/service/resource/[0-9]+$%, response_time]",
                    "INFO 09/25/2014 12:00:00 GET /service/resource/67 1200"));
            assertFalse(matches("[logLevel, date, time, method, url=%/service/resource/[0-9]+$%, response_time]",
                    "INFO 09/25/2014 12:00:01 POST /service/resource/67/part/111 1310"));
            assertTrue(matches("[logLevel, date, time, method, url=%/service/resource/[0-9]+/part/[0-9]+$%, response_time]",
                    "INFO 09/25/2014 12:00:01 POST /service/resource/67/part/111 1310"));
            assertTrue(matches("[w1=ERROR || w1=%WARN%, w2]", "WARNING 09/25/2014 12:00:02 Invalid user request"));
        }

        /** The extracted values of the TestMetricFilter reference examples, verbatim. */
        @Test
        void extractedValuesNameFieldsAndNumberTheRest() {
            FilterMatch all = match("[ip, identity, user_id, timestamp, request, status_code, size]", ACCESS_200);
            assertEquals(Map.of("$ip", "127.0.0.1", "$identity", "-", "$user_id", "frank",
                    "$timestamp", "10/Oct/2000:13:25:15 -0700", "$request", "GET /apache_pb.gif HTTP/1.0",
                    "$status_code", "200", "$size", "1534"), all.extractedValues());

            FilterMatch ellipsis = match("[..., size]", ACCESS_200);
            assertEquals(Map.of("$1", "127.0.0.1", "$2", "-", "$3", "frank", "$4", "10/Oct/2000:13:25:15 -0700",
                    "$5", "GET /apache_pb.gif HTTP/1.0", "$6", "200", "$size", "1534"), ellipsis.extractedValues());

            FilterMatch none = match("[]", ACCESS_200);
            assertEquals("1534", none.extractedValues().get("$7"));
            assertEquals(7, none.extractedValues().size());

            FilterMatch mixed = match("[..., status_code=200, size]", ACCESS_200);
            assertEquals("200", mixed.extractedValues().get("$status_code"));
            assertEquals("1534", mixed.extractedValues().get("$size"));
            assertEquals("frank", mixed.extractedValues().get("$3"));

            FilterMatch trailingComma = match("[..., request=*.html*, status_code=4*,]", APACHE);
            assertTrue(trailingComma.matched());
            assertEquals("1534", trailingComma.extractedValues().get("$7"), "an empty entry is an unnamed field");
            assertEquals("404", trailingComma.extractedValues().get("$status_code"));
        }

        @Test
        void valuesOfNamedAndNumberedFieldsCanBeReadOffAMatch() {
            FilterMatch m = match("[..., status_code, bytes]", APACHE);
            assertEquals("1534", m.value("$bytes"));
            assertEquals("Prod", m.value("$2"));
            assertNull(m.value("$missing"));
            assertNull(m.value("$9"));
            assertNull(m.value("$.bytes"), "a JSON reference means nothing here");
        }

        @Test
        void quotedValuesAndNumbersCompare() {
            assertTrue(matches("[..., request=\"GET /index.html HTTP/1.0\", status_code, bytes]", APACHE));
            assertTrue(matches("[..., status_code >= 400 && status_code < 500, bytes]", APACHE));
            assertTrue(matches("[..., status_code, bytes = 1534]", APACHE));
            assertFalse(matches("[..., status_code, bytes < 100]", APACHE));
            assertFalse(matches("[..., status_code, bytes > 100]", "a b c d e f text"), "a word is not a number");
        }

        @Test
        void malformedPatternsAreRejected() {
            for (String bad : List.of("[a, ..., b, ..., c]", "[a, a]", "[a = ]", "[a, b", "[a, b] trailing",
                    "[a = 1 ||]", "[1a]", "[a, b=x || c=y]")) {
                assertThrows(FilterPatternException.class, () -> FilterPattern.parse(bad), bad);
            }
        }
    }

    @Nested
    class References {

        @ParameterizedTest
        @ValueSource(strings = {"$ip", "$1", "$7"})
        void aDelimitedPatternDeclaresItsNamesAndAnyPosition(String reference) {
            assertTrue(FilterPattern.parse("[ip, ..., bytes]").declaresField(reference));
        }

        @Test
        void aDelimitedPatternDoesNotDeclareOtherNames() {
            FilterPattern pattern = FilterPattern.parse("[ip, ..., bytes]");
            assertFalse(pattern.declaresField("$size"));
            assertFalse(pattern.declaresField("$.ip"));
            assertFalse(pattern.declaresField("ip"));
        }

        @Test
        void aJsonPatternDeclaresAnySelector() {
            FilterPattern pattern = FilterPattern.parse("{ $.a = 1 }");
            assertTrue(pattern.declaresField("$.latency"));
            assertTrue(pattern.declaresField("$.['a.b'][0]"));
            assertFalse(pattern.declaresField("$latency"));
            assertFalse(pattern.declaresField("$.a["));
        }

        @Test
        void termPatternsDeclareNothing() {
            assertFalse(FilterPattern.parse("ERROR").declaresField("$.a"));
            assertFalse(FilterPattern.parse("").declaresField("$1"));
        }

        @Test
        void kindsAreReported() {
            assertEquals(FilterPattern.Kind.MATCH_ALL, FilterPattern.parse(" ").kind());
            assertEquals(FilterPattern.Kind.TERMS, FilterPattern.parse("ERROR").kind());
            assertEquals(FilterPattern.Kind.TERMS, FilterPattern.parse("%ERR%").kind());
            assertEquals(FilterPattern.Kind.JSON, FilterPattern.parse("{ $.a = 1 }").kind());
            assertEquals(FilterPattern.Kind.SPACE_DELIMITED, FilterPattern.parse("[a, b]").kind());
        }
    }
}
