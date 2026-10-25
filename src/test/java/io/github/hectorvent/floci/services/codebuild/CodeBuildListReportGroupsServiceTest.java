package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.services.codebuild.model.ReportGroup;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CodeBuildListReportGroupsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeBuildService service = new CodeBuildService(
            mock(CodeBuildRunner.class), mock(EmulatorConfig.class), null);
    private final CodeBuildJsonHandler handler = new CodeBuildJsonHandler(service, mapper);

    @Test
    void returnsRequestedPageAndContinuationToken() throws Exception {
        ReportGroup first = createGroup("alpha");
        ReportGroup second = createGroup("beta");

        JsonNode page = list(Map.of("maxResults", 1));
        assertEquals(List.of(first.getArn()), arns(page));
        assertTrue(page.path("nextToken").isTextual());

        JsonNode last = list(Map.of("maxResults", 1, "nextToken", page.path("nextToken").asText()));
        assertEquals(List.of(second.getArn()), arns(last));
        assertFalse(last.has("nextToken"));
    }

    @Test
    void defaultsToOneHundredAndAllowsChangingPageSize() throws Exception {
        List<String> expected = IntStream.range(0, 103)
                .mapToObj(index -> createGroup("group-%03d".formatted(index)).getArn()).toList();
        JsonNode first = list(Map.of());
        assertEquals(expected.subList(0, 100), arns(first));
        JsonNode second = list(Map.of("maxResults", 2, "nextToken", first.path("nextToken").asText()));
        assertEquals(expected.subList(100, 102), arns(second));
        JsonNode last = list(Map.of("maxResults", 100, "nextToken", second.path("nextToken").asText()));
        assertEquals(expected.subList(102, 103), arns(last));
        assertFalse(last.has("nextToken"));
    }

    @ParameterizedTest
    @CsvSource({
            "NAME, ASCENDING, alpha:mu:zeta",
            "NAME, DESCENDING, zeta:mu:alpha",
            "CREATED_TIME, ASCENDING, zeta:alpha:mu",
            "CREATED_TIME, DESCENDING, mu:alpha:zeta",
            "LAST_MODIFIED_TIME, ASCENDING, mu:zeta:alpha",
            "LAST_MODIFIED_TIME, DESCENDING, alpha:zeta:mu"
    })
    void continuesInRequestedSortOrder(String sortBy, String sortOrder, String expected) throws Exception {
        ReportGroup zeta = createGroup("zeta");
        zeta.setCreated(1.0);
        zeta.setLastModified(2.0);
        ReportGroup alpha = createGroup("alpha");
        alpha.setCreated(2.0);
        alpha.setLastModified(3.0);
        ReportGroup mu = createGroup("mu");
        mu.setCreated(3.0);
        mu.setLastModified(1.0);
        service.createReportGroup("eu-west-1", ACCOUNT, "elsewhere", "TEST", Map.of(), null);

        JsonNode first = list(Map.of("maxResults", 2, "sortBy", sortBy, "sortOrder", sortOrder));
        JsonNode last = list(Map.of("maxResults", 2, "sortBy", sortBy, "sortOrder", sortOrder,
                "nextToken", first.path("nextToken").asText()));
        List<String> seen = new ArrayList<>(arns(first));
        seen.addAll(arns(last));
        assertEquals(List.of(expected.split(":")), seen.stream()
                .map(arn -> arn.substring(arn.lastIndexOf('/') + 1)).toList());
        assertFalse(last.has("nextToken"));
    }

    @Test
    void continuesPastDeletedAnchorAndTiedTimestamps() throws Exception {
        ReportGroup alpha = createGroup("alpha");
        ReportGroup beta = createGroup("beta");
        ReportGroup gamma = createGroup("gamma");
        for (ReportGroup group : List.of(alpha, beta, gamma)) {
            group.setCreated(1.0);
        }
        JsonNode first = list(Map.of("maxResults", 1, "sortBy", "CREATED_TIME"));
        assertEquals(List.of(alpha.getArn()), arns(first));
        service.deleteReportGroup(REGION, alpha.getArn());
        JsonNode last = list(Map.of("maxResults", 2, "sortBy", "CREATED_TIME",
                "nextToken", first.path("nextToken").asText()));
        assertEquals(List.of(beta.getArn(), gamma.getArn()), arns(last));
        assertFalse(last.has("nextToken"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsCursorSortValueWhenReportGroupChangesDuringPageConstruction() throws Exception {
        ReportGroup alpha = createGroup("alpha");
        ReportGroup beta = createGroup("beta");
        beta.setLastModified(2.0);
        ReportGroup changing = new ReportGroup() {
            private boolean changed;

            @Override
            public String getArn() {
                if (!changed) {
                    setLastModified(99.0);
                    changed = true;
                }
                return super.getArn();
            }
        };
        changing.setArn(alpha.getArn());
        changing.setName(alpha.getName());
        changing.setLastModified(1.0);
        Field field = CodeBuildService.class.getDeclaredField("reportGroups");
        field.setAccessible(true);
        Map<String, Map<String, ReportGroup>> regions =
                (Map<String, Map<String, ReportGroup>>) field.get(service);
        regions.get(REGION).put(alpha.getArn(), changing);

        JsonNode first = list(Map.of("maxResults", 1, "sortBy", "LAST_MODIFIED_TIME"));
        assertEquals(List.of(alpha.getArn()), arns(first));
        JsonNode second = list(Map.of("maxResults", 1, "sortBy", "LAST_MODIFIED_TIME",
                "nextToken", first.path("nextToken").asText()));
        assertEquals(List.of(beta.getArn()), arns(second));
    }

    @Test
    void emptyRegionHasNoContinuationToken() throws Exception {
        JsonNode response = list(Map.of());
        assertEquals(List.of(), arns(response));
        assertFalse(response.has("nextToken"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "1.5", "2147483648", "true", "\"1\"", "null"})
    void rejectsInvalidPageSizes(String value) {
        AwsException error = assertThrows(AwsException.class, () -> handler.handle("ListReportGroups",
                mapper.readTree("{\"maxResults\":" + value + "}"), REGION, ACCOUNT));
        assertEquals("InvalidInputException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @ParameterizedTest
    @CsvSource({"sortBy, BAD", "sortOrder, BAD"})
    void rejectsUnknownSortOptionsOnEmptyRegion(String key, String value) {
        assertThrows(AwsException.class, () -> list(Map.of(key, value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-token!", "e30", "bnVsbA", "MQ"})
    void rejectsMalformedTokens(String token) {
        AwsException error = assertThrows(AwsException.class, () -> list(Map.of("nextToken", token)));
        assertEquals("InvalidInputException", error.getErrorCode());
    }

    @Test
    void rejectsTokensForOtherRegionsOperationsAndSorts() throws Exception {
        createGroup("alpha");
        createGroup("beta");
        String token = list(Map.of("maxResults", 1)).path("nextToken").asText();
        assertThrows(AwsException.class, () -> handler.handle("ListReportGroups",
                mapper.valueToTree(Map.of("nextToken", token)), "eu-west-1", ACCOUNT));
        assertThrows(AwsException.class, () -> handler.handle("ListProjects",
                mapper.valueToTree(Map.of("nextToken", token)), REGION, ACCOUNT));
        assertThrows(AwsException.class, () -> list(Map.of("nextToken", token, "sortBy", "CREATED_TIME")));
        assertThrows(AwsException.class, () -> list(Map.of("nextToken", token, "sortOrder", "DESCENDING")));
        assertThrows(AwsException.class, () -> list(Map.of("nextToken", 42)));
        assertThrows(AwsException.class, () -> list(Map.of("nextToken", Base64Url.encode("[]"))));
    }

    private ReportGroup createGroup(String name) {
        return service.createReportGroup(REGION, ACCOUNT, name, "TEST",
                Map.of("exportConfigType", "NO_EXPORT"), null);
    }

    private JsonNode list(Map<String, ?> request) throws Exception {
        try (Response response = handler.handle("ListReportGroups", mapper.valueToTree(request), REGION, ACCOUNT)) {
            return mapper.valueToTree(response.getEntity());
        }
    }

    private List<String> arns(JsonNode response) {
        return mapper.convertValue(response.path("reportGroups"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
