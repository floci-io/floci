package io.floci.aws.services.verifiedpermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;

class CedarAuthorizationEvaluatorTest {

    private static final String SCHEMA = """
            {"Demo":{"entityTypes":{"User":{}},"actions":{
              "Read":{},
              "read":{"memberOf":[{"id":"Read"},{"type":"Other::Action","id":"Audit"},{"type":"Action","id":"All"}]}
            }}}""";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CedarAuthorizationEvaluator evaluator =
            new CedarAuthorizationEvaluator(objectMapper, mock(CedarSidecarClient.class));

    @Test
    void schemaActionsBecomeEntitiesWithTheirGroupsAsParents() throws Exception {
        JsonNode out = evaluator.withSchemaActions(objectMapper.readTree("{}"), SCHEMA);

        JsonNode entities = cedarEntities(out);
        assertThat(uids(entities), containsInAnyOrder("Demo::Action/Read", "Demo::Action/read"));
        JsonNode read = entity(entities, "read");
        assertThat(uids(read.get("parents")),
                containsInAnyOrder("Demo::Action/Read", "Other::Action/Audit", "Demo::Action/All"));
        assertThat(entity(entities, "Read").get("parents").size(), equalTo(0));
    }

    @Test
    void anEntityTheCallerSentForTheSameActionIsKept() throws Exception {
        String callerEntities = "[{\"uid\":{\"type\":\"Demo::Action\",\"id\":\"read\"},\"attrs\":{},\"parents\":[]}]";
        JsonNode request = objectMapper.createObjectNode().set("entities",
                objectMapper.createObjectNode().put("cedarJson", callerEntities));

        JsonNode entities = cedarEntities(evaluator.withSchemaActions(request, SCHEMA));

        assertThat(uids(entities), containsInAnyOrder("Demo::Action/read", "Demo::Action/Read"));
        assertThat(entity(entities, "read").get("parents").size(), equalTo(0));
    }

    @Test
    void entityListInputIsConvertedAndMerged() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"entities":{"entityList":[{"identifier":{"entityType":"Demo::User","entityId":"alice"}}]}}""");

        JsonNode entities = cedarEntities(evaluator.withSchemaActions(request, SCHEMA));

        assertThat(uids(entities), containsInAnyOrder("Demo::User/alice", "Demo::Action/Read", "Demo::Action/read"));
    }

    @Test
    void aRequestIsUnchangedWithoutSchemaActions() throws Exception {
        JsonNode request = objectMapper.readTree("{\"principal\":{\"entityType\":\"Demo::User\",\"entityId\":\"alice\"}}");

        assertThat(evaluator.withSchemaActions(request, null), sameInstance(request));
        assertThat(evaluator.withSchemaActions(request, " "), sameInstance(request));
        assertThat(evaluator.withSchemaActions(request, "{not json"), sameInstance(request));
        assertThat(evaluator.withSchemaActions(request, "{\"Demo\":{\"entityTypes\":{}}}"), sameInstance(request));
    }

    @Test
    void theEmptyNamespaceUsesBareActionType() throws Exception {
        String schema = "{\"\":{\"actions\":{\"view\":{\"memberOf\":[{\"id\":\"All\"}]}}}}";

        JsonNode entities = cedarEntities(evaluator.withSchemaActions(objectMapper.readTree("{}"), schema));

        assertThat(uids(entities), containsInAnyOrder("Action/view"));
        assertThat(uids(entities.get(0).get("parents")), containsInAnyOrder("Action/All"));
    }

    private JsonNode cedarEntities(JsonNode request) throws Exception {
        return objectMapper.readTree(request.path("entities").path("cedarJson").asText());
    }

    private static JsonNode entity(JsonNode entities, String id) {
        for (JsonNode entity : entities) {
            if (id.equals(entity.path("uid").path("id").asText())) {
                return entity;
            }
        }
        throw new AssertionError("No entity with id " + id + " in " + entities);
    }

    private static List<String> uids(JsonNode nodes) {
        List<String> out = new ArrayList<>();
        nodes.forEach(node -> {
            JsonNode uid = node.has("uid") ? node.get("uid") : node;
            out.add(uid.path("type").asText() + "/" + uid.path("id").asText());
        });
        return out;
    }
}
