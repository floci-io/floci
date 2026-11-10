package io.floci.aws.services.rds;

import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * VpcSecurityGroupIds given to CreateDBCluster and ModifyDBCluster are stored on the cluster and
 * read back through DescribeDBClusters, instead of a fixed sg-00000000 membership.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterVpcSecurityGroupsIntegrationTest {

    private static final String ID = "vpc-sg-cluster";

    private static io.restassured.specification.RequestSpecification rds(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static List<String> groupIds(String xml) {
        try {
            NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getElementsByTagName("VpcSecurityGroupId");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                ids.add(nodes.item(i).getTextContent());
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("unparseable RDS response: " + xml, e);
        }
    }

    private static List<String> describedGroupIds() {
        return groupIds(rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200).extract().asString());
    }

    private static io.restassured.specification.RequestSpecification create() {
        return rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password");
    }

    @AfterEach
    void cleanUp() {
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void createStoresRequestedGroupsAndModifyReplacesThem() {
        String created = create()
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", "sg-abcdef01")
                .when().post("/").then().statusCode(200).extract().asString();
        assertEquals(List.of("sg-abcdef01"), groupIds(created));
        assertEquals(List.of("sg-abcdef01"), describedGroupIds());

        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", "sg-11111111")
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.2", "sg-22222222")
                .when().post("/").then().statusCode(200);
        assertEquals(List.of("sg-11111111", "sg-22222222"), describedGroupIds());

        // A modify that does not name VpcSecurityGroupIds leaves them alone.
        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("EnableIAMDatabaseAuthentication", "true")
                .when().post("/").then().statusCode(200);
        assertEquals(List.of("sg-11111111", "sg-22222222"), describedGroupIds());
    }

    @Test
    void omittedGroupsFallBackToTheInstanceDefault() {
        create().when().post("/").then().statusCode(200);
        assertEquals(List.of("sg-00000000"), describedGroupIds());
    }
}
