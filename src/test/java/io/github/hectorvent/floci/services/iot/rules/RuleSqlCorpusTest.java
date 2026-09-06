package io.github.hectorvent.floci.services.iot.rules;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real statements, mostly lifted from the AWS IoT SQL reference, sorted into the two things the
 * parser may do with them. A statement inside the subset must parse. A statement outside it
 * must fail naming the first thing that could not be placed, never anything else, because
 * that failure is what sends the rule down the unparsed path and into the WARN log. The
 * tokenizer runs before the parser, so a character outside the grammar (an arithmetic sign,
 * an unquoted slash) is named ahead of any token the parser would have stumbled on.
 *
 * <p>When a construct is implemented, move its rows from the second test to the first.
 */
class RuleSqlCorpusTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM 'topic/subtopic'",
            "SELECT color FROM 'topic/subtopic'",
            "SELECT color AS my_color FROM 'topic/subtopic'",
            "SELECT color as my_color, temperature as fahrenheit FROM 'topic/subtopic'",
            "SELECT *, 15 as speed FROM 'topic/subtopic'",
            "SELECT color.red as red_value FROM 'topic/subtopic'",
            "SELECT color AS rgb FROM 'topic/subtopic' WHERE temperature > 50",
            "SELECT color AS my_color FROM 'topic/subtopic' WHERE temperature > 50 AND color <> 'red'",
            "SELECT foo.bar AS baz FROM 'topic/subtopic'",
            "SELECT NULL AS n FROM 'topic/subtopic'",
            "SELECT topic(3) AS device_id FROM 'devices/+/data'",
            "SELECT *, topic() as topic FROM '$aws/things/+/shadow/name/building/update/accepted' WHERE endswith(clientToken, 'inbound')",
            "SELECT *, topic(3) AS thingId FROM 'prod/fleet/+/ingest_v1'",
            "SELECT * FROM '$aws/events/presence/connected/+' WHERE startswith(clientId, 'gw-')",
            "SELECT * FROM '$aws/events/presence/disconnected/+' WHERE startswith(clientId, 'gw-')",
            "SELECT state.reported.temperature AS temp FROM '$aws/things/+/shadow/update/accepted' WHERE state.reported.temperature >= 30",
            "SELECT * FROM 'a/b' WHERE NOT active = FALSE",
            "SELECT * FROM 'a/b' WHERE (size > 10 OR weight <= 2.5) AND color = 'red'",
            "SELECT * FROM 'a/b' WHERE flag = TRUE AND level != 0",
            "SELECT * FROM 'a/b' WHERE endswith(clientToken, ':ingest:inbound') OR (endswith(clientToken, ':events:outbound') AND startswith(clientToken, 'gateway:'))",
            "SELECT * FROM 'a/#'",
            "SELECT * FROM '+'",
            "SELECT * FROM '#'",
            "SELECT * FROM 'a/+/b/+'",
            "select * from 'a/b' where x = 'y'",
            "SELECT * FROM \"topic/subtopic\" WHERE startswith(\"ranger\", \"ran\")",
            "SELECT * FROM 'topic/subtopic' WHERE startswith(topic(), 'topic') AND endswith(topic(2), 'topic')",
            "SELECT * FROM 'a/b' WHERE temperature > -10 AND temperature < 100.5",
            "SELECT * FROM 'a/b' WHERE name = 'o''brien'",
            "SELECT *\n  FROM 'topic/subtopic'\n WHERE temperature > 50\n   AND color <> 'red'"
    })
    void aStatementInsideTheSubsetParses(String sql) {
        assertNotNull(RuleSqlParser.parse(sql));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // AWS syntax outside the subset today. Each row moves up when its construct lands.
            "SELECT (temperature - 32) * 5 / 9 AS celsius, upper(color) as my_color FROM 'topic/subtopic' | -",
            "SELECT temperature * 1.8 + 32 AS f FROM 'a/b'                        | +",
            "SELECT VALUE color FROM 'topic/subtopic'                              | color",
            "SELECT {'latitude': get(lat_long, 0)} as lat_long FROM 'topic/subtopic' | {",
            "SELECT [lat,long] as lat_long FROM 'topic/subtopic'                   | [",
            "SELECT * FROM 'a/b' WHERE 3 IN arr                                    | IN",
            "SELECT * FROM 'a/b' WHERE exists (select * from arr as a where a = 3) | exists",
            "SELECT clientid() AS client FROM 'a/b'                                | clientid",
            "SELECT *, timestamp() AS ts FROM 'a/b'                                | timestamp",
            "SELECT *, accountid() AS account FROM 'a/b'                           | accountid",
            "SELECT newuuid() AS id FROM 'a/b'                                     | newuuid",
            "SELECT principal() AS p FROM 'a/b'                                    | principal",
            "SELECT * FROM 'a/b' WHERE traceid() <> ''                             | traceid",
            "SELECT * FROM 'a/b' WHERE isUndefined(x)                              | isUndefined",
            "SELECT * FROM 'a/b' WHERE isNull(x)                                   | isNull",
            "SELECT get(get(get(mydata,\"item2\"),\"0\"),\"my-key\") FROM 'iot/rules' | get",
            "SELECT temp, md5(deviceid) AS hashed_id FROM 'topic/#'               | md5",
            "SELECT encode(*, 'base64') AS data FROM 'a/b'                         | encode",
            "SELECT lower(color) AS c FROM 'a/b'                                   | lower",
            "SELECT * FROM 'a/b' WHERE length(name) > 3                            | length",
            "SELECT * FROM 'a/b' WHERE regexp_matches(x, '^a')                     | regexp_matches",
            "SELECT * FROM 'a/b' WHERE cast(x AS Int) = 1                          | cast",
            "SELECT sql_version() AS v FROM 'a/b'                                  | sql_version",
            "SELECT get_thing_shadow('t', 'arn') FROM 'a/b'                        | get_thing_shadow",
            "SELECT CASE temperature WHEN 0 THEN 'cold' ELSE 'warm' END AS label FROM 'a/b' | temperature",
            "SET t = temperature SELECT t FROM 'a/b'                               | SET",
            "SELECT foo.bar AS bar.baz FROM 'topic/subtopic'                       | .",
            "SELECT a.b[0] FROM 'a/b'                                              | [",
            "SELECT * FROM topic/subtopic                                          | /",
            "SELECT * FROM 'a/b' WHERE x = -y                                      | -",
            // Not AWS syntax either. It must fail the same way rather than silently parse.
            "SELECT * FROM 'a/b' WHERE x LIKE 'a%'                                 | LIKE",
            "SELECT * FROM 'a/b' WHERE x BETWEEN 1 AND 2                           | BETWEEN",
            "SELECT * FROM 'a/b' WHERE x IS NULL                                   | IS",
            "SELECT * FROM 'a/b' WHERE x IS NOT NULL                               | IS",
            "SELECT * FROM 'a/b' ORDER BY x                                        | ORDER",
            "SELECT * FROM 'a/b' LIMIT 1                                           | LIMIT",
            "SELECT * FROM 'a/b' GROUP BY x                                        | GROUP",
            "SELECT DISTINCT x FROM 'a/b'                                          | x",
            "SELECT * FROM 'a/b' JOIN 'c/d'                                        | JOIN",
            "SELECT * FROM 'a/b' WHERE x == 'y'                                    | =",
            "SELECT * FROM 'a/b'; DROP TABLE x                                     | ;",
            "SELECT * FROM 'a/b' -- comment                                        | -",
            "SELECT * FROM 'a/b' WHERE x && y                                      | &",
            "SELECT * FROM 'a/b' WHERE x = `y`                                     | `",
            "INSERT INTO 'a/b' VALUES (1)                                          | INSERT"
    })
    void aStatementOutsideTheSubsetFailsNamingTheFirstUnplaceableToken(String sql, String token) {
        RuleSqlParseException failure = assertThrows(RuleSqlParseException.class, () -> RuleSqlParser.parse(sql));

        assertEquals(token, failure.token(), failure.getMessage());
    }
}
