package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
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

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "ElasticMapReduce.";
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

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", PREFIX + action)
                .body(body).when().post("/");
    }

    private static String cluster() {
        return "{\"ClusterId\":\"" + clusterId + "\"";
    }

    @Test
    @Order(1)
    void runJobFlowStoresPolicies() {
        Response resp = call("RunJobFlow",
                "{\"Name\":\"policies\",\"ReleaseLabel\":\"emr-7.5.0\","
                        + "\"ManagedScalingPolicy\":" + MANAGED_SCALING + ","
                        + "\"AutoTerminationPolicy\":{\"IdleTimeout\":3600},"
                        + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                        + "\"InstanceGroups\":[{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
                        + "{\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":2,"
                        + "\"AutoScalingPolicy\":" + AUTO_SCALING + "}]}}");
        resp.then().statusCode(200);
        clusterId = resp.jsonPath().getString("JobFlowId");

        call("GetManagedScalingPolicy", cluster() + "}").then().statusCode(200)
                .body("ManagedScalingPolicy.ComputeLimits.MaximumCapacityUnits", equalTo(5));
        call("GetAutoTerminationPolicy", cluster() + "}").then().statusCode(200)
                .body("AutoTerminationPolicy.IdleTimeout", equalTo(3600));
        Response groups = call("ListInstanceGroups", cluster() + "}");
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
        call("PutManagedScalingPolicy", cluster() + ",\"ManagedScalingPolicy\":{\"ComputeLimits\":"
                + "{\"UnitType\":\"VCPU\",\"MinimumCapacityUnits\":4,\"MaximumCapacityUnits\":32},"
                + "\"ScalingStrategy\":\"ADVANCED\"}}").then().statusCode(200);
        call("GetManagedScalingPolicy", cluster() + "}").then().statusCode(200)
                .body("ManagedScalingPolicy.ComputeLimits.UnitType", equalTo("VCPU"))
                .body("ManagedScalingPolicy.ScalingStrategy", equalTo("ADVANCED"));
        call("RemoveManagedScalingPolicy", cluster() + "}").then().statusCode(200);
        call("GetManagedScalingPolicy", cluster() + "}").then().statusCode(200)
                .body("$", anEmptyMap());
    }

    @Test
    @Order(3)
    void managedScalingPolicyIsValidated() {
        call("PutManagedScalingPolicy", cluster() + ",\"ManagedScalingPolicy\":{}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("PutManagedScalingPolicy", cluster() + ",\"ManagedScalingPolicy\":{\"ComputeLimits\":"
                + "{\"UnitType\":\"Instances\",\"MinimumCapacityUnits\":6,\"MaximumCapacityUnits\":5}}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("PutManagedScalingPolicy", cluster() + ",\"ManagedScalingPolicy\":{\"ComputeLimits\":"
                + "{\"UnitType\":\"Instances\",\"MinimumCapacityUnits\":1,\"MaximumCapacityUnits\":5,"
                + "\"MaximumOnDemandCapacityUnits\":-1}}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("PutManagedScalingPolicy", "{\"ClusterId\":\"j-MISSING\",\"ManagedScalingPolicy\":" + MANAGED_SCALING + "}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(4)
    void autoTerminationPolicyPutGetRemove() {
        call("PutAutoTerminationPolicy", cluster() + ",\"AutoTerminationPolicy\":{\"IdleTimeout\":120}}")
                .then().statusCode(200);
        call("GetAutoTerminationPolicy", cluster() + "}").then().statusCode(200)
                .body("AutoTerminationPolicy.IdleTimeout", equalTo(120));
        call("PutAutoTerminationPolicy", cluster() + ",\"AutoTerminationPolicy\":{\"IdleTimeout\":59}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("PutAutoTerminationPolicy", cluster() + ",\"AutoTerminationPolicy\":{\"IdleTimeout\":604801}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("RemoveAutoTerminationPolicy", cluster() + "}").then().statusCode(200);
        call("GetAutoTerminationPolicy", cluster() + "}").then().statusCode(200)
                .body("$", anEmptyMap());
    }

    @Test
    @Order(5)
    void autoScalingPolicyPutAndRemove() {
        call("PutAutoScalingPolicy", cluster() + ",\"InstanceGroupId\":\"" + coreGroupId + "\","
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
        call("RemoveAutoScalingPolicy", cluster() + ",\"InstanceGroupId\":\"" + coreGroupId + "\"}")
                .then().statusCode(200);
        call("ListInstanceGroups", cluster() + "}").then().statusCode(200)
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.AutoScalingPolicy", nullValue());
    }

    @Test
    @Order(6)
    void autoScalingPolicyOnlyOnCoreOrTaskGroups() {
        call("PutAutoScalingPolicy", cluster() + ",\"InstanceGroupId\":\"" + masterGroupId + "\","
                + "\"AutoScalingPolicy\":" + AUTO_SCALING + "}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("PutAutoScalingPolicy", cluster() + ",\"InstanceGroupId\":\"ig-MISSING\","
                + "\"AutoScalingPolicy\":" + AUTO_SCALING + "}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("RunJobFlow", "{\"Name\":\"bad\",\"Instances\":{\"InstanceGroups\":[{\"InstanceRole\":\"MASTER\","
                + "\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1,\"AutoScalingPolicy\":" + AUTO_SCALING + "}]}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(7)
    void blockPublicAccessDefaultsThenPut() {
        call("GetBlockPublicAccessConfiguration", "{}").then().statusCode(200)
                .body("BlockPublicAccessConfiguration.BlockPublicSecurityGroupRules", equalTo(true))
                .body("BlockPublicAccessConfiguration.PermittedPublicSecurityGroupRuleRanges[0].MinRange", equalTo(22))
                .body("BlockPublicAccessConfigurationMetadata.CreatedByArn", notNullValue())
                .body("BlockPublicAccessConfigurationMetadata.CreationDateTime", notNullValue());
        call("PutBlockPublicAccessConfiguration", "{\"BlockPublicAccessConfiguration\":"
                + "{\"BlockPublicSecurityGroupRules\":true,\"PermittedPublicSecurityGroupRuleRanges\":"
                + "[{\"MinRange\":22,\"MaxRange\":22},{\"MinRange\":100,\"MaxRange\":101}]}}")
                .then().statusCode(200);
        call("GetBlockPublicAccessConfiguration", "{}").then().statusCode(200)
                .body("BlockPublicAccessConfiguration.PermittedPublicSecurityGroupRuleRanges", hasSize(2));
        call("PutBlockPublicAccessConfiguration", "{\"BlockPublicAccessConfiguration\":"
                + "{\"BlockPublicSecurityGroupRules\":true,\"PermittedPublicSecurityGroupRuleRanges\":"
                + "[{\"MinRange\":70000}]}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("PutBlockPublicAccessConfiguration", "{\"BlockPublicAccessConfiguration\":"
                + "{\"BlockPublicSecurityGroupRules\":true,\"PermittedPublicSecurityGroupRuleRanges\":"
                + "[{\"MinRange\":22,\"MaxRange\":\"invalid\"}]}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("PutBlockPublicAccessConfiguration", "{\"BlockPublicAccessConfiguration\":{}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }
}
