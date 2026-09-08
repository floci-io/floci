package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class CloudFormationListIntrinsicsIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void conditionalTagsAndJoinedListsSurviveStackCreationAndUpdates() {
        String name = "cfn-lists-" + Long.toString(System.nanoTime(), 36);
        String template = """
                {
                  "Parameters": {"UseTags": {"Type": "String"}},
                  "Conditions": {"Tagged": {"Fn::Equals": [{"Ref": "UseTags"}, "true"]}},
                  "Resources": {
                    "Stream": {
                      "Type": "AWS::Kinesis::Stream",
                      "Properties": {"Name": "%1$s", "ShardCount": 1,
                        "Tags": {"Fn::If": ["Tagged", [{"Key": "region", "Value": {"Ref": "AWS::Region"}}], []]}}
                    },
                    "Table": {
                      "Type": "AWS::DynamoDB::Table",
                      "Properties": {"TableName": "%1$s", "BillingMode": "PAY_PER_REQUEST",
                        "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                        "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                        "Tags": {"Fn::If": ["Tagged", [{"Key": "region", "Value": {"Ref": "AWS::Region"}}], []]}}
                    },
                    "Group": {
                      "Type": "AWS::Scheduler::ScheduleGroup",
                      "Properties": {"Name": "%1$s",
                        "Tags": {"Fn::If": ["Tagged", [{"Key": "region", "Value": {"Ref": "AWS::Region"}}], []]}}
                    }
                  },
                  "Outputs": {
                    "JoinedIf": {"Value": {"Fn::Join": [",", {"Fn::If": ["Tagged", ["a", "b"], ["c"]]}]}},
                    "JoinedSplit": {"Value": {"Fn::Join": ["|", {"Fn::Split": [",", "x,,y,"]}]}}
                  }
                }
                """.formatted(name);

        try {
            for (boolean tagged : List.of(true, false)) {
                given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", tagged ? "CreateStack" : "UpdateStack")
                    .formParam("StackName", name)
                    .formParam("TemplateBody", template)
                    .formParam("Parameters.member.1.ParameterKey", "UseTags")
                    .formParam("Parameters.member.1.ParameterValue", Boolean.toString(tagged))
                .when().post("/")
                .then().statusCode(200);

                given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DescribeStacks")
                    .formParam("StackName", name)
                .when().post("/")
                .then().statusCode(200)
                    .body(containsString(tagged ? "CREATE_COMPLETE" : "UPDATE_COMPLETE"))
                    .body(containsString(tagged ? "<OutputValue>a,b</OutputValue>" : "<OutputValue>c</OutputValue>"))
                    .body(containsString("<OutputValue>x||y|</OutputValue>"));

                List<Map<String, String>> expected = tagged
                        ? List.of(Map.of("Key", "region", "Value", "us-east-1")) : List.of();
                given()
                    .contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", "Kinesis_20131202.ListTagsForStream")
                    .body(Map.of("StreamName", name))
                .when().post("/")
                .then().statusCode(200).body("Tags", equalTo(expected));

                given()
                    .contentType("application/x-amz-json-1.0")
                    .header("X-Amz-Target", "DynamoDB_20120810.ListTagsOfResource")
                    .body(Map.of("ResourceArn", "arn:aws:dynamodb:us-east-1:000000000000:table/" + name))
                .when().post("/")
                .then().statusCode(200).body("Tags", equalTo(expected));

                given()
                    .queryParam("resourceArn", "arn:aws:scheduler:us-east-1:000000000000:schedule-group/" + name)
                .when().get("/tags")
                .then().statusCode(200).body("Tags", equalTo(expected));
            }
        } finally {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", name)
            .when().post("/")
            .then().statusCode(200);
        }
    }
}
