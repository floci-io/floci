package io.floci.aws.services.ssm;

import io.floci.aws.core.common.AwsArnUtils.Arn;
import io.floci.aws.services.ssmmessages.SsmMessageGateway;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.Node;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Session;
import software.amazon.awssdk.services.ssm.model.StartSessionResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class SsmSessionPermissiveIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String TARGET = "i-fedcba98765432100";

    @InjectMock SsmMessageGateway gateway;

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"us-east-1, aws", "us-gov-west-1, aws-us-gov", "cn-north-1, aws-cn"})
    void arbitraryDummyKeyUsesPartitionRootOwner(String region, String partition) {
        connectGateway(ACCOUNT, region);
        try (SsmClient caller = AwsTestClients.create(SsmClient.builder(), "local", Region.of(region), endpoint)) {
            StartSessionResponse response = caller.startSession(r -> r.target(TARGET));
            try {
                assertOwner(caller, response.sessionId(), Arn.global(partition, "iam", ACCOUNT, "root").toString(), "root");
                assertEquals("initial-token", response.tokenValue());
            } finally {
                caller.terminateSession(r -> r.sessionId(response.sessionId()));
            }
        }
    }

    @Test
    void unsignedCallerUsesDefaultAccountRootOwner() {
        connectGateway(ACCOUNT, "us-east-1");
        Response response = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonSSM.StartSession")
                .body(("{\"Target\":\"" + TARGET + "\"}").getBytes(StandardCharsets.UTF_8)).post(endpoint);
        String sessionId = response.jsonPath().getString("SessionId");
        try (SsmClient admin = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1, endpoint)) {
            try {
                assertEquals(200, response.statusCode(), response.asString());
                assertOwner(admin, sessionId, Arn.global("aws", "iam", ACCOUNT, "root").toString(), "root");
                assertEquals("initial-token", response.jsonPath().getString("TokenValue"));
            } finally {
                if (sessionId != null) {
                    admin.terminateSession(r -> r.sessionId(sessionId));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "123456789012"})
    void recognisedRootKeyRetainsRootOwnership(String key) {
        String account = "test".equals(key) ? ACCOUNT : key;
        connectGateway(account, "us-east-1");
        try (SsmClient caller = AwsTestClients.create(SsmClient.builder(), key, Region.US_EAST_1, endpoint)) {
            StartSessionResponse response = caller.startSession(r -> r.target(TARGET));
            try {
                assertOwner(caller, response.sessionId(), Arn.global("aws", "iam", account, "root").toString(), "root");
            } finally {
                caller.terminateSession(r -> r.sessionId(response.sessionId()));
            }
        }
    }

    @Test
    void iamUserRetainsUserOwnership() throws Exception {
        connectGateway(ACCOUNT, "us-east-1");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-owner-");
                SsmClient caller = AwsTestClients.create(SsmClient.builder(), user.accessKeyId(),
                        user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            StartSessionResponse response = caller.startSession(r -> r.target(TARGET));
            try {
                assertOwner(caller, response.sessionId(),
                        Arn.global("aws", "iam", ACCOUNT, "user/" + user.name()).toString(), user.name());
            } finally {
                caller.terminateSession(r -> r.sessionId(response.sessionId()));
            }
        }
    }

    @Test
    void rootOwnedSessionIsNotVisibleInAnotherAccount() {
        connectGateway("123456789012", "us-east-1");
        try (SsmClient owner = AwsTestClients.create(SsmClient.builder(), "123456789012", Region.US_EAST_1, endpoint);
                SsmClient other = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1, endpoint)) {
            StartSessionResponse response = owner.startSession(r -> r.target(TARGET));
            try {
                assertEquals(1, owner.describeSessions(r -> r.state("Active")
                        .filters(f -> f.key("SessionId").value(response.sessionId()))).sessions().size());
                assertTrue(other.describeSessions(r -> r.state("Active")
                        .filters(f -> f.key("SessionId").value(response.sessionId()))).sessions().isEmpty());
            } finally {
                owner.terminateSession(r -> r.sessionId(response.sessionId()));
            }
        }
    }

    private void connectGateway(String account, String region) {
        when(gateway.isConnected(new Node(account, region, TARGET))).thenReturn(true);
        when(gateway.startSession(any(), any())).thenReturn("initial-token");
    }

    private void assertOwner(SsmClient caller, String sessionId, String owner, String prefix) {
        List<Session> sessions = caller.describeSessions(r -> r.state("Active")
                .filters(f -> f.key("SessionId").value(sessionId))).sessions();
        assertEquals(1, sessions.size());
        assertEquals(owner, sessions.getFirst().owner());
        assertTrue(sessionId.startsWith(prefix + "-"), sessionId);
    }
}
