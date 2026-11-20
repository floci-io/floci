package io.floci.aws.services.glue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.glue.model.JobRunBookkeeping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRunBookkeepingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aScriptRunSurvivesStorage() throws Exception {
        JobRunBookkeeping bookkeeping = new JobRunBookkeeping();
        bookkeeping.setScriptRun(true);

        JobRunBookkeeping read = mapper.readValue(mapper.writeValueAsString(bookkeeping), JobRunBookkeeping.class);

        assertTrue(read.isScriptRun());
    }

    @Test
    void bookkeepingStoredBeforeScriptRunsExistReadsAsNoScriptRun() throws Exception {
        String stored = "{\"completionOrder\":4,\"originRunId\":\"jr_1\",\"workflowRunId\":null}";

        JobRunBookkeeping read = mapper.readValue(stored, JobRunBookkeeping.class);

        assertFalse(read.isScriptRun());
    }
}
