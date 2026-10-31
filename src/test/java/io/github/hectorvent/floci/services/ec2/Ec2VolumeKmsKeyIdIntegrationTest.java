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
        String instanceId = launch();
        try {
            assertNoKmsKeyId(attachedVolume(instanceId, "/dev/xvda", "false"));
        } finally {
            call("TerminateInstances", "InstanceId.1", instanceId);
        }
    }

    /**
     * AWS refuses a launch mapping that names a key without Encrypted=true; Floci accepts it, and
     * with encryption by default off the volume stays unencrypted and keeps no key.
     */
    @Test
    void aLaunchMappingKeyWithoutEncryptedLeavesTheVolumeUnencrypted() {
        String instanceId = launch(
                "BlockDeviceMapping.1.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "1",
                "BlockDeviceMapping.1.Ebs.KmsKeyId", KEY_ARN);
        try {
            assertNoKmsKeyId(attachedVolume(instanceId, "/dev/sdf", "false"));
        } finally {
            call("TerminateInstances", "InstanceId.1", instanceId);
        }
    }

    /**
     * A launch's encrypted volumes keep the key their block device mapping names, the root
     * included, and one that names none gets the region's default.
     */
    @Test
    void launchVolumesKeepTheKeyTheirMappingNames() {
        String instanceId = launch(
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.Encrypted", "true",
                "BlockDeviceMapping.1.Ebs.KmsKeyId", KEY_ARN,
                "BlockDeviceMapping.2.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "1",
                "BlockDeviceMapping.2.Ebs.Encrypted", "true",
                "BlockDeviceMapping.2.Ebs.KmsKeyId", DEFAULT_KEY_ARN,
                "BlockDeviceMapping.3.DeviceName", "/dev/sdg",
                "BlockDeviceMapping.3.Ebs.VolumeSize", "1",
                "BlockDeviceMapping.3.Ebs.Encrypted", "true");
        try {
            assertEquals(List.of(KEY_ARN), XmlParser.extractAll(attachedVolume(instanceId, "/dev/xvda"), "kmsKeyId"));
            assertEquals(List.of(DEFAULT_KEY_ARN),
                    XmlParser.extractAll(attachedVolume(instanceId, "/dev/sdf"), "kmsKeyId"));
            assertEquals(List.of("alias/aws/ebs"),
                    XmlParser.extractAll(attachedVolume(instanceId, "/dev/sdg"), "kmsKeyId"));
        } finally {
            call("TerminateInstances", "InstanceId.1", instanceId);
        }
    }

    /**
     * A launch template's mapping key reaches the volume a launch from the template creates, and a
     * request entry for the same device that names another key wins over it.
     */
    @Test
    void aLaunchTemplateMappingKeyReachesTheVolume() {
        String templateId = ec2("CreateLaunchTemplate", "LaunchTemplateName", "kms-key-" + System.nanoTime(),
                "LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/sdf",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeSize", "1",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.Encrypted", "true",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.KmsKeyId", KEY_ARN)
                .when().post("/").then().statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        try {
            String fromTemplate = launch("LaunchTemplate.LaunchTemplateId", templateId);
            String overridden = launch("LaunchTemplate.LaunchTemplateId", templateId,
                    "BlockDeviceMapping.1.DeviceName", "/dev/sdf",
                    "BlockDeviceMapping.1.Ebs.KmsKeyId", DEFAULT_KEY_ARN);
            try {
                assertEquals(List.of(KEY_ARN),
                        XmlParser.extractAll(attachedVolume(fromTemplate, "/dev/sdf"), "kmsKeyId"));
                assertEquals(List.of(DEFAULT_KEY_ARN),
                        XmlParser.extractAll(attachedVolume(overridden, "/dev/sdf"), "kmsKeyId"));
            } finally {
                call("TerminateInstances", "InstanceId.1", fromTemplate, "InstanceId.2", overridden);
            }
        } finally {
            call("DeleteLaunchTemplate", "LaunchTemplateId", templateId);
        }
    }

    /** With encryption by default on, a launch's volumes that name no key get the default key. */
    @Test
    void launchVolumesEncryptedByDefaultGetTheDefaultKey() {
        call("EnableEbsEncryptionByDefault");
        try {
            call("ModifyEbsDefaultKmsKeyId", "KmsKeyId", DEFAULT_KEY_ARN);
            String instanceId = launch();
            try {
                assertEquals(List.of(DEFAULT_KEY_ARN),
                        XmlParser.extractAll(attachedVolume(instanceId, "/dev/xvda"), "kmsKeyId"));
            } finally {
                call("TerminateInstances", "InstanceId.1", instanceId);
            }
        } finally {
            try {
                call("DisableEbsEncryptionByDefault");
            } finally {
                call("ResetEbsDefaultKmsKeyId");
            }
        }
    }

    private static String launch(String... extraParams) {
        String[] params = new String[extraParams.length + 8];
        params[0] = "ImageId";
        params[1] = "ami-0abcdef1234567890";
        params[2] = "InstanceType";
        params[3] = "t3.micro";
        params[4] = "MinCount";
        params[5] = "1";
        params[6] = "MaxCount";
        params[7] = "1";
        System.arraycopy(extraParams, 0, params, 8, extraParams.length);
        return ec2("RunInstances", params).when().post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    /** DescribeVolumes for the one encrypted volume attached to the instance at the device. */
    private static String attachedVolume(String instanceId, String device) {
        return attachedVolume(instanceId, device, "true");
    }

    /** DescribeVolumes for the one volume attached to the instance at the device. */
    private static String attachedVolume(String instanceId, String device, String encrypted) {
        String xml = ec2("DescribeVolumes",
                "Filter.1.Name", "attachment.instance-id", "Filter.1.Value.1", instanceId,
                "Filter.2.Name", "attachment.device", "Filter.2.Value.1", device)
                .when().post("/").then().statusCode(200)
                .extract().asString();
        assertEquals(1, XmlParser.extractAll(xml, "size").size(), xml);
        assertEquals(List.of(encrypted), XmlParser.extractAll(xml, "encrypted"), xml);
        return xml;
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
