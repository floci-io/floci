package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MetadataOptions declared through CloudFormation reach the instance: an AWS::EC2::LaunchTemplate
 * stores them, an AWS::EC2::Instance launches with its template's options under its own, and an
 * update changes them in place, as the property's "No interruption" update behavior says, and a
 * rollback puts them back. A template with {@code HttpTokens: required} is what CDK's
 * {@code requireImdsv2} generates; the instance then carries it as RunInstances or
 * ModifyInstanceMetadataOptions would have set it.
 */
@QuarkusTest
class CloudFormationEc2MetadataOptionsIntegrationTest {
    private static final String ACCOUNT = "468024680246";
    private static final String REGION = "us-east-1";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.";

    @Test
    void launchTemplateMetadataOptionsReachTheInstance() {
        String stackName = "cfn-metadata-lt-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateData": {
                          "ImageId": "ami-12345678",
                          "InstanceType": "t3.micro",
                          "MetadataOptions": {
                            "HttpTokens": "required",
                            "HttpPutResponseHopLimit": 2,
                            "InstanceMetadataTags": "enabled"
                          }
                        }
                      }
                    },
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "LaunchTemplate": {
                          "LaunchTemplateId": {"Ref": "Lt"},
                          "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "InstanceId": {"Value": {"Ref": "Inst"}},
                    "TemplateId": {"Value": {"Ref": "Lt"}}
                  }
                }
                """;
        try {
            cfn("CreateStack", stackName, template);
            XmlPath stack = describeStack(stackName, "CREATE_COMPLETE");
            String instanceId = output(stack, "InstanceId");

            XmlPath versions = ec2Request("DescribeLaunchTemplateVersions")
                    .formParam("LaunchTemplateId", output(stack, "TemplateId"))
                    .post("/").then().statusCode(200).extract().xmlPath();
            String stored = "DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item"
                    + ".launchTemplateData.metadataOptions.";
            assertEquals("required", versions.getString(stored + "httpTokens"));
            assertEquals("2", versions.getString(stored + "httpPutResponseHopLimit"));
            assertEquals("enabled", versions.getString(stored + "instanceMetadataTags"));

            XmlPath described = describeInstance(instanceId);
            assertEquals("required", described.getString(INSTANCE + "metadataOptions.httpTokens"));
            assertEquals("2", described.getString(INSTANCE + "metadataOptions.httpPutResponseHopLimit"));
            assertEquals("enabled", described.getString(INSTANCE + "metadataOptions.instanceMetadataTags"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    @Test
    void instanceMetadataOptionsOverrideItsTemplateAndUpdateInPlace() {
        String stackName = "cfn-metadata-inst-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateData": {
                          "ImageId": "ami-12345678",
                          "MetadataOptions": {"HttpTokens": "optional", "HttpPutResponseHopLimit": "3"}
                        }
                      }
                    },
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "LaunchTemplate": {
                          "LaunchTemplateId": {"Ref": "Lt"},
                          "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                        },
                        "MetadataOptions": {"HttpTokens": "%s"}
                      }
                    }
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """;
        try {
            cfn("CreateStack", stackName, template.formatted("required"));
            String instanceId = output(describeStack(stackName, "CREATE_COMPLETE"), "InstanceId");
            XmlPath created = describeInstance(instanceId);
            assertEquals("required", created.getString(INSTANCE + "metadataOptions.httpTokens"));
            assertEquals("3", created.getString(INSTANCE + "metadataOptions.httpPutResponseHopLimit"));

            cfn("UpdateStack", stackName, template.formatted("optional"));
            assertEquals(instanceId, output(describeStack(stackName, "UPDATE_COMPLETE"), "InstanceId"));
            XmlPath updated = describeInstance(instanceId);
            assertEquals("optional", updated.getString(INSTANCE + "metadataOptions.httpTokens"));
            assertEquals("3", updated.getString(INSTANCE + "metadataOptions.httpPutResponseHopLimit"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    @Test
    void optionsTheInstanceStopsDeclaringReturnToTheLaunchDefaults() {
        String stackName = "cfn-metadata-unset-" + UUID.randomUUID().toString().substring(0, 8);
        String declared = """
                {
                  "Resources": {
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "ImageId": "ami-12345678",
                        "MetadataOptions": {"HttpTokens": "required", "HttpPutResponseHopLimit": 2}
                      }
                    }
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """;
        String undeclared = """
                {
                  "Resources": {
                    "Inst": {"Type": "AWS::EC2::Instance", "Properties": {"ImageId": "ami-12345678"}}
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """;
        try {
            cfn("CreateStack", stackName, declared);
            String instanceId = output(describeStack(stackName, "CREATE_COMPLETE"), "InstanceId");

            cfn("UpdateStack", stackName, undeclared);
            assertEquals(instanceId, output(describeStack(stackName, "UPDATE_COMPLETE"), "InstanceId"));
            XmlPath updated = describeInstance(instanceId);
            assertEquals("optional", updated.getString(INSTANCE + "metadataOptions.httpTokens"));
            assertEquals("1", updated.getString(INSTANCE + "metadataOptions.httpPutResponseHopLimit"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    @Test
    void removingTheLaunchTemplateKeepsTheInstanceAndResetsItsOptions() {
        String stackName = "cfn-metadata-drop-" + UUID.randomUUID().toString().substring(0, 8);
        String withTemplate = """
                {
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateData": {"ImageId": "ami-12345678", "MetadataOptions": {"HttpTokens": "required"}}
                      }
                    },
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "LaunchTemplate": {
                          "LaunchTemplateId": {"Ref": "Lt"},
                          "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                        }
                      }
                    }
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """;
        // What CDK's requireImdsv2 turned off produces: the template and the reference are gone.
        // AWS replaces the instance, LaunchTemplate being createOnly; Floci keeps it and returns
        // what the template set to the launch defaults.
        String withoutTemplate = """
                {
                  "Resources": {
                    "Inst": {"Type": "AWS::EC2::Instance", "Properties": {"ImageId": "ami-12345678"}}
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """;
        try {
            cfn("CreateStack", stackName, withTemplate);
            String instanceId = output(describeStack(stackName, "CREATE_COMPLETE"), "InstanceId");
            assertEquals("required", describeInstance(instanceId).getString(INSTANCE + "metadataOptions.httpTokens"));

            cfn("UpdateStack", stackName, withoutTemplate);
            assertEquals(instanceId, output(describeStack(stackName, "UPDATE_COMPLETE"), "InstanceId"));
            assertEquals("optional", describeInstance(instanceId).getString(INSTANCE + "metadataOptions.httpTokens"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    /**
     * The instance names {@code Fn::GetAtt LatestVersionNumber}, so updating the template changes
     * its LaunchTemplate property. AWS replaces the instance; Floci keeps it and applies what the
     * new version declares, here no token requirement.
     */
    @Test
    void aNewVersionOfTheLaunchTemplateUpdatesTheInstanceInPlace() {
        String stackName = "cfn-metadata-version-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {"LaunchTemplateData": {"ImageId": "ami-12345678"%s}}
                    },
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "LaunchTemplate": {
                          "LaunchTemplateId": {"Ref": "Lt"},
                          "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                        }
                      }
                    }
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """;
        try {
            cfn("CreateStack", stackName, template.formatted(", \"MetadataOptions\": {\"HttpTokens\": \"required\"}"));
            String instanceId = output(describeStack(stackName, "CREATE_COMPLETE"), "InstanceId");

            cfn("UpdateStack", stackName, template.formatted(""));
            assertEquals(instanceId, output(describeStack(stackName, "UPDATE_COMPLETE"), "InstanceId"));
            assertEquals("optional", describeInstance(instanceId).getString(INSTANCE + "metadataOptions.httpTokens"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    @Test
    void invalidMetadataOptionsFailTheUpdateBeforeTheInstanceChanges() {
        String stackName = "cfn-metadata-invalid-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {
                  "Resources": {
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "ImageId": "ami-12345678",
                        "InstanceType": "%s",
                        "MetadataOptions": {"HttpTokens": "%s"}
                      }
                    }
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """;
        try {
            cfn("CreateStack", stackName, template.formatted("t3.micro", "required"));
            String instanceId = output(describeStack(stackName, "CREATE_COMPLETE"), "InstanceId");

            cfn("UpdateStack", stackName, template.formatted("t3.small", "Required"));
            describeStack(stackName, "UPDATE_ROLLBACK_COMPLETE");
            XmlPath unchanged = describeInstance(instanceId);
            assertEquals("t3.micro", unchanged.getString(INSTANCE + "instanceType"));
            assertEquals("required", unchanged.getString(INSTANCE + "metadataOptions.httpTokens"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    /**
     * An update that changes the instance's MetadataOptions in place, then fails on a later
     * resource, rolls the instance back; the stack ends rolled back, not stuck. That holds both for
     * an update that turns the token requirement off and for one that only declares the default
     * the instance already has, which changes no EC2 setting. For that second row the options are
     * {@code optional} either way; what tells a working rollback apart is the stack ending
     * {@code UPDATE_ROLLBACK_COMPLETE} rather than {@code UPDATE_ROLLBACK_FAILED}.
     */
    @ParameterizedTest
    @CsvSource({"required,optional,required", "'',optional,optional"})
    void aFailedUpdateRollsTheInstancesMetadataOptionsBack(String createdTokens, String updatedTokens,
                                                           String httpTokens) {
        String stackName = "cfn-metadata-rollback-" + UUID.randomUUID().toString().substring(0, 8);
        String createdOptions = createdTokens.isEmpty() ? ""
                : ", \"MetadataOptions\": {\"HttpTokens\": \"" + createdTokens + "\"}";
        String created = """
                {
                  "Resources": {
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {"ImageId": "ami-12345678"%s}
                    }
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """.formatted(createdOptions);
        // The second instance names a launch template that does not exist, so it fails after Inst
        // has been updated, which it depends on.
        String failing = """
                {
                  "Resources": {
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {"ImageId": "ami-12345678", "MetadataOptions": {"HttpTokens": "%s"}}
                    },
                    "Later": {
                      "Type": "AWS::EC2::Instance",
                      "DependsOn": "Inst",
                      "Properties": {"LaunchTemplate": {"LaunchTemplateId": "lt-0123456789abcdef0", "Version": "1"}}
                    }
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """.formatted(updatedTokens);
        try {
            cfn("CreateStack", stackName, created);
            String instanceId = output(describeStack(stackName, "CREATE_COMPLETE"), "InstanceId");

            cfn("UpdateStack", stackName, failing);
            describeStack(stackName, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(httpTokens, describeInstance(instanceId).getString(INSTANCE + "metadataOptions.httpTokens"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    /**
     * LaunchTemplateData given as an {@code Fn::If} keeps the MetadataOptions of the branch its
     * condition chooses, as it keeps that branch's ImageId.
     */
    @ParameterizedTest
    @CsvSource({"true,required", "false,optional"})
    void launchTemplateDataChosenByFnIfKeepsItsMetadataOptions(String require, String httpTokens) {
        String stackName = "cfn-metadata-lt-if-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {
                  "Parameters": {"Require": {"Type": "String", "Default": "%s"}},
                  "Conditions": {"Requires": {"Fn::Equals": [{"Ref": "Require"}, "true"]}},
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateData": {"Fn::If": ["Requires",
                          {"ImageId": "ami-12345678", "MetadataOptions": {"HttpTokens": "required"}},
                          {"ImageId": "ami-12345678"}]}
                      }
                    },
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {"LaunchTemplate": {"LaunchTemplateId": {"Ref": "Lt"}, "Version": "1"}}
                    }
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """.formatted(require);
        try {
            cfn("CreateStack", stackName, template);
            String instanceId = output(describeStack(stackName, "CREATE_COMPLETE"), "InstanceId");
            assertEquals(httpTokens, describeInstance(instanceId).getString(INSTANCE + "metadataOptions.httpTokens"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    @Test
    void intrinsicsAndNoValueResolveInsideMetadataOptions() {
        String stackName = "cfn-metadata-intrinsic-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {
                  "Parameters": {"Tokens": {"Type": "String", "Default": "required"}},
                  "Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
                  "Resources": {
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "ImageId": "ami-12345678",
                        "MetadataOptions": {
                          "HttpTokens": {"Ref": "Tokens"},
                          "HttpPutResponseHopLimit": {"Fn::If": ["Never", 5, {"Ref": "AWS::NoValue"}]},
                          "HttpEndpoint": {"Fn::If": ["Never", "disabled", {"Ref": "AWS::NoValue"}]}
                        }
                      }
                    }
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """;
        try {
            cfn("CreateStack", stackName, template);
            XmlPath described = describeInstance(output(describeStack(stackName, "CREATE_COMPLETE"), "InstanceId"));
            assertEquals("required", described.getString(INSTANCE + "metadataOptions.httpTokens"));
            assertEquals("1", described.getString(INSTANCE + "metadataOptions.httpPutResponseHopLimit"));
            assertEquals("enabled", described.getString(INSTANCE + "metadataOptions.httpEndpoint"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    /**
     * The same holds inside a launch template's LaunchTemplateData, for a member and for the whole
     * block: AWS::NoValue leaves it out rather than failing the template.
     */
    @Test
    void intrinsicsAndNoValueResolveInsideLaunchTemplateMetadataOptions() {
        String stackName = "cfn-metadata-lt-intrinsic-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {
                  "Parameters": {"Tokens": {"Type": "String", "Default": "required"}},
                  "Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
                  "Resources": {
                    "MemberLt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateData": {
                          "ImageId": "ami-12345678",
                          "MetadataOptions": {
                            "HttpTokens": {"Ref": "Tokens"},
                            "HttpPutResponseHopLimit": {"Fn::If": ["Never", 5, {"Ref": "AWS::NoValue"}]}
                          }
                        }
                      }
                    },
                    "BlockLt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateData": {
                          "ImageId": "ami-12345678",
                          "MetadataOptions": {"Fn::If": ["Never", {"HttpTokens": "required"}, {"Ref": "AWS::NoValue"}]}
                        }
                      }
                    },
                    "MemberInst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {"LaunchTemplate": {"LaunchTemplateId": {"Ref": "MemberLt"}, "Version": "1"}}
                    },
                    "BlockInst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {"LaunchTemplate": {"LaunchTemplateId": {"Ref": "BlockLt"}, "Version": "1"}}
                    }
                  },
                  "Outputs": {
                    "MemberInstance": {"Value": {"Ref": "MemberInst"}},
                    "BlockInstance": {"Value": {"Ref": "BlockInst"}}
                  }
                }
                """;
        try {
            cfn("CreateStack", stackName, template);
            XmlPath stack = describeStack(stackName, "CREATE_COMPLETE");
            XmlPath member = describeInstance(output(stack, "MemberInstance"));
            assertEquals("required", member.getString(INSTANCE + "metadataOptions.httpTokens"));
            assertEquals("1", member.getString(INSTANCE + "metadataOptions.httpPutResponseHopLimit"));
            assertEquals("optional",
                    describeInstance(output(stack, "BlockInstance")).getString(INSTANCE + "metadataOptions.httpTokens"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    /**
     * A MetadataOptions block or member of the wrong type fails the resource with CloudFormation's
     * template validation error, on the instance and on the launch template alike. A list or object
     * member must not read as absent: {@code HttpTokens: [required]} would otherwise launch an
     * instance that still serves IMDSv1.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Instance|{\"HttpTokens\": [\"required\"]}|Property [HttpTokens] expected type: String, found: JSONArray",
            "Instance|{\"HttpTokens\": {\"Value\": \"required\"}}|Property [HttpTokens] expected type: String, found: JSONObject",
            "Instance|\"required\"|Property [MetadataOptions] expected type: JSONObject, found: String",
            "Instance|{\"HttpPutResponseHopLimit\": \"two\"}|Property [HttpPutResponseHopLimit] expected type: Integer, found: String",
            "Instance|{\"HttpPutResponseHopLimit\": 4.5}|Property [HttpPutResponseHopLimit] expected type: Integer, found: Float",
            "LaunchTemplate|{\"HttpTokens\": [\"required\"]}|Property [HttpTokens] expected type: String, found: JSONArray",
            "LaunchTemplate|[\"required\"]|Property [MetadataOptions] expected type: JSONObject, found: JSONArray",
            "LaunchTemplate|\"required\"|Property [MetadataOptions] expected type: JSONObject, found: String",
            "LaunchTemplate|{\"HttpTokens\": {\"Value\": \"required\"}}|Property [HttpTokens] expected type: String, found: JSONObject",
            "LaunchTemplate|{\"HttpPutResponseHopLimit\": \"two\"}|Property [HttpPutResponseHopLimit] expected type: Integer, found: String",
            "LaunchTemplate|{\"HttpPutResponseHopLimit\": 4.5}|Property [HttpPutResponseHopLimit] expected type: Integer, found: Float"
    })
    void aMetadataOptionsValueOfTheWrongTypeFailsTheResource(String resource, String block, String message) {
        String stackName = "cfn-metadata-type-" + UUID.randomUUID().toString().substring(0, 8);
        String properties = "Instance".equals(resource)
                ? "{\"ImageId\": \"ami-12345678\", \"MetadataOptions\": " + block + "}"
                : "{\"LaunchTemplateData\": {\"ImageId\": \"ami-12345678\", \"MetadataOptions\": " + block + "}}";
        String template = "{\"Resources\": {\"R\": {\"Type\": \"AWS::EC2::" + resource + "\", \"Properties\": "
                + properties + "}}}";
        try {
            cfn("CreateStack", stackName, template);
            describeStack(stackName, "ROLLBACK_COMPLETE");
            cfnRequest("DescribeStackEvents").formParam("StackName", stackName)
                    .post("/").then().statusCode(200)
                    .body(containsString(message));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    private static void cfn(String action, String stackName, String template) {
        RequestSpecification request = cfnRequest(action).formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.post("/").then().statusCode(200);
    }

    private static XmlPath describeStack(String stackName, String expectedStatus) {
        CfnStackWaits.awaitStatus(stackName, expectedStatus,
                EnforcementFixtures.auth(ACCOUNT, REGION, "cloudformation"));
        return cfnRequest("DescribeStacks").formParam("StackName", stackName)
                .post("/").then().statusCode(200)
                .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
                .extract().xmlPath();
    }

    private static String output(XmlPath stack, String key) {
        return stack.getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member"
                + ".find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private static XmlPath describeInstance(String instanceId) {
        return ec2Request("DescribeInstances").formParam("InstanceId.1", instanceId)
                .post("/").then().statusCode(200).extract().xmlPath();
    }

    private static RequestSpecification cfnRequest(String action) {
        return request("cloudformation", action);
    }

    private static RequestSpecification ec2Request(String action) {
        return request("ec2", action);
    }

    private static RequestSpecification request(String service, String action) {
        return given().header("Authorization", EnforcementFixtures.auth(ACCOUNT, REGION, service))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action);
    }
}
