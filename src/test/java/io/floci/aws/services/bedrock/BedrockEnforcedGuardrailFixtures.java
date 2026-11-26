package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.bedrock.model.Guardrail;

final class BedrockEnforcedGuardrailFixtures {

    private BedrockEnforcedGuardrailFixtures() {
    }

    static Guardrail publish(BedrockService service, String region, ObjectNode request) {
        Guardrail draft = service.createGuardrail(request, region);
        return service.createGuardrailVersion(draft.getGuardrailId(), null, region);
    }

    static ObjectNode guardrailRequest(ObjectMapper mapper, String name, String inputMessage, String outputMessage) {
        return mapper.createObjectNode().put("name", name).put("blockedInputMessaging", inputMessage)
                .put("blockedOutputsMessaging", outputMessage);
    }

    static ObjectNode request(ObjectMapper mapper, String identifier, String version) {
        ObjectNode request = mapper.createObjectNode();
        request.putObject("guardrailInferenceConfig").put("guardrailIdentifier", identifier).put("guardrailVersion", version);
        return request;
    }

    static ObjectNode selectiveRequest(ObjectMapper mapper, String identifier, String version) {
        ObjectNode request = request(mapper, identifier, version);
        ObjectNode config = (ObjectNode) request.get("guardrailInferenceConfig");
        config.putObject("selectiveContentGuarding").put("system", "SELECTIVE");
        ObjectNode enforcement = config.putObject("modelEnforcement");
        enforcement.putArray("includedModels").add("ALL");
        enforcement.putArray("excludedModels");
        return request;
    }
}
