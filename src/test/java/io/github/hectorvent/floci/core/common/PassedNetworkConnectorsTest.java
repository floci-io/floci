package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The vendored table of where each operation's request names the network connectors it passes. */
class PassedNetworkConnectorsTest {

    private static final String CONNECTOR = "arn:aws:lambda:us-east-1:111122223333:network-connector:nc-1";
    private static final String MANAGED = "arn:aws:lambda:us-west-2:aws:network-connector:aws-network-connector:";

    @Test
    void theTableReadsTheLambdaOperationsAndLeavesOutTheUnreadOnes() {
        assertEquals(Set.of("lambda:CreateMicrovmImage", "lambda:CreateWebFunction", "lambda:CreateWebFunctionRevision",
                "lambda:RunMicrovm", "lambda:UpdateMicrovmImage"), PassedNetworkConnectors.table().keySet());
    }

    @Test
    void aWebFunctionPassesTheConnectorItsRevisionNames() {
        assertEquals(List.of(CONNECTOR + ":1"), PassedNetworkConnectors.of("lambda:CreateWebFunctionRevision",
                json("{\"serviceConfig\":{\"egressNetworkConnectorArn\":\"" + CONNECTOR + ":1\"}}"), "us-west-2"));
        assertEquals(List.of(CONNECTOR + ":2"), PassedNetworkConnectors.of("lambda:CreateWebFunction",
                json("{\"revisionConfig\":{\"serviceConfig\":{\"egressNetworkConnectorArn\":\"" + CONNECTOR + ":2\"}}}"),
                "us-west-2"));
        assertEquals(List.of(), PassedNetworkConnectors.of("lambda:CreateWebFunctionRevision",
                json("{\"serviceConfig\":{\"executionRoleArn\":\"arn:aws:iam::111122223333:role/r\"}}"), "us-west-2"));
    }

    @Test
    void aMicrovmPassesItsEgressConnectorsThenItsIngressOnes() {
        assertEquals(List.of(CONNECTOR + ":1", CONNECTOR + ":3", CONNECTOR + ":2"), PassedNetworkConnectors.of(
                "lambda:RunMicrovm", json("{\"ingressNetworkConnectors\":[\"" + CONNECTOR + ":2\"],"
                        + "\"egressNetworkConnectors\":[\"" + CONNECTOR + ":1\",\"" + CONNECTOR + ":3\"]}"), "us-west-2"));
    }

    @Test
    void aMicrovmThatNamesNoConnectorPassesTheManagedOnes() {
        assertEquals(List.of(MANAGED + "INTERNET_EGRESS", MANAGED + "HTTP_INGRESS"),
                PassedNetworkConnectors.of("lambda:RunMicrovm", json("{\"imageIdentifier\":\"i\"}"), "us-west-2"));
        assertEquals(List.of(MANAGED + "INTERNET_EGRESS", CONNECTOR + ":2"), PassedNetworkConnectors.of(
                "lambda:RunMicrovm", json("{\"egressNetworkConnectors\":[],\"ingressNetworkConnectors\":[\""
                        + CONNECTOR + ":2\"]}"), "us-west-2"));
        assertEquals(List.of(MANAGED + "INTERNET_EGRESS"),
                PassedNetworkConnectors.of("lambda:CreateMicrovmImage", json("{\"name\":\"n\"}"), "us-west-2"));
        assertEquals(List.of("arn:aws-cn:lambda:cn-north-1:aws:network-connector:aws-network-connector:INTERNET_EGRESS"),
                PassedNetworkConnectors.of("lambda:UpdateMicrovmImage", json("{}"), "cn-north-1"));
    }

    @Test
    void anOperationTheTableDoesNotReadPassesNothing() {
        assertEquals(List.of(), PassedNetworkConnectors.of("appsync:CreateDataSource", json("{}"), "us-west-2"));
        assertEquals(List.of(), PassedNetworkConnectors.of("lambda:CreateFunction", json("{}"), "us-west-2"));
    }

    @Test
    void aMalformedTableFailsToLoad() {
        assertThrows(IllegalStateException.class, () -> PassedNetworkConnectors.parse(stream("{\"operations\":{}}")));
        assertThrows(IllegalStateException.class, () -> PassedNetworkConnectors.parse(stream(
                "{\"operations\":{\"lambda:RunMicrovm\":{\"connectors\":[{\"member\":\"a..b\"}]}}}")));
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ContainerRequestContext json(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
        when(request.getEntityStream()).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        return request;
    }
}
