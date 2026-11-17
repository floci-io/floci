package io.floci.aws.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.floci.aws.testing.ParkingStore;

final class BedrockAgentCoreConcurrencyFixture {

    static final String REGION = "us-east-1";

    final ParkingStore<AgentRuntime> storage = new ParkingStore<>();
    final BedrockAgentCoreControlService service = new BedrockAgentCoreControlService(
            storage, new RegionResolver(REGION, "000000000000"));
    final String runtimeId;
    final String arn;

    BedrockAgentCoreConcurrencyFixture() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode artifact = mapper.createObjectNode();
        artifact.putObject("containerConfiguration").put("containerUri", "example.invalid/probe:1");
        ObjectNode network = mapper.createObjectNode().put("networkMode", "PUBLIC");
        AgentRuntime runtime = service.createAgentRuntime("probe", artifact, network,
                "arn:aws:iam::000000000000:role/probe", null, null, null, null, null, REGION);
        runtimeId = runtime.getAgentRuntimeId();
        arn = service.arn(runtime, REGION);
    }

    AgentRuntime delete() {
        return service.deleteAgentRuntime(runtimeId, null, REGION);
    }
}
