package io.floci.aws.services.emr;

import io.floci.aws.core.common.Html;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EmrRequests.emr;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * A cluster's application UIs over the AWS JSON 1.1 wire protocol: CreatePersistentAppUI, DescribePersistentAppUI and
 * the two presigned URL operations, and the page Floci serves for their URLs, as it serves no application UI itself.
 */
@QuarkusTest
class EmrAppUiIntegrationTest {

    private static final String ARN = "arn:aws:elasticmapreduce:us-east-1:000000000000:cluster/";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aPersistentAppUiIsMadeForAClusterAndItsPresignedUrlOpensFlocisPage() {
        String clusterId = cluster("emr-6.15.0", "[{\"Name\":\"Spark\"},{\"Name\":\"Tez\"},{\"Name\":\"Hadoop\"}]", "");

        String appUiId = emr("CreatePersistentAppUI", "{\"TargetResourceArn\":\"" + ARN + clusterId + "\","
                        + "\"Tags\":[{\"Key\":\"team\",\"Value\":\"data\"}]}")
                .then().statusCode(200)
                .body("PersistentAppUIId", matchesPattern("p-[A-Z0-9]{13}"))
                .body("RuntimeRoleEnabledCluster", equalTo(false))
                .extract().path("PersistentAppUIId");
        emr("DescribePersistentAppUI", "{\"PersistentAppUIId\":\"" + appUiId + "\"}")
                .then().statusCode(200)
                .body("PersistentAppUI.PersistentAppUIId", equalTo(appUiId))
                .body("PersistentAppUI.PersistentAppUITypeList", equalTo(List.of("SHS", "TEZ", "YTS")))
                .body("PersistentAppUI.PersistentAppUIStatus", equalTo("ACTIVE"))
                .body("PersistentAppUI.AuthorId", equalTo("000000000000"))
                .body("PersistentAppUI.CreationTime", notNullValue())
                .body("PersistentAppUI.Tags[0].Key", equalTo("team"));

        String url = emr("GetPersistentAppUIPresignedURL", "{\"PersistentAppUIId\":\"" + appUiId + "\","
                        + "\"PersistentAppUIType\":\"TEZ\",\"ApplicationId\":\"application_1_0001\"}")
                .then().statusCode(200)
                .body("PresignedURLReady", equalTo(true))
                .extract().path("PresignedURL");
        page(url).then().statusCode(200).contentType(containsString("text/html"))
                .body(containsString("<h1>TEZ of cluster " + clusterId + "</h1>"))
                .body(containsString("<tr><th>Application</th><td>application_1_0001</td></tr>"))
                .body(containsString(Html.escape(EmrAppUiController.NO_UI)));
        page(url.replace("type=TEZ", "type=SHS")).then().statusCode(403)
                .body(containsString(Html.escape(EmrAppUiController.INVALID)));
        String withoutApplication = emr("GetPersistentAppUIPresignedURL", "{\"PersistentAppUIId\":\"" + appUiId
                        + "\"}")
                .then().statusCode(200).extract().path("PresignedURL");
        page(withoutApplication).then().statusCode(200);
        // A link with no application is not one for an application named "null".
        page(withoutApplication.replace("&expires=", "&application=null&expires=")).then().statusCode(403);
        terminate(clusterId);
    }

    @Test
    void theProfilerTypeAskedForIsTheUisOneInterfaceAndATagNeedsAKey() {
        String clusterId = cluster("emr-6.15.0", "[{\"Name\":\"Spark\"},{\"Name\":\"Tez\"}]", "");
        String appUiId = emr("CreatePersistentAppUI", "{\"TargetResourceArn\":\"" + ARN + clusterId + "\","
                        + "\"ProfilerType\":\"TEZUI\"}")
                .then().statusCode(200).extract().path("PersistentAppUIId");
        emr("DescribePersistentAppUI", "{\"PersistentAppUIId\":\"" + appUiId + "\"}")
                .then().statusCode(200)
                .body("PersistentAppUI.PersistentAppUITypeList", equalTo(List.of("TEZ")));
        emr("CreatePersistentAppUI", "{\"TargetResourceArn\":\"" + ARN + clusterId + "\",\"ProfilerType\":\"YTS\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("CreatePersistentAppUI", "{\"TargetResourceArn\":\"" + ARN + clusterId + "\","
                        + "\"Tags\":[{\"Value\":\"no-key\"}]}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        terminate(clusterId);
    }

    @Test
    void anOnClusterUrlIsThereWhileTheClusterRunsAndADryRunGivesNone() {
        String clusterId = cluster("emr-6.15.0", "[{\"Name\":\"Spark\"},{\"Name\":\"Hadoop\"}]", "");

        String url = emr("GetOnClusterAppUIPresignedURL", "{\"ClusterId\":\"" + clusterId + "\","
                        + "\"OnClusterAppUIType\":\"SparkHistoryServer\"}")
                .then().statusCode(200)
                .body("PresignedURLReady", equalTo(true))
                .extract().path("PresignedURL");
        page(url).then().statusCode(200)
                .body(containsString("<h1>SparkHistoryServer of cluster " + clusterId + "</h1>"));
        emr("GetOnClusterAppUIPresignedURL", "{\"ClusterId\":\"" + clusterId + "\",\"DryRun\":true}")
                .then().statusCode(200)
                .body("PresignedURLReady", equalTo(true))
                .body("PresignedURL", nullValue());
        emr("GetOnClusterAppUIPresignedURL", "{\"ClusterId\":\"" + clusterId + "\","
                        + "\"OnClusterAppUIType\":\"Ganglia\"}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));

        terminate(clusterId);
        emr("GetOnClusterAppUIPresignedURL", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void aPersistentAppUiIsRefusedForAnOldReleaseAnotherResourceOrAnInterfaceTheClusterHasNot() {
        String old = cluster("emr-5.24.0", "[{\"Name\":\"Spark\"}]", "");
        emr("CreatePersistentAppUI", "{\"TargetResourceArn\":\"" + ARN + old + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("CreatePersistentAppUI", "{\"TargetResourceArn\":\"arn:aws:emr-containers:us-east-1:000000000000:"
                        + "/virtualclusters/abc\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("CreatePersistentAppUI", "{}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));

        String sparkOnly = cluster("emr-5.29.0", "[{\"Name\":\"Spark\"}]", "");
        String appUiId = emr("CreatePersistentAppUI", "{\"TargetResourceArn\":\"" + ARN + sparkOnly + "\"}")
                .then().statusCode(200).extract().path("PersistentAppUIId");
        emr("DescribePersistentAppUI", "{\"PersistentAppUIId\":\"" + appUiId + "\"}")
                .then().statusCode(200)
                .body("PersistentAppUI.PersistentAppUITypeList", equalTo(List.of("SHS")));
        emr("GetPersistentAppUIPresignedURL", "{\"PersistentAppUIId\":\"" + appUiId + "\","
                        + "\"PersistentAppUIType\":\"TEZ\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        terminate(old);
        terminate(sparkOnly);
    }

    @Test
    void aClusterWhoseSecurityConfigurationTurnsOnRuntimeRolesIsReportedAsOne() {
        String name = "runtime-roles-" + UUID.randomUUID();
        emr("CreateSecurityConfiguration", "{\"Name\":\"" + name + "\",\"SecurityConfiguration\":"
                        + "\"{\\\"AuthorizationConfiguration\\\":{\\\"IAMConfiguration\\\":"
                        + "{\\\"EnableApplicationScopedIAMRole\\\":true}}}\"}")
                .then().statusCode(200);
        String clusterId = cluster("emr-6.15.0", "[{\"Name\":\"Spark\"}]",
                "\"SecurityConfiguration\": \"" + name + "\",");

        emr("CreatePersistentAppUI", "{\"TargetResourceArn\":\"" + ARN + clusterId + "\"}")
                .then().statusCode(200)
                .body("RuntimeRoleEnabledCluster", equalTo(true));
        terminate(clusterId);
    }

    private static String cluster(String releaseLabel, String applications, String extra) {
        return emr("RunJobFlow", """
                {"Name": "app-uis", "ReleaseLabel": "%s", "Applications": %s, %s
                 "Instances": {"KeepJobFlowAliveWhenNoSteps": true,
                   "InstanceGroups": [{"Name": "primary", "InstanceRole": "MASTER", "InstanceType": "m5.xlarge",
                     "InstanceCount": 1}]}}
                """.formatted(releaseLabel, applications, extra))
                .then().statusCode(200).extract().path("JobFlowId");
    }

    private static void terminate(String clusterId) {
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);
    }

    // The page a link opens, asked for on the address this test's Floci listens on, which the link's base URL is not.
    @Test
    void aRequestToAnInterfacesHostReachesItsProxyWhateverItsPath() {
        // The ResourceManager's REST answers are under /ws/, a path API Gateway's WebSocket route takes too.
        given().header("Host", "a".repeat(26) + "-8088.emrappui.localhost:4566")
                .when().get("/ws/v1/cluster/apps")
                .then().statusCode(403).body(containsString(Html.escape(EmrAppUiController.INVALID)));
        given().header("Host", "a".repeat(26) + "-4040.emr-serverless-ui.localhost:4566")
                .when().get("/ws/v1/cluster/apps")
                .then().statusCode(403);
    }

    private static Response page(String url) {
        URI link = URI.create(url);
        return given().urlEncodingEnabled(false).when().get(link.getRawPath() + "?" + link.getRawQuery());
    }
}
