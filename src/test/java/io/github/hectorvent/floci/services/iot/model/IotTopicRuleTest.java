package io.github.hectorvent.floci.services.iot.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iot.rules.RuleSql;
import io.github.hectorvent.floci.services.iot.rules.RuleSqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class IotTopicRuleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void theParsedStatementIsNeverPersisted() throws Exception {
        IotTopicRule rule = new IotTopicRule();
        rule.setRuleName("persistenceRule");
        rule.setSql("SELECT * FROM 'a/b'");
        rule.setCompiledSql(RuleSql.Compilation.of(RuleSqlParser.parse(rule.getSql())));

        String json = objectMapper.writeValueAsString(rule);

        assertFalse(json.contains("compiledSql"), json);
        assertNull(objectMapper.readValue(json, IotTopicRule.class).getCompiledSql());
    }

    @Test
    void changingTheSqlDropsTheParsedStatement() {
        IotTopicRule rule = new IotTopicRule();
        rule.setSql("SELECT * FROM 'a/b'");
        rule.setCompiledSql(RuleSql.Compilation.of(RuleSqlParser.parse(rule.getSql())));
        assertNotNull(rule.getCompiledSql());

        rule.setSql("SELECT * FROM 'c/d'");

        assertNull(rule.getCompiledSql());
    }
}
