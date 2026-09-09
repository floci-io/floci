package io.github.hectorvent.floci.services.firehose.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    // Field-level default so streams persisted before this field existed deserialize to
    // the AWS-shaped DISABLED configuration instead of null (Jackson only overwrites it
    // when the stored JSON actually carries the property).
    @JsonProperty("DeliveryStreamEncryptionConfiguration")
    private DeliveryStreamEncryptionConfiguration deliveryStreamEncryptionConfiguration =
            DeliveryStreamEncryptionConfiguration.disabled();

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
        this.deliveryStreamEncryptionConfiguration = DeliveryStreamEncryptionConfiguration.disabled();
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
    public DeliveryStreamEncryptionConfiguration getDeliveryStreamEncryptionConfiguration() {
        return deliveryStreamEncryptionConfiguration;
    }
    public void setDeliveryStreamEncryptionConfiguration(DeliveryStreamEncryptionConfiguration configuration) {
        this.deliveryStreamEncryptionConfiguration = configuration;
    }
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
        @JsonProperty("DataFormatConversionConfiguration")
        private DataFormatConversionConfiguration dataFormatConversionConfiguration;

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
        public DataFormatConversionConfiguration getDataFormatConversionConfiguration() {
            return dataFormatConversionConfiguration;
        }
        public void setDataFormatConversionConfiguration(DataFormatConversionConfiguration configuration) {
            this.dataFormatConversionConfiguration = configuration;
        }

        /**
         * True when data format conversion applies to deliveries: the configuration is
         * present and not explicitly disabled. AWS treats an omitted Enabled as enabled
         * (probe: a config without Enabled is validated exactly like an enabled one).
         *
         * Ignored for serialization: an {@code is...} accessor with no backing field of
         * that name is a property to Jackson, which would put a non-AWS
         * {@code dataFormatConversionEnabled} member in every destination it renders.
         */
        @JsonIgnore
        public boolean isDataFormatConversionEnabled() {
            return dataFormatConversionConfiguration != null
                    && !Boolean.FALSE.equals(dataFormatConversionConfiguration.getEnabled());
        }

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
            // A conversion-enabled destination that specified no hints gets AWS's
            // larger default size, verified against the service: the stored hints
            // come back as 128 MiB, not the ordinary 5 MiB, while the interval
            // default is unchanged. Filling in 5 here would report a size AWS never
            // uses and would then fail the 64 MiB floor on the stream's next update.
            int defaultSizeInMBs = isDataFormatConversionEnabled()
                    ? BufferingHints.CONVERSION_DEFAULT_SIZE_MBS
                    : BufferingHints.DEFAULT_SIZE_MBS;
            if (bufferingHints == null) {
                bufferingHints = BufferingHints.defaults(defaultSizeInMBs);
            } else {
                // Self-heal legacy persisted state; validation keeps partial
                // hints out of the create/update paths.
                if (bufferingHints.getSizeInMBs() == null) {
                    bufferingHints.setSizeInMBs(defaultSizeInMBs);
                }
                if (bufferingHints.getIntervalInSeconds() == null) {
                    bufferingHints.setIntervalInSeconds(BufferingHints.DEFAULT_INTERVAL_SECONDS);
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

    /**
     * Members stay null-honest, mirroring real AWS: DescribeDeliveryStream echoes the
     * configuration exactly as it was given (an empty ParquetSerDe stays {}, CatalogId
     * and VersionId are not materialized), and UpdateDestination merges member-wise, so
     * "not specified" must remain distinguishable from a value.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DataFormatConversionConfiguration {
        @JsonProperty("Enabled")
        private Boolean enabled;
        @JsonProperty("SchemaConfiguration")
        private SchemaConfiguration schemaConfiguration;
        @JsonProperty("InputFormatConfiguration")
        private InputFormatConfiguration inputFormatConfiguration;
        @JsonProperty("OutputFormatConfiguration")
        private OutputFormatConfiguration outputFormatConfiguration;

        public DataFormatConversionConfiguration() {}

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public SchemaConfiguration getSchemaConfiguration() { return schemaConfiguration; }
        public void setSchemaConfiguration(SchemaConfiguration schemaConfiguration) {
            this.schemaConfiguration = schemaConfiguration;
        }
        public InputFormatConfiguration getInputFormatConfiguration() { return inputFormatConfiguration; }
        public void setInputFormatConfiguration(InputFormatConfiguration inputFormatConfiguration) {
            this.inputFormatConfiguration = inputFormatConfiguration;
        }
        public OutputFormatConfiguration getOutputFormatConfiguration() { return outputFormatConfiguration; }
        public void setOutputFormatConfiguration(OutputFormatConfiguration outputFormatConfiguration) {
            this.outputFormatConfiguration = outputFormatConfiguration;
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaConfiguration {
        @JsonProperty("RoleARN")
        private String roleArn;
        @JsonProperty("CatalogId")
        private String catalogId;
        @JsonProperty("DatabaseName")
        private String databaseName;
        @JsonProperty("TableName")
        private String tableName;
        @JsonProperty("Region")
        private String region;
        @JsonProperty("VersionId")
        private String versionId;

        public SchemaConfiguration() {}

        public String getRoleArn() { return roleArn; }
        public void setRoleArn(String roleArn) { this.roleArn = roleArn; }
        public String getCatalogId() { return catalogId; }
        public void setCatalogId(String catalogId) { this.catalogId = catalogId; }
        public String getDatabaseName() { return databaseName; }
        public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getVersionId() { return versionId; }
        public void setVersionId(String versionId) { this.versionId = versionId; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InputFormatConfiguration {
        @JsonProperty("Deserializer")
        private Deserializer deserializer;

        public InputFormatConfiguration() {}

        public Deserializer getDeserializer() { return deserializer; }
        public void setDeserializer(Deserializer deserializer) { this.deserializer = deserializer; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Deserializer {
        @JsonProperty("OpenXJsonSerDe")
        private OpenXJsonSerDe openXJsonSerDe;
        @JsonProperty("HiveJsonSerDe")
        private HiveJsonSerDe hiveJsonSerDe;

        public Deserializer() {}

        public OpenXJsonSerDe getOpenXJsonSerDe() { return openXJsonSerDe; }
        public void setOpenXJsonSerDe(OpenXJsonSerDe openXJsonSerDe) { this.openXJsonSerDe = openXJsonSerDe; }
        public HiveJsonSerDe getHiveJsonSerDe() { return hiveJsonSerDe; }
        public void setHiveJsonSerDe(HiveJsonSerDe hiveJsonSerDe) { this.hiveJsonSerDe = hiveJsonSerDe; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OpenXJsonSerDe {
        @JsonProperty("ConvertDotsInJsonKeysToUnderscores")
        private Boolean convertDotsInJsonKeysToUnderscores;
        @JsonProperty("CaseInsensitive")
        private Boolean caseInsensitive;
        @JsonProperty("ColumnToJsonKeyMappings")
        private Map<String, String> columnToJsonKeyMappings;

        public OpenXJsonSerDe() {}

        public Boolean getConvertDotsInJsonKeysToUnderscores() { return convertDotsInJsonKeysToUnderscores; }
        public void setConvertDotsInJsonKeysToUnderscores(Boolean convertDotsInJsonKeysToUnderscores) {
            this.convertDotsInJsonKeysToUnderscores = convertDotsInJsonKeysToUnderscores;
        }
        public Boolean getCaseInsensitive() { return caseInsensitive; }
        public void setCaseInsensitive(Boolean caseInsensitive) { this.caseInsensitive = caseInsensitive; }
        public Map<String, String> getColumnToJsonKeyMappings() { return columnToJsonKeyMappings; }
        public void setColumnToJsonKeyMappings(Map<String, String> columnToJsonKeyMappings) {
            this.columnToJsonKeyMappings = columnToJsonKeyMappings;
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HiveJsonSerDe {
        @JsonProperty("TimestampFormats")
        private List<String> timestampFormats;

        public HiveJsonSerDe() {}

        public List<String> getTimestampFormats() { return timestampFormats; }
        public void setTimestampFormats(List<String> timestampFormats) { this.timestampFormats = timestampFormats; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OutputFormatConfiguration {
        @JsonProperty("Serializer")
        private Serializer serializer;

        public OutputFormatConfiguration() {}

        public Serializer getSerializer() { return serializer; }
        public void setSerializer(Serializer serializer) { this.serializer = serializer; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Serializer {
        @JsonProperty("ParquetSerDe")
        private ParquetSerDe parquetSerDe;
        @JsonProperty("OrcSerDe")
        private OrcSerDe orcSerDe;

        public Serializer() {}

        public ParquetSerDe getParquetSerDe() { return parquetSerDe; }
        public void setParquetSerDe(ParquetSerDe parquetSerDe) { this.parquetSerDe = parquetSerDe; }
        public OrcSerDe getOrcSerDe() { return orcSerDe; }
        public void setOrcSerDe(OrcSerDe orcSerDe) { this.orcSerDe = orcSerDe; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ParquetSerDe {
        @JsonProperty("BlockSizeBytes")
        private Long blockSizeBytes;
        @JsonProperty("PageSizeBytes")
        private Long pageSizeBytes;
        @JsonProperty("Compression")
        private String compression;
        @JsonProperty("EnableDictionaryCompression")
        private Boolean enableDictionaryCompression;
        @JsonProperty("MaxPaddingBytes")
        private Long maxPaddingBytes;
        @JsonProperty("WriterVersion")
        private String writerVersion;

        public ParquetSerDe() {}

        public Long getBlockSizeBytes() { return blockSizeBytes; }
        public void setBlockSizeBytes(Long blockSizeBytes) { this.blockSizeBytes = blockSizeBytes; }
        public Long getPageSizeBytes() { return pageSizeBytes; }
        public void setPageSizeBytes(Long pageSizeBytes) { this.pageSizeBytes = pageSizeBytes; }
        public String getCompression() { return compression; }
        public void setCompression(String compression) { this.compression = compression; }
        public Boolean getEnableDictionaryCompression() { return enableDictionaryCompression; }
        public void setEnableDictionaryCompression(Boolean enableDictionaryCompression) {
            this.enableDictionaryCompression = enableDictionaryCompression;
        }
        public Long getMaxPaddingBytes() { return maxPaddingBytes; }
        public void setMaxPaddingBytes(Long maxPaddingBytes) { this.maxPaddingBytes = maxPaddingBytes; }
        public String getWriterVersion() { return writerVersion; }
        public void setWriterVersion(String writerVersion) { this.writerVersion = writerVersion; }
    }

    /**
     * Floci rejects an <em>enabled</em> OrcSerDe configuration (DuckDB cannot write
     * ORC), but a disabled one is stored like any other, so the members are mapped:
     * a marker class would silently drop them through {@code ignoreUnknown} and
     * DescribeDeliveryStream could no longer echo the configuration as supplied.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OrcSerDe {
        @JsonProperty("StripeSizeBytes")
        private Long stripeSizeBytes;
        @JsonProperty("BlockSizeBytes")
        private Long blockSizeBytes;
        @JsonProperty("RowIndexStride")
        private Integer rowIndexStride;
        @JsonProperty("EnablePadding")
        private Boolean enablePadding;
        @JsonProperty("PaddingTolerance")
        private Double paddingTolerance;
        @JsonProperty("Compression")
        private String compression;
        @JsonProperty("BloomFilterColumns")
        private List<String> bloomFilterColumns;
        @JsonProperty("BloomFilterFalsePositiveProbability")
        private Double bloomFilterFalsePositiveProbability;
        @JsonProperty("DictionaryKeyThreshold")
        private Double dictionaryKeyThreshold;
        @JsonProperty("FormatVersion")
        private String formatVersion;

        public OrcSerDe() {}

        public Long getStripeSizeBytes() { return stripeSizeBytes; }
        public void setStripeSizeBytes(Long stripeSizeBytes) { this.stripeSizeBytes = stripeSizeBytes; }
        public Long getBlockSizeBytes() { return blockSizeBytes; }
        public void setBlockSizeBytes(Long blockSizeBytes) { this.blockSizeBytes = blockSizeBytes; }
        public Integer getRowIndexStride() { return rowIndexStride; }
        public void setRowIndexStride(Integer rowIndexStride) { this.rowIndexStride = rowIndexStride; }
        public Boolean getEnablePadding() { return enablePadding; }
        public void setEnablePadding(Boolean enablePadding) { this.enablePadding = enablePadding; }
        public Double getPaddingTolerance() { return paddingTolerance; }
        public void setPaddingTolerance(Double paddingTolerance) { this.paddingTolerance = paddingTolerance; }
        public String getCompression() { return compression; }
        public void setCompression(String compression) { this.compression = compression; }
        public List<String> getBloomFilterColumns() { return bloomFilterColumns; }
        public void setBloomFilterColumns(List<String> bloomFilterColumns) {
            this.bloomFilterColumns = bloomFilterColumns;
        }
        public Double getBloomFilterFalsePositiveProbability() { return bloomFilterFalsePositiveProbability; }
        public void setBloomFilterFalsePositiveProbability(Double bloomFilterFalsePositiveProbability) {
            this.bloomFilterFalsePositiveProbability = bloomFilterFalsePositiveProbability;
        }
        public Double getDictionaryKeyThreshold() { return dictionaryKeyThreshold; }
        public void setDictionaryKeyThreshold(Double dictionaryKeyThreshold) {
            this.dictionaryKeyThreshold = dictionaryKeyThreshold;
        }
        public String getFormatVersion() { return formatVersion; }
        public void setFormatVersion(String formatVersion) { this.formatVersion = formatVersion; }
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
     * together, and the defaults apply only when the whole object is absent. The
     * size default depends on the destination (see
     * {@link S3Destination#applyDefaults()}); the interval default does not.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BufferingHints {
        static final int DEFAULT_SIZE_MBS = 5;
        static final int CONVERSION_DEFAULT_SIZE_MBS = 128;
        static final int DEFAULT_INTERVAL_SECONDS = 300;

        @JsonProperty("SizeInMBs")
        private Integer sizeInMBs;
        @JsonProperty("IntervalInSeconds")
        private Integer intervalInSeconds;

        public BufferingHints() {}

        public static BufferingHints defaults() {
            return defaults(DEFAULT_SIZE_MBS);
        }

        static BufferingHints defaults(int sizeInMBs) {
            BufferingHints hints = new BufferingHints();
            hints.sizeInMBs = sizeInMBs;
            hints.intervalInSeconds = DEFAULT_INTERVAL_SECONDS;
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

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DeliveryStreamEncryptionConfiguration {
        @JsonProperty("KeyType")
        private String keyType;
        @JsonProperty("KeyARN")
        private String keyArn;
        @JsonProperty("Status")
        private String status;

        public DeliveryStreamEncryptionConfiguration() {}

        public DeliveryStreamEncryptionConfiguration(String keyType, String keyArn, String status) {
            this.keyType = keyType;
            this.keyArn = keyArn;
            this.status = status;
        }

        public static DeliveryStreamEncryptionConfiguration disabled() {
            return new DeliveryStreamEncryptionConfiguration("AWS_OWNED_CMK", null, "DISABLED");
        }

        public String getKeyType() { return keyType; }
        public void setKeyType(String keyType) { this.keyType = keyType; }
        public String getKeyArn() { return keyArn; }
        public void setKeyArn(String keyArn) { this.keyArn = keyArn; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
