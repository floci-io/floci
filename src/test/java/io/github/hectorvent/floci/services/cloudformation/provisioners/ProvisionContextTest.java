package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The shared helpers on {@link ProvisionContext} that extracted provisioners build on. */
class ProvisionContextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        // The engine resolves intrinsics; these tests are about what ProvisionContext asks it to
        // resolve, so it passes values through the way it would with no intrinsics present.
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
    }

    private ProvisionContext context(String priorPhysicalId) {
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void isUpdateReflectsWhetherAPriorPhysicalIdExists() {
        assertFalse(context(null).isUpdate(), "a first-time create has no prior physical id");
        assertFalse(context("  ").isUpdate(), "a blank id is not a prior provision");
        assertTrue(context("my-stack-Queue-abc123").isUpdate());
    }

    /**
     * The four-argument form is what the existing construction sites use, so it must keep meaning
     * "create".
     */
    @Test
    void theCreateOnlyConstructorIsNotAnUpdate() {
        ProvisionContext ctx = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");

        assertFalse(ctx.isUpdate());
        assertNull(ctx.priorPhysicalId());
    }

    @Test
    void resolveTagsKeepsTemplateOrderAndDefaultsAMissingValue() {
        Map<String, String> tags = context(null).resolveTags(
                props("""
                        {
                          "Tags": [
                            {"Key": "zeta", "Value": "z"},
                            {"Key": "alpha", "Value": "a"},
                            {"Key": "novalue"}
                          ]
                        }
                        """),
                "Tags");

        assertEquals(List.of("zeta", "alpha", "novalue"), List.copyOf(tags.keySet()),
                "tag order follows the template, not hash order");
        assertEquals("z", tags.get("zeta"));
        assertEquals("", tags.get("novalue"), "a missing Value becomes empty, not null");
    }

    @Test
    void resolveTagsSkipsBlankKeysAndNeverReturnsNull() {
        Map<String, String> blankKeys = context(null).resolveTags(
                props("""
                        {
                          "Tags": [
                            {"Key": "", "Value": "v"},
                            {"Key": "  ", "Value": "v"},
                            {"Key": "ok", "Value": "v"}
                          ]
                        }
                        """),
                "Tags");
        assertEquals(Map.of("ok", "v"), blankKeys);

        assertEquals(Map.of(), context(null).resolveTags(props("{}"), "Tags"),
                "an absent property is an empty map");
        assertEquals(Map.of(), context(null).resolveTags(props("""
                {"Tags": "not-a-list"}
                """), "Tags"),
                "a non-array property is an empty map");
        assertEquals(Map.of(), context(null).resolveTags(null, "Tags"));
    }

    /**
     * The whole property is handed to the engine, which is what lets an intrinsic wrapping the list
     * work. Resolving only each entry would see an unresolved {@code Fn::If} object and yield
     * nothing.
     */
    @Test
    void resolveTagsResolvesAnIntrinsicWrappingTheWholeList() {
        JsonNode wrapped = props("""
                {
                  "Tags": {
                    "Fn::If": [
                      "isProd",
                      [{"Key": "env", "Value": "prod"}],
                      [{"Key": "env", "Value": "dev"}]
                    ]
                  }
                }
                """);
        JsonNode chosenBranch = props("""
                [{"Key": "env", "Value": "prod"}]
                """);
        when(engine.resolveNode(wrapped.get("Tags"))).thenReturn(chosenBranch);

        assertEquals(Map.of("env", "prod"), context(null).resolveTags(wrapped, "Tags"));
    }
}
