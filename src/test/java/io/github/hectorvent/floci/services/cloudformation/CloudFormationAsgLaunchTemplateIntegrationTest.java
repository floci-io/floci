package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Covers how AWS::AutoScaling::AutoScalingGroup resolves a launch template when it is provisioned by
 * CloudFormation: by name, by id, through a Ref to an in-stack AWS::EC2::LaunchTemplate, and through
 * a MixedInstancesPolicy.
 *
 * <p>Regression coverage for <a href="https://github.com/floci-io/floci/issues/2005">#2005</a>,
 * where every shape but the by-name one failed with "The specified launch template does not exist".
 * Provisioning of the AWS::EC2::LaunchTemplate resource itself is covered by
 * {@code CloudFormationLaunchTemplateIntegrationTest}; this suite exercises the Auto Scaling side.
 */
@QuarkusTest
class CloudFormationAsgLaunchTemplateIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    // Capacity is kept at zero throughout: launch template resolution is validated when the group is
    // created, and launching instances would add ENIs that other tests in this suite count.
    private static final String ASG_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/autoscaling/aws4_request";

    /** Creates a launch template through the EC2 API and returns its {@code lt-} id. */
    private String createLaunchTemplate(String name) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-12345678")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();
        int start = xml.indexOf("<launchTemplateId>") + "<launchTemplateId>".length();
        return xml.substring(start, xml.indexOf("</launchTemplateId>"));
    }

    private void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private String describeStacks(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();
    }

    private String describeAutoScalingGroup(String asgName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", ASG_AUTH)
            .formParam("Action", "DescribeAutoScalingGroups")
            .formParam("AutoScalingGroupNames.member.1", asgName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();
    }

    private void assertStackCreated(String stackName) {
        String body = describeStacks(stackName);
        assertThat(body, containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));
        assertThat(body, not(containsString("The specified launch template does not exist.")));
    }

    @Test
    void asgResolvesLaunchTemplateByName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String ltName = "cfn-lt-name-" + suffix;
        String stackName = "cfn-lt-name-stack-" + suffix;
        createLaunchTemplate(ltName);

        createStack(stackName, """
                {
                  "Resources": {
                    "Asg": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "AutoScalingGroupName": "cfn-asg-name-%s",
                        "LaunchTemplate": {"LaunchTemplateName": "%s", "Version": "1"},
                        "MinSize": 0,
                        "MaxSize": 0,
                        "DesiredCapacity": 0,
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  }
                }
                """.formatted(suffix, ltName));

        assertStackCreated(stackName);
        assertThat(describeAutoScalingGroup("cfn-asg-name-" + suffix),
                containsString("<LaunchTemplateName>" + ltName + "</LaunchTemplateName>"));
    }

    @Test
    void asgResolvesLaunchTemplateById() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-lt-id-stack-" + suffix;
        String ltId = createLaunchTemplate("cfn-lt-id-" + suffix);

        createStack(stackName, """
                {
                  "Resources": {
                    "Asg": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "AutoScalingGroupName": "cfn-asg-id-%s",
                        "LaunchTemplate": {"LaunchTemplateId": "%s", "Version": "1"},
                        "MinSize": 0,
                        "MaxSize": 0,
                        "DesiredCapacity": 0,
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  }
                }
                """.formatted(suffix, ltId));

        assertStackCreated(stackName);
        assertThat(describeAutoScalingGroup("cfn-asg-id-" + suffix),
                containsString("<LaunchTemplateId>" + ltId + "</LaunchTemplateId>"));
    }

    @Test
    void asgResolvesInStackLaunchTemplateByRef() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-lt-instack-stack-" + suffix;

        createStack(stackName, """
                {
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateName": "cfn-lt-instack-%s",
                        "LaunchTemplateData": {"ImageId": "ami-12345678", "InstanceType": "t3.micro"}
                      }
                    },
                    "Asg": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "AutoScalingGroupName": "cfn-asg-instack-%s",
                        "LaunchTemplate": {
                          "LaunchTemplateId": {"Ref": "Lt"},
                          "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                        },
                        "MinSize": 0,
                        "MaxSize": 0,
                        "DesiredCapacity": 0,
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  }
                }
                """.formatted(suffix, suffix));

        assertStackCreated(stackName);
        // Ref must have yielded a real lt- id, not the synthetic physical id of a stubbed resource.
        assertThat(describeAutoScalingGroup("cfn-asg-instack-" + suffix),
                containsString("<LaunchTemplateId>lt-"));
    }

    @Test
    void asgResolvesMixedInstancesPolicyLaunchTemplate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String ltName = "cfn-lt-mip-" + suffix;
        String stackName = "cfn-lt-mip-stack-" + suffix;
        createLaunchTemplate(ltName);

        createStack(stackName, """
                {
                  "Resources": {
                    "Asg": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "AutoScalingGroupName": "cfn-asg-mip-%s",
                        "MixedInstancesPolicy": {
                          "LaunchTemplate": {
                            "LaunchTemplateSpecification": {"LaunchTemplateName": "%s", "Version": "1"},
                            "Overrides": [{"InstanceType": "t3.micro"}]
                          }
                        },
                        "MinSize": 0,
                        "MaxSize": 0,
                        "DesiredCapacity": 0,
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  }
                }
                """.formatted(suffix, ltName));

        assertStackCreated(stackName);
        String group = describeAutoScalingGroup("cfn-asg-mip-" + suffix);
        assertThat(group, containsString("<MixedInstancesPolicy>"));
        assertThat(group, containsString("<LaunchTemplateName>" + ltName + "</LaunchTemplateName>"));
        assertThat(group, containsString("<InstanceType>t3.micro</InstanceType>"));
    }

    /** InstancesDistribution is part of MixedInstancesPolicy and has to survive the round trip. */
    @Test
    void mixedInstancesPolicyInstancesDistributionIsMapped() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String ltName = "cfn-lt-dist-" + suffix;
        String asgName = "cfn-asg-dist-" + suffix;
        String stackName = "cfn-lt-dist-stack-" + suffix;
        createLaunchTemplate(ltName);

        createStack(stackName, """
                {
                  "Resources": {
                    "Asg": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "AutoScalingGroupName": "%s",
                        "MixedInstancesPolicy": {
                          "LaunchTemplate": {
                            "LaunchTemplateSpecification": {"LaunchTemplateName": "%s", "Version": "1"},
                            "Overrides": [{"InstanceType": "t3.micro"}, {"InstanceType": "t3.small"}]
                          },
                          "InstancesDistribution": {
                            "OnDemandBaseCapacity": 2,
                            "OnDemandPercentageAboveBaseCapacity": 50,
                            "SpotAllocationStrategy": "capacity-optimized"
                          }
                        },
                        "MinSize": 0,
                        "MaxSize": 0,
                        "DesiredCapacity": 0,
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  }
                }
                """.formatted(asgName, ltName));

        assertStackCreated(stackName);
        String group = describeAutoScalingGroup(asgName);
        assertThat(group, containsString("<OnDemandBaseCapacity>2</OnDemandBaseCapacity>"));
        assertThat(group, containsString(
                "<OnDemandPercentageAboveBaseCapacity>50</OnDemandPercentageAboveBaseCapacity>"));
        assertThat(group, containsString("<SpotAllocationStrategy>capacity-optimized</SpotAllocationStrategy>"));
        assertThat(group, containsString("<InstanceType>t3.micro</InstanceType>"));
        assertThat(group, containsString("<InstanceType>t3.small</InstanceType>"));
    }

    /** A non-integer where AWS requires a number is a template error, not an absent property. */
    @Test
    void malformedInstancesDistributionNumberFailsTheStack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String ltName = "cfn-lt-baddist-" + suffix;
        String stackName = "cfn-lt-baddist-stack-" + suffix;
        createLaunchTemplate(ltName);

        createStack(stackName, """
                {
                  "Resources": {
                    "Asg": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "AutoScalingGroupName": "cfn-asg-baddist-%s",
                        "MixedInstancesPolicy": {
                          "LaunchTemplate": {
                            "LaunchTemplateSpecification": {"LaunchTemplateName": "%s", "Version": "1"}
                          },
                          "InstancesDistribution": {"OnDemandBaseCapacity": "abc"}
                        },
                        "MinSize": 0,
                        "MaxSize": 0,
                        "DesiredCapacity": 0,
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  }
                }
                """.formatted(suffix, ltName));

        assertThat(describeStacks(stackName),
                containsString("Value of property OnDemandBaseCapacity must be an integer."));
    }
}
