package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The API Gateway account CFN provisioner in isolation. */
class ApiGatewayAccountCfnProvisionerTest {

    private final ApiGatewayService apiGateway = mock(ApiGatewayService.class);
    private final ApiGatewayAccountCfnProvisioner provisioner =
            new ApiGatewayAccountCfnProvisioner(apiGateway);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("ApiAccount");
        r.setResourceType("AWS::ApiGateway::Account");
        r.setAttributes(new HashMap<>());
        return r;
    }

    @Test
    void cloudWatchRoleArnIsPatchedIntoTheAccount() {
        StackResource r = resource();
        String arn = "arn:aws:iam::000000000000:role/apigw-cw";

        provisioner.provision(r, mapper.createObjectNode().put("CloudWatchRoleArn", arn), ctx());

        verify(apiGateway).updateAccount("us-east-1", List.of(Map.of(
                "op", "replace", "path", "/cloudwatchRoleArn", "value", arn)));
        assertEquals("ApiAccount", r.getPhysicalId());
        assertEquals("ApiAccount", r.getAttributes().get("Id"));
    }

    @Test
    void accountWithoutRoleArnTouchesNothing() {
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        assertEquals("ApiAccount", r.getPhysicalId());
        verifyNoInteractions(apiGateway);
    }

    @Test
    void deleteLeavesAccountSettingsAlone() {
        provisioner.delete("AWS::ApiGateway::Account", "ApiAccount", "us-east-1");
        verifyNoInteractions(apiGateway);
    }
}
