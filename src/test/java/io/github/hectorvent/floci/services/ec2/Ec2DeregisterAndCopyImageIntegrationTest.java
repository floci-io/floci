package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.xml.HasXPath.hasXPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeregisterImage and CopyImage. Without DeregisterImage an iterative image build is one-shot:
 * the second run hits InvalidAMIName.Duplicate and Packer's force_deregister has no way to clear
 * the previous AMI. Without CopyImage the build-in-one-region, promote-to-others pattern cannot
 * run at all.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DeregisterImage.html">DeregisterImage</a>
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CopyImage.html">CopyImage</a>
 */
@QuarkusTest
class Ec2DeregisterAndCopyImageIntegrationTest {

    @Inject
    Ec2Service service;

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    private static final String EAST_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String WEST_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-west-2/ec2/aws4_request";
    /** A 12-digit access key is taken as the account id, so this signs as a second account. */
    private static final String OTHER_ACCOUNT_EAST_AUTH =
            "AWS4-HMAC-SHA256 Credential=111111111111/20260205/us-east-1/ec2/aws4_request";

    private String ec2(String auth, String action, String... formParams) {
        RequestSpecification req = given().formParam("Action", action).header("Authorization", auth);
        for (int i = 0; i < formParams.length; i += 2) {
            req = req.formParam(formParams[i], formParams[i + 1]);
        }
        return req.when().post("/").then().statusCode(200).extract().asString();
    }

    private String xmlValue(String xml, String element) {
        String open = "<" + element + ">";
        String close = "</" + element + ">";
        int start = xml.indexOf(open);
        return start < 0 ? null : xml.substring(start + open.length(), xml.indexOf(close, start));
    }

    /** A name unique per test run, so tests never collide on InvalidAMIName.Duplicate. */
    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String registerViaHttp(String auth, String name) {
        return xmlValue(ec2(auth, "RegisterImage", "Name", name, "RootDeviceName", "/dev/xvda"), "imageId");
    }

    // ─── DeregisterImage ──────────────────────────────────────────────────────

    @Test
    void deregisterImageSucceedsAndTheAmiStopsBeingDescribed() {
        String name = uniqueName("floci-deregister");
        String imageId = registerViaHttp(EAST_AUTH, name);
        assertTrue(ec2(EAST_AUTH, "DescribeImages", "Owner.1", "self").contains(imageId));

        given()
            .formParam("Action", "DeregisterImage")
            .formParam("ImageId", imageId)
            .header("Authorization", EAST_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // "Returns true if the request succeeds; otherwise, it returns an error."
            .body(hasXPath("//*[local-name()='DeregisterImageResponse']/*[local-name()='return']",
                    equalTo("true")));

        assertFalse(ec2(EAST_AUTH, "DescribeImages", "Owner.1", "self").contains(imageId),
                "a deregistered AMI must not be reported by DescribeImages");
    }

    @Test
    void deregisteringAnUnknownImageIsRejected() {
        // Silently succeeding here would make an idempotency check on a fresh account believe a
        // previous AMI had just been cleaned up.
        given()
            .formParam("Action", "DeregisterImage")
            .formParam("ImageId", "ami-0000000000000dead")
            .header("Authorization", EAST_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(hasXPath("//*[local-name()='Code']", equalTo("InvalidAMIID.NotFound")));
    }

    @Test
    void deregisteringTwiceReportsTheImageAsUnavailable() {
        // "The specified AMI has been deregistered and is no longer available" -- distinguishable
        // from an id that never existed, which is what makes a retry diagnosable.
        String imageId = registerViaHttp(EAST_AUTH, uniqueName("floci-deregister-twice"));
        ec2(EAST_AUTH, "DeregisterImage", "ImageId", imageId);

        given()
            .formParam("Action", "DeregisterImage")
            .formParam("ImageId", imageId)
            .header("Authorization", EAST_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(hasXPath("//*[local-name()='Code']", equalTo("InvalidAMIID.Unavailable")));
    }

    @Test
    void deregisteringSomeoneElsesAmiIsAnAuthFailure() {
        // Catalog AMIs are owned by amazon. AWS reports "trying to use an AMI for which you do not
        // have permissions" as AuthFailure rather than as a missing image.
        given()
            .formParam("Action", "DeregisterImage")
            .formParam("ImageId", "ami-0abcdef1234567890")
            .header("Authorization", EAST_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(hasXPath("//*[local-name()='Code']", equalTo("AuthFailure")));
    }

    @Test
    void theNameIsFreedSoTheSameAmiNameCanBeRegisteredAgain() {
        // Exactly what Packer's force_deregister does: deregister the AMI holding the name, then
        // register the rebuilt one under the same name. Without this the second build fails with
        // InvalidAMIName.Duplicate.
        String name = uniqueName("floci-packer-rebuild");
        String first = registerViaHttp(EAST_AUTH, name);
        ec2(EAST_AUTH, "DeregisterImage", "ImageId", first);

        String second = registerViaHttp(EAST_AUTH, name);
        assertNotEquals(first, second);
        String described = ec2(EAST_AUTH, "DescribeImages", "Owner.1", "self");
        assertTrue(described.contains(second));
        assertFalse(described.contains(first));
    }

    @Test
    void anInstanceLaunchedFromTheAmiKeepsRunningAfterDeregistration() {
        // "Deregistering an AMI does not delete ... Instances already launched from the AMI."
        Image image = service.registerImage(EAST, uniqueName("floci-live-instance"), null,
                "x86_64", "/dev/xvda", null);
        String instanceId = service.runInstances(EAST, image.getImageId(), "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null, null)
                .getInstances().getFirst().getInstanceId();

        service.deregisterImage(EAST, image.getImageId(), false);

        Instance instance = service.describeInstances(EAST, List.of(instanceId), java.util.Map.of()).getFirst()
                .getInstances().getFirst();
        assertEquals("running", instance.getState().getName());
        assertEquals(image.getImageId(), instance.getImageId());
    }

    @Test
    void aDeregisteredAmiCannotLaunchNewInstances() {
        // "A deregistered AMI can't be used to launch new instances." Falling back to the default
        // guest image instead would silently launch the wrong thing.
        Image image = service.registerImage(EAST, uniqueName("floci-no-relaunch"), null,
                "x86_64", "/dev/xvda", null);
        service.deregisterImage(EAST, image.getImageId(), false);

        AwsException error = assertThrows(AwsException.class, () -> service.runInstances(
                EAST, image.getImageId(), "t3.micro", 1, 1, null, List.of(), null, null,
                List.of(), null, null, null));
        assertEquals("InvalidAMIID.Unavailable", error.getErrorCode());
    }

    @Test
    void snapshotsAreKeptByDefaultAndDeletedOnlyOnRequest() {
        // "Default: The snapshots are not deleted."
        Image kept = service.registerImage(EAST, uniqueName("floci-keep-snapshots"), null,
                "x86_64", "/dev/xvda", List.of(mapping("/dev/xvda", "snap-" + hex())));
        String keptSnapshot = snapshotIdOf(kept);
        service.deregisterImage(EAST, kept.getImageId(), false);
        assertEquals(1, service.describeSnapshots(EAST, List.of(keptSnapshot), null, Map.of()).size());

        Image deleted = service.registerImage(EAST, uniqueName("floci-delete-snapshots"), null,
                "x86_64", "/dev/xvda", List.of(mapping("/dev/xvda", "snap-" + hex())));
        String deletedSnapshot = snapshotIdOf(deleted);
        List<Ec2Service.SnapshotDeletion> results =
                service.deregisterImage(EAST, deleted.getImageId(), true);
        assertEquals(List.of(new Ec2Service.SnapshotDeletion(deletedSnapshot, "success")), results);
        assertThrows(AwsException.class,
                () -> service.describeSnapshots(EAST, List.of(deletedSnapshot), null, Map.of()));
    }

    @Test
    void aSnapshotDeletedWithItsImageTakesItsTags() {
        Image image = service.registerImage(EAST, uniqueName("floci-tagged-snapshot"), null,
                "x86_64", "/dev/xvda", List.of(mapping("/dev/xvda", "snap-" + hex())));
        String snapshotId = snapshotIdOf(image);
        service.createTags(EAST, List.of(snapshotId), List.of(new Tag("Team", "snapshots")));

        service.deregisterImage(EAST, image.getImageId(), true);

        assertTrue(service.describeTags(EAST, Map.of("resource-id", List.of(snapshotId))).isEmpty());
    }

    @Test
    void aSnapshotSharedWithAnotherAmiIsSkippedRatherThanDeleted() {
        // "if a snapshot is associated with multiple AMIs, it won't be deleted even if specified
        // for deletion, although the AMI will still be deregistered."
        String snapshotId = "snap-" + hex();
        Image first = service.registerImage(EAST, uniqueName("floci-shared-a"), null,
                "x86_64", "/dev/xvda", List.of(mapping("/dev/xvda", snapshotId)));
        service.registerImage(EAST, uniqueName("floci-shared-b"), null,
                "x86_64", "/dev/xvda", List.of(mapping("/dev/xvda", snapshotId)));

        List<Ec2Service.SnapshotDeletion> results =
                service.deregisterImage(EAST, first.getImageId(), true);

        assertEquals(List.of(new Ec2Service.SnapshotDeletion(snapshotId, "skipped")), results);
        assertEquals(1, service.describeSnapshots(EAST, List.of(snapshotId), null, Map.of()).size());
        assertTrue(service.describeImages(EAST, List.of(first.getImageId()), null).isEmpty(),
                "the AMI is still deregistered even when its snapshot is kept");
    }

    // ─── CopyImage ────────────────────────────────────────────────────────────

    @Test
    void copyImageProducesADistinctAmiInTheRegionTheCallWasMadeIn() {
        // "The copy operation must be initiated in the destination Region" -- the destination is
        // the caller's own region, so the copy must appear in us-west-2 and not in us-east-1.
        String sourceName = uniqueName("floci-copy-source");
        String sourceId = registerViaHttp(EAST_AUTH, sourceName);
        String copyName = uniqueName("floci-copy-dest");

        String copyId = xmlValue(ec2(WEST_AUTH, "CopyImage",
                "SourceRegion", EAST,
                "SourceImageId", sourceId,
                "Name", copyName,
                "Description", "promoted"), "imageId");

        assertNotEquals(sourceId, copyId);
        assertTrue(copyId.startsWith("ami-"));

        // Compared by imageId rather than anywhere in the XML: the copy names its source in
        // sourceImageId.
        String west = ec2(WEST_AUTH, "DescribeImages", "Owner.1", "self");
        List<String> westIds = XmlParser.extractAll(west, "imageId");
        assertTrue(westIds.contains(copyId), "the copy must be visible in the destination region");
        assertTrue(west.contains(copyName));
        assertFalse(westIds.contains(sourceId), "the source AMI stays in its own region");

        String east = ec2(EAST_AUTH, "DescribeImages", "Owner.1", "self");
        List<String> eastIds = XmlParser.extractAll(east, "imageId");
        assertTrue(eastIds.contains(sourceId));
        assertFalse(eastIds.contains(copyId), "the copy must not leak back into the source region");
    }

    @Test
    void theCopyIsOwnedByTheCallerAndAvailableImmediately() {
        // AWS reports 'pending' until the backing snapshots finish copying. Floci's store is
        // in-memory and the copy completes within the call, so it is 'available' at once, which
        // is what CreateImage and RegisterImage already report.
        Image source = service.registerImage(EAST, uniqueName("floci-copy-state-src"), null,
                "arm64", "/dev/xvda", List.of(mapping("/dev/xvda", "snap-" + hex())));
        Image copy = service.copyImage(WEST, EAST, source.getImageId(),
                uniqueName("floci-copy-state-dst"), "promoted", false, List.of());

        assertEquals("available", copy.getState());
        assertEquals(source.getOwnerId(), copy.getOwnerId());
        assertFalse(copy.isPublic());
        assertEquals("arm64", copy.getArchitecture());
        assertEquals("/dev/xvda", copy.getRootDeviceName());
        assertEquals("promoted", copy.getDescription());
        assertEquals(WEST, copy.getRegion());
    }

    @Test
    void theCopyGetsItsOwnSnapshotsRatherThanSharingTheSourcesBacking() {
        // Two AMIs sharing one snapshot would make deleting either look like it took the other's
        // backing with it -- and the source's snapshots are not in the destination region anyway.
        Image source = service.registerImage(EAST, uniqueName("floci-copy-snap-src"), null,
                "x86_64", "/dev/xvda", List.of(mapping("/dev/xvda", "snap-" + hex())));
        Image copy = service.copyImage(WEST, EAST, source.getImageId(),
                uniqueName("floci-copy-snap-dst"), null, false, List.of());

        assertNotEquals(snapshotIdOf(source), snapshotIdOf(copy));
        assertEquals(1, service.describeSnapshots(WEST, List.of(snapshotIdOf(copy)), null, Map.of()).size());
        assertEquals(1, service.describeSnapshots(EAST, List.of(snapshotIdOf(source)), null, Map.of()).size());
    }

    @Test
    void aCopyCanBeLaunchedInTheDestinationRegion() {
        // The promotion pattern is only useful if the copy is launchable where it landed.
        Image source = service.registerImage(EAST, uniqueName("floci-copy-launch-src"), null,
                "x86_64", "/dev/xvda", null);
        Image copy = service.copyImage(WEST, EAST, source.getImageId(),
                uniqueName("floci-copy-launch-dst"), null, false, List.of());

        String instanceId = service.runInstances(WEST, copy.getImageId(), "t3.micro",
                1, 1, null, List.of(), null, null, List.of(), null, null, null)
                .getInstances().getFirst().getInstanceId();
        assertEquals(copy.getImageId(), service.describeInstances(WEST, List.of(instanceId), java.util.Map.of())
                .getFirst().getInstances().getFirst().getImageId());
    }

    @Test
    void copyingFromARegionThatHasNoSuchImageIsRejected() {
        // Registered AMIs are region-scoped, so a source id that exists in us-east-1 is genuinely
        // absent when SourceRegion names a region it was never copied to.
        String sourceId = registerViaHttp(EAST_AUTH, uniqueName("floci-copy-wrong-region"));

        given()
            .formParam("Action", "CopyImage")
            .formParam("SourceRegion", "eu-west-1")
            .formParam("SourceImageId", sourceId)
            .formParam("Name", uniqueName("floci-copy-wrong-region-dst"))
            .header("Authorization", WEST_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(hasXPath("//*[local-name()='Code']", equalTo("InvalidAMIID.NotFound")));
    }

    @Test
    void copyingADeregisteredSourceIsRejected() {
        String sourceId = registerViaHttp(EAST_AUTH, uniqueName("floci-copy-deregistered"));
        ec2(EAST_AUTH, "DeregisterImage", "ImageId", sourceId);

        given()
            .formParam("Action", "CopyImage")
            .formParam("SourceRegion", EAST)
            .formParam("SourceImageId", sourceId)
            .formParam("Name", uniqueName("floci-copy-deregistered-dst"))
            .header("Authorization", WEST_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(hasXPath("//*[local-name()='Code']", equalTo("InvalidAMIID.Unavailable")));
    }

    @Test
    void copyImageRequiresNameSourceImageIdAndSourceRegion() {
        // All three are documented as required; SourceRegion in particular has no useful default,
        // since a missing one would silently mean "the destination region".
        assertEquals("MissingParameter", assertThrows(AwsException.class,
                () -> service.copyImage(WEST, EAST, null, "n", null, false, List.of())).getErrorCode());
        assertEquals("MissingParameter", assertThrows(AwsException.class,
                () -> service.copyImage(WEST, null, "ami-0abcdef1234567890", "n", null, false, List.of())).getErrorCode());
        assertEquals("MissingParameter", assertThrows(AwsException.class,
                () -> service.copyImage(WEST, EAST, "ami-0abcdef1234567890", null, null, false, List.of())).getErrorCode());
    }

    @Test
    void copyImageCopiesTheSourceTagsOnlyWhenCopyImageTagsIsTrue() {
        // "Default: Your user-defined AMI tags are not copied."
        String sourceId = registerViaHttp(EAST_AUTH, uniqueName("floci-copy-tags-src"));
        ec2(EAST_AUTH, "CreateTags", "ResourceId.1", sourceId,
                "Tag.1.Key", "Team", "Tag.1.Value", "images", "Tag.2.Key", "Stage", "Tag.2.Value", "");

        String byDefault = copyToWest(sourceId, uniqueName("floci-copy-tags-default"));
        String declined = copyToWest(sourceId, uniqueName("floci-copy-tags-declined"), "CopyImageTags", "false");
        String copied = copyToWest(sourceId, uniqueName("floci-copy-tags-copied"), "CopyImageTags", "true");

        assertEquals(Map.of(), tagsOf(WEST_AUTH, byDefault));
        assertEquals(Map.of(), tagsOf(WEST_AUTH, declined));
        assertEquals(Map.of("Team", "images", "Stage", ""), tagsOf(WEST_AUTH, copied));
        List<String> taggedInWest = XmlParser.extractAll(ec2(WEST_AUTH, "DescribeImages",
                "Filter.1.Name", "tag:Team", "Filter.1.Value.1", "images"), "imageId");
        assertTrue(taggedInWest.contains(copied), "DescribeImages filters on the copied tags");
        assertFalse(taggedInWest.contains(byDefault));
        // The source keeps its tags, and the copy's are only in the destination region.
        assertEquals(Map.of("Team", "images", "Stage", ""), tagsOf(EAST_AUTH, sourceId));
        assertEquals(Map.of(), tagsOf(EAST_AUTH, copied));
    }

    @Test
    void copyImageTagsLeaveTheSourcesSystemTagsBehind() {
        // "The following tags are not be copied: System tags (prefixed with aws:)". CreateTags
        // refuses that prefix, so the system tag is written the way Floci writes its own.
        String sourceId = registerViaHttp(EAST_AUTH, uniqueName("floci-copy-system-tags-src"));
        ec2(EAST_AUTH, "CreateTags", "ResourceId.1", sourceId, "Tag.1.Key", "Team", "Tag.1.Value", "images");
        service.createSystemTags(EAST, List.of(sourceId), List.of(new Tag("aws:cloudformation:stack-name", "images")));

        String copied = copyToWest(sourceId, uniqueName("floci-copy-system-tags-dst"), "CopyImageTags", "true");

        assertEquals(Map.of("Team", "images"), tagsOf(WEST_AUTH, copied));
    }

    @Test
    void copyImageTagsCopiesTheCallersTagsOnACatalogImageAndNoOtherAccounts() {
        // The caller's own tags on a public AMI are user-defined tags AWS copies. Another
        // account's tags on it are not, and Floci models no shared tags that would be.
        String catalogId = "ami-0abcdef1234567890";
        String key = "Copied-" + UUID.randomUUID();
        String otherKey = "OtherAccount-" + UUID.randomUUID();
        ec2(EAST_AUTH, "CreateTags", "ResourceId.1", catalogId, "Tag.1.Key", key, "Tag.1.Value", "catalog");
        ec2(OTHER_ACCOUNT_EAST_AUTH, "CreateTags", "ResourceId.1", catalogId,
                "Tag.1.Key", otherKey, "Tag.1.Value", "not-yours");
        try {
            String copied = copyToWest(catalogId, uniqueName("floci-copy-catalog-tags"), "CopyImageTags", "true");

            Map<String, String> copiedTags = tagsOf(WEST_AUTH, copied);
            assertEquals("catalog", copiedTags.get(key));
            assertFalse(copiedTags.containsKey(otherKey), "another account's tag must not reach the copy");
        } finally {
            ec2(EAST_AUTH, "DeleteTags", "ResourceId.1", catalogId, "Tag.1.Key", key);
            ec2(OTHER_ACCOUNT_EAST_AUTH, "DeleteTags", "ResourceId.1", catalogId, "Tag.1.Key", otherKey);
        }
    }

    @Test
    void copyImageTagsReadACatalogImageNamedByItsAliasUnderItsOwnId() {
        // A catalog alias names the same AMI as its id, so the copy takes the tags on that id.
        String key = "ByAlias-" + UUID.randomUUID();
        ec2(EAST_AUTH, "CreateTags", "ResourceId.1", "ami-0abcdef1234567890",
                "Tag.1.Key", key, "Tag.1.Value", "canonical");
        try {
            String copied = copyToWest("ami-amazonlinux2", uniqueName("floci-copy-alias-tags"), "CopyImageTags", "true");

            assertEquals("canonical", tagsOf(WEST_AUTH, copied).get(key));
        } finally {
            ec2(EAST_AUTH, "DeleteTags", "ResourceId.1", "ami-0abcdef1234567890", "Tag.1.Key", key);
        }
    }

    @Test
    void aTagSpecificationTagWinsOverACopiedTagOfTheSameKey() {
        // Both reach the copy; Floci keeps the value the request names explicitly. The public
        // CopyImage reference does not say how AWS settles a key given both ways.
        String sourceId = registerViaHttp(EAST_AUTH, uniqueName("floci-copy-tags-and-spec-src"));
        ec2(EAST_AUTH, "CreateTags", "ResourceId.1", sourceId,
                "Tag.1.Key", "Team", "Tag.1.Value", "images", "Tag.2.Key", "Stage", "Tag.2.Value", "dev");

        String copied = copyToWest(sourceId, uniqueName("floci-copy-tags-and-spec-dst"), "CopyImageTags", "true",
                "TagSpecification.1.ResourceType", "image",
                "TagSpecification.1.Tag.1.Key", "Stage", "TagSpecification.1.Tag.1.Value", "prod");

        assertEquals(Map.of("Team", "images", "Stage", "prod"), tagsOf(WEST_AUTH, copied));
    }

    @Test
    void copiedAndSpecifiedTagsOverTheLimitRefuseTheCopyAndLeaveNothingBehind() {
        // The copy's tags are the copied ones plus the TagSpecification's; past 50 the request
        // fails, and it must fail before the AMI and its snapshot exist.
        Image source = service.registerImage(EAST, uniqueName("floci-copy-tag-limit-src"), null,
                "x86_64", "/dev/xvda", List.of(mapping("/dev/xvda", "snap-" + hex())));
        List<String> sourceTags = new ArrayList<>(List.of("ResourceId.1", source.getImageId()));
        for (int i = 1; i <= 30; i++) {
            sourceTags.addAll(List.of("Tag." + i + ".Key", "copied-" + i, "Tag." + i + ".Value", "v"));
        }
        ec2(EAST_AUTH, "CreateTags", sourceTags.toArray(String[]::new));
        String name = uniqueName("floci-copy-tag-limit-dst");
        int snapshotsBefore = service.describeSnapshots(WEST, List.of(), null, Map.of()).size();
        RequestSpecification copy = given().formParam("Action", "CopyImage").header("Authorization", WEST_AUTH)
                .formParam("SourceRegion", EAST).formParam("SourceImageId", source.getImageId())
                .formParam("Name", name).formParam("CopyImageTags", "true")
                .formParam("TagSpecification.1.ResourceType", "image");
        for (int i = 1; i <= 30; i++) {
            copy = copy.formParam("TagSpecification.1.Tag." + i + ".Key", "specified-" + i);
        }

        copy.when().post("/").then().statusCode(400)
                .body(hasXPath("//*[local-name()='Code']", equalTo("TagLimitExceeded")));

        assertFalse(ec2(WEST_AUTH, "DescribeImages", "Owner.1", "self").contains(name),
                "a refused copy must not be registered");
        assertEquals(snapshotsBefore, service.describeSnapshots(WEST, List.of(), null, Map.of()).size());
    }

    private String copyToWest(String sourceId, String name, String... extraParams) {
        List<String> params = new ArrayList<>(List.of("SourceRegion", EAST, "SourceImageId", sourceId, "Name", name));
        params.addAll(List.of(extraParams));
        return xmlValue(ec2(WEST_AUTH, "CopyImage", params.toArray(String[]::new)), "imageId");
    }

    /** The tags DescribeTags lists for one resource in the caller's region, by key. */
    private Map<String, String> tagsOf(String auth, String resourceId) {
        String xml = ec2(auth, "DescribeTags", "Filter.1.Name", "resource-id", "Filter.1.Value.1", resourceId);
        List<String> keys = XmlParser.extractAll(xml, "key");
        List<String> values = XmlParser.extractAll(xml, "value");
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            tags.put(keys.get(i), values.get(i));
        }
        return tags;
    }

    // ─── Concurrency ──────────────────────────────────────────────────────────
    //
    // Driven through the service rather than over HTTP: requests through the test server are
    // serialized enough that the HTTP path does not reliably reproduce a lost update, so an
    // HTTP-level test would pass with the locks removed (the same trap as
    // Ec2ModifyVpcEndpointTest.concurrentAssociationsAllSurvive).

    @Test
    void concurrentCopiesUnderOneNameYieldExactlyOneAmi() throws Exception {
        // A multi-region promotion job fans out and retries, so the same (region, name) can be
        // requested several times at once. The duplicate-name check is a read-modify-write over
        // the whole image store: unlocked, every racer sees no duplicate and every racer inserts,
        // leaving several AMIs sharing a name that is documented as unique.
        //
        // The window between the scan and the insert is what has to be hit, so the store is
        // padded first: with a few thousand images the scan takes long enough that the race is
        // reproducible rather than theoretical. Removing the lock in registerImage fails this
        // test; the same test against an empty store passes with or without it.
        Image source = padStoreAndRegisterSource();

        for (int round = 0; round < RACE_ROUNDS; round++) {
            String name = uniqueName("floci-race-dst");
            AtomicInteger duplicates = new AtomicInteger();
            List<Image> created = runConcurrently(RACE_THREADS, () -> {
                try {
                    return service.copyImage(WEST, EAST, source.getImageId(), name, null, false, List.of());
                } catch (AwsException e) {
                    assertEquals("InvalidAMIName.Duplicate", e.getErrorCode());
                    duplicates.incrementAndGet();
                    return null;
                }
            });

            List<Image> winners = created.stream().filter(java.util.Objects::nonNull).toList();
            assertEquals(1, winners.size(),
                    "exactly one copy may claim the name; round " + round + " produced " + winners.size());
            assertEquals(RACE_THREADS - 1, duplicates.get());
            assertEquals(1, service.describeImages(WEST, List.of(), List.of("self")).stream()
                    .filter(img -> name.equals(img.getName()))
                    .count(), "the store must not end up with two AMIs sharing a name");
        }
    }

    @Test
    void concurrentDeregistrationsOfOneAmiSucceedExactlyOnce() throws Exception {
        // Deregistration is a read-modify-write on one image: read the state, reject if it is
        // already a tombstone, write the tombstone. Unlocked, two racers can both observe the AMI
        // as available and both report success, so a caller retrying cannot tell which attempt
        // actually did the work -- and with DeleteAssociatedSnapshots both would then run the
        // deletion pass.
        for (int round = 0; round < RACE_ROUNDS; round++) {
            Image image = service.registerImage(EAST, uniqueName("floci-race-deregister"), null,
                    "x86_64", "/dev/xvda", null);

            AtomicInteger successes = new AtomicInteger();
            AtomicInteger unavailable = new AtomicInteger();
            runConcurrently(RACE_THREADS, () -> {
                try {
                    service.deregisterImage(EAST, image.getImageId(), true);
                    successes.incrementAndGet();
                } catch (AwsException e) {
                    assertEquals("InvalidAMIID.Unavailable", e.getErrorCode());
                    unavailable.incrementAndGet();
                }
                return null;
            });

            assertEquals(1, successes.get(), "round " + round + " deregistered the same AMI twice");
            assertEquals(RACE_THREADS - 1, unavailable.get());
            assertTrue(service.describeImages(EAST, List.of(image.getImageId()), List.of()).isEmpty());
        }
    }

    @Test
    void concurrentCopiesUnderDistinctNamesAllSurvive() throws Exception {
        // The name stripe must not swallow unrelated copies: fanning one AMI out under distinct
        // names has to leave every one of them in the store.
        Image source = service.registerImage(EAST, uniqueName("floci-fanout-src"), null,
                "x86_64", "/dev/xvda", null);
        String prefix = uniqueName("floci-fanout-dst");

        AtomicInteger seq = new AtomicInteger();
        List<Image> copies = runConcurrently(24,
                () -> service.copyImage(WEST, EAST, source.getImageId(),
                        prefix + "-" + seq.getAndIncrement(), null, false, List.of()));

        Set<String> ids = copies.stream().map(Image::getImageId).collect(Collectors.toSet());
        assertEquals(24, ids.size(), "every concurrent copy must get a distinct id");
        assertEquals(24, service.describeImages(WEST, List.of(), List.of("self")).stream()
                .filter(img -> img.getName().startsWith(prefix))
                .count(), "every concurrent copy must survive in the store");
    }

    private static final int RACE_THREADS = 24;
    private static final int RACE_ROUNDS = 8;
    private static final int STORE_PADDING = 2000;

    /**
     * Pads the image store so the read-modify-write windows are wide enough to hit, and returns a
     * source AMI to copy. The padding is what makes the race reproducible: the duplicate-name
     * check scans every image in the store.
     */
    private Image padStoreAndRegisterSource() {
        String padPrefix = uniqueName("floci-race-pad");
        for (int i = 0; i < STORE_PADDING; i++) {
            service.registerImage(WEST, padPrefix + "-" + i, null, "x86_64", "/dev/xvda", null);
        }
        return service.registerImage(EAST, uniqueName("floci-race-src"), null,
                "x86_64", "/dev/xvda", null);
    }

    private <T> List<T> runConcurrently(int threads, Callable<T> body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return body.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    /**
     * A DeregisterImage landing after a launch's first tombstone check must leave nothing behind.
     * The rejection used to arrive only once the launch had already persisted its root volume, its
     * tags, its subnet IP and, worst of the four, the attachment on a caller-supplied ENI. No
     * reservation was ever returned, so no caller had an id to clean any of it up with, and the
     * interface stayed "in-use" for good.
     *
     * <p>The interleaving is made exact rather than raced for. The test holds the registry lock
     * the launch has to acquire, which parks the launch at a known point, deregisters the AMI
     * while it is parked, and only then releases. Every store write on the launch path lives on
     * the far side of that lock, so a launch parked there has written nothing yet.
     */
    @Test
    void aTombstoneLandingMidLaunchLeavesNoOrphanedResources() throws Exception {
        Image image = service.registerImage(EAST, uniqueName("floci-midlaunch"), null,
                "x86_64", "/dev/xvda", null);
        String subnetId = service.describeSubnets(EAST, List.of(), Map.of()).getFirst().getSubnetId();
        NetworkInterface eni = service.createNetworkInterface(EAST, subnetId,
                "mid-launch tombstone probe", null, null, null, null);
        int volumesBefore = service.describeVolumes(EAST, List.of(), Map.of()).size();

        AtomicReference<Throwable> outcome = new AtomicReference<>();
        try {
            landATombstoneOnAParkedLaunch(image, subnetId, eni, outcome, volumesBefore);
        } finally {
            // @QuarkusTest classes share one application, and a leftover interface here is visible
            // to every other test in the run. Best effort on purpose: if the invariant above broke
            // the interface is still attached and undeletable, and the assertion that caught that
            // is the news, not the failed cleanup.
            try {
                service.deleteNetworkInterface(EAST, eni.getNetworkInterfaceId());
            } catch (AwsException ignored) {
                // reported by the assertions above
            }
        }
    }

    private void landATombstoneOnAParkedLaunch(Image image, String subnetId, NetworkInterface eni,
                                               AtomicReference<Throwable> outcome, int volumesBefore)
            throws Exception {
        Thread launcher = new Thread(() -> {
            try {
                service.runInstances(EAST, image.getImageId(), "t3.micro", 1, 1, null,
                        List.of(), subnetId, null, List.of(new Tag("Name", "orphan-probe")),
                        null, null, null, eni.getNetworkInterfaceId(), 0);
            } catch (Throwable t) {
                outcome.set(t);
            }
        }, "mid-launch-tombstone");

        synchronized (service.imageRegistryLock()) {
            launcher.start();
            awaitParkedOnTheRegistryLock(launcher);
            service.deregisterImage(EAST, image.getImageId(), false);
        }
        launcher.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(launcher.isAlive(), "the parked launch never finished");

        assertInstanceOf(AwsException.class, outcome.get(), "the launch must be rejected outright");
        assertEquals("InvalidAMIID.Unavailable", ((AwsException) outcome.get()).getErrorCode());

        assertEquals(volumesBefore, service.describeVolumes(EAST, List.of(), Map.of()).size(),
                "a rejected launch must not leave its root volume behind");
        NetworkInterface after = service.describeNetworkInterfaces(EAST,
                List.of(eni.getNetworkInterfaceId()), Map.of(), 0, null).networkInterfaces().getFirst();
        assertNull(after.getAttachment(),
                "a rejected launch must not leave the caller's ENI attached to an instance that does not exist");
        assertEquals("available", after.getStatus(),
                "a rejected launch must return the caller's ENI, not strand it in-use");
    }

    /**
     * Blocks until the launch thread is waiting to enter the registry-lock section of
     * {@code runInstances}, which is the only point at which the tombstone can be landed
     * mid-launch on purpose. Matching on the frame as well as the state keeps a thread blocked
     * somewhere else from being mistaken for a parked launch, which would deregister too early
     * and let the launch fail its *first* check instead, proving nothing.
     */
    private static void awaitParkedOnTheRegistryLock(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() - deadline < 0) {
            StackTraceElement[] stack = thread.getStackTrace();
            if (thread.getState() == Thread.State.BLOCKED && stack.length > 0
                    && "runInstances".equals(stack[0].getMethodName())
                    && Ec2Service.class.getName().equals(stack[0].getClassName())) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("the launch never parked on the image registry lock");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static BlockDeviceMapping mapping(String device, String snapshotId) {
        EbsBlockDevice ebs = new EbsBlockDevice();
        ebs.setSnapshotId(snapshotId);
        ebs.setVolumeSize(8);
        ebs.setVolumeType("gp3");
        ebs.setDeleteOnTermination(true);
        BlockDeviceMapping bdm = new BlockDeviceMapping();
        bdm.setDeviceName(device);
        bdm.setEbs(ebs);
        return bdm;
    }

    private static String snapshotIdOf(Image image) {
        return image.getBlockDeviceMappings().getFirst().getEbs().getSnapshotId();
    }

    private static String hex() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 17);
    }
}
