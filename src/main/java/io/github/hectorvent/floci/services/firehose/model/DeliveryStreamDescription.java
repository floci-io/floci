package io.github.hectorvent.floci.services.firehose.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeliveryStreamDescription {
    @JsonProperty("DeliveryStreamName")
    private String deliveryStreamName;
    private String accountId;
    @JsonProperty("DeliveryStreamARN")
    private String deliveryStreamARN;
    @JsonProperty("DeliveryStreamStatus")
    private DeliveryStreamStatus deliveryStreamStatus;
    @JsonProperty("DeliveryStreamType")
    private String deliveryStreamType = "DirectPut";
    @JsonProperty("VersionId")
    private String versionId = "1";
    @JsonProperty("HasMoreDestinations")
    private boolean hasMoreDestinations;
    @JsonProperty("CreateTimestamp")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createTimestamp;
    @JsonProperty("LastUpdateTimestamp")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Instant lastUpdateTimestamp;
    @JsonProperty("Destinations")
    private List<Destination> destinations;
    @JsonProperty("Source")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Source source;
    @JsonProperty("Tags")
    private List<Tag> tags = new ArrayList<>();

    public DeliveryStreamDescription() {}
    public DeliveryStreamDescription(String name, String arn, S3Destination s3) {
        this(name, arn, s3, null);
    }

    public DeliveryStreamDescription(String name, String arn, S3Destination s3, KinesisStreamSource kinesisStreamSource) {
        this.deliveryStreamName = name;
        this.deliveryStreamARN = arn;
        this.deliveryStreamStatus = DeliveryStreamStatus.ACTIVE;
        this.createTimestamp = Instant.now();
        if (s3 != null) {
            s3.applyDefaults();
        }
        this.destinations = List.of(new Destination(s3));
        if (kinesisStreamSource != null) {
            this.source = new Source(kinesisStreamSource);
        }
    }

    public String getDeliveryStreamName() { return deliveryStreamName; }
    public void setDeliveryStreamName(String deliveryStreamName) { this.deliveryStreamName = deliveryStreamName; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getDeliveryStreamARN() { return deliveryStreamARN; }
    public void setDeliveryStreamARN(String deliveryStreamARN) { this.deliveryStreamARN = deliveryStreamARN; }
    public DeliveryStreamStatus getDeliveryStreamStatus() { return deliveryStreamStatus; }
    public void setDeliveryStreamStatus(DeliveryStreamStatus deliveryStreamStatus) { this.deliveryStreamStatus = deliveryStreamStatus; }
    public String getDeliveryStreamType() { return deliveryStreamType; }
    public void setDeliveryStreamType(String deliveryStreamType) { this.deliveryStreamType = deliveryStreamType; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public boolean isHasMoreDestinations() { return hasMoreDestinations; }
    public void setHasMoreDestinations(boolean hasMoreDestinations) { this.hasMoreDestinations = hasMoreDestinations; }
    public Instant getCreateTimestamp() { return createTimestamp; }
    public void setCreateTimestamp(Instant createTimestamp) { this.createTimestamp = createTimestamp; }
    public Instant getLastUpdateTimestamp() { return lastUpdateTimestamp; }
    public void setLastUpdateTimestamp(Instant lastUpdateTimestamp) { this.lastUpdateTimestamp = lastUpdateTimestamp; }
    public List<Destination> getDestinations() { return destinations; }
    public void setDestinations(List<Destination> destinations) { this.destinations = destinations; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Source {
        @JsonProperty("KinesisStreamSourceDescription")
        private KinesisStreamSource kinesisStreamSourceDescription;

        public Source() {}

        public Source(KinesisStreamSource kinesisStreamSourceDescription) {
            this.kinesisStreamSourceDescription = kinesisStreamSourceDescription;
            if (kinesisStreamSourceDescription.getDeliveryStartTimestamp() == null) {
                kinesisStreamSourceDescription.setDeliveryStartTimestamp(Instant.now());
            }
        }

        public KinesisStreamSource getKinesisStreamSourceDescription() { return kinesisStreamSourceDescription; }
        public void setKinesisStreamSourceDescription(KinesisStreamSource kinesisStreamSourceDescription) {
            this.kinesisStreamSourceDescription = kinesisStreamSourceDescription;
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KinesisStreamSource {
        @JsonProperty("KinesisStreamARN")
        private String kinesisStreamArn;
        @JsonProperty("RoleARN")
        private String roleArn;
        @JsonProperty("DeliveryStartTimestamp")
        @JsonFormat(shape = JsonFormat.Shape.NUMBER)
        private Instant deliveryStartTimestamp;

        public KinesisStreamSource() {}

        public String getKinesisStreamArn() { return kinesisStreamArn; }
        public void setKinesisStreamArn(String kinesisStreamArn) { this.kinesisStreamArn = kinesisStreamArn; }
        public String getRoleArn() { return roleArn; }
        public void setRoleArn(String roleArn) { this.roleArn = roleArn; }
        public Instant getDeliveryStartTimestamp() { return deliveryStartTimestamp; }
        public void setDeliveryStartTimestamp(Instant deliveryStartTimestamp) {
            this.deliveryStartTimestamp = deliveryStartTimestamp;
        }
    }

    /**
     * Convenience: returns the first S3 destination, or null if none. Reads through the
     * extended getter - the live, full object - not getS3DestinationDescription(), which
     * returns a filtered view for the plain wire shape and would otherwise silently drop
     * FileExtension/CustomTimeZone/S3BackupMode from every caller of this method.
     */
    public S3Destination s3Destination() {
        if (destinations == null || destinations.isEmpty()) return null;
        return destinations.get(0).getExtendedS3DestinationDescription();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Destination {
        @JsonProperty("DestinationId")
        private String destinationId = "destinationId-000000000001";

        // Single canonical S3 config, serialized under both wire keys: real AWS returns
        // ExtendedS3DestinationDescription plus the deprecated S3DestinationDescription mirror
        // for every S3-backed stream. The mirror is a filtered view (see standardView()) since
        // extended-only fields like S3BackupMode aren't part of the plain shape.
        private S3Destination s3;

        public Destination() {}
        public Destination(S3Destination s3) { this.s3 = s3; }

        public String getDestinationId() { return destinationId; }
        public void setDestinationId(String destinationId) { this.destinationId = destinationId; }

        @JsonProperty("S3DestinationDescription")
        public S3Destination getS3DestinationDescription() { return s3 != null ? s3.standardView() : null; }
        @JsonProperty("S3DestinationDescription")
        public void setS3DestinationDescription(S3Destination s3) {
            // Guarded so persisted JSON carrying both keys (identical content) stays
            // idempotent while legacy files with only the S3 key still load.
            if (this.s3 == null) {
                this.s3 = s3;
            }
        }

        @JsonProperty("ExtendedS3DestinationDescription")
        public S3Destination getExtendedS3DestinationDescription() { return s3; }
        @JsonProperty("ExtendedS3DestinationDescription")
        public void setExtendedS3DestinationDescription(S3Destination s3) { this.s3 = s3; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class S3Destination {
        @JsonProperty("RoleARN")
        private String roleArn;
        @JsonProperty("BucketARN")
        private String bucketArn;
        @JsonProperty("Prefix")
        private String prefix;
        @JsonProperty("ErrorOutputPrefix")
        private String errorOutputPrefix;
        @JsonProperty("CompressionFormat")
        private String compressionFormat;
        @JsonProperty("FileExtension")
        private String fileExtension;
        @JsonProperty("CustomTimeZone")
        private String customTimeZone;
        @JsonProperty("BufferingHints")
        private BufferingHints bufferingHints;
        @JsonProperty("EncryptionConfiguration")
        private EncryptionConfiguration encryptionConfiguration;
        @JsonProperty("S3BackupMode")
        private String s3BackupMode;

        public S3Destination() {}
        public String getRoleArn() { return roleArn; }
        public void setRoleArn(String roleArn) { this.roleArn = roleArn; }
        public String getBucketArn() { return bucketArn; }
        public void setBucketArn(String bucketArn) { this.bucketArn = bucketArn; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public String getErrorOutputPrefix() { return errorOutputPrefix; }
        public void setErrorOutputPrefix(String errorOutputPrefix) { this.errorOutputPrefix = errorOutputPrefix; }
        public String getCompressionFormat() { return compressionFormat; }
        public void setCompressionFormat(String compressionFormat) { this.compressionFormat = compressionFormat; }
        public String getFileExtension() { return fileExtension; }
        public void setFileExtension(String fileExtension) { this.fileExtension = fileExtension; }
        public String getCustomTimeZone() { return customTimeZone; }
        public void setCustomTimeZone(String customTimeZone) { this.customTimeZone = customTimeZone; }
        public BufferingHints getBufferingHints() { return bufferingHints; }
        public void setBufferingHints(BufferingHints bufferingHints) { this.bufferingHints = bufferingHints; }
        public EncryptionConfiguration getEncryptionConfiguration() { return encryptionConfiguration; }
        public void setEncryptionConfiguration(EncryptionConfiguration encryptionConfiguration) { this.encryptionConfiguration = encryptionConfiguration; }
        public String getS3BackupMode() { return s3BackupMode; }
        public void setS3BackupMode(String s3BackupMode) { this.s3BackupMode = s3BackupMode; }

        /**
         * Fills the members the wire contract marks required with the AWS defaults.
         * Getters stay null-honest so UpdateDestination merges can tell "not
         * specified" from a value; call this only on create and describe paths.
         */
        public void applyDefaults() {
            if (compressionFormat == null) {
                compressionFormat = "UNCOMPRESSED";
            }
            if (s3BackupMode == null) {
                s3BackupMode = "Disabled";
            }
            if (encryptionConfiguration == null) {
                encryptionConfiguration = EncryptionConfiguration.noEncryption();
            }
            if (bufferingHints == null) {
                bufferingHints = BufferingHints.defaults();
            } else {
                // Self-heal legacy persisted state; validation keeps partial
                // hints out of the create/update paths.
                if (bufferingHints.getSizeInMBs() == null) {
                    bufferingHints.setSizeInMBs(5);
                }
                if (bufferingHints.getIntervalInSeconds() == null) {
                    bufferingHints.setIntervalInSeconds(300);
                }
            }
        }

        /**
         * A view of this config with only the fields AWS's plain S3DestinationDescription
         * actually has - FileExtension, CustomTimeZone, and S3BackupMode are extended-only.
         * Used for the standard/legacy wire shape; ExtendedS3DestinationDescription serializes
         * this same object directly instead, with every field included.
         */
        S3Destination standardView() {
            S3Destination view = new S3Destination();
            view.roleArn = roleArn;
            view.bucketArn = bucketArn;
            view.prefix = prefix;
            view.errorOutputPrefix = errorOutputPrefix;
            view.compressionFormat = compressionFormat;
            view.bufferingHints = bufferingHints;
            view.encryptionConfiguration = encryptionConfiguration;
            return view;
        }

        /** Extracts bucket name from ARN: arn:aws:s3:::my-bucket → my-bucket */
        public String bucketName() {
            if (bucketArn == null) return null;
            int last = bucketArn.lastIndexOf(':');
            return last >= 0 ? bucketArn.substring(last + 1) : bucketArn;
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EncryptionConfiguration {
        @JsonProperty("NoEncryptionConfig")
        private String noEncryptionConfig;
        @JsonProperty("KMSEncryptionConfig")
        private KmsEncryptionConfig kmsEncryptionConfig;

        public EncryptionConfiguration() {}

        public static EncryptionConfiguration noEncryption() {
            EncryptionConfiguration config = new EncryptionConfiguration();
            config.noEncryptionConfig = "NoEncryption";
            return config;
        }

        public String getNoEncryptionConfig() { return noEncryptionConfig; }
        public void setNoEncryptionConfig(String noEncryptionConfig) { this.noEncryptionConfig = noEncryptionConfig; }
        public KmsEncryptionConfig getKmsEncryptionConfig() { return kmsEncryptionConfig; }
        public void setKmsEncryptionConfig(KmsEncryptionConfig kmsEncryptionConfig) { this.kmsEncryptionConfig = kmsEncryptionConfig; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KmsEncryptionConfig {
        @JsonProperty("AWSKMSKeyARN")
        private String awsKmsKeyArn;

        public KmsEncryptionConfig() {}
        public String getAwsKmsKeyArn() { return awsKmsKeyArn; }
        public void setAwsKmsKeyArn(String awsKmsKeyArn) { this.awsKmsKeyArn = awsKmsKeyArn; }
    }

    /**
     * Members stay boxed and null-honest so validation can tell "not specified"
     * from a value: AWS requires SizeInMBs and IntervalInSeconds to be specified
     * together, and the defaults (5 MiB / 300 s) apply only when the whole
     * object is absent.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BufferingHints {
        @JsonProperty("SizeInMBs")
        private Integer sizeInMBs;
        @JsonProperty("IntervalInSeconds")
        private Integer intervalInSeconds;

        public BufferingHints() {}

        public static BufferingHints defaults() {
            BufferingHints hints = new BufferingHints();
            hints.sizeInMBs = 5;
            hints.intervalInSeconds = 300;
            return hints;
        }

        public Integer getSizeInMBs() { return sizeInMBs; }
        public void setSizeInMBs(Integer sizeInMBs) { this.sizeInMBs = sizeInMBs; }
        public Integer getIntervalInSeconds() { return intervalInSeconds; }
        public void setIntervalInSeconds(Integer intervalInSeconds) { this.intervalInSeconds = intervalInSeconds; }
    }

    public List<Tag> getTags() {
        if (tags == null) tags = new ArrayList<>();
        return tags;
    }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tag {
        @JsonProperty("Key")
        private String key;
        @JsonProperty("Value")
        private String value;

        public Tag() {}
        public Tag(String key, String value) {
            this.key = key;
            this.value = value;
        }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
