package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateVolumeResponse;
import software.amazon.awssdk.services.ec2.model.Volume;

import java.net.URI;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The KMS key a volume is created with, as the wire reports it: {@code kmsKeyId} on CreateVolume's
 * response and on the volume DescribeVolumes lists, beside {@code encrypted}.
 *
 * <p>Runs in its own region: the EBS encryption defaults are region-scoped and persist across test
 * classes in the same application, and the tests that change them put them back in a
 * {@code finally}.</p>
 */
@QuarkusTest
class Ec2VolumeKmsKeyIdIntegrationTest {

    private static final String REGION = "ap-southeast-5";
    private static final String AZ = REGION + "a";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/" + REGION + "/ec2/aws4_request";
    private static final String KEY_ARN =
            "arn:aws:kms:" + REGION + ":000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";
    private static final String DEFAULT_KEY_ARN =
            "arn:aws:kms:" + REGION + ":000000000000:key/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    private static RequestSpecification ec2(String action, String... params) {
        RequestSpecification request = given()
                .formParam("Action", action)
                .header("Authorization", AUTH_HEADER);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request;
    }

    private static RequestSpecification createVolume(String... extraParams) {
        String[] params = new String[extraParams.length + 4];
        params[0] = "AvailabilityZone";
        params[1] = AZ;
        params[2] = "Size";
        params[3] = "1";
        System.arraycopy(extraParams, 0, params, 4, extraParams.length);
        return ec2("CreateVolume", params);
    }

    /** The key an encrypted volume was created with, or an empty string when the element is missing. */
    private static String createdVolumeKey(String... extraParams) {
        return createVolume(extraParams).when().post("/").then().statusCode(200)
                .body("CreateVolumeResponse.encrypted", equalTo("true"))
                .extract().xmlPath().getString("CreateVolumeResponse.kmsKeyId");
    }

    private static String describedVolumeKey(String volumeId) {
        return ec2("DescribeVolumes", "VolumeId.1", volumeId)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("DescribeVolumesResponse.volumeSet.item.kmsKeyId");
    }

    private static void assertNoKmsKeyId(String xml) {
        assertEquals(List.of(), XmlParser.extractAll(xml, "kmsKeyId"), xml);
    }

    private static void call(String action, String... params) {
        ec2(action, params).when().post("/").then().statusCode(200);
    }

    @Test
    void createVolumeAndDescribeVolumesReportTheKeyAnEncryptedVolumeWasCreatedWith() {
        String volumeId = createVolume("Encrypted", "true", "KmsKeyId", KEY_ARN)
                .when().post("/").then().statusCode(200)
                .body("CreateVolumeResponse.volumeId", startsWith("vol-"))
                .body("CreateVolumeResponse.encrypted", equalTo("true"))
                .body("CreateVolumeResponse.kmsKeyId", equalTo(KEY_ARN))
                .extract().path("CreateVolumeResponse.volumeId");

        ec2("DescribeVolumes", "VolumeId.1", volumeId)
                .when().post("/").then().statusCode(200)
                .body("DescribeVolumesResponse.volumeSet.item.volumeId", equalTo(volumeId))
                .body("DescribeVolumesResponse.volumeSet.item.encrypted", equalTo("true"))
                .body("DescribeVolumesResponse.volumeSet.item.kmsKeyId", equalTo(KEY_ARN));
    }

    @Test
    void aVolumeThatIsNotEncryptedHasNoKmsKeyIdElement() {
        String created = createVolume("KmsKeyId", KEY_ARN)
                .when().post("/").then().statusCode(200)
                .body("CreateVolumeResponse.encrypted", equalTo("false"))
                .extract().asString();
        assertNoKmsKeyId(created);

        assertNoKmsKeyId(ec2("DescribeVolumes", "VolumeId.1", XmlParser.extractFirst(created, "volumeId", null))
                .when().post("/").then().statusCode(200)
                .body("DescribeVolumesResponse.volumeSet.item.encrypted", equalTo("false"))
                .extract().asString());
    }

    /**
     * An encrypted volume that names no key gets the region's default EBS key as it is when the
     * volume is created; changing the default later leaves the volume's key alone.
     */
    @Test
    void anEncryptedVolumeThatNamesNoKeyGetsTheRegionsDefaultKeyOfTheMoment() {
        assertEquals("alias/aws/ebs", createdVolumeKey("Encrypted", "true"));
        try {
            call("ModifyEbsDefaultKmsKeyId", "KmsKeyId", DEFAULT_KEY_ARN);
            String volumeId = createVolume("Encrypted", "true").when().post("/").then().statusCode(200)
                    .body("CreateVolumeResponse.kmsKeyId", equalTo(DEFAULT_KEY_ARN))
                    .extract().path("CreateVolumeResponse.volumeId");

            call("ResetEbsDefaultKmsKeyId");
            assertEquals(DEFAULT_KEY_ARN, describedVolumeKey(volumeId));
        } finally {
            call("ResetEbsDefaultKmsKeyId");
        }
    }

    /**
     * With encryption by default on, a CreateVolume volume that names no key gets the default key,
     * and one that names a key keeps it. AWS refuses a KmsKeyId without Encrypted=true even then;
     * Floci accepts it, and the volume, encrypted by default, keeps the key it names.
     */
    @Test
    void volumesEncryptedByDefaultGetTheDefaultKeyUnlessTheyNameOne() {
        call("EnableEbsEncryptionByDefault");
        try {
            call("ModifyEbsDefaultKmsKeyId", "KmsKeyId", DEFAULT_KEY_ARN);
            assertEquals(DEFAULT_KEY_ARN, createdVolumeKey());
            assertEquals(KEY_ARN, createdVolumeKey("Encrypted", "true", "KmsKeyId", KEY_ARN));
            assertEquals(KEY_ARN, createdVolumeKey("KmsKeyId", KEY_ARN));
        } finally {
            // Encryption by default first: it is the setting the other tests here depend on.
            try {
                call("DisableEbsEncryptionByDefault");
            } finally {
                call("ResetEbsDefaultKmsKeyId");
            }
        }
    }

    @Test
    void anInstanceRootVolumeThatIsNotEncryptedHasNoKey() {
        String instanceId = ec2("RunInstances", "ImageId", "ami-0abcdef1234567890",
                "InstanceType", "t3.micro", "MinCount", "1", "MaxCount", "1")
                .when().post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        String rootVolumeId = ec2("DescribeInstances", "InstanceId.1", instanceId)
                .when().post("/").then().statusCode(200)
                .extract().path("DescribeInstancesResponse.reservationSet.item.instancesSet.item"
                        + ".blockDeviceMapping.item.ebs.volumeId");

        assertNoKmsKeyId(ec2("DescribeVolumes", "VolumeId.1", rootVolumeId)
                .when().post("/").then().statusCode(200)
                .body("DescribeVolumesResponse.volumeSet.item.encrypted", equalTo("false"))
                .extract().asString());
        call("TerminateInstances", "InstanceId.1", instanceId);
    }

    @Test
    void theAwsSdkReadsTheKeyFromCreateVolumeAndDescribeVolumes() {
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            CreateVolumeResponse created = client.createVolume(request -> request
                    .availabilityZone(AZ).size(1).encrypted(true).kmsKeyId(KEY_ARN));
            assertEquals(KEY_ARN, created.kmsKeyId());
            Volume described = client.describeVolumes(request -> request.volumeIds(created.volumeId()))
                    .volumes().getFirst();
            assertEquals(KEY_ARN, described.kmsKeyId());

            String plainId = client.createVolume(request -> request.availabilityZone(AZ).size(1)).volumeId();
            assertNull(client.describeVolumes(request -> request.volumeIds(plainId))
                    .volumes().getFirst().kmsKeyId());
        }
    }
}
