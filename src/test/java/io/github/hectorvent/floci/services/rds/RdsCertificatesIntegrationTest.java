package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * {@code DescribeCertificates}, {@code ModifyCertificates} and an instance's
 * {@code CACertificateIdentifier} over the Query protocol. The requests sign for eu-north-1, so the
 * account override set here is not the one other tests' instances in us-east-1 see.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsCertificatesIntegrationTest {

    private static final String REGION = "eu-north-1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261020/" + REGION + "/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static RequestSpecification createInstance(String id) {
        return query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", id)
                .formParam("Engine", "postgres")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("AllocatedStorage", "20");
    }

    /** The instances hold ports the other tests in this Quarkus app expect to find free. */
    @AfterEach
    void cleanUp() {
        for (String id : new String[] {"ca-default-db", "ca-named-db"}) {
            query("DeleteDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
        query("ModifyCertificates").formParam("RemoveCustomerOverride", "true").when().post("/");
    }

    @Test
    @Order(1)
    void theRegionsCertificatesAreListedWithTheDefault() {
        query("DescribeCertificates")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DescribeCertificatesResult><DefaultCertificateForNewLaunches>"
                        + "rds-ca-rsa2048-g1</DefaultCertificateForNewLaunches><Certificates><Certificate>"
                        + "<CertificateIdentifier>rds-ca-ecc384-g1</CertificateIdentifier>"
                        + "<CertificateType>CA</CertificateType>"))
                .body(containsString("<CertificateArn>arn:aws:rds:" + REGION + "::cert:rds-ca-rsa2048-g1"
                        + "</CertificateArn><CustomerOverride>false</CustomerOverride>"))
                .body(not(containsString("<Marker>")));

        query("DescribeCertificates").formParam("CertificateIdentifier", "rds-ca-2019")
        .when().post("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
        query("DescribeCertificates").formParam("MaxRecords", "20").formParam("Marker", "2")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Certificates><Certificate><CertificateIdentifier>rds-ca-rsa4096-g1"
                        + "</CertificateIdentifier>"));
    }

    @Test
    @Order(2)
    void anOverrideIsWhatANewInstanceGets() {
        query("ModifyCertificates").formParam("CertificateIdentifier", "rds-ca-ecc384-g1")
        .when().post("/").then().statusCode(200)
                .body(containsString("<ModifyCertificatesResult><Certificate>"
                        + "<CertificateIdentifier>rds-ca-ecc384-g1</CertificateIdentifier>"))
                .body(containsString("<CustomerOverride>true</CustomerOverride><CustomerOverrideValidTill>"));
        query("DescribeCertificates")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DefaultCertificateForNewLaunches>rds-ca-ecc384-g1"
                        + "</DefaultCertificateForNewLaunches>"));

        createInstance("ca-default-db")
        .when().post("/").then().statusCode(200)
                .body(containsString("<CACertificateIdentifier>rds-ca-ecc384-g1</CACertificateIdentifier>"
                        + "<CertificateDetails><CAIdentifier>rds-ca-ecc384-g1</CAIdentifier><ValidTill>"));

        query("ModifyCertificates").formParam("RemoveCustomerOverride", "true")
        .when().post("/").then().statusCode(200)
                .body(containsString("<CustomerOverride>false</CustomerOverride>"));
        query("ModifyCertificates")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterCombination"));
    }

    @Test
    @Order(3)
    void anInstanceNamesItsCaAndChangesIt() {
        createInstance("ca-named-db").formParam("CACertificateIdentifier", "rds-ca-rsa4096-g1")
        .when().post("/").then().statusCode(200)
                .body(containsString("<CACertificateIdentifier>rds-ca-rsa4096-g1</CACertificateIdentifier>"));
        createInstance("ca-refused-db").formParam("CACertificateIdentifier", "rds-ca-2019")
        .when().post("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));

        query("ModifyDBInstance").formParam("DBInstanceIdentifier", "ca-named-db")
                .formParam("CACertificateIdentifier", "rds-ca-rsa2048-g1")
                .formParam("CertificateRotationRestart", "false")
        .when().post("/").then().statusCode(200)
                .body(containsString("<CACertificateIdentifier>rds-ca-rsa2048-g1</CACertificateIdentifier>"));
        query("DescribeDBInstances").formParam("DBInstanceIdentifier", "ca-named-db")
        .when().post("/").then().statusCode(200)
                .body(containsString("<CertificateDetails><CAIdentifier>rds-ca-rsa2048-g1</CAIdentifier>"));
    }
}
