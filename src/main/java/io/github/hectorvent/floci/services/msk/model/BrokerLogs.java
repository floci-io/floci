package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BrokerLogs {

    @JsonProperty("cloudWatchLogs")
    private CloudWatchLogs cloudWatchLogs;

    @JsonProperty("firehose")
    private Firehose firehose;

    @JsonProperty("s3")
    private S3 s3;

    public BrokerLogs() {}

    public CloudWatchLogs getCloudWatchLogs() { return cloudWatchLogs; }
    public void setCloudWatchLogs(CloudWatchLogs cloudWatchLogs) { this.cloudWatchLogs = cloudWatchLogs; }

    public Firehose getFirehose() { return firehose; }
    public void setFirehose(Firehose firehose) { this.firehose = firehose; }

    public S3 getS3() { return s3; }
    public void setS3(S3 s3) { this.s3 = s3; }
}