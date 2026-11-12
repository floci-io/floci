package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudWatchLogsQueryDefinitionHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CloudWatchLogsQueryDefinitionService service = mock(CloudWatchLogsQueryDefinitionService.class);
    private final CloudWatchLogsQueryDefinitionHandler handler = new CloudWatchLogsQueryDefinitionHandler(service, mapper);

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void modeledConstraintsReturnInvalidParameterBeforeCallingTheService(String action, String body,
                                                                         String member, String constraint) throws Exception {
        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle(action, mapper.readTree(body), "us-east-1"));
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint, error.getMessage());
        verifyNoInteractions(service);
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("PutQueryDefinition", "[]", "PutQueryDefinition", "Member must be a structure"),
                Arguments.of("PutQueryDefinition", "null", "PutQueryDefinition", "Member must be a structure"),
                Arguments.of("PutQueryDefinition", "{}", "name", "Member must not be null"),
                Arguments.of("PutQueryDefinition", "{\"name\":\"q\"}", "queryString", "Member must not be null"),
                put("name", "", "Member must have length greater than or equal to 1"),
                put("name", "x".repeat(256), "Member must have length less than or equal to 255"),
                put("queryString", "", "Member must have length greater than or equal to 1"),
                put("queryString", "x".repeat(10001), "Member must have length less than or equal to 10000"),
                put("queryDefinitionId", "", "Member must have length greater than or equal to 1"),
                put("queryDefinitionId", "x".repeat(257), "Member must have length less than or equal to 256"),
                put("clientToken", "short", "Member must have length greater than or equal to 36"),
                put("clientToken", "x".repeat(129), "Member must have length less than or equal to 128"),
                put("clientToken", " ".repeat(36), "Member must satisfy regular expression pattern: \\S{36,128}"),
                put("queryLanguage", "invalid", "Member must satisfy enum value set: [CWLI, SQL, PPL]"),
                Arguments.of("PutQueryDefinition", "{\"name\":\"q\",\"queryString\":\"q\",\"logGroupNames\":[\"bad:*\"]}",
                        "logGroupNames", "Member must satisfy constraint: [Member must have length less than or equal to 512, "
                                + "Member must have length greater than or equal to 1, "
                                + "Member must satisfy regular expression pattern: [\\.\\-_/#A-Za-z0-9]+]"),
                Arguments.of("PutQueryDefinition", "{\"name\":\"q\",\"queryString\":\"q\",\"parameters\":[7]}",
                        "parameters", "Member must be a structure"),
                Arguments.of("PutQueryDefinition", "{\"name\":\"q\",\"queryString\":\"q\",\"parameters\":[{}]}",
                        "parameters[].name", "Member must not be null"),
                Arguments.of("PutQueryDefinition", "{\"name\":\"q\",\"queryString\":\"q\",\"parameters\":["
                        + "{\"name\":\"p\"},".repeat(20) + "{\"name\":\"p\"}]}",
                        "parameters", "Member must have length less than or equal to 20"),
                parameter("name", "", "Member must have length greater than or equal to 1"),
                parameter("name", "x".repeat(129), "Member must have length less than or equal to 128"),
                parameter("name", "1bad", "Member must satisfy regular expression pattern: ^[a-zA-Z_][a-zA-Z0-9_]*"),
                parameter("defaultValue", "x".repeat(1025), "Member must have length less than or equal to 1024"),
                parameter("description", "x".repeat(513), "Member must have length less than or equal to 512"),
                Arguments.of("DescribeQueryDefinitions", "{\"queryDefinitionNamePrefix\":\"\"}",
                        "queryDefinitionNamePrefix", "Member must have length greater than or equal to 1"),
                Arguments.of("DescribeQueryDefinitions", "{\"queryDefinitionNamePrefix\":\"" + "x".repeat(256) + "\"}",
                        "queryDefinitionNamePrefix", "Member must have length less than or equal to 255"),
                Arguments.of("DescribeQueryDefinitions", "{\"queryLanguage\":\"invalid\"}",
                        "queryLanguage", "Member must satisfy enum value set: [CWLI, SQL, PPL]"),
                Arguments.of("DescribeQueryDefinitions", "{\"maxResults\":0}",
                        "maxResults", "Member must have value greater than or equal to 1"),
                Arguments.of("DescribeQueryDefinitions", "{\"maxResults\":1001}",
                        "maxResults", "Member must have value less than or equal to 1000"),
                Arguments.of("DescribeQueryDefinitions", "{\"maxResults\":1.5}",
                        "maxResults", "Member must be a integer"),
                Arguments.of("DescribeQueryDefinitions", "{\"nextToken\":\"\"}",
                        "nextToken", "Member must have length greater than or equal to 1"),
                Arguments.of("DeleteQueryDefinition", "{}", "queryDefinitionId", "Member must not be null"),
                Arguments.of("DeleteQueryDefinition", "{\"queryDefinitionId\":\"\"}",
                        "queryDefinitionId", "Member must have length greater than or equal to 1"),
                Arguments.of("DeleteQueryDefinition", "{\"queryDefinitionId\":\"" + "x".repeat(257) + "\"}",
                        "queryDefinitionId", "Member must have length less than or equal to 256"));
    }

    private static Arguments put(String member, String value, String constraint) {
        return Arguments.of("PutQueryDefinition", new ObjectMapper().createObjectNode()
                .put("name", "q").put("queryString", "q").put(member, value).toString(), member, constraint);
    }

    private static Arguments parameter(String member, String value, String constraint) {
        ObjectMapper mapper = new ObjectMapper();
        return Arguments.of("PutQueryDefinition", mapper.createObjectNode().put("name", "q").put("queryString", "q")
                .set("parameters", mapper.createArrayNode().add(mapper.createObjectNode().put("name", "p")
                        .put(member, value))).toString(), "parameters[]." + member, constraint);
    }
}
