package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code AWS::DSQL::Cluster}, measured on CloudFormation (eu-west-1, 2026-10-16 and 2026-10-20):
 * Ref is the identifier, the read-only properties are attributes, a cluster created without
 * DeletionProtectionEnabled is protected, and deleting the stack then fails.
 */
@QuarkusTest
class CloudFormationDsqlIntegrationTest {

    private static final String TEMPLATE = """
            {"Resources": {"Cluster": {"Type": "AWS::DSQL::Cluster", "Properties": %s}},
             "Outputs": {
               "Ref": {"Value": {"Ref": "Cluster"}},
               "Identifier": {"Value": {"Fn::GetAtt": ["Cluster", "Identifier"]}},
               "ResourceArn": {"Value": {"Fn::GetAtt": ["Cluster", "ResourceArn"]}},
               "Status": {"Value": {"Fn::GetAtt": ["Cluster", "Status"]}},
               "Endpoint": {"Value": {"Fn::GetAtt": ["Cluster", "Endpoint"]}},
               "VpcEndpoint": {"Value": {"Fn::GetAtt": ["Cluster", "VpcEndpoint"]}},
               "VpcEndpointServiceName": {"Value": {"Fn::GetAtt": ["Cluster", "VpcEndpointServiceName"]}},
               "CreationTime": {"Value": {"Fn::GetAtt": ["Cluster", "CreationTime"]}}}}
            """;

    @Test
    void aClusterGetsItsReferenceAndAttributes() {
        createStack("dsql-cluster", TEMPLATE.formatted("""
                {"DeletionProtectionEnabled": false, "Tags": [{"Key": "team", "Value": "data"}]}"""));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("dsql-cluster").status());

        Map<String, String> outputs = outputs("dsql-cluster");
        String identifier = outputs.get("Ref");
        assertThat(identifier, matchesPattern("[a-z2-7]{26}"));
        assertEquals(identifier, outputs.get("Identifier"));
        assertEquals("arn:aws:dsql:us-east-1:000000000000:cluster/" + identifier, outputs.get("ResourceArn"));
        assertEquals("ACTIVE", outputs.get("Status"));
        assertEquals(identifier + ".dsql.us-east-1.localhost.floci.io", outputs.get("Endpoint"));
        assertThat(outputs.get("VpcEndpoint"), matchesPattern(identifier + "\\.dsql-[a-z2-7]{4}\\.us-east-1\\..+"));
        assertThat(outputs.get("VpcEndpointServiceName"), matchesPattern("com\\.amazonaws\\.us-east-1\\.dsql-[a-z2-7]{4}"));
        assertThat(outputs.get("CreationTime"), matchesPattern("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack").formParam("StackName", "dsql-cluster")
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted("dsql-cluster");
    }

    @Test
    void aProtectedClusterKeepsItsStackFromBeingDeleted() {
        createStack("dsql-protected", TEMPLATE.formatted("{}"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("dsql-protected").status());

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack").formParam("StackName", "dsql-protected")
                .when().post("/").then().statusCode(200);
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal("dsql-protected");
        assertEquals("DELETE_FAILED", state.status());
        assertThat(state.reason(), containsString("failed to delete"));
    }

    /** AWS::NoValue leaves a property out: the cluster is protected by default and has no policy. */
    @Test
    void aPropertyLeftOutByNoValueTakesItsDefault() {
        createStack("dsql-novalue", """
                {"Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
                 "Resources": {"Cluster": {"Type": "AWS::DSQL::Cluster", "Properties": {
                   "DeletionProtectionEnabled": {"Fn::If": ["Never", false, {"Ref": "AWS::NoValue"}]},
                   "PolicyDocument": {"Fn::If": ["Never", "{}", {"Ref": "AWS::NoValue"}]}}}},
                 "Outputs": {"Ref": {"Value": {"Ref": "Cluster"}}}}""");
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("dsql-novalue").status());
        String identifier = outputs("dsql-novalue").get("Ref");
        String auth = "AWS4-HMAC-SHA256 Credential=test/20261016/us-east-1/dsql/aws4_request, SignedHeaders=host, Signature=abc";

        given().header("Authorization", auth).get("/cluster/" + identifier).then().statusCode(200)
                .body("deletionProtectionEnabled", equalTo(true));
        given().header("Authorization", auth).get("/cluster/" + identifier + "/policy").then().statusCode(404);

        given().header("Authorization", auth).contentType("application/json").body(Map.of("deletionProtectionEnabled", false))
                .post("/cluster/" + identifier).then().statusCode(200);
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack").formParam("StackName", "dsql-novalue")
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted("dsql-novalue");
    }

    /**
     * Measured (eu-west-1, 2026-10-20, stack floci-measure-dsql-update): the tags follow the
     * template, a policy left as it was keeps its version and a changed one gets a new one, and a
     * template that sets nothing removes the tags and the policy but leaves deletion protection as
     * it was rather than back at its default.
     */
    @Test
    void anUpdateLeavesWhatTheTemplateNoLongerSetsButTheTagsAndThePolicy() {
        String stack = "dsql-update";
        createStack(stack, updatable("""
                {"DeletionProtectionEnabled": "FALSE", "Tags": [{"Key": "a", "Value": "1"}, {"Key": "b", "Value": "2"}],
                 "PolicyDocument": %s}""".formatted(policy("One")), true));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        String identifier = outputs(stack).get("Ref");
        String first = outputs(stack).get("PolicyVersion");

        updateStack(stack, updatable("""
                {"DeletionProtectionEnabled": false, "Tags": [{"Key": "a", "Value": "3"}], "PolicyDocument": %s}"""
                .formatted(policy("One")), true));
        assertEquals(first, outputs(stack).get("PolicyVersion"));
        dsql().get("/cluster/" + identifier).then().statusCode(200).body("tags", equalTo(Map.of("a", "3")));

        updateStack(stack, updatable("""
                {"DeletionProtectionEnabled": false, "Tags": [{"Key": "a", "Value": "3"}], "PolicyDocument": %s}"""
                .formatted(policy("Two")), true));
        assertThat(Long.parseLong(outputs(stack).get("PolicyVersion")), greaterThan(Long.parseLong(first)));

        updateStack(stack, updatable("{}", false));
        dsql().get("/cluster/" + identifier).then().statusCode(200)
                .body("deletionProtectionEnabled", equalTo(false))
                .body("tags", nullValue());
        dsql().get("/cluster/" + identifier + "/policy").then().statusCode(404);

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack").formParam("StackName", stack)
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    /** Measured: the schema's types are checked before the cluster is created. */
    @Test
    void aPolicyDocumentThatIsNoTextFailsTheResource() {
        createStack("dsql-object-policy", TEMPLATE.formatted("""
                {"DeletionProtectionEnabled": false, "PolicyDocument": {"Version": "2012-10-17"}}"""));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal("dsql-object-policy");
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(CfnStackWaits.failedResourceReason("dsql-object-policy"),
                containsString("Property [PolicyDocument] expected type: String, found: JSONObject"));
    }

    private static String updatable(String properties, boolean policyVersion) {
        return """
                {"Resources": {"Cluster": {"Type": "AWS::DSQL::Cluster", "Properties": %s}},
                 "Outputs": {"Ref": {"Value": {"Ref": "Cluster"}}%s}}"""
                .formatted(properties, policyVersion
                        ? ", \"PolicyVersion\": {\"Value\": {\"Fn::GetAtt\": [\"Cluster\", \"PolicyVersion\"]}}" : "");
    }

    /** A Deny of connections from outside one VPC, as text, the type the schema gives PolicyDocument. */
    private static String policy(String sid) {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"" + sid + "\",\"Effect\":\"Deny\","
                + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\",\"Condition\":"
                + "{\"StringNotEquals\":{\"aws:SourceVpc\":\"vpc-0123456789abcdef0\"}}}]}";
        return "\"" + document.replace("\"", "\\\"") + "\"";
    }

    private static void updateStack(String name, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "UpdateStack").formParam("StackName", name).formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());
    }

    private static RequestSpecification dsql() {
        return given().header("Authorization",
                "AWS4-HMAC-SHA256 Credential=test/20261016/us-east-1/dsql/aws4_request, SignedHeaders=host, Signature=abc");
    }

    private static void createStack(String name, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack").formParam("StackName", name).formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }

    private static Map<String, String> outputs(String name) {
        String xml = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks").formParam("StackName", name)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }
}
