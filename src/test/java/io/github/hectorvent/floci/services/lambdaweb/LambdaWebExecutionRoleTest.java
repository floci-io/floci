package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LambdaWebExecutionRoleTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:web-function/app";
    private static final String LAMBDA = "{\"Service\":\"lambda.amazonaws.com\"}";

    private final IamService iamService = mock(IamService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LambdaWebExecutionRole executionRole = new LambdaWebExecutionRole(iamService,
            new AssumeRolePolicyEvaluator(mapper, new IamPolicyEvaluator(mapper)));

    @Test
    void aRevisionNeedsARoleThatTrustsLambdaWithNoSourceGiven() {
        assertTrue(creates(role("lambda", "/", statement("Allow", LAMBDA, "sts:AssumeRole", null))));
        assertTrue(creates(role("array", "/", statement("Allow",
                "{\"Service\":[\"ec2.amazonaws.com\",\"lambda.amazonaws.com\"]}", "sts:AssumeRole", null))));
        assertTrue(creates(role("path", "/service-role/", statement("Allow", LAMBDA, "sts:AssumeRole", null))));
        assertTrue(creates(role("no-arn", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                "{\"Null\":{\"aws:SourceArn\":\"true\"}}"))));
        assertTrue(creates(role("no-account", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                "{\"Null\":{\"aws:SourceAccount\":\"true\"}}"))));

        assertFalse(creates("arn:aws:iam::000000000000:role/missing"));
        assertFalse(creates(role("ec2", "/", statement("Allow", "{\"Service\":\"ec2.amazonaws.com\"}",
                "sts:AssumeRole", null))));
        assertFalse(creates(role("deny", "/", statement("Allow", LAMBDA, "sts:AssumeRole", null) + ","
                + statement("Deny", LAMBDA, "sts:AssumeRole", null))));
        assertFalse(creates(role("account", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                "{\"StringEquals\":{\"aws:SourceAccount\":\"" + ACCOUNT + "\"}}"))));
        assertFalse(creates(role("arn", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                "{\"ArnEquals\":{\"aws:SourceArn\":\"" + FUNCTION_ARN + "\"}}"))));
        assertFalse(creates(role("root", "/", statement("Allow", "{\"AWS\":\"arn:aws:iam::" + ACCOUNT + ":root\"}",
                "sts:AssumeRole", null))));
        assertFalse(creates(role("tag", "/", statement("Allow", LAMBDA, "sts:TagSession", null))));
        assertFalse(creates("arn:aws:iam::000000000000:role/path"));
        String lambda = role("lambda", "/", statement("Allow", LAMBDA, "sts:AssumeRole", null));
        Optional<IamRole> found = iamService.findRole(ACCOUNT, "lambda");
        when(iamService.findRole(ACCOUNT, "LAMBDA")).thenReturn(found);
        assertFalse(creates(lambda.replace("lambda", "LAMBDA")));
        assertFalse(creates(lambda.replace("arn:aws:", "arn:aws-cn:")));
    }

    @Test
    void theRefusalNamesTheRoleAndLambda() {
        AwsException refused = assertThrows(AwsException.class,
                () -> executionRole.requireAssumable(REGION, "arn:aws:iam::000000000000:role/missing"));
        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals(400, refused.getHttpStatus());
        assertEquals("The execution role arn:aws:iam::000000000000:role/missing cannot be assumed by"
                + " lambda.amazonaws.com. Verify its trust policy. If the role was just created, retry in a few"
                + " seconds.", refused.getMessage());
    }

    @Test
    void anEnvironmentAssumesTheRoleWithTheFunctionAsItsSource() {
        assertTrue(starts(role("account", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                "{\"StringEquals\":{\"aws:SourceAccount\":\"" + ACCOUNT + "\"}}"))));
        assertTrue(starts(role("arn", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                "{\"ArnEquals\":{\"aws:SourceArn\":\"" + FUNCTION_ARN + "\"}}"))));

        assertFalse(starts(role("no-arn", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                "{\"Null\":{\"aws:SourceArn\":\"true\"}}"))));
        assertFalse(starts(role("ec2", "/", statement("Allow", "{\"Service\":\"ec2.amazonaws.com\"}",
                "sts:AssumeRole", null))));
        assertFalse(starts("arn:aws:iam::000000000000:role/missing"));
    }

    @Test
    void lambdaAssumesTheRoleAsAServicePrincipal() {
        String allow = statement("Allow", LAMBDA, "sts:AssumeRole", null);
        List<String> accepted = List.of(
                role("everyone", "/", statement("Allow", "{\"AWS\":\"*\"}", "sts:AssumeRole", null)),
                role("service", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                        "{\"Bool\":{\"aws:PrincipalIsAWSService\":\"true\"}}")),
                role("service-name", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                        "{\"StringEquals\":{\"aws:PrincipalServiceName\":\"lambda.amazonaws.com\"}}")));
        List<String> refused = List.of(
                role("deny-everyone", "/", allow + ","
                        + statement("Deny", "{\"AWS\":\"*\"}", "sts:AssumeRole", null)),
                role("not-service", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                        "{\"Bool\":{\"aws:PrincipalIsAWSService\":\"false\"}}")),
                role("no-service", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                        "{\"Null\":{\"aws:PrincipalIsAWSService\":\"true\"}}")),
                role("no-service-name", "/", statement("Allow", LAMBDA, "sts:AssumeRole",
                        "{\"Null\":{\"aws:PrincipalServiceName\":\"true\"}}")));
        for (String roleArn : accepted) {
            assertTrue(creates(roleArn), roleArn);
            assertTrue(starts(roleArn), roleArn);
        }
        for (String roleArn : refused) {
            assertFalse(creates(roleArn), roleArn);
            assertFalse(starts(roleArn), roleArn);
        }
    }

    private boolean creates(String roleArn) {
        try {
            executionRole.requireAssumable(REGION, roleArn);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    private boolean starts(String roleArn) {
        return executionRole.assumableFor(REGION, FUNCTION_ARN, roleArn);
    }

    private String role(String name, String path, String statements) {
        String arn = "arn:aws:iam::" + ACCOUNT + ":role" + path + name;
        IamRole role = new IamRole("AROA" + name, name, path, arn,
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        when(iamService.findRole(ACCOUNT, name)).thenReturn(Optional.of(role));
        return arn;
    }

    private static String statement(String effect, String principal, String action, String condition) {
        return "{\"Effect\":\"" + effect + "\",\"Principal\":" + principal + ",\"Action\":\"" + action + "\""
                + (condition == null ? "" : ",\"Condition\":" + condition) + "}";
    }
}
