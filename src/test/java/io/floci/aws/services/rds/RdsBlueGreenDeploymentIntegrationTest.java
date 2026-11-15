package io.floci.aws.services.rds;

import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

/**
 * CreateBlueGreenDeployment, DescribeBlueGreenDeployments, SwitchoverBlueGreenDeployment and
 * DeleteBlueGreenDeployment over the Query protocol: the green instance is a linked copy of the
 * blue one with the target settings, switchover hands green the blue identifier, ARN, endpoint
 * and tags while blue becomes {@code -old1}, and each operation answers the API reference's
 * errors for the states that refuse it.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsBlueGreenDeploymentIntegrationTest {

    private static final String BLUE = "bg-test-blue";
    private static final String NAME = "bg-test-upgrade";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static String deploymentId;
    private static String blueArn;
    private static String greenId;
    private static int bluePort;

    /**
     * Runs last. The test profile's application is shared with the other RDS mock tests, so every
     * instance this class created is deleted to hand its endpoint port back to the pool. It is an
     * ordered test rather than an AfterAll because RestAssured's port is reset before AfterAll.
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void deleteCreatedResources() {
        query("DescribeDBInstances").when().post("/").then().statusCode(200).extract().xmlPath()
                .getList("**.findAll { it.name() == 'DBInstanceIdentifier' }", String.class).stream()
                .filter(id -> id.startsWith("bg-test-"))
                .forEach(id -> query("DeleteDBInstance")
                        .formParam("DBInstanceIdentifier", id)
                        .formParam("SkipFinalSnapshot", "true")
                .when().post("/"));
        query("DeleteDBCluster")
                .formParam("DBClusterIdentifier", "bg-test-cluster")
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/");
    }

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static String find(XmlPath xml, String element) {
        return xml.getString("**.find { it.name() == '" + element + "' }");
    }

    private static XmlPath createInstance(String id, String engine, String version, String retention) {
        return query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", id)
                .formParam("Engine", engine)
                .formParam("EngineVersion", version)
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("AllocatedStorage", "20")
                .formParam("BackupRetentionPeriod", retention)
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "prod")
        .when().post("/").then().statusCode(200).extract().xmlPath();
    }

    private static XmlPath describeInstance(String id) {
        return query("DescribeDBInstances")
                .formParam("DBInstanceIdentifier", id)
        .when().post("/").then().statusCode(200).extract().xmlPath();
    }

    private static XmlPath createDeployment(String name, String sourceArn) {
        return query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", name)
                .formParam("Source", sourceArn)
        .when().post("/").then().statusCode(200).extract().xmlPath();
    }

    @Test
    @Order(1)
    void createStagesAnUpgradedGreenCopyLinkedToBlue() {
        XmlPath blue = createInstance(BLUE, "postgres", "16.3", "1");
        blueArn = find(blue, "DBInstanceArn");
        bluePort = Integer.parseInt(find(blue, "Port"));

        XmlPath created = query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", NAME)
                .formParam("Source", blueArn)
                .formParam("TargetEngineVersion", "17.4")
                .formParam("TargetDBInstanceClass", "db.t3.medium")
                .formParam("Tags.Tag.1.Key", "change")
                .formParam("Tags.Tag.1.Value", "pg17")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CreateBlueGreenDeploymentResult>"))
            .body(containsString("<BlueGreenDeploymentName>" + NAME + "</BlueGreenDeploymentName>"))
            .body(containsString("<Source>" + blueArn + "</Source>"))
            .body(containsString("<Name>CREATING_READ_REPLICA_OF_SOURCE</Name>"))
            .body(containsString("<Name>DB_ENGINE_VERSION_UPGRADE</Name>"))
            .body(containsString("<Status>AVAILABLE</Status>"))
            .body(containsString("<Key>change</Key>"))
            .extract().xmlPath();

        deploymentId = find(created, "BlueGreenDeploymentIdentifier");
        assertThat(deploymentId, matchesPattern("bgd-[a-z0-9]{16}"));
        String target = find(created, "Target");
        assertThat(target, matchesPattern(".*:db:" + BLUE + "-green-[a-z0-9]{6}"));
        assertThat(find(created, "SourceMember"), equalTo(blueArn));
        assertThat(find(created, "TargetMember"), equalTo(target));
        greenId = target.substring(target.lastIndexOf(':') + 1);

        XmlPath green = describeInstance(greenId);
        assertThat(find(green, "EngineVersion"), equalTo("17.4"));
        assertThat(find(green, "DBInstanceClass"), equalTo("db.t3.medium"));
        assertThat(find(green, "ReadReplicaSourceDBInstanceIdentifier"), equalTo(BLUE));
        assertThat(find(green, "DBInstanceStatus"), equalTo("available"));
        assertThat(find(green, "Value"), equalTo("prod"));
        assertThat(find(describeInstance(BLUE), "ReadReplicaDBInstanceIdentifier"), equalTo(greenId));
    }

    @Test
    @Order(2)
    void createRefusesWhatAwsRefuses() {
        query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", NAME)
                .formParam("Source", blueArn)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>BlueGreenDeploymentAlreadyExistsFault</Code>"));

        query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", "bg-test-second")
                .formParam("Source", blueArn)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBInstanceState</Code>"))
                .body(containsString(deploymentId));

        query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", "bg-test-missing")
                .formParam("Source", blueArn.replace(BLUE, "bg-test-nowhere"))
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBInstanceNotFound</Code>"));

        query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", "bg--bad")
                .formParam("Source", blueArn)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));

        query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", "bg-test-nosource")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));

        String older = find(createInstance("bg-test-downgrade", "postgres", "16.3", "1"), "DBInstanceArn");
        query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", "bg-test-downgrade")
                .formParam("Source", older)
                .formParam("TargetEngineVersion", "15.12")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterCombination</Code>"));

        String noBackups = find(createInstance("bg-test-nobackups", "postgres", "16.3", "0"), "DBInstanceArn");
        query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", "bg-test-nobackups")
                .formParam("Source", noBackups)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBInstanceState</Code>"))
                .body(containsString("Automated backups"));

        query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", "bg-test-greensource")
                .formParam("Source", blueArn.replace(BLUE, greenId))
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBInstanceState</Code>"))
                .body(containsString(deploymentId));

        query("CreateDBInstanceReadReplica")
                .formParam("DBInstanceIdentifier", "bg-test-replica")
                .formParam("SourceDBInstanceIdentifier", "bg-test-downgrade")
        .when().post("/").then().statusCode(200);
        query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", "bg-test-replica")
                .formParam("Source", blueArn.replace(BLUE, "bg-test-replica"))
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>SourceDatabaseNotSupportedFault</Code>"));
    }

    @Test
    @Order(3)
    void describeFindsTheDeploymentByIdentifierAndEachFilter() {
        query("DescribeBlueGreenDeployments")
                .formParam("BlueGreenDeploymentIdentifier", deploymentId)
        .when().post("/").then().statusCode(200)
                .body(containsString("<DescribeBlueGreenDeploymentsResult>"))
                .body(containsString("<BlueGreenDeployments><member>"))
                .body(containsString("<BlueGreenDeploymentIdentifier>" + deploymentId
                        + "</BlueGreenDeploymentIdentifier>"));

        for (String[] filter : new String[][] {
                {"blue-green-deployment-identifier", deploymentId},
                {"blue-green-deployment-name", NAME},
                {"source", blueArn},
                {"target", blueArn.replace(BLUE, greenId)}}) {
            query("DescribeBlueGreenDeployments")
                    .formParam("Filters.Filter.1.Name", filter[0])
                    .formParam("Filters.Filter.1.Values.Value.1", filter[1])
            .when().post("/").then().statusCode(200)
                    .body(containsString("<BlueGreenDeploymentIdentifier>" + deploymentId
                            + "</BlueGreenDeploymentIdentifier>"));
        }

        query("DescribeBlueGreenDeployments")
                .formParam("Filters.Filter.1.Name", "blue-green-deployment-name")
                .formParam("Filters.Filter.1.Values.Value.1", "bg-test-other")
        .when().post("/").then().statusCode(200)
                .body(not(containsString(deploymentId)));

        query("DescribeBlueGreenDeployments")
                .formParam("Filters.Filter.1.Name", "engine")
                .formParam("Filters.Filter.1.Values.Value.1", "postgres")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));

        query("DescribeBlueGreenDeployments")
                .formParam("MaxRecords", "10")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));

        query("DescribeBlueGreenDeployments")
                .formParam("BlueGreenDeploymentIdentifier", "bgd-doesnotexist0000")
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>BlueGreenDeploymentNotFoundFault</Code>"));
    }

    @Test
    @Order(4)
    void theDeploymentIsTaggableByItsArn() {
        String arn = blueArn.replace(":db:" + BLUE, ":deployment:" + deploymentId);
        query("AddTagsToResource")
                .formParam("ResourceName", arn)
                .formParam("Tags.Tag.1.Key", "owner")
                .formParam("Tags.Tag.1.Value", "dba")
        .when().post("/").then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", arn)
        .when().post("/").then().statusCode(200)
                .body(containsString("<Key>change</Key>"))
                .body(containsString("<Key>owner</Key>"));
    }

    @Test
    @Order(5)
    void switchoverHandsGreenTheBlueNameArnEndpointAndTags() {
        query("SwitchoverBlueGreenDeployment")
                .formParam("BlueGreenDeploymentIdentifier", deploymentId)
                .formParam("SwitchoverTimeout", "10")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));

        int greenPort = Integer.parseInt(find(describeInstance(greenId), "Port"));

        query("SwitchoverBlueGreenDeployment")
                .formParam("BlueGreenDeploymentIdentifier", deploymentId)
                .formParam("SwitchoverTimeout", "300")
        .when().post("/").then().statusCode(200)
                .body(containsString("<SwitchoverBlueGreenDeploymentResult>"))
                .body(containsString("<Status>SWITCHOVER_COMPLETED</Status>"))
                .body(containsString("<Source>" + blueArn + "-old1</Source>"))
                .body(containsString("<Target>" + blueArn + "</Target>"));

        XmlPath production = describeInstance(BLUE);
        assertThat(find(production, "EngineVersion"), equalTo("17.4"));
        assertThat(find(production, "DBInstanceArn"), equalTo(blueArn));
        assertThat(Integer.parseInt(find(production, "Port")), equalTo(bluePort));
        assertThat(find(production, "Value"), equalTo("prod"));
        assertThat(production.prettify(), not(containsString("ReadReplicaSourceDBInstanceIdentifier")));

        XmlPath old = describeInstance(BLUE + "-old1");
        assertThat(find(old, "EngineVersion"), equalTo("16.3"));
        assertThat(find(old, "DBInstanceArn"), endsWith(":db:" + BLUE + "-old1"));
        assertThat(Integer.parseInt(find(old, "Port")), equalTo(greenPort));
        assertThat(old.prettify(), not(containsString("ReadReplicaDBInstanceIdentifier>")));

        query("DescribeDBInstances")
                .formParam("DBInstanceIdentifier", greenId)
        .when().post("/").then().statusCode(404);

        query("SwitchoverBlueGreenDeployment")
                .formParam("BlueGreenDeploymentIdentifier", deploymentId)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidBlueGreenDeploymentStateFault</Code>"));
    }

    @Test
    @Order(6)
    void deleteAfterSwitchoverKeepsBothInstancesAndRefusesDeleteTarget() {
        query("DeleteBlueGreenDeployment")
                .formParam("BlueGreenDeploymentIdentifier", deploymentId)
                .formParam("DeleteTarget", "true")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidBlueGreenDeploymentStateFault</Code>"));

        query("DeleteBlueGreenDeployment")
                .formParam("BlueGreenDeploymentIdentifier", deploymentId)
        .when().post("/").then().statusCode(200)
                .body(containsString("<DeleteBlueGreenDeploymentResult>"))
                .body(containsString("<Status>DELETING</Status>"))
                .body(containsString("<DeleteTime>"));

        query("DescribeBlueGreenDeployments")
                .formParam("BlueGreenDeploymentIdentifier", deploymentId)
        .when().post("/").then().statusCode(404);
        describeInstance(BLUE);
        describeInstance(BLUE + "-old1");
    }

    @Test
    @Order(7)
    void deleteBeforeSwitchoverDeletesOrReleasesGreen() {
        String source = find(createInstance("bg-test-mysql", "mysql", "8.0.36", "1"), "DBInstanceArn");

        XmlPath first = createDeployment("bg-test-drop-green", source);
        String firstGreen = find(first, "Target").substring(find(first, "Target").lastIndexOf(':') + 1);
        query("DeleteBlueGreenDeployment")
                .formParam("BlueGreenDeploymentIdentifier", find(first, "BlueGreenDeploymentIdentifier"))
                .formParam("DeleteTarget", "true")
        .when().post("/").then().statusCode(200);
        query("DescribeDBInstances")
                .formParam("DBInstanceIdentifier", firstGreen)
        .when().post("/").then().statusCode(404);

        XmlPath second = createDeployment("bg-test-keep-green", source);
        String secondGreen = find(second, "Target").substring(find(second, "Target").lastIndexOf(':') + 1);
        query("DeleteBlueGreenDeployment")
                .formParam("BlueGreenDeploymentIdentifier", find(second, "BlueGreenDeploymentIdentifier"))
        .when().post("/").then().statusCode(200);
        assertThat(describeInstance(secondGreen).prettify(),
                not(containsString("ReadReplicaSourceDBInstanceIdentifier")));
    }

    @Test
    @Order(8)
    void aMissingGreenInstanceMakesTheDeploymentInvalid() {
        String source = find(createInstance("bg-test-orphan", "postgres", "16.3", "1"), "DBInstanceArn");
        XmlPath created = createDeployment("bg-test-orphan", source);
        String id = find(created, "BlueGreenDeploymentIdentifier");
        String green = find(created, "Target").substring(find(created, "Target").lastIndexOf(':') + 1);

        query("DeleteDBInstance")
                .formParam("DBInstanceIdentifier", green)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);

        query("DescribeBlueGreenDeployments")
                .formParam("BlueGreenDeploymentIdentifier", id)
        .when().post("/").then().statusCode(200)
                .body(containsString("<Status>INVALID_CONFIGURATION</Status>"))
                .body(containsString("<Status>MISSING_TARGET</Status>"));

        query("SwitchoverBlueGreenDeployment")
                .formParam("BlueGreenDeploymentIdentifier", id)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidBlueGreenDeploymentStateFault</Code>"));

        query("DeleteBlueGreenDeployment")
                .formParam("BlueGreenDeploymentIdentifier", id)
                .formParam("DeleteTarget", "true")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(9)
    void clusterSourcesAreNotSupported() {
        String cluster = query("CreateDBCluster")
                .formParam("DBClusterIdentifier", "bg-test-cluster")
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
        .when().post("/").then().statusCode(200).extract().xmlPath()
                .getString("**.find { it.name() == 'DBClusterArn' }");

        query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", "bg-test-cluster")
                .formParam("Source", cluster)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>SourceClusterNotSupportedFault</Code>"));
    }

    @Test
    @Order(10)
    void switchoverRefusesAPromotedGreenWithoutADescribeFirst() {
        String source = find(createInstance("bg-test-promoted", "postgres", "16.3", "1"), "DBInstanceArn");

        query("CreateBlueGreenDeployment")
                .formParam("BlueGreenDeploymentName", "bg-test-promoted")
                .formParam("Source", source)
                .formParam("TargetAllocatedStorage", "0")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));

        XmlPath created = createDeployment("bg-test-promoted", source);
        String id = find(created, "BlueGreenDeploymentIdentifier");
        String green = find(created, "Target").substring(find(created, "Target").lastIndexOf(':') + 1);

        query("PromoteReadReplica")
                .formParam("DBInstanceIdentifier", green)
        .when().post("/").then().statusCode(200);

        query("SwitchoverBlueGreenDeployment")
                .formParam("BlueGreenDeploymentIdentifier", id)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidBlueGreenDeploymentStateFault</Code>"));
        query("DescribeDBInstances")
                .formParam("DBInstanceIdentifier", "bg-test-promoted")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DBInstanceArn>" + source + "</DBInstanceArn>"));

        query("DeleteBlueGreenDeployment")
                .formParam("BlueGreenDeploymentIdentifier", id)
        .when().post("/").then().statusCode(200);
    }
}
