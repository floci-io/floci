package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * {@code AmazonEC2ContainerRegistryFullAccess} allows {@code iam:CreateServiceLinkedRole} only with
 * {@code StringEquals} {@code iam:AWSServiceName} {@code replication.ecr.amazonaws.com}, as most AWS
 * managed policies that create a service-linked role do. The key comes from the request's
 * {@code AWSServiceName} member.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamAwsServiceNameConditionEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String POLICY = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryFullAccess";
    private static final String ROLE = "AWSServiceRoleForECRReplication";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aManagedPolicyAllowsOnlyTheServiceLinkedRoleItNames() {
        String userName = "ecr-admin-" + UUID.randomUUID().toString().substring(0, 8);
        UserCredentials user = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"),
                userName);
        iam(ADMIN, "AttachUserPolicy").formParam("UserName", userName).formParam("PolicyArn", POLICY)
                .post("/").then().statusCode(200);
        cleanup.register(() -> iam(ADMIN, "DeleteServiceLinkedRole").formParam("RoleName", ROLE).post("/"));

        iam(user.accessKeyId(), "CreateServiceLinkedRole")
                .formParam("AWSServiceName", "memorydb.amazonaws.com").post("/").then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"))
                .body(containsString("iam:CreateServiceLinkedRole"));
        iam(user.accessKeyId(), "CreateServiceLinkedRole")
                .formParam("AWSServiceName", "replication.ecr.amazonaws.com").post("/").then()
                .statusCode(200)
                .body(containsString("<RoleName>" + ROLE + "</RoleName>"));
    }

    private static RequestSpecification iam(String accessKeyId, String action) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", auth(accessKeyId, "iam"))
                .formParam("Action", action);
    }
}
