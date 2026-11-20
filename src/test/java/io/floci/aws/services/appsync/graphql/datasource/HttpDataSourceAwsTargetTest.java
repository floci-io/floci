package io.floci.aws.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.appsync.model.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpDataSourceAwsTargetTest {

    private static final String ACCOUNT = "724600001234";
    private static final String API_REGION = "us-east-1";
    private static final String REGION = "us-west-2";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver regions = new RegionResolver(API_REGION, ACCOUNT);

    @Test
    void schedulerCanUseADifferentRegionWithinTheSamePartition() {
        HttpDataSourceAwsTarget target = parse(source("scheduler", endpoint("scheduler")), "GET", "/schedules/example", Map.of());
        assertEquals("scheduler", target.service());
        assertEquals(REGION, target.region());
        assertEquals(ACCOUNT, target.accountId());
        assertEquals("arn:aws:appsync:" + API_REGION + ":" + ACCOUNT + ":apis/example", target.sourceApiArn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/state/reset", "/", "/schedules", "/schedules/.", "/schedules/..",
            "/schedules/a/extra", "/schedules/a%2Fb", "/schedules/%2e%2e", "/schedules/a%3fb"})
    void schedulerCannotEscapeTheAuditedOperationPaths(String path) {
        assertInvalid(source("scheduler", endpoint("scheduler")), "POST", path, Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://scheduler.us-west-2.amazonaws.com", "https://scheduler.us-west-2.amazonaws.com:443",
            "https://scheduler.us-west-2.amazonaws.com.evil.example", "https://scheduler.us-west-2.amazonaws.com/base",
            "https://states.us-west-2.amazonaws.com"})
    void signingScopeCannotBeRedirectedToAnotherEndpoint(String endpoint) {
        assertInvalid(source("scheduler", endpoint), "GET", "/schedules/example", Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization", "x-amz-date", "x-amz-security-token", "x-amzn-query-mode",
            "x-forwarded-host", "x-floci-service"})
    void resolverHeadersCannotOverrideCredentialsOrListenerRouting(String header) {
        assertInvalid(source("scheduler", endpoint("scheduler")), "GET", "/schedules/example",
                Map.of("headers", Map.of(header, "untrusted")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Action", "X-Amz-Credential", "X-Amz-Signature", "service"})
    void queryParametersCannotSelectAnotherProtocolOrIdentity(String name) {
        assertInvalid(source("scheduler", endpoint("scheduler")), "GET", "/schedules/example",
                Map.of("query", Map.of(name, "untrusted")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s3", "ssm", "execute-api", "unknown"})
    void unauditedAwsServicesAreRefused(String service) {
        assertInvalid(source(service, endpoint(service)), "POST", "/", Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws-global", "us-fake-99", "US-WEST-2"})
    void nonRegionalSigningScopesAreRejected(String region) {
        String endpoint = "https://scheduler." + region + ".amazonaws.com";
        DataSource source = source("scheduler", endpoint);
        source.setHttpConfig(Map.of("endpoint", endpoint, "authorizationConfig",
                Map.of("authorizationType", "AWS_IAM", "awsIamConfig",
                        Map.of("signingRegion", region, "signingServiceName", "scheduler"))));
        assertInvalid(source, "GET", "/schedules/example", Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWSStepFunctions.SendTaskSuccess", "AWSStepFunctions.SendTaskFailure"})
    void stateTaskCallbacksAreTheOnlySupportedJsonOperations(String operation) {
        assertEquals("states", parse(source("states", endpoint("states")), "POST", "/",
                Map.of("headers", Map.of("X-Amz-Target", operation))).service());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWSStepFunctions.StartExecution", "AWSSimpleSystemsManagement.GetParameter", "unknown", ""})
    void jsonTargetsCannotSelectAnUnauditedOperation(String operation) {
        assertInvalid(source("states", endpoint("states")), "POST", "/",
                Map.of("headers", Map.of("X-Amz-Target", operation)));
    }

    @Test
    void anUnsupportedStepFunctionsWireProtocolIsRefusedBeforeSigning() {
        assertInvalid(source("states", endpoint("states")), "POST", "/", Map.of("headers",
                Map.of("X-Amz-Target", "AWSStepFunctions.SendTaskSuccess", "Content-Type", "application/x-amz-json-1.1")));
    }

    @Test
    void missingStateTargetAndForeignRolesFailClosed() {
        assertInvalid(source("states", endpoint("states")), "POST", "/", Map.of());
        for (String role : new String[]{"arn:aws:iam::999999999999:role/other",
                "arn:aws-cn:iam::" + ACCOUNT + ":role/other"}) {
            DataSource source = source("scheduler", endpoint("scheduler"));
            source.setServiceRoleArn(role);
            AwsException error = assertThrows(AwsException.class, () -> parse(source, "GET", "/schedules/example", Map.of()));
            assertEquals("AccessDeniedException", error.getErrorCode());
        }
    }

    private void assertInvalid(DataSource source, String method, String path, Map<String, Object> params) {
        AwsException error = assertThrows(AwsException.class, () -> parse(source, method, path, params));
        assertEquals("MappingTemplate", error.getErrorCode());
    }

    private HttpDataSourceAwsTarget parse(DataSource source, String method, String path, Map<String, Object> params) {
        HttpDataSourceRequest request = HttpDataSourceRequest.parse(source,
                Map.of("method", method, "resourcePath", path, "params", params), mapper);
        return HttpDataSourceAwsTarget.parse(source, request, API_REGION, regions);
    }

    private static DataSource source(String service, String endpoint) {
        DataSource source = new DataSource();
        source.setDataSourceArn("arn:aws:appsync:" + API_REGION + ":" + ACCOUNT + ":apis/example/datasources/Http");
        source.setServiceRoleArn("arn:aws:iam::" + ACCOUNT + ":role/Http");
        source.setHttpConfig(Map.of("endpoint", endpoint, "authorizationConfig",
                Map.of("authorizationType", "AWS_IAM", "awsIamConfig",
                        Map.of("signingRegion", REGION, "signingServiceName", service))));
        return source;
    }

    private static String endpoint(String service) {
        return "https://" + service + "." + REGION + ".amazonaws.com";
    }
}
