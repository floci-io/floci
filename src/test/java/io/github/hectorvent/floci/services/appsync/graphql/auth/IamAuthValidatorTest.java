package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IamAuthValidatorTest {

    private static final String ALLOW = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"appsync:GraphQL","Resource":"*"}]}
            """;
    private static final String DENY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Action":"appsync:GraphQL","Resource":"*"}]}
            """;
    private static final String FIELD_DENY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"appsync:GraphQL","Resource":"arn:aws:appsync:us-east-1:000000000000:apis/api-1/*"},
              {"Effect":"Deny","Action":"appsync:GraphQL","Resource":"arn:aws:appsync:us-east-1:000000000000:apis/api-1/types/Query/fields/secret"}
            ]}
            """;

    @Mock
    IamService iamService;

    private IamAuthValidator validator;
    private AuthRequestInfo info;

    @BeforeEach
    void setUp() {
        validator = new IamAuthValidator(
                new AccountResolver("000000000000"),
                iamService,
                new IamPolicyEvaluator(new ObjectMapper()));
        info = new AuthRequestInfo("{ hello }", null, Map.of(), List.of("10.0.0.1"),
                "req-1", "000000000000", "us-east-1", Map.of());
    }

    @Test
    void knownAllowBuildsIdentity() {
        when(iamService.resolveCallerContext("AKIAGOOD")).thenReturn(CallerContext.of(List.of(ALLOW)));
        when(iamService.resolveCallerArn("AKIAGOOD"))
                .thenReturn(Optional.of("arn:aws:iam::000000000000:user/alice"));

        Map<String, Object> identity = validator.validateRequest(
                "AWS4-HMAC-SHA256 Credential=AKIAGOOD/20260205/us-east-1/appsync/aws4_request",
                "api-1", info);

        assertEquals("AKIAGOOD", identity.get("user"));
        assertEquals("alice", identity.get("username"));
        assertInstanceOf(List.class, identity.get("sourceIp"));
        assertEquals(List.of("10.0.0.1"), identity.get("sourceIp"));
    }

    @Test
    void knownRequestDenyThrows401() {
        when(iamService.resolveCallerContext("AKIDDENY")).thenReturn(CallerContext.of(List.of(DENY)));

        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(
                        "AWS4-HMAC-SHA256 Credential=AKIDDENY/20260205/us-east-1/appsync/aws4_request",
                        "api-1", info));
        assertEquals(401, ex.getHttpStatus());
    }

    @Test
    void unknownTestKeyIsAllowed() {
        Map<String, Object> identity = validator.validateRequest(
                "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request",
                "api-1", info);
        assertEquals("test", identity.get("user"));
    }

    /**
     * These two build the resource the policy evaluator matches against, and the ARN a customer
     * writes their policy from is the one AppSync minted, which carries the region's partition.
     * A pinned {@code aws} here meant a GovCloud policy naming its own API never matched and the
     * request was denied with nothing saying why.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "us-east-1,      arn:aws:appsync:",
            "us-gov-west-1,  arn:aws-us-gov:appsync:",
            "cn-north-1,     arn:aws-cn:appsync:",
            "us-isob-east-1, arn:aws-iso-b:appsync:"})
    void resourceArnsCarryTheRegionsPartition(String region, String expectedPrefix) {
        assertTrue(IamAuthValidator.requestArn(region, "000000000000", "api-1").startsWith(expectedPrefix),
                IamAuthValidator.requestArn(region, "000000000000", "api-1"));
        assertTrue(IamAuthValidator.fieldArn(region, "000000000000", "api-1", "Query", "hello")
                        .startsWith(expectedPrefix),
                IamAuthValidator.fieldArn(region, "000000000000", "api-1", "Query", "hello"));
    }

    /** A null or blank region keeps the commercial partition, as every global ARN does. */
    @Test
    void aBlankRegionStaysCommercial() {
        assertEquals("arn:aws:appsync::000000000000:apis/api-1/*",
                IamAuthValidator.requestArn(null, "000000000000", "api-1"));
    }

    @Test
    void fieldArnDenyDetected() {
        when(iamService.resolveCallerContext("AKIAGOOD")).thenReturn(CallerContext.of(List.of(FIELD_DENY)));
        String fieldArn = IamAuthValidator.fieldArn("us-east-1", "000000000000", "api-1", "Query", "secret");
        assertTrue(validator.isFieldDenied("AKIAGOOD", fieldArn));
        assertFalse(validator.isFieldDenied("AKIAGOOD",
                IamAuthValidator.fieldArn("us-east-1", "000000000000", "api-1", "Query", "hello")));
        assertFalse(validator.isFieldDenied("test", fieldArn));
    }
}
