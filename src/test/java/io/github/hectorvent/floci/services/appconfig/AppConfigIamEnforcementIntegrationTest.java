package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AppConfigIamEnforcementIntegrationTest {
    private static final String ACCOUNT = "111111111111";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void deniedUpdateDoesNotChangeTheApplication() {
        String applicationId = given()
                .header("Authorization", auth(ACCOUNT, "appconfig"))
                .contentType("application/json")
                .body("{\"Name\":\"iam-update-app\",\"Description\":\"original\"}")
                .when().post("/applications")
                .then().statusCode(201)
                .extract().path("Id");
        String user = "appconfig-update-deny-" + UUID.randomUUID();
        String accessKeyId = EnforcementFixtures.createUser(
                cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), user).accessKeyId();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "deny-update", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"*","Resource":"*"},
                  {"Effect":"Deny","Action":"appconfig:UpdateApplication","Resource":"*"}]}
                """);

        try {
            given()
                    .header("Authorization", auth(accessKeyId, "appconfig"))
                    .contentType("application/json")
                    .body("{\"Name\":\"denied-name\"}")
                    .when().patch("/applications/" + applicationId)
                    .then().statusCode(403)
                    .body("__type", equalTo("AccessDeniedException"));

            given()
                    .header("Authorization", auth(ACCOUNT, "appconfig"))
                    .when().get("/applications/" + applicationId)
                    .then().statusCode(200)
                    .body("Name", equalTo("iam-update-app"))
                    .body("Description", equalTo("original"));
        } finally {
            given()
                    .header("Authorization", auth(ACCOUNT, "appconfig"))
                    .when().delete("/applications/" + applicationId)
                    .then().statusCode(204);
        }
    }
}
