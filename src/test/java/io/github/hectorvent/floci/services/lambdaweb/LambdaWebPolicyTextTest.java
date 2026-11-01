package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LambdaWebPolicyTextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT = "arn:aws:iam::111122223333:root";
    private static final String ENDPOINTS = "arn:aws:lambda:us-east-1:111122223333:web-function/app/endpoint/*";
    private static final String GRANT = "'Principal':{'AWS':'" + ROOT + "'},'Action':'lambda:InvokeWebFunctionEndpoint',"
            + "'Resource':'" + ENDPOINTS + "'";

    @Test
    void aPolicyIsWrittenCompactWithItsElementsInAFixedOrder() throws Exception {
        String expected = json("{'Version':'2012-10-17','Statement':[{'Effect':'Allow'," + GRANT + "}]}");
        assertEquals(expected, kept(json("{'Statement':[{'Action':'lambda:InvokeWebFunctionEndpoint','Effect':'Allow',"
                + "'Principal':{'AWS':'" + ROOT + "'},'Resource':'" + ENDPOINTS + "'}],'Version':'2012-10-17'}")));
        assertEquals(expected, kept(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(expected))));
        assertEquals(json("{'Version':'2012-10-17','Id':'i','Statement':[{'Sid':'s1','Effect':'Allow'," + GRANT
                        + ",'Condition':{'StringEquals':{'aws:SourceAccount':'111122223333'}}}]}"),
                kept(json("{'Statement':[{'Condition':{'StringEquals':{'aws:SourceAccount':'111122223333'}},'Resource':'"
                        + ENDPOINTS + "','Action':'lambda:InvokeWebFunctionEndpoint','Principal':{'AWS':'" + ROOT + "'},"
                        + "'Effect':'Allow','Sid':'s1'}],'Id':'i','Version':'2012-10-17'}")));
    }

    @Test
    void aMissingVersionIsTheOlderOneAndAStatementObjectIsAListOfOne() {
        assertEquals(json("{'Version':'2008-10-17','Statement':[{'Effect':'Deny'," + GRANT + "}]}"),
                kept(json("{'Statement':{'Effect':'deny'," + GRANT + "}}")));
    }

    @Test
    void aListOfOneIsWrittenAsItsValueAndLongerListsKeepTheirRepeats() {
        assertEquals(json("{'Version':'2012-10-17','Statement':[{'Effect':'Allow'," + GRANT
                        + ",'Condition':{'StringEquals':{'aws:UserAgent':'x'}}}]}"),
                kept(json("{'Version':'2012-10-17','Statement':[{'Effect':'Allow','Principal':{'AWS':['" + ROOT + "']},"
                        + "'Action':['lambda:InvokeWebFunctionEndpoint'],'Resource':['" + ENDPOINTS + "'],"
                        + "'Condition':{'StringEquals':{'aws:UserAgent':['x']}}}]}")));
        String repeated = json("{'Version':'2012-10-17','Statement':[{'Effect':'Allow',"
                + "'Principal':{'AWS':['111122223333','" + ROOT + "','" + ROOT + "']},"
                + "'Action':['lambda:InvokeWebFunctionEndpoint','lambda:InvokeWebFunctionEndpoint'],"
                + "'Resource':['" + ENDPOINTS + "','" + ENDPOINTS + "'],'Condition':{'StringEquals':{'aws:UserAgent':['b','a','b']}}}]}");
        assertEquals(repeated, kept(repeated));
    }

    @Test
    void conditionValuesAreTextWithNumbersAsWritten() {
        assertEquals(json("{'Version':'2012-10-17','Statement':[{'Effect':'Deny'," + GRANT + ",'Condition':"
                        + "{'NumericLessThan':{'aws:MultiFactorAuthAge':['1.50','1E+3','-2','12345678901234567890']}}},"
                        + "{'Effect':'Deny'," + GRANT + ",'Condition':{'Bool':{'aws:SecureTransport':'false'}}}]}"),
                kept(json("{'Version':'2012-10-17','Statement':[{'Effect':'Deny'," + GRANT + ",'Condition':"
                        + "{'NumericLessThan':{'aws:MultiFactorAuthAge':[1.50,1e3,-2,12345678901234567890]}}},"
                        + "{'Effect':'Deny'," + GRANT + ",'Condition':{'Bool':{'aws:SecureTransport':false}}}]}")));
    }

    @Test
    void operatorsComeInTheOrderAwsGivesThemWhateverOrderTheyAreWrittenIn() {
        Map<List<String>, List<String>> written = Map.of(
                List.of("StringEquals", "StringNotEquals", "StringEqualsIgnoreCase", "StringNotEqualsIgnoreCase",
                        "StringLike", "StringNotLike", "NumericEquals", "NumericNotEquals", "NumericLessThan",
                        "NumericLessThanEquals", "NumericGreaterThan", "NumericGreaterThanEquals", "DateEquals",
                        "DateNotEquals", "DateLessThan", "DateLessThanEquals", "DateGreaterThan", "DateGreaterThanEquals",
                        "Bool", "IpAddress", "NotIpAddress", "ArnEquals", "ArnNotEquals", "ArnLike", "ArnNotLike", "Null"),
                List.of("StringEquals", "Null", "ArnNotEquals", "NumericGreaterThan", "NumericLessThan",
                        "DateLessThanEquals", "NumericEquals", "DateLessThan", "StringNotLike", "Bool",
                        "StringEqualsIgnoreCase", "DateEquals", "DateGreaterThan", "ArnLike", "ArnEquals",
                        "NumericNotEquals", "NumericLessThanEquals", "StringLike", "StringNotEquals",
                        "StringNotEqualsIgnoreCase", "DateGreaterThanEquals", "DateNotEquals", "NotIpAddress",
                        "ArnNotLike", "IpAddress", "NumericGreaterThanEquals"),
                List.of("ArnLike", "BoolIfExists", "ForAllValues:ArnLike", "StringEqualsIfExists", "Bool",
                        "ForAnyValue:StringEqualsIfExists", "StringLike", "ForAnyValue:StringEquals", "Null",
                        "ForAllValues:StringEquals", "StringEquals"),
                List.of("StringEquals", "Null", "StringLike", "ForAllValues:StringEquals", "Bool",
                        "ForAnyValue:StringEquals", "ForAllValues:ArnLike", "ForAnyValue:StringEqualsIfExists",
                        "StringEqualsIfExists", "BoolIfExists", "ArnLike"));
        written.forEach((operators, expected) -> {
            ObjectNode condition = MAPPER.createObjectNode();
            operators.forEach(operator -> condition.putObject(operator).put("aws:UserAgent", "x"));
            String policy = json("{'Version':'2012-10-17','Statement':[{'Effect':'Deny'," + GRANT + ",'Condition':")
                    + condition + "}]}";
            assertEquals(expected, fieldNames(statement(kept(policy)).get("Condition")));
            assertEquals(expected, fieldNames(statement(read(kept(policy), "aws")).get("Condition")));
        });
    }

    @Test
    void anOperatorWithoutKeysIsLeftOutButAnEmptyConditionStays() {
        String expected = json("{'Version':'2012-10-17','Statement':[{'Effect':'Deny'," + GRANT + ",'Condition':{}}]}");
        assertEquals(expected, kept(json("{'Version':'2012-10-17','Statement':[{'Effect':'Deny'," + GRANT
                + ",'Condition':{'StringEquals':{}}}]}")));
        assertEquals(expected, kept(expected));
    }

    @Test
    void principalTypesComeInAFixedOrderAndTheirValuesAsWritten() {
        assertEquals(json("{'Version':'2012-10-17','Statement':[{'Effect':'Deny','Principal':{'AWS':'" + ROOT + "',"
                        + "'Federated':'cognito-identity.amazonaws.com','Service':['s3.amazonaws.com',"
                        + "'cloudfront.amazonaws.com','s3.amazonaws.com']},'Action':'lambda:InvokeWebFunctionEndpoint',"
                        + "'Resource':'" + ENDPOINTS + "'}]}"),
                kept(json("{'Version':'2012-10-17','Statement':[{'Effect':'Deny','Principal':{'Service':['s3.amazonaws.com',"
                        + "'cloudfront.amazonaws.com','s3.amazonaws.com'],'Federated':'cognito-identity.amazonaws.com',"
                        + "'AWS':'" + ROOT + "'},'Action':'lambda:InvokeWebFunctionEndpoint','Resource':'" + ENDPOINTS + "'}]}")));
    }

    @Test
    void textIsWrittenWithTheEscapesJsonNeedsOnly() {
        assertEquals("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"" + ROOT
                        + "\"},\"Action\":\"lambda:InvokeWebFunctionEndpoint\",\"Resource\":\"" + ENDPOINTS + "\","
                        + "\"Condition\":{\"StringEquals\":{\"aws:UserAgent\":\"a<b>&'\\\"\\t\\u0001 é😀 /xA\"}}}]}",
                kept("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"" + ROOT
                        + "\"},\"Action\":\"lambda:InvokeWebFunctionEndpoint\",\"Resource\":\""
                        + ENDPOINTS.replace("/", "\\/") + "\",\"Condition\":{\"StringEquals\":{\"aws:UserAgent\":"
                        + "\"a<b>&'\\\"\\t\\u0001\\u2028\\u00e9\\ud83d\\ude00 \\/x\\u0041\"}}}]}"));
    }

    @Test
    void getResourcePolicyNamesAnAccountByItsRootAndDropsRepeatedPrincipals() {
        String kept = kept(json("{'Version':'2012-10-17','Statement':[{'Effect':'Allow','Principal':{'AWS':"
                + "'111122223333'},'Action':'lambda:InvokeWebFunctionEndpoint','Resource':'" + ENDPOINTS + "'},"
                + "{'Effect':'Deny','Principal':{'AWS':['111122223333','" + ROOT + "','arn:aws:iam::111122223333:role/r'],"
                + "'Service':['s3.amazonaws.com','cloudfront.amazonaws.com','s3.amazonaws.com']},"
                + "'Action':'lambda:InvokeWebFunctionEndpoint','Resource':'" + ENDPOINTS + "'}]}"));
        assertEquals(json("{'Version':'2012-10-17','Statement':[{'Effect':'Allow','Principal':{'AWS':'" + ROOT + "'},"
                        + "'Action':'lambda:InvokeWebFunctionEndpoint','Resource':'" + ENDPOINTS + "'},"
                        + "{'Effect':'Deny','Principal':{'AWS':['" + ROOT + "','arn:aws:iam::111122223333:role/r'],"
                        + "'Service':['s3.amazonaws.com','cloudfront.amazonaws.com']},"
                        + "'Action':'lambda:InvokeWebFunctionEndpoint','Resource':'" + ENDPOINTS + "'}]}"),
                read(kept, "aws"));
        assertEquals("arn:aws-us-gov:iam::111122223333:root",
                statement(read(kept, "aws-us-gov")).path("Principal").path("AWS").textValue());
    }

    private static String kept(String policy) {
        return LambdaWebPolicyText.kept(MAPPER, policy);
    }

    private static String read(String kept, String partition) {
        return LambdaWebPolicyText.read(MAPPER, kept, partition);
    }

    private static JsonNode statement(String policy) {
        try {
            return MAPPER.readTree(policy).path("Statement").get(0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }
}
