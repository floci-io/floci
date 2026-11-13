package io.floci.aws.services.efs;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EfsIntegrationTest {

    private String fileSystemId;
    private String mountTargetId;
    private String accessPointId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createFileSystem() {
        fileSystemId = given()
            .contentType("application/json")
            .body("""
                {
                    "CreationToken": "my-token",
                    "PerformanceMode": "generalPurpose",
                    "Encrypted": true,
                    "Tags": [{"Key": "Name", "Value": "MyFS"}]
                }
                """)
        .when()
            .post("/2015-02-01/file-systems")
        .then()
            .statusCode(201)
            .body("FileSystemId", startsWith("fs-"))
            .body("Encrypted", equalTo(true))
            .body("NumberOfMountTargets", equalTo(0))
            .body("FileSystemProtection.ReplicationOverwriteProtection", equalTo("ENABLED"))
            .body("Tags[0].Value", equalTo("MyFS"))
            .extract().jsonPath().getString("FileSystemId");
    }

    @Test
    @Order(2)
    void describeFileSystemsByFileSystemId() {
    given()
        .queryParam("FileSystemId", fileSystemId)
    .when()
        .get("/2015-02-01/file-systems")
    .then()
        .statusCode(200)
        .body("FileSystems.size()", equalTo(1))
        .body("FileSystems[0].FileSystemId", equalTo(fileSystemId));
    }

    @Test
    @Order(3)
    void updateFileSystem() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ThroughputMode": "provisioned",
                    "ProvisionedThroughputInMibps": 50.0
                }
                """)
        .when()
            .put("/2015-02-01/file-systems/" + fileSystemId)
        .then()
            .statusCode(202)
            .body("ProvisionedThroughputInMibps", equalTo(50.0f));
    }

    @Test
    @Order(4)
    void createMountTarget() {
        mountTargetId = given()
            .contentType("application/json")
            .body("""
                {
                    "FileSystemId": "%s",
                    "SubnetId": "subnet-12345",
                    "SecurityGroups": ["sg-11111"]
                }
                """.formatted(fileSystemId))
        .when()
            .post("/2015-02-01/mount-targets")
        .then()
            .statusCode(200)
            .body("MountTargetId", startsWith("fsmt-"))
            .body("FileSystemId", equalTo(fileSystemId))
            .body("SubnetId", equalTo("subnet-12345"))
            .body("AvailabilityZoneName", equalTo("us-east-1a"))
            // The zone id EC2 reports for the same zone, so a mount target agrees with its subnet.
            .body("AvailabilityZoneId", equalTo("use1-az1"))
            .extract().jsonPath().getString("MountTargetId");
    }

    @Test
    @Order(5)
    void describeMountTargets() {
        given()
            .contentType("application/json")
            .queryParam("FileSystemId", fileSystemId)
        .when()
            .get("/2015-02-01/mount-targets")
        .then()
            .statusCode(200)
            .body("MountTargets.MountTargetId", hasItem(mountTargetId));
    }
    
    @Test
    @Order(6)
    void modifyMountTargetSecurityGroups() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "SecurityGroups": ["sg-22222"]
                }
                """)
        .when()
            .put("/2015-02-01/mount-targets/" + mountTargetId + "/security-groups")
        .then()
            .statusCode(204);
            
        given()
            .contentType("application/json")
        .when()
            .get("/2015-02-01/mount-targets/" + mountTargetId + "/security-groups")
        .then()
            .statusCode(200)
            .body("SecurityGroups", hasItem("sg-22222"));
    }

    @Test
    @Order(7)
    void createAccessPoint() {
        accessPointId = given()
            .contentType("application/json")
            .body("""
                {
                    "ClientToken": "ap-token",
                    "FileSystemId": "%s"
                }
                """.formatted(fileSystemId))
        .when()
            .post("/2015-02-01/access-points")
        .then()
            .statusCode(200)
            .body("AccessPointId", startsWith("fsap-"))
            .body("FileSystemId", equalTo(fileSystemId))
            .extract().jsonPath().getString("AccessPointId");
    }

    @Test
    @Order(8)
    void describeAccessPoints() {
        given()
            .contentType("application/json")
            .queryParam("FileSystemId", fileSystemId)
        .when()
            .get("/2015-02-01/access-points")
        .then()
            .statusCode(200)
            .body("AccessPoints.AccessPointId", hasItem(accessPointId));
    }
    
    @Test
    @Order(9)
    void manageFileSystemPolicy() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Policy": "{\\"Statement\\": []}"
                }
                """)
        .when()
            .put("/2015-02-01/file-systems/" + fileSystemId + "/policy")
        .then()
            .statusCode(200)
            .body("Policy", equalTo("{\"Statement\": []}"));
            
        given()
            .contentType("application/json")
        .when()
            .get("/2015-02-01/file-systems/" + fileSystemId + "/policy")
        .then()
            .statusCode(200)
            .body("Policy", equalTo("{\"Statement\": []}"));
    }

    @Test
    @Order(10)
    void describeReportsProtectionEnabledByDefault() {
        given()
            .queryParam("FileSystemId", fileSystemId)
        .when()
            .get("/2015-02-01/file-systems")
        .then()
            .statusCode(200)
            .body("FileSystems[0].FileSystemProtection.ReplicationOverwriteProtection",
                    equalTo("ENABLED"));
    }

    @Test
    @Order(11)
    void updateFileSystemProtection() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ReplicationOverwriteProtection": "DISABLED"
                }
                """)
        .when()
            .put("/2015-02-01/file-systems/" + fileSystemId + "/protection")
        .then()
            .statusCode(200)
            .body("ReplicationOverwriteProtection", equalTo("DISABLED"));

        // The change has to survive the round trip, not just echo back: the terraform
        // provider reads protection back through DescribeFileSystems.
        given()
            .queryParam("FileSystemId", fileSystemId)
        .when()
            .get("/2015-02-01/file-systems")
        .then()
            .statusCode(200)
            .body("FileSystems[0].FileSystemProtection.ReplicationOverwriteProtection",
                    equalTo("DISABLED"));
    }

    @Test
    @Order(12)
    void updateFileSystemProtectionOnMissingFileSystemIs404() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ReplicationOverwriteProtection": "DISABLED"
                }
                """)
        .when()
            .put("/2015-02-01/file-systems/fs-does-not-exist/protection")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(13)
    void deleteResources() {
        given()
            .contentType("application/json")
        .when()
            .delete("/2015-02-01/access-points/" + accessPointId)
        .then()
            .statusCode(204);

        given()
            .contentType("application/json")
        .when()
            .delete("/2015-02-01/mount-targets/" + mountTargetId)
        .then()
            .statusCode(204);

        given()
            .contentType("application/json")
        .when()
            .delete("/2015-02-01/file-systems/" + fileSystemId)
        .then()
            .statusCode(204);
            
        given()
            .contentType("application/json")
        .when()
            .get("/2015-02-01/file-systems")
        .then()
            .statusCode(200)
            .body("FileSystems.FileSystemId", not(hasItem(fileSystemId)));
    }

    @Test
    @Order(14)
    void createMountTargetKeepsItsAddressTypeAndAddressesPerType() {
        String ipv6FileSystemId = given()
            .contentType("application/json")
            .body("{\"CreationToken\": \"mount-target-address-types\"}")
        .when()
            .post("/2015-02-01/file-systems")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("FileSystemId");

        given()
            .contentType("application/json")
            .body("""
                {"FileSystemId": "%s", "SubnetId": "subnet-0123456789abcdef0",
                 "IpAddressType": "IPV6_ONLY", "Ipv6Address": "2600:1f18::1"}
                """.formatted(ipv6FileSystemId))
        .when()
            .post("/2015-02-01/mount-targets")
        .then()
            .statusCode(200)
            .body("IpAddressType", equalTo("IPV6_ONLY"))
            .body("Ipv6Address", equalTo("2600:1f18::1"))
            .body("IpAddress", nullValue());

        given()
            .queryParam("FileSystemId", ipv6FileSystemId)
        .when()
            .get("/2015-02-01/mount-targets")
        .then()
            .statusCode(200)
            .body("MountTargets[0].IpAddressType", equalTo("IPV6_ONLY"))
            .body("MountTargets[0].Ipv6Address", equalTo("2600:1f18::1"))
            .body("MountTargets[0].IpAddress", nullValue());
    }

    @Test
    @Order(16)
    void createMountTargetAssignsAnIpv6AddressWhenTheTypeNeedsOneAndNoneIsGiven() {
        String dualFileSystemId = given()
            .contentType("application/json")
            .body("{\"CreationToken\": \"mount-target-dual-stack\"}")
        .when()
            .post("/2015-02-01/file-systems")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("FileSystemId");

        given()
            .contentType("application/json")
            .body("""
                {"FileSystemId": "%s", "SubnetId": "subnet-0123456789abcdef0", "IpAddressType": "DUAL_STACK"}
                """.formatted(dualFileSystemId))
        .when()
            .post("/2015-02-01/mount-targets")
        .then()
            .statusCode(200)
            .body("IpAddress", equalTo("10.0.0.10"))
            .body("Ipv6Address", equalTo("2001:db8::10"));
    }

    @Test
    @Order(17)
    void createMountTargetRejectsAnAddressThatContradictsItsAddressType() {
        String fileSystemWithBadRequests = given()
            .contentType("application/json")
            .body("{\"CreationToken\": \"mount-target-contradiction\"}")
        .when()
            .post("/2015-02-01/file-systems")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("FileSystemId");

        for (String contradiction : new String[] {
                "\"IpAddressType\": \"IPV4_ONLY\", \"Ipv6Address\": \"2600:1f18::1\"",
                "\"IpAddressType\": \"IPV6_ONLY\", \"IpAddress\": \"10.0.5.5\""}) {
            given()
                .contentType("application/json")
                .body("{\"FileSystemId\": \"" + fileSystemWithBadRequests
                        + "\", \"SubnetId\": \"subnet-0123456789abcdef0\", " + contradiction + "}")
            .when()
                .post("/2015-02-01/mount-targets")
            .then()
                .statusCode(400);
        }

        given()
            .queryParam("FileSystemId", fileSystemWithBadRequests)
        .when()
            .get("/2015-02-01/mount-targets")
        .then()
            .statusCode(200)
            .body("MountTargets", empty());
    }

    @Test
    @Order(15)
    void createMountTargetWithoutASubnetIsRejectedAndStoresNothing() {
        String bareFileSystemId = given()
            .contentType("application/json")
            .body("{\"CreationToken\": \"mount-target-no-subnet\"}")
        .when()
            .post("/2015-02-01/file-systems")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("FileSystemId");

        given()
            .contentType("application/json")
            .body("{\"FileSystemId\": \"" + bareFileSystemId + "\"}")
        .when()
            .post("/2015-02-01/mount-targets")
        .then()
            .statusCode(400);

        given()
            .queryParam("FileSystemId", bareFileSystemId)
        .when()
            .get("/2015-02-01/mount-targets")
        .then()
            .statusCode(200)
            .body("MountTargets", empty());
    }
}
