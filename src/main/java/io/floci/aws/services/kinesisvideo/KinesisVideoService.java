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
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class KinesisVideoService implements Resettable {
    static final String STATUS_ACTIVE = "ACTIVE";

    // The constraints AWS models (names, media type, version, tags, MaxResults) are checked against the vendored
    // model by KinesisVideoRequestShapes before a request reaches this service. What stays here is what the model
    // does not say: the parts of a stream ARN, and the stream name a NextToken carries.
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_.-]{1,256}");
    private static final int DEFAULT_LIST_RESULTS = 10000;
    private static final String DEFAULT_KMS_ALIAS = "alias/aws/kinesisvideo";

    private final AccountAwareStorageBackend<KinesisVideoStream> streams;
    private long lastCreationTimeMillis;

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
        String streamName = request.streamName();
        int retention = request.dataRetentionInHours() == null ? 0 : request.dataRetentionInHours();
        Map<String, String> tags = request.tags() == null ? Map.of() : Map.copyOf(request.tags());

        String key = key(region, streamName);
        if (streams.getForAccount(accountId, key).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "The stream " + streamName + " already exists.", 400);
        }

        // The creation time is the ARN's last segment, so it must differ from every earlier stream's: a stream
        // deleted and recreated within one millisecond would otherwise get the old ARN back.
        long creationTimeMillis = Math.max(System.currentTimeMillis(), lastCreationTimeMillis + 1);
        lastCreationTimeMillis = creationTimeMillis;
        String arn = AwsArnUtils.Arn.of("kinesisvideo", region, accountId,
                "stream/" + streamName + "/" + creationTimeMillis).toString();
        String resolvedKmsKeyId = request.kmsKeyId() != null
                ? request.kmsKeyId()
                : AwsArnUtils.Arn.of("kms", region, accountId, DEFAULT_KMS_ALIAS).toString();
        KinesisVideoStream stream = new KinesisVideoStream(
                streamName,
                arn,
                request.deviceName(),
                request.mediaType(),
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
        return streams.getForAccount(accountId, key(region, streamName)).orElseThrow(KinesisVideoService::notFound);
    }

    public Page listStreams(String region, String accountId, Integer maxResults, String nextToken, String namePrefix) {
        int limit = maxResults == null ? DEFAULT_LIST_RESULTS : maxResults;
        String regionPrefix = region + "::";
        List<KinesisVideoStream> items = new ArrayList<>(streams.scanForAccount(accountId,
                key -> key.startsWith(regionPrefix)));
        if (namePrefix != null) {
            items.removeIf(stream -> !stream.streamName().startsWith(namePrefix));
        }
        String after = decodeCursor(nextToken);
        if (after != null) {
            items.removeIf(stream -> stream.streamName().compareTo(after) <= 0);
        }
        items.sort(Comparator.comparing(KinesisVideoStream::streamName));
        if (items.size() <= limit) {
            return new Page(List.copyOf(items), null);
        }
        List<KinesisVideoStream> page = List.copyOf(items.subList(0, limit));
        return new Page(page, encodeCursor(page.get(limit - 1).streamName()));
    }

    /**
     * The ARN the stream of {@code streamName} in the account and Region is stored under, which ends with the creation
     * time a request naming the stream by its name does not carry; empty when there is no such stream.
     */
    public Optional<String> storedStreamArn(String region, String accountId, String streamName) {
        return streams.getForAccount(accountId, key(region, streamName)).map(KinesisVideoStream::streamArn);
    }

    /**
     * The tags of the stream of the account and Region whose ARN is {@code streamArn}, matched on the whole ARN, so the
     * ARN of a deleted stream reads none from a stream later created with its name; empty when there is no such stream.
     */
    public Map<String, String> storedStreamTags(String region, String accountId, String streamArn) {
        return streamNameOf(streamArn)
                .flatMap(streamName -> streams.getForAccount(accountId, key(region, streamName)))
                .filter(stream -> stream.streamArn().equals(streamArn))
                .map(KinesisVideoStream::tags)
                .orElse(Map.of());
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
        return streamNameOf(streamArn).orElseThrow(() -> invalidArgument("StreamARN is invalid."));
    }

    /** The stream name a Kinesis Video stream ARN ({@code stream/<name>/<creationTime>}) carries; empty for any other. */
    private static Optional<String> streamNameOf(String streamArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(streamArn);
        } catch (IllegalArgumentException notAnArn) {
            return Optional.empty();
        }
        String[] resource = arn.resource().split("/", -1);
        if (!"kinesisvideo".equals(arn.service()) || resource.length != 3 || !"stream".equals(resource[0])
                || !NAME_PATTERN.matcher(resource[1]).matches() || !resource[2].matches("[0-9]+")) {
            return Optional.empty();
        }
        return Optional.of(resource[1]);
    }

    /**
     * The token names the last stream of the previous page, not its position, so a stream deleted or created
     * between two pages does not shift the next page past a stream that was never returned.
     */
    private static String decodeCursor(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String streamName;
        try {
            streamName = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalidArgument("NextToken is invalid.");
        }
        if (!NAME_PATTERN.matcher(streamName).matches()) {
            throw invalidArgument("NextToken is invalid.");
        }
        return streamName;
    }

    private static String encodeCursor(String streamName) {
        return Base64.getEncoder().encodeToString(streamName.getBytes(StandardCharsets.UTF_8));
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
