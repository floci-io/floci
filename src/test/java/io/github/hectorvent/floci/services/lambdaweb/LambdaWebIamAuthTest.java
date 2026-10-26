package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.IamService.OwnedSecretKey;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebIamAuth.Inbound;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService.WebFunctionTarget;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.MultiMap;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LambdaWebIamAuthTest {

    private static final String ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "222222222222";
    private static final String HOST = "abcdefghijklmnop.001.lambda-web.us-east-1.localhost:4566";
    private static final String WEST_HOST = "abcdefghijklmnop.001.lambda-web.us-west-2.localhost:4566";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":web-function/app";
    private static final String ENDPOINT_ARN = FUNCTION_ARN + "/endpoint/prod";
    private static final String USER_KEY = "AKIAUSERKEY000000001";
    private static final String USER_SECRET = "user-secret";
    private static final String SESSION_KEY = "ASIASESSIONKEY000001";
    private static final String DISTRIBUTION_ARN = "arn:aws:cloudfront::" + ACCOUNT + ":distribution/E2EXAMPLE";

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final IamService iamService = mock(IamService.class);
    @SuppressWarnings("unchecked")
    private final Instance<ScpProvider> scpProvider = mock(Instance.class);
    private final LambdaWebIamAuth iamAuth = new LambdaWebIamAuth(config, iamService,
            new IamPolicyEvaluator(new ObjectMapper()), scpProvider);
    private final WebFunction function = new WebFunction();
    private final WebFunctionEndpoint endpoint = new WebFunctionEndpoint();

    @BeforeEach
    void setUp() {
        function.functionName = "app";
        function.functionArn = FUNCTION_ARN;
        endpoint.endpointName = "prod";
        endpoint.endpointArn = ENDPOINT_ARN;
        endpoint.authType = "IamAuth";
        when(iamService.findSecretKeyInAnyAccount(USER_KEY, null))
                .thenReturn(Optional.of(new OwnedSecretKey(USER_SECRET, ACCOUNT)));
        when(iamService.findSecretKeyInAnyAccount(SESSION_KEY, "token"))
                .thenReturn(Optional.of(new OwnedSecretKey("session-secret", null)));
    }

    @Test
    void aRequestSignedForLambdaInTheEndpointRegionPasses() {
        assertTrue(allows(signer().sign("GET", HOST, "/items?b=2&a=1")));
        assertTrue(allows(signer().sign("POST", HOST, "/")));
        assertTrue(allows(signer().presigned().sign("GET", HOST, "/q?x=1")));
    }

    @Test
    void theSignatureCoversThePathAsSignersNormalizeIt() {
        assertTrue(allows(signer().sign("GET", HOST, "/a/c").withPathAndQuery("/a/./b/../c")));
        assertTrue(allows(signer().sign("GET", HOST, "/a/b").withPathAndQuery("/a//b")));
        assertTrue(allows(signer().sign("GET", HOST, "/a").withPathAndQuery("/a/b/..")));
        assertTrue(allows(signer().sign("GET", HOST, "/path%2Fenc/a%20b?c=x%2By")));
    }

    @Test
    void aSignatureOverThePathAsSentIsRefusedWhenSignersWouldNormalizeIt() {
        assertFalse(allows(signer().rawPath().sign("GET", HOST, "/a/./b/../c")));
        assertFalse(allows(signer().rawPath().sign("GET", HOST, "/a//b")));
        assertTrue(allows(signer().rawPath().sign("GET", HOST, "/a/b/")));
    }

    @Test
    void signersResolveDotSegmentsAndDropEmptySegments() {
        assertEquals("/a/c", LambdaWebIamAuth.signedPath("/a/./b/../c"));
        assertEquals("/a/b", LambdaWebIamAuth.signedPath("/a//b"));
        assertEquals("/a", LambdaWebIamAuth.signedPath("//a"));
        assertEquals("/a", LambdaWebIamAuth.signedPath("/a/b/.."));
        assertEquals("/a/b/", LambdaWebIamAuth.signedPath("/a/b/"));
        assertEquals("/", LambdaWebIamAuth.signedPath("/a/.."));
        assertEquals("/", LambdaWebIamAuth.signedPath("/"));
        assertEquals("/a/%2e%2e/b", LambdaWebIamAuth.signedPath("/a/%2e%2e/b"));
    }

    @Test
    void anUnsignedOrForeignAuthorizationIsRefused() {
        LambdaWebRequestSigner.Signed signed = signer().sign("GET", HOST, "/");
        assertFalse(allows(signed.withHeader("Authorization", null)));
        assertFalse(allows(signed.withHeader("Authorization", "Bearer abc")));
        assertFalse(allows(signer().presigned().sign("GET", HOST, "/").withHeader("Authorization", "Bearer abc")),
                "the header wins over the query");
    }

    @Test
    void theContentSha256HeaderIsRequired() {
        assertFalse(allows(signer().sign("GET", HOST, "/").withHeader("X-Amz-Content-SHA256", null)));
        assertFalse(allows(signer().contentSha256(null).presigned().sign("GET", HOST, "/")));
    }

    @Test
    void theScopeMustNameLambdaAndTheEndpointRegion() {
        assertFalse(allows(signer().region("us-west-2").sign("GET", HOST, "/")));
        assertFalse(allows(signer().service("lambda-web").sign("GET", HOST, "/")));
        assertFalse(allows(signer().service("execute-api").sign("GET", HOST, "/")));
    }

    @Test
    void aRegionalDomainOfAnotherRegionTakesOnlyASignatureForThatRegion() {
        assertTrue(allows(signer().region("us-west-2").sign("GET", WEST_HOST, "/"), WEST_HOST, "us-west-2"));
        assertFalse(allows(signer().sign("GET", WEST_HOST, "/"), WEST_HOST, "us-west-2"));
    }

    @Test
    void theSigningTimeMustBeWithinFiveMinutes() {
        Instant now = Instant.now();
        assertTrue(allows(signer().signedAt(now.minus(Duration.ofMinutes(4))).sign("GET", HOST, "/")));
        assertFalse(allows(signer().signedAt(now.minus(Duration.ofMinutes(6))).sign("GET", HOST, "/")));
        assertFalse(allows(signer().signedAt(now.plus(Duration.ofMinutes(6))).sign("GET", HOST, "/")));
        assertFalse(allows(signer().presigned().signedAt(now.minus(Duration.ofMinutes(6))).sign("GET", HOST, "/")),
                "a presigned request expires");
    }

    @Test
    void anAlteredRequestIsRefused() {
        LambdaWebRequestSigner.Signed signed = signer().sign("GET", HOST, "/x?b=1");
        assertFalse(allows(signed.withPathAndQuery("/x?a=1")));
        assertFalse(allows(signed.withPathAndQuery("/y?b=1")));
        assertFalse(allows(signer().credentials("test", "other", null).sign("GET", HOST, "/")));
    }

    @Test
    void theKeyMustBeKnownAndCarryItsSessionTokenOnlyWhenTemporary() {
        assertTrue(allows(signer().credentials(USER_KEY, USER_SECRET, null).sign("GET", HOST, "/")));
        assertFalse(allows(signer().credentials("AKIAUNKNOWN000000001", "x", null).sign("GET", HOST, "/")));
        assertFalse(allows(signer().credentials(USER_KEY, USER_SECRET, "token").sign("GET", HOST, "/")),
                "a long-term key with a session token");
        assertTrue(allows(signer().credentials(SESSION_KEY, "session-secret", "token").sign("GET", HOST, "/")));
        assertFalse(allows(signer().credentials(SESSION_KEY, "session-secret", "token").sign("GET", HOST, "/")
                .withHeader("X-Amz-Security-Token", null)));
    }

    @Test
    void theSessionTokenIsReadFromWhereTheSignatureIsAndMayNotBeEmpty() {
        LambdaWebRequestSigner longTerm = signer().credentials(USER_KEY, USER_SECRET, null);
        assertFalse(allows(longTerm.sign("GET", HOST, "/").withHeader("X-Amz-Security-Token", "")));
        assertTrue(allows(longTerm.sign("GET", HOST, "/x?X-Amz-Security-Token=bogus")));
        assertTrue(allows(longTerm.presigned().sign("GET", HOST, "/").withHeader("X-Amz-Security-Token", "bogus")));
    }

    @Test
    void aMalformedDateOrQueryIsRefused() {
        assertFalse(allows(signer().sign("GET", HOST, "/").withHeader("X-Amz-Date", "20261017T99")));
        assertFalse(allows(signer().sign("GET", HOST, "/q?x=1").withPathAndQuery("/q?x=%zz")));
    }

    @Test
    void aFailureInsideFlociIsNotTakenForARefusal() {
        when(iamService.findSecretKeyInAnyAccount(USER_KEY, null)).thenThrow(new IllegalStateException("storage"));
        assertThrows(IllegalStateException.class,
                () -> allows(signer().credentials(USER_KEY, USER_SECRET, null).sign("GET", HOST, "/")));
    }

    @Test
    void underEnforcementTheCallerNeedsInvokeOnTheEndpoint() {
        enforceFor(ACCOUNT);
        assertTrue(allowsUserWith(allow(ENDPOINT_ARN, "")));
        assertTrue(allowsUserWith(allow(FUNCTION_ARN + "/endpoint/*", "")));
        assertFalse(allowsUserWith(allow(FUNCTION_ARN, "")));
        assertFalse(allowsUserWith());
        assertFalse(allowsUserWith(allow("*", ""), """
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Action":"lambda:InvokeWebFunctionEndpoint",
                 "Resource":"%s"}]}""".formatted(ENDPOINT_ARN)));
    }

    @Test
    void underEnforcementTheAuthTypeAndTagConditionKeysAreSet() {
        enforceFor(ACCOUNT);
        function.tags.put("env", "probe");
        assertTrue(allowsUserWith(allow(ENDPOINT_ARN,
                ",\"Condition\":{\"StringEquals\":{\"lambda:Resource/WebFunctionAuthType\":\"IamAuth\"}}")));
        assertFalse(allowsUserWith(allow(ENDPOINT_ARN,
                ",\"Condition\":{\"StringEquals\":{\"lambda:Resource/WebFunctionAuthType\":\"ApplicationManaged\"}}")));
        assertTrue(allowsUserWith(allow(ENDPOINT_ARN,
                ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/env\":\"probe\"}}")));
        assertFalse(allowsUserWith(allow(ENDPOINT_ARN,
                ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/env\":\"prod\"}}")));
    }

    @Test
    void underEnforcementTheRequestedRegionIsTheRegionOfTheDomain() {
        enforceFor(ACCOUNT);
        LambdaWebRequestSigner.Signed west = signer().credentials(USER_KEY, USER_SECRET, null).region("us-west-2")
                .sign("GET", WEST_HOST, "/");
        function.policy = denyRequestedRegion("us-west-2");
        assertTrue(allowsUserWith(allow(ENDPOINT_ARN, "")));
        assertFalse(allows(west, WEST_HOST, "us-west-2"));
        function.policy = denyRequestedRegion("us-east-1");
        assertFalse(allowsUserWith(allow(ENDPOINT_ARN, "")));
        assertTrue(allows(west, WEST_HOST, "us-west-2"));
    }

    @Test
    void underEnforcementTheResourcePolicyAllowsWithinTheAccountAndItsDenyWins() {
        enforceFor(ACCOUNT);
        function.policy = resourcePolicy("Allow", ACCOUNT);
        assertTrue(allowsUserWith());
        function.policy = resourcePolicy("Deny", ACCOUNT);
        assertFalse(allowsUserWith(allow(ENDPOINT_ARN, "")));
    }

    @Test
    void underEnforcementAnotherAccountNeedsBothPolicies() {
        enforceFor(OTHER_ACCOUNT);
        assertFalse(allowsUserWith(allow(ENDPOINT_ARN, "")));
        function.policy = resourcePolicy("Allow", OTHER_ACCOUNT);
        assertFalse(allowsUserWith());
        assertTrue(allowsUserWith(allow(ENDPOINT_ARN, "")));
    }

    @Test
    void theTestKeyIsNotEvaluated() {
        enforceFor(ACCOUNT);
        assertTrue(allows(signer().sign("GET", HOST, "/")));
    }

    @Test
    void cloudFrontSignsWithTheKeyOfThisProcess() {
        assertTrue(allowsCloudFront(iamAuth.cloudFrontSignature(DISTRIBUTION_ARN)));

        Map<String, String> forged = new HashMap<>(iamAuth.cloudFrontSignature(DISTRIBUTION_ARN));
        forged.put("lambda-web-cloudfront-key", "0".repeat(64));
        assertFalse(allowsCloudFront(forged));
        LambdaWebIamAuth another = new LambdaWebIamAuth(config, iamService, new IamPolicyEvaluator(new ObjectMapper()),
                scpProvider);
        assertFalse(allowsCloudFront(another.cloudFrontSignature(DISTRIBUTION_ARN)));
        assertFalse(allowsCloudFront(iamAuth.cloudFrontSignature("E2EXAMPLE")));
    }

    @Test
    void underEnforcementTheFunctionPolicyMustAllowCloudFrontForTheDistribution() {
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        assertFalse(allowsCloudFront(iamAuth.cloudFrontSignature(DISTRIBUTION_ARN)), "no policy denies");

        function.policy = cloudFrontPolicy(cloudFrontStatement("Allow", "aws:SourceArn", DISTRIBUTION_ARN));
        assertTrue(allowsCloudFront(iamAuth.cloudFrontSignature(DISTRIBUTION_ARN)));
        assertFalse(allowsCloudFront(iamAuth.cloudFrontSignature(
                "arn:aws:cloudfront::" + ACCOUNT + ":distribution/E3OTHER")));

        function.policy = cloudFrontPolicy(cloudFrontStatement("Allow", "aws:SourceAccount", ACCOUNT));
        assertTrue(allowsCloudFront(iamAuth.cloudFrontSignature(DISTRIBUTION_ARN)));
        assertFalse(allowsCloudFront(iamAuth.cloudFrontSignature(
                "arn:aws:cloudfront::" + OTHER_ACCOUNT + ":distribution/E2EXAMPLE")));

        String allowIamAuth = cloudFrontStatement("Allow", "lambda:Resource/WebFunctionAuthType", "IamAuth");
        function.policy = cloudFrontPolicy(allowIamAuth);
        assertTrue(allowsCloudFront(iamAuth.cloudFrontSignature(DISTRIBUTION_ARN)));
        function.policy = cloudFrontPolicy(allowIamAuth, cloudFrontStatement("Deny", "aws:SourceArn", DISTRIBUTION_ARN));
        assertFalse(allowsCloudFront(iamAuth.cloudFrontSignature(DISTRIBUTION_ARN)));
    }

    @Test
    void underEnforcementCloudFrontRequestsTheRegionThatServes() {
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        Map<String, String> signature = iamAuth.cloudFrontSignature(DISTRIBUTION_ARN);
        String allow = cloudFrontStatement("Allow", "aws:SourceArn", DISTRIBUTION_ARN);
        function.policy = cloudFrontPolicy(allow, cloudFrontStatement("Deny", "aws:RequestedRegion", "us-west-2"));
        assertTrue(allowsCloudFront(signature));
        assertFalse(allowsCloudFront(signature, WEST_HOST, "us-west-2"));
        function.policy = cloudFrontPolicy(allow, cloudFrontStatement("Deny", "aws:RequestedRegion", "us-east-1"));
        assertFalse(allowsCloudFront(signature));
        assertTrue(allowsCloudFront(signature, WEST_HOST, "us-west-2"));
    }

    private boolean allowsCloudFront(Map<String, String> signature) {
        return allowsCloudFront(signature, HOST, "us-east-1");
    }

    private boolean allowsCloudFront(Map<String, String> signature, String host, String servingRegion) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().add("Host", host).addAll(signature);
        return iamAuth.allows(new Inbound("POST", "/", null, headers, MultiMap.caseInsensitiveMultiMap()),
                new WebFunctionTarget(ACCOUNT, "us-east-1", function, endpoint, servingRegion));
    }

    private static String cloudFrontPolicy(String... statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
    }

    private static String cloudFrontStatement(String effect, String conditionKey, String conditionValue) {
        return """
                {"Effect":"%s","Principal":{"Service":"cloudfront.amazonaws.com"},
                 "Action":"lambda:InvokeWebFunctionEndpoint","Resource":"%s",
                 "Condition":{"StringEquals":{"%s":"%s"}}}"""
                .formatted(effect, ENDPOINT_ARN, conditionKey, conditionValue);
    }

    private void enforceFor(String callerAccount) {
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        when(iamService.findSecretKeyInAnyAccount(USER_KEY, null))
                .thenReturn(Optional.of(new OwnedSecretKey(USER_SECRET, callerAccount)));
        when(iamService.resolveAccountId(USER_KEY)).thenReturn(Optional.of(callerAccount));
        String userArn = "arn:aws:iam::" + callerAccount + ":user/caller";
        when(iamService.resolveCallerArns(USER_KEY)).thenReturn(Optional.of(
                new CallerArns(userArn, userArn, CallerArns.TYPE_USER, "AIDACALLER", "caller", false)));
    }

    private boolean allowsUserWith(String... identityPolicies) {
        when(iamService.resolveCallerContext(eq(USER_KEY), any(Instant.class)))
                .thenReturn(CallerContext.of(List.of(identityPolicies)));
        return allows(signer().credentials(USER_KEY, USER_SECRET, null).sign("GET", HOST, "/"));
    }

    private static String resourcePolicy(String effect, String callerAccount) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Principal":{"AWS":"arn:aws:iam::%s:user/caller"},
                 "Action":"lambda:InvokeWebFunctionEndpoint","Resource":"%s"}]}"""
                .formatted(effect, callerAccount, ENDPOINT_ARN);
    }

    private static String denyRequestedRegion(String region) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":"*",
                 "Action":"lambda:InvokeWebFunctionEndpoint","Resource":"%s",
                 "Condition":{"StringEquals":{"aws:RequestedRegion":"%s"}}}]}""".formatted(ENDPOINT_ARN, region);
    }

    private static String allow(String resource, String condition) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"lambda:InvokeWebFunctionEndpoint",
                 "Resource":"%s"%s}]}""".formatted(resource, condition);
    }

    private static LambdaWebRequestSigner signer() {
        return new LambdaWebRequestSigner();
    }

    private boolean allows(LambdaWebRequestSigner.Signed signed) {
        return allows(signed, HOST, "us-east-1");
    }

    private boolean allows(LambdaWebRequestSigner.Signed signed, String host, String servingRegion) {
        String pathAndQuery = signed.pathAndQuery();
        int question = pathAndQuery.indexOf('?');
        String path = question < 0 ? pathAndQuery : pathAndQuery.substring(0, question);
        String query = question < 0 ? null : pathAndQuery.substring(question + 1);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().add("Host", host);
        signed.headers().forEach(headers::add);
        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        try {
            new QueryStringDecoder(pathAndQuery).parameters().forEach(params::add);
        } catch (IllegalArgumentException expected) {
            // The data plane keeps going with no parameters when the query does not decode.
        }
        return iamAuth.allows(new Inbound(signed.method(), path, query, headers, params),
                new WebFunctionTarget(ACCOUNT, "us-east-1", function, endpoint, servingRegion));
    }
}
