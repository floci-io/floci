package io.floci.aws.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ses.model.ExportJob;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The response shape of a job that has no record count yet, which the integration test cannot
 * reach: a Floci export finishes in milliseconds, so every job it reads back is already counted.
 */
class SesExportJobControllerTest {

    private static final String REGION = "eu-north-1";
    private static final String JOB_ID = "7b9563a7-12fc-4129-ab8c-bd4783006c08";

    private final SesExportJobService exportJobService = mock(SesExportJobService.class);
    private final RegionResolver regionResolver = mock(RegionResolver.class);
    private final SesExportJobController controller = new SesExportJobController(
            exportJobService, mock(SesAccountService.class), regionResolver, new ObjectMapper());

    @Test
    void aCancelledJobCarriesAnEmptyStatisticsAndNoCompletedTimestamp() {
        ExportJob job = new ExportJob();
        job.setJobId(JOB_ID);
        job.setExportSourceType(ExportJob.SOURCE_METRICS);
        job.setJobStatus(ExportJob.STATUS_CANCELLED);
        job.setDataFormat(ExportJob.FORMAT_CSV);
        job.setDataSource("{}");
        job.setCreatedTimestamp(Instant.parse("2026-11-14T00:00:00Z"));
        when(regionResolver.resolveRegion(any())).thenReturn(REGION);
        when(exportJobService.getExportJob(REGION, JOB_ID)).thenReturn(job);

        Response response = controller.getExportJob(null, JOB_ID);

        ObjectNode body = (ObjectNode) response.getEntity();
        // Probed 2026-11-14: SES answers with the structure present and both counts null.
        assertTrue(body.has("Statistics"), body.toString());
        assertEquals(0, body.get("Statistics").size(), body.toString());
        assertFalse(body.has("CompletedTimestamp"), body.toString());
        assertFalse(body.get("ExportDestination").has("S3Url"), body.toString());
    }
}
