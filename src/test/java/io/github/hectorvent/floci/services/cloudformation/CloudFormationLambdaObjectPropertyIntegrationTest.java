package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnRequests;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * An object property of {@code AWS::Lambda::Function} that is not an object. Measured on AWS
 * (eu-west-1, 2026-10-18): a VpcConfig, DeadLetterConfig or LoggingConfig that is a string, a number,
 * a boolean or a list fails the stack before the function is touched, on create and on update,
 * with "Property [VpcConfig] expected type: JSONObject, found: String" ("found: JSONArray" for a
 * list). It is never read as an absent property, which would reset the function's setting.
 */
@QuarkusTest
class CloudFormationLambdaObjectPropertyIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String LAMBDA = "/2015-03-31/functions/";
    /** A String, a Number and a CommaDelimitedList parameter, and a condition that holds. */
    private static final String LIST_SECTIONS = """
            "Parameters": {
              "Str": {"Type": "String", "Default": "x86_64"},
              "Num": {"Type": "Number", "Default": "1"},
              "Csv": {"Type": "CommaDelimitedList", "Default": "x86_64"}
            },
            "Conditions": {"Yes": {"Fn::Equals": ["a", "a"]}},
            """;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aPropertyThatIsNotAnObjectFailsTheCreate() {
        String vpc = stackName("vpc");
        submit("CreateStack", vpc, template(",\"VpcConfig\": 5"));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(vpc, CFN_AUTH).status());
        assertEventReason(vpc, "Property [VpcConfig] expected type: JSONObject, found: String");

        String deadLetter = stackName("dlq");
        submit("CreateStack", deadLetter, template(",\"DeadLetterConfig\": [\"x\"]"));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(deadLetter, CFN_AUTH).status());
        assertEventReason(deadLetter, "Property [DeadLetterConfig] expected type: JSONObject, found: JSONArray");
    }

    @Test
    void aPropertyThatIsNotAnObjectFailsTheUpdateAndKeepsTheSetting() {
        String stack = stackName("update");
        submit("CreateStack", stack, template(",\"TracingConfig\": {\"Mode\": \"Active\"}"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        String function = functionName(stack);

        submit("UpdateStack", stack, template(",\"TracingConfig\": \"Active\""));
        assertNotEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        assertEventReason(stack, "Property [TracingConfig] expected type: JSONObject, found: String");
        given()
        .when()
            .get(LAMBDA + function + "/configuration")
        .then()
            .statusCode(200)
            .body("TracingConfig.Mode", equalTo("Active"));
    }

    /**
     * Measured on AWS (eu-west-1, 2026-10-18): {@code Ref: AWS::NoValue}, directly or chosen by
     * {@code Fn::If}, leaves the property out, object and list properties alike, and the stack is
     * created.
     */
    @Test
    void aPropertyLeftOutWithNoValueIsNotRefused() {
        String direct = stackName("novalue");
        submit("CreateStack", direct, template(
                ",\"VpcConfig\": {\"Ref\": \"AWS::NoValue\"}, \"TracingConfig\": {\"Ref\": \"AWS::NoValue\"}"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(direct, CFN_AUTH).status());

        String conditional = stackName("novalue-if");
        submit("CreateStack", conditional, """
                {
                  "Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        "DeadLetterConfig": {"Fn::If": ["Never",
                            {"TargetArn": "arn:aws:sqs:us-east-1:000000000000:none"}, {"Ref": "AWS::NoValue"}]}
                      }
                    }
                  }
                }
                """);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(conditional, CFN_AUTH).status());

        String lists = stackName("novalue-lists");
        submit("CreateStack", lists, template(",\"Layers\": {\"Ref\": \"AWS::NoValue\"}, "
                + "\"Architectures\": {\"Ref\": \"AWS::NoValue\"}, \"Environment\": {\"Ref\": \"AWS::NoValue\"}, "
                + "\"ImageConfig\": {\"Ref\": \"AWS::NoValue\"}"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(lists, CFN_AUTH).status());

        String conditionalList = stackName("novalue-if-list");
        submit("CreateStack", conditionalList, """
                {
                  "Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        "FileSystemConfigs": {"Fn::If": ["Never", [{"LocalMountPath": "/mnt/x",
                            "Arn": "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0"}],
                            {"Ref": "AWS::NoValue"}]}
                      }
                    }
                  }
                }
                """);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(conditionalList, CFN_AUTH).status());
    }

    /**
     * Measured on AWS (eu-west-1, 2026-10-18): a {@code Ref} to a {@code CommaDelimitedList}
     * parameter and {@code Fn::Split} are lists, so the stack is created with them; the engine
     * resolves such a {@code Ref} to the comma-joined text, which is not a value of the wrong type.
     */
    @Test
    void aListPropertyFromACommaDelimitedListOrFnSplitIsAList() throws IOException {
        String layerArn = publishLayer();

        String delimited = stackName("comma-delimited");
        submit("CreateStack", delimited, """
                {
                  "Parameters": {
                    "LayerArns": {"Type": "CommaDelimitedList", "Default": "%s"},
                    "Archs": {"Type": "CommaDelimitedList", "Default": "arm64"}
                  },
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        "Layers": {"Ref": "LayerArns"},
                        "Architectures": {"Ref": "Archs"}
                      }
                    }
                  },
                  "Outputs": {"Name": {"Value": {"Ref": "Fn"}}}
                }
                """.formatted(layerArn));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(delimited, CFN_AUTH).status());
        given().when().get(LAMBDA + functionName(delimited) + "/configuration")
            .then().statusCode(200)
            .body("Layers.Arn", contains(layerArn))
            .body("Architectures", contains("arm64"));

        String split = stackName("fn-split");
        submit("CreateStack", split, """
                {
                  "Parameters": {"LayerArn": {"Type": "String", "Default": "%s"}},
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        "Layers": {"Fn::Split": [",", {"Ref": "LayerArn"}]}
                      }
                    }
                  },
                  "Outputs": {"Name": {"Value": {"Ref": "Fn"}}}
                }
                """.formatted(layerArn));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(split, CFN_AUTH).status());
        given().when().get(LAMBDA + functionName(split) + "/configuration")
            .then().statusCode(200)
            .body("Layers.Arn", contains(layerArn));
    }

    /**
     * The other readers of the function and of its layers refuse a value of the wrong type the same
     * way, nested members included, each with the message measured for it on AWS (eu-west-1,
     * 2026-10-18 and 2026-10-19).
     */
    @Test
    void everyOtherPropertyOfTheWrongTypeFailsTheCreate() {
        Map<String, String> functionProperties = new LinkedHashMap<>();
        functionProperties.put(",\"Environment\": [\"x\"]",
                "Property [Environment] expected type: JSONObject, found: JSONArray");
        functionProperties.put(",\"Environment\": {\"Variables\": \"x\"}",
                "Property [Variables] expected type: JSONObject, found: String");
        functionProperties.put(",\"ImageConfig\": \"x\"",
                "Property [ImageConfig] expected type: JSONObject, found: String");
        functionProperties.put(",\"Layers\": {\"a\": \"b\"}",
                "Property [Layers] expected type: JSONArray, found: JSONObject");
        functionProperties.put(",\"Layers\": 5",
                "Property [Layers] expected type: JSONArray, found: String");
        functionProperties.put(",\"Architectures\": \"x86_64\"",
                "Property [Architectures] expected type: JSONArray, found: String");
        functionProperties.put(",\"FileSystemConfigs\": \"x\"",
                "Property [FileSystemConfigs] expected type: JSONArray, found: String");
        functionProperties.put(",\"VpcConfig\": {\"SubnetIds\": \"subnet-0123456789abcdef0\", \"SecurityGroupIds\": []}",
                "Property [SubnetIds] expected type: JSONArray, found: String");
        functionProperties.put(",\"EphemeralStorage\": {\"Size\": \"abc\"}",
                "Property [Size] expected type: Integer, found: String");
        functionProperties.put(",\"ImageConfig\": {\"Command\": \"x\"}",
                "Property [Command] expected type: JSONArray, found: String");
        functionProperties.put(",\"TracingConfig\": {\"Mode\": {\"a\": 1}}",
                "Property [Mode] expected type: String, found: JSONObject");
        functionProperties.put(",\"Environment\": {\"Variables\": {\"AB\": {\"b\": 1}}}",
                "Property [AB] expected type: String, found: JSONObject");
        functionProperties.put(",\"Environment\": {\"Variables\": {\"AB\": [\"x\"]}}",
                "Property [AB] expected type: String, found: JSONArray");
        functionProperties.forEach((property, reason) -> {
            String stack = stackName("type");
            submit("CreateStack", stack, template(property));
            assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status(), property);
            assertEventReason(stack, reason);
        });

        Map<String, String> layerProperties = Map.of(
                "\"CompatibleRuntimes\": \"python3.14\"",
                "Property [CompatibleRuntimes] expected type: JSONArray, found: String",
                "\"CompatibleArchitectures\": {\"a\": \"b\"}",
                "Property [CompatibleArchitectures] expected type: JSONArray, found: JSONObject");
        layerProperties.forEach((property, reason) -> {
            String stack = stackName("layer-type");
            submit("CreateStack", stack, """
                    {"Resources": {"L": {"Type": "AWS::Lambda::LayerVersion", "Properties": {
                      "Content": {"S3Bucket": "none", "S3Key": "none.zip"}, %s}}}}
                    """.formatted(property));
            assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status(), property);
            assertEventReason(stack, reason);
        });
    }

    /**
     * Measured on CloudFormation (eu-west-1, 2026-10-18): where a list is expected, a Ref to a String
     * or Number parameter, Ref AWS::Region, Fn::Select, Fn::Join, Fn::Sub, Fn::Base64 and an Fn::If
     * choosing a literal string fail validation with "found: String", while an Fn::If choosing a Ref to a
     * CommaDelimitedList is a list. A nested stack's own parameter types decide for its resources.
     */
    @Test
    void aListPropertyThatYieldsAStringFailsTheCreate() {
        for (String architectures : List.of("{\"Ref\": \"Str\"}", "{\"Ref\": \"Num\"}", "{\"Ref\": \"AWS::Region\"}",
                "{\"Fn::Select\": [0, {\"Ref\": \"Csv\"}]}", "{\"Fn::Join\": [\",\", {\"Ref\": \"Csv\"}]}",
                "{\"Fn::Sub\": \"x86_64\"}", "{\"Fn::Base64\": \"x86_64\"}",
                "{\"Fn::If\": [\"Yes\", \"x86_64\", {\"Ref\": \"AWS::NoValue\"}]}")) {
            String stack = stackName("yields-string");
            submit("CreateStack", stack, template(LIST_SECTIONS, ",\"Architectures\": " + architectures));
            assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status(), architectures);
            assertEventReason(stack, "Property [Architectures] expected type: JSONArray, found: String");
        }

        String listChosen = stackName("if-list");
        submit("CreateStack", listChosen, template(LIST_SECTIONS,
                ",\"Architectures\": {\"Fn::If\": [\"Yes\", {\"Ref\": \"Csv\"}, [\"arm64\"]]}"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(listChosen, CFN_AUTH).status());
        given().when().get(LAMBDA + functionName(listChosen) + "/configuration")
            .then().statusCode(200)
            .body("Architectures", contains("x86_64"));

        String bucket = "cfn-lambda-nested-" + Long.toString(System.nanoTime(), 36);
        given().when().put("/" + bucket).then().statusCode(200);
        cleanup.register(() -> {
            given().when().delete("/" + bucket + "/string.json");
            given().when().delete("/" + bucket + "/list.json");
            given().when().delete("/" + bucket);
        });
        for (String type : List.of("String", "CommaDelimitedList")) {
            String child = """
                    {"Parameters": {"Archs": {"Type": "%s"}},
                     "Resources": {"Fn": {"Type": "AWS::Lambda::Function", "Properties": {
                       "Runtime": "python3.14", "Handler": "index.handler",
                       "Role": "arn:aws:iam::000000000000:role/lambda-role",
                       "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                       "Architectures": {"Ref": "Archs"}}}},
                     "Outputs": {"Name": {"Value": {"Ref": "Fn"}}}}
                    """.formatted(type);
            String key = "String".equals(type) ? "string.json" : "list.json";
            given().contentType("application/json").body(child)
                .when().put("/" + bucket + "/" + key).then().statusCode(200);
            String parent = stackName("nested-" + key.replace(".json", ""));
            submit("CreateStack", parent, """
                    {"Resources": {"Child": {"Type": "AWS::CloudFormation::Stack", "Properties": {
                      "TemplateURL": "http://localhost/%s/%s", "Parameters": {"Archs": "x86_64"}}}}}
                    """.formatted(bucket, key));
            assertEquals("String".equals(type) ? "ROLLBACK_COMPLETE" : "CREATE_COMPLETE",
                    CfnStackWaits.awaitTerminal(parent, CFN_AUTH).status(), type);
            String childStack = CfnRequests.physicalId(parent, "Child", CFN_AUTH);
            if ("String".equals(type)) {
                assertEventReason(childStack, "Property [Architectures] expected type: JSONArray, found: String");
            } else {
                given().when().get(LAMBDA + functionName(childStack) + "/configuration")
                    .then().statusCode(200)
                    .body("Architectures", contains("x86_64"));
            }
        }
    }

    private String publishLayer() throws IOException {
        String layerName = "cfn-lambda-list-" + Long.toString(System.nanoTime(), 36);
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream entries = new ZipOutputStream(zip)) {
            entries.putNextEntry(new ZipEntry("python/shared.py"));
            entries.write("VALUE = 1".getBytes(StandardCharsets.UTF_8));
            entries.closeEntry();
        }
        String arn = given()
            .contentType("application/json")
            .body("{\"Content\": {\"ZipFile\": \"" + Base64.getEncoder().encodeToString(zip.toByteArray()) + "\"}}")
        .when()
            .post("/2018-10-31/layers/" + layerName + "/versions")
        .then()
            .statusCode(201)
            .extract().path("LayerVersionArn");
        cleanup.register(() -> given().when().delete("/2018-10-31/layers/" + layerName + "/versions/1"));
        return arn;
    }

    private String stackName(String purpose) {
        String name = "cfn-lambda-object-" + purpose + "-" + Long.toString(System.nanoTime(), 36);
        cleanup.register(() -> {
            submit("DeleteStack", name, null);
            CfnStackWaits.awaitStackDeleted(name, CFN_AUTH);
        });
        return name;
    }

    private static String template(String property) {
        return template("", property);
    }

    /** {@link #template(String)} after the template sections {@code sections}, each followed by a comma. */
    private static String template(String sections, String property) {
        return """
                {
                  %s
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"}
                        %s
                      }
                    }
                  },
                  "Outputs": {"Name": {"Value": {"Ref": "Fn"}}}
                }
                """.formatted(sections, property);
    }

    private static void submit(String action, String stackName, String template) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void assertEventReason(String stackName, String reason) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(reason));
    }

    private static String functionName(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.name() == 'OutputValue' }");
    }
}
