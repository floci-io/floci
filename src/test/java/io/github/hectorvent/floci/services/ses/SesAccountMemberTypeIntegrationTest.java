package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
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
 * PutAccountVdmAttributes and PutAccountSuppressionAttributes check their body against the SES v2
 * shapes, so a member of the wrong JSON type, nested ones included, is a SerializationException
 * ahead of the required and enum checks. Every case fails before the account state is touched.
 */
@QuarkusTest
class SesAccountMemberTypeIntegrationTest {

    private static final String VDM = "/v2/email/account/vdm";
    private static final String SUPPRESSION = "/v2/email/account/suppression";

    static Stream<Arguments> putAccountVdmAttributesCases() {
        return Stream.of(
                Arguments.of("stringVdmAttributes", "{\"VdmAttributes\": \"x\"}", EXPECTED_NULL),
                Arguments.of("arrayVdmAttributes", "{\"VdmAttributes\": []}", LIST),
                Arguments.of("booleanVdmEnabled", "{\"VdmAttributes\": {\"VdmEnabled\": true}}",
                        "TRUE_VALUE can not be converted to a String"),
                Arguments.of("stringDashboardAttributes",
                        "{\"VdmAttributes\": {\"VdmEnabled\": \"ENABLED\", \"DashboardAttributes\": \"oops\"}}",
                        EXPECTED_NULL),
                Arguments.of("numericOptimizedSharedDelivery", "{\"VdmAttributes\": {\"VdmEnabled\": \"ENABLED\", "
                        + "\"GuardianAttributes\": {\"OptimizedSharedDelivery\": 1}}}", NUMBER));
    }

    static Stream<Arguments> putAccountSuppressionAttributesCases() {
        return Stream.of(
                Arguments.of("stringSuppressedReasons", "{\"SuppressedReasons\": \"BOUNCE\"}", EXPECTED_LIST),
                Arguments.of("numericReasons", "{\"SuppressedReasons\": [1, 2]}", NUMBER),
                Arguments.of("objectReason", "{\"SuppressedReasons\": [{}]}", STRUCTURE),
                Arguments.of("stringConditionThreshold",
                        "{\"ValidationAttributes\": {\"ConditionThreshold\": \"x\"}}", EXPECTED_NULL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("putAccountVdmAttributesCases")
    void putAccountVdmAttributes_wrongMemberType(String caseName, String body, String message) {
        assertSerializationException("PUT", VDM, body, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("putAccountSuppressionAttributesCases")
    void putAccountSuppressionAttributes_wrongMemberType(String caseName, String body, String message) {
        assertSerializationException("PUT", SUPPRESSION, body, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonObjectBodyPaths")
    void nonObjectBody_isRejectedBeforeTheShapeCheck(String path) {
        assertError("PUT", path, "[]", "SerializationException", LIST);
        assertError("PUT", path, "\"x\"", "SerializationException", null);
    }

    static Stream<String> nonObjectBodyPaths() {
        return Stream.of(VDM, SUPPRESSION);
    }
}
