package io.floci.aws.core.common;

import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class AwsQueryGetIntegrationTest {

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({
            "elasticloadbalancing,DescribeLoadBalancers,2012-06-01",
            "elasticloadbalancing,DescribeLoadBalancers,2015-12-01",
            "autoscaling,DescribeTags,2011-01-01",
            "ec2,DescribeAvailabilityZones,2016-11-15",
            "iam,ListUsers,2010-05-08",
            "sts,GetCallerIdentity,2011-06-15",
            "sns,ListTopics,2010-03-31",
            "sqs,ListQueues,2012-11-05",
            "rds,DescribeDBInstances,2014-10-31",
            "ses,GetSendQuota,2010-12-01",
            "docdb,DescribeDBClusters,2014-10-31",
            "neptune,DescribeDBClusters,2014-10-31"
    })
    void queryGetUsesTheSigningServiceAndApiVersion(String service, String action, String version) {
        ValidatableResponse response = given().header("Authorization", auth("test", service))
                .queryParam("Action", action).queryParam("Version", version)
                .get("/").then().statusCode(200)
                .contentType("application/xml").body(containsString(action + "Response"));
        if ("elasticloadbalancing".equals(service) || "autoscaling".equals(service)) {
            response.body(containsString("/doc/" + version + "/"));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "elasticloadbalancing,DescribeLoadBalancers,2012-06-01",
            "ec2,DescribeAvailabilityZones,2016-11-15",
            "iam,ListUsers,2010-05-08"
    })
    void signingServiceKeepsQueryGetWhenTargetAndBrowserHeadersArePresent(
            String service, String action, String version) {
        given().header("Authorization", auth("test", service))
                .header("X-Amz-Target", "AmazonSSM.GetParameter").header("Accept", "text/html")
                .queryParam("Action", action).queryParam("Version", version)
                .get("/").then().statusCode(200).body(containsString(action + "Response"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Action", "Operation"})
    void getDescribesAndDeletesTheSameClassicLoadBalancerAsPost(String actionParameter) {
        String name = "query-get-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> identity = Map.of(
                "Version", "2012-06-01", "LoadBalancerName", name);
        cleanup.register(() -> given().header("Authorization", auth("test", "elasticloadbalancing"))
                .contentType("application/x-www-form-urlencoded")
                .formParams(identity).formParam("Action", "DeleteLoadBalancer").post("/"));
        given().header("Authorization", auth("test", "elasticloadbalancing"))
                .contentType("application/x-www-form-urlencoded")
                .formParams(identity).formParam("Action", "CreateLoadBalancer")
                .formParam("Listeners.member.1.Protocol", "HTTP")
                .formParam("Listeners.member.1.LoadBalancerPort", "80")
                .formParam("Listeners.member.1.InstancePort", "8080")
                .post("/").then().statusCode(200);
        classicGet(actionParameter, name).get("/").then().statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.LoadBalancerName", equalTo(name));
        given().header("Authorization", auth("test", "elasticloadbalancing"))
                .queryParams(identity).queryParam(actionParameter, "DeleteLoadBalancer")
                .get("/").then().statusCode(200);
        classicGet(actionParameter, name).get("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
    }

    @Test
    void presignedGetKeepsTheVersionAndReturnsTheClassicError() throws Exception {
        presignedClassicGet(AwsRequestSigner.signedAs("test", "test", "elasticloadbalancing"),
                "missing-query-get-load-balancer").statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
    }

    @Test
    void presignedGetUsesTheCredentialAccountAndNonDefaultRegion() throws Exception {
        String name = "query-region-" + UUID.randomUUID().toString().substring(0, 8);
        AwsRequestSigner signer = AwsRequestSigner.signedAs("777777777777", "test", "elasticloadbalancing")
                .inRegion("eu-west-1");
        cleanup.register(() -> given().port(baseUri.getPort()).filter(signer)
                .formParam("Action", "DeleteLoadBalancer").formParam("Version", "2012-06-01")
                .formParam("LoadBalancerName", name).post("/"));
        given().filter(signer).formParam("Action", "CreateLoadBalancer")
                .formParam("Version", "2012-06-01").formParam("LoadBalancerName", name)
                .formParam("Listeners.member.1.Protocol", "HTTP")
                .formParam("Listeners.member.1.LoadBalancerPort", "80")
                .formParam("Listeners.member.1.InstancePort", "8080")
                .post("/").then().statusCode(200);
        presignedClassicGet(signer, name).statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.LoadBalancerName", equalTo(name));
        for (AwsRequestSigner foreign : new AwsRequestSigner[] {signer.inRegion("us-east-1"),
                AwsRequestSigner.signedAs("888888888888", "test", "elasticloadbalancing")
                        .inRegion("eu-west-1")}) {
            presignedClassicGet(foreign, name).statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
        }
    }

    @Test
    void missingActionReturnsAQueryError() {
        given().header("Authorization", auth("test", "elasticloadbalancing"))
                .queryParam("Version", "2012-06-01").get("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("MissingAction"));
    }

    private static RequestSpecification classicGet(String actionParameter, String name) {
        return given().header("Authorization", auth("test", "elasticloadbalancing"))
                .queryParam(actionParameter, "DescribeLoadBalancers")
                .queryParam("Version", "2012-06-01")
                .queryParam("LoadBalancerNames.member.1", name);
    }

    private static ValidatableResponse presignedClassicGet(AwsRequestSigner signer, String name) throws Exception {
        Map<String, String> query = Map.of("Action", "DescribeLoadBalancers", "Version", "2012-06-01",
                "LoadBalancerNames.member.1", name);
        return given().urlEncodingEnabled(false)
                .get(signer.presignedUri("GET", baseUri, query, new byte[0])).then();
    }
}
