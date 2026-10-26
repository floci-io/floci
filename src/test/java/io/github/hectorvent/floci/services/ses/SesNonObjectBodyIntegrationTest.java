package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.assertError;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.assertSerializationException;

/**
 * The create and update operations that read their body member by member refuse a body that is
 * not a JSON object before any member is read, as AWS's deserializer does, while a blank body is
 * still read as one without members.
 */
@QuarkusTest
class SesNonObjectBodyIntegrationTest {

    private static final List<Arguments> ENDPOINTS = List.of(
            Arguments.of("POST", "/v2/email/identities"),
            Arguments.of("POST", "/v2/email/templates"),
            Arguments.of("PUT", "/v2/email/templates/non-object-ghost"),
            Arguments.of("POST", "/v2/email/custom-verification-email-templates"),
            Arguments.of("PUT", "/v2/email/custom-verification-email-templates/non-object-ghost"),
            Arguments.of("POST", "/v2/email/tags"));

    static Stream<Arguments> nonObjectBodyCases() {
        return ENDPOINTS.stream().flatMap(endpoint -> SesV2SerializationErrors.nonObjectBodies()
                .map(body -> Arguments.of(endpoint.get()[0], endpoint.get()[1], body.get()[0], body.get()[1])));
    }

    static Stream<Arguments> blankBodyCases() {
        return Stream.of(
                Arguments.of("POST", "/v2/email/identities", "EmailIdentity is required."),
                Arguments.of("POST", "/v2/email/templates", "TemplateName is required."),
                Arguments.of("PUT", "/v2/email/templates/non-object-ghost",
                        "Template must have at least a subject, text, or html part."),
                Arguments.of("POST", "/v2/email/custom-verification-email-templates", "TemplateName is required."),
                Arguments.of("PUT", "/v2/email/custom-verification-email-templates/non-object-ghost",
                        "FromEmailAddress is required."),
                Arguments.of("POST", "/v2/email/tags", "Request body is required."));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("nonObjectBodyCases")
    void nonObjectBody_isASerializationException(String method, String path, String body, String message) {
        assertSerializationException(method, path, body, message);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("blankBodyCases")
    void blankBody_isReadAsOneWithoutMembers(String method, String path, String message) {
        assertError(method, path, "", "BadRequestException", message);
    }
}
