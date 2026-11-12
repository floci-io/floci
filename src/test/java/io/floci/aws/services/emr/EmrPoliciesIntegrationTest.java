package io.floci.aws.services.emr;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * EMR managed scaling, auto termination and automatic scaling policies, and the account's block
 * public access configuration, over the AWS JSON 1.1 wire protocol.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmrPoliciesIntegrationTest {

    private static final String MANAGED_SCALING =
            "{\"ComputeLimits\":{\"UnitType\":\"Instances\",\"MinimumCapacityUnits\":1,\"MaximumCapacityUnits\":5}}";
    private static final String AUTO_SCALING =
            "{\"Constraints\":{\"MinCapacity\":1,\"MaxCapacity\":4},\"Rules\":[{\"Name\":\"scale-out\","
                    + "\"Action\":{\"SimpleScalingPolicyConfiguration\":{\"ScalingAdjustment\":1}},"
                    + "\"Trigger\":{\"CloudWatchAlarmDefinition\":{\"ComparisonOperator\":\"LESS_THAN\","
                    + "\"MetricName\":\"YARNMemoryAvailablePercentage\",\"Period\":300,\"Threshold\":15}}}]}";

    private static String clusterId;
    private static String masterGroupId;
    private static String coreGroupId;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String cluster() {
        return "{\"ClusterId\":\"" + clusterId + "\"";
    }

    @Test
    @Order(1)
    void runJobFlowStoresPolicies() {
        Response resp = emr("RunJobFlow",
                "{\"Name\":\"policies\",\"ReleaseLabel\":\"emr-7.5.0\","
                        + "\"ManagedScalingPolicy\":" + MANAGED_SCALING + ","
                        + "\"AutoTerminationPolicy\":{\"IdleTimeout\":3600},"
                        + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                        + "\"InstanceGroups\":[{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
                        + "{\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":2,"
                        + "\"AutoScalingPolicy\":" + AUTO_SCALING + "}]}}");
        resp.then().statusCode(200);
        clusterId = resp.jsonPath().getString("JobFlowId");

        emr("GetManagedScalingPolicy", cluster() + "}").then().statusCode(200)
                .body("ManagedScalingPolicy.ComputeLimits.MaximumCapacityUnits", equalTo(5));
        emr("GetAutoTerminationPolicy", cluster() + "}").then().statusCode(200)
                .body("AutoTerminationPolicy.IdleTimeout", equalTo(3600));
        Response groups = emr("ListInstanceGroups", cluster() + "}");
        groups.then().statusCode(200)
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.AutoScalingPolicy.Status.State",
                        equalTo("ATTACHED"))
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.AutoScalingPolicy.Constraints.MaxCapacity",
                        equalTo(4))
                .body("InstanceGroups.find { it.InstanceGroupType == 'MASTER' }.AutoScalingPolicy", nullValue());
        masterGroupId = groups.jsonPath().getString("InstanceGroups.find { it.InstanceGroupType == 'MASTER' }.Id");
        coreGroupId = groups.jsonPath().getString("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Id");
    }

    @Test
    @Order(2)
    void managedScalingPolicyPutGetRemove() {
        emr("PutManagedScalingPolicy", cluster() + ",\"ManagedScalingPolicy\":{\"ComputeLimits\":"
                + "{\"UnitType\":\"VCPU\",\"MinimumCapacityUnits\":4,\"MaximumCapacityUnits\":32},"
                + "\"ScalingStrategy\":\"ADVANCED\"}}").then().statusCode(200);
        emr("GetManagedScalingPolicy", cluster() + "}").then().statusCode(200)
                .body("ManagedScalingPolicy.ComputeLimits.UnitType", equalTo("VCPU"))
                .body("ManagedScalingPolicy.ScalingStrategy", equalTo("ADVANCED"));
        emr("RemoveManagedScalingPolicy", cluster() + "}").then().statusCode(200);
        emr("GetManagedScalingPolicy", cluster() + "}").then().statusCode(200)
                .body("$", anEmptyMap());
    }

    @Test
    @Order(3)
    void managedScalingPolicyIsValidated() {
        emr("PutManagedScalingPolicy", cluster() + ",\"ManagedScalingPolicy\":{}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("PutManagedScalingPolicy", cluster() + ",\"ManagedScalingPolicy\":{\"ComputeLimits\":"
                + "{\"UnitType\":\"Instances\",\"MinimumCapacityUnits\":6,\"MaximumCapacityUnits\":5}}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("PutManagedScalingPolicy", cluster() + ",\"ManagedScalingPolicy\":{\"ComputeLimits\":"
                + "{\"UnitType\":\"Instances\",\"MinimumCapacityUnits\":1,\"MaximumCapacityUnits\":5,"
                + "\"MaximumOnDemandCapacityUnits\":-1}}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("PutManagedScalingPolicy", "{\"ClusterId\":\"j-MISSING\",\"ManagedScalingPolicy\":" + MANAGED_SCALING + "}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(4)
    void autoTerminationPolicyPutGetRemove() {
        emr("PutAutoTerminationPolicy", cluster() + ",\"AutoTerminationPolicy\":{\"IdleTimeout\":120}}")
                .then().statusCode(200);
        emr("GetAutoTerminationPolicy", cluster() + "}").then().statusCode(200)
                .body("AutoTerminationPolicy.IdleTimeout", equalTo(120));
        emr("PutAutoTerminationPolicy", cluster() + ",\"AutoTerminationPolicy\":{\"IdleTimeout\":59}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("PutAutoTerminationPolicy", cluster() + ",\"AutoTerminationPolicy\":{\"IdleTimeout\":604801}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("RemoveAutoTerminationPolicy", cluster() + "}").then().statusCode(200);
        emr("GetAutoTerminationPolicy", cluster() + "}").then().statusCode(200)
                .body("$", anEmptyMap());
    }

    @Test
    @Order(5)
    void autoScalingPolicyPutAndRemove() {
        emr("PutAutoScalingPolicy", cluster() + ",\"InstanceGroupId\":\"" + coreGroupId + "\","
                + "\"AutoScalingPolicy\":{\"Constraints\":{\"MinCapacity\":2,\"MaxCapacity\":8},\"Rules\":"
                + "[{\"Name\":\"r\",\"Action\":{\"SimpleScalingPolicyConfiguration\":{\"ScalingAdjustment\":2}},"
                + "\"Trigger\":{\"CloudWatchAlarmDefinition\":{\"ComparisonOperator\":\"GREATER_THAN\","
                + "\"MetricName\":\"m\",\"Period\":300,\"Threshold\":80}}}]}}")
                .then().statusCode(200)
                .body("ClusterId", equalTo(clusterId))
                .body("InstanceGroupId", equalTo(coreGroupId))
                .body("ClusterArn", notNullValue())
                .body("AutoScalingPolicy.Status.State", equalTo("ATTACHED"))
                .body("AutoScalingPolicy.Constraints.MaxCapacity", equalTo(8))
                .body("AutoScalingPolicy.Rules", hasSize(1));
        emr("RemoveAutoScalingPolicy", cluster() + ",\"InstanceGroupId\":\"" + coreGroupId + "\"}")
                .then().statusCode(200);
        emr("ListInstanceGroups", cluster() + "}").then().statusCode(200)
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.AutoScalingPolicy", nullValue());
    }

    @Test
    @Order(6)
    void autoScalingPolicyOnlyOnCoreOrTaskGroups() {
        emr("PutAutoScalingPolicy", cluster() + ",\"InstanceGroupId\":\"" + masterGroupId + "\","
                + "\"AutoScalingPolicy\":" + AUTO_SCALING + "}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("PutAutoScalingPolicy", cluster() + ",\"InstanceGroupId\":\"ig-MISSING\","
                + "\"AutoScalingPolicy\":" + AUTO_SCALING + "}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("RunJobFlow", "{\"Name\":\"bad\",\"Instances\":{\"InstanceGroups\":[{\"InstanceRole\":\"MASTER\","
                + "\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1,\"AutoScalingPolicy\":" + AUTO_SCALING + "}]}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(7)
    void blockPublicAccessDefaultsThenPut() {
        emr("GetBlockPublicAccessConfiguration", "{}").then().statusCode(200)
                .body("BlockPublicAccessConfiguration.BlockPublicSecurityGroupRules", equalTo(true))
                .body("BlockPublicAccessConfiguration.PermittedPublicSecurityGroupRuleRanges[0].MinRange", equalTo(22))
                .body("BlockPublicAccessConfigurationMetadata.CreatedByArn", notNullValue())
                .body("BlockPublicAccessConfigurationMetadata.CreationDateTime", notNullValue());
        emr("PutBlockPublicAccessConfiguration", "{\"BlockPublicAccessConfiguration\":"
                + "{\"BlockPublicSecurityGroupRules\":true,\"PermittedPublicSecurityGroupRuleRanges\":"
                + "[{\"MinRange\":22,\"MaxRange\":22},{\"MinRange\":100,\"MaxRange\":101}]}}")
                .then().statusCode(200);
        emr("GetBlockPublicAccessConfiguration", "{}").then().statusCode(200)
                .body("BlockPublicAccessConfiguration.PermittedPublicSecurityGroupRuleRanges", hasSize(2));
        emr("PutBlockPublicAccessConfiguration", "{\"BlockPublicAccessConfiguration\":"
                + "{\"BlockPublicSecurityGroupRules\":true,\"PermittedPublicSecurityGroupRuleRanges\":"
                + "[{\"MinRange\":70000}]}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("PutBlockPublicAccessConfiguration", "{\"BlockPublicAccessConfiguration\":"
                + "{\"BlockPublicSecurityGroupRules\":true,\"PermittedPublicSecurityGroupRuleRanges\":"
                + "[{\"MinRange\":22,\"MaxRange\":\"invalid\"}]}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("PutBlockPublicAccessConfiguration", "{\"BlockPublicAccessConfiguration\":{}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(8)
    void anEndedClustersPoliciesCannotBeChangedNorItsManagedScalingPolicyRead() {
        String core = ",\"InstanceGroupId\":\"" + coreGroupId + "\"";
        emr("PutManagedScalingPolicy", cluster() + ",\"ManagedScalingPolicy\":" + MANAGED_SCALING + "}")
                .then().statusCode(200);
        emr("PutAutoScalingPolicy", cluster() + core + ",\"AutoScalingPolicy\":" + AUTO_SCALING + "}")
                .then().statusCode(200);
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);

        assertShutDown("GetManagedScalingPolicy", cluster() + "}");
        assertShutDown("PutManagedScalingPolicy", cluster() + ",\"ManagedScalingPolicy\":" + MANAGED_SCALING + "}");
        assertShutDown("RemoveManagedScalingPolicy", cluster() + "}");
        assertShutDown("PutAutoTerminationPolicy", cluster() + ",\"AutoTerminationPolicy\":{\"IdleTimeout\":3600}}");
        assertShutDown("RemoveAutoTerminationPolicy", cluster() + "}");
        assertShutDown("PutAutoScalingPolicy", cluster() + core + ",\"AutoScalingPolicy\":" + AUTO_SCALING + "}");
        assertShutDown("RemoveAutoScalingPolicy", cluster() + core + "}");

        emr("ListInstanceGroups", cluster() + "}").then().statusCode(200)
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.AutoScalingPolicy.Constraints.MaxCapacity",
                        equalTo(4));
    }

    private static void assertShutDown(String action, String body) {
        emr(action, body).then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(
                        "A job flow that is shutting down, terminated, or finished may not be modified."));
    }
}
