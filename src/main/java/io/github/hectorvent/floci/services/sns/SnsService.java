package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.SsrfProtection;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.sns.model.PlatformApplication;
import io.github.hectorvent.floci.services.sns.model.PlatformEndpoint;
import io.github.hectorvent.floci.services.sns.model.PushNotification;
import io.github.hectorvent.floci.services.sns.model.SentSms;
import io.github.hectorvent.floci.services.sns.model.SmsSandboxPhoneNumber;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@ApplicationScoped
public class SnsService implements Resettable, ResourceProvider {

    private static final Logger LOG = Logger.getLogger(SnsService.class);
    private static final Duration FIFO_DEDUP_WINDOW = Duration.ofMinutes(5);
    private static final Set<String> SMS_ATTRIBUTE_NAMES = Set.of("MonthlySpendLimit",
            "DeliveryStatusIAMRole", "DeliveryStatusSuccessSamplingRate", "DefaultSenderID",
            "DefaultSMSType", "UsageReportS3Bucket");
    private static final Pattern SMS_SENDER_ID = Pattern.compile("(?=.*[A-Za-z])[A-Za-z0-9]{1,11}");
    /** The format AWS accepts for a number it opts in or checks the opt-out status of. */
    private static final Pattern E164_PHONE_NUMBER = Pattern.compile("\\+[1-9][0-9]{1,14}");
    /** A sandbox destination number, as the {@code PhoneNumberString} shape in SNS's model constrains it. */
    private static final Pattern SANDBOX_PHONE_NUMBER = Pattern.compile("\\+[0-9]{8,}|[0-9]{0,9}");
    private static final int SANDBOX_PHONE_NUMBER_MAX_LENGTH = 20;
    /** The {@code OTPCode} shape in SNS's model. */
    private static final Pattern ONE_TIME_PASSWORD = Pattern.compile("[0-9]{5,8}");
    /** The {@code LanguageCodeString} enum in SNS's model, the languages a verification text can be sent in. */
    private static final Set<String> SANDBOX_LANGUAGE_CODES = Set.of("en-US", "en-GB", "es-419", "es-ES",
            "de-DE", "fr-CA", "fr-FR", "it-IT", "ja-JP", "pt-BR", "kr-KR", "zh-CN", "zh-TW");
    /** An AWS account ID, the only principal {@code AddPermission} takes. */
    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9]{12}");
    /** The {@code MaxItems} ceiling on {@code ListSMSSandboxPhoneNumbers}, which is also its page size. */
    private static final int SANDBOX_PAGE_SIZE = 100;
    /**
     * The actions a topic policy statement can grant, which are SNS's actions in AWS's service
     * reference (https://servicereference.us-east-1.amazonaws.com/v1/sns/sns.json).
     * {@code AddPermission} refuses any other name.
     */
    private static final Set<String> SNS_POLICY_ACTIONS = Set.of(
            "AddPermission", "CheckIfPhoneNumberIsOptedOut", "ConfirmSubscription",
            "CreatePlatformApplication", "CreatePlatformEndpoint", "CreateSMSSandboxPhoneNumber",
            "CreateTopic", "DeleteEndpoint", "DeletePlatformApplication", "DeleteSMSSandboxPhoneNumber",
            "DeleteTopic", "GetDataProtectionPolicy", "GetEndpointAttributes",
            "GetPlatformApplicationAttributes", "GetSMSAttributes", "GetSMSSandboxAccountStatus",
            "GetSubscriptionAttributes", "GetTopicAttributes", "ListEndpointsByPlatformApplication",
            "ListOriginationNumbers", "ListPhoneNumbersOptedOut", "ListPlatformApplications",
            "ListSMSSandboxPhoneNumbers", "ListSubscriptions", "ListSubscriptionsByTopic",
            "ListTagsForResource", "ListTopics", "OptInPhoneNumber", "Publish", "PutDataProtectionPolicy",
            "RemovePermission", "SetEndpointAttributes", "SetPlatformApplicationAttributes",
            "SetSMSAttributes", "SetSubscriptionAttributes", "SetTopicAttributes", "Subscribe",
            "TagResource", "Unsubscribe", "UntagResource", "VerifySMSSandboxPhoneNumber");
    /** What the default statement of a topic's policy grants its owner. */
    private static final List<String> DEFAULT_TOPIC_POLICY_ACTIONS = List.of(
            "SNS:GetTopicAttributes", "SNS:SetTopicAttributes", "SNS:AddPermission",
            "SNS:RemovePermission", "SNS:DeleteTopic", "SNS:Subscribe",
            "SNS:ListSubscriptionsByTopic", "SNS:Publish");
    /** Includes the original send and two immediate retries for local SQS fan-out. */
    private static final int SQS_SUBSCRIPTION_DELIVERY_ATTEMPTS = 3;
    private static final String SUBSCRIPTION_REDRIVE_POLICY = "RedrivePolicy";
    /** Default value of the {@code MaximumMessageSize} topic attribute, and the ceiling below
     *  which a topic carries no subscription restrictions. AWS raised the maximum to 1 MiB in
     *  September 2026 but left the default at 256 KiB, so an unconfigured topic is unchanged. */
    public static final int DEFAULT_MAX_MESSAGE_SIZE = 262_144;
    private static final int MIN_MAX_MESSAGE_SIZE = 1_024;
    private static final int MAX_MAX_MESSAGE_SIZE = 1_048_576;
    /** The only protocols allowed on a topic above {@link #DEFAULT_MAX_MESSAGE_SIZE}. */
    private static final Set<String> LARGE_PAYLOAD_PROTOCOLS = Set.of("sqs", "firehose", "lambda");
    private static final String SUBSCRIPTION_ROLE_ARN = "SubscriptionRoleArn";
    private static final Pattern IAM_ROLE_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_/-]+");
    /** How many subscriptions a topic above {@link #DEFAULT_MAX_MESSAGE_SIZE} may carry. */
    private static final int LARGE_PAYLOAD_SUBSCRIPTION_LIMIT = 100;
    private static final String MAXIMUM_MESSAGE_SIZE = "MaximumMessageSize";
    private static final int PUSH_CAPTURE_LIMIT = 1000;
    private static final int MAX_SUBJECT_LENGTH = 100;
    private static final String CONTROL_TOWER_AGGREGATE_SECURITY_TOPIC =
            "aws-controltower-AggregateSecurityNotifications";
    private static final List<String> PENDING_CONFIRMATION_PROTOCOLS =
            List.of("http", "https", "email", "email-json", "sms");
    /** Mobile-push platforms Floci mocks. iOS and Android only — anything else is rejected. */
    private static final Set<String> SUPPORTED_PUSH_PLATFORMS = Set.of(
            "APNS", "APNS_SANDBOX", "GCM", "FCM");
    private static final Pattern PLATFORM_APP_NAME_PATTERN =
            Pattern.compile("[a-zA-Z0-9_.\\-]{1,256}");

    /**
     * Operator names that may not appear as a field name inside a {@code $or} array; their
     * presence is one of the conditions AWS uses to decide whether {@code $or} is an operator.
     */
    private static final Set<String> RESERVED_POLICY_KEYWORDS = Set.of(
            "$or", "anything-but", "exists", "prefix", "suffix", "numeric", "cidr",
            "equals-ignore-case", "wildcard");

    private final StorageBackend<String, Topic> topicStore;
    private final StorageBackend<String, Subscription> subscriptionStore;
    private final StorageBackend<String, PlatformApplication> platformAppStore;
    private final StorageBackend<String, PlatformEndpoint> platformEndpointStore;
    private final Deque<PushNotification> pushCapture = new ConcurrentLinkedDeque<>();
    private final StorageBackend<String, SentSms> smsStore;
    private final StorageBackend<String, Map<String, String>> smsAttributesStore;
    private final StorageBackend<String, SmsSandboxPhoneNumber> smsSandboxStore;
    private final SecureRandom random = new SecureRandom();
    private final RegionResolver regionResolver;
    private final SqsService sqsService;
    private final LambdaService lambdaService;
    private final FirehoseService firehoseService;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SnsMessageSigner messageSigner;
    private final Map<String, Instant> fifoDeduplicationCache = new ConcurrentHashMap<>();
    private static final HexFormat HEX = HexFormat.of();
    
    @Inject
    public SnsService(StorageFactory storageFactory, EmulatorConfig config,
                      RegionResolver regionResolver, SqsService sqsService,
                      LambdaService lambdaService, FirehoseService firehoseService,
                      ObjectMapper objectMapper, SnsMessageSigner messageSigner) {
        this(
                storageFactory.create("sns", "sns-topics.json",
                        new TypeReference<Map<String, Topic>>() {
                        }),
                storageFactory.create("sns", "sns-subscriptions.json",
                        new TypeReference<Map<String, Subscription>>() {
                        }),
                storageFactory.create("sns", "sns-platform-applications.json",
                        new TypeReference<Map<String, PlatformApplication>>() {
                        }),
                storageFactory.create("sns", "sns-platform-endpoints.json",
                        new TypeReference<Map<String, PlatformEndpoint>>() {
                        }),
                storageFactory.create("sns", "sns-sms.json",
                        new TypeReference<Map<String, SentSms>>() {
                        }),
                storageFactory.create("sns", "sns-sms-attributes.json",
                        new TypeReference<Map<String, Map<String, String>>>() {
                        }),
                storageFactory.create("sns", "sns-sms-sandbox.json",
                        new TypeReference<Map<String, SmsSandboxPhoneNumber>>() {
                        }),
                regionResolver,
                sqsService,
                lambdaService,
                firehoseService,
                config.effectiveBaseUrl(),
                objectMapper,
                messageSigner
        );
    }

    /**
     * Package-private constructor for testing (no HTTP client — HTTP delivery is skipped).
     */
    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService) {
        this(topicStore, subscriptionStore, regionResolver, sqsService, lambdaService, null);
    }

    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService,
               FirehoseService firehoseService) {
        this(topicStore, subscriptionStore,
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                regionResolver, sqsService, lambdaService, firehoseService);
    }

    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               StorageBackend<String, PlatformApplication> platformAppStore,
               StorageBackend<String, PlatformEndpoint> platformEndpointStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService) {
        this(topicStore, subscriptionStore, platformAppStore, platformEndpointStore,
                regionResolver, sqsService, lambdaService, null);
    }

    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               StorageBackend<String, PlatformApplication> platformAppStore,
               StorageBackend<String, PlatformEndpoint> platformEndpointStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService,
               FirehoseService firehoseService) {
        this.topicStore = topicStore;
        this.subscriptionStore = subscriptionStore;
        this.platformAppStore = platformAppStore;
        this.platformEndpointStore = platformEndpointStore;
        this.smsStore = new InMemoryStorage<>();
        this.smsAttributesStore = new InMemoryStorage<>();
        this.smsSandboxStore = new InMemoryStorage<>();
        this.regionResolver = regionResolver;
        this.sqsService = sqsService;
        this.lambdaService = lambdaService;
        this.firehoseService = firehoseService;
        this.baseUrl = "http://localhost:4566";
        this.objectMapper = new ObjectMapper();
        this.httpClient = null;
        this.messageSigner = SnsMessageSigner.inMemory(baseUrl);
    }

    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               StorageBackend<String, PlatformApplication> platformAppStore,
               StorageBackend<String, PlatformEndpoint> platformEndpointStore,
               StorageBackend<String, SentSms> smsStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService, String baseUrl, ObjectMapper objectMapper) {
        this(topicStore, subscriptionStore, platformAppStore, platformEndpointStore,
                smsStore, regionResolver, sqsService, lambdaService, null, baseUrl, objectMapper);
    }

    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               StorageBackend<String, PlatformApplication> platformAppStore,
               StorageBackend<String, PlatformEndpoint> platformEndpointStore,
               StorageBackend<String, SentSms> smsStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService, FirehoseService firehoseService,
               String baseUrl, ObjectMapper objectMapper) {
        this(topicStore, subscriptionStore, platformAppStore, platformEndpointStore,
                smsStore, new InMemoryStorage<>(), regionResolver, sqsService,
                lambdaService, firehoseService, baseUrl, objectMapper);
    }

    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               StorageBackend<String, PlatformApplication> platformAppStore,
               StorageBackend<String, PlatformEndpoint> platformEndpointStore,
               StorageBackend<String, SentSms> smsStore,
               StorageBackend<String, Map<String, String>> smsAttributesStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService, FirehoseService firehoseService,
               String baseUrl, ObjectMapper objectMapper) {
        this(topicStore, subscriptionStore, platformAppStore, platformEndpointStore, smsStore,
                smsAttributesStore, regionResolver, sqsService, lambdaService, firehoseService,
                baseUrl, objectMapper, SnsMessageSigner.inMemory(baseUrl));
    }

    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               StorageBackend<String, PlatformApplication> platformAppStore,
               StorageBackend<String, PlatformEndpoint> platformEndpointStore,
               StorageBackend<String, SentSms> smsStore,
               StorageBackend<String, Map<String, String>> smsAttributesStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService, FirehoseService firehoseService,
               String baseUrl, ObjectMapper objectMapper, SnsMessageSigner messageSigner) {
        this(topicStore, subscriptionStore, platformAppStore, platformEndpointStore, smsStore,
                smsAttributesStore, new InMemoryStorage<>(), regionResolver, sqsService,
                lambdaService, firehoseService, baseUrl, objectMapper, messageSigner);
    }

    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               StorageBackend<String, PlatformApplication> platformAppStore,
               StorageBackend<String, PlatformEndpoint> platformEndpointStore,
               StorageBackend<String, SentSms> smsStore,
               StorageBackend<String, Map<String, String>> smsAttributesStore,
               StorageBackend<String, SmsSandboxPhoneNumber> smsSandboxStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService, FirehoseService firehoseService,
               String baseUrl, ObjectMapper objectMapper, SnsMessageSigner messageSigner) {
        this.topicStore = topicStore;
        this.subscriptionStore = subscriptionStore;
        this.platformAppStore = platformAppStore;
        this.platformEndpointStore = platformEndpointStore;
        this.smsStore = smsStore;
        this.smsAttributesStore = smsAttributesStore;
        this.smsSandboxStore = smsSandboxStore;
        this.regionResolver = regionResolver;
        this.sqsService = sqsService;
        this.lambdaService = lambdaService;
        this.firehoseService = firehoseService;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.messageSigner = messageSigner;
    }

    public void clear() {
        pushCapture.clear();
        fifoDeduplicationCache.clear();
    }

    public synchronized void setSmsAttributes(Map<String, String> attributes, String region) {
        String key = smsAttributesKey(region);
        Map<String, String> updated = new LinkedHashMap<>(smsAttributesStore.get(key).orElse(Map.of()));
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (!SMS_ATTRIBUTE_NAMES.contains(name) || value == null) {
                throw new AwsException("InvalidParameter", "Invalid SMS attribute: " + name, 400);
            }
            if (value.isEmpty()) {
                updated.remove(name);
                continue;
            }
            if ("DefaultSMSType".equals(name)
                    && !"Promotional".equals(value) && !"Transactional".equals(value)) {
                throw new AwsException("InvalidParameter", "Invalid DefaultSMSType: " + value, 400);
            }
            if ("DefaultSenderID".equals(name) && !SMS_SENDER_ID.matcher(value).matches()) {
                throw new AwsException("InvalidParameter", "Invalid DefaultSenderID: " + value, 400);
            }
            if ("MonthlySpendLimit".equals(name)) {
                try {
                    if (new BigDecimal(value).signum() < 0) {
                        throw new NumberFormatException("negative amount");
                    }
                } catch (NumberFormatException e) {
                    throw new AwsException("InvalidParameter", "Invalid MonthlySpendLimit: " + value, 400);
                }
            }
            if ("DeliveryStatusSuccessSamplingRate".equals(name)) {
                try {
                    int rate = Integer.parseInt(value);
                    if (rate < 0 || rate > 100) {
                        throw new NumberFormatException("out of range");
                    }
                } catch (NumberFormatException e) {
                    throw new AwsException("InvalidParameter",
                            "Invalid DeliveryStatusSuccessSamplingRate: " + value, 400);
                }
            }
            updated.put(name, value);
        }
        smsAttributesStore.put(key, updated);
    }

    public Map<String, String> getSmsAttributes(List<String> names, String region) {
        String key = smsAttributesKey(region);
        Map<String, String> stored = smsAttributesStore.get(key).orElse(Map.of());
        if (names == null || names.isEmpty()) {
            return new LinkedHashMap<>(stored);
        }
        Map<String, String> selected = new LinkedHashMap<>();
        for (String name : names) {
            if (stored.containsKey(name)) {
                selected.put(name, stored.get(name));
            }
        }
        return selected;
    }

    /**
     * Whether the account is in the SMS sandbox, where AWS sends SMS only to verified numbers. A
     * new AWS account starts in it, but Floci delivers SMS to any number, so it answers as an
     * account that has left the sandbox, which is how it actually behaves. The sandbox numbers
     * below still work, without the limits AWS lifts on leaving: ten verified numbers, and no
     * deleting one until 24 hours after its last verification attempt.
     */
    public boolean isInSmsSandbox() {
        return false;
    }

    /**
     * Adds a destination number to the SMS sandbox and texts it a one-time password, which
     * {@code GET /_aws/sns?phone=} then shows, as it does any SMS. The text is Floci's; AWS does
     * not publish its own. A number already pending gets a fresh password, and one already
     * verified stays verified with nothing sent. If the text cannot be recorded, the number is
     * put back as it was, since a password nobody received could never verify it.
     *
     * <p>The sandbox edits are synchronized: each reads a number's record, changes it and writes
     * it back, so two overlapping requests could otherwise lose one's change, or a verification
     * could write back a number deleted meanwhile.
     */
    public synchronized void createSmsSandboxPhoneNumber(String phoneNumber, String languageCode, String region) {
        requireSandboxPhoneNumber(phoneNumber);
        if (languageCode != null && !SANDBOX_LANGUAGE_CODES.contains(languageCode)) {
            throw new AwsException("InvalidParameter", "Invalid parameter: LanguageCode", 400);
        }
        String key = smsSandboxKey(region, phoneNumber);
        SmsSandboxPhoneNumber previous = smsSandboxStore.get(key).orElse(null);
        if (previous != null && SmsSandboxPhoneNumber.VERIFIED.equals(previous.getStatus())) {
            return;
        }
        String oneTimePassword = String.format("%06d", random.nextInt(1_000_000));
        smsSandboxStore.put(key,
                new SmsSandboxPhoneNumber(phoneNumber, SmsSandboxPhoneNumber.PENDING, oneTimePassword));
        try {
            recordSms(phoneNumber, "Your verification code is " + oneTimePassword, null, region);
        } catch (RuntimeException e) {
            try {
                if (previous == null) {
                    smsSandboxStore.delete(key);
                } else {
                    smsSandboxStore.put(key, previous);
                }
            } catch (RuntimeException restoreFailure) {
                e.addSuppressed(restoreFailure);
            }
            throw e;
        }
    }

    /** Verifies a pending sandbox number with the password it was sent. A verified one stays so. */
    public synchronized void verifySmsSandboxPhoneNumber(String phoneNumber, String oneTimePassword, String region) {
        requireSandboxPhoneNumber(phoneNumber);
        if (oneTimePassword == null || !ONE_TIME_PASSWORD.matcher(oneTimePassword).matches()) {
            throw new AwsException("InvalidParameter", "Invalid parameter: OneTimePassword", 400);
        }
        String key = smsSandboxKey(region, phoneNumber);
        SmsSandboxPhoneNumber number = smsSandboxStore.get(key)
                .orElseThrow(SnsService::sandboxPhoneNumberNotFound);
        if (SmsSandboxPhoneNumber.VERIFIED.equals(number.getStatus())) {
            return;
        }
        if (!oneTimePassword.equals(number.getOneTimePassword())) {
            // Status is a required member of the error and AWS documents no values for it, so it carries
            // the number's own verification status, which a wrong password leaves Pending: the only
            // statuses SNS publishes for a sandbox number are Pending and Verified.
            throw new AwsException("VerificationException",
                    "The one-time password does not match the one sent to " + phoneNumber + ".", 400,
                    Map.of("Status", number.getStatus()));
        }
        number.setStatus(SmsSandboxPhoneNumber.VERIFIED);
        number.setOneTimePassword(null);
        smsSandboxStore.put(key, number);
    }

    public synchronized void deleteSmsSandboxPhoneNumber(String phoneNumber, String region) {
        requireSandboxPhoneNumber(phoneNumber);
        String key = smsSandboxKey(region, phoneNumber);
        if (smsSandboxStore.get(key).isEmpty()) {
            throw sandboxPhoneNumberNotFound();
        }
        smsSandboxStore.delete(key);
    }

    public PaginatedResult<SmsSandboxPhoneNumber> listSmsSandboxPhoneNumbers(Integer maxResults,
                                                                             String nextToken,
                                                                             String region) {
        String prefix = smsAttributesKey(region) + "::";
        return Pagination.paginate(smsSandboxStore.scan(key -> key.startsWith(prefix)),
                SmsSandboxPhoneNumber::getPhoneNumber, maxResults, nextToken, SANDBOX_PAGE_SIZE,
                "InvalidParameter");
    }

    /**
     * Whether a number has opted out of SMS from this account. AWS opts a number out when its
     * owner replies STOP, and nothing reaches Floci that way, so no number is ever opted out.
     */
    public boolean isPhoneNumberOptedOut(String phoneNumber) {
        requireE164PhoneNumber(phoneNumber);
        return false;
    }

    /** The numbers opted out of SMS from this account, which for the reason above is none. */
    public List<String> listPhoneNumbersOptedOut() {
        return List.of();
    }

    /**
     * Opts a number back in to SMS. None is ever opted out here, and AWS answers success for a
     * number that is not opted out, so only the format is checked. AWS's limit of one opt-in per
     * number every 30 days can therefore never apply.
     */
    public void optInPhoneNumber(String phoneNumber) {
        requireE164PhoneNumber(phoneNumber);
    }

    private static void requireE164PhoneNumber(String phoneNumber) {
        if (phoneNumber == null || !E164_PHONE_NUMBER.matcher(phoneNumber).matches()) {
            throw invalidPhoneNumber();
        }
    }

    private static void requireSandboxPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()
                || phoneNumber.length() > SANDBOX_PHONE_NUMBER_MAX_LENGTH
                || !SANDBOX_PHONE_NUMBER.matcher(phoneNumber).matches()) {
            throw invalidPhoneNumber();
        }
    }

    /** The answer AWS gives {@code OptInPhoneNumber} for a malformed number. */
    private static AwsException invalidPhoneNumber() {
        return new AwsException("InvalidParameter",
                "Invalid parameter: PhoneNumber Reason: input incorrectly formatted", 400);
    }

    private static AwsException sandboxPhoneNumberNotFound() {
        return new AwsException("ResourceNotFound",
                "The phone number is not in the SMS sandbox.", 404);
    }

    public Topic createTopic(String name, Map<String, String> attributes,
                             Map<String, String> tags, String region) {
        return createTopicWithStatus(name, attributes, tags, region).topic();
    }

    public record TopicCreation(Topic topic, boolean created) {
    }

    public synchronized TopicCreation createTopicWithStatus(String name, Map<String, String> attributes,
                                                            Map<String, String> tags, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameter", "Topic name is required.", 400);
        }
        if (attributes != null && attributes.containsKey(MAXIMUM_MESSAGE_SIZE)) {
            validateMaximumMessageSize(attributes.get(MAXIMUM_MESSAGE_SIZE), true);
        }
        String topicArn = regionResolver.buildArn("sns", region, name);
        String key = topicKey(region, topicArn);

        Topic existing = topicStore.get(key).orElse(null);
        if (existing != null) return new TopicCreation(existing, false);

        Topic topic = new Topic(name, topicArn);
        if (attributes != null) topic.getAttributes().putAll(attributes);
        if (tags != null) topic.getTags().putAll(tags);

        if (name.endsWith(".fifo")) {
            topic.getAttributes().put("FifoTopic", "true");
            if (attributes != null && attributes.containsKey("ContentBasedDeduplication") && "true".equals(attributes.get("ContentBasedDeduplication"))) {
                topic.getAttributes().putIfAbsent("ContentBasedDeduplication", "true");
            } else {
                topic.getAttributes().putIfAbsent("ContentBasedDeduplication", "false");
            }
        }

        topicStore.put(key, topic);
        LOG.infov("Created SNS topic: {0} in region {1}", name, region);
        return new TopicCreation(topic, true);
    }

    public void deleteTopic(String topicArn, String region) {
        String key = topicKey(region, topicArn);
        if (topicStore.get(key).isEmpty()) {
            throw new AwsException("NotFound", "Topic does not exist.", 404);
        }
        List<String> toDelete = new ArrayList<>();
        String subPrefix = "sub::" + region + "::";
        for (String subKey : subscriptionStore.keys()) {
            if (subKey.startsWith(subPrefix)) {
                subscriptionStore.get(subKey).ifPresent(sub -> {
                    if (topicArn.equals(sub.getTopicArn())) toDelete.add(subKey);
                });
            }
        }
        toDelete.forEach(subscriptionStore::delete);
        topicStore.delete(key);
        LOG.infov("Deleted SNS topic: {0}", topicArn);
    }

    public List<Topic> listTopics(String region) {
        String prefix = "topic::" + region + "::";
        return topicStore.scan(k -> k.startsWith(prefix));
    }

    public boolean topicExists(String topicArn, String region) {
        if (topicStore instanceof AccountAwareStorageBackend<Topic> aware) {
            String accountId = AwsArnUtils.accountOrDefault(topicArn, regionResolver.getAccountId());
            return aware.getForAccountMigratingLegacy(accountId, topicKey(region, topicArn),
                    topic -> topicArn.equals(topic.getTopicArn())).isPresent();
        }
        return topicStore.get(topicKey(region, topicArn)).isPresent();
    }

    public Map<String, String> getTopicAttributes(String topicArn, String region) {
        String key = topicKey(region, topicArn);
        Topic topic = topicStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound", "Topic does not exist.", 404));
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>(topic.getAttributes());
        List<Subscription> subs = subscriptionsByTopic(topicArn, region);
        long confirmed = subs.stream()
                .filter(s -> !"true".equals(s.getAttributes().get("PendingConfirmation")))
                .count();
        long pending = subs.stream()
                .filter(s -> "true".equals(s.getAttributes().get("PendingConfirmation")))
                .count();
        attrs.put("SubscriptionsConfirmed", String.valueOf(confirmed));
        attrs.put("SubscriptionsPending", String.valueOf(pending));
        attrs.put("SubscriptionsDeleted", "0");
        attrs.put("TopicArn", topicArn);
        attrs.putIfAbsent("DisplayName", "");
        attrs.putIfAbsent("Owner", regionResolver.getAccountId());
        attrs.putIfAbsent("EffectiveDeliveryPolicy", "{\"http\":{\"defaultHealthyRetryPolicy\":{\"minDelayTarget\":20,\"maxDelayTarget\":20,\"numRetries\":3,\"numMaxDelayRetries\":0,\"numNoDelayRetries\":0,\"numMinDelayRetries\":0,\"backoffFunction\":\"linear\"},\"disableSubscriptionOverrides\":false}}");
        if (!attrs.containsKey("Policy") || attrs.get("Policy") == null || attrs.get("Policy").isBlank()) {
            attrs.put("Policy", defaultTopicPolicy(topicArn, regionResolver.getAccountId()).toString());
        }
        return attrs;
    }

    /**
     * The access policy of the topic an ARN names, read in the account and Region the ARN names
     * rather than the caller's: its {@code Policy} attribute, or the default a new topic has when
     * that is not set. Empty when there is no such topic.
     */
    public Optional<String> findTopicPolicy(String topicArn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(topicArn);
        String key = topicKey(arn.region(), topicArn);
        Optional<Topic> topic = topicStore instanceof AccountAwareStorageBackend<Topic> aware
                ? aware.getForAccountMigratingLegacy(arn.accountId(), key,
                        stored -> topicArn.equals(stored.getTopicArn()))
                : topicStore.get(key);
        return topic.map(stored -> {
            String policy = stored.getAttributes().get("Policy");
            return policy == null || policy.isBlank()
                    ? defaultTopicPolicy(topicArn, arn.accountId()).toString()
                    : policy;
        });
    }

    /**
     * The policy a topic has until one is set, as AWS returns it for a new topic: its owner may
     * manage it, subscribe to it and publish to it.
     */
    private ObjectNode defaultTopicPolicy(String topicArn, String ownerAccountId) {
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("Version", "2008-10-17");
        policy.put("Id", "__default_policy_ID");
        ObjectNode statement = policy.putArray("Statement").addObject();
        statement.put("Sid", "__default_statement_ID");
        statement.put("Effect", "Allow");
        statement.putObject("Principal").put("AWS", "*");
        ArrayNode actions = statement.putArray("Action");
        DEFAULT_TOPIC_POLICY_ACTIONS.forEach(actions::add);
        statement.put("Resource", topicArn);
        statement.putObject("Condition").putObject("StringEquals")
                .put("AWS:SourceOwner", ownerAccountId);
        return policy;
    }

    public synchronized void setTopicAttributes(String topicArn, String attributeName,
                                                String attributeValue, String region) {
        String key = topicKey(region, topicArn);
        Topic topic = topicStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound", "Topic does not exist.", 404));
        if (MAXIMUM_MESSAGE_SIZE.equals(attributeName)
                && validateMaximumMessageSize(attributeValue, false) > DEFAULT_MAX_MESSAGE_SIZE) {
            requireLargePayloadSubscriptions(topicArn, region);
        }
        topic.getAttributes().put(attributeName, attributeValue);
        topicStore.put(key, topic);
    }

    /**
     * Adds a statement to the topic's policy granting {@code accountIds} the named SNS actions,
     * written as AWS writes it: the label is its {@code Sid}, each account becomes its
     * {@code root} principal, and a single account or action is a string rather than a list of
     * one. A topic with no policy set gets the statement added to its default one.
     *
     * <p>The policy is stored and returned by {@code GetTopicAttributes}. Floci does not evaluate
     * topic policies when authorizing a request, so the grant has no effect beyond that.
     *
     * <p>This, {@link #removePermission} and {@link #setTopicAttributes} are synchronized: each
     * reads the policy, changes it and writes it back, so overlapping edits would otherwise lose
     * all but the last, and two grants under one label could both pass the duplicate check.
     */
    public synchronized void addPermission(String topicArn, String label, List<String> accountIds,
                                           List<String> actionNames, String region) {
        requirePermissionParameter("TopicArn", topicArn != null && !topicArn.isBlank());
        requirePermissionParameter("Label", label != null && !label.isBlank());
        requirePermissionParameter("AWSAccountId", accountIds != null && !accountIds.isEmpty()
                && accountIds.stream().allMatch(id -> id != null && ACCOUNT_ID.matcher(id).matches()));
        requirePermissionParameter("ActionName", actionNames != null && !actionNames.isEmpty());
        String key = topicKey(region, topicArn);
        Topic topic = topicStore.get(key).orElseThrow(SnsService::permissionTopicNotFound);
        for (String action : actionNames) {
            if (!SNS_POLICY_ACTIONS.contains(action)) {
                throw new AwsException("InvalidParameter",
                        "Invalid parameter: Policy statement action out of service scope!", 400);
            }
        }
        ObjectNode policy = topicPolicy(topic, topicArn);
        ArrayNode statements = (ArrayNode) policy.get("Statement");
        for (JsonNode existing : statements) {
            if (label.equals(existing.path("Sid").asText(null))) {
                throw new AwsException("InvalidParameter", "Invalid parameter: Statement already exists", 400);
            }
        }
        String partition = AwsRegions.partitionFor(region);
        ObjectNode statement = statements.addObject();
        statement.put("Sid", label);
        statement.put("Effect", "Allow");
        putOneOrMany(statement.putObject("Principal"), "AWS", accountIds.stream()
                .map(accountId -> AwsArnUtils.Arn.global(partition, "iam", accountId, "root").toString())
                .toList());
        putOneOrMany(statement, "Action", actionNames.stream().map(action -> "SNS:" + action).toList());
        statement.put("Resource", topicArn);
        topic.getAttributes().put("Policy", policy.toString());
        topicStore.put(key, topic);
    }

    /**
     * Removes the statement {@code label} names from the topic's policy. A label the policy does
     * not hold leaves it as it is: AWS's reference names no error for that case and no recording
     * shows one, so this answers success rather than invent one.
     */
    public synchronized void removePermission(String topicArn, String label, String region) {
        requirePermissionParameter("TopicArn", topicArn != null && !topicArn.isBlank());
        requirePermissionParameter("Label", label != null && !label.isBlank());
        String key = topicKey(region, topicArn);
        Topic topic = topicStore.get(key).orElseThrow(SnsService::permissionTopicNotFound);
        ObjectNode policy = topicPolicy(topic, topicArn);
        ArrayNode statements = (ArrayNode) policy.get("Statement");
        boolean removed = false;
        for (int i = statements.size() - 1; i >= 0; i--) {
            if (label.equals(statements.get(i).path("Sid").asText(null))) {
                statements.remove(i);
                removed = true;
            }
        }
        if (removed) {
            topic.getAttributes().put("Policy", policy.toString());
            topicStore.put(key, topic);
        }
    }

    /** The topic's policy as a document to edit, the default one if none is set, with its statements in a list. */
    private ObjectNode topicPolicy(Topic topic, String topicArn) {
        String stored = topic.getAttributes().get("Policy");
        if (stored == null || stored.isBlank()) {
            return defaultTopicPolicy(topicArn, regionResolver.getAccountId());
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(stored);
        } catch (JsonProcessingException e) {
            parsed = null;
        }
        if (!(parsed instanceof ObjectNode policy)) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Policy Error: the topic's policy is not a JSON object", 400);
        }
        JsonNode statement = policy.get("Statement");
        if (!(statement instanceof ArrayNode)) {
            ArrayNode statements = objectMapper.createArrayNode();
            if (statement != null && !statement.isNull()) {
                statements.add(statement);
            }
            policy.set("Statement", statements);
        }
        return policy;
    }

    private static void putOneOrMany(ObjectNode node, String field, List<String> values) {
        if (values.size() == 1) {
            node.put(field, values.get(0));
        } else {
            values.forEach(node.putArray(field)::add);
        }
    }

    private static void requirePermissionParameter(String name, boolean present) {
        if (!present) {
            throw new AwsException("InvalidParameter", "Invalid parameter: " + name, 400);
        }
    }

    /** What AWS answers {@code AddPermission} and {@code RemovePermission} for a missing topic. */
    private static AwsException permissionTopicNotFound() {
        return new AwsException("NotFound", "Topic does not exist", 404);
    }

    /**
     * Parse and range-check a {@code MaximumMessageSize} attribute value, returning it. AWS
     * parses strictly: surrounding whitespace, a decimal point and an empty value are all
     * rejected rather than trimmed, coerced or treated as a reset -- which is where this
     * differs from the SQS attribute of the same name. {@code CreateTopic} reports the same
     * reason behind its own prefix.
     */
    private static int validateMaximumMessageSize(String value, boolean onCreate) {
        int parsed = -1;
        if (value != null) {
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                parsed = -1;
            }
        }
        if (parsed < MIN_MAX_MESSAGE_SIZE || parsed > MAX_MAX_MESSAGE_SIZE) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: " + (onCreate ? "Attributes Reason: " : "")
                            + MAXIMUM_MESSAGE_SIZE + ": " + (value == null ? "" : value)
                            + " is not an integer between " + MIN_MAX_MESSAGE_SIZE + " and "
                            + MAX_MAX_MESSAGE_SIZE + " bytes", 400);
        }
        return parsed;
    }

    /**
     * Enforce what AWS asks of a topic carrying payloads above 256 KiB: at most 100
     * subscriptions, every one of them Amazon SQS, Amazon Data Firehose or Lambda. Pending
     * confirmations count towards both. Only the protocol half is also checked on Subscribe --
     * AWS lets the count drift past 100 once the attribute is already raised, and catches it
     * the next time the attribute is set.
     */
    private void requireLargePayloadSubscriptions(String topicArn, String region) {
        List<Subscription> subs = subscriptionsByTopic(topicArn, region);
        for (Subscription sub : subs) {
            requireLargePayloadProtocol(sub.getProtocol());
        }
        if (subs.size() > LARGE_PAYLOAD_SUBSCRIPTION_LIMIT) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: A topic with " + MAXIMUM_MESSAGE_SIZE + " greater than "
                            + DEFAULT_MAX_MESSAGE_SIZE + " bytes supports a maximum of "
                            + LARGE_PAYLOAD_SUBSCRIPTION_LIMIT + " subscriptions", 400);
        }
    }

    private static void requireLargePayloadProtocol(String protocol) {
        if (protocol != null && LARGE_PAYLOAD_PROTOCOLS.contains(protocol)) {
            return;
        }
        throw new AwsException("InvalidParameter",
                "Invalid parameter: " + MAXIMUM_MESSAGE_SIZE + " greater than "
                        + DEFAULT_MAX_MESSAGE_SIZE
                        + " bytes is not supported for the following protocol: ["
                        + protocol + "]", 400);
    }

    /** The topic's configured {@code MaximumMessageSize}, or the AWS default when unset. */
    private int topicMaxMessageSize(String topicArn, String region) {
        return topicStore.get(topicKey(region, topicArn))
                .map(SnsService::maxMessageSize)
                .orElse(DEFAULT_MAX_MESSAGE_SIZE);
    }

    /**
     * The read path is deliberately more forgiving than {@link #validateMaximumMessageSize}: a
     * topic persisted before the attribute was known holds whatever the old generic setter
     * accepted, and an out-of-range value there is as unusable as a nonnumeric one. Above the
     * AWS ceiling it would wave a publish past the limit AWS enforces; at zero or below it
     * would reject every publish and silently gate subscriptions. Both fall back to the
     * default rather than being honoured.
     */
    private static int maxMessageSize(Topic topic) {
        String value = topic.getAttributes().get(MAXIMUM_MESSAGE_SIZE);
        if (value == null) {
            return DEFAULT_MAX_MESSAGE_SIZE;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= MIN_MAX_MESSAGE_SIZE && parsed <= MAX_MAX_MESSAGE_SIZE) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Not a number at all: same treatment as out of range, handled below.
        }
        return DEFAULT_MAX_MESSAGE_SIZE;
    }

    private static void requireWithinMaxMessageSize(int payloadSize, int maxMessageSize) {
        if (payloadSize > maxMessageSize) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Message too long", 400);
        }
    }

    public Subscription subscribe(String topicArn, String protocol, String endpoint, String region,
                                  Map<String, String> attributes) {
        return subscribeWithStatus(topicArn, protocol, endpoint, region, attributes).subscription();
    }

    public record SubscriptionCreation(Subscription subscription, boolean created) {
    }

    public SubscriptionCreation subscribeWithStatus(String topicArn, String protocol,
                                                    String endpoint, String region,
                                                    Map<String, String> attributes) {
        String topicKey = topicKey(region, topicArn);
        if (topicStore.get(topicKey).isEmpty() && !ensureControlTowerManagedTopic(topicArn, region)) {
            throw new AwsException("NotFound", "Topic does not exist.", 404);
        }
        if (protocol == null || protocol.isBlank()) {
            throw new AwsException("InvalidParameter", "Protocol is required.", 400);
        }
        if (("http".equals(protocol) && endpoint != null && !endpoint.startsWith("http://"))
                || ("https".equals(protocol) && endpoint != null && !endpoint.startsWith("https://"))) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Endpoint scheme does not match protocol '" + protocol + "'.", 400);
        }
        if ("http".equals(protocol) || "https".equals(protocol)) {
            requireDeliverableEndpoint(endpoint);
        }
        if ("firehose".equals(protocol)) {
            requireFirehoseRoleArn(attributes == null ? null : attributes.get(SUBSCRIPTION_ROLE_ARN));
        }
        if (topicMaxMessageSize(topicArn, region) > DEFAULT_MAX_MESSAGE_SIZE) {
            requireLargePayloadProtocol(protocol);
        }

        Subscription subscription;
        synchronized (this) {
            for (Subscription existing : subscriptionsByTopic(topicArn, region)) {
                if (protocol.equals(existing.getProtocol())
                        && Objects.equals(endpoint, existing.getEndpoint())) {
                    return new SubscriptionCreation(existing, false);
                }
            }

            String subscriptionArn = topicArn + ":" + UUID.randomUUID().toString();
            subscription = new Subscription(subscriptionArn, topicArn, protocol, endpoint,
                    regionResolver.getAccountId());
            subscription.setAccountId(regionResolver.getAccountId());
            if (attributes != null) subscription.getAttributes().putAll(attributes);

            if (PENDING_CONFIRMATION_PROTOCOLS.contains(protocol)) {
                String token = UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", "");
                subscription.getAttributes().put("PendingConfirmation", "true");
                subscription.getAttributes().put("ConfirmationToken", token);
                LOG.infov("Subscription pending confirmation for {0} ({1}) to topic {2} in {3}",
                        endpoint, protocol, topicArn, region);
            }

            subscriptionStore.put(subKey(region, subscriptionArn), subscription);
        }
        if (attributes == null || attributes.isEmpty()) {
            LOG.infov("Subscribed {0} ({1}) to topic {2} in {3}", endpoint, protocol, topicArn, region);
        } else {
            LOG.infov("Subscribed {0} ({1}) to topic {2} in {3} with attributes: {4}", endpoint, protocol, topicArn, region, attributes);
        }

        if (("http".equals(protocol) || "https".equals(protocol)) && endpoint != null) {
            sendSubscriptionConfirmation(subscription, topicArn, region);
        }

        return new SubscriptionCreation(subscription, true);
    }

    private boolean ensureControlTowerManagedTopic(String topicArn, String region) {
        String expectedArn = regionResolver.buildArn(
                "sns", region, CONTROL_TOWER_AGGREGATE_SECURITY_TOPIC);
        if (!expectedArn.equals(topicArn)) {
            return false;
        }
        createTopic(CONTROL_TOWER_AGGREGATE_SECURITY_TOPIC, Map.of(), Map.of(), region);
        return true;
    }

    public String confirmSubscription(String topicArn, String token, String region) {
        if (topicStore.get(topicKey(region, topicArn)).isEmpty()) {
            throw new AwsException("NotFound", "Topic does not exist.", 404);
        }
        String subPrefix = "sub::" + region + "::";
        for (String subKey : subscriptionStore.keys()) {
            if (!subKey.startsWith(subPrefix)) continue;
            Subscription sub = subscriptionStore.get(subKey).orElse(null);
            if (sub == null || !topicArn.equals(sub.getTopicArn())) continue;
            if (token.equals(sub.getAttributes().get("ConfirmationToken"))) {
                sub.getAttributes().put("PendingConfirmation", "false");
                sub.getAttributes().remove("ConfirmationToken");
                subscriptionStore.put(subKey, sub);
                LOG.infov("Confirmed subscription {0} for topic {1}", sub.getSubscriptionArn(), topicArn);
                return sub.getSubscriptionArn();
            }
        }
        throw new AwsException("AuthorizationError", "Token is invalid for this topic.", 403);
    }

    public void unsubscribe(String subscriptionArn, String region) {
        String key = subKey(region, subscriptionArn);
        if (subscriptionStore.get(key).isEmpty()) {
            throw new AwsException("NotFound", "Subscription does not exist.", 404);
        }
        subscriptionStore.delete(key);
        LOG.infov("Unsubscribed: {0}", subscriptionArn);
    }

    public List<Subscription> listSubscriptions(String region) {
        String prefix = "sub::" + region + "::";
        return subscriptionStore.scan(k -> k.startsWith(prefix));
    }

    public List<Subscription> listSubscriptionsByTopic(String topicArn, String region) {
        return subscriptionsByTopic(topicArn, region);
    }

    // Since this method is called by S3 and EventBridge, this doesn't need "phoneNumber" parameter.
    public String publish(String topicArn, String targetArn, String message,
                          String subject, String region) {
        return publish(topicArn, targetArn, null, message, subject, null, null, null, region);
    }

    public String publish(String topicArn, String targetArn, String phoneNumber, String message,
                          String subject, Map<String, MessageAttributeValue> messageAttributes, String region) {
        return publish(topicArn, targetArn, phoneNumber, message, subject, messageAttributes, null, null, region);
    }

    public String publish(String topicArn, String targetArn, String phoneNumber, String message,
                          String subject, Map<String, MessageAttributeValue> messageAttributes,
                          String messageGroupId, String messageDeduplicationId, String region) {
        return publish(topicArn, targetArn, phoneNumber, message, subject, null,
                messageAttributes, messageGroupId, messageDeduplicationId, region);
    }

    public String publish(String topicArn, String targetArn, String phoneNumber, String message,
                          String subject, String messageStructure,
                          Map<String, MessageAttributeValue> messageAttributes,
                          String messageGroupId, String messageDeduplicationId, String region) {
        int messageBytes = message == null ? 0 : message.getBytes(StandardCharsets.UTF_8).length;
        // The limit is a per-topic attribute, so it cannot be applied until the topic is in
        // hand. SMS and mobile-push publishes never reach a topic and keep the AWS default.
        int payloadSize = computePublishSize(messageBytes, messageAttributes);
        validateSubject(subject);

        // Send SMS
        if (phoneNumber != null) {
            requireWithinMaxMessageSize(payloadSize, DEFAULT_MAX_MESSAGE_SIZE);
            return recordSms(phoneNumber, message, subject, region);
        }

        // Send a message to topic or directly to a target ARN
        String effectiveArn = topicArn != null ? topicArn : targetArn;
        if (effectiveArn == null) {
            throw new AwsException("InvalidParameter", "TopicArn or TargetArn is required.", 400);
        }
        if (message == null || message.isBlank()) {
            throw new AwsException("InvalidParameter", "Message is required.", 400);
        }

        if (isEndpointArn(effectiveArn)) {
            requireWithinMaxMessageSize(payloadSize, DEFAULT_MAX_MESSAGE_SIZE);
            return publishToEndpoint(effectiveArn, message, subject, messageStructure,
                    messageAttributes, region);
        }
        if (isPlatformApplicationArn(effectiveArn)) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: TargetArn Reason: Cannot publish directly to a platform application ARN.",
                    400);
        }

        String topicStoreKey = topicKey(region, effectiveArn);
        Topic topic = topicStore.get(topicStoreKey)
                .orElseThrow(() -> new AwsException("NotFound", "Topic does not exist.", 404));

        requireWithinMaxMessageSize(payloadSize, maxMessageSize(topic));

        validateTopicMessageStructure(message, messageStructure);

        boolean isFifo = "true".equals(topic.getAttributes().get("FifoTopic"));
        String dedupId = messageDeduplicationId;
        if (isFifo) {
            if (messageGroupId == null || messageGroupId.isBlank()) {
                throw new AwsException("InvalidParameter",
                        "MessageGroupId is required for FIFO topics.", 400);
            }
            if (dedupId == null && "true".equals(topic.getAttributes().get("ContentBasedDeduplication"))) {
                dedupId = Digests.sha256Hex(message);
            }
            if (dedupId != null && isDuplicate(effectiveArn, messageGroupId, dedupId,
                    isGroupScopedDeduplication(topic))) {
                LOG.debugv("FIFO dedup: skipping duplicate for topic {0}, dedupId {1}", effectiveArn, dedupId);
                return UUID.randomUUID().toString();
            }
        }

        String messageId = UUID.randomUUID().toString();
        JsonNode parsedBody = null;
        boolean bodyParseAttempted = false;
        for (Subscription sub : subscriptionsByTopic(effectiveArn, region)) {
            if ("true".equals(sub.getAttributes().get("PendingConfirmation"))) {
                LOG.debugv("Skipping delivery to pending subscription {0}", sub.getSubscriptionArn());
                continue;
            }
            if (!bodyParseAttempted && isMessageBodyScope(sub)) {
                parsedBody = tryParseBody(message);
                bodyParseAttempted = true;
            }
            if (!matchesFilterPolicy(sub, parsedBody, messageAttributes)) {
                continue;
            }
            deliverMessage(sub, message, subject, messageAttributes, messageId, effectiveArn, messageGroupId, dedupId, messageStructure,
                    signatureVersion(topic));
        }
        LOG.infov("Published message {0} to topic {1}", messageId, effectiveArn);
        return messageId;
    }

    // --- Mobile push (iOS / Android) ---

    public PlatformApplication createPlatformApplication(String name, String platform,
                                                          Map<String, String> attributes, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameter", "Name is required.", 400);
        }
        if (!PLATFORM_APP_NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Name Reason: must be made up of only uppercase and"
                            + " lowercase ASCII letters, numbers, underscores, hyphens, and"
                            + " periods, and must be between 1 and 256 characters long.",
                    400);
        }
        if (platform == null || !SUPPORTED_PUSH_PLATFORMS.contains(platform)) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Platform Reason: Floci mocks only APNS, APNS_SANDBOX, GCM, and FCM.",
                    400);
        }
        String arn = platformApplicationArn(region, platform, name);
        String key = platformAppKey(region, arn);

        PlatformApplication existing = platformAppStore.get(key).orElse(null);
        if (existing != null) return existing;

        PlatformApplication app = new PlatformApplication(name, arn, platform);
        if (attributes != null) app.getAttributes().putAll(attributes);
        platformAppStore.put(key, app);
        LOG.infov("Created SNS platform application: {0} ({1}) in {2}", name, platform, region);
        return app;
    }

    public void deletePlatformApplication(String arn, String region) {
        String key = platformAppKey(region, arn);
        if (platformAppStore.get(key).isEmpty()) {
            return; // AWS treats DeletePlatformApplication as idempotent
        }
        String endpointPrefix = "endpoint::" + region + "::";
        List<String> toDelete = new ArrayList<>();
        for (String endpointKey : platformEndpointStore.keys()) {
            if (!endpointKey.startsWith(endpointPrefix)) continue;
            platformEndpointStore.get(endpointKey).ifPresent(ep -> {
                if (arn.equals(ep.getPlatformApplicationArn())) toDelete.add(endpointKey);
            });
        }
        toDelete.forEach(platformEndpointStore::delete);
        platformAppStore.delete(key);
        LOG.infov("Deleted SNS platform application: {0}", arn);
    }

    public Map<String, String> getPlatformApplicationAttributes(String arn, String region) {
        String key = platformAppKey(region, arn);
        PlatformApplication app = platformAppStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound",
                        "PlatformApplication does not exist.", 404));
        return new LinkedHashMap<>(app.getAttributes());
    }

    public void setPlatformApplicationAttributes(String arn, Map<String, String> attributes, String region) {
        String key = platformAppKey(region, arn);
        PlatformApplication app = platformAppStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound",
                        "PlatformApplication does not exist.", 404));
        if (attributes != null) app.getAttributes().putAll(attributes);
        platformAppStore.put(key, app);
    }

    public List<PlatformApplication> listPlatformApplications(String region) {
        String prefix = "app::" + region + "::";
        return platformAppStore.scan(k -> k.startsWith(prefix));
    }

    public PlatformEndpoint createPlatformEndpoint(String platformApplicationArn, String token,
                                                   String customUserData, Map<String, String> attributes,
                                                   String region) {
        if (token == null || token.isBlank()) {
            throw new AwsException("InvalidParameter", "Token is required.", 400);
        }
        String appKey = platformAppKey(region, platformApplicationArn);
        PlatformApplication app = platformAppStore.get(appKey)
                .orElseThrow(() -> new AwsException("NotFound",
                        "PlatformApplication does not exist.", 404));
        if ("false".equalsIgnoreCase(app.getAttributes().get("Enabled"))) {
            throw new AwsException("PlatformApplicationDisabled",
                    "Platform application is disabled.", 400);
        }

        String endpointPrefix = "endpoint::" + region + "::";
        for (String key : platformEndpointStore.keys()) {
            if (!key.startsWith(endpointPrefix)) continue;
            PlatformEndpoint existing = platformEndpointStore.get(key).orElse(null);
            if (existing == null) continue;
            if (!platformApplicationArn.equals(existing.getPlatformApplicationArn())) continue;
            if (!token.equals(existing.getToken())) continue;
            // AWS idempotency: same token + same attributes -> return existing; otherwise reject.
            String existingUserData = existing.getAttributes().getOrDefault("CustomUserData", "");
            String newUserData = customUserData != null ? customUserData : "";
            boolean sameAttrs = existingUserData.equals(newUserData)
                    && (attributes == null || matchesExistingAttributes(existing, attributes));
            if (sameAttrs) return existing;
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Token Reason: Endpoint " + existing.getArn()
                            + " already exists with the same Token, but different attributes.",
                    400);
        }

        String endpointArn = platformEndpointArn(region, app.getPlatform(), app.getName());
        PlatformEndpoint endpoint = new PlatformEndpoint(endpointArn, platformApplicationArn, token);
        if (customUserData != null) endpoint.getAttributes().put("CustomUserData", customUserData);
        if (attributes != null) endpoint.getAttributes().putAll(attributes);
        if (isExpiredSentinelToken(token)) {
            // Fast-path for tests: the token is "already expired" — AWS would normally only
            // mark Enabled=false after an async APNS/FCM failure; Floci does it on create.
            endpoint.getAttributes().put("Enabled", "false");
            LOG.infov("Endpoint {0} created with Enabled=false (token matches expired sentinel)", endpointArn);
        }
        platformEndpointStore.put(endpointKey(region, endpointArn), endpoint);
        LOG.infov("Created SNS platform endpoint: {0} for application {1}", endpointArn, platformApplicationArn);
        return endpoint;
    }

    public void deleteEndpoint(String endpointArn, String region) {
        // AWS treats DeleteEndpoint as idempotent — no error if missing.
        platformEndpointStore.delete(endpointKey(region, endpointArn));
        LOG.infov("Deleted SNS platform endpoint: {0}", endpointArn);
    }

    public Map<String, String> getEndpointAttributes(String endpointArn, String region) {
        PlatformEndpoint endpoint = platformEndpointStore.get(endpointKey(region, endpointArn))
                .orElseThrow(() -> new AwsException("NotFound", "Endpoint does not exist.", 404));
        return new LinkedHashMap<>(endpoint.getAttributes());
    }

    public void setEndpointAttributes(String endpointArn, Map<String, String> attributes, String region) {
        String key = endpointKey(region, endpointArn);
        PlatformEndpoint endpoint = platformEndpointStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound", "Endpoint does not exist.", 404));
        if (attributes != null) {
            endpoint.getAttributes().putAll(attributes);
            String newToken = attributes.get("Token");
            if (newToken != null) endpoint.setToken(newToken);
        }
        platformEndpointStore.put(key, endpoint);
    }

    public List<PlatformEndpoint> listEndpointsByPlatformApplication(String platformApplicationArn, String region) {
        String prefix = "endpoint::" + region + "::";
        List<PlatformEndpoint> result = new ArrayList<>();
        for (String key : platformEndpointStore.keys()) {
            if (!key.startsWith(prefix)) continue;
            platformEndpointStore.get(key).ifPresent(ep -> {
                if (platformApplicationArn.equals(ep.getPlatformApplicationArn())) result.add(ep);
            });
        }
        return result;
    }

    /** Returns the most recent captured pushes (newest first), optionally filtered by endpoint ARN. */
    public List<PushNotification> peekPushNotifications(String endpointArn) {
        List<PushNotification> out = new ArrayList<>();
        for (PushNotification p : pushCapture) {
            if (endpointArn != null && !endpointArn.equals(p.endpointArn())) continue;
            out.add(p);
        }
        return out;
    }

    public void clearPushNotifications() {
        pushCapture.clear();
    }

    private String publishToEndpoint(String endpointArn, String message, String subject,
                                     String messageStructure,
                                     Map<String, MessageAttributeValue> messageAttributes,
                                     String region) {
        PlatformEndpoint endpoint = platformEndpointStore.get(endpointKey(region, endpointArn))
                .orElseThrow(() -> new AwsException("NotFound", "Endpoint does not exist.", 404));
        if (!"true".equalsIgnoreCase(endpoint.getAttributes().getOrDefault("Enabled", "true"))) {
            throw new AwsException("EndpointDisabled",
                    "Endpoint " + endpointArn + " disabled", 400);
        }
        PlatformApplication app = platformAppStore.get(platformAppKey(region, endpoint.getPlatformApplicationArn()))
                .orElseThrow(() -> new AwsException("NotFound",
                        "PlatformApplication does not exist.", 404));
        if ("false".equalsIgnoreCase(app.getAttributes().get("Enabled"))) {
            throw new AwsException("PlatformApplicationDisabled",
                    "Platform application is disabled.", 400);
        }
        return capturePushForEndpoint(endpoint, app, message, subject, messageStructure, messageAttributes);
    }

    /**
     * Resolves the platform payload and records a captured push for the given endpoint/app.
     * Shared by direct-to-endpoint {@code Publish} and topic fan-out to {@code application}
     * subscriptions, so broadcast pushes surface identically via the retrospection API.
     */
    private String capturePushForEndpoint(PlatformEndpoint endpoint, PlatformApplication app,
                                          String message, String subject, String messageStructure,
                                          Map<String, MessageAttributeValue> messageAttributes) {
        String payload = resolveProtocolPayload(app.getPlatform(), message, messageStructure);
        String messageId = UUID.randomUUID().toString();
        recordPushNotification(new PushNotification(
                endpoint.getArn(), app.getArn(), app.getPlatform(), endpoint.getToken(),
                payload, subject, messageAttributes, messageId, Instant.now()));
        LOG.infov("Captured push notification {0} -> {1} ({2})", messageId, endpoint.getArn(), app.getPlatform());
        return messageId;
    }

    /**
     * Resolves the payload SNS would forward to a given delivery target.
     * If {@code messageStructure="json"}, pick the target-specific key from the JSON envelope,
     * falling back to {@code default}. The key is the push platform for mobile endpoints
     * ({@code APNS}, {@code APNS_SANDBOX}, {@code GCM}, {@code FCM}) and the subscription
     * protocol for every other subscriber ({@code sqs}, {@code lambda}, {@code http},
     * {@code https}, {@code email}, {@code email-json}, {@code sms}).
     * Otherwise return the raw message.
     */
    private String resolveProtocolPayload(String protocol, String message, String messageStructure) {
        if (messageStructure == null || !"json".equals(messageStructure)) {
            return message;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (Exception e) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Message Reason: Message is not valid JSON.", 400);
        }
        if (!root.isObject()) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Message Reason: Messages must be a JSON object.",
                    400);
        }
        // Real SNS ignores a key whose value isn't a string ("Non-string values will cause the
        // key to be ignored"), falling through to default rather than stringifying it.
        JsonNode protocolValue = root.get(protocol);
        if (protocolValue != null && protocolValue.isTextual()) {
            return protocolValue.asText();
        }
        JsonNode defaultValue = root.get("default");
        if (defaultValue != null && defaultValue.isTextual()) {
            return defaultValue.asText();
        }
        throw new AwsException("InvalidParameter",
                "Invalid parameter: Message Reason: Messages must have a '" + protocol
                        + "' or 'default' key.",
                400);
    }

    /**
     * Validates {@code MessageStructure="json"} on a topic {@code Publish} the way the real SNS
     * API does: synchronously, before any fan-out. The message must be a JSON object carrying a
     * top-level {@code default} entry. Validating here (rather than lazily inside per-subscriber
     * delivery) means a malformed envelope surfaces to the caller as {@code InvalidParameter}
     * instead of being silently swallowed while the {@code publish} call still reports success.
     * {@code PublishBatch} runs the same check per entry, reporting a bad envelope as that entry's
     * {@code BatchResultErrorEntry} rather than failing the whole batch.
     */
    private void validateTopicMessageStructure(String message, String messageStructure) {
        if (!"json".equals(messageStructure)) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (Exception e) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Message Structure - JSON message body failed to parse", 400);
        }
        if (root == null || !root.isObject()) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Message Structure - JSON message body should be an object.", 400);
        }
        JsonNode defaultValue = root.get("default");
        if (defaultValue == null || !defaultValue.isTextual()) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Message Structure - No default entry in JSON message body", 400);
        }
    }

    /**
     * Subjects must be UTF-8 text with no line breaks or control characters and at most 100
     * characters long. A null or empty subject is the absent optional parameter and is accepted.
     */
    static void validateSubject(String subject) {
        if (subject == null || subject.isEmpty()) {
            return;
        }
        if (subject.length() > MAX_SUBJECT_LENGTH
                || subject.chars().anyMatch(Character::isISOControl)) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Subject Reason: Subjects must be UTF-8 text with no line"
                            + " breaks or control characters, and at most 100 characters long.",
                    400);
        }
    }

    private void recordPushNotification(PushNotification notification) {
        pushCapture.addFirst(notification);
        while (pushCapture.size() > PUSH_CAPTURE_LIMIT) {
            pushCapture.pollLast();
        }
    }

    private boolean matchesExistingAttributes(PlatformEndpoint existing, Map<String, String> requested) {
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String existingValue = existing.getAttributes().get(entry.getKey());
            if (existingValue == null) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) return false;
            } else if (!existingValue.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExpiredSentinelToken(String token) {
        return token != null && token.toUpperCase(Locale.ROOT).contains("EXPIRED");
    }

    private static boolean isEndpointArn(String arn) {
        return arn != null && arn.contains(":endpoint/");
    }

    private static boolean isPlatformApplicationArn(String arn) {
        return arn != null && arn.contains(":app/");
    }

    private String platformApplicationArn(String region, String platform, String name) {
        return regionResolver.buildArn("sns", region, "app/" + platform + "/" + name);
    }

    private String platformEndpointArn(String region, String platform, String appName) {
        return regionResolver.buildArn("sns", region,
                "endpoint/" + platform + "/" + appName + "/" + UUID.randomUUID());
    }

    private static String platformAppKey(String region, String arn) {
        return "app::" + region + "::" + arn;
    }

    private static String endpointKey(String region, String arn) {
        return "endpoint::" + region + "::" + arn;
    }

    public Map<String, String> getSubscriptionAttributes(String subscriptionArn, String region) {
        String key = subKey(region, subscriptionArn);
        Subscription sub = subscriptionStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound", "Subscription does not exist.", 404));
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>(sub.getAttributes());
        attrs.put("SubscriptionArn", sub.getSubscriptionArn());
        attrs.put("TopicArn", sub.getTopicArn());
        attrs.put("Protocol", sub.getProtocol());
        attrs.put("Endpoint", sub.getEndpoint() != null ? sub.getEndpoint() : "");
        attrs.put("Owner", sub.getOwner() != null ? sub.getOwner() : "");
        attrs.put("RawMessageDelivery", attrs.getOrDefault("RawMessageDelivery", "false"));
        attrs.putIfAbsent("PendingConfirmation", "false");
        attrs.putIfAbsent("ConfirmationWasAuthenticated", "false");
        // AWS reports FilterPolicyScope, stored or default, only alongside a filter policy.
        String filterPolicy = attrs.get("FilterPolicy");
        if (filterPolicy == null || filterPolicy.isEmpty()) {
            attrs.remove("FilterPolicyScope");
        } else {
            attrs.putIfAbsent("FilterPolicyScope", "MessageAttributes");
        }
        attrs.remove("ConfirmationToken");
        return attrs;
    }

    public void setSubscriptionAttribute(String subscriptionArn, String attributeName,
                                         String attributeValue, String region) {
        String key = subKey(region, subscriptionArn);
        Subscription sub = subscriptionStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound", "Subscription does not exist.", 404));
        if ("firehose".equals(sub.getProtocol()) && SUBSCRIPTION_ROLE_ARN.equals(attributeName)) {
            requireFirehoseRoleArn(attributeValue);
        }
        sub.getAttributes().put(attributeName, attributeValue);
        subscriptionStore.put(key, sub);
    }

    private static void requireFirehoseRoleArn(String roleArn) {
        if (roleArn == null || !IAM_ROLE_ARN.matcher(roleArn).matches()) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: SubscriptionRoleArn must be a valid IAM role ARN for firehose subscriptions.",
                    400);
        }
    }

    public record BatchPublishResult(List<String[]> successful, List<String[]> failed) {
    }

    public BatchPublishResult publishBatch(String topicArn, List<Map<String, Object>> entries, String region) {
        String topicStoreKey = topicKey(region, topicArn);
        Topic topic = topicStore.get(topicStoreKey)
                .orElseThrow(() -> new AwsException("NotFound", "Topic does not exist.", 404));

        int batchSize = 0;
        for (Map<String, Object> entry : entries) {
            String message = (String) entry.get("Message");
            @SuppressWarnings("unchecked")
            Map<String, MessageAttributeValue> attrs =
                    (Map<String, MessageAttributeValue>) entry.get("MessageAttributes");
            int entryMessageBytes = message == null ? 0 : message.getBytes(StandardCharsets.UTF_8).length;
            batchSize += computePublishSize(entryMessageBytes, attrs);
        }
        if (batchSize > maxMessageSize(topic)) {
            throw new AwsException("BatchRequestTooLong",
                    "The length of all the messages put together is more than the limit.", 400);
        }

        boolean isFifo = "true".equals(topic.getAttributes().get("FifoTopic"));
        boolean groupScopedDedup = isGroupScopedDeduplication(topic);
        List<String[]> successful = new ArrayList<>();
        List<String[]> failed = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String id = (String) entry.get("Id");
            String message = (String) entry.get("Message");
            if (message == null || message.isBlank()) {
                failed.add(new String[]{id, "InvalidParameter", "Message is required.", "true"});
                continue;
            }
            String subject = (String) entry.get("Subject");
            String messageStructure = (String) entry.get("MessageStructure");
            String messageGroupId = (String) entry.get("MessageGroupId");
            String messageDeduplicationId = (String) entry.get("MessageDeduplicationId");

            try {
                validateSubject(subject);
                validateTopicMessageStructure(message, messageStructure);
            } catch (AwsException e) {
                failed.add(new String[]{id, e.getErrorCode(), e.getMessage(), "true"});
                continue;
            }

            if (isFifo && (messageGroupId == null || messageGroupId.isBlank())) {
                failed.add(new String[]{id, "InvalidParameter",
                        "MessageGroupId is required for FIFO topics.", "true"});
                continue;
            }
            // Derive deduplication ID if ContentBasedDeduplication is enabled and not provided
            if (isFifo && messageDeduplicationId == null && "true".equals(topic.getAttributes().get("ContentBasedDeduplication"))) {
                messageDeduplicationId = Digests.sha256Hex(message);
            }
            if (isFifo && messageDeduplicationId != null && isDuplicate(topicArn, messageGroupId,
                    messageDeduplicationId, groupScopedDedup)) {
                successful.add(new String[]{id, UUID.randomUUID().toString()});
                continue;
            }

            String messageId = UUID.randomUUID().toString();
            @SuppressWarnings("unchecked")
            Map<String, MessageAttributeValue> attrs = (Map<String, MessageAttributeValue>) entry.get("MessageAttributes");
            JsonNode parsedBody = null;
            boolean bodyParseAttempted = false;
            for (Subscription sub : subscriptionsByTopic(topicArn, region)) {
                if ("true".equals(sub.getAttributes().get("PendingConfirmation"))) continue;
                if (!bodyParseAttempted && isMessageBodyScope(sub)) {
                    parsedBody = tryParseBody(message);
                    bodyParseAttempted = true;
                }
                if (!matchesFilterPolicy(sub, parsedBody, attrs)) continue;
                deliverMessage(sub, message, subject, attrs, messageId, topicArn, messageGroupId, messageDeduplicationId, messageStructure,
                        signatureVersion(topic));
            }
            LOG.debugv("Batch published message {0} (id={1}) to {2}", messageId, id, topicArn);
            successful.add(new String[]{id, messageId});
        }
        return new BatchPublishResult(successful, failed);
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        String key = topicKey(region, resourceArn);
        Topic topic = topicStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource does not exist.", 404));
        if (tags != null) topic.getTags().putAll(tags);
        topicStore.put(key, topic);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        String key = topicKey(region, resourceArn);
        Topic topic = topicStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource does not exist.", 404));
        if (tagKeys != null) tagKeys.forEach(topic.getTags()::remove);
        topicStore.put(key, topic);
    }

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        String key = topicKey(region, resourceArn);
        Topic topic = topicStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource does not exist.", 404));
        return new LinkedHashMap<>(topic.getTags());
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Topic topic : topicStore.scan(k -> true)) {
            String arn = topic.getTopicArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "sns:topic", "sns",
                    parsed.region(), parsed.accountId(),
                    topic.getCreatedAt() != null ? topic.getCreatedAt() : Instant.now(),
                    topic.getTags() != null ? topic.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("sns:topic", "sns", true));
    }

    /**
     * Evaluates whether a message satisfies the subscription's filter policy.
     * Returns {@code true} if no filter policy is set.
     * Returns {@code false} for malformed filter policies (fail closed).
     * <p>
     * {@code FilterPolicyScope=MessageAttributes} (default) evaluates the policy against
     * the message attribute map. {@code FilterPolicyScope=MessageBody} parses the message
     * body as JSON and evaluates the policy against the parsed tree (with nested-key
     * descent and type-aware matching). A body that is not valid JSON fails closed.
     * <p>
     * All keys in the policy must match (AND logic). Within each key's rule array,
     * any matching element is sufficient (OR logic).
     */
    private static boolean isMessageBodyScope(Subscription sub) {
        return "MessageBody".equals(
                sub.getAttributes().getOrDefault("FilterPolicyScope", "MessageAttributes"));
    }

    /**
     * Per AWS SNS docs, {@code exists:true} requires the key to have a non-null and
     * non-empty value. Empty strings, empty arrays, and empty objects do not count as
     * "exists". Numbers and booleans are always considered non-empty.
     */
    private static boolean isNonEmptyValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return !node.asText().isEmpty();
        }
        if (node.isContainerNode()) {
            return !node.isEmpty();
        }
        return true;
    }

    private JsonNode tryParseBody(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(message);
        } catch (Exception e) {
            return null;
        }
    }

    boolean matchesFilterPolicy(Subscription sub, JsonNode parsedBody,
                                Map<String, MessageAttributeValue> messageAttributes) {
        String filterPolicyJson = sub.getAttributes().get("FilterPolicy");
        if (filterPolicyJson == null || filterPolicyJson.isBlank()) {
            return true;
        }
        String scope = sub.getAttributes().getOrDefault("FilterPolicyScope", "MessageAttributes");
        try {
            JsonNode filterPolicy = objectMapper.readTree(filterPolicyJson);
            if (!filterPolicy.isObject()) {
                LOG.warnv("Invalid FilterPolicy (not a JSON object) for {0}", sub.getSubscriptionArn());
                return false;
            }
            if ("MessageBody".equals(scope)) {
                if (parsedBody == null) {
                    LOG.debugv("FilterPolicyScope=MessageBody but body is not valid JSON for {0}; not delivering",
                            sub.getSubscriptionArn());
                    return false;
                }
                return matchesBodyPolicy(filterPolicy, parsedBody);
            }
            return matchesAttributePolicy(filterPolicy,
                    messageAttributes != null ? messageAttributes : Map.of());
        } catch (Exception e) {
            LOG.warnv("Failed to parse filter policy for {0}: {1}", sub.getSubscriptionArn(), e.getMessage());
            return false;
        }
    }

    /**
     * Evaluates a filter policy object against a parsed JSON body. Each policy key must
     * match (AND); a key's value is either a rule array (terminal) or an object (nested
     * descent into the body at the same key).
     */
    private boolean matchesBodyPolicy(JsonNode policy, JsonNode body) {
        if (!policy.isObject()) {
            return false;
        }
        if (body != null && body.isArray()) {
            for (JsonNode element : body) {
                if (matchesBodyPolicy(policy, element)) {
                    return true;
                }
            }
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = policy.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode ruleOrNested = entry.getValue();
            if (isOrOperator(key, ruleOrNested)) {
                if (!anyClauseMatches(ruleOrNested, clause -> matchesBodyPolicy(clause, body))) {
                    return false;
                }
                continue;
            }
            JsonNode bodyValue = (body != null && body.isObject()) ? body.get(key) : null;
            if (ruleOrNested.isArray()) {
                if (!matchesBodyRules(bodyValue, ruleOrNested)) {
                    return false;
                }
            } else if (ruleOrNested.isObject()) {
                if (!matchesBodyPolicy(ruleOrNested, bodyValue)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates a rule array against a body value. When the body value is itself an array,
     * the rules are applied to each element (OR over elements), with {@code exists:true}
     * additionally matching the array as a whole.
     */
    private boolean matchesBodyRules(JsonNode actual, JsonNode rules) {
        if (!rules.isArray()) {
            return false;
        }
        if (actual != null && actual.isArray()) {
            boolean nonEmpty = !actual.isEmpty();
            for (JsonNode rule : rules) {
                if (rule.isObject() && rule.has("exists")) {
                    if (rule.get("exists").asBoolean() == nonEmpty) {
                        return true;
                    }
                } else {
                    for (JsonNode element : actual) {
                        if (matchesSingleBodyRule(element, rule)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        for (JsonNode rule : rules) {
            if (matchesSingleBodyRule(actual, rule)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSingleBodyRule(JsonNode actual, JsonNode rule) {
        if (rule.isTextual()) {
            return actual != null && actual.isTextual() && rule.asText().equals(actual.asText());
        }
        if (rule.isNumber()) {
            if (actual == null || !actual.isNumber()) {
                return false;
            }
            try {
                return new BigDecimal(actual.asText()).compareTo(rule.decimalValue()) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (rule.isBoolean()) {
            return actual != null && actual.isBoolean() && rule.booleanValue() == actual.booleanValue();
        }
        if (rule.isNull()) {
            return actual != null && actual.isNull();
        }
        if (rule.isObject()) {
            return matchesObjectRuleForNode(rule, actual);
        }
        return false;
    }

    private boolean matchesObjectRuleForNode(JsonNode rule, JsonNode actual) {
        if (rule.has("exists")) {
            return rule.get("exists").asBoolean() == isNonEmptyValue(actual);
        }
        boolean present = actual != null && !actual.isMissingNode() && !actual.isNull();
        String stringValue = present && actual.isTextual() ? actual.asText() : null;
        if (rule.has("prefix")) {
            return stringValue != null && stringValue.startsWith(rule.get("prefix").asText());
        }
        if (rule.has("suffix")) {
            return matchesStringOperator("suffix", rule.get("suffix"), stringValue);
        }
        if (rule.has("equals-ignore-case")) {
            return matchesStringOperator("equals-ignore-case", rule.get("equals-ignore-case"), stringValue);
        }
        if (rule.has("wildcard")) {
            return matchesStringOperator("wildcard", rule.get("wildcard"), stringValue);
        }
        if (rule.has("cidr")) {
            return matchesStringOperator("cidr", rule.get("cidr"), stringValue);
        }
        if (rule.has("anything-but")) {
            JsonNode anythingBut = rule.get("anything-but");
            if (anythingBut.isObject()) {
                // A missing or JSON-null property does not match, even when the nested operator
                // would also reject it. A present value matches only when that operator does not.
                return present && excludesNestedOperator(anythingBut, actual, stringValue, false);
            }
            return matchesAnythingButForNode(anythingBut, actual, stringValue, present);
        }
        if (rule.has("numeric") && present && actual.isNumber()) {
            return evaluateNumericCondition(actual.decimalValue(), rule.get("numeric")).orElse(false);
        }
        return false;
    }

    private boolean matchesAnythingButForNode(JsonNode anythingBut, JsonNode actual,
                                              String stringValue, boolean present) {
        if (!present) {
            return false;
        }
        if (anythingBut.isArray()) {
            for (JsonNode v : anythingBut) {
                if (v.isTextual() && stringValue != null && v.asText().equals(stringValue)) {
                    return false;
                }
                if (v.isNumber() && actual.isNumber()
                        && actual.decimalValue().compareTo(v.decimalValue()) == 0) {
                    return false;
                }
            }
            return true;
        }
        if (anythingBut.isTextual()) {
            return stringValue == null || !stringValue.equals(anythingBut.asText());
        }
        return false;
    }

    /**
     * Evaluates a filter policy against the message attribute map. Every key must match (AND),
     * except {@code $or}, whose clauses are alternatives (OR) that still AND with their siblings.
     */
    private boolean matchesAttributePolicy(JsonNode policy, Map<String, MessageAttributeValue> attrs) {
        if (!policy.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = policy.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode rules = entry.getValue();
            if (isOrOperator(key, rules)) {
                if (!anyClauseMatches(rules, clause -> matchesAttributePolicy(clause, attrs))) {
                    return false;
                }
                continue;
            }
            if (!matchesAttribute(attrs.get(key), rules)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies a rule array to one message attribute. A {@code String.Array} attribute carries a
     * JSON array in its StringValue and AWS matches each element separately, so a positive rule
     * passes when any element matches. {@code anything-but} inverts that: it passes only when no
     * element is listed. {@code exists} asks about the attribute itself, not about its elements.
     */
    private boolean matchesAttribute(MessageAttributeValue attr, JsonNode rules) {
        if (!rules.isArray()) {
            return false;
        }
        List<String> elements = stringArrayElements(attr);
        if (elements == null) {
            return matchesAttributeRules(attr != null ? attr.getStringValue() : null, rules);
        }
        for (JsonNode rule : rules) {
            if (rule.isObject() && rule.has("exists")) {
                if (rule.get("exists").asBoolean()) {
                    return true;
                }
                continue;
            }
            if (rule.isObject() && rule.has("anything-but")) {
                if (noElementIsListed(rule, elements)) {
                    return true;
                }
                continue;
            }
            for (String element : elements) {
                if (matchesSingleAttributeRule(element, rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when no element of a {@code String.Array} is named by an {@code anything-but} rule,
     * which is what AWS requires for that rule to match. A JSON null element names nothing, so
     * it cannot veto the match. Note {@link #matchesObjectRule} returns true for a value that is
     * NOT listed, so a false from it is the element that vetoes.
     */
    private boolean noElementIsListed(JsonNode rule, List<String> elements) {
        for (String element : elements) {
            if (element != null && !matchesObjectRule(rule, element)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the elements of a {@code String.Array} attribute, or {@code null} when the attribute
     * is not one -- including when its StringValue does not hold a JSON array. AWS rejects that at
     * publish time; here the value falls back to being matched whole.
     */
    private List<String> stringArrayElements(MessageAttributeValue attr) {
        if (attr == null || !"String.Array".equals(attr.getDataType())
                || attr.getStringValue() == null) {
            return null;
        }
        try {
            // FAIL_ON_TRAILING_TOKENS matters here: without it a value of "[\"a\"] junk" parses as
            // ["a"] and element-matches, instead of falling back to being matched whole.
            JsonNode parsed = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(attr.getStringValue());
            if (!parsed.isArray()) {
                return null;
            }
            List<String> elements = new ArrayList<>(parsed.size());
            for (JsonNode element : parsed) {
                elements.add(element.isNull() ? null : element.asText());
            }
            return elements;
        } catch (Exception e) {
            LOG.debugv("String.Array attribute is not a JSON array, matching it whole: {0}",
                    attr.getStringValue());
            return null;
        }
    }

    /**
     * Checks if an attribute value matches a single filter policy rule set.
     * Rules must be a JSON array where ANY element matching means the rule passes (OR logic).
     * Non-array rules are treated as non-matching.
     */
    private boolean matchesAttributeRules(String actualValue, JsonNode rules) {
        if (!rules.isArray()) {
            return false;
        }
        for (JsonNode rule : rules) {
            if (matchesSingleAttributeRule(actualValue, rule)) {
                return true;
            }
        }
        return false;
    }

    /** Evaluates one rule out of a rule array against a single string value. */
    private boolean matchesSingleAttributeRule(String actualValue, JsonNode rule) {
        if (rule.isTextual()) {
            return rule.asText().equals(actualValue);
        }
        if (rule.isNumber() && actualValue != null) {
            try {
                return new BigDecimal(actualValue).compareTo(rule.decimalValue()) == 0;
            } catch (NumberFormatException ignored) {
                // A non-numeric attribute simply does not match a numeric rule.
                return false;
            }
        }
        if (rule.isObject()) {
            return matchesObjectRule(rule, actualValue);
        }
        return false;
    }

    /** True when at least one clause of a {@code $or} array matches. */
    private boolean anyClauseMatches(JsonNode clauses, Predicate<JsonNode> matches) {
        for (JsonNode clause : clauses) {
            if (matches.test(clause)) {
                return true;
            }
        }
        return false;
    }

    /**
     * AWS reads {@code $or} as an operator only when its value is an array holding at least two
     * objects, none of which use a reserved operator name as a field name. Anything else is an
     * ordinary key named {@code $or} and is matched as one.
     */
    private static boolean isOrOperator(String key, JsonNode value) {
        if (!"$or".equals(key) || value == null || !value.isArray() || value.size() < 2) {
            return false;
        }
        for (JsonNode clause : value) {
            if (!clause.isObject()) {
                return false;
            }
            Iterator<String> names = clause.fieldNames();
            while (names.hasNext()) {
                if (RESERVED_POLICY_KEYWORDS.contains(names.next())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Evaluates a single object-type filter rule (exists, prefix, suffix, equals-ignore-case,
     * wildcard, cidr, anything-but, numeric) against the actual attribute value.
     */
    private boolean matchesObjectRule(JsonNode rule, String actualValue) {
        if (rule.has("exists")) {
            boolean shouldExist = rule.get("exists").asBoolean();
            return shouldExist ? actualValue != null : actualValue == null;
        }
        if (rule.has("prefix") && actualValue != null) {
            return actualValue.startsWith(rule.get("prefix").asText());
        }
        if (rule.has("suffix")) {
            return matchesStringOperator("suffix", rule.get("suffix"), actualValue);
        }
        if (rule.has("equals-ignore-case")) {
            return matchesStringOperator("equals-ignore-case", rule.get("equals-ignore-case"), actualValue);
        }
        if (rule.has("wildcard")) {
            return matchesStringOperator("wildcard", rule.get("wildcard"), actualValue);
        }
        if (rule.has("cidr")) {
            return matchesStringOperator("cidr", rule.get("cidr"), actualValue);
        }
        if (rule.has("anything-but") && actualValue != null) {
            JsonNode anythingBut = rule.get("anything-but");
            if (anythingBut.isObject()) {
                return excludesNestedOperator(anythingBut, null, actualValue, true);
            }
            return !containsValue(anythingBut, actualValue);
        }
        if (rule.has("numeric") && actualValue != null) {
            try {
                return evaluateNumericCondition(new BigDecimal(actualValue), rule.get("numeric")).orElse(false);
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    /**
     * suffix, equals-ignore-case, wildcard, and cidr apply to a string the way prefix does.
     * The operand must be textual; numbers and objects are not coerced with {@code asText()}.
     * A null value does not match.
     */
    private static boolean matchesStringOperator(String operator, JsonNode operand, String value) {
        if (value == null || operand == null || !operand.isTextual()) {
            return false;
        }
        String expected = operand.asText();
        return switch (operator) {
            case "prefix" -> value.startsWith(expected);
            case "suffix" -> value.endsWith(expected);
            case "equals-ignore-case" -> value.equalsIgnoreCase(expected);
            case "wildcard" -> wildcardMatches(expected, value);
            case "cidr" -> cidrContains(expected, value);
            default -> false;
        };
    }

    /**
     * {@code anything-but} of exactly one nested positive operator. A present value matches only
     * when that operator does not. An empty object, an unknown key, more than one key, or a
     * malformed operand fails closed. Does not recurse into {@code anything-but} or {@code exists}.
     *
     * @param attributeValue when true, {@code stringValue} is the attribute and {@code numeric}
     *                       parses it; otherwise {@code numeric} matches only a JSON number
     */
    private boolean excludesNestedOperator(JsonNode operator, JsonNode actual, String stringValue,
                                           boolean attributeValue) {
        if (operator.size() != 1) {
            return false;
        }
        String name = operator.fieldNames().next();
        JsonNode operand = operator.get(name);
        if ("prefix".equals(name) || "suffix".equals(name) || "equals-ignore-case".equals(name)
                || "wildcard".equals(name) || "cidr".equals(name)) {
            if (operand == null || !operand.isTextual()) {
                return false;
            }
            return !matchesStringOperator(name, operand, stringValue);
        }
        if (!"numeric".equals(name)) {
            return false;
        }
        BigDecimal number = null;
        try {
            if (attributeValue) {
                if (stringValue != null) {
                    number = new BigDecimal(stringValue);
                }
            } else if (actual != null && actual.isNumber()) {
                number = actual.decimalValue();
            }
        } catch (NumberFormatException ignored) {
        }
        Optional<Boolean> matched = evaluateNumericCondition(number, operand);
        return matched.isPresent() && !matched.get();
    }

    /**
     * Anchored, case-sensitive glob. Each {@code *} matches any run of characters, including an
     * empty one, and every other character matches only itself. This is a scan, not a regular
     * expression, so {@code .} and other metacharacters stay literal.
     */
    private static boolean wildcardMatches(String pattern, String value) {
        int patternIndex = 0;
        int valueIndex = 0;
        int starPattern = -1;
        int starValue = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                starPattern = patternIndex++;
                starValue = valueIndex;
            } else if (patternIndex < pattern.length()
                    && pattern.charAt(patternIndex) == value.charAt(valueIndex)) {
                patternIndex++;
                valueIndex++;
            } else if (starPattern >= 0) {
                patternIndex = starPattern + 1;
                valueIndex = ++starValue;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }

    /**
     * True when {@code value} is a literal IP in the same address family as {@code cidr} and the
     * two share the prefix bits. Host bits in the CIDR address are masked off. A zone id, a prefix
     * outside the address width, a family mismatch, or any non-address returns false. The value is
     * never name-resolved.
     */
    private static boolean cidrContains(String cidr, String value) {
        if (cidr == null || value == null) {
            return false;
        }
        int slash = cidr.indexOf('/');
        if (slash <= 0 || slash != cidr.lastIndexOf('/')) {
            return false;
        }
        String prefixText = cidr.substring(slash + 1);
        if (!isDecimalPrefix(prefixText)) {
            return false;
        }
        byte[] network = parseIpLiteral(cidr.substring(0, slash));
        byte[] address = parseIpLiteral(value);
        if (network == null || address == null || network.length != address.length) {
            return false;
        }
        int prefix = Integer.parseInt(prefixText);
        if (prefix > network.length * 8) {
            return false;
        }
        return sameNetwork(network, address, prefix);
    }

    private static boolean isDecimalPrefix(String text) {
        if (text.isEmpty() || text.length() > 3) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** A literal IPv4 or IPv6 address, or null when {@code text} is not one. */
    private static byte[] parseIpLiteral(String text) {
        if (text.indexOf(':') >= 0) {
            return parseIpv6Literal(text);
        }
        return parseIpv4Literal(text);
    }

    private static byte[] parseIpv4Literal(String text) {
        if (text.isEmpty() || text.indexOf('%') >= 0 || text.indexOf('/') >= 0) {
            return null;
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] address = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                return null;
            }
            int octet = 0;
            for (int c = 0; c < part.length(); c++) {
                char ch = part.charAt(c);
                if (ch < '0' || ch > '9') {
                    return null;
                }
                octet = octet * 10 + (ch - '0');
            }
            if (octet > 255) {
                return null;
            }
            address[i] = (byte) octet;
        }
        return address;
    }

    private static byte[] parseIpv6Literal(String text) {
        if (text.isEmpty() || text.indexOf('%') >= 0 || text.indexOf('/') >= 0) {
            return null;
        }
        String address = text;
        if (text.indexOf('.') >= 0) {
            int lastColon = text.lastIndexOf(':');
            if (lastColon < 0) {
                return null;
            }
            byte[] v4 = parseIpv4Literal(text.substring(lastColon + 1));
            if (v4 == null) {
                return null;
            }
            String high = Integer.toHexString(((v4[0] & 0xff) << 8) | (v4[1] & 0xff));
            String low = Integer.toHexString(((v4[2] & 0xff) << 8) | (v4[3] & 0xff));
            address = text.substring(0, lastColon + 1) + high + ":" + low;
        }
        int compress = address.indexOf("::");
        if (compress != address.lastIndexOf("::")) {
            return null;
        }
        String left = compress < 0 ? address : address.substring(0, compress);
        String right = compress < 0 ? null : address.substring(compress + 2);
        int[] leftGroups = parseHextets(left);
        int[] rightGroups = right == null ? null : parseHextets(right);
        if (leftGroups == null || (right != null && rightGroups == null)) {
            return null;
        }
        int rightCount = rightGroups == null ? 0 : rightGroups.length;
        int total = leftGroups.length + rightCount;
        if (rightGroups == null ? total != 8 : total > 7) {
            return null;
        }
        byte[] out = new byte[16];
        int index = writeHextets(out, 0, leftGroups);
        if (rightGroups != null) {
            writeHextets(out, index + (8 - total) * 2, rightGroups);
        }
        return out;
    }

    /** Hextets of a colon-separated piece, or null when a group is empty or not 1-4 hex digits. */
    private static int[] parseHextets(String part) {
        if (part.isEmpty()) {
            return new int[0];
        }
        String[] groups = part.split(":", -1);
        int[] values = new int[groups.length];
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            if (group.isEmpty() || group.length() > 4) {
                return null;
            }
            int value = 0;
            for (int c = 0; c < group.length(); c++) {
                int digit = Character.digit(group.charAt(c), 16);
                if (digit < 0) {
                    return null;
                }
                value = (value << 4) | digit;
            }
            values[i] = value;
        }
        return values;
    }

    private static int writeHextets(byte[] out, int index, int[] groups) {
        for (int group : groups) {
            out[index++] = (byte) (group >> 8);
            out[index++] = (byte) group;
        }
        return index;
    }

    private static boolean sameNetwork(byte[] network, byte[] address, int prefixBits) {
        int fullBytes = prefixBits / 8;
        int remainder = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != address[i]) {
                return false;
            }
        }
        if (remainder == 0) {
            return true;
        }
        int mask = (0xff << (8 - remainder)) & 0xff;
        return (network[fullBytes] & mask) == (address[fullBytes] & mask);
    }

    private boolean containsValue(JsonNode node, String value) {
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (element.asText().equals(value)) return true;
            }
            return false;
        }
        LOG.warnv("FilterPolicy 'anything-but' expected an array but got a scalar; treating as single-value list");
        return node.asText().equals(value);
    }

    /**
     * Evaluates a numeric condition array against a value.
     * The conditions array contains alternating operator-target pairs (e.g. [">=", 100, "<", 200]).
     * All pairs must match for the condition to pass (AND logic). Empty when the operand is not
     * comparator/number pairs, so a caller does not negate a malformed condition. A null value
     * misses a well-formed condition.
     */
    private Optional<Boolean> evaluateNumericCondition(BigDecimal value, JsonNode conditions) {
        if (conditions == null || !conditions.isArray() || conditions.size() % 2 != 0) {
            return Optional.empty();
        }
        boolean missed = value == null;
        for (int i = 0; i < conditions.size(); i += 2) {
            JsonNode op = conditions.get(i);
            JsonNode target = conditions.get(i + 1);
            if (!op.isTextual() || !target.isNumber()) {
                return Optional.empty();
            }
            int cmp = value == null ? 0 : value.compareTo(target.decimalValue());
            Boolean matches = switch (op.asText()) {
                case "=" -> cmp == 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                default -> null;
            };
            if (matches == null) {
                return Optional.empty();
            }
            if (!matches) {
                missed = true;
            }
        }
        return Optional.of(!missed);
    }

    /**
     * {@code MessageGroup} narrows deduplication to a single message group. {@code Topic}, the AWS
     * default, keeps it topic-wide.
     */
    private static boolean isGroupScopedDeduplication(Topic topic) {
        return "MessageGroup".equalsIgnoreCase(topic.getAttributes().get("FifoThroughputScope"));
    }

    private boolean isDuplicate(String topicArn, String messageGroupId, String deduplicationId,
                                boolean groupScoped) {
        // The scope is part of the key: the attribute can change inside the deduplication window,
        // and a topic-scoped id must never land on a group-scoped entry. The group is
        // length-prefixed because nothing validates the characters in either id.
        String scopedId = groupScoped
                ? "group:" + messageGroupId.length() + ":" + messageGroupId + deduplicationId
                : "topic:" + deduplicationId;
        String cacheKey = topicArn + ":" + scopedId;
        Instant now = Instant.now();
        Instant existing = fifoDeduplicationCache.get(cacheKey);
        if (existing != null && existing.plus(FIFO_DEDUP_WINDOW).isAfter(now)) {
            return true;
        }
        fifoDeduplicationCache.put(cacheKey, now);
        fifoDeduplicationCache.entrySet().removeIf(e -> e.getValue().plus(FIFO_DEDUP_WINDOW).isBefore(now));
        return false;
    }

    /**
     * Removes all FIFO deduplication cache entries for SNS topics that have an SQS subscription
     * whose endpoint resolves to the same queue path as {@code queueUrl} (used when purging SQS
     * with {@code clearFifoDeduplicationCacheOnPurge}).
     */
    public void clearFifoDeduplicationCacheForSqsQueueSubscriptions(String queueUrl, String region) {
        String queuePath = extractQueuePathFromUrl(queueUrl);
        if (queuePath.isEmpty()) {
            return;
        }
        String subPrefix = "sub::" + region + "::";
        subscriptionStore.keys().stream()
                .filter(key -> key.startsWith(subPrefix))
                .map(key -> subscriptionStore.get(key).orElse(null))
                .filter(Objects::nonNull)
                .filter(sub -> "sqs".equals(sub.getProtocol()))
                .filter(sub -> sqsSubscriptionEndpointMatchesQueuePath(sub.getEndpoint(), queuePath))
                .map(Subscription::getTopicArn)
                .forEach(topicArn ->
                        fifoDeduplicationCache.keySet().removeIf(cacheKey -> cacheKey.startsWith(topicArn + ":")));
    }

    private boolean sqsSubscriptionEndpointMatchesQueuePath(String endpoint, String queuePath) {
        if (endpoint == null) {
            return false;
        }
        String asUrl = sqsArnToUrl(endpoint);
        return extractQueuePathFromUrl(asUrl).equals(queuePath);
    }

    /**
     * Same path extraction as {@code SqsService} queue URL normalization ({@code /accountId/queueName}).
     */
    private static String extractQueuePathFromUrl(String url) {
        if (url == null) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return url;
        }
        return url.substring(pathStart);
    }

    private List<Subscription> subscriptionsByTopic(String topicArn, String region) {
        List<Subscription> result = new ArrayList<>();
        String prefix = "sub::" + region + "::";
        for (String k : subscriptionStore.keys()) {
            if (k.startsWith(prefix)) {
                subscriptionStore.get(k).ifPresent(sub -> {
                    if (topicArn.equals(sub.getTopicArn())) result.add(sub);
                });
            }
        }
        return result;
    }

    private void deliverMessage(Subscription sub, String message, String subject,
                                Map<String, MessageAttributeValue> messageAttributes, String messageId,
                                String topicArn, String messageGroupId, String messageDeduplicationId,
                                String messageStructure, String signatureVersion) {
        try {
            // Under MessageStructure="json" every subscriber gets the value under its own
            // protocol key, falling back to "default". The application case resolves against
            // the push platform (APNS/GCM/...) inside capturePushForEndpoint instead.
            String protocolMessage = "application".equals(sub.getProtocol())
                    ? message
                    : resolveProtocolPayload(sub.getProtocol(), message, messageStructure);
            switch (sub.getProtocol()) {
                case "sqs" -> {
                    String region = extractRegionFromArn(sub.getEndpoint());
                    if (region == null) {
                        region = extractRegionFromArn(topicArn);
                    }
                    String queueUrl = sqsArnToUrl(sub.getEndpoint());
                    boolean rawDelivery = "true".equalsIgnoreCase(sub.getAttributes().get("RawMessageDelivery"));
                    String body = rawDelivery
                            ? protocolMessage
                            : buildSnsEnvelope(protocolMessage, subject, messageAttributes, topicArn, messageId,
                                    sub.getSubscriptionArn(), signatureVersion);
                    Map<String, MessageAttributeValue> sqsAttributes = rawDelivery
                            ? toSqsMessageAttributes(messageAttributes)
                            : Collections.emptyMap();
                    deliverToSqsSubscription(sub, queueUrl, body, messageId, messageGroupId,
                            messageDeduplicationId, sqsAttributes, region, rawDelivery);
                }
                case "lambda" -> {
                    String region = extractRegionFromArn(sub.getEndpoint());
                    String eventJson = buildSnsLambdaEvent(topicArn, messageId, protocolMessage,
                            subject, messageAttributes, sub.getSubscriptionArn(), signatureVersion);
                    lambdaService.invoke(region, sub.getEndpoint(), eventJson.getBytes(), InvocationType.Event);
                    LOG.debugv("Delivered SNS message to Lambda: {0}", sub.getEndpoint());
                }
                case "http", "https" -> {
                    if (httpClient == null) {
                        break;
                    }
                    boolean rawDelivery = "true".equalsIgnoreCase(sub.getAttributes().get("RawMessageDelivery"));
                    String body = rawDelivery
                            ? protocolMessage
                            : buildSnsHttpNotification(protocolMessage, subject, messageAttributes, topicArn, messageId,
                                    sub.getSubscriptionArn(), signatureVersion);
                    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(sub.getEndpoint()))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "text/plain; charset=UTF-8")
                            .header("x-amz-sns-message-type", "Notification")
                            .header("x-amz-sns-message-id", messageId)
                            .header("x-amz-sns-topic-arn", topicArn)
                            .header("x-amz-sns-subscription-arn", sub.getSubscriptionArn());
                    if (rawDelivery) {
                        requestBuilder.header("x-amz-sns-rawdelivery", "true");
                    }
                    HttpRequest request = requestBuilder
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    String endpoint = sub.getEndpoint();
                    // Screened off this thread: Publish fans out to every subscriber here, and a
                    // slow DNS answer for one endpoint must not hold up the others or the caller.
                    postScreenedAsync(request, endpoint, "Delivered SNS notification");
                }
                case "application" -> {
                    String region = extractRegionFromArn(sub.getEndpoint());
                    if (region == null) {
                        region = extractRegionFromArn(topicArn);
                    }
                    String endpointArn = sub.getEndpoint();
                    PlatformEndpoint endpoint = platformEndpointStore.get(endpointKey(region, endpointArn)).orElse(null);
                    if (endpoint == null) {
                        LOG.debugv("Skipping topic fan-out to missing platform endpoint {0}", endpointArn);
                        break;
                    }
                    if (!"true".equalsIgnoreCase(endpoint.getAttributes().getOrDefault("Enabled", "true"))) {
                        LOG.debugv("Skipping topic fan-out to disabled platform endpoint {0}", endpointArn);
                        break;
                    }
                    PlatformApplication app = platformAppStore.get(
                            platformAppKey(region, endpoint.getPlatformApplicationArn())).orElse(null);
                    if (app == null || "false".equalsIgnoreCase(app.getAttributes().get("Enabled"))) {
                        LOG.debugv("Skipping topic fan-out to endpoint {0}: platform application missing or disabled",
                                endpointArn);
                        break;
                    }
                    capturePushForEndpoint(endpoint, app, message, subject, messageStructure, messageAttributes);
                    LOG.debugv("Delivered SNS message to platform endpoint: {0}", endpointArn);
                }
                case "firehose" -> {
                    if (firehoseService == null) {
                        break;
                    }
                    String streamName;
                    String region;
                    String accountId;
                    if (AwsArnUtils.isArn(sub.getEndpoint())) {
                        AwsArnUtils.Arn arn = AwsArnUtils.parse(sub.getEndpoint());
                        region = arn.region().isEmpty() ? extractRegionFromArn(topicArn) : arn.region();
                        accountId = arn.accountId().isEmpty() ? regionResolver.getAccountId() : arn.accountId();
                        String resource = arn.resource();
                        streamName = resource.startsWith("deliverystream/")
                                ? resource.substring("deliverystream/".length())
                                : resource;
                    } else {
                        streamName = sub.getEndpoint();
                        region = extractRegionFromArn(topicArn);
                        accountId = regionResolver.getAccountId();
                    }
                    if (region == null || region.isEmpty()) {
                        region = regionResolver.getDefaultRegion();
                    }
                    if (accountId == null || accountId.isEmpty()) {
                        accountId = regionResolver.getAccountId();
                    }
                    boolean rawDelivery = sub.getAttributes() != null
                            && "true".equalsIgnoreCase(sub.getAttributes().get("RawMessageDelivery"));
                    String body = rawDelivery
                            ? protocolMessage
                            : buildSnsEnvelope(protocolMessage, subject, messageAttributes, topicArn, messageId,
                                    null, null);
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    firehoseService.putRecord(accountId, region, streamName, new Record(data));
                    LOG.debugv("Delivered SNS message to Firehose: {0} ({1}) raw={2}", sub.getEndpoint(), streamName, rawDelivery);
                }
                case "email", "email-json" -> LOG.infov("SNS email delivery (stub): to={0}, subject={1}, message={2}",
                        sub.getEndpoint(), subject, protocolMessage);
                case "sms" -> LOG.infov("SNS SMS delivery (stub): to={0}, message={1}", sub.getEndpoint(), protocolMessage);
                default -> LOG.debugv("Protocol {0} delivery not implemented, skipping: {1}",
                        sub.getProtocol(), sub.getEndpoint());
            }
        } catch (Exception e) {
            // Delivery failures are per-subscriber and never reported to the publisher, which
            // matches AWS. Both SNS and SQS now top out at 1048576 bytes, so a publish at the
            // SNS limit no longer fits a queue at all: the non-raw sqs envelope wraps the body
            // in a few hundred bytes of JSON, and escaping expands every control character
            // below 0x20 to six bytes, so a publish made largely of them can serialize several
            // times larger. A topic left at the 262144-byte default clears an ordinary queue
            // with room to spare; a queue configured with a smaller MaximumMessageSize crosses
            // the limit more easily. Either way the message is dropped here.
            LOG.warnv("Failed to deliver SNS message to {0}: {1}", sub.getEndpoint(), e.getMessage());
        }
    }

    private void deliverToSqsSubscription(Subscription sub, String queueUrl, String body, String messageId,
                                          String messageGroupId, String messageDeduplicationId,
                                          Map<String, MessageAttributeValue> sqsAttributes,
                                          String region, boolean rawDelivery) {
        RuntimeException deliveryFailure = null;
        for (int attempt = 1; attempt <= SQS_SUBSCRIPTION_DELIVERY_ATTEMPTS; attempt++) {
            try {
                sqsService.sendMessage(queueUrl, body, null, messageGroupId,
                        messageDeduplicationId, sqsAttributes, null, ServicePrincipals.of("sns"), region);
                LOG.debugv("Delivered SNS message to SQS: {0} ({1}) raw={2}",
                        sub.getEndpoint(), queueUrl, rawDelivery);
                return;
            } catch (RuntimeException e) {
                deliveryFailure = e;
            }
        }

        String deadLetterTargetArn = subscriptionDeadLetterTargetArn(sub);
        if (deadLetterTargetArn != null) {
            try {
                String deadLetterRegion = extractRegionFromArn(deadLetterTargetArn);
                if (deadLetterRegion == null) {
                    deadLetterRegion = region;
                }
                boolean fifoDeadLetterQueue = AwsArnUtils.parse(deadLetterTargetArn)
                        .resource().endsWith(".fifo");
                String deadLetterGroupId = messageGroupId;
                String deadLetterDeduplicationId = messageDeduplicationId;
                if (fifoDeadLetterQueue) {
                    if (deadLetterGroupId == null || deadLetterGroupId.isBlank()) {
                        deadLetterGroupId = messageId;
                    }
                    // Each failed subscription is a distinct DLQ delivery, even when
                    // several subscriptions share this queue for the same publish.
                    deadLetterDeduplicationId = Digests.sha256Hex(messageId + "\0" + sub.getSubscriptionArn());
                }
                sqsService.sendMessage(sqsArnToUrl(deadLetterTargetArn), body, null,
                        deadLetterGroupId, deadLetterDeduplicationId, sqsAttributes, null,
                        ServicePrincipals.of("sns"), deadLetterRegion);
                LOG.warnv("SNS delivery to {0} failed after {1} attempts; sent notification to subscription DLQ {2}",
                        sub.getEndpoint(), SQS_SUBSCRIPTION_DELIVERY_ATTEMPTS, deadLetterTargetArn);
                return;
            } catch (RuntimeException deadLetterFailure) {
                LOG.warnv("SNS delivery to {0} and subscription DLQ {1} failed: {2}",
                        sub.getEndpoint(), deadLetterTargetArn, deadLetterFailure.getMessage());
            }
        }
        LOG.warnv("SNS delivery to {0} failed after {1} attempts: {2}",
                sub.getEndpoint(), SQS_SUBSCRIPTION_DELIVERY_ATTEMPTS,
                deliveryFailure == null ? "unknown failure" : deliveryFailure.getMessage());
    }

    private String subscriptionDeadLetterTargetArn(Subscription sub) {
        String redrivePolicy = sub.getAttributes().get(SUBSCRIPTION_REDRIVE_POLICY);
        if (redrivePolicy == null || redrivePolicy.isBlank()) {
            return null;
        }
        try {
            JsonNode policy = objectMapper.readTree(redrivePolicy);
            String targetArn = policy.path("deadLetterTargetArn").asText(null);
            return AwsArnUtils.isArnFor(targetArn, "sqs") ? targetArn : null;
        } catch (Exception e) {
            LOG.warnv("Ignoring invalid SNS subscription RedrivePolicy for {0}: {1}",
                    sub.getSubscriptionArn(), e.getMessage());
            return null;
        }
    }

    private String buildSnsLambdaEvent(String topicArn, String messageId, String message,
                                       String subject, Map<String, MessageAttributeValue> messageAttributes,
                                       String subscriptionArn, String signatureVersion) {
        try {
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            ObjectNode snsNode = objectMapper.createObjectNode();
            snsNode.put("Type", "Notification");
            snsNode.put("MessageId", messageId);
            snsNode.put("TopicArn", topicArn);
            if (subject != null) {
                snsNode.put("Subject", subject);
            } else {
                snsNode.putNull("Subject");
            }
            snsNode.put("Message", message);
            snsNode.put("Timestamp", timestamp);
            messageSigner.sign(snsNode, signatureVersion, "SigningCertUrl");
            snsNode.put("UnsubscribeUrl", unsubscribeUrl(subscriptionArn));
            ObjectNode attrs = snsNode.putObject("MessageAttributes");
            if (messageAttributes != null) {
                for (Map.Entry<String, MessageAttributeValue> entry : messageAttributes.entrySet()) {
                    ObjectNode attr = attrs.putObject(entry.getKey());
                    attr.put("Type", entry.getValue().getDataType());
                    if (entry.getValue().getBinaryValue() != null) {
                        attr.put("Value", Base64.getEncoder()
                                .encodeToString(entry.getValue().getBinaryValue()));
                    } else {
                        attr.put("Value", entry.getValue().getStringValue());
                    }
                }
            }
            ObjectNode record = objectMapper.createObjectNode();
            record.put("EventVersion", "1.0");
            record.put("EventSubscriptionArn", subscriptionArn);
            record.put("EventSource", "aws:sns");
            record.set("Sns", snsNode);
            ObjectNode root = objectMapper.createObjectNode();
            root.putArray("Records").add(record);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return "{\"Records\":[]}";
        }
    }

    private static String extractRegionFromArn(String arn) {
        return AwsArnUtils.regionOrDefault(arn, null);
    }

    /**
     * Forwards SNS message attributes as SQS MessageAttributeValue objects
     * when RawMessageDelivery is enabled, preserving the original DataType.
     */
    private Map<String, MessageAttributeValue> toSqsMessageAttributes(Map<String, MessageAttributeValue> snsAttributes) {
        if (snsAttributes == null || snsAttributes.isEmpty()) {
            return Collections.emptyMap();
        }
        return new HashMap<>(snsAttributes);
    }

    /**
     * The JSON notification envelope SQS and Firehose share. With a {@code subscriptionArn} it is
     * signed and carries {@code UnsubscribeURL}, as a non-raw SQS delivery does; without one it is
     * the unsigned form Firehose receives.
     */
    private String buildSnsEnvelope(String message, String subject,
                                    Map<String, MessageAttributeValue> messageAttributes,
                                    String topicArn, String messageId, String subscriptionArn,
                                    String signatureVersion) {
        try {
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Type", "Notification");
            node.put("MessageId", messageId);
            node.put("TopicArn", topicArn);
            node.put("Timestamp", timestamp);
            if (subject != null) {
                node.put("Subject", subject);
            }
            node.put("Message", message);
            if (subscriptionArn != null) {
                messageSigner.sign(node, signatureVersion, "SigningCertURL");
                node.put("UnsubscribeURL", unsubscribeUrl(subscriptionArn));
            }
            ObjectNode attrs = node.putObject("MessageAttributes");
            if (messageAttributes != null) {
                for (Map.Entry<String, MessageAttributeValue> entry : messageAttributes.entrySet()) {
                    ObjectNode attr = attrs.putObject(entry.getKey());
                    attr.put("Type", entry.getValue().getDataType());
                    if (entry.getValue().getBinaryValue() != null) {
                        attr.put("Value", Base64.getEncoder()
                                .encodeToString(entry.getValue().getBinaryValue()));
                    } else {
                        attr.put("Value", entry.getValue().getStringValue());
                    }
                }
            }
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            LOG.warnv("Failed to build SNS envelope for message {0}: {1}", messageId, e.getMessage());
            return "{}";
        }
    }

    private String unsubscribeUrl(String subscriptionArn) {
        return baseUrl + "/?Action=Unsubscribe&SubscriptionArn=" + subscriptionArn;
    }

    private String signatureVersion(Topic topic) {
        return SnsMessageSigner.normalizeSignatureVersion(
                topic == null ? null : topic.getAttributes().get("SignatureVersion"));
    }

    private String buildSnsHttpNotification(String message, String subject,
                                             Map<String, MessageAttributeValue> messageAttributes,
                                             String topicArn, String messageId, String subscriptionArn,
                                             String signatureVersion) {
        try {
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Type", "Notification");
            node.put("MessageId", messageId);
            node.put("TopicArn", topicArn);
            node.put("Timestamp", timestamp);
            if (subject != null) {
                node.put("Subject", subject);
            }
            node.put("Message", message);
            messageSigner.sign(node, signatureVersion, "SigningCertURL");
            node.put("UnsubscribeURL", unsubscribeUrl(subscriptionArn));
            ObjectNode attrs = node.putObject("MessageAttributes");
            if (messageAttributes != null) {
                for (Map.Entry<String, MessageAttributeValue> entry : messageAttributes.entrySet()) {
                    ObjectNode attr = attrs.putObject(entry.getKey());
                    attr.put("Type", entry.getValue().getDataType());
                    if (entry.getValue().getBinaryValue() != null) {
                        attr.put("Value", Base64.getEncoder()
                                .encodeToString(entry.getValue().getBinaryValue()));
                    } else {
                        attr.put("Value", entry.getValue().getStringValue());
                    }
                }
            }
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private void logHttpResult(String action, String endpoint, int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            LOG.debugv("{0} to {1}, status={2}", action, endpoint, statusCode);
        } else {
            LOG.warnv("{0} to {1} returned non-success status {2}", action, endpoint, statusCode);
        }
    }

    private void sendSubscriptionConfirmation(Subscription subscription, String topicArn, String region) {
        if (httpClient == null) return;
        try {
            String token = subscription.getAttributes().get("ConfirmationToken");
            String subscribeUrl = baseUrl + "/?Action=ConfirmSubscription&TopicArn=" + topicArn + "&Token=" + token;
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Type", "SubscriptionConfirmation");
            node.put("MessageId", UUID.randomUUID().toString());
            node.put("TopicArn", topicArn);
            node.put("Timestamp", timestamp);
            node.put("Message", "You have chosen to subscribe to the topic " + topicArn + ".\nTo confirm the subscription, visit the SubscribeURL included in this message.");
            node.put("SubscribeURL", subscribeUrl);
            node.put("Token", token);
            messageSigner.sign(node, signatureVersion(topicStore.get(topicKey(region, topicArn)).orElse(null)),
                    "SigningCertURL");
            String body = objectMapper.writeValueAsString(node);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(subscription.getEndpoint()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .header("x-amz-sns-message-type", "SubscriptionConfirmation")
                    .header("x-amz-sns-topic-arn", topicArn)
                    .header("x-amz-sns-subscription-arn", "PendingConfirmation")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            postScreenedAsync(request, subscription.getEndpoint(), "Sent SubscriptionConfirmation");
        } catch (Exception e) {
            LOG.warnv("Failed to send SubscriptionConfirmation to {0}: {1}", subscription.getEndpoint(), e.getMessage());
        }
    }

    /**
     * Rejects an endpoint that resolves to a link-local or cloud instance-metadata address. Floci
     * posts to a subscribed endpoint itself, so without this an unauthenticated Subscribe turns the
     * emulator into a blind request forwarder against addresses only it can reach. Loopback and
     * private ranges stay allowed: a local topic delivering to a neighbouring container is the
     * normal case here.
     */
    private static void requireDeliverableEndpoint(String endpoint) {
        if (endpoint == null) {
            return;
        }
        String host;
        try {
            host = URI.create(endpoint).getHost();
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameter", "Invalid parameter: Endpoint is not a valid URL.", 400);
        }
        if (host == null || host.isBlank()) {
            throw new AwsException("InvalidParameter", "Invalid parameter: Endpoint is not a valid URL.", 400);
        }
        try {
            SsrfProtection.rejectMetadataAddresses(InetAddress.getAllByName(host), host);
        } catch (UnknownHostException e) {
            // Unresolvable now does not mean unresolvable at delivery time, and AWS accepts an
            // endpoint whose DNS is not yet live, so this is not the place to refuse it.
            LOG.debugv("SNS endpoint host {0} did not resolve at subscribe time: {1}", host, e.getMessage());
        } catch (IOException e) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Endpoint " + endpoint + " is not a permitted address.", 400);
        }
    }

    /** Screens the endpoint, then posts. Nothing here resolves a name, so nothing here blocks. */
    private void postScreenedAsync(HttpRequest request, String endpoint, String what) {
        if (!deliverable(endpoint)) {
            return;
        }
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> logHttpResult(what, endpoint, response.statusCode()))
                .exceptionally(ex -> {
                    LOG.warnv("Failed to reach SNS endpoint {0}: {1}", endpoint, ex.getMessage());
                    return null;
                });
    }

    /**
     * Whether this endpoint may be posted to, judged without resolving anything. Subscribe is where
     * a name gets resolved and screened; this catches the endpoint that names an address outright,
     * including a subscription stored before Subscribe began refusing them.
     *
     * <p>Deliberately not a second DNS lookup. {@link HttpClient} resolves the name again when it
     * connects and cannot be handed the result of a check, so a lookup here could never be the thing
     * that decides where the request goes: it would cost a resolution per delivery and still leave
     * the same rebinding residual.
     */
    static boolean deliverable(String endpoint) {
        String host;
        try {
            host = URI.create(endpoint).getHost();
        } catch (IllegalArgumentException e) {
            LOG.warnv("Refusing to deliver to SNS endpoint {0}: not a valid URL", endpoint);
            return false;
        }
        if (host == null || !isIpLiteral(host)) {
            return true;
        }
        try {
            SsrfProtection.rejectMetadataAddresses(InetAddress.getAllByName(host), host);
            return true;
        } catch (IOException e) {
            LOG.warnv("Refusing to deliver to SNS endpoint {0}: {1}", endpoint, e.getMessage());
            return false;
        }
    }

    /** True for a bracketed IPv6 literal or a dotted IPv4 one, neither of which needs resolving. */
    private static boolean isIpLiteral(String host) {
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        return bare.indexOf(':') >= 0 || bare.matches("[0-9.]+");
    }

    private String sqsArnToUrl(String arn) {
        if (arn == null) return null;
        if (arn.startsWith("http")) return arn;
        try { AwsArnUtils.parse(arn); } catch (IllegalArgumentException e) { return arn; }
        return AwsArnUtils.arnToQueueUrl(arn, baseUrl);
    }

    /**
     * Payload size as AWS counts it against {@code MaximumMessageSize}: UTF-8 body bytes plus,
     * per attribute, its name, data type and value. {@code Subject} is excluded -- a publish of
     * exactly the limit succeeds however long its subject is.
     */
    private static int computePublishSize(int messageBytes,
                                          Map<String, MessageAttributeValue> attributes) {
        int total = messageBytes;
        if (attributes != null) {
            for (Map.Entry<String, MessageAttributeValue> entry : attributes.entrySet()) {
                total += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
                MessageAttributeValue value = entry.getValue();
                if (value == null) {
                    continue;
                }
                if (value.getDataType() != null) {
                    total += value.getDataType().getBytes(StandardCharsets.UTF_8).length;
                }
                if (value.getBinaryValue() != null) {
                    total += value.getBinaryValue().length;
                } else if (value.getStringValue() != null) {
                    total += value.getStringValue().getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
        return total;
    }

    private String smsAttributesKey(String region) {
        return regionResolver.getAccountId() + "::" + region;
    }

    private String smsSandboxKey(String region, String phoneNumber) {
        return smsAttributesKey(region) + "::" + phoneNumber;
    }

    /** Sends an SMS, which here means storing it for {@code GET /_aws/sns} to read back. */
    private String recordSms(String phoneNumber, String message, String subject, String region) {
        String messageId = UUID.randomUUID().toString();
        String effectiveRegion = region != null ? region : regionResolver.getDefaultRegion();
        SentSms sms = new SentSms(messageId, effectiveRegion, phoneNumber, message, subject, Instant.now());
        smsStore.put("sms::" + effectiveRegion + "::" + messageId, sms);
        LOG.infov("SNS SMS published: messageId={0} phone={1}", messageId, phoneNumber);
        return messageId;
    }

    private static String topicKey(String region, String arn) {
        return "topic::" + region + "::" + arn;
    }

    private static String subKey(String region, String subscriptionArn) {
        return "sub::" + region + "::" + subscriptionArn;
    }

    /** All SMS messages published since last clear. Used by SnsInspectionController. */
    public List<SentSms> getSentMessages() {
        return smsStore.scan(k -> k.startsWith("sms::"));
    }

    /** Drop all stored SMS records. Used by SnsInspectionController. */
    public void clearSentMessages() {
        for (String key : smsStore.keys()) {
            if (key.startsWith("sms::")) {
                smsStore.delete(key);
            }
        }
        LOG.info("Cleared all SNS SMS records");
    }
}
