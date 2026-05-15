package io.github.hectorvent.floci.services.bcmdataexports.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * S3 destination + output config for a BCM Data Exports {@code Export}.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DestinationConfiguration {

    private S3Destination s3Destination;

    public S3Destination getS3Destination() { return s3Destination; }
    public void setS3Destination(S3Destination v) { this.s3Destination = v; }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class S3Destination {
        private String s3Bucket;
        private String s3Prefix;
        private String s3Region;
        private S3OutputConfigurations s3OutputConfigurations;

        public String getS3Bucket() { return s3Bucket; }
        public void setS3Bucket(String v) { this.s3Bucket = v; }

        public String getS3Prefix() { return s3Prefix; }
        public void setS3Prefix(String v) { this.s3Prefix = v; }

        public String getS3Region() { return s3Region; }
        public void setS3Region(String v) { this.s3Region = v; }

        public S3OutputConfigurations getS3OutputConfigurations() { return s3OutputConfigurations; }
        public void setS3OutputConfigurations(S3OutputConfigurations v) { this.s3OutputConfigurations = v; }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class S3OutputConfigurations {
        private String compression;     // GZIP | PARQUET
        private String format;          // TEXT_OR_CSV | PARQUET
        private String outputType;      // CUSTOM
        private String overwrite;       // CREATE_NEW_REPORT | OVERWRITE_REPORT

        public String getCompression() { return compression; }
        public void setCompression(String v) { this.compression = v; }

        public String getFormat() { return format; }
        public void setFormat(String v) { this.format = v; }

        public String getOutputType() { return outputType; }
        public void setOutputType(String v) { this.outputType = v; }

        public String getOverwrite() { return overwrite; }
        public void setOverwrite(String v) { this.overwrite = v; }
    }
}
