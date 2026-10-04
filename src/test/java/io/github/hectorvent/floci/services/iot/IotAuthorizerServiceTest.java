package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iot.model.IotAuthorizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IotAuthorizerServiceTest {

    private static final String REGION = "us-east-1";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:auth";
    private static final String ARN_PREFIX = "arn:aws:iot:us-east-1:000000000000:authorizer/";

    private static String rsaKey1;
    private static String rsaKey2;
    private static String rsa1024Key;
    private static String ecKey;
    private static String privateKey;

    private final ObjectMapper mapper = new ObjectMapper();
    private final IotAuthorizerService service =
            new IotAuthorizerService(new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));

    @BeforeAll
    static void generateKeys() throws GeneralSecurityException {
        KeyPair rsa = keyPair("RSA", 2048);
        rsaKey1 = pem("PUBLIC KEY", rsa.getPublic().getEncoded());
        privateKey = pem("PRIVATE KEY", rsa.getPrivate().getEncoded());
        rsaKey2 = pem("PUBLIC KEY", keyPair("RSA", 2048).getPublic().getEncoded());
        rsa1024Key = pem("PUBLIC KEY", keyPair("RSA", 1024).getPublic().getEncoded());
        ecKey = pem("PUBLIC KEY", keyPair("EC", 256).getPublic().getEncoded());
    }

    private static KeyPair keyPair(String algorithm, int bits) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----";
    }

    private ObjectNode unsigned() {
        return mapper.createObjectNode().put("authorizerFunctionArn", FUNCTION_ARN).put("signingDisabled", true);
    }

    private ObjectNode signed(Map<String, String> keys) {
        ObjectNode request = mapper.createObjectNode().put("authorizerFunctionArn", FUNCTION_ARN).put("tokenKeyName", "tok");
        request.set("tokenSigningPublicKeys", mapper.valueToTree(keys));
        return request;
    }

    private ObjectNode keysOnly(Map<String, String> keys) {
        ObjectNode request = mapper.createObjectNode();
        request.set("tokenSigningPublicKeys", mapper.valueToTree(keys));
        return request;
    }

    private static AwsException assertAwsError(String code, int status, String message, Executable call) {
        AwsException failure = assertThrows(AwsException.class, call);
        assertEquals(code, failure.getErrorCode());
        assertEquals(status, failure.getHttpStatus());
        assertEquals(message, failure.getMessage());
        return failure;
    }

    private static void assertInvalid(String message, Executable call) {
        assertAwsError("InvalidRequestException", 400, message, call);
    }

    private List<String> names(IotService.Page<IotAuthorizer> page) {
        return page.items().stream().map(IotAuthorizer::getAuthorizerName).toList();
    }

    @Test
    void createWithSigningDisabledAppliesTheAwsDefaults() {
        IotAuthorizer created = service.createAuthorizer("plain", unsigned(), REGION);

        assertEquals("plain", created.getAuthorizerName());
        assertEquals(ARN_PREFIX + "plain", created.getAuthorizerArn());
        assertEquals(FUNCTION_ARN, created.getAuthorizerFunctionArn());
        assertEquals("INACTIVE", created.getStatus());
        assertTrue(created.isSigningDisabled());
        assertFalse(created.isEnableCachingForHttp());
        assertNull(created.getTokenKeyName());
        assertNull(created.getTokenSigningPublicKeys());
        assertEquals(created.getCreationDate(), created.getLastModifiedDate());
        assertEquals(created.getAuthorizerArn(), service.describeAuthorizer("plain", REGION).getAuthorizerArn());
    }

    @Test
    void createSignedAuthorizerKeepsThePemVerbatim() {
        ObjectNode request = signed(Map.of("k1", rsaKey1 + "\n")).put("status", "ACTIVE").put("enableCachingForHttp", true);

        IotAuthorizer created = service.createAuthorizer("signed", request, REGION);

        assertFalse(created.isSigningDisabled());
        assertEquals("tok", created.getTokenKeyName());
        assertEquals(Map.of("k1", rsaKey1 + "\n"), created.getTokenSigningPublicKeys());
        assertEquals("ACTIVE", created.getStatus());
        assertTrue(created.isEnableCachingForHttp());
    }

    /** The envelopes measured on AWS, which reads the PEM line by line. */
    static Stream<Arguments> malformedPemEnvelopes() {
        List<String> body = rsaKey1.lines().filter(line -> !line.startsWith("-----")).toList();
        String base64 = String.join("", body);
        String bodyLines = String.join("\n", body);
        String notRsa = "Authorizer a public key for key name k1 not a valid RSA key";
        return Stream.of(
                Arguments.of("raw base64, no envelope", base64, notRsa),
                Arguments.of("body lines, no envelope", bodyLines, notRsa),
                Arguments.of("indented header line", "\n  " + rsaKey1 + "  \n", notRsa),
                Arguments.of("envelope on one line", "-----BEGIN PUBLIC KEY-----" + base64 + "-----END PUBLIC KEY-----", notRsa),
                Arguments.of("header without end line", "-----BEGIN PUBLIC KEY-----\n" + bodyLines,
                        "Cannot convert public key PEM for authorizer a to RSA key"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedPemEnvelopes")
    void createRejectsAPemEnvelopeAwsCannotRead(String label, String pem, String message) {
        assertInvalid(message, () -> service.createAuthorizer("a", signed(Map.of("k1", pem)), REGION));
    }

    @Test
    void createSkipsTextBeforeThePemHeaderAndKeepsTheValueVerbatim() {
        String pem = "junk\n" + rsaKey1;

        IotAuthorizer created = service.createAuthorizer("prefixed", signed(Map.of("k1", pem)), REGION);

        assertEquals(Map.of("k1", pem), created.getTokenSigningPublicKeys());
        assertEquals(Map.of("k1", pem), service.describeAuthorizer("prefixed", REGION).getTokenSigningPublicKeys());
    }

    @Test
    void createAcceptsSigningDisabledWithATokenKeyNameAndAnyWellFormedFunctionArn() {
        ObjectNode request = unsigned().put("tokenKeyName", "tok")
                .put("authorizerFunctionArn", "arn:aws:lambda:eu-west-1:111122223333:function:elsewhere");

        assertEquals("tok", service.createAuthorizer("loose", request, REGION).getTokenKeyName());
    }

    @Test
    void createRejectsAnInvalidSigningConfigurationWithTheAwsMessages() {
        Map<ObjectNode, String> cases = new LinkedHashMap<>();
        cases.put(mapper.createObjectNode().put("authorizerFunctionArn", FUNCTION_ARN),
                "Token key name for authorizer a cannot be null");
        cases.put(mapper.createObjectNode().put("authorizerFunctionArn", FUNCTION_ARN).put("tokenKeyName", "tok"),
                "Token signing keys map for authorizer a cannot be null or empty. There must be at least one and at most two token signing public keys in the map");
        cases.put(signed(Map.of("k1", rsaKey1)).put("tokenKeyName", (String) null),
                "Token key name for authorizer a cannot be null");
        cases.put(signed(Map.of("k1", rsaKey1)).put("signingDisabled", true),
                "Token signing keys map must be null for authorizer a if using optional signature header");
        cases.put(signed(Map.of("k1", rsaKey1, "k2", rsaKey2, "k3", rsaKey1)),
                "Token signing keys map for authorizer a cannot contain more than 2 keys");
        cases.put(signed(Map.of("k1", "")), "Token signing public keys for authorizer a cannot be null or empty");
        cases.put(signed(Map.of("k1", "not-a-pem")), "Authorizer a public key for key name k1 not a valid RSA key");
        cases.put(signed(Map.of("k1", ecKey)), "Authorizer a public key for key name k1 not a valid RSA key");
        cases.put(signed(Map.of("k1", rsa1024Key)),
                "Authorizer a public key for key name k1 invalid: Key must be 2048 bits but was 1024 bits");
        cases.put(signed(Map.of("k1", privateKey)), "Cannot convert public key PEM for authorizer a to RSA key");

        cases.forEach((request, message) -> assertInvalid(message, () -> service.createAuthorizer("a", request, REGION)));
        assertAwsError("ResourceNotFoundException", 404, "Authorizer a not found",
                () -> service.describeAuthorizer("a", REGION));
    }

    @Test
    void createRejectsMalformedMembersWithTheAwsValidationMessages() {
        String prefix = "1 validation error detected: Value at ";
        assertInvalid(prefix + "'authorizerName' failed to satisfy constraint: Member must satisfy regular expression pattern: [\\w=,@-]+",
                () -> service.createAuthorizer("bad name!", unsigned(), REGION));
        assertInvalid(prefix + "'authorizerName' failed to satisfy constraint: Member must have length less than or equal to 128",
                () -> service.createAuthorizer("a".repeat(129), unsigned(), REGION));
        assertInvalid(prefix + "'authorizerFunctionArn' failed to satisfy constraint: Member must not be null",
                () -> service.createAuthorizer("a", unsigned().putNull("authorizerFunctionArn"), REGION));
        assertInvalid(prefix + "'status' failed to satisfy constraint: Member must satisfy enum value set: [ACTIVE, INACTIVE]",
                () -> service.createAuthorizer("a", unsigned().put("status", "BOGUS"), REGION));
        assertInvalid(prefix + "'tokenKeyName' failed to satisfy constraint: Member must satisfy regular expression pattern: [a-zA-Z0-9_-]+",
                () -> service.createAuthorizer("a", unsigned().put("tokenKeyName", "bad name!"), REGION));
        assertInvalid(prefix + "'tokenKeyName' failed to satisfy constraint: Member must have length less than or equal to 128",
                () -> service.createAuthorizer("a", unsigned().put("tokenKeyName", "t".repeat(129)), REGION));
        String nameConstraint = "Value at 'authorizerName' failed to satisfy constraint: ";
        assertInvalid("2 validation errors detected: " + nameConstraint + "Member must satisfy regular expression pattern: [\\w=,@-]+; "
                        + nameConstraint + "Member must have length less than or equal to 128",
                () -> service.createAuthorizer("!".repeat(129), unsigned(), REGION));
        String tokenConstraint = "Value at 'tokenKeyName' failed to satisfy constraint: ";
        assertInvalid("2 validation errors detected: " + tokenConstraint + "Member must satisfy regular expression pattern: [a-zA-Z0-9_-]+; "
                        + tokenConstraint + "Member must have length greater than or equal to 1",
                () -> service.createAuthorizer("a", unsigned().put("tokenKeyName", ""), REGION));
        assertInvalid("2 validation errors detected: " + tokenConstraint + "Member must satisfy regular expression pattern: [a-zA-Z0-9_-]+; "
                        + tokenConstraint + "Member must have length less than or equal to 128",
                () -> service.createAuthorizer("a", unsigned().put("tokenKeyName", "!".repeat(129)), REGION));
        assertInvalid(prefix + "'tokenSigningPublicKeys' failed to satisfy constraint: Map keys must satisfy constraint: "
                        + "[Member must have length less than or equal to 128, Member must have length greater than or equal to 1, "
                        + "Member must satisfy regular expression pattern: [a-zA-Z0-9:_-]+]",
                () -> service.createAuthorizer("a", signed(Map.of("bad key", rsaKey1)), REGION));
        for (String arn : List.of("not-an-arn", "arn:aws:sqs:us-east-1:000000000000:queue")) {
            assertInvalid("Lambda function arn for authorizer a is not in proper ARN syntax",
                    () -> service.createAuthorizer("a", unsigned().put("authorizerFunctionArn", arn), REGION));
        }
    }

    @Test
    void createRejectsADuplicateNameAndKeepsTheOriginal() {
        Instant created = service.createAuthorizer("dup", unsigned(), REGION).getLastModifiedDate();

        AwsException failure = assertAwsError("ResourceAlreadyExistsException", 409,
                "Authorizer dup already exist for this account",
                () -> service.createAuthorizer("dup", unsigned().put("status", "ACTIVE"), REGION));

        assertEquals(Map.of("resourceId", "dup", "resourceArn", ARN_PREFIX + "dup"), failure.getExtendedData());
        assertEquals("INACTIVE", service.describeAuthorizer("dup", REGION).getStatus());
        assertEquals(created, service.describeAuthorizer("dup", REGION).getLastModifiedDate());
    }

    @Test
    void missingAuthorizerIsNotFound() {
        String message = "Authorizer missing not found";
        assertAwsError("ResourceNotFoundException", 404, message, () -> service.describeAuthorizer("missing", REGION));
        assertAwsError("ResourceNotFoundException", 404, message,
                () -> service.updateAuthorizer("missing", mapper.createObjectNode(), REGION));
        assertAwsError("ResourceNotFoundException", 404, message, () -> service.deleteAuthorizer("missing", REGION));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"describe", "update", "delete"})
    void pathOperationsValidateTheNameBeforeTheLookup(String operation) {
        String constraint = "Value at 'authorizerName' failed to satisfy constraint: ";
        String pattern = constraint + "Member must satisfy regular expression pattern: [\\w=,@-]+";
        String tooLong = constraint + "Member must have length less than or equal to 128";

        assertInvalid("1 validation error detected: " + pattern, byName(operation, "bad name!"));
        assertInvalid("1 validation error detected: " + tooLong, byName(operation, "a".repeat(129)));
        assertInvalid("2 validation errors detected: " + pattern + "; " + tooLong, byName(operation, "!".repeat(129)));
        assertAwsError("ResourceNotFoundException", 404, "Authorizer absent not found", byName(operation, "absent"));
    }

    private Executable byName(String operation, String name) {
        return switch (operation) {
            case "describe" -> () -> service.describeAuthorizer(name, REGION);
            case "update" -> () -> service.updateAuthorizer(name, mapper.createObjectNode().put("status", "INACTIVE"), REGION);
            case "delete" -> () -> service.deleteAuthorizer(name, REGION);
            default -> throw new IllegalArgumentException(operation);
        };
    }

    @Test
    void updateChangesOnlyTheFieldsSentAndBumpsLastModifiedDate() throws InterruptedException {
        Instant created = service.createAuthorizer("upd", unsigned(), REGION).getLastModifiedDate();
        Thread.sleep(5);

        service.updateAuthorizer("upd", mapper.createObjectNode(), REGION);
        assertEquals(created, service.describeAuthorizer("upd", REGION).getLastModifiedDate());

        IotAuthorizer active = service.updateAuthorizer("upd", mapper.createObjectNode().put("status", "ACTIVE"), REGION);
        assertEquals("ACTIVE", active.getStatus());
        assertTrue(active.getLastModifiedDate().isAfter(created));
        assertEquals(created, active.getCreationDate());

        IotAuthorizer moved = service.updateAuthorizer("upd", mapper.createObjectNode()
                .put("authorizerFunctionArn", FUNCTION_ARN + "2").put("enableCachingForHttp", true)
                .put("tokenKeyName", "tok"), REGION);
        assertEquals(FUNCTION_ARN + "2", moved.getAuthorizerFunctionArn());
        assertTrue(moved.isEnableCachingForHttp());
        assertEquals("tok", moved.getTokenKeyName());
        assertEquals("ACTIVE", moved.getStatus());
        assertTrue(moved.isSigningDisabled());
        assertNull(moved.getTokenSigningPublicKeys());
    }

    @Test
    void updateValidatesLikeCreateAndChangesNothingOnFailure() {
        service.createAuthorizer("val", unsigned(), REGION);

        assertInvalid("1 validation error detected: Value at 'status' failed to satisfy constraint: Member must satisfy enum value set: [ACTIVE, INACTIVE]",
                () -> service.updateAuthorizer("val", mapper.createObjectNode().put("status", "BOGUS"), REGION));
        assertInvalid("Lambda function arn for authorizer val is not in proper ARN syntax",
                () -> service.updateAuthorizer("val", mapper.createObjectNode().put("authorizerFunctionArn", "nope"), REGION));
        assertInvalid("Token signing keys map must be null for authorizer val if using optional signature header",
                () -> service.updateAuthorizer("val", keysOnly(Map.of("k1", rsaKey1)), REGION));
        assertEquals(FUNCTION_ARN, service.describeAuthorizer("val", REGION).getAuthorizerFunctionArn());
    }

    @Test
    void updateMergesSigningKeysPerKeyNameAndRefusesAThird() throws InterruptedException {
        service.createAuthorizer("merge", signed(Map.of("k1", rsaKey1)), REGION);

        service.updateAuthorizer("merge", keysOnly(Map.of("k2", rsaKey2)), REGION);
        assertEquals(Map.of("k1", rsaKey1, "k2", rsaKey2), service.describeAuthorizer("merge", REGION).getTokenSigningPublicKeys());

        service.updateAuthorizer("merge", keysOnly(Map.of("k1", rsaKey2)), REGION);
        assertEquals(Map.of("k1", rsaKey2, "k2", rsaKey2), service.describeAuthorizer("merge", REGION).getTokenSigningPublicKeys());

        assertInvalid("Number of signing keys for authorizer merge must neither be zero nor greater than 2",
                () -> service.updateAuthorizer("merge", keysOnly(Map.of("k3", rsaKey1)), REGION));
        assertInvalid("Token signing keys map for authorizer merge cannot contain more than 2 keys",
                () -> service.updateAuthorizer("merge", keysOnly(Map.of("k1", rsaKey1, "k2", rsaKey1, "k3", rsaKey1)), REGION));
        assertInvalid("Authorizer merge public key for key name k1 not a valid RSA key",
                () -> service.updateAuthorizer("merge", keysOnly(Map.of("k1", ecKey)), REGION));
        assertEquals(Map.of("k1", rsaKey2, "k2", rsaKey2), service.describeAuthorizer("merge", REGION).getTokenSigningPublicKeys());

        Instant before = service.describeAuthorizer("merge", REGION).getLastModifiedDate();
        Thread.sleep(5);
        IotAuthorizer emptied = service.updateAuthorizer("merge", keysOnly(Map.of()), REGION);
        assertEquals(Map.of("k1", rsaKey2, "k2", rsaKey2), emptied.getTokenSigningPublicKeys());
        assertTrue(emptied.getLastModifiedDate().isAfter(before));
    }

    @Test
    void deleteRefusesAnActiveOrDefaultAuthorizerAndFreesTheName() {
        service.createAuthorizer("del", unsigned().put("status", "ACTIVE"), REGION);
        assertInvalid("Cannot delete authorizer del in ACTIVE status. Update status to INACTIVE and delete.",
                () -> service.deleteAuthorizer("del", REGION));

        service.setDefaultAuthorizer("del", REGION);
        assertInvalid("Cannot delete authorizer del in ACTIVE status. Update status to INACTIVE and delete.",
                () -> service.deleteAuthorizer("del", REGION));

        service.updateAuthorizer("del", mapper.createObjectNode().put("status", "INACTIVE"), REGION);
        assertAwsError("DeleteConflictException", 409,
                "Cannot delete default authorizer del. Change default and retry delete.",
                () -> service.deleteAuthorizer("del", REGION));

        service.clearDefaultAuthorizer(REGION);
        service.deleteAuthorizer("del", REGION);
        assertAwsError("ResourceNotFoundException", 404, "Authorizer del not found",
                () -> service.describeAuthorizer("del", REGION));
        assertEquals("INACTIVE", service.createAuthorizer("del", unsigned(), REGION).getStatus());
    }

    @Test
    void defaultAuthorizerIsSetReplacedDescribedAndCleared() {
        String none = "Default authorizer not found";
        assertAwsError("ResourceNotFoundException", 404, none, () -> service.describeDefaultAuthorizer(REGION));
        assertAwsError("ResourceNotFoundException", 404, none, () -> service.clearDefaultAuthorizer(REGION));
        assertAwsError("ResourceNotFoundException", 404, "Authorizer d1 not found",
                () -> service.setDefaultAuthorizer("d1", REGION));
        service.createAuthorizer("d1", unsigned(), REGION);
        service.createAuthorizer("d2", unsigned(), REGION);

        assertEquals(ARN_PREFIX + "d1", service.setDefaultAuthorizer("d1", REGION).getAuthorizerArn());
        AwsException duplicate = assertAwsError("ResourceAlreadyExistsException", 409,
                "Duplicate default authorizer d1 already configured for this account",
                () -> service.setDefaultAuthorizer("d1", REGION));
        assertEquals(Map.of("resourceId", "d1", "resourceArn", ARN_PREFIX + "d1"), duplicate.getExtendedData());

        service.setDefaultAuthorizer("d2", REGION);
        assertEquals("d2", service.describeDefaultAuthorizer(REGION).getAuthorizerName());
        service.deleteAuthorizer("d1", REGION);

        service.clearDefaultAuthorizer(REGION);
        assertAwsError("ResourceNotFoundException", 404, none, () -> service.describeDefaultAuthorizer(REGION));
        assertAwsError("ResourceNotFoundException", 404, none, () -> service.describeDefaultAuthorizer("eu-west-1"));
    }

    @Test
    void setDefaultValidatesTheNameBeforeTheLookupAndKeepsTheDefault() {
        service.createAuthorizer("keep", unsigned(), REGION);
        service.setDefaultAuthorizer("keep", REGION);
        String constraint = "Value at 'authorizerName' failed to satisfy constraint: ";
        String pattern = constraint + "Member must satisfy regular expression pattern: [\\w=,@-]+";
        String tooLong = constraint + "Member must have length less than or equal to 128";

        assertInvalid("1 validation error detected: " + constraint + "Member must not be null",
                () -> service.setDefaultAuthorizer(null, REGION));
        assertInvalid("2 validation errors detected: " + pattern + "; " + constraint + "Member must have length greater than or equal to 1",
                () -> service.setDefaultAuthorizer("", REGION));
        assertInvalid("1 validation error detected: " + pattern, () -> service.setDefaultAuthorizer("bad name!", REGION));
        assertInvalid("1 validation error detected: " + tooLong, () -> service.setDefaultAuthorizer("a".repeat(129), REGION));
        assertInvalid("2 validation errors detected: " + pattern + "; " + tooLong,
                () -> service.setDefaultAuthorizer("!".repeat(129), REGION));
        assertAwsError("ResourceNotFoundException", 404, "Authorizer absent not found",
                () -> service.setDefaultAuthorizer("absent", REGION));

        assertEquals("keep", service.describeDefaultAuthorizer(REGION).getAuthorizerName());
    }

    @Test
    void listOrdersByCreationFiltersByStatusAndPages() throws InterruptedException {
        for (String name : List.of("l2", "l3", "l1")) {
            service.createAuthorizer(name, unsigned(), REGION);
            Thread.sleep(2);
        }
        service.updateAuthorizer("l3", mapper.createObjectNode().put("status", "ACTIVE"), REGION);
        service.createAuthorizer("elsewhere", unsigned(), "eu-west-1");

        assertEquals(List.of("l1", "l3", "l2"), names(service.listAuthorizers(REGION, null, false, null, null)));
        assertEquals(List.of("l2", "l3", "l1"), names(service.listAuthorizers(REGION, null, true, null, null)));
        assertEquals(List.of("l3"), names(service.listAuthorizers(REGION, "ACTIVE", false, null, null)));
        assertEquals(List.of("l1", "l2"), names(service.listAuthorizers(REGION, "INACTIVE", false, null, null)));

        IotService.Page<IotAuthorizer> first = service.listAuthorizers(REGION, null, false, null, 2);
        assertEquals(List.of("l1", "l3"), names(first));
        IotService.Page<IotAuthorizer> last = service.listAuthorizers(REGION, null, false, first.nextToken(), 2);
        assertEquals(List.of("l2"), names(last));
        assertNull(last.nextToken());
    }

    @Test
    void listRejectsAnInvalidPageSizeStatusOrMarker() {
        String prefix = "1 validation error detected: Value at ";
        assertInvalid(prefix + "'pageSize' failed to satisfy constraint: Member must have value greater than or equal to 1",
                () -> service.listAuthorizers(REGION, null, false, null, 0));
        assertInvalid(prefix + "'pageSize' failed to satisfy constraint: Member must have value less than or equal to 250",
                () -> service.listAuthorizers(REGION, null, false, null, 251));
        assertInvalid(prefix + "'status' failed to satisfy constraint: Member must satisfy enum value set: [ACTIVE, INACTIVE]",
                () -> service.listAuthorizers(REGION, "BOGUS", false, null, null));
        assertInvalid("Invalid/Malformed marker passed for listAuthorizers",
                () -> service.listAuthorizers(REGION, null, false, "garbage", null));
        assertTrue(service.listAuthorizers(REGION, null, false, null, 250).items().isEmpty());
    }

    @Test
    void tagsAreSetAtCreateChangedByArnAndRefusedOnAMissingAuthorizer() {
        ObjectNode request = unsigned();
        request.putArray("tags").addObject().put("Key", "env").put("Value", "dev");
        String arn = service.createAuthorizer("tags", request, REGION).getAuthorizerArn();
        assertEquals(Map.of("env", "dev"), service.listTagsForResource(arn));

        service.tagResource(arn, Map.of("env", "prod", "team", "a"));
        service.untagResource(arn, List.of("team", "absent"));
        assertEquals(Map.of("env", "prod"), service.listTagsForResource(arn));

        String missing = ARN_PREFIX + "gone";
        assertEquals(Map.of(), service.listTagsForResource(missing));
        assertAwsError("ResourceNotFoundException", 404, "Authorizer gone not found",
                () -> service.tagResource(missing, Map.of("k", "v")));
        assertAwsError("ResourceNotFoundException", 404, "Authorizer gone not found",
                () -> service.untagResource(missing, List.of("k")));
    }

    @Test
    void tagOperationsDoNotResolveASameNamedArnFromAnotherAccount() {
        ObjectNode request = unsigned();
        request.putArray("tags").addObject().put("Key", "env").put("Value", "dev");
        service.createAuthorizer("shared", request, REGION);
        String foreign = "arn:aws:iot:us-east-1:111122223333:authorizer/shared";

        assertEquals(Map.of(), service.listTagsForResource(foreign));
        assertAwsError("ResourceNotFoundException", 404, "Authorizer shared not found",
                () -> service.tagResource(foreign, Map.of("k", "v")));
        assertEquals(Map.of("env", "dev"), service.listTagsForResource(ARN_PREFIX + "shared"));
    }

    @Test
    void authorizerSurvivesTheJsonRoundTripPersistentStorageUses() throws Exception {
        ObjectNode request = signed(Map.of("k1", rsaKey1));
        request.putArray("tags").addObject().put("Key", "env").put("Value", "dev");
        service.createAuthorizer("persisted", request, REGION);
        IotAuthorizer created = service.setDefaultAuthorizer("persisted", REGION);

        // The same mapper setup PersistentStorage uses: defaults plus java.time.
        ObjectMapper persistence = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = persistence.writeValueAsString(created);
        IotAuthorizer reloaded = persistence.readValue(json, IotAuthorizer.class);

        assertEquals(json, persistence.writeValueAsString(reloaded));
        assertTrue(reloaded.isDefaultAuthorizer());
        assertEquals(created.getCreationDate(), reloaded.getCreationDate());
        assertEquals(created.getTokenSigningPublicKeys(), reloaded.getTokenSigningPublicKeys());
        assertEquals(created.getTags(), reloaded.getTags());
    }
}
