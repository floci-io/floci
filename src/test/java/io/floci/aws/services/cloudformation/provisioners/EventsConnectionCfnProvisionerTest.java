package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.eventbridge.model.Connection;
import io.floci.aws.services.eventbridge.model.ConnectionState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code AWS::Events::Connection} in the Events CFN provisioner, with only {@link EventBridgeService} mocked. */
class EventsConnectionCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String NAME = "orders-connection";
    private static final String ARN = "arn:aws:events:us-east-1:000000000000:connection/" + NAME + "/4f1c2d3e";
    private static final String SECRET_ARN =
            "arn:aws:secretsmanager:us-east-1:000000000000:secret:events!connection/" + NAME + "/4f1c2d3e";

    private final EventBridgeService events = mock(EventBridgeService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventsCfnProvisioner provisioner = new EventsCfnProvisioner(events);

    private ProvisionContext ctx(String priorPhysicalId, JsonNode priorProperties) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.omitNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId,
                event -> { }, priorProperties);
    }

    private StackResource resource(String logicalId) {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Events::Connection");
        r.setLogicalId(logicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode apiKeyAuth(String value) {
        ObjectNode apiKey = mapper.createObjectNode().put("ApiKeyName", "Authorization").put("ApiKeyValue", value);
        return mapper.createObjectNode().set("ApiKeyAuthParameters", apiKey);
    }

    private ObjectNode props(String description, String apiKeyValue) {
        ObjectNode props = mapper.createObjectNode();
        props.put("Name", NAME);
        props.put("AuthorizationType", "API_KEY");
        props.set("AuthParameters", apiKeyAuth(apiKeyValue));
        if (description != null) {
            props.put("Description", description);
        }
        return props;
    }

    private Connection stored() {
        Connection connection = new Connection();
        connection.setName(NAME);
        connection.setConnectionArn(ARN);
        connection.setSecretArn(SECRET_ARN);
        return connection;
    }

    @Test
    void createMapsThePropertiesAndExposesTheAttributes() throws Exception {
        when(events.createConnection(eq(NAME), eq("Events to orders"), eq("API_KEY"), anyString(),
                isNull(), isNull(), eq(REGION))).thenReturn(stored());

        StackResource r = resource("ApiDestinationConnection");
        provisioner.provision(r, props("Events to orders", "Bearer tok"), ctx(null, null));

        ArgumentCaptor<String> auth = ArgumentCaptor.forClass(String.class);
        verify(events).createConnection(eq(NAME), eq("Events to orders"), eq("API_KEY"), auth.capture(),
                isNull(), isNull(), eq(REGION));
        assertEquals(apiKeyAuth("Bearer tok"), mapper.readTree(auth.getValue()),
                "AuthParameters reach the service as the JSON object the API takes");
        assertEquals(NAME, r.getPhysicalId(), "Ref is the connection name");
        assertEquals(ARN, r.getAttributes().get("Arn"));
        assertEquals("arn:aws:events:us-east-1:000000000000:connection/" + NAME,
                r.getAttributes().get("ArnForPolicy"), "the policy ARN drops the trailing id");
        assertEquals(SECRET_ARN, r.getAttributes().get("SecretArn"));
    }

    @Test
    void anUnnamedConnectionGetsAGeneratedNameWithinTheLimit() {
        when(events.createConnection(anyString(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(stored());
        ObjectNode props = props(null, "tok");
        props.remove("Name");

        StackResource r = resource("ApiDestinationConnection");
        provisioner.provision(r, props, ctx(null, null));

        assertTrue(r.getPhysicalId().startsWith("my-stack-ApiDestinationConnection-"), r.getPhysicalId());
        assertTrue(r.getPhysicalId().length() <= 64, r.getPhysicalId());
        assertTrue(r.getPhysicalId().matches("[.\\-_A-Za-z0-9]+"), r.getPhysicalId());
    }

    @Test
    void anUpdateChangesTheConnectionInPlace() {
        when(events.describeConnection(NAME, REGION)).thenReturn(stored());
        when(events.updateConnection(eq(NAME), eq("new description"), eq("API_KEY"), anyString(),
                isNull(), isNull(), eq(REGION))).thenReturn(stored());

        StackResource r = resource("ApiDestinationConnection");
        ObjectNode prior = props("old description", "tok");
        provisioner.provision(r, props("new description", "rotated"), ctx(NAME, prior));

        verify(events).updateConnection(eq(NAME), eq("new description"), eq("API_KEY"), anyString(),
                isNull(), isNull(), eq(REGION));
        verify(events, never()).createConnection(any(), any(), any(), any(), any(), any(), any());
        assertEquals(NAME, r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void aDifferentNameIsAReplacementAndRefused() {
        StackResource r = resource("ApiDestinationConnection");
        ObjectNode renamed = props(null, "tok");
        renamed.put("Name", "another-name");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(r, renamed, ctx(NAME, props(null, "tok"))));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("requires resource replacement"), e.getMessage());
        verify(events, never()).createConnection(any(), any(), any(), any(), any(), any(), any());
        verify(events, never()).updateConnection(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDroppedPropertyIsRefusedBecauseEventBridgeWouldKeepIt() {
        StackResource r = resource("ApiDestinationConnection");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props(null, "tok"), ctx(NAME, props("a description", "tok"))));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("Removing Description"), e.getMessage());
        verify(events, never()).updateConnection(any(), any(), any(), any(), any(), any(), any());
    }

    private Connection priorConnection() {
        Connection connection = stored();
        connection.setDescription("old description");
        connection.setAuthorizationType("API_KEY");
        connection.setAuthParameters(apiKeyAuth("old-key").toString());
        connection.setKmsKeyIdentifier("alias/old");
        connection.setConnectionState(ConnectionState.AUTHORIZED);
        connection.setLastModifiedTime(Instant.parse("2026-11-01T10:00:00Z"));
        connection.setLastAuthorizedTime(Instant.parse("2026-11-01T10:00:05Z"));
        return connection;
    }

    private StackResource updatedConnection() {
        when(events.describeConnection(NAME, REGION)).thenReturn(priorConnection());
        when(events.updateConnection(any(), any(), any(), any(), any(), any(), any())).thenReturn(stored());
        StackResource r = resource("ApiDestinationConnection");
        provisioner.provision(r, props("new description", "rotated"), ctx(NAME, props("old description", "old-key")));
        return r;
    }

    @Test
    void aRollbackPutsBackTheConnectionTheUpdateChanged() {
        StackResource r = updatedConnection();

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<Connection> restored = ArgumentCaptor.forClass(Connection.class);
        verify(events).restoreConnection(restored.capture(), eq(REGION));
        Connection previous = restored.getValue();
        assertEquals(NAME, previous.getName());
        assertEquals("old description", previous.getDescription());
        assertEquals("API_KEY", previous.getAuthorizationType());
        assertEquals(apiKeyAuth("old-key").toString(), previous.getAuthParameters());
        assertEquals("alias/old", previous.getKmsKeyIdentifier());
        assertEquals(ConnectionState.AUTHORIZED, previous.getConnectionState());
        assertEquals(Instant.parse("2026-11-01T10:00:00Z"), previous.getLastModifiedTime());
        assertEquals(Instant.parse("2026-11-01T10:00:05Z"), previous.getLastAuthorizedTime());
        assertFalse(provisioner.rollbackUpdate(r), "the snapshot is spent once restored");
    }

    @Test
    void aRollbackKeepsAFailedRestoreForTheNextAttempt() {
        StackResource r = updatedConnection();
        doThrow(new AwsException("InternalException", "boom", 500)).when(events).restoreConnection(any(), any());

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertNotNull(r.getAttributes().get(CfnRollback.CONNECTION_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void aSnapshotLeftByAnEarlierUpdateIsReplacedByTheOneInFlight() {
        StackResource r = updatedConnection();
        Connection later = priorConnection();
        later.setDescription("description after the first update");
        when(events.describeConnection(NAME, REGION)).thenReturn(later);

        provisioner.provision(r, props("third", "rotated"), ctx(NAME, props("new description", "rotated")));
        provisioner.rollbackUpdate(r);

        ArgumentCaptor<Connection> restored = ArgumentCaptor.forClass(Connection.class);
        verify(events).restoreConnection(restored.capture(), eq(REGION));
        assertEquals("description after the first update", restored.getValue().getDescription());
    }

    @Test
    void aConnectionCreateTakesNoSnapshotToRollBack() {
        when(events.createConnection(any(), any(), any(), any(), any(), any(), any())).thenReturn(stored());
        StackResource r = resource("ApiDestinationConnection");

        provisioner.provision(r, props("Events to orders", "tok"), ctx(null, null));

        assertFalse(provisioner.rollbackUpdate(r));
        verify(events, never()).restoreConnection(any(), any());
    }

    @Test
    void deleteRemovesTheConnection() {
        provisioner.delete("AWS::Events::Connection", NAME, REGION);

        verify(events).deleteConnection(NAME, REGION);
    }

    @Test
    void deleteToleratesAConnectionThatIsAlreadyGone() {
        doThrow(new AwsException("ResourceNotFoundException", "Connection " + NAME + " does not exist.", 400))
                .when(events).deleteConnection(NAME, REGION);

        assertDoesNotThrow(() -> provisioner.delete("AWS::Events::Connection", NAME, REGION));
    }

    @Test
    void deleteLetsARealFailurePropagate() {
        doThrow(new AwsException("InternalException", "boom", 500)).when(events).deleteConnection(NAME, REGION);

        assertThrows(AwsException.class, () -> provisioner.delete("AWS::Events::Connection", NAME, REGION));
    }

    @Test
    void deleteWithoutAPhysicalIdDoesNothing() {
        provisioner.delete("AWS::Events::Connection", null, REGION);
        provisioner.delete("AWS::Events::Connection", "", REGION);

        verify(events, never()).deleteConnection(any(), any());
    }
}
