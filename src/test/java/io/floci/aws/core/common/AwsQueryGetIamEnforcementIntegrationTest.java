package io.floci.aws.core.common;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AwsQueryGetIamEnforcementIntegrationTest {

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"false,Action", "false,Operation", "true,Action", "true,Operation"})
    void getRequiresPermissionForTheQueryService(boolean presigned, String actionParameter) throws Exception {
        String user = "query-get-" + UUID.randomUUID();
        String admin = auth("000000000000", "iam");
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, baseUri.getPort(), admin, user);
        putPolicy(admin, user, "s3:ListAllMyBuckets");
        describe(credentials, presigned, actionParameter).statusCode(403)
                .contentType("application/xml")
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
        putPolicy(admin, user, "elasticloadbalancing:DescribeLoadBalancers");
        describe(credentials, presigned, actionParameter).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
    }

    private static ValidatableResponse describe(EnforcementFixtures.UserCredentials credentials,
                                                boolean presigned, String actionParameter) throws Exception {
        AwsRequestSigner signer = AwsRequestSigner.signedAs(credentials.accessKeyId(),
                credentials.secretAccessKey(), "elasticloadbalancing");
        Map<String, String> query = Map.of(actionParameter, "DescribeLoadBalancers", "Version", "2012-06-01",
                "LoadBalancerNames.member.1", "missing-" + UUID.randomUUID().toString().substring(0, 8));
        if (presigned) {
            return given().urlEncodingEnabled(false)
                    .get(signer.presignedUri("GET", baseUri, query, new byte[0])).then();
        }
        return given().filter(signer).queryParams(query).get("/").then();
    }

    private static void putPolicy(String admin, String user, String action) {
        given().header("Authorization", admin).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "PutUserPolicy").formParam("UserName", user)
                .formParam("PolicyName", "query-read")
                .formParam("PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{
                          "Effect":"Allow","Action":"%s","Resource":"*"
                        }]}
                        """.formatted(action))
                .post("/").then().statusCode(200);
    }
}
