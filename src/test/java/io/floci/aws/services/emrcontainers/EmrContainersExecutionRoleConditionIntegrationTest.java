package io.floci.aws.services.emrcontainers;

import io.floci.aws.testing.EksRequests;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.floci.aws.testing.IamTestPolicies.EMPTY_POLICY_DOCUMENT;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The execution role a StartJobRun or CreateManagedEndpoint request names is its
 * {@code emr-containers:ExecutionRoleArn}, the key the EMR on EKS Development Guide gives an IAM administrator to
 * restrict the roles a tenant's jobs run as ("Controlling access to the execution role").
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EmrContainersExecutionRoleConditionIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String RELEASE = "emr-7.5.0-latest";

    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anAllowOnAnExecutionRoleStartsJobsAsThatRoleAlone() {
        String user = "emrc-role-" + UUID.randomUUID().toString().substring(0, 8);
        UserCredentials caller = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), user);
        String allowed = role(user + "-allowed");
        String other = role(user + "-other");
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "jobs", """
                {"Version":"2012-10-17","Statement":[
                 {"Effect":"Allow","Action":"emr-containers:StartJobRun","Resource":"*",
                  "Condition":{"ArnEquals":{"emr-containers:ExecutionRoleArn":"%s"}}},
                 {"Effect":"Allow","Action":"iam:PassRole","Resource":"*"}]}""".formatted(allowed));
        String cluster = virtualCluster();

        startJobRun(caller, cluster, allowed).statusCode(200);
        denied(startJobRun(caller, cluster, other));
    }

    @Test
    void aDenyOnARolePrefixRefusesAManagedEndpointForThoseRoles() {
        String user = "emrc-endpoint-" + UUID.randomUUID().toString().substring(0, 8);
        UserCredentials caller = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), user);
        String restricted = role(user + "-restricted");
        String other = role(user + "-other");
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "endpoints", """
                {"Version":"2012-10-17","Statement":[
                 {"Effect":"Allow","Action":["emr-containers:CreateManagedEndpoint","iam:PassRole"],"Resource":"*"},
                 {"Effect":"Deny","Action":"emr-containers:CreateManagedEndpoint","Resource":"*",
                  "Condition":{"ArnLike":{"emr-containers:ExecutionRoleArn":"arn:aws:iam::*:role/%s-restricted*"}}}]}"""
                .formatted(user));
        String cluster = virtualCluster();

        denied(createManagedEndpoint(caller, cluster, restricted));
        createManagedEndpoint(caller, cluster, other).statusCode(200);
    }

    private String role(String name) {
        return EnforcementFixtures.createRole(cleanup, endpoint.getPort(), ACCOUNT, "/", name, EMPTY_POLICY_DOCUMENT);
    }

    private ValidatableResponse startJobRun(UserCredentials caller, String cluster, String role) {
        return emr(caller.accessKeyId()).body("""
                        {"name": "pi", "clientToken": "%s", "executionRoleArn": "%s", "releaseLabel": "%s",
                         "jobDriver": {"sparkSubmitJobDriver": {"entryPoint": "s3://bucket/pi.py"}}}
                        """.formatted(UUID.randomUUID(), role, RELEASE))
                .post("/virtualclusters/" + cluster + "/jobruns")
                .then();
    }

    private ValidatableResponse createManagedEndpoint(UserCredentials caller, String cluster, String role) {
        return emr(caller.accessKeyId()).body("""
                        {"name": "studio", "type": "JUPYTER_ENTERPRISE_GATEWAY", "releaseLabel": "%s",
                         "executionRoleArn": "%s", "clientToken": "%s"}
                        """.formatted(RELEASE, role, UUID.randomUUID()))
                .post("/virtualclusters/" + cluster + "/endpoints")
                .then();
    }

    /** A virtual cluster on a new EKS cluster, made by the account itself and removed after the test. */
    private String virtualCluster() {
        String eks = "emrc-" + UUID.randomUUID().toString().substring(0, 8);
        EksRequests.eks(ACCOUNT, REGION).body("""
                        {"name": "%s", "roleArn": "arn:aws:iam::000000000000:role/eks-role", "version": "1.29",
                         "accessConfig": {"authenticationMode": "CONFIG_MAP"}}
                        """.formatted(eks))
                .post("/clusters").then().statusCode(200);
        cleanup.register(() -> EksRequests.eks(ACCOUNT, REGION).delete("/clusters/" + eks));
        String id = emr(ACCOUNT).body("""
                        {"name": "vc-%s", "clientToken": "%s",
                         "containerProvider": {"type": "EKS", "id": "%s", "info": {"eksInfo": {"namespace": "spark"}}}}
                        """.formatted(eks, UUID.randomUUID(), eks))
                .post("/virtualclusters").then().statusCode(200).extract().path("id");
        cleanup.register(() -> emr(ACCOUNT).delete("/virtualclusters/" + id));
        return id;
    }

    private RequestSpecification emr(String accessKeyId) {
        return given().port(endpoint.getPort())
                .header("Authorization", auth(accessKeyId, REGION, "emr-containers"))
                .contentType("application/json")
                .urlEncodingEnabled(false);
    }

    private static void denied(ValidatableResponse response) {
        response.statusCode(403).body(containsString("AccessDeniedException"));
    }
}
