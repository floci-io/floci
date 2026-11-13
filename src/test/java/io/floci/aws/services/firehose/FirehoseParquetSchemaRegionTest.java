package io.floci.aws.services.firehose;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.firehose.model.DeliveryStreamDescription;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Parquet conversion reads its Glue table in SchemaConfiguration.Region when the stream names one, as
 * AWS does, and in the Region the delivery runs in otherwise. The Glue catalog is Regional, so the Region
 * decides which table is found. Runs under Quarkus, where a scoped call can set the Region.
 */
@QuarkusTest
class FirehoseParquetSchemaRegionTest {

    @Inject
    RegionResolver regionResolver;

    @Test
    void theSchemasTableIsReadInTheRegionItsConfigurationNames() {
        assertEquals("eu-west-1", regionOfTheTableRead("eu-west-1"));
    }

    @Test
    void withoutARegionTheTableIsReadInTheDeliverysOwnRegion() {
        assertEquals(regionResolver.getDefaultRegion(), regionOfTheTableRead(null));
    }

    /** The Region current when the converter read the Glue table, for a schema naming {@code schemaRegion}. */
    private String regionOfTheTableRead(String schemaRegion) {
        GlueService glueService = mock(GlueService.class);
        AtomicReference<String> region = new AtomicReference<>();
        when(glueService.getTable("db", "events")).thenAnswer(invocation -> {
            region.set(regionResolver.getRegion());
            return FirehoseParquetConverterTest.table(new Column("ticker", "string"));
        });
        FirehoseParquetConverter converter = new FirehoseParquetConverter(glueService, mock(FlociDuckClient.class),
                mock(S3Service.class), new ObjectMapper(), regionResolver, FirehoseParquetConverterTest.STAGING);
        DeliveryStreamDescription stream = FirehoseParquetConverterTest.stream();
        stream.s3Destination().getDataFormatConversionConfiguration().getSchemaConfiguration().setRegion(schemaRegion);

        converter.deliver(stream, FirehoseParquetConverterTest.BUCKET,
                List.of(FirehoseParquetConverterTest.record("{\"ticker\": \"AAA\"}")),
                FirehoseParquetConverterTest.DELIVERY_TIME);

        return region.get();
    }
}
