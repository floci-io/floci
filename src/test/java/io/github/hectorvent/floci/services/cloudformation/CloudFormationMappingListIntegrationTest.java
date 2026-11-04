package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Subnet;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationMappingListIntegrationTest {
    private final String name = "mapping-lists-" + UUID.randomUUID();
    private CloudFormationClient cfn;
    private Ec2Client ec2;

    @BeforeEach
    void clients() {
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        ec2 = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
    }

    @AfterEach
    void cleanup() {
        try {
            List<String> subnetIds = cfn.describeStackResources(request -> request.stackName(name))
                    .stackResources().stream()
                    .filter(resource -> "AWS::EC2::Subnet".equals(resource.resourceType()))
                    .map(StackResource::physicalResourceId).toList();
            cfn.deleteStack(request -> request.stackName(name));
            assertEquals(3, subnetIds.size(), "Capture every created subnet before deleting the stack");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                CloudFormationException absent = assertThrows(CloudFormationException.class,
                        () -> cfn.describeStacks(request -> request.stackName(name)));
                assertEquals("ValidationError", absent.awsErrorDetails().errorCode());
                assertTrue(absent.getMessage().contains("does not exist"));
                List<String> remaining = ec2.describeSubnets().subnets().stream().map(Subnet::subnetId).toList();
                for (String subnetId : subnetIds) {
                    assertFalse(remaining.contains(subnetId), "Subnet remains after stack deletion: " + subnetId);
                }
            });
        } finally {
            ec2.close();
            cfn.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Discover", "Mapped"})
    void conditionalMappedListsPlaceEachSubnetInTheSelectedZone(String mode) {
        cfn.createStack(request -> request.stackName(name).templateBody(template())
                .parameters(mode(mode)));
        CfnStackWaits.awaitStatus(cfn, name, "CREATE_COMPLETE");
        assertPlacement(mode);
    }

    @Test
    void changingTheSelectedMappingReplacesSubnetsInTheNewZones() {
        cfn.createStack(request -> request.stackName(name).templateBody(template())
                .parameters(mode("Discover")));
        CfnStackWaits.awaitStatus(cfn, name, "CREATE_COMPLETE");
        assertPlacement("Discover");
        Map<String, String> previous = outputs();

        String updated = template().replace("10.88.0.0/24", "10.88.3.0/24")
                .replace("10.88.2.0/24", "10.88.4.0/24");
        cfn.updateStack(request -> request.stackName(name).templateBody(updated)
                .parameters(mode("Mapped")));
        CfnStackWaits.awaitStatus(cfn, name, "UPDATE_COMPLETE");
        assertPlacement("Mapped");
        List<String> remaining = ec2.describeSubnets().subnets().stream().map(Subnet::subnetId).toList();
        assertTrue(!remaining.contains(previous.get("Subnet0")) && !remaining.contains(previous.get("Subnet2")),
                "Subnets whose selected zone changed must be replaced and cleaned up");
        assertEquals(previous.get("Subnet1"), outputs().get("Subnet1"));
    }

    private void assertPlacement(String mode) {
        Map<String, String> output = outputs();
        List<String> expected = "Discover".equals(mode)
                ? List.of("us-east-1a", "us-east-1b", "us-east-1c")
                : List.of("us-east-1c", "us-east-1b", "us-east-1a");
        for (int index = 0; index < expected.size(); index++) {
            String subnetId = output.get("Subnet" + index);
            assertEquals(expected.get(index), ec2.describeSubnets(request -> request.subnetIds(subnetId))
                    .subnets().getFirst().availabilityZone());
        }
        assertEquals("Discover".equals(mode) ? "true" : "false", output.get("UsesDiscovery"));
        assertEquals("first,with-comma||last", output.get("Joined"));
    }

    private Map<String, String> outputs() {
        return cfn.describeStacks(request -> request.stackName(name)).stacks().getFirst().outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static Parameter mode(String value) {
        return Parameter.builder().parameterKey("ZoneMode").parameterValue(value).build();
    }

    private static String template() {
        return """
                {
                  "Parameters": {
                    "ZoneMode": {
                      "Type": "String"
                    }
                  },
                  "Mappings": {
                    "Zones": {
                      "Discover": {
                        "Values": [
                          "automatic"
                        ]
                      },
                      "Mapped": {
                        "Values": [
                          "us-east-1c",
                          "us-east-1b",
                          "us-east-1a"
                        ]
                      }
                    },
                    "Text": {
                      "Example": {
                        "Values": [
                          "first,with-comma",
                          "",
                          "last"
                        ]
                      }
                    }
                  },
                  "Conditions": {
                    "UseDiscovery": {
                      "Fn::Equals": [
                        "automatic",
                        {
                          "Fn::Select": [
                            0,
                            {
                              "Fn::FindInMap": [
                                "Zones",
                                {
                                  "Ref": "ZoneMode"
                                },
                                "Values"
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  },
                  "Resources": {
                    "Vpc": {
                      "Type": "AWS::EC2::VPC",
                      "Properties": {
                        "CidrBlock": "10.88.0.0/16"
                      }
                    },
                    "Subnet0": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {
                        "VpcId": {
                          "Ref": "Vpc"
                        },
                        "CidrBlock": "10.88.0.0/24",
                        "AvailabilityZone": {
                          "Fn::Select": [
                            0,
                            {
                              "Fn::If": [
                                "UseDiscovery",
                                {
                                  "Fn::GetAZs": {
                                    "Ref": "AWS::Region"
                                  }
                                },
                                {
                                  "Fn::FindInMap": [
                                    "Zones",
                                    {
                                      "Ref": "ZoneMode"
                                    },
                                    "Values"
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      }
                    },
                    "Subnet1": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {
                        "VpcId": {
                          "Ref": "Vpc"
                        },
                        "CidrBlock": "10.88.1.0/24",
                        "AvailabilityZone": {
                          "Fn::Select": [
                            1,
                            {
                              "Fn::If": [
                                "UseDiscovery",
                                {
                                  "Fn::GetAZs": {
                                    "Ref": "AWS::Region"
                                  }
                                },
                                {
                                  "Fn::FindInMap": [
                                    "Zones",
                                    {
                                      "Ref": "ZoneMode"
                                    },
                                    "Values"
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      }
                    },
                    "Subnet2": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {
                        "VpcId": {
                          "Ref": "Vpc"
                        },
                        "CidrBlock": "10.88.2.0/24",
                        "AvailabilityZone": {
                          "Fn::Select": [
                            2,
                            {
                              "Fn::If": [
                                "UseDiscovery",
                                {
                                  "Fn::GetAZs": {
                                    "Ref": "AWS::Region"
                                  }
                                },
                                {
                                  "Fn::FindInMap": [
                                    "Zones",
                                    {
                                      "Ref": "ZoneMode"
                                    },
                                    "Values"
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "Subnet0": {
                      "Value": {
                        "Ref": "Subnet0"
                      }
                    },
                    "Subnet1": {
                      "Value": {
                        "Ref": "Subnet1"
                      }
                    },
                    "Subnet2": {
                      "Value": {
                        "Ref": "Subnet2"
                      }
                    },
                    "UsesDiscovery": {
                      "Value": {
                        "Fn::If": [
                          "UseDiscovery",
                          "true",
                          "false"
                        ]
                      }
                    },
                    "Joined": {
                      "Value": {
                        "Fn::Join": [
                          "|",
                          {
                            "Fn::FindInMap": [
                              "Text",
                              "Example",
                              "Values"
                            ]
                          }
                        ]
                      }
                    }
                  }
                }
                """;
    }
}
