package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.wafv2.Wafv2Client;
import software.amazon.awssdk.services.wafv2.model.ResourceType;
import software.amazon.awssdk.services.wafv2.model.WebACL;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudFormation AWS::WAFv2::WebACLAssociation")
class CloudFormationWafV2WebAclAssociationTest {

    private static final Logger LOG = Logger.getLogger(CloudFormationWafV2WebAclAssociationTest.class.getName());

    private static CloudFormationClient cfn;
    private static Wafv2Client waf;
    private static String stackName;

    @BeforeAll
    static void setup() {
        cfn = TestFixtures.cloudFormationClient();
        waf = TestFixtures.wafv2Client();
        stackName = TestFixtures.uniqueName("compat-cfn-waf-assoc");
    }

    @AfterAll
    static void cleanup() {
        try {
            cfn.deleteStack(r -> r.stackName(stackName));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to delete WAFv2 association test stack: " + stackName, e);
        }
        cfn.close();
        waf.close();
    }

    @Test
    void associationFollowsTheTemplateAcrossReplacementAndStackDelete() throws InterruptedException {
        cfn.createStack(r -> r.stackName(stackName).templateBody(template("AclA")));
        awaitStatus("CREATE_COMPLETE");
        Map<String, String> created = outputs();
        String poolArn = created.get("PoolArn");
        String aclAArn = created.get("AclAArn");
        String aclBArn = created.get("AclBArn");

        assertThat(created.get("AssocRef")).as("Ref is ResourceArn|WebACLArn").isEqualTo(poolArn + "|" + aclAArn);
        assertThat(cfn.describeStackResource(r -> r.stackName(stackName).logicalResourceId("Assoc"))
                .stackResourceDetail().physicalResourceId()).isEqualTo(poolArn + "|" + aclAArn);
        assertThat(webAclArnFor(poolArn)).isEqualTo(aclAArn);

        cfn.updateStack(r -> r.stackName(stackName).templateBody(template("AclB")));
        awaitStatus("UPDATE_COMPLETE");

        assertThat(outputs().get("AssocRef")).isEqualTo(poolArn + "|" + aclBArn);
        assertThat(webAclArnFor(poolArn))
                .as("cleaning up the old pair keeps the pool associated with the new ACL")
                .isEqualTo(aclBArn);
        assertThat(resourcesFor(aclAArn)).isEmpty();
        assertThat(resourcesFor(aclBArn)).containsExactly(poolArn);

        deleteStack();
        assertThat(webAclArnFor(poolArn)).as("the stack delete disassociates the pool").isNull();
    }

    private static String template(String associatedAcl) {
        return """
                {
                  "Resources": {
                    "Pool": {
                      "Type": "AWS::Cognito::UserPool",
                      "Properties": {"UserPoolName": "%1$s-pool"}
                    },
                    "AclA": {
                      "Type": "AWS::WAFv2::WebACL",
                      "Properties": {
                        "Name": "%1$s-a",
                        "Scope": "REGIONAL",
                        "DefaultAction": {"Allow": {}},
                        "VisibilityConfig": {
                          "SampledRequestsEnabled": false,
                          "CloudWatchMetricsEnabled": false,
                          "MetricName": "%1$s-a"
                        }
                      }
                    },
                    "AclB": {
                      "Type": "AWS::WAFv2::WebACL",
                      "Properties": {
                        "Name": "%1$s-b",
                        "Scope": "REGIONAL",
                        "DefaultAction": {"Block": {}},
                        "VisibilityConfig": {
                          "SampledRequestsEnabled": false,
                          "CloudWatchMetricsEnabled": false,
                          "MetricName": "%1$s-b"
                        }
                      }
                    },
                    "Assoc": {
                      "Type": "AWS::WAFv2::WebACLAssociation",
                      "Properties": {
                        "ResourceArn": {"Fn::GetAtt": ["Pool", "Arn"]},
                        "WebACLArn": {"Fn::GetAtt": ["%2$s", "Arn"]}
                      }
                    }
                  },
                  "Outputs": {
                    "AssocRef": {"Value": {"Ref": "Assoc"}},
                    "PoolArn": {"Value": {"Fn::GetAtt": ["Pool", "Arn"]}},
                    "AclAArn": {"Value": {"Fn::GetAtt": ["AclA", "Arn"]}},
                    "AclBArn": {"Value": {"Fn::GetAtt": ["AclB", "Arn"]}}
                  }
                }
                """.formatted(stackName, associatedAcl);
    }

    private static Map<String, String> outputs() {
        return cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0).outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static String webAclArnFor(String resourceArn) {
        WebACL acl = waf.getWebACLForResource(r -> r.resourceArn(resourceArn)).webACL();
        return acl == null ? null : acl.arn();
    }

    private static List<String> resourcesFor(String webAclArn) {
        return waf.listResourcesForWebACL(r -> r.webACLArn(webAclArn).resourceType(ResourceType.COGNITO_USER_POOL))
                .resourceArns();
    }

    private static void awaitStatus(String expected) throws InterruptedException {
        await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (status.endsWith("_FAILED") || status.contains("ROLLBACK")) {
                throw new AssertionError(stackName + " reached " + status + ": " + stack.stackStatusReason());
            }
            return expected.equals(status);
        }, expected);
    }

    private static void deleteStack() throws InterruptedException {
        cfn.deleteStack(r -> r.stackName(stackName));
        await(() -> {
            try {
                Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
                if ("DELETE_FAILED".equals(stack.stackStatusAsString())) {
                    throw new AssertionError(stackName + " deletion failed: " + stack.stackStatusReason());
                }
                return "DELETE_COMPLETE".equals(stack.stackStatusAsString());
            } catch (CloudFormationException e) {
                if (e.getMessage().contains("does not exist")) {
                    return true;
                }
                throw e;
            }
        }, "stack deletion");
    }

    private static void await(BooleanSupplier condition, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + 300_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError(stackName + " timed out waiting for " + expected);
    }
}
