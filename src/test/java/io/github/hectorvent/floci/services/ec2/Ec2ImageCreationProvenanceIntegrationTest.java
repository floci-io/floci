package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises creation provenance through the public EC2 Query protocol.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_Image.html">Image</a>
 */
@QuarkusTest
class Ec2ImageCreationProvenanceIntegrationTest {
    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String CATALOG = "ami-0abcdef1234567890";

    @Inject
    Ec2Service service;

    private final List<OwnedResource> images = new ArrayList<>();
    private final List<OwnedResource> instances = new ArrayList<>();

    @AfterEach
    void cleanCreatedResources() {
        for (OwnedResource instance : instances) {
            query(instance.region(), "TerminateInstances", "InstanceId.1", instance.id());
        }
        for (OwnedResource image : images.reversed()) {
            query(image.region(), "DeregisterImage", "ImageId", image.id(),
                    "DeleteAssociatedSnapshots", "true");
        }
    }

    @Test
    void registrationAndCatalogImagesOmitCreationProvenance() {
        String registered = rememberImage(EAST, query(EAST, "RegisterImage", "Name", uniqueName()));

        assertNoProvenance(describe(EAST, registered));
        assertNoProvenance(describe(EAST, CATALOG));
    }

    @ParameterizedTest
    @ValueSource(strings = {EAST, WEST})
    void copyReportsTheRequestedSourceAndItsRegion(String destination) {
        String registered = rememberImage(EAST, query(EAST, "RegisterImage", "Name", uniqueName()));
        String copied = copy(destination, EAST, registered);
        String xml = describe(destination, copied);

        assertProvenance(xml, registered, EAST, null);
        assertNoProvenance(describe(EAST, registered));
    }

    @Test
    void copyOfCopyReportsImmediateSourceWhileRuntimeAncestorStaysFlattened() {
        String first = copy(EAST, EAST, CATALOG);
        String second = copy(WEST, EAST, first);

        assertProvenance(describe(WEST, second), first, EAST, null);
        assertProvenance(describe(EAST, first), CATALOG, EAST, null);
        Image stored = service.describeImages(WEST, List.of(second), List.of()).getFirst();
        assertEquals(CATALOG, stored.getSourceImageId());
        assertEquals(first, stored.getCreationSourceImageId());
        assertEquals(EAST, stored.getCreationSourceImageRegion());
    }

    @Test
    void createImageReportsTheActualInstanceAndImmediateLaunchImage() {
        String copied = copy(WEST, EAST, CATALOG);
        String instance = launch(WEST, copied);
        String captured = capture(WEST, instance);

        assertProvenance(describe(WEST, captured), copied, WEST, instance);
        Image stored = service.describeImages(WEST, List.of(captured), List.of()).getFirst();
        assertEquals(CATALOG, stored.getSourceImageId());
        assertEquals(copied, stored.getCreationSourceImageId());
    }

    @Test
    void copyingACreatedImageDoesNotInheritItsSourceInstance() {
        String instance = launch(EAST, CATALOG);
        String captured = capture(EAST, instance);
        String copied = copy(WEST, EAST, captured);

        assertProvenance(describe(EAST, captured), CATALOG, EAST, instance);
        assertProvenance(describe(WEST, copied), captured, EAST, null);
    }

    @Test
    void copyingACatalogAliasRecordsTheAmiIdItNames() {
        String copied = copy(EAST, EAST, "ami-amazonlinux2");

        assertProvenance(describe(EAST, copied), CATALOG, EAST, null);
    }

    @Test
    void aCaptureOfAnInstanceLaunchedByAliasRecordsTheAmiIdItNames() {
        String instance = launch(EAST, "ami-amazonlinux2");
        String captured = capture(EAST, instance);

        assertProvenance(describe(EAST, captured), CATALOG, EAST, instance);
    }

    @Test
    void theSourceFiltersMatchOnlyImagesWithThatProvenance() {
        String instance = launch(EAST, CATALOG);
        String captured = capture(EAST, instance);
        String copied = copy(WEST, EAST, captured);

        assertEquals(List.of(captured), filteredImageIds(EAST, "source-instance-id", instance));
        assertEquals(List.of(copied), filteredImageIds(WEST, "source-image-id", captured));
        assertTrue(filteredImageIds(WEST, "source-image-region", EAST).contains(copied));
        assertFalse(filteredImageIds(WEST, "source-image-region", WEST).contains(copied));
        assertEquals(List.of(), filteredImageIds(EAST, "source-image-id", "ami-0000000000000none"));
        assertEquals(List.of(captured), filteredImageIds(EAST, "source-instance-id",
                instance.substring(0, instance.length() - 1) + "?"));
        assertEquals(List.of(copied), filteredImageIds(WEST, "source-image-id", "ami-0000000000000none", captured));
    }

    private String copy(String destination, String sourceRegion, String source) {
        return rememberImage(destination, query(destination, "CopyImage",
                "SourceRegion", sourceRegion, "SourceImageId", source, "Name", uniqueName()));
    }

    private String capture(String region, String instance) {
        return rememberImage(region, query(region, "CreateImage",
                "InstanceId", instance, "Name", uniqueName(), "NoReboot", "true"));
    }

    private String launch(String region, String image) {
        String xml = query(region, "RunInstances", "ImageId", image,
                "InstanceType", "t3.micro", "MinCount", "1", "MaxCount", "1");
        String id = only(xml, "instanceId");
        instances.add(new OwnedResource(region, id));
        return id;
    }

    private List<String> filteredImageIds(String region, String filter, String... values) {
        List<String> parameters = new ArrayList<>(List.of("Filter.1.Name", filter));
        for (int i = 0; i < values.length; i++) {
            parameters.add("Filter.1.Value." + (i + 1));
            parameters.add(values[i]);
        }
        return XmlParser.extractAll(query(region, "DescribeImages", parameters.toArray(String[]::new)), "imageId");
    }

    private String rememberImage(String region, String xml) {
        String id = only(xml, "imageId");
        images.add(new OwnedResource(region, id));
        return id;
    }

    private String describe(String region, String image) {
        String xml = query(region, "DescribeImages", "ImageId.1", image);
        assertEquals(image, only(xml, "imageId"));
        assertFalse(xml.contains("creationSource"), "Persistence property names must not leak into EC2 XML");
        return xml;
    }

    private String query(String region, String action, String... parameters) {
        RequestSpecification request = given().formParam("Action", action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261008/"
                        + region + "/ec2/aws4_request");
        for (int i = 0; i < parameters.length; i += 2) {
            request.formParam(parameters[i], parameters[i + 1]);
        }
        return request.post("/").then().statusCode(200).extract().asString();
    }

    private static void assertProvenance(String xml, String image, String region, String instance) {
        assertEquals(image, only(xml, "sourceImageId"));
        assertEquals(region, only(xml, "sourceImageRegion"));
        assertEquals(instance == null ? List.of() : List.of(instance),
                XmlParser.extractAll(xml, "sourceInstanceId"));
    }

    private static void assertNoProvenance(String xml) {
        for (String field : List.of("sourceImageId", "sourceImageRegion", "sourceInstanceId")) {
            assertEquals(List.of(), XmlParser.extractAll(xml, field));
        }
    }

    private static String only(String xml, String field) {
        List<String> values = XmlParser.extractAll(xml, field);
        assertEquals(1, values.size(), "Expected one " + field);
        return values.getFirst();
    }

    private static String uniqueName() {
        return "image-provenance-" + UUID.randomUUID();
    }

    private record OwnedResource(String region, String id) {}
}
