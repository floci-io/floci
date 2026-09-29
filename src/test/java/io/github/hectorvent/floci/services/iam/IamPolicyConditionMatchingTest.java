package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamPolicyConditionMatchingTest {

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    @ParameterizedTest
    @CsvSource({
            "StringLike, home/Alice/*, home/Alice/notes, ALLOW",
            "StringLike, home/Alice/*, home/alice/notes, DENY",
            "StringNotLike, home/Alice/*, home/Alice/notes, DENY",
            "StringNotLike, home/Alice/*, home/alice/notes, ALLOW",
            "StringLike, home/Alice/?.txt, home/Alice/a.txt, ALLOW",
            "StringLike, home/Alice/?.txt, home/Alice/ab.txt, DENY",
            "StringLike, home/Alice/*, home/Alice/a:b/notes, ALLOW",
            "StringEqualsIgnoreCase, Alice, alice, ALLOW",
            "StringNotEqualsIgnoreCase, Alice, alice, DENY",
            "ArnEquals, arn:aws:sns:us-east-1:111122223333:Alerts, arn:aws:sns:us-east-1:111122223333:Alerts, ALLOW",
            "ArnEquals, arn:aws:sns:us-east-1:111122223333:Alerts, arn:aws:sns:us-east-1:111122223333:alerts, DENY",
            "ArnLike, arn:aws:sns:us-east-1:111122223333:Alerts, arn:aws:sns:us-east-1:111122223333:alerts, DENY",
            "ArnNotEquals, arn:aws:sns:us-east-1:111122223333:Alerts, arn:aws:sns:us-east-1:111122223333:alerts, ALLOW",
            "ArnNotLike, arn:aws:sns:us-east-1:111122223333:Alerts, arn:aws:sns:us-east-1:111122223333:alerts, ALLOW",
            "ArnNotLike, arn:aws:sns:us-east-1:111122223333:Alerts, arn:aws:sns:us-east-1:111122223333:Alerts, DENY",
            "ArnEquals, arn:aws:sns:*:111122223333:Alert?, arn:aws:sns:us-east-1:111122223333:Alerts, ALLOW",
            "ArnLike, arn:aws:lambda:us-east-1:*:worker, arn:aws:lambda:us-east-1:111122223333:function:worker, DENY",
            "ArnNotLike, arn:aws:lambda:us-east-1:*:worker, arn:aws:lambda:us-east-1:111122223333:function:worker, ALLOW",
            "ArnLike, arn:aws:lambda:us-east-1:*:function:worker:*, arn:aws:lambda:us-east-1:111122223333:function:worker:live, ALLOW",
            "ArnLike, arn:aws-*:sns:*:*:Alert?, arn:aws-us-gov:sns:us-gov-west-1:111122223333:Alerts, ALLOW",
            "ArnLike, arn:aws:sns:*:*:Alert?, arn:aws:sns:us-east-1:111122223333:Alert, DENY"
    })
    void conditionMatchingPreservesCaseAndArnComponents(String operator, String pattern,
                                                       String value, Decision expected) {
        assertEquals(expected, decision(operator, List.of(pattern), List.of(value)));
    }

    @Test
    void setOperatorsRemainCaseSensitive() {
        assertEquals(Decision.DENY, decision("ForAllValues:StringLike",
                List.of("home/Alice/*"), List.of("home/Alice/notes", "home/alice/notes")));
        assertEquals(Decision.ALLOW, decision("ForAnyValue:StringLike",
                List.of("home/Alice/*"), List.of("home/Alice/notes", "home/alice/notes")));
        assertEquals(Decision.DENY, decision("ForAnyValue:ArnLike",
                List.of("arn:aws:sns:*:*:Alerts"), List.of("arn:aws:sns:us-east-1:111122223333:alerts")));
    }

    @Test
    void negatedOperatorsRequireMismatchAgainstEveryPolicyValue() {
        assertEquals(Decision.DENY, decision("ArnNotLike",
                List.of("arn:aws:sns:*:*:Alerts", "arn:aws:sns:*:*:alerts"),
                List.of("arn:aws:sns:us-east-1:111122223333:alerts")));
        assertEquals(Decision.ALLOW, decision("ArnNotLike",
                List.of("arn:aws:sns:*:*:Alerts", "arn:aws:sns:*:*:Warnings"),
                List.of("arn:aws:sns:us-east-1:111122223333:alerts")));
    }

    @Test
    void publicPrincipalGlobRemainsCaseInsensitive() {
        assertTrue(IamPolicyEvaluator.globMatches("arn:aws:iam::*:user/Alice", "arn:aws:iam::111122223333:user/alice"));
    }

    private Decision decision(String operator, List<String> patterns, List<String> values) {
        String policy;
        try {
            policy = new ObjectMapper().writeValueAsString(Map.of(
                    "Version", "2012-10-17",
                    "Statement", List.of(Map.of("Effect", "Allow", "Action", "s3:GetObject", "Resource", "*",
                            "Condition", Map.of(operator, Map.of("aws:SourceArn", patterns))))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return evaluator.simulateCustomPolicy(List.of(policy), "s3:GetObject", "*",
                Map.of("aws:SourceArn", values));
    }
}
