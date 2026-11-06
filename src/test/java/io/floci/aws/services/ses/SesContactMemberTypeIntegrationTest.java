package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.floci.aws.services.ses.SesV2SerializationErrors.EXPECTED_LIST;
import static io.floci.aws.services.ses.SesV2SerializationErrors.EXPECTED_NULL;
import static io.floci.aws.services.ses.SesV2SerializationErrors.LIST;
import static io.floci.aws.services.ses.SesV2SerializationErrors.NUMBER;
import static io.floci.aws.services.ses.SesV2SerializationErrors.STRUCTURE;
import static io.floci.aws.services.ses.SesV2SerializationErrors.assertError;
import static io.floci.aws.services.ses.SesV2SerializationErrors.assertSerializationException;

/**
 * The contact list and contact operations whose body holds a list of structures, and ListContacts
 * with its nested Filter, check it against the SES v2 shapes, so a member of the wrong JSON type is
 * a SerializationException ahead of the name validation and the contact list lookup. The messages
 * per JSON type are covered by SesV2RequestShapesTest; these cases show each operation runs the
 * check, nested members included.
 */
@QuarkusTest
class SesContactMemberTypeIntegrationTest {

    private static final String CONTACT_LISTS = "/v2/email/contact-lists";
    private static final String MISSING_LIST = CONTACT_LISTS + "/v2-contact-member-type-missing";
    private static final String CONTACTS = MISSING_LIST + "/contacts";
    private static final String TOPIC =
            "{\"TopicName\": \"news\", \"DisplayName\": \"News\", \"DefaultSubscriptionStatus\": \"OPT_IN\"}";
    private static final String PREFERENCE = "{\"TopicName\": \"news\", \"SubscriptionStatus\": \"OPT_OUT\"}";

    static Stream<Arguments> createContactListCases() {
        return Stream.of(
                Arguments.of("numericName", "\"ContactListName\": 1", NUMBER),
                Arguments.of("stringTopicsBeforeNameValidation",
                        "\"ContactListName\": \"\", \"Topics\": \"news\"", EXPECTED_LIST),
                Arguments.of("objectTopics", "\"Topics\": {}", STRUCTURE),
                Arguments.of("stringTopic", "\"Topics\": [\"news\"]", EXPECTED_NULL),
                Arguments.of("arrayTopic", "\"Topics\": [[]]", LIST),
                Arguments.of("numericTopicName", "\"Topics\": [{\"TopicName\": 1}]", NUMBER),
                Arguments.of("booleanDefaultSubscriptionStatus",
                        "\"Topics\": [{\"TopicName\": \"news\", \"DefaultSubscriptionStatus\": true}]",
                        "TRUE_VALUE can not be converted to a String"),
                Arguments.of("tagsBeforeTopicsInBodyOrder", "\"Tags\": \"x\", \"Topics\": {}", EXPECTED_LIST),
                Arguments.of("numericDescription", "\"ContactListName\": \"x\", \"Description\": 1", NUMBER));
    }

    static Stream<Arguments> updateContactListCases() {
        return Stream.of(
                Arguments.of("stringTopics", "\"Topics\": \"news\"", EXPECTED_LIST),
                Arguments.of("numericDisplayName", "\"Topics\": [{\"TopicName\": \"news\", \"DisplayName\": 1}]",
                        NUMBER),
                Arguments.of("booleanDescription", "\"Description\": false",
                        "FALSE_VALUE can not be converted to a String"));
    }

    static Stream<Arguments> createContactCases() {
        return Stream.of(
                Arguments.of("numericEmailAddress", "\"EmailAddress\": 1", NUMBER),
                Arguments.of("stringTopicPreferences",
                        "\"EmailAddress\": \"a@example.com\", \"TopicPreferences\": \"news\"", EXPECTED_LIST),
                Arguments.of("stringTopicPreference", "\"TopicPreferences\": [\"news\"]", EXPECTED_NULL),
                Arguments.of("numericSubscriptionStatus",
                        "\"TopicPreferences\": [{\"TopicName\": \"news\", \"SubscriptionStatus\": 1}]", NUMBER),
                Arguments.of("objectAttributesData", "\"AttributesData\": {}", STRUCTURE));
    }

    static Stream<Arguments> updateContactCases() {
        return Stream.of(
                Arguments.of("objectTopicPreferences", "\"TopicPreferences\": {}", STRUCTURE),
                Arguments.of("arrayTopicName", "\"TopicPreferences\": [{\"TopicName\": []}]", LIST),
                Arguments.of("arrayAttributesData", "\"AttributesData\": []", LIST));
    }

    static Stream<Arguments> listContactsCases() {
        return Stream.of(
                Arguments.of("numericNextToken", "\"NextToken\": 1", NUMBER),
                Arguments.of("stringFilter", "\"Filter\": \"x\"", EXPECTED_NULL),
                Arguments.of("arrayTopicFilter", "\"Filter\": {\"TopicFilter\": []}", LIST),
                Arguments.of("numericTopicFilterTopicName",
                        "\"Filter\": {\"FilteredStatus\": \"OPT_IN\", \"TopicFilter\": {\"TopicName\": 1}}", NUMBER),
                Arguments.of("filterTypeBeforePageSizeValidation",
                        "\"PageSize\": 0, \"Filter\": {\"TopicFilter\": \"x\"}", EXPECTED_NULL));
    }

    // A null element passes AWS's deserializer ([null, "x"] answers "Expected null" for "x"), and AWS
    // then answers 500 InternalFailure (probed 2026-10-17). Floci keeps a 400 naming the element.
    static Stream<Arguments> nullElementCases() {
        return Stream.of(
                Arguments.of("POST", CONTACT_LISTS, "{\"ContactListName\": \"x\", \"Topics\": [null]}",
                        nullTopicName("topics", 1)),
                Arguments.of("PUT", MISSING_LIST, "{\"Topics\": [" + TOPIC + ", null]}", nullTopicName("topics", 2)),
                Arguments.of("POST", CONTACTS, "{\"EmailAddress\": \"a@example.com\", \"TopicPreferences\": [null]}",
                        nullTopicName("topicPreferences", 1)),
                Arguments.of("PUT", CONTACTS + "/a@example.com", "{\"TopicPreferences\": [" + PREFERENCE + ", null]}",
                        nullTopicName("topicPreferences", 2)));
    }

    static Stream<Arguments> nullElementBeforeWrongTypeCases() {
        return Stream.of(
                Arguments.of("POST", CONTACT_LISTS, "{\"Topics\": [null, \"x\"]}"),
                Arguments.of("PUT", MISSING_LIST, "{\"Topics\": [null, \"x\"]}"),
                Arguments.of("POST", CONTACTS, "{\"TopicPreferences\": [null, \"x\"]}"),
                Arguments.of("PUT", CONTACTS + "/a@example.com", "{\"TopicPreferences\": [null, \"x\"]}"));
    }

    private static String nullTopicName(String member, int position) {
        return "1 validation error detected: Value at '" + member + "." + position
                + ".member.topicName' failed to satisfy constraint: Member must not be null";
    }

    private static void assertMembersRejected(String method, String path, String members, String message) {
        assertSerializationException(method, path, "{" + members + "}", message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("createContactListCases")
    void createContactList_wrongMemberType(String caseName, String members, String message) {
        assertMembersRejected("POST", CONTACT_LISTS, members, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateContactListCases")
    void updateContactList_wrongMemberTypeBeforeLookup(String caseName, String members, String message) {
        assertMembersRejected("PUT", MISSING_LIST, members, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("createContactCases")
    void createContact_wrongMemberTypeBeforeLookup(String caseName, String members, String message) {
        assertMembersRejected("POST", CONTACTS, members, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateContactCases")
    void updateContact_wrongMemberTypeBeforeLookup(String caseName, String members, String message) {
        assertMembersRejected("PUT", CONTACTS + "/a@example.com", members, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listContactsCases")
    void listContacts_wrongMemberTypeBeforeLookup(String caseName, String members, String message) {
        assertMembersRejected("POST", CONTACTS + "/list", members, message);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("nullElementCases")
    void nullElement_isAValidationErrorNotAServerError(String method, String path, String body, String message) {
        assertError(method, path, body, "BadRequestException", message);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("nullElementBeforeWrongTypeCases")
    void nullElement_passesTheShapeCheck(String method, String path, String body) {
        assertSerializationException(method, path, body, EXPECTED_NULL);
    }
}
