package io.floci.aws.services.ssm.session;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ssm.SsmService;
import io.floci.aws.services.ssm.model.SsmDocument;
import io.floci.aws.services.ssm.session.SessionDocumentResolver.ResolvedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionDocumentResolverServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "123456789012";
    private static final String DEFAULT = SessionDocumentResolver.DEFAULT_DOCUMENT_NAME;
    private static final String CUSTOM = "CustomSession";
    private static final int SESSION_DOCUMENT_BYTE_LIMIT = 65_536;

    private static final String CUSTOM_SHELL = """
            schemaVersion: '1.0'
            sessionType: Standard_Stream
            parameters:
              requiredSample:
                type: String
                allowedPattern: '^[a-z]+$'
              sample:
                type: String
                default: example
              labels:
                type: StringList
                allowedPattern: '^[a-z]+$'
                default: [first, second]
            inputs:
              shellProfile:
                linux: 'echo {{ requiredSample }}'
            """;

    private final SsmService ssm = mock(SsmService.class);
    private final RegionResolver regionResolver = mock(RegionResolver.class);
    private final SessionDocumentResolver resolver = new SessionDocumentResolver(ssm, regionResolver);

    @BeforeEach
    void setAccount() {
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
    }

    @Test
    void absentPreferencesFallBackToImplicitShell() {
        ResolvedDocument document = resolver.resolve(REGION, null, Map.of());
        assertEquals(DEFAULT, document.name());
        assertEquals("Standard_Stream", document.content().path("sessionType").asText());
        assertTrue(document.parameters().isEmpty());
    }

    @Test
    void explicitDefaultMustExistInAccount() {
        AwsException error = assertThrows(AwsException.class, () -> resolver.resolve(REGION, DEFAULT, Map.of()));
        assertEquals("InvalidDocument", error.getErrorCode());
    }

    @Test
    void implicitDefaultReadsStoredAccountPreferences() {
        stored(DEFAULT, "Session", CUSTOM_SHELL);
        ResolvedDocument document = resolver.resolve(REGION, null, Map.of("requiredSample", List.of("given")));
        assertEquals("echo {{ requiredSample }}", document.content().at("/inputs/shellProfile/linux").asText());
        assertEquals("arn:aws:ssm:" + REGION + ":" + ACCOUNT + ":document/" + DEFAULT, document.arn());
    }

    @ParameterizedTest
    @CsvSource({"123456789012, CustomSession", "'', AWS-StartSSHSession"})
    void resolvesExplicitCanonicalDocumentArn(String account, String name) {
        stored(name, "Session", "{\"schemaVersion\":\"1.0\",\"sessionType\":\"Port\"}");
        String arn = "arn:aws:ssm:" + REGION + ":" + account + ":document/" + name;
        ResolvedDocument document = resolver.resolve(REGION, arn, Map.of());
        assertEquals(name, document.name());
        assertEquals(arn, document.arn());
    }

    @Test
    void otherAccountArnDoesNotResolveByLocalName() {
        stored(CUSTOM, "Session", CUSTOM_SHELL);
        AwsException error = assertThrows(AwsException.class, () -> resolver.resolve(REGION,
                "arn:aws:ssm:us-east-1:999999999999:document/" + CUSTOM, Map.of()));
        assertEquals("InvalidDocument", error.getErrorCode());
    }

    @Test
    void resolvesDefaultsAndConvertsScalarParametersForAgentDispatch() {
        stored(CUSTOM, "Session", CUSTOM_SHELL);
        ResolvedDocument document = resolver.resolve(REGION, CUSTOM, Map.of("requiredSample", List.of("given")));
        assertEquals(Map.of("requiredSample", "given", "sample", "example", "labels", List.of("first", "second")),
                document.parameters());
    }

    @Test
    void emptyListOverridesDocumentDefault() {
        stored(CUSTOM, "Session", CUSTOM_SHELL);
        ResolvedDocument document = resolver.resolve(REGION, CUSTOM,
                Map.of("requiredSample", List.of("given"), "labels", List.of()));
        assertEquals(List.of(), document.parameters().get("labels"));
    }

    static Stream<Map<String, List<String>>> invalidParameters() {
        return Stream.of(Map.of(), Map.of("RequiredSample", List.of("given")),
                Map.of("requiredSample", List.of()), Map.of("requiredSample", List.of("first", "second")),
                Map.of("requiredSample", List.of("UPPER")),
                Map.of("requiredSample", List.of("given"), "labels", List.of("valid", "BAD")));
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void rejectsInvalidParameters(Map<String, List<String>> parameters) {
        stored(CUSTOM, "Session", CUSTOM_SHELL);
        AwsException error = assertThrows(AwsException.class, () -> resolver.resolve(REGION, CUSTOM, parameters));
        assertEquals("InvalidParameters", error.getErrorCode());
    }

    @Test
    void rejectsNonSessionDocument() {
        stored(CUSTOM, "Command", "{\"schemaVersion\":\"2.2\",\"mainSteps\":[]}");
        AwsException error = assertThrows(AwsException.class, () -> resolver.resolve(REGION, CUSTOM, Map.of()));
        assertEquals("InvalidDocument", error.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "café glacé"})
    void acceptsStoredSessionAtUtf8ByteLimit(String description) {
        String source = sessionDocumentOfSize(description, SESSION_DOCUMENT_BYTE_LIMIT);
        stored(CUSTOM, "Session", source);

        ResolvedDocument document = resolver.resolve(REGION, CUSTOM, Map.of());
        assertEquals(description, document.content().path("description").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "café glacé"})
    void rejectsStoredSessionOneUtf8ByteOverLimit(String description) {
        String source = sessionDocumentOfSize(description, SESSION_DOCUMENT_BYTE_LIMIT + 1);
        stored(CUSTOM, "Session", source);

        AwsException error = assertThrows(AwsException.class, () -> resolver.resolve(REGION, CUSTOM, Map.of()));
        assertEquals("InvalidDocument", error.getErrorCode());
    }

    @Test
    void rejectsUnsupportedSessionType() {
        stored(CUSTOM, "Session", "{\"schemaVersion\":\"1.0\",\"sessionType\":\"InteractiveCommands\"}");
        AwsException error = assertThrows(AwsException.class, () -> resolver.resolve(REGION, CUSTOM, Map.of()));
        assertEquals("InvalidDocument", error.getErrorCode());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "allowedValues: [first] | outside its allowedValues",
            "minItems: 3 | violates minItems",
            "maxItems: 1 | violates maxItems",
            "minChars: 8 | violates minChars",
            "maxChars: 4 | violates maxChars"
    })
    void validatesDeclaredListConstraints(String declaration, String description) {
        stored(CUSTOM, "Session", """
                schemaVersion: '1.0'
                sessionType: Port
                parameters:
                  labels:
                    type: StringList
                    %s
                """.formatted(declaration));
        AwsException error = assertThrows(AwsException.class,
                () -> resolver.resolve(REGION, CUSTOM, Map.of("labels", List.of("first", "second"))));
        assertEquals("InvalidParameters", error.getErrorCode());
        assertTrue(error.getMessage().contains(description));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"shell\"", "42", "true", "null"})
    void rejectsPresentNonObjectInputs(String inputs) {
        stored(CUSTOM, "Session", """
                {"schemaVersion":"1.0","sessionType":"Standard_Stream","inputs":%s}
                """.formatted(inputs));
        AwsException error = assertThrows(AwsException.class, () -> resolver.resolve(REGION, CUSTOM, Map.of()));
        assertEquals("InvalidDocument", error.getErrorCode());
    }

    @Test
    void acceptsEmptyInputsObject() {
        stored(CUSTOM, "Session", """
                {"schemaVersion":"1.0","sessionType":"Standard_Stream","inputs":{}}
                """);
        ResolvedDocument document = resolver.resolve(REGION, CUSTOM, Map.of());
        assertTrue(document.content().path("inputs").isObject());
        assertTrue(document.content().path("inputs").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"kmsKeyId", "s3BucketName", "cloudWatchLogGroupName"})
    void rejectsDocumentsRequiringUnsupportedEncryptionOrLogging(String setting) {
        stored(CUSTOM, "Session", """
                {"schemaVersion":"1.0","sessionType":"Standard_Stream","inputs":{"%s":"example"}}
                """.formatted(setting));
        AwsException error = assertThrows(AwsException.class, () -> resolver.resolve(REGION, CUSTOM, Map.of()));
        assertEquals("InvalidDocument", error.getErrorCode());
    }

    private void stored(String name, String type, String source) {
        when(ssm.findDocument(name, REGION)).thenReturn(Optional.of(new SsmDocument(name, source, type)));
    }

    private static String sessionDocumentOfSize(String description, int bytes) {
        String document = """
                {"schemaVersion":"1.0","sessionType":"Standard_Stream","description":"%s"}
                """.formatted(description);
        String source = document + " ".repeat(bytes - document.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(bytes, source.getBytes(StandardCharsets.UTF_8).length);
        return source;
    }
}
