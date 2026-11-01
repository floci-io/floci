package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IamViaServiceContextTest {

    @Test
    void directCallerHasFalseWithoutMutatingInput() {
        Map<String, List<String>> input = Map.of("aws:SourceIp", List.of("127.0.0.1"));
        Map<String, List<String>> result = IamConditionContextResolver.withGlobalContext(
                input, "arn:aws:iam::000000000000:user/example", "us-east-1", "000000000000", "000000000000");
        assertEquals(List.of("false"), result.get("aws:ViaAWSService"));
        assertEquals(input.get("aws:SourceIp"), result.get("aws:SourceIp"));
        assertFalse(input.containsKey("aws:ViaAWSService"));
    }

    @Test
    void explicitForwardedContextIsPreserved() {
        Map<String, List<String>> result = IamConditionContextResolver.withGlobalContext(
                Map.of("aws:ViaAWSService", List.of("true")),
                "arn:aws:s3:::example", "us-east-1", "000000000000", "000000000000");
        assertEquals(List.of("true"), result.get("aws:ViaAWSService"));
    }

    @Test
    void servicePrincipalIsNotAForwardAccessSession() {
        Map<String, List<String>> result = IamConditionContextResolver.serviceCallContext(
                "sns.amazonaws.com", "arn:aws:lambda:us-east-1:000000000000:function:example",
                "000000000000", "arn:aws:sns:us-east-1:000000000000:example", "000000000000");
        assertEquals(List.of("true"), result.get("aws:PrincipalIsAWSService"));
        assertEquals(List.of("false"), result.get("aws:ViaAWSService"));
    }
}
