package io.github.hectorvent.floci.services.cloudhsmv2;

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
class CloudHsmV2IntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "BaldrApiService.";

    private String createdClusterId;
    private String createdHsmId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ──────────────────────────── CreateCluster ────────────────────────────

    @Test
    @Order(1)
    void createClusterBasic() {
        createdClusterId = given()
            .header("X-Amz-Target", TARGET_PREFIX + "CreateCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "HsmType": "hsm1.medium",
                    "SubnetIds": ["subnet-abcdef01"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Cluster.ClusterId", startsWith("cluster-"))
            .body("Cluster.State", equalTo("UNINITIALIZED"))
            .body("Cluster.HsmType", equalTo("hsm1.medium"))
            .body("Cluster.VpcId", startsWith("vpc-"))
            .body("Cluster.SecurityGroup", startsWith("sg-"))
            .body("Cluster.BackupPolicy", equalTo("DEFAULT"))
            .body("Cluster.CreateTimestamp", notNullValue())
            .body("Cluster.Certificates", notNullValue())
            .body("Cluster.Certificates.ClusterCsr", startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
            .body("Cluster.Certificates.AwsHardwareCertificate", startsWith("-----BEGIN CERTIFICATE-----"))
            .body("Cluster.Certificates.ManufacturerHardwareCertificate", startsWith("-----BEGIN CERTIFICATE-----"))
            .body("Cluster.Certificates.HsmCertificate", startsWith("-----BEGIN CERTIFICATE-----"))
            .body("Cluster.Hsms", empty())
            .extract().jsonPath().getString("Cluster.ClusterId");
    }

    @Test
    @Order(2)
    void createClusterMissingSubnetIdsFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "CreateCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "HsmType": "hsm1.medium"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }

    @Test
    @Order(3)
    void createClusterEmptySubnetIdsFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "CreateCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "HsmType": "hsm1.medium",
                    "SubnetMapping": {}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }

    @Test
    @Order(4)
    void createClusterWithoutHsmTypeFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "CreateCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "SubnetIds": ["subnet-12345678"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }
    // ──────────────────────────── DescribeClusters ────────────────────────────

    @Test
    @Order(10)
    void describeClustersReturnsAll() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Clusters", notNullValue())
            .body("Clusters.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(11)
    void describeClustersFilterByClusterId() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["%s"]
                    }
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Clusters.size()", equalTo(1))
            .body("Clusters[0].ClusterId", equalTo(createdClusterId))
            .body("Clusters[0].State", equalTo("UNINITIALIZED"))
            .body("Clusters[0].Certificates.ClusterCsr", startsWith("-----BEGIN CERTIFICATE REQUEST-----"));
    }

    @Test
    @Order(12)
    void describeClustersFilterByState() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "states": ["UNINITIALIZED"]
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Clusters", notNullValue())
            .body("Clusters.size()", greaterThanOrEqualTo(1))
            .body("Clusters.every { it.State == 'UNINITIALIZED' }", is(true));
    }

    @Test
    @Order(13)
    void describeClustersFilterNoMatch() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["cluster-nonexistent"]
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Clusters.size()", equalTo(0));
    }

    // ──────────────────────────── CSR Generation ────────────────────────────

    @Test
    @Order(14)
    void csrIsValidPem() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["%s"]
                    }
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Clusters[0].Certificates.ClusterCsr", startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
            .body("Clusters[0].Certificates.ClusterCsr", containsString("-----END CERTIFICATE REQUEST-----"));
    }

    // ──────────────────────────── InitializeCluster ────────────────────────────

    @Test
    @Order(20)
    void initializeClusterMissingSignedCertFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "InitializeCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "TrustAnchor": "-----BEGIN CERTIFICATE-----\\nMIIBxTCCAW4=\\n-----END CERTIFICATE-----"
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }

    @Test
    @Order(21)
    void initializeClusterMissingTrustAnchorFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "InitializeCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "SignedCert": "-----BEGIN CERTIFICATE-----\\nMIIBxTCCAW4=\\n-----END CERTIFICATE-----"
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }

    @Test
    @Order(22)
    void initializeClusterInvalidPemFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "InitializeCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "SignedCert": "not-a-pem-certificate",
                    "TrustAnchor": "also-not-a-pem-certificate"
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }

    @Test
    @Order(23)
    void initializeClusterMalformedPemFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "InitializeCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "SignedCert": "-----BEGIN CERTIFICATE-----\\ncorrupted-base64\\n-----END CERTIFICATE-----",
                    "TrustAnchor": "-----BEGIN CERTIFICATE-----\\ncorrupted-base64\\n-----END CERTIFICATE-----"
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }

    @Test
    @Order(24)
    void initializeClusterNonExistentClusterFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "InitializeCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "cluster-nonexistent",
                    "SignedCert": "-----BEGIN CERTIFICATE-----\\nMIIBxTCCAW4=\\n-----END CERTIFICATE-----",
                    "TrustAnchor": "-----BEGIN CERTIFICATE-----\\nMIIBxTCCAW4=\\n-----END CERTIFICATE-----"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmResourceNotFoundException"));
    }

    @Test
    @Order(30)
    void initializeClusterSuccess() {
        // Get the hardware certs from the cluster to use as valid PEM for initialization
        String csr = given().header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters").contentType(CONTENT_TYPE).body("{\"Filters\":{\"clusterIds\":[\"" + createdClusterId + "\"]}}").when().post("/").then().statusCode(200).extract().jsonPath().getString("Clusters[0].Certificates.ClusterCsr");
        String[] certs;
        try { certs = generateCerts(csr); } catch (Exception e) { throw new RuntimeException(e); }
        String signedCert = certs[0];
        String trustAnchor = certs[1];

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "InitializeCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "SignedCert": %s,
                    "TrustAnchor": %s
                }
                """.formatted(createdClusterId,
                    jsonString(signedCert), jsonString(trustAnchor)))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("State", equalTo("INITIALIZED"))
            .body("StateMessage", notNullValue());
    }

    @Test
    @Order(31)
    void initializeClusterAlreadyInitializedFails() {
        String csr = given().header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters").contentType(CONTENT_TYPE).body("{\"Filters\":{\"clusterIds\":[\"" + createdClusterId + "\"]}}").when().post("/").then().statusCode(200).extract().jsonPath().getString("Clusters[0].Certificates.ClusterCsr");
        String[] certs;
        try { certs = generateCerts(csr); } catch (Exception e) { throw new RuntimeException(e); }
        String signedCert = certs[0];
        String trustAnchor = certs[1];

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "InitializeCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "SignedCert": %s,
                    "TrustAnchor": %s
                }
                """.formatted(createdClusterId,
                    jsonString(signedCert), jsonString(trustAnchor)))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }

    @Test
    @Order(32)
    void describeClustersShowsInitializedState() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["%s"]
                    }
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Clusters[0].State", equalTo("INITIALIZED"))
            .body("Clusters[0].Certificates.ClusterCertificate", notNullValue());
    }

    // ──────────────────────────── Persistence ────────────────────────────

    @Test
    @Order(33)
    void clusterCertificatePersistedAfterInit() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["%s"]
                    }
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Clusters[0].Certificates.ClusterCertificate", startsWith("-----BEGIN CERTIFICATE-----"))
            .body("Clusters[0].Certificates.ClusterCsr", startsWith("-----BEGIN CERTIFICATE REQUEST-----"));
    }

    // ──────────────────────────── ACTIVE Gating ────────────────────────────

    @Test
    @Order(40)
    void clusterDoesNotBecomeActiveWithoutHsm() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["%s"]
                    }
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Clusters[0].State", equalTo("INITIALIZED"))
            .body("Clusters[0].Hsms.size()", equalTo(0));
    }

    @Test
    @Order(41)
    void createHsmTransitionsClusterToActive() {
        createdHsmId = given()
            .header("X-Amz-Target", TARGET_PREFIX + "CreateHsm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "AvailabilityZone": "us-east-1a"
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Hsm.HsmId", startsWith("hsm-"))
            .body("Hsm.AvailabilityZone", equalTo("us-east-1a"))
            .body("Hsm.ClusterId", equalTo(createdClusterId))
            .body("Hsm.State", equalTo("ACTIVE"))
            .extract().jsonPath().getString("Hsm.HsmId");

        // Cluster should now be ACTIVE
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["%s"]
                    }
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Clusters[0].State", equalTo("ACTIVE"))
            .body("Clusters[0].Hsms.size()", equalTo(1))
            .body("Clusters[0].Hsms[0].HsmId", equalTo(createdHsmId));
    }

    @Test
    @Order(42)
    void deleteHsmRevertsClusterToInitialized() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteHsm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "HsmId": "%s"
                }
                """.formatted(createdClusterId, createdHsmId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("HsmId", equalTo(createdHsmId));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["%s"]
                    }
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Clusters[0].State", equalTo("INITIALIZED"))
            .body("Clusters[0].Hsms.size()", equalTo(0));
    }

    @Test
    @Order(43)
    void deleteHsmByEniIdSuccess() {
        // Create an HSM
        String hsmId = given()
            .header("X-Amz-Target", TARGET_PREFIX + "CreateHsm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "AvailabilityZone": "us-east-1a"
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Hsm.HsmId");

        // Get its EniId
        String eniId = given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["%s"]
                    }
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Clusters[0].Hsms.find { it.HsmId == '%s' }.EniId".formatted(hsmId));

        // Delete by EniId
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteHsm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "EniId": "%s"
                }
                """.formatted(createdClusterId, eniId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("HsmId", equalTo(hsmId));
    }

    @Test
    @Order(44)
    void deleteHsmByEniIpSuccess() {
        // Create an HSM
        String hsmId = given()
            .header("X-Amz-Target", TARGET_PREFIX + "CreateHsm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "AvailabilityZone": "us-east-1a"
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Hsm.HsmId");

        // Get its EniIp
        String eniIp = given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["%s"]
                    }
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Clusters[0].Hsms.find { it.HsmId == '%s' }.EniIp".formatted(hsmId));

        // Delete by EniIp
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteHsm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "EniIp": "%s"
                }
                """.formatted(createdClusterId, eniIp))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("HsmId", equalTo(hsmId));
    }

    // ──────────────────────────── Error Responses ────────────────────────────

    @Test
    @Order(50)
    void createHsmMissingAzFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "CreateHsm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s"
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }

    @Test
    @Order(51)
    void deleteHsmNotFoundFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteHsm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "HsmId": "hsm-nonexistent"
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmResourceNotFoundException"));
    }

    @Test
    @Order(52)
    void deleteHsmMissingSelectorFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteHsm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s"
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }

    @Test
    @Order(53)
    void deleteHsmMultipleSelectorsFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteHsm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "HsmId": "hsm-abc",
                    "EniId": "eni-xyz"
                }
                """.formatted(createdClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }

    @Test
    @Order(54)
    void deleteClusterWithHsmsFails() {
        // Create a cluster, add an HSM, then try to delete the cluster
        String tempClusterId = given()
            .header("X-Amz-Target", TARGET_PREFIX + "CreateCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "HsmType": "hsm1.medium",
                    "SubnetIds": ["subnet-temptest01"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Cluster.ClusterId");

        String csr = given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["%s"]
                    }
                }
                """.formatted(tempClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Clusters[0].Certificates.ClusterCsr");

        String[] certs;
        try { certs = generateCerts(csr); } catch (Exception e) { throw new RuntimeException(e); }
        String signedCert = certs[0];
        String trustAnchor = certs[1];

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "InitializeCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "SignedCert": %s,
                    "TrustAnchor": %s
                }
                """.formatted(tempClusterId, jsonString(signedCert), jsonString(trustAnchor)))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "CreateHsm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s",
                    "AvailabilityZone": "us-east-1a"
                }
                """.formatted(tempClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s"
                }
                """.formatted(tempClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmInvalidRequestException"));
    }

    @Test
    @Order(60)
    void unsupportedOperationReturnsError() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "SomeUnsupportedAction")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }

    // ──────────────────────────── DeleteCluster ────────────────────────────

    @Test
    @Order(100)
    void deleteClusterSuccess() {
        // Create a fresh cluster to delete
        String tempClusterId = given()
            .header("X-Amz-Target", TARGET_PREFIX + "CreateCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "HsmType": "hsm1.medium",
                    "SubnetIds": ["subnet-deletetest"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Cluster.ClusterId");

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "%s"
                }
                """.formatted(tempClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Cluster.State", equalTo("DELETE_IN_PROGRESS"));

        // Verify it's gone
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeClusters")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Filters": {
                        "clusterIds": ["%s"]
                    }
                }
                """.formatted(tempClusterId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Clusters.size()", equalTo(0));
    }

    @Test
    @Order(101)
    void deleteClusterNotFoundFails() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteCluster")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ClusterId": "cluster-nonexistent"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CloudHsmResourceNotFoundException"));
    }

    /** Escapes a PEM string as a JSON string value. */
    private static String jsonString(String pem) {
        if (pem == null) {
            return "null";
        }
        return "\"" + pem
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                + "\"";
    }

    private String[] generateCerts(String csrPem) throws Exception {
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA", "BC");
        keyGen.initialize(2048, new java.security.SecureRandom());
        java.security.KeyPair caKeyPair = keyGen.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name caName = new org.bouncycastle.asn1.x500.X500Name("CN=Floci Test CA");
        long now = System.currentTimeMillis();
        java.util.Date startDate = new java.util.Date(now);
        java.util.Date endDate = new java.util.Date(now + 365L * 24 * 3600 * 1000);
        java.math.BigInteger serial = java.math.BigInteger.valueOf(now);
        org.bouncycastle.cert.X509v3CertificateBuilder caBuilder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                caName, serial, startDate, endDate, caName, caKeyPair.getPublic());
        org.bouncycastle.operator.ContentSigner caSigner = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider("BC").build(caKeyPair.getPrivate());
        org.bouncycastle.cert.X509CertificateHolder caHolder = caBuilder.build(caSigner);
        java.io.StringWriter caSw = new java.io.StringWriter();
        try (org.bouncycastle.openssl.jcajce.JcaPEMWriter pw = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(caSw)) {
            pw.writeObject(caHolder);
        }
        String trustAnchor = caSw.toString();
        org.bouncycastle.pkcs.PKCS10CertificationRequest csr;
        try (org.bouncycastle.openssl.PEMParser parser = new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(csrPem))) {
            csr = (org.bouncycastle.pkcs.PKCS10CertificationRequest) parser.readObject();
        }
        java.security.PublicKey csrPublicKey = new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().setProvider("BC").getPublicKey(csr.getSubjectPublicKeyInfo());
        org.bouncycastle.cert.X509v3CertificateBuilder certBuilder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                caName, serial.add(java.math.BigInteger.ONE), startDate, endDate, csr.getSubject(), csrPublicKey);
        org.bouncycastle.cert.X509CertificateHolder certHolder = certBuilder.build(caSigner);
        java.io.StringWriter certSw = new java.io.StringWriter();
        try (org.bouncycastle.openssl.jcajce.JcaPEMWriter pw = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(certSw)) {
            pw.writeObject(certHolder);
        }
        String signedCert = certSw.toString();
        return new String[]{signedCert, trustAnchor};
    }
}
