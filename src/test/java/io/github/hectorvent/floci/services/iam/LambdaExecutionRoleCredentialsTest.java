package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaExecutionRoleCredentialsTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE_NAME = "LambdaExecutionRole";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/" + ROLE_NAME;
    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Deny","Action":"s3:*","Resource":"*"}
            ]}
            """;

    private IamService iamService;
    private LambdaExecutionRoleCredentials credentials;

    @BeforeEach
    void setUp() {
        iamService = new IamService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", ACCOUNT_ID));
        iamService.createRole(ROLE_NAME, "/", "{}", null, 0, null);
        iamService.putRolePolicy(ROLE_NAME, "DenyS3", POLICY);
        credentials = new LambdaExecutionRoleCredentials(iamService);
    }

    @Test
    void knownRoleMintsRegisteredNonExpiringSessionCredentials() {
        LambdaFunction function = function(ROLE_ARN);

        SessionCreds session = credentials.forFunction(function).orElseThrow();

        assertTrue(session.accessKeyId().startsWith("ASIA"));
        assertEquals(20, session.accessKeyId().length());
        assertEquals(40, session.secretAccessKey().length());
        assertEquals(200, session.sessionToken().length());
        CallerContext caller = iamService.resolveCallerContext(session.accessKeyId());
        assertNotNull(caller);
        assertEquals(java.util.List.of(POLICY), caller.identityPolicies());
        assertEquals(
                "arn:aws:sts::000000000000:assumed-role/LambdaExecutionRole/floci-session",
                iamService.resolveCallerArn(session.accessKeyId()).orElseThrow());
    }

    @Test
    void unknownRoleKeepsCredentialFallback() {
        assertTrue(credentials.forFunction(
                function("arn:aws:iam::000000000000:role/UnknownRole")).isEmpty());
    }

    @Test
    void missingOrMalformedRoleKeepsCredentialFallback() {
        for (String role : new String[]{null, "", "   ", "not-an-arn", "arn:aws:s3:::bucket"}) {
            assertEquals(Optional.empty(), credentials.forFunction(function(role)), role);
        }
    }

    private static LambdaFunction function(String roleArn) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName("function");
        function.setAccountId(ACCOUNT_ID);
        function.setRole(roleArn);
        return function;
    }
}
