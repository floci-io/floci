package io.floci.aws.services.rds;

import io.floci.aws.services.docdb.DocDbService;
import io.floci.aws.services.rds.export.RdsExportTaskService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RdsStoredResourceArnsTest {
    @Test
    void anUntaggedOwnerDoesNotBorrowTagsFromALegacyArnCollision() {
        String arn = "arn:aws:rds:us-east-1:000000000000:cluster:shared";
        RdsService rds = mock(RdsService.class);
        DocDbService docdb = mock(DocDbService.class);
        RdsStoredResourceArns lookup = new RdsStoredResourceArns(rds, docdb, mock(RdsExportTaskService.class));
        when(rds.storedTags(arn)).thenReturn(Map.of("Owner", "rds"));
        when(docdb.storedResourceTags(arn)).thenReturn(Optional.of(Map.of()));
        assertEquals(Map.of(), lookup.storedTags(arn));
        when(docdb.storedResourceTags(arn)).thenReturn(Optional.empty());
        assertEquals(Map.of("Owner", "rds"), lookup.storedTags(arn));
    }
}
