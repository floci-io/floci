package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
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
}
