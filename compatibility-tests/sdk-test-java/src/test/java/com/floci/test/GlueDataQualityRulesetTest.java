package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.AlreadyExistsException;
import software.amazon.awssdk.services.glue.model.DataQualityRulesetListDetails;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GetDataQualityRulesetResponse;
import software.amazon.awssdk.services.glue.model.UpdateDataQualityRulesetResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Glue data quality rulesets")
class GlueDataQualityRulesetTest {

    private static final String NAME = TestFixtures.uniqueName("dq_ruleset");
    private static final String RULESET = "Rules = [ RowCount > 0, IsComplete \"id\" ]";

    private static GlueClient glue;

    @BeforeAll
    static void setup() {
        glue = TestFixtures.glueClient();
    }

    @AfterAll
    static void cleanup() {
        if (glue == null) {
            return;
        }
        try {
            glue.deleteDataQualityRuleset(r -> r.name(NAME));
        }
        catch (Exception ignored) {
            // Cleanup is best effort: the name is unique to this run and the emulator is disposable.
        }
    }

    @Test
    void sdkRoundTripsADataQualityRuleset() {
        Instant before = Instant.now().minusSeconds(60);
        assertThat(glue.createDataQualityRuleset(r -> r.name(NAME).description("orders checks").ruleset(RULESET)
                .targetTable(t -> t.tableName("orders").databaseName("sales"))
                .tags(Map.of("team", "data"))).name()).isEqualTo(NAME);
        assertThatThrownBy(() -> glue.createDataQualityRuleset(r -> r.name(NAME).ruleset(RULESET)))
                .isInstanceOf(AlreadyExistsException.class);

        GetDataQualityRulesetResponse got = glue.getDataQualityRuleset(r -> r.name(NAME));
        assertThat(got.ruleset()).isEqualTo(RULESET);
        assertThat(got.targetTable().databaseName()).isEqualTo("sales");
        assertThat(got.createdOn()).isAfter(before);
        assertThat(got.lastModifiedOn()).isEqualTo(got.createdOn());

        UpdateDataQualityRulesetResponse updated = glue.updateDataQualityRuleset(r -> r.name(NAME)
                .description("orders, nightly"));
        assertThat(updated.ruleset()).isEqualTo(RULESET);
        assertThat(updated.description()).isEqualTo("orders, nightly");

        List<DataQualityRulesetListDetails> listed = glue.listDataQualityRulesets(r -> r
                .filter(f -> f.targetTable(t -> t.tableName("orders").databaseName("sales"))
                        .createdAfter(before))
                .tags(Map.of("team", "data"))).rulesets();
        assertThat(listed).extracting(DataQualityRulesetListDetails::name).contains(NAME);
        DataQualityRulesetListDetails item = listed.stream().filter(d -> d.name().equals(NAME)).findFirst()
                .orElseThrow();
        assertThat(item.ruleCount()).isEqualTo(2);
        assertThat(item.createdOn()).isEqualTo(got.createdOn());

        glue.deleteDataQualityRuleset(r -> r.name(NAME));
        assertThatThrownBy(() -> glue.getDataQualityRuleset(r -> r.name(NAME)))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
