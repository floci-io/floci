package io.floci.aws.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventBridgeTargetLookupHandlerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @MethodSource("wrongTypes")
    void wrongJsonTypesAreNotCoerced(String json) throws Exception {
        EventBridgeService service = mock(EventBridgeService.class);
        try (Response response = new EventBridgeHandler(service, MAPPER)
                .handle("ListRuleNamesByTarget", MAPPER.readTree(json), REGION)) {
            assertEquals(400, response.getStatus());
            assertEquals("SerializationException", ((AwsErrorResponse) response.getEntity()).type());
            verifyNoInteractions(service);
        }
    }

    static Stream<String> wrongTypes() {
        return Stream.of(
                "{\"TargetArn\":1}", "{\"TargetArn\":true}", "{\"TargetArn\":[]}", "{\"TargetArn\":{}}",
                "{\"TargetArn\":\"filter\",\"EventBusName\":12}",
                "{\"TargetArn\":\"filter\",\"EventBusName\":{}}",
                "{\"TargetArn\":\"filter\",\"NextToken\":false}",
                "{\"TargetArn\":\"filter\",\"NextToken\":[]}",
                "{\"TargetArn\":\"filter\",\"Limit\":\"1\"}",
                "{\"TargetArn\":\"filter\",\"Limit\":1.5}",
                "{\"TargetArn\":\"filter\",\"Limit\":true}",
                "{\"TargetArn\":\"filter\",\"Limit\":{}}");
    }

    @ParameterizedTest
    @MethodSource("outOfBounds")
    void modeledBoundsReachValidationInsteadOfTruncation(String json) throws Exception {
        EventBridgeService service = service();
        try (Response response = new EventBridgeHandler(service, MAPPER)
                .handle("ListRuleNamesByTarget", MAPPER.readTree(json), REGION)) {
            assertEquals(400, response.getStatus());
            assertEquals("ValidationException", ((AwsErrorResponse) response.getEntity()).type());
        }
    }

    static Stream<String> outOfBounds() {
        return Stream.of("{}", "{\"TargetArn\":null}", "{\"TargetArn\":\"\"}",
                "{\"TargetArn\":\"" + "x".repeat(1601) + "\"}",
                "{\"TargetArn\":\"filter\",\"EventBusName\":\"\"}",
                "{\"TargetArn\":\"filter\",\"EventBusName\":\"bad name\"}",
                "{\"TargetArn\":\"filter\",\"EventBusName\":\"" + "x".repeat(1601) + "\"}",
                "{\"TargetArn\":\"filter\",\"NextToken\":\"\"}",
                "{\"TargetArn\":\"filter\",\"NextToken\":\"" + "x".repeat(2049) + "\"}",
                "{\"TargetArn\":\"filter\",\"Limit\":0}",
                "{\"TargetArn\":\"filter\",\"Limit\":101}",
                "{\"TargetArn\":\"filter\",\"Limit\":2147483648}",
                "{\"TargetArn\":\"filter\",\"Limit\":-2147483649}");
    }

    @Test
    void dispatchPreservesStringsAndWritesOnlyModeledResponseMembers() throws Exception {
        EventBridgeService service = mock(EventBridgeService.class);
        when(service.listRuleNamesByTarget("filter", "default", 1, "token", REGION))
                .thenReturn(new PaginatedResult<>(List.of("owned"), "next"));
        try (Response response = new EventBridgeHandler(service, MAPPER).handle("ListRuleNamesByTarget",
                MAPPER.readTree("{\"TargetArn\":\"filter\",\"EventBusName\":\"default\",\"Limit\":1,\"NextToken\":\"token\"}"),
                REGION)) {
            assertEquals(200, response.getStatus());
            assertEquals(MAPPER.readTree("{\"RuleNames\":[\"owned\"],\"NextToken\":\"next\"}"), response.getEntity());
            verify(service).listRuleNamesByTarget("filter", "default", 1, "token", REGION);
        }
    }

    @Test
    void omittedAndNullOptionalArgumentsYieldAnEmptyTerminalPage() throws Exception {
        EventBridgeHandler handler = new EventBridgeHandler(service(), MAPPER);
        for (String body : List.of("{\"TargetArn\":\"filter\"}",
                "{\"TargetArn\":\"filter\",\"EventBusName\":null,\"Limit\":null,\"NextToken\":null}")) {
            try (Response response = handler.handle("ListRuleNamesByTarget", MAPPER.readTree(body), REGION)) {
                assertEquals(200, response.getStatus());
                assertEquals(MAPPER.readTree("{\"RuleNames\":[]}"), (JsonNode) response.getEntity());
            }
        }
    }

    private static EventBridgeService service() {
        return new EventBridgeService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"), MAPPER,
                null, null, null, null, null);
    }
}
