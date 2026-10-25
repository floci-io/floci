package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.EXPECTED_LIST;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.EXPECTED_NULL;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.LIST;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.NUMBER;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.STRUCTURE;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.assertError;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.assertSerializationException;

/**
 * The configuration set operations whose body nests a structure check it against the SES v2
 * shapes, so a member of the wrong JSON type is a SerializationException ahead of the name
 * validation and the configuration set lookup. The messages per JSON type are covered by
 * SesV2RequestShapesTest; these cases show each operation runs the check, nested members included.
 */
@QuarkusTest
class SesConfigurationSetMemberTypeIntegrationTest {

    private static final String MISSING_CS = "v2-cs-member-type-missing";
    private static final String CREATE = "/v2/email/configuration-sets";
    private static final String MISSING_CS_PATH = CREATE + "/" + MISSING_CS;
    private static final String DESTINATIONS = MISSING_CS_PATH + "/event-destinations";
    private static final String UPDATE = "/v2/email/update-configuration-sets";

    static Stream<Arguments> createConfigurationSetCases() {
        return Stream.of(
                Arguments.of("numericName", "\"ConfigurationSetName\": 123", NUMBER),
                Arguments.of("stringTagsBeforeNameValidation",
                        "\"ConfigurationSetName\": \"\", \"Tags\": \"not-an-array\"", EXPECTED_LIST),
                Arguments.of("stringTagElement", "\"Tags\": [\"env\"]", EXPECTED_NULL),
                Arguments.of("numericTagKey", "\"Tags\": [{\"Key\": 1, \"Value\": \"v\"}]", NUMBER),
                Arguments.of("arrayTrackingOptions", "\"TrackingOptions\": []", LIST),
                Arguments.of("booleanTlsPolicy", "\"DeliveryOptions\": {\"TlsPolicy\": true}",
                        "TRUE_VALUE can not be converted to a String"),
                Arguments.of("stringSuppressionOptions", "\"SuppressionOptions\": \"BOUNCE\"", EXPECTED_NULL),
                Arguments.of("stringSuppressedReasons",
                        "\"SuppressionOptions\": {\"SuppressedReasons\": \"BOUNCE\"}", EXPECTED_LIST),
                Arguments.of("stringConditionThreshold", "\"SuppressionOptions\": {\"SuppressedReasons\": [], "
                        + "\"ValidationOptions\": {\"ConditionThreshold\": \"x\"}}", EXPECTED_NULL),
                Arguments.of("stringSendingOptions", "\"SendingOptions\": \"x\"", EXPECTED_NULL),
                Arguments.of("arrayReputationOptions", "\"ReputationOptions\": []", LIST),
                Arguments.of("arrayDashboardOptions", "\"VdmOptions\": {\"DashboardOptions\": []}", LIST),
                Arguments.of("numericArchiveArn", "\"ArchivingOptions\": {\"ArchiveArn\": 1}", NUMBER),
                Arguments.of("stringSmimeScheme",
                        "\"MessageSecurityOptions\": {\"SigningScheme\": {\"SmimeScheme\": \"x\"}}", EXPECTED_NULL));
    }

    static Stream<Arguments> eventDestinationCases() {
        return Stream.of(
                Arguments.of("stringEventDestination", "\"EventDestination\": \"x\"", EXPECTED_NULL),
                Arguments.of("arrayEventDestination", "\"EventDestination\": []", LIST),
                Arguments.of("stringMatchingEventTypes", destination("\"MatchingEventTypes\": \"SEND\""),
                        EXPECTED_LIST),
                Arguments.of("numericEventType", destination("\"MatchingEventTypes\": [1]"), NUMBER),
                Arguments.of("stringFirehoseDestination", destination("\"KinesisFirehoseDestination\": \"x\""),
                        EXPECTED_NULL),
                Arguments.of("objectDimensionConfigurations",
                        destination("\"CloudWatchDestination\": {\"DimensionConfigurations\": {}}"), STRUCTURE),
                Arguments.of("stringDimensionConfiguration",
                        destination("\"CloudWatchDestination\": {\"DimensionConfigurations\": [\"x\"]}"),
                        EXPECTED_NULL),
                Arguments.of("numericTopicArn", destination("\"SnsDestination\": {\"TopicArn\": 1}"), NUMBER),
                Arguments.of("booleanEventBusArn", destination("\"EventBridgeDestination\": {\"EventBusArn\": false}"),
                        "FALSE_VALUE can not be converted to a String"),
                Arguments.of("arrayPinpointDestination", destination("\"PinpointDestination\": []"), LIST));
    }

    static Stream<Arguments> putSuppressionOptionsCases() {
        return Stream.of(
                Arguments.of("stringSuppressedReasons", "\"SuppressedReasons\": \"BOUNCE\"", EXPECTED_LIST),
                Arguments.of("numericReason", "\"SuppressedReasons\": [1]", NUMBER),
                Arguments.of("objectReason", "\"SuppressedReasons\": [{}]", STRUCTURE),
                Arguments.of("stringConditionThreshold",
                        "\"ValidationOptions\": {\"ConditionThreshold\": \"x\"}", EXPECTED_NULL));
    }

    static Stream<Arguments> putVdmOptionsCases() {
        return Stream.of(
                Arguments.of("stringVdmOptions", "\"VdmOptions\": \"x\"", EXPECTED_NULL),
                Arguments.of("arrayGuardianOptions", "\"VdmOptions\": {\"GuardianOptions\": []}", LIST),
                Arguments.of("numericEngagementMetrics",
                        "\"VdmOptions\": {\"DashboardOptions\": {\"EngagementMetrics\": 1}}", NUMBER));
    }

    static Stream<Arguments> updateConfigurationSetCases() {
        return Stream.of(
                Arguments.of("numericName", "\"ConfigurationSetName\": 123", NUMBER),
                Arguments.of("stringSigningScheme", "\"ConfigurationSetName\": \"" + MISSING_CS + "\", "
                        + "\"MessageSecurityOptions\": {\"SigningScheme\": \"x\"}", EXPECTED_NULL),
                Arguments.of("arrayDefaultScheme", "\"ConfigurationSetName\": \"" + MISSING_CS + "\", "
                        + "\"MessageSecurityOptions\": {\"SigningScheme\": {\"DefaultScheme\": []}}", LIST),
                Arguments.of("numericSignatureFormat", "\"ConfigurationSetName\": \"" + MISSING_CS + "\", "
                        + "\"MessageSecurityOptions\": {\"SigningScheme\": "
                        + "{\"SmimeScheme\": {\"SignatureFormat\": 1}}}", NUMBER));
    }

    static Stream<Arguments> nonObjectBodyEndpoints() {
        return Stream.of(
                Arguments.of("POST", CREATE),
                Arguments.of("POST", DESTINATIONS),
                Arguments.of("PUT", DESTINATIONS + "/ed-missing"),
                Arguments.of("PUT", MISSING_CS_PATH + "/suppression-options"),
                Arguments.of("PUT", MISSING_CS_PATH + "/vdm-options"),
                Arguments.of("POST", UPDATE));
    }

    static Stream<Arguments> blankBodyEndpoints() {
        return Stream.of(
                Arguments.of("POST", CREATE, "The configuration set name must be specified."),
                Arguments.of("POST", DESTINATIONS, "EventDestinationName is required."),
                Arguments.of("PUT", DESTINATIONS + "/ed-missing", "EventDestination is required."),
                Arguments.of("POST", UPDATE, "ConfigurationSetName is required."));
    }

    private static String destination(String members) {
        return "\"EventDestination\": {" + members + "}";
    }

    private static void assertMembersRejected(String method, String path, String members, String message) {
        assertSerializationException(method, path, "{" + members + "}", message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("createConfigurationSetCases")
    void createConfigurationSet_wrongMemberType(String caseName, String members, String message) {
        assertMembersRejected("POST", CREATE, members, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventDestinationCases")
    void createConfigurationSetEventDestination_wrongMemberTypeBeforeLookup(String caseName, String members,
                                                                            String message) {
        assertMembersRejected("POST", DESTINATIONS,
                "\"EventDestinationName\": \"ed-" + caseName + "\", " + members, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventDestinationCases")
    void updateConfigurationSetEventDestination_wrongMemberTypeBeforeLookup(String caseName, String members,
                                                                            String message) {
        assertMembersRejected("PUT", DESTINATIONS + "/ed-missing", members, message);
    }

    @Test
    void createConfigurationSetEventDestination_numericNameBeforeLookup() {
        assertMembersRejected("POST", DESTINATIONS,
                "\"EventDestinationName\": 123, " + destination("\"Enabled\": true"), NUMBER);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("putSuppressionOptionsCases")
    void putConfigurationSetSuppressionOptions_wrongMemberTypeBeforeLookup(String caseName, String members,
                                                                           String message) {
        assertMembersRejected("PUT", MISSING_CS_PATH + "/suppression-options", members, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("putVdmOptionsCases")
    void putConfigurationSetVdmOptions_wrongMemberTypeBeforeLookup(String caseName, String members,
                                                                   String message) {
        assertMembersRejected("PUT", MISSING_CS_PATH + "/vdm-options", members, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateConfigurationSetCases")
    void updateConfigurationSet_wrongMemberTypeBeforeLookup(String caseName, String members, String message) {
        assertMembersRejected("POST", UPDATE, members, message);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("nonObjectBodyEndpoints")
    void nonObjectBody_isRejectedBeforeTheShapeCheck(String method, String path) {
        assertError(method, path, "[]", "SerializationException", LIST);
        assertError(method, path, "\"x\"", "SerializationException", null);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("blankBodyEndpoints")
    void blankBody_isReadAsAnEmptyObject(String method, String path, String message) {
        assertError(method, path, "", "BadRequestException", message);
    }

    @Test
    void createConfigurationSet_nullTagElement_isLeftToTheTagParser() {
        assertError("POST", CREATE, "{\"ConfigurationSetName\": \"v2-cs-member-type-null-tag\", \"Tags\": [null]}",
                "SerializationException", null);
    }

    @Test
    void putConfigurationSetSuppressionOptions_nullReason_isAnInvalidReason() {
        assertError("PUT", MISSING_CS_PATH + "/suppression-options", "{\"SuppressedReasons\": [null]}",
                "BadRequestException", "Reason null is invalid, must be one of [BOUNCE, COMPLAINT].");
    }
}
