package io.floci.aws.services.kinesisvideo;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.kinesisvideo.model.KinesisVideoStream;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class KinesisVideoService implements Resettable {
    static final String STATUS_ACTIVE = "ACTIVE";

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_.-]+");
    private static final Pattern MEDIA_TYPE_PATTERN = Pattern.compile(
            "[\\w\\-.+]+/[\\w\\-.+]+(,[\\w\\-.+]+/[\\w\\-.+]+)*");
    private static final Pattern VERSION_PATTERN = Pattern.compile("[a-zA-Z0-9]+");
    private static final int MAX_STREAM_NAME_LENGTH = 256;
    private static final int MAX_DEVICE_NAME_LENGTH = 128;
    private static final int MAX_MEDIA_TYPE_LENGTH = 128;
    private static final int MAX_KMS_KEY_ID_LENGTH = 2048;
    private static final int MAX_TAGS = 50;
    private static final int MAX_TAG_KEY_LENGTH = 128;
    private static final int MAX_TAG_VALUE_LENGTH = 256;
    private static final int MAX_LIST_RESULTS = 10000;
    private static final String DEFAULT_KMS_ALIAS = "alias/aws/kinesisvideo";

    private final AccountAwareStorageBackend<KinesisVideoStream> streams;

    @Inject
    public KinesisVideoService(StorageFactory storageFactory) {
        this(storageFactory.create("kinesisvideo", "kinesisvideo-streams.json",
                new TypeReference<Map<String, KinesisVideoStream>>() {}));
    }

    KinesisVideoService(AccountAwareStorageBackend<KinesisVideoStream> streams) {
        this.streams = streams;
    }

    public record CreateStreamRequest(
            String streamName,
            String deviceName,
            String mediaType,
            String kmsKeyId,
            Integer dataRetentionInHours,
            Map<String, String> tags) {
    }

    public record Page(List<KinesisVideoStream> streams, String nextToken) {
    }

    public synchronized KinesisVideoStream createStream(String region, String accountId, CreateStreamRequest request) {
        String streamName = requireName(request.streamName(), "StreamName", MAX_STREAM_NAME_LENGTH);
        String deviceName = optionalName(request.deviceName(), "DeviceName", MAX_DEVICE_NAME_LENGTH);
        String mediaType = optionalMediaType(request.mediaType());
        String kmsKeyId = optionalKmsKeyId(request.kmsKeyId());
        int retention = dataRetention(request.dataRetentionInHours());
        Map<String, String> tags = validateTags(request.tags());

        String key = key(region, streamName);
        if (streams.getForAccount(accountId, key).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "The stream " + streamName + " already exists.", 400);
        }

        long creationTimeMillis = System.currentTimeMillis();
        String arn = AwsArnUtils.Arn.of("kinesisvideo", region, accountId,
                "stream/" + streamName + "/" + creationTimeMillis).toString();
        String resolvedKmsKeyId = kmsKeyId != null
                ? kmsKeyId
                : AwsArnUtils.Arn.of("kms", region, accountId, DEFAULT_KMS_ALIAS).toString();
        KinesisVideoStream stream = new KinesisVideoStream(
                streamName,
                arn,
                deviceName,
                mediaType,
                resolvedKmsKeyId,
                newVersion(),
                STATUS_ACTIVE,
                creationTimeMillis,
                retention,
                tags);
        streams.putForAccount(accountId, key, stream);
        return stream;
    }

    public synchronized void deleteStream(String region, String accountId, String streamArn, String currentVersion) {
        if (streamArn == null) {
            throw invalidArgument("StreamARN is required.");
        }
        if (currentVersion != null && (currentVersion.isEmpty() || currentVersion.length() > 64
                || !VERSION_PATTERN.matcher(currentVersion).matches())) {
            throw invalidArgument("CurrentVersion is invalid.");
        }
        KinesisVideoStream stream = findByArn(region, accountId, streamArn);
        if (currentVersion != null && !currentVersion.equals(stream.version())) {
            throw new AwsException("VersionMismatchException",
                    "The version of the stream " + stream.streamName() + " does not match the CurrentVersion.", 400);
        }
        streams.deleteForAccount(accountId, key(region, stream.streamName()));
    }

    public KinesisVideoStream describeStream(String region, String accountId, String streamName, String streamArn) {
        if (streamName == null && streamArn == null) {
            throw invalidArgument("Either StreamName or StreamARN must be specified.");
        }
        if (streamArn != null) {
            KinesisVideoStream stream = findByArn(region, accountId, streamArn);
            if (streamName != null && !streamName.equals(stream.streamName())) {
                throw notFound();
            }
            return stream;
        }
        requireName(streamName, "StreamName", MAX_STREAM_NAME_LENGTH);
        return streams.getForAccount(accountId, key(region, streamName)).orElseThrow(KinesisVideoService::notFound);
    }

    public Page listStreams(String region, String accountId, Integer maxResults, String nextToken, String namePrefix) {
        int limit = maxResults == null ? MAX_LIST_RESULTS : maxResults;
        if (limit < 1 || limit > MAX_LIST_RESULTS) {
            throw invalidArgument("MaxResults must be between 1 and " + MAX_LIST_RESULTS + ".");
        }
        if (namePrefix != null) {
            requireName(namePrefix, "ComparisonValue", MAX_STREAM_NAME_LENGTH);
        }
        String regionPrefix = region + "::";
        List<KinesisVideoStream> items = new ArrayList<>(streams.scanForAccount(accountId,
                key -> key.startsWith(regionPrefix)));
        if (namePrefix != null) {
            items.removeIf(stream -> !stream.streamName().startsWith(namePrefix));
        }
        items.sort(Comparator.comparing(KinesisVideoStream::streamName));
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(items.size(), offset + limit);
        return new Page(List.copyOf(items.subList(offset, end)), end < items.size() ? encodeOffset(end) : null);
    }

    @Override
    public void clear() {
        streams.clear();
    }

    private KinesisVideoStream findByArn(String region, String accountId, String streamArn) {
        String streamName = streamNameFromArn(streamArn);
        return streams.getForAccount(accountId, key(region, streamName))
                .filter(stream -> stream.streamArn().equals(streamArn))
                .orElseThrow(KinesisVideoService::notFound);
    }

    private static String streamNameFromArn(String streamArn) {
        if (streamArn.isEmpty() || streamArn.length() > 1024) {
            throw invalidArgument("StreamARN is invalid.");
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(streamArn);
        } catch (IllegalArgumentException e) {
            throw invalidArgument("StreamARN is invalid.");
        }
        String[] resource = arn.resource().split("/", -1);
        if (!"kinesisvideo".equals(arn.service()) || resource.length != 3 || !"stream".equals(resource[0])
                || !NAME_PATTERN.matcher(resource[1]).matches() || !resource[2].matches("[0-9]+")) {
            throw invalidArgument("StreamARN is invalid.");
        }
        return resource[1];
    }

    private static String requireName(String value, String field, int maxLength) {
        if (value == null) {
            throw invalidArgument(field + " is required.");
        }
        return optionalName(value, field, maxLength);
    }

    private static String optionalName(String value, String field, int maxLength) {
        if (value != null && (value.isEmpty() || value.length() > maxLength
                || !NAME_PATTERN.matcher(value).matches())) {
            throw invalidArgument(field + " must be 1 to " + maxLength
                    + " characters matching [a-zA-Z0-9_.-]+.");
        }
        return value;
    }

    private static String optionalMediaType(String value) {
        if (value != null && (value.isEmpty() || value.length() > MAX_MEDIA_TYPE_LENGTH
                || !MEDIA_TYPE_PATTERN.matcher(value).matches())) {
            throw invalidArgument("MediaType is invalid.");
        }
        return value;
    }

    private static String optionalKmsKeyId(String value) {
        if (value != null && (value.isEmpty() || value.length() > MAX_KMS_KEY_ID_LENGTH)) {
            throw invalidArgument("KmsKeyId must be 1 to " + MAX_KMS_KEY_ID_LENGTH + " characters.");
        }
        return value;
    }

    private static int dataRetention(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw invalidArgument("DataRetentionInHours must be greater than or equal to 0.");
        }
        return value;
    }

    private static Map<String, String> validateTags(Map<String, String> tags) {
        if (tags == null) {
            return Map.of();
        }
        if (tags.isEmpty()) {
            throw invalidArgument("Tags must contain at least 1 tag.");
        }
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("TagsPerResourceExceededLimitException",
                    "A stream can have at most " + MAX_TAGS + " tags.", 400);
        }
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            String tagKey = tag.getKey();
            String tagValue = tag.getValue();
            if (tagKey == null || tagKey.isEmpty() || tagKey.length() > MAX_TAG_KEY_LENGTH
                    || tagValue == null || tagValue.length() > MAX_TAG_VALUE_LENGTH) {
                throw invalidArgument("Tag keys must be 1 to " + MAX_TAG_KEY_LENGTH
                        + " characters and values at most " + MAX_TAG_VALUE_LENGTH + " characters.");
            }
        }
        return Map.copyOf(tags);
    }

    private static int decodeOffset(String token, int size) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8));
            if (offset < 0 || offset > size) {
                throw invalidArgument("NextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw invalidArgument("NextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getEncoder().encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static String newVersion() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String key(String region, String streamName) {
        return region + "::" + streamName;
    }

    private static AwsException invalidArgument(String message) {
        return new AwsException("InvalidArgumentException", message, 400);
    }

    private static AwsException notFound() {
        return new AwsException("ResourceNotFoundException", "The requested stream is not found or not active.", 404);
    }
}
