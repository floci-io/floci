package io.floci.aws.services.emr;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

/** GetClusterSessionCredentials over the AWS JSON 1.1 wire protocol, for a runtime role enabled cluster and others. */
@QuarkusTest
class EmrClusterSessionCredentialsIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/test-emr-demo3";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aRuntimeRoleEnabledClusterGivesTemporaryBasicCredentials() {
        String clusterId = cluster("emr-6.15.0", runtimeRoles());

        emr("GetClusterSessionCredentials", "{\"ClusterId\":\"" + clusterId + "\",\"ExecutionRoleArn\":\"" + ROLE
                        + "\"}")
                .then().statusCode(200)
                .body("Credentials.UsernamePassword.Username", matchesPattern("emr-[a-z0-9]{16}"))
                .body("Credentials.UsernamePassword.Password", matchesPattern("[0-9a-f]{48}"))
                .body("ExpiresAt", notNullValue());

        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);
        refused(clusterId, "InvalidRequestException");
    }

    @Test
    void aClusterKeepsTheRuntimeRolesItWasCreatedWithAfterItsSecurityConfigurationIsDeleted() {
        String configuration = runtimeRoles();
        String clusterId = cluster("emr-6.15.0", configuration);
        emr("DeleteSecurityConfiguration", "{\"Name\":\"" + configuration + "\"}").then().statusCode(200);

        emr("GetClusterSessionCredentials", "{\"ClusterId\":\"" + clusterId + "\",\"ExecutionRoleArn\":\"" + ROLE
                        + "\"}")
                .then().statusCode(200)
                .body("Credentials.UsernamePassword.Username", notNullValue());
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);
    }

    @Test
    void aRequestWithoutARuntimeRoleIsRefused() {
        String clusterId = cluster("emr-6.15.0", runtimeRoles());
        emr("GetClusterSessionCredentials", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);
    }

    @Test
    void aClusterWithoutRuntimeRolesOrOfAnEarlierReleaseIsRefused() {
        String plain = cluster("emr-6.15.0", null);
        refused(plain, "InvalidRequestException");
        String early = cluster("emr-6.10.0", runtimeRoles());
        refused(early, "InvalidRequestException");
        refused("j-NOSUCHCLUSTER", "InvalidRequestException");
        emr("GetClusterSessionCredentials", "{\"ClusterId\":\"" + plain + "\",\"ExecutionRoleArn\":\"arn:short\"}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + plain + "\",\"" + early + "\"]}").then().statusCode(200);
    }

    private static void refused(String clusterId, String type) {
        emr("GetClusterSessionCredentials", "{\"ClusterId\":\"" + clusterId + "\",\"ExecutionRoleArn\":\"" + ROLE
                        + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo(type));
    }

    // A security configuration that turns on runtime roles.
    private static String runtimeRoles() {
        String name = "runtime-roles-" + UUID.randomUUID();
        emr("CreateSecurityConfiguration", "{\"Name\":\"" + name + "\",\"SecurityConfiguration\":"
                        + "\"{\\\"AuthorizationConfiguration\\\":{\\\"IAMConfiguration\\\":"
                        + "{\\\"EnableApplicationScopedIAMRole\\\":true}}}\"}")
                .then().statusCode(200);
        return name;
    }

    private static String cluster(String releaseLabel, String securityConfiguration) {
        String extra = securityConfiguration != null
                ? "\"SecurityConfiguration\": \"" + securityConfiguration + "\"," : "";
        return emr("RunJobFlow", """
                {"Name": "runtime-roles", "ReleaseLabel": "%s", %s
                 "Applications": [{"Name": "Spark"}, {"Name": "Livy"}, {"Name": "JupyterEnterpriseGateway"}],
                 "Instances": {"KeepJobFlowAliveWhenNoSteps": true,
                   "InstanceGroups": [{"Name": "primary", "InstanceRole": "MASTER", "InstanceType": "m5.xlarge",
                     "InstanceCount": 1}]}}
                """.formatted(releaseLabel, extra))
                .then().statusCode(200).extract().path("JobFlowId");
    }
}
