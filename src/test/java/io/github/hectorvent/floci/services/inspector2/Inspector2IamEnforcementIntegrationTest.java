package io.github.hectorvent.floci.services.inspector2;

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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Inspector2IamEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void explicitDenyPreventsDisablingScans() {
        String user = "inspector2-iam-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ACCOUNT, "iam"), user).accessKeyId();
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"*","Resource":"*"},
                  {"Effect":"Deny","Action":"inspector2:Disable","Resource":"*"}
                ]}""";
        given().formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "p")
                .formParam("PolicyDocument", policy)
                .header("Authorization", auth(ACCOUNT, "iam"))
                .post("/").then().statusCode(200);

        given().contentType("application/json").header("Authorization", auth(ACCOUNT, "inspector2"))
                .body("{\"resourceTypes\":[\"EC2\"]}")
                .post("/enable").then().statusCode(200);

        given().contentType("application/json").header("Authorization", auth(accessKeyId, "inspector2"))
                .body("{\"resourceTypes\":[\"EC2\"]}")
                .post("/disable").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("inspector2:Disable"));
    }
}
