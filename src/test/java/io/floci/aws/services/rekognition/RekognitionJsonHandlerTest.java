package io.floci.aws.services.rekognition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RekognitionJsonHandlerTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RekognitionService detection = mock(RekognitionService.class);
    private final RekognitionImageValidator images = mock(RekognitionImageValidator.class);
    private final RekognitionCollectionService collections = mock(RekognitionCollectionService.class);
    private final RekognitionJsonHandler handler = new RekognitionJsonHandler(detection, images, collections);

    @ParameterizedTest
    @ValueSource(strings = {"CreateCollection", "DeleteCollection", "DescribeCollection", "ListCollections",
            "IndexFaces", "ListFaces", "DeleteFaces", "SearchFaces", "SearchFacesByImage", "DetectLabels",
            "DetectFaces", "DetectText", "CompareFaces", "DetectModerationLabels"})
    void everyServedActionValidatesBeforeDispatch(String action) throws Exception {
        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle(action, MAPPER.readTree("[]"), REGION));
        assertEquals("SerializationException", error.getErrorCode());
        verifyNoInteractions(detection, images, collections);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void modeledConstraintsAreCheckedBeforeImageReadsOrWrites(String action, String body, String code) throws Exception {
        JsonNode request = MAPPER.readTree(body);
        AwsException error = assertThrows(AwsException.class, () -> handler.handle(action, request, REGION));
        assertEquals(code, error.getErrorCode());
        verifyNoInteractions(detection, images, collections);
    }

    static Stream<Arguments> invalidRequests() {
        String image = "\"Image\":{\"Bytes\":\"\"}";
        return Stream.of(
                Arguments.of("CreateCollection", "{\"CollectionId\":\"bad/id\"}", "InvalidParameterException"),
                Arguments.of("CreateCollection", "{\"CollectionId\":123}", "SerializationException"),
                Arguments.of("CreateCollection", "{\"CollectionId\":\"tenant\",\"Tags\":{\"aws:owner\":\"team\"}}", "InvalidParameterException"),
                Arguments.of("CreateCollection", "{\"CollectionId\":\"tenant\",\"Tags\":{\"owner\":\"invalid!\"}}", "InvalidParameterException"),
                Arguments.of("IndexFaces", "{\"CollectionId\":\"tenant\"," + image + ",\"MaxFaces\":0}", "InvalidParameterException"),
                Arguments.of("IndexFaces", "{\"CollectionId\":\"tenant\"," + image + ",\"QualityFilter\":\"BAD\"}", "InvalidParameterException"),
                Arguments.of("IndexFaces", "{\"CollectionId\":\"tenant\"," + image + ",\"DetectionAttributes\":[\"UNKNOWN\"]}", "InvalidParameterException"),
                Arguments.of("ListFaces", "{\"CollectionId\":\"tenant\",\"MaxResults\":1.2}", "SerializationException"),
                Arguments.of("DeleteFaces", "{\"CollectionId\":\"tenant\"}", "InvalidParameterException"),
                Arguments.of("SearchFacesByImage", "{\"CollectionId\":\"tenant\"," + image + ",\"FaceMatchThreshold\":101}", "InvalidParameterException"),
                Arguments.of("DetectLabels", "{" + image + ",\"MinConfidence\":101}", "InvalidParameterException"),
                Arguments.of("DetectFaces", "{" + image + ",\"Attributes\":[\"UNKNOWN\"]}", "InvalidParameterException"),
                Arguments.of("CompareFaces", "{\"SourceImage\":{\"Bytes\":\"\"},\"TargetImage\":{\"Bytes\":\"\"},\"SimilarityThreshold\":-0.1}", "InvalidParameterException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UnknownAction", "CreateProject"})
    void unservedActionsReturnUnknownOperationBeforeCheckingTheirModeledInput(String action) throws Exception {
        try (Response response = handler.handle(action, MAPPER.readTree("[]"), REGION)) {
            assertEquals(400, response.getStatus());
            assertEquals("UnknownOperationException", ((AwsErrorResponse) response.getEntity()).type());
        }
        verifyNoInteractions(detection, images, collections);
    }

    @Test
    void validRequestsReachTheirOperation() throws Exception {
        JsonNode request = MAPPER.readTree("{\"CollectionId\":\"tenant\"}");
        when(collections.createCollection(request, REGION)).thenReturn(MAPPER.createObjectNode().put("StatusCode", 200));
        try (Response response = handler.handle("CreateCollection", request, REGION)) {
            assertEquals(200, response.getStatus());
        }
        verify(collections).createCollection(request, REGION);
        verifyNoInteractions(detection, images);
    }
}
