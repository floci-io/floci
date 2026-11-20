package io.floci.aws.services.athena;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A request's list members reach the service as the strings they hold, in order, and as none when absent. */
class AthenaJsonHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AthenaService athenaService;
    private AthenaJsonHandler handler;

    @BeforeEach
    void setUp() {
        athenaService = mock(AthenaService.class);
        when(athenaService.startQueryExecution(anyString(), anyString(), any(), any(), anyList())).thenReturn("query-id");
        handler = new AthenaJsonHandler(athenaService, mock(AthenaNamedQueryService.class), mapper);
    }

    @Test
    void aQueryIsStartedWithItsExecutionParametersInOrder() throws Exception {
        handler.handle("StartQueryExecution", mapper.readTree(
                "{\"QueryString\": \"SELECT ? AS region, ? AS n\", \"ExecutionParameters\": [\"'eu'\", \"10\"]}"),
                "us-east-1");

        verify(athenaService).startQueryExecution(eq("SELECT ? AS region, ? AS n"), eq("primary"), isNull(),
                isNull(), eq(List.of("'eu'", "10")));
    }

    @Test
    void aQueryWithoutExecutionParametersIsStartedWithNone() throws Exception {
        handler.handle("StartQueryExecution", mapper.readTree("{\"QueryString\": \"SELECT 1\"}"), "us-east-1");

        verify(athenaService).startQueryExecution(eq("SELECT 1"), eq("primary"), isNull(), isNull(), eq(List.of()));
    }

    @Test
    void aResourceIsUntaggedOfTheKeysTheRequestNames() throws Exception {
        String arn = "arn:aws:athena:us-east-1:000000000000:workgroup/analytics";
        handler.handle("UntagResource", mapper.readTree(
                "{\"ResourceARN\": \"" + arn + "\", \"TagKeys\": [\"Owner\", \"Environment\"]}"), "us-east-1");

        verify(athenaService).untagResource(arn, List.of("Owner", "Environment"));
    }
}
