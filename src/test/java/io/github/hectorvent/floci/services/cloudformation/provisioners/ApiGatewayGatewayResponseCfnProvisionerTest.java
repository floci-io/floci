package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.GatewayResponse;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The API Gateway gateway response CFN provisioner in isolation, against a mocked {@link ApiGatewayService}. */
class ApiGatewayGatewayResponseCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String TYPE = "AWS::ApiGateway::GatewayResponse";

    private final ApiGatewayService apiGateway = mock(ApiGatewayService.class);
    private final ApiGatewayGatewayResponseCfnProvisioner provisioner =
            new ApiGatewayGatewayResponseCfnProvisioner(apiGateway);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String priorPhysicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("Cors4xx");
        r.setResourceType(TYPE);
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode props(String restApiId, String responseType, String statusCode) {
        ObjectNode props = mapper.createObjectNode();
        if (restApiId != null) {
            props.put("RestApiId", restApiId);
        }
        if (responseType != null) {
            props.put("ResponseType", responseType);
        }
        if (statusCode != null) {
            props.put("StatusCode", statusCode);
        }
        props.putObject("ResponseParameters")
                .put("gatewayresponse.header.Access-Control-Allow-Origin", "'*'")
                .put("gatewayresponse.header.Access-Control-Allow-Headers", "'*'");
        props.putObject("ResponseTemplates").put("application/json", "{\"message\":$context.error.messageString}");
        return props;
    }

    @Test
    void createPutsTheGatewayResponseAndRecordsTheCompositeId() {
        when(apiGateway.putGatewayResponse(eq(REGION), eq("api1"), eq("DEFAULT_4XX"), anyMap()))
                .thenReturn(new GatewayResponse());
        StackResource r = resource(null);

        provisioner.provision(r, props("api1", "DEFAULT_4XX", "400"), ctx());

        assertEquals("api1-DEFAULT_4XX", r.getPhysicalId());
        assertEquals("api1-DEFAULT_4XX", r.getAttributes().get("Id"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).putGatewayResponse(eq(REGION), eq("api1"), eq("DEFAULT_4XX"), request.capture());
        assertEquals("400", request.getValue().get("statusCode"));
        assertEquals(Map.of(
                "gatewayresponse.header.Access-Control-Allow-Origin", "'*'",
                "gatewayresponse.header.Access-Control-Allow-Headers", "'*'"),
                request.getValue().get("responseParameters"));
        assertEquals(Map.of("application/json", "{\"message\":$context.error.messageString}"),
                request.getValue().get("responseTemplates"));
    }

    @Test
    void anOmittedStatusCodeIsNotSent() {
        provisioner.provision(resource(null), props("api1", "DEFAULT_4XX", null), ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).putGatewayResponse(eq(REGION), eq("api1"), eq("DEFAULT_4XX"), request.capture());
        assertFalse(request.getValue().containsKey("statusCode"));
    }

    @Test
    void missingRequiredPropertiesAreRefused() {
        AwsException noApi = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(null), props(null, "DEFAULT_4XX", null), ctx()));
        assertEquals("ValidationError", noApi.getErrorCode());

        AwsException noType = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(null), props("api1", null, null), ctx()));
        assertEquals("ValidationError", noType.getErrorCode());
        verify(apiGateway, never()).putGatewayResponse(any(), any(), any(), anyMap());
    }

    @Test
    void updateRePutsUnderTheSameId() {
        StackResource r = resource("api1-DEFAULT_4XX");

        provisioner.provision(r, props("api1", "DEFAULT_4XX", "404"), ctx("api1-DEFAULT_4XX"));

        assertEquals("api1-DEFAULT_4XX", r.getPhysicalId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).putGatewayResponse(eq(REGION), eq("api1"), eq("DEFAULT_4XX"), request.capture());
        assertEquals("404", request.getValue().get("statusCode"));
    }

    @Test
    void changingTheApiOrTheTypeIsRefusedAsReplacementWorthy() {
        AwsException apiChanged = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("api1-DEFAULT_4XX"), props("api2", "DEFAULT_4XX", null), ctx("api1-DEFAULT_4XX")));
        assertTrue(apiChanged.getMessage().contains("RestApiId"));

        AwsException typeChanged = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("api1-DEFAULT_4XX"), props("api1", "DEFAULT_5XX", null), ctx("api1-DEFAULT_4XX")));
        assertTrue(typeChanged.getMessage().contains("ResponseType"));
        verify(apiGateway, never()).putGatewayResponse(any(), any(), any(), anyMap());
    }

    @Test
    void deleteDelegatesToTheService() {
        provisioner.delete(TYPE, "api1-MISSING_AUTHENTICATION_TOKEN", REGION);

        verify(apiGateway).deleteGatewayResponse(REGION, "api1", "MISSING_AUTHENTICATION_TOKEN");
    }

    @Test
    void deleteToleratesAResponseThatIsAlreadyGone() {
        doThrow(new AwsException("NotFoundException", "Gateway response not found", 404))
                .when(apiGateway).deleteGatewayResponse(REGION, "api1", "DEFAULT_4XX");

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "api1-DEFAULT_4XX", REGION));
    }

    @Test
    void deleteLeavesAnUnparseableIdAlone() {
        assertDoesNotThrow(() -> provisioner.delete(TYPE, "no-separator-here-", REGION));
        assertDoesNotThrow(() -> provisioner.delete(TYPE, "", REGION));
        verify(apiGateway, never()).deleteGatewayResponse(any(), any(), any());
    }
}
