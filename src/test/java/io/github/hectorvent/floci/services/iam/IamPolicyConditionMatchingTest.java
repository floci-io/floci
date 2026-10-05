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

    @ParameterizedTest
    @CsvSource({
            "203.0.113.0/24, 203.0.113.42, ALLOW",
            "203.0.113.0/24, 203.0.114.42, DENY",
            "203.0.113.129/25, 203.0.113.255, ALLOW",
            "203.0.113.129/25, 203.0.113.127, DENY",
            "0.0.0.0/0, 203.0.113.42, ALLOW",
            "203.0.113.42/32, 203.0.113.42, ALLOW",
            "203.0.113.42/32, 203.0.113.43, DENY",
            "2001:db8::/32, 2001:db8::42, ALLOW",
            "2001:db8::/32, 2001:db9::42, DENY",
            "2001:db8::1234/33, 2001:db8:7fff::42, ALLOW",
            "2001:db8::1234/33, 2001:db8:8000::42, DENY",
            "::/0, 2001:db8::42, ALLOW",
            "2001:db8::42/128, 2001:0db8:0:0:0:0:0:0042, ALLOW",
            "2001:db8::42/128, 2001:db8::43, DENY",
            "2001:db8::42/127, 2001:db8::43, ALLOW",
            "2001:db8::42/127, 2001:db8::44, DENY",
            "::/0, 203.0.113.42, DENY",
            "0.0.0.0/0, 2001:db8::42, DENY",
            "2001:db8::/129, 2001:db8::42, DENY",
            "2001:db8::/-1, 2001:db8::42, DENY",
            "203.0.113.0/33, 203.0.113.42, DENY",
            "203.0.113.0/-1, 203.0.113.42, DENY",
            "999.0.0.0/0, 203.0.113.42, DENY",
            "example.com/0, 203.0.113.42, DENY",
            "2001:db8::/32/extra, 2001:db8::42, DENY",
            "2001:db8::/32, invalid, DENY"
    })
    void ipConditionsMatchAddressFamilyAndPrefix(String range, String sourceIp, Decision expected) {
        assertEquals(expected, decision("IpAddress", List.of(range), List.of(sourceIp)));
        Decision negated = expected == Decision.ALLOW ? Decision.DENY : Decision.ALLOW;
        assertEquals(negated, decision("NotIpAddress", List.of(range), List.of(sourceIp)));
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
        String conditionKey = operator.endsWith("IpAddress") ? "aws:SourceIp" : "aws:SourceArn";
        String policy;
        try {
            policy = new ObjectMapper().writeValueAsString(Map.of(
                    "Version", "2012-10-17",
                    "Statement", List.of(Map.of("Effect", "Allow", "Action", "s3:GetObject", "Resource", "*",
                            "Condition", Map.of(operator, Map.of(conditionKey, patterns))))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return evaluator.simulateCustomPolicy(List.of(policy), "s3:GetObject", "*",
                Map.of(conditionKey, values));
    }
}
