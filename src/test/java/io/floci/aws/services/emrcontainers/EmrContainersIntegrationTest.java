package io.floci.aws.services.emrcontainers;

import io.floci.aws.testing.EksRequests;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class EmrContainersIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-job";
    private static final String RELEASE = "emr-7.5.0-latest";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification emr() {
        return emr(REGION);
    }

    private static RequestSpecification emr(String region) {
        return given().header("Authorization", EnforcementFixtures.auth(ACCOUNT, region, "emr-containers"))
                .contentType("application/json")
                .urlEncodingEnabled(false);
    }

    private static String eksCluster() {
        return eksCluster("CONFIG_MAP");
    }

    private static String eksCluster(String authenticationMode) {
        String name = "emr-" + UUID.randomUUID().toString().substring(0, 8);
        EksRequests.eks(ACCOUNT, REGION)
                .body("""
                        {"name": "%s", "roleArn": "arn:aws:iam::000000000000:role/eks-role", "version": "1.29",
                         "accessConfig": {"authenticationMode": "%s"}}
                        """.formatted(name, authenticationMode))
                .post("/clusters")
                .then().statusCode(200);
        return name;
    }

    private static String virtualCluster(String eksCluster) {
        return emr().body("""
                        {"name": "vc-%s", "clientToken": "%s",
                         "containerProvider": {"type": "EKS", "id": "%s", "info": {"eksInfo": {"namespace": "spark"}}},
                         "tags": {"team": "data"}}
                        """.formatted(UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(), eksCluster))
                .post("/virtualclusters")
                .then().statusCode(200)
                .body("id", matchesPattern("[0-9a-z]{25}"))
                .body("arn", matchesPattern(
                        "arn:aws:emr-containers:us-east-1:000000000000:/virtualclusters/[0-9a-z]{25}"))
                .extract().path("id");
    }

    private static String startJobRun(String virtualClusterId) {
        return emr().body("""
                        {"name": "pi", "clientToken": "%s", "executionRoleArn": "%s", "releaseLabel": "%s",
                         "jobDriver": {"sparkSubmitJobDriver": {"entryPoint": "s3://bucket/pi.py",
                           "sparkSubmitParameters": "--conf spark.executor.instances=2"}},
                         "tags": {"run": "nightly"}}
                        """.formatted(UUID.randomUUID(), ROLE, RELEASE))
                .post("/virtualclusters/" + virtualClusterId + "/jobruns")
                .then().statusCode(200)
                .body("virtualClusterId", equalTo(virtualClusterId))
                .body("name", equalTo("pi"))
                .extract().path("id");
    }

    private static String encoded(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    @Test
    void virtualClusterLifecycleOverTheWire() {
        String id = virtualCluster(eksCluster());

        emr().get("/virtualclusters/" + id)
                .then().statusCode(200)
                .body("virtualCluster.state", equalTo("RUNNING"))
                .body("virtualCluster.containerProvider.info.eksInfo.namespace", equalTo("spark"))
                .body("virtualCluster.createdAt", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"))
                .body("virtualCluster.tags.team", equalTo("data"))
                .body("virtualCluster", not(hasKey("_clientToken")));

        emr().get("/virtualclusters?states=RUNNING&maxResults=50")
                .then().statusCode(200)
                .body("virtualClusters.id", hasItem(id));

        emr().delete("/virtualclusters/" + id)
                .then().statusCode(200).body("id", equalTo(id));
        emr().get("/virtualclusters/" + id)
                .then().statusCode(200)
                .body("virtualCluster.state", equalTo("TERMINATED"))
                .body("virtualCluster.tags.size()", equalTo(0));
    }

    @Test
    void jobRunCompletesAndIsListedAndDescribed() {
        String clusterId = virtualCluster(eksCluster());
        String runId = startJobRun(clusterId);

        emr().get("/virtualclusters/" + clusterId + "/jobruns/" + runId)
                .then().statusCode(200)
                .body("jobRun.id", matchesPattern("[0-9a-z]{19}"))
                .body("jobRun.state", equalTo("COMPLETED"))
                .body("jobRun.finishedAt", notNullValue())
                .body("jobRun.createdBy", notNullValue())
                .body("jobRun.clientToken", notNullValue())
                .body("jobRun.jobDriver.sparkSubmitJobDriver.entryPoint", equalTo("s3://bucket/pi.py"))
                .body("jobRun.tags.run", equalTo("nightly"));

        emr().get("/virtualclusters/" + clusterId + "/jobruns?states=COMPLETED&name=pi")
                .then().statusCode(200)
                .body("jobRuns", hasSize(1))
                .body("jobRuns[0].id", equalTo(runId));

        emr().delete("/virtualclusters/" + clusterId + "/jobruns/" + runId)
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void jobRunOnAMissingVirtualClusterAnswersAwsDocumentedError() {
        emr().body("""
                        {"clientToken": "c", "executionRoleArn": "%s", "releaseLabel": "%s",
                         "jobDriver": {"sparkSubmitJobDriver": {"entryPoint": "s3://bucket/pi.py"}}}
                        """.formatted(ROLE, RELEASE))
                .post("/virtualclusters/nosuchcluster/jobruns")
                .then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"))
                .body("message", equalTo("Virtual cluster nosuchcluster doesn't exist."));
    }

    @Test
    void jobRunOnAClusterEksDoesNotHaveFails() {
        String clusterId = virtualCluster("no-such-eks-cluster");
        String runId = startJobRun(clusterId);

        emr().get("/virtualclusters/" + clusterId + "/jobruns/" + runId)
                .then().statusCode(200)
                .body("jobRun.state", equalTo("FAILED"))
                .body("jobRun.stateDetails", equalTo("Cluster no-such-eks-cluster does not exist."))
                .body("jobRun.failureReason", equalTo("CLUSTER_UNAVAILABLE"));
    }

    @Test
    void modelConstraintsAnswerValidationException() {
        emr().body("""
                        {"name": "bad name", "clientToken": "c", "containerProvider": {"type": "EKS", "id": "x"}}
                        """)
                .post("/virtualclusters")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
        emr().body("{\"name\": \"vc\", \"clientToken\": \"c\"}")
                .post("/virtualclusters")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("containerProvider"));
        emr().get("/virtualclusters/NOT-AN-ID")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void jobTemplateRunsWithItsParameters() {
        String clusterId = virtualCluster(eksCluster());
        String templateId = emr().body("""
                        {"name": "nightly", "clientToken": "%s", "jobTemplateData": {
                           "executionRoleArn": "%s", "releaseLabel": "%s",
                           "jobDriver": {"sparkSubmitJobDriver": {"entryPoint": "${EntryPoint}"}},
                           "parameterConfiguration": {"EntryPoint": {"type": "STRING"}}}}
                        """.formatted(UUID.randomUUID(), ROLE, RELEASE))
                .post("/jobtemplates")
                .then().statusCode(200)
                .body("arn", containsString(":/jobtemplates/"))
                .body("createdAt", notNullValue())
                .extract().path("id");

        emr().get("/jobtemplates/" + templateId)
                .then().statusCode(200)
                .body("jobTemplate.jobTemplateData.jobDriver.sparkSubmitJobDriver.entryPoint",
                        equalTo("${EntryPoint}"));

        String runId = emr().body("""
                        {"clientToken": "%s", "jobTemplateId": "%s",
                         "jobTemplateParameters": {"EntryPoint": "s3://bucket/job.py"}}
                        """.formatted(UUID.randomUUID(), templateId))
                .post("/virtualclusters/" + clusterId + "/jobruns")
                .then().statusCode(200).extract().path("id");

        emr().get("/virtualclusters/" + clusterId + "/jobruns/" + runId)
                .then().statusCode(200)
                .body("jobRun.jobDriver.sparkSubmitJobDriver.entryPoint", equalTo("s3://bucket/job.py"))
                .body("jobRun.executionRoleArn", equalTo(ROLE));

        emr().delete("/jobtemplates/" + templateId).then().statusCode(200).body("id", equalTo(templateId));
        emr().get("/jobtemplates/" + templateId)
                .then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void managedEndpointAndSessionCredentials() {
        String clusterId = virtualCluster(eksCluster());
        String endpointId = emr().body("""
                        {"name": "studio", "type": "JUPYTER_ENTERPRISE_GATEWAY", "releaseLabel": "%s",
                         "executionRoleArn": "%s", "clientToken": "%s"}
                        """.formatted(RELEASE, ROLE, UUID.randomUUID()))
                .post("/virtualclusters/" + clusterId + "/endpoints")
                .then().statusCode(200)
                .body("id", matchesPattern("[0-9a-z]{13}"))
                .extract().path("id");

        emr().get("/virtualclusters/" + clusterId + "/endpoints?types=JUPYTER_ENTERPRISE_GATEWAY")
                .then().statusCode(200)
                .body("endpoints", hasSize(1))
                .body("endpoints[0].state", equalTo("ACTIVE"));

        emr().body("{\"executionRoleArn\": \"%s\", \"credentialType\": \"TOKEN\"}".formatted(ROLE))
                .post("/virtualclusters/" + clusterId + "/endpoints/" + endpointId + "/credentials")
                .then().statusCode(200)
                .body("credentials.token", notNullValue())
                .body("expiresAt", notNullValue());

        emr().delete("/virtualclusters/" + clusterId + "/endpoints/" + endpointId)
                .then().statusCode(200).body("id", equalTo(endpointId));
        emr().get("/virtualclusters/" + clusterId + "/endpoints/" + endpointId)
                .then().statusCode(200).body("endpoint.state", equalTo("TERMINATED"));
    }

    @Test
    void securityConfigurationLifecycle() {
        String id = emr().body("""
                        {"name": "sc", "clientToken": "%s", "securityConfigurationData": {
                           "authorizationConfiguration": {
                             "lakeFormationConfiguration": {"authorizedSessionTagValue": "EMR"}}}}
                        """.formatted(UUID.randomUUID()))
                .post("/securityconfigurations")
                .then().statusCode(200)
                .body("arn", containsString(":/securityconfigurations/"))
                .extract().path("id");

        emr().get("/securityconfigurations/" + id)
                .then().statusCode(200)
                .body("securityConfiguration.securityConfigurationData.authorizationConfiguration"
                        + ".lakeFormationConfiguration.authorizedSessionTagValue", equalTo("EMR"));
        emr().get("/securityconfigurations")
                .then().statusCode(200)
                .body("securityConfigurations.id", hasItem(id));
        emr().delete("/securityconfigurations/" + id).then().statusCode(200);
        emr().delete("/securityconfigurations/" + id)
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void tagsGoThroughTheSharedTagsPath() {
        String clusterId = virtualCluster(eksCluster());
        String arn = emr().get("/virtualclusters/" + clusterId).then().extract().path("virtualCluster.arn");

        emr().body("{\"tags\": {\"env\": \"test\"}}")
                .post("/tags/" + encoded(arn))
                .then().statusCode(200);
        emr().get("/tags/" + encoded(arn))
                .then().statusCode(200)
                .body("tags.team", equalTo("data"))
                .body("tags.env", equalTo("test"));
        emr().delete("/tags/" + encoded(arn) + "?tagKeys=team")
                .then().statusCode(200);
        emr().get("/tags/" + encoded(arn))
                .then().statusCode(200)
                .body("tags", not(hasKey("team")));
    }

    @Test
    void anAnotherRegionsRequestDoesNotFindTheResourceToTag() {
        String clusterId = virtualCluster(eksCluster());
        String arn = emr().get("/virtualclusters/" + clusterId).then().extract().path("virtualCluster.arn");

        emr("eu-west-1").body("{\"tags\": {\"env\": \"test\"}}")
                .post("/tags/" + encoded(arn))
                .then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
        emr().get("/tags/" + encoded(arn))
                .then().statusCode(200)
                .body("tags", not(hasKey("env")));
    }

    @Test
    void theAccessEntryFilterKeepsVirtualClustersOfEksClustersAdmittingAccessEntries() {
        String plain = virtualCluster(eksCluster("CONFIG_MAP"));
        String integrated = virtualCluster(eksCluster("API_AND_CONFIG_MAP"));

        emr().get("/virtualclusters?eksAccessEntryIntegrated=true&maxResults=1000")
                .then().statusCode(200)
                .body("virtualClusters.id", hasItem(integrated))
                .body("virtualClusters.id", not(hasItem(plain)));
        emr().get("/virtualclusters?eksAccessEntryIntegrated=false&maxResults=1000")
                .then().statusCode(200)
                .body("virtualClusters.id", hasItem(plain))
                .body("virtualClusters.id", not(hasItem(integrated)));
        emr().get("/virtualclusters?eksAccessEntryIntegrated=maybe")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void aRequestSignedForS3KeepsThePathAsABucket() {
        given().header("Authorization", EnforcementFixtures.auth(ACCOUNT, REGION, "s3"))
                .get("/virtualclusters")
                .then().statusCode(404)
                .body(containsString("NoSuchBucket"));
    }
}
