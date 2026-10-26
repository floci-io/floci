package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.EXPECTED_MAP;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.LIST;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.NUMBER;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.STRUCTURE;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.assertSerializationException;

/**
 * The reputation entity operations check their body against the SES v2 shapes, so a member of the
 * wrong JSON type is a SerializationException ahead of every other check. The updates name an
 * entity type and a reference that would both be refused, to show the body is read first.
 */
@QuarkusTest
class SesReputationEntityMemberTypeIntegrationTest {

    private static final String LIST_PATH = "/v2/email/reputation/entities";
    private static final String ENTITY_PATH = "/v2/email/reputation/entities/FOO/hello";

    static Stream<Arguments> listReputationEntitiesCases() {
        return Stream.of(
                Arguments.of("stringFilter", "{\"Filter\": \"x\"}", EXPECTED_MAP),
                Arguments.of("arrayFilter", "{\"Filter\": []}", LIST),
                Arguments.of("numericFilterValue", "{\"Filter\": {\"SENDING_STATUS\": 1}}", NUMBER),
                Arguments.of("objectNextToken", "{\"NextToken\": {}}", STRUCTURE),
                Arguments.of("arrayNextToken", "{\"NextToken\": []}", LIST));
    }

    static Stream<Arguments> updateReputationEntityPolicyCases() {
        return Stream.of(
                Arguments.of("numericPolicy", "{\"ReputationEntityPolicy\": 1}", NUMBER),
                Arguments.of("objectPolicy", "{\"ReputationEntityPolicy\": {}}", STRUCTURE),
                Arguments.of("arrayPolicy", "{\"ReputationEntityPolicy\": []}", LIST));
    }

    static Stream<Arguments> updateReputationEntityCustomerManagedStatusCases() {
        return Stream.of(
                Arguments.of("booleanStatus", "{\"SendingStatus\": true}", "TRUE_VALUE can not be converted to a String"),
                Arguments.of("objectStatus", "{\"SendingStatus\": {}}", STRUCTURE),
                Arguments.of("arrayStatus", "{\"SendingStatus\": []}", LIST));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listReputationEntitiesCases")
    void listReputationEntities_wrongMemberType(String caseName, String body, String message) {
        assertSerializationException("POST", LIST_PATH, body, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateReputationEntityPolicyCases")
    void updateReputationEntityPolicy_wrongMemberType(String caseName, String body, String message) {
        assertSerializationException("PUT", ENTITY_PATH + "/policy", body, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateReputationEntityCustomerManagedStatusCases")
    void updateReputationEntityCustomerManagedStatus_wrongMemberType(String caseName, String body, String message) {
        assertSerializationException("PUT", ENTITY_PATH + "/customer-managed-status", body, message);
    }
}
