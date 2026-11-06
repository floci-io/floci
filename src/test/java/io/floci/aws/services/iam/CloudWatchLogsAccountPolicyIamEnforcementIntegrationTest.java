package io.floci.aws.services.iam;

import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.LogsAuthorizationFixtures;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * PutAccountPolicy also needs the action for the type of policy it creates, as its API reference
 * says: "To use this operation, you must be signed on with the correct permissions depending on the
 * type of policy that you are creating." DescribeAccountPolicies likewise needs the read action of the
 * type it lists, for the four types its reference names.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudWatchLogsAccountPolicyIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    /**
     * Where the allowed cases create their policies. Logs has no DeleteAccountPolicy, and a region
     * holds one data protection and one subscription filter policy, so the refused cases, which
     * create nothing, keep {@link #REGION} to themselves.
     */
    private static final String CREATE_REGION = "eu-west-1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({
            "DATA_PROTECTION_POLICY, PutDataProtectionPolicy",
            "SUBSCRIPTION_FILTER_POLICY, PutSubscriptionFilter",
            "TRANSFORMER_POLICY, PutTransformer",
            "FIELD_INDEX_POLICY, PutIndexPolicy",
            "METRIC_EXTRACTION_POLICY, PutMetricExtractionPolicy"})
    void callerAllowedOnlyPutAccountPolicyIsRefusedThePolicyType(String policyType, String typeAction) {
        String caller = user(allow("PutAccountPolicy"));
        String policyName = policyName();

        putAccountPolicy(caller, REGION, policyName, policyType)
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("logs:" + typeAction));
        describeAccountPolicy(REGION, policyName, policyType)
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @ParameterizedTest
    @CsvSource({
            "DATA_PROTECTION_POLICY, PutDataProtectionPolicy",
            "SUBSCRIPTION_FILTER_POLICY, PutSubscriptionFilter",
            "TRANSFORMER_POLICY, PutTransformer",
            "FIELD_INDEX_POLICY, PutIndexPolicy",
            "METRIC_EXTRACTION_POLICY, PutMetricExtractionPolicy"})
    void callerAllowedThePolicyTypeActionCreatesThePolicy(String policyType, String typeAction) {
        String caller = user(allow("PutAccountPolicy") + "," + allow(typeAction));
        String policyName = policyName();

        putAccountPolicy(caller, CREATE_REGION, policyName, policyType).statusCode(200);
        describeAccountPolicy(CREATE_REGION, policyName, policyType)
                .statusCode(200)
                .body("accountPolicies[0].policyName", equalTo(policyName));
    }

    @Test
    void unknownPolicyTypeIsRejectedAsInvalidRatherThanDenied() {
        String caller = user(allow("PutAccountPolicy"));

        putAccountPolicy(caller, REGION, policyName(), "UNKNOWN_POLICY")
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @ParameterizedTest
    @CsvSource({
            "DATA_PROTECTION_POLICY, GetDataProtectionPolicy",
            "SUBSCRIPTION_FILTER_POLICY, DescribeSubscriptionFilters",
            "TRANSFORMER_POLICY, GetTransformer",
            "FIELD_INDEX_POLICY, DescribeIndexPolicies"})
    void callerAllowedOnlyDescribeAccountPoliciesIsRefusedThePolicyType(String policyType, String typeAction) {
        String caller = user(allow("DescribeAccountPolicies"));

        describeAccountPolicies(caller, policyType)
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("logs:" + typeAction));
    }

    @ParameterizedTest
    @CsvSource({
            "DATA_PROTECTION_POLICY, GetDataProtectionPolicy",
            "SUBSCRIPTION_FILTER_POLICY, DescribeSubscriptionFilters",
            "TRANSFORMER_POLICY, GetTransformer",
            "FIELD_INDEX_POLICY, DescribeIndexPolicies"})
    void callerAllowedThePolicyTypeReadActionDescribesThePolicies(String policyType, String typeAction) {
        String caller = user(allow("DescribeAccountPolicies") + "," + allow(typeAction));

        describeAccountPolicies(caller, policyType).statusCode(200);
    }

    @Test
    void metricExtractionPoliciesNeedOnlyDescribeAccountPolicies() {
        String caller = user(allow("DescribeAccountPolicies"));

        describeAccountPolicies(caller, "METRIC_EXTRACTION_POLICY").statusCode(200);
    }

    private ValidatableResponse describeAccountPolicies(String caller, String policyType) {
        return LogsAuthorizationFixtures.call(endpoint.getPort(), caller, REGION, "DescribeAccountPolicies",
                Map.of("policyType", policyType)).then();
    }

    private ValidatableResponse putAccountPolicy(String caller, String region, String policyName,
                                                 String policyType) {
        return LogsAuthorizationFixtures.call(endpoint.getPort(), caller, region, "PutAccountPolicy",
                Map.of("policyName", policyName, "policyType", policyType, "policyDocument", "{}")).then();
    }

    private ValidatableResponse describeAccountPolicy(String region, String policyName, String policyType) {
        return LogsAuthorizationFixtures.call(endpoint.getPort(), ACCOUNT, region, "DescribeAccountPolicies",
                Map.of("policyName", policyName, "policyType", policyType)).then();
    }

    private static String policyName() {
        return "account-policy-auth-" + UUID.randomUUID();
    }

    private String user(String statements) {
        return LogsAuthorizationFixtures.user(cleanup, endpoint.getPort(), ACCOUNT, "account-policy-auth-",
                "account-policies", statements);
    }

    private static String allow(String action) {
        return "{\"Effect\":\"Allow\",\"Action\":\"logs:" + action + "\",\"Resource\":\"*\"}";
    }
}
