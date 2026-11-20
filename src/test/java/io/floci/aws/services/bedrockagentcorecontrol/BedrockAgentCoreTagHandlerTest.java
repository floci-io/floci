package io.floci.aws.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.TagDispatcher;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreTagFixtures.invalidTagMaps;
import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreTagFixtures.malformedTagBodies;
import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreTagFixtures.numberedTags;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BedrockAgentCoreTagHandlerTest {

    private static final String CUSTOM_REGION = "xx-custom-1";
    private static final String ACCOUNT = "000000000000";
    private static final List<String> RESOURCE_PREFIXES = List.of("agent/", "agentEndpoint/", "gateway/", "memory/",
            "harness/");

    private final BedrockAgentCoreControlService runtimeService = mock(BedrockAgentCoreControlService.class);
    private final BedrockAgentCoreGatewayService gatewayService = mock(BedrockAgentCoreGatewayService.class);
    private final BedrockAgentCoreMemoryService memoryService = mock(BedrockAgentCoreMemoryService.class);
    private final BedrockAgentCoreHarnessRegistry harnessRegistry = mock(BedrockAgentCoreHarnessRegistry.class);
    private final RegionResolver resolver = new RegionResolver(CUSTOM_REGION, ACCOUNT, "aws-cn");
    private final BedrockAgentCoreTagHandler handler = new BedrockAgentCoreTagHandler(runtimeService,
            gatewayService, memoryService, harnessRegistry, resolver);

    private static String endpointArn(String partition) {
        return "arn:" + partition + ":bedrock-agentcore:" + CUSTOM_REGION + ":" + ACCOUNT
                + ":runtime/test-AbCdEfGhIj/runtime-endpoint/prod";
    }

    @Test
    void customRegionOnChinaDeploymentResolvesItsOwnArn() {
        String arn = endpointArn("aws-cn");
        when(runtimeService.getEndpointTagsByArn(CUSTOM_REGION, arn)).thenReturn(Map.of("env", "prod"));

        assertEquals(Map.of("env", "prod"), handler.listTags(CUSTOM_REGION, arn));
    }

    @Test
    void customRegionRejectsAnArnOfAnotherPartition() {
        AwsException e = assertThrows(AwsException.class,
                () -> handler.listTags(CUSTOM_REGION, endpointArn("aws")));

        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    private static String resourceArn(String prefix) {
        return "arn:aws-cn:bedrock-agentcore:" + CUSTOM_REGION + ":" + ACCOUNT + ":" + prefix + "abc123";
    }

    static Stream<Arguments> invalidTagRequests() {
        List<Map<String, String>> invalid = new ArrayList<>(invalidTagMaps());
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("key", null);
        invalid.add(nullValue);
        return RESOURCE_PREFIXES.stream().flatMap(prefix -> invalid.stream().map(tags -> Arguments.of(prefix, tags)));
    }

    @ParameterizedTest
    @MethodSource("invalidTagRequests")
    void rejectsInvalidTagsBeforeAnyBackingServiceMutation(String prefix, Map<String, String> tags) {
        AwsException failure = assertThrows(AwsException.class,
                () -> handler.tagResource(CUSTOM_REGION, resourceArn(prefix), tags));

        assertEquals("ValidationException", failure.getErrorCode());
        verifyNoInteractions(runtimeService, gatewayService, memoryService, harnessRegistry);
    }

    static Stream<Arguments> malformedTagRequests() {
        List<String> bodies = malformedTagBodies();
        return RESOURCE_PREFIXES.stream().flatMap(prefix -> bodies.stream().map(body -> Arguments.of(prefix, body)));
    }

    @ParameterizedTest
    @MethodSource("malformedTagRequests")
    void rejectsMalformedTagPayloadsBeforeAnyBackingServiceMutation(String prefix, String body) {
        TagDispatcher dispatcher = new TagDispatcher(List.of(handler), resolver, new ObjectMapper());
        AwsException failure = assertThrows(AwsException.class,
                () -> dispatcher.tagResourcePost(mock(HttpHeaders.class), resourceArn(prefix), body,
                        Response.noContent().build()));

        assertEquals("ValidationException", failure.getErrorCode());
        verifyNoInteractions(runtimeService, gatewayService, memoryService, harnessRegistry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent/", "agentEndpoint/", "gateway/", "memory/", "harness/"})
    void acceptsTheRequestMapBoundariesAndAnEmptyMap(String prefix) {
        Map<String, String> tags = numberedTags(48);
        tags.put("k".repeat(128), "v".repeat(256));
        tags.put("empty", "");
        String arn = resourceArn(prefix);

        handler.tagResource(CUSTOM_REGION, arn, tags);
        handler.tagResource(CUSTOM_REGION, arn, Map.of());

        for (Map<String, String> request : List.of(tags, Map.<String, String>of())) {
            switch (prefix) {
                case "agent/" -> verify(runtimeService).tagByArn(CUSTOM_REGION, arn, request);
                case "agentEndpoint/" -> verify(runtimeService).tagEndpointByArn(CUSTOM_REGION, arn, request);
                case "gateway/" -> verify(gatewayService).tagByArn(CUSTOM_REGION, arn, request);
                case "memory/" -> verify(memoryService).tagByArn(CUSTOM_REGION, arn, request);
                case "harness/" -> verify(harnessRegistry).tagByArn(CUSTOM_REGION, arn, request);
                default -> throw new IllegalArgumentException(prefix);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent/", "agentEndpoint/", "gateway/", "memory/", "harness/"})
    void keepsEmptyUntagRequestsUnchanged(String prefix) {
        TagDispatcher dispatcher = new TagDispatcher(List.of(handler), resolver, new ObjectMapper());
        UriInfo uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        String arn = resourceArn(prefix);

        dispatcher.untagResourceForArn(mock(HttpHeaders.class), uri, arn, Response.noContent().build());

        switch (prefix) {
            case "agent/" -> verify(runtimeService).untagByArn(CUSTOM_REGION, arn, List.of());
            case "agentEndpoint/" -> verify(runtimeService).untagEndpointByArn(CUSTOM_REGION, arn, List.of());
            case "gateway/" -> verify(gatewayService).untagByArn(CUSTOM_REGION, arn, List.of());
            case "memory/" -> verify(memoryService).untagByArn(CUSTOM_REGION, arn, List.of());
            case "harness/" -> verify(harnessRegistry).untagByArn(CUSTOM_REGION, arn, List.of());
            default -> throw new IllegalArgumentException(prefix);
        }
    }
}
