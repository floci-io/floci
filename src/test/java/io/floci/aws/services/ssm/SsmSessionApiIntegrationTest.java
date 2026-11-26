package io.floci.aws.services.ssm;

import io.floci.aws.core.common.AwsArnUtils.Arn;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ssmmessages.SsmMessageGateway;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.Node;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionRequest;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionState;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DocumentType;
import software.amazon.awssdk.services.ssm.model.GetConnectionStatusResponse;
import software.amazon.awssdk.services.ssm.model.ResumeSessionResponse;
import software.amazon.awssdk.services.ssm.model.Session;
import software.amazon.awssdk.services.ssm.model.SessionFilter;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.ssm.model.StartSessionResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SsmSessionApiIntegrationTest {
    private static final String MISSING_TARGET = "i-0123456789abcdef0";
    private static final String MISSING_SESSION = "session-test-0123456789abcdef0";
    private static final String CONNECTED_TARGET = "i-fedcba98765432100";
    private static final Node CONNECTED_NODE = new Node("000000000000", "us-east-1", CONNECTED_TARGET);

    @InjectMock SsmMessageGateway gateway;

    @Inject SsmService documents;

    @TestHTTPResource("/")
    URI endpoint;

    private SsmClient ssm;

    @BeforeEach
    void connect() {
        ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1);
    }

    @AfterEach
    void disconnect() {
        ssm.close();
    }

    @Test
    void strictAuthenticationRejectsUnknownSessionCaller() {
        String target = "i-11111111111111111";
        when(gateway.isConnected(new Node("000000000000", "us-east-1", target))).thenReturn(true);
        try (SsmClient caller = AwsTestClients.create(SsmClient.builder(), "local", Region.US_EAST_1, endpoint)) {
            SsmException error = assertThrows(SsmException.class,
                    () -> caller.startSession(r -> r.target(target)));
            assertEquals(403, error.statusCode());
            assertEquals("UnrecognizedClientException", error.awsErrorDetails().errorCode());
            assertNoSessionsForTarget(target);
        }
    }

    @Test
    void strictAuthenticationRejectsUnsignedSessionCaller() {
        String target = "i-22222222222222222";
        when(gateway.isConnected(new Node("000000000000", "us-east-1", target))).thenReturn(true);
        RestAssured.given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonSSM.StartSession")
                .body(("{\"Target\":\"" + target + "\"}").getBytes(StandardCharsets.UTF_8)).post(endpoint)
                .then().statusCode(403)
                .body(containsString("MissingAuthenticationTokenException"));
        assertNoSessionsForTarget(target);
    }

    private void assertNoSessionsForTarget(String target) {
        assertTrue(ssm.describeSessions(r -> r.state("Active")
                .filters(f -> f.key("Target").value(target))).sessions().isEmpty());
        assertTrue(ssm.describeSessions(r -> r.state("History")
                .filters(f -> f.key("Target").value(target))).sessions().isEmpty());
    }

    @Test
    void missingTargetIsNotConnected() {
        GetConnectionStatusResponse status = ssm.getConnectionStatus(r -> r.target(MISSING_TARGET));
        assertEquals(MISSING_TARGET, status.target());
        assertEquals("notconnected", status.statusAsString());
    }

    @Test
    void connectionStatusRequiresPermissionOnTheRequestedInstance() throws Exception {
        when(gateway.isConnected(CONNECTED_NODE)).thenReturn(true);
        String arn = Arn.of("ec2", "us-east-1", "000000000000", "instance/" + CONNECTED_TARGET).toString();
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ssm:GetConnectionStatus","Resource":"%s"}
                ]}
                """.formatted(arn);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-status-", "status", policy);
                SsmClient caller = AwsTestClients.create(SsmClient.builder(), user.accessKeyId(),
                        user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            assertEquals("connected", caller.getConnectionStatus(r -> r.target(CONNECTED_TARGET)).statusAsString());
            SsmException denied = assertThrows(SsmException.class,
                    () -> caller.getConnectionStatus(r -> r.target(MISSING_TARGET)));
            assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mi-0123456789abcdef0", "ecs:cluster_container_runtime"})
    void unsupportedTargetCannotStartASession(String target) {
        when(gateway.isConnected(new Node("000000000000", "us-east-1", target))).thenReturn(true);
        SsmException error = assertThrows(SsmException.class, () -> ssm.startSession(r -> r.target(target)));
        assertEquals("TargetNotConnected", error.awsErrorDetails().errorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"i-01234567", "i-0123456789abcdef0"})
    void iamResolvesTheTargetInTheRequestAccountAndPartition(String target) {
        RequestScopes.runAs("111122223333", "us-gov-west-1", () -> assertEquals(
                List.of("arn:aws-us-gov:ec2:us-gov-west-1:111122223333:instance/" + target),
                documents.through("Target", target, "us-gov-west-1")));
    }

    @Test
    void iamResolvesTheImplicitDocumentWithoutStorage() {
        String region = "cn-north-1";
        String name = "SSM-SessionManagerRunShell";
        RequestScopes.runAs("111122223333", region, () -> {
            assertTrue(documents.findDocument(name, region).isEmpty());
            assertEquals(Optional.of("arn:aws-cn:ssm:cn-north-1:111122223333:document/" + name),
                    documents.findDefault("document", region));
        });
    }

    @Test
    void cannotStartSessionForMissingTarget() {
        SsmException error = assertThrows(SsmException.class,
                () -> ssm.startSession(r -> r.target(MISSING_TARGET)));
        assertEquals("TargetNotConnected", error.awsErrorDetails().errorCode());
    }

    @Test
    void unreachableTargetIsReportedBeforeMissingDocument() {
        SsmException error = assertThrows(SsmException.class,
                () -> ssm.startSession(r -> r.target(MISSING_TARGET).documentName("missing-document")));
        assertEquals("TargetNotConnected", error.awsErrorDetails().errorCode());
    }

    @Test
    void cannotResumeUnknownSession() {
        SsmException error = assertThrows(SsmException.class,
                () -> ssm.resumeSession(r -> r.sessionId(MISSING_SESSION)));
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
    }

    @Test
    void cannotTerminateUnknownSession() {
        SsmException error = assertThrows(SsmException.class,
                () -> ssm.terminateSession(r -> r.sessionId(MISSING_SESSION)));
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
    }

    @Test
    void unknownSessionHasNeitherActiveNorHistoricalEntry() {
        SessionFilter filter = SessionFilter.builder().key("SessionId").value(MISSING_SESSION).build();
        assertTrue(ssm.describeSessions(r -> r.state("Active").filters(filter)).sessions().isEmpty());
        assertTrue(ssm.describeSessions(r -> r.state("History").filters(filter)).sessions().isEmpty());
    }

    @Test
    void startReturnsReachableChannelAndPublishesActiveMetadata() {
        connectGateway();
        StartSessionResponse response = ssm.startSession(r -> r.target(CONNECTED_TARGET).reason("API integration"));
        try {
            URI url = URI.create(response.streamUrl());
            assertEquals("ws", url.getScheme());
            assertEquals(RestAssured.port, url.getPort());
            assertEquals("/v1/data-channel/" + response.sessionId(), url.getPath());
            assertEquals("role=publish_subscribe", url.getQuery());
            assertEquals("initial-token", response.tokenValue());
            Session session = sessions("Active", response.sessionId()).getFirst();
            assertEquals(CONNECTED_TARGET, session.target());
            assertEquals("Connected", session.statusAsString());
            assertEquals("API integration", session.reason());
            assertNotNull(session.startDate());
        } finally {
            ssm.terminateSession(r -> r.sessionId(response.sessionId()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startSessionGrantRequiresPermissionOnTheSelectedDocument(boolean useCustomDocument)
            throws Exception {
        connectGateway();
        String customDocument = "session-iam-" + UUID.randomUUID();
        ssm.createDocument(r -> r.name(customDocument).documentType(DocumentType.SESSION)
                .content("""
                        {"schemaVersion":"1.0","sessionType":"Standard_Stream"}
                        """));
        String permittedDocument = useCustomDocument ? customDocument : "SSM-SessionManagerRunShell";
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ssm:StartSession","Resource":"arn:aws:ec2:us-east-1:000000000000:instance/%s"},
                  {"Effect":"Allow","Action":"ssm:StartSession","Resource":"arn:aws:ssm:us-east-1:000000000000:document/%s"}
                ]}
                """.formatted(CONNECTED_TARGET, permittedDocument);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-session-", "session", policy);
                SsmClient caller = AwsTestClients.create(SsmClient.builder(), user.accessKeyId(),
                        user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            StartSessionResponse session = caller.startSession(r -> r.target(CONNECTED_TARGET)
                    .documentName(useCustomDocument ? customDocument : null));
            try {
                assertEquals("initial-token", session.tokenValue());
            } finally {
                ssm.terminateSession(r -> r.sessionId(session.sessionId()));
            }
            SsmException denied = assertThrows(SsmException.class, () -> caller.startSession(r -> r
                    .target(CONNECTED_TARGET).documentName(useCustomDocument ? null : customDocument)));
            assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
        } finally {
            ssm.deleteDocument(r -> r.name(customDocument));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "AWS-StartSSHSession, us-east-1, false",
            "AWS-StartSSHSession, us-east-1, true",
            "AWS-StartPortForwardingSession, us-east-1, false",
            "AWS-StartPortForwardingSession, us-east-1, true",
            "AWS-StartPortForwardingSessionToRemoteHost, us-east-1, false",
            "AWS-StartPortForwardingSessionToRemoteHost, us-east-1, true",
            "AWS-StartPortForwardingSession, us-gov-west-1, false",
            "AWS-StartPortForwardingSession, us-gov-west-1, true"
    })
    void awsOwnedSessionDocumentAllowsExactEmptyAccountGrant(String name, String regionName, boolean useArn)
            throws Exception {
        Region region = Region.of(regionName);
        connectGateway();
        when(gateway.isConnected(new Node("000000000000", regionName, CONNECTED_TARGET))).thenReturn(true);
        String documentArn = Arn.of("ssm", regionName, "", "document/" + name).toString();
        Map<String, List<String>> parameters = name.endsWith("ToRemoteHost")
                ? Map.of("host", List.of("localhost")) : Map.of();
        String policy = startSessionPolicy(regionName, documentArn);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-builtin-", "session", policy);
                SsmClient caller = AwsTestClients.create(SsmClient.builder(), user.accessKeyId(),
                        user.secretAccessKey(), region, endpoint);
                SsmClient admin = AwsTestClients.create(SsmClient.builder(), "test", region, endpoint)) {
            StartSessionResponse session = caller.startSession(r -> r.target(CONNECTED_TARGET)
                    .documentName(useArn ? documentArn : name).parameters(parameters));
            try {
                assertEquals("initial-token", session.tokenValue());
                assertNotNull(session.sessionId());
            } finally {
                admin.terminateSession(r -> r.sessionId(session.sessionId()));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "AWS-StartSSHSession, us-east-1, false",
            "AWS-StartSSHSession, us-east-1, true",
            "AWS-StartPortForwardingSession, us-east-1, false",
            "AWS-StartPortForwardingSession, us-east-1, true",
            "AWS-StartPortForwardingSessionToRemoteHost, us-east-1, false",
            "AWS-StartPortForwardingSessionToRemoteHost, us-east-1, true",
            "AWS-StartPortForwardingSession, us-gov-west-1, false",
            "AWS-StartPortForwardingSession, us-gov-west-1, true"
    })
    void awsOwnedSessionDocumentRejectsCallerAccountGrant(String name, String regionName, boolean useArn)
            throws Exception {
        Region region = Region.of(regionName);
        connectGateway();
        when(gateway.isConnected(new Node("000000000000", regionName, CONNECTED_TARGET))).thenReturn(true);
        String documentArn = Arn.of("ssm", regionName, "", "document/" + name).toString();
        Map<String, List<String>> parameters = name.endsWith("ToRemoteHost")
                ? Map.of("host", List.of("localhost")) : Map.of();
        String wrongOwnerArn = Arn.of("ssm", regionName, "000000000000", "document/" + name).toString();
        String policy = startSessionPolicy(regionName, wrongOwnerArn);
        AtomicReference<StartSessionResponse> started = new AtomicReference<>();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-builtin-", "session", policy);
                SsmClient caller = AwsTestClients.create(SsmClient.builder(), user.accessKeyId(),
                        user.secretAccessKey(), region, endpoint);
                SsmClient admin = AwsTestClients.create(SsmClient.builder(), "test", region, endpoint)) {
            try {
                SsmException denied = assertThrows(SsmException.class, () -> started.set(caller.startSession(r -> r
                        .target(CONNECTED_TARGET).documentName(useArn ? documentArn : name).parameters(parameters))));
                assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
            } finally {
                if (started.get() != null) {
                    admin.terminateSession(r -> r.sessionId(started.get().sessionId()));
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void accountOwnedSessionDocumentAllowsExactCallerAccountGrant(boolean useDefaultDocument, boolean useArn)
            throws Exception {
        connectGateway();
        String name = useDefaultDocument ? "SSM-SessionManagerRunShell" : "session-owned-" + UUID.randomUUID();
        ssm.createDocument(r -> r.name(name).documentType(DocumentType.SESSION)
                .content("""
                        {"schemaVersion":"1.0","sessionType":"Standard_Stream"}
                        """));
        String documentArn = Arn.of("ssm", "us-east-1", "000000000000", "document/" + name).toString();
        String policy = startSessionPolicy("us-east-1", documentArn);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-owned-", "session", policy);
                SsmClient caller = AwsTestClients.create(SsmClient.builder(), user.accessKeyId(),
                        user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            StartSessionResponse session = caller.startSession(r -> r.target(CONNECTED_TARGET)
                    .documentName(useArn ? documentArn : name));
            try {
                assertEquals("initial-token", session.tokenValue());
            } finally {
                ssm.terminateSession(r -> r.sessionId(session.sessionId()));
            }
        } finally {
            ssm.deleteDocument(r -> r.name(name));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "false, false, Bool, false", "false, true, Bool, false",
            "true, false, Bool, false", "true, true, Bool, false",
            "false, false, Null, true", "false, true, Null, true",
            "true, false, Null, true", "true, true, Null, true"
    })
    void assumedRoleSessionDocumentConditionKeyIsAbsent(boolean customDocument, boolean conditionOnDocument,
                                                       String operator, boolean allowed) {
        connectGateway();
        String name = "session-condition-" + UUID.randomUUID();
        String role = "session-condition-" + UUID.randomUUID();
        String targetArn = Arn.of("ec2", "us-east-1", "000000000000", "instance/" + CONNECTED_TARGET).toString();
        String documentArn = Arn.of("ssm", "us-east-1", "000000000000", "document/"
                + (customDocument ? name : "SSM-SessionManagerRunShell")).toString();
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ssm:StartSession","Resource":"%s"},
                  {"Effect":"Allow","Action":"ssm:StartSession","Resource":"%s",
                   "Condition":{"%s":{"ssm:SessionDocumentAccessCheck":"true"}}}
                ]}
                """.formatted(conditionOnDocument ? targetArn : documentArn,
                        conditionOnDocument ? documentArn : targetArn, operator);
        ssm.createDocument(r -> r.name(name).documentType(DocumentType.SESSION)
                .content("""
                        {"schemaVersion":"1.0","sessionType":"Standard_Stream"}
                        """));
        try (IamClient iam = AwsTestClients.create(IamClient.builder(), "test", Region.US_EAST_1, endpoint);
                StsClient sts = AwsTestClients.create(StsClient.builder(), "test", Region.US_EAST_1, endpoint)) {
            String roleArn = iam.createRole(r -> r.roleName(role).assumeRolePolicyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                     "Principal":{"AWS":"arn:aws:iam::000000000000:root"},"Action":"sts:AssumeRole"}]}
                    """)).role().arn();
            try {
                iam.putRolePolicy(r -> r.roleName(role).policyName("session").policyDocument(policy));
                try {
                    Credentials credentials = sts.assumeRole(r -> r.roleArn(roleArn).roleSessionName("condition-test"))
                            .credentials();
                    try (SsmClient caller = AwsTestClients.create(SsmClient.builder(), AwsSessionCredentials.create(
                            credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()),
                            Region.US_EAST_1, endpoint)) {
                        AtomicReference<StartSessionResponse> started = new AtomicReference<>();
                        try {
                            if (allowed) {
                                started.set(caller.startSession(r -> r.target(CONNECTED_TARGET)
                                        .documentName(customDocument ? name : null)));
                                assertEquals("initial-token", started.get().tokenValue());
                            } else {
                                SsmException error = assertThrows(SsmException.class,
                                        () -> started.set(caller.startSession(r -> r.target(CONNECTED_TARGET)
                                                .documentName(customDocument ? name : null))));
                                assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
                            }
                        } finally {
                            if (started.get() != null) {
                                ssm.terminateSession(r -> r.sessionId(started.get().sessionId()));
                            }
                        }
                    }
                } finally {
                    iam.deleteRolePolicy(r -> r.roleName(role).policyName("session"));
                }
            } finally {
                iam.deleteRole(r -> r.roleName(role));
            }
        } finally {
            ssm.deleteDocument(r -> r.name(name));
        }
    }

    private String startSessionPolicy(String region, String documentArn) {
        String targetArn = Arn.of("ec2", region, "000000000000", "instance/" + CONNECTED_TARGET).toString();
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ssm:StartSession","Resource":["%s","%s"]}
                ]}
                """.formatted(targetArn, documentArn);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"shell\"", "42", "true", "null"})
    void rejectsNonObjectInputsBeforeDispatchingSessionTask(String inputs) {
        connectGateway();
        BlockingQueue<SessionRequest> tasks = new LinkedBlockingQueue<>();
        when(gateway.startSession(any(), any())).thenAnswer(call -> {
            tasks.add(call.getArgument(0));
            return "initial-token";
        });
        String document = "session-inputs-" + UUID.randomUUID();
        ssm.createDocument(r -> r.name(document).documentType(DocumentType.SESSION).content("""
                {"schemaVersion":"1.0","sessionType":"Standard_Stream","inputs":%s}
                """.formatted(inputs)));
        AtomicReference<StartSessionResponse> started = new AtomicReference<>();
        try {
            assertAll(
                    () -> {
                        SsmException error = assertThrows(SsmException.class,
                                () -> started.set(ssm.startSession(r -> r.target(CONNECTED_TARGET)
                                        .documentName(document))));
                        assertEquals("InvalidDocument", error.awsErrorDetails().errorCode());
                        assertEquals(400, error.statusCode());
                    },
                    () -> assertNull(tasks.poll(),
                            "Invalid session inputs must not dispatch a session task"));
        } finally {
            try {
                if (started.get() != null) {
                    ssm.terminateSession(r -> r.sessionId(started.get().sessionId()));
                }
            } finally {
                ssm.deleteDocument(r -> r.name(document));
            }
        }
    }

    @Test
    void rejectsOversizedStoredSessionDocumentBeforeDispatchAndCanStillStartSession() {
        AtomicReference<Consumer<SessionState>> listener = connectGateway();
        String document = "session-oversized-" + UUID.randomUUID();
        String shell = "{\"schemaVersion\":\"1.0\",\"sessionType\":\"Standard_Stream\"}";
        String oversized = shell + " ".repeat(65_537 - shell.length());
        ssm.createDocument(r -> r.name(document).documentType(DocumentType.SESSION).content(oversized));
        AtomicReference<StartSessionResponse> unexpectedStart = new AtomicReference<>();
        try {
            assertAll(
                    () -> {
                        SsmException error = assertThrows(SsmException.class,
                                () -> unexpectedStart.set(ssm.startSession(r -> r.target(CONNECTED_TARGET)
                                        .documentName(document))));
                        assertEquals("InvalidDocument", error.awsErrorDetails().errorCode());
                        assertEquals(400, error.statusCode());
                    },
                    () -> assertNull(listener.get(), "Oversized documents must not dispatch a session task"),
                    () -> {
                        StartSessionResponse ordinary = ssm.startSession(r -> r.target(CONNECTED_TARGET));
                        try {
                            assertEquals("initial-token", ordinary.tokenValue());
                        } finally {
                            ssm.terminateSession(r -> r.sessionId(ordinary.sessionId()));
                        }
                    });
        } finally {
            try {
                if (unexpectedStart.get() != null) {
                    ssm.terminateSession(r -> r.sessionId(unexpectedStart.get().sessionId()));
                }
            } finally {
                ssm.deleteDocument(r -> r.name(document));
            }
        }
    }

    @Test
    void resumeReturnsFreshTokenForSameSessionId() {
        connectGateway();
        StartSessionResponse original = ssm.startSession(r -> r.target(CONNECTED_TARGET));
        try {
            when(gateway.resumeSession(CONNECTED_NODE, original.sessionId())).thenReturn(Optional.of("resume-token"));
            ResumeSessionResponse resumed = ssm.resumeSession(r -> r.sessionId(original.sessionId()));
            assertEquals(original.sessionId(), resumed.sessionId());
            assertEquals(original.streamUrl(), resumed.streamUrl());
            assertNotEquals(original.tokenValue(), resumed.tokenValue());
        } finally {
            ssm.terminateSession(r -> r.sessionId(original.sessionId()));
        }
    }

    @Test
    void agentCompletionMovesSessionToHistoryAndIgnoresLateConnectionEvent() throws Exception {
        AtomicReference<Consumer<SessionState>> listener = connectGateway();
        String sessionId = ssm.startSession(r -> r.target(CONNECTED_TARGET)).sessionId();
        CompletableFuture.runAsync(() -> {
            listener.get().accept(SessionState.TERMINATED);
            listener.get().accept(SessionState.CONNECTED);
        }).get(5, TimeUnit.SECONDS);
        assertTrue(sessions("Active", sessionId).isEmpty());
        Session history = sessions("History", sessionId).getFirst();
        assertEquals("Terminated", history.statusAsString());
        assertNotNull(history.endDate());
    }

    private List<Session> sessions(String state, String sessionId) {
        return ssm.describeSessions(r -> r.state(state).filters(f -> f.key("SessionId").value(sessionId))).sessions();
    }

    private AtomicReference<Consumer<SessionState>> connectGateway() {
        AtomicReference<Consumer<SessionState>> listener = new AtomicReference<>();
        when(gateway.isConnected(CONNECTED_NODE)).thenReturn(true);
        when(gateway.startSession(any(), any())).thenAnswer(call -> {
            listener.set(call.getArgument(1));
            return "initial-token";
        });
        return listener;
    }
}
