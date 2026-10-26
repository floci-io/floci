package io.github.hectorvent.floci.services.servicequotas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.ServiceQuotasStartupValidator;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Service Quotas emulation backed by a generated in-memory catalog.
 *
 * <p>Every service code resolves to a quota list: a curated set with real AWS
 * quota codes where tooling depends on them (CodeBuild's {@code L-2DC20C30}
 * "Concurrently running builds", Lambda's {@code L-B99A9384} "Concurrent
 * executions"), plus a deterministic generic set for any other service code.
 * Unmodeled values are deliberately generous so local pipelines that gate on
 * quota headroom never stall on a limit the emulator does not enforce.
 * Applied EC2 Standard On-Demand vCPU values use local configuration; AWS defaults
 * retain the catalog value.
 *
 * @see <a href="https://docs.aws.amazon.com/servicequotas/2019-06-24/apireference/Welcome.html">Service Quotas API</a>
 */
@ApplicationScoped
public class ServiceQuotasService {

    private static final double GENERIC_QUOTA_VALUE = 5000.0;
    private static final Pattern SERVICE_CODE_PATTERN =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9-]{1,63}");

    private static final Map<String, String> SERVICE_NAMES = Map.ofEntries(
            Map.entry("codebuild", "AWS CodeBuild"),
            Map.entry("codepipeline", "AWS CodePipeline"),
            Map.entry("lambda", "AWS Lambda"),
            Map.entry("cloudformation", "AWS CloudFormation"),
            Map.entry("organizations", "AWS Organizations"),
            Map.entry("iam", "AWS Identity and Access Management (IAM)"),
            Map.entry("kms", "AWS Key Management Service (AWS KMS)"),
            Map.entry("s3", "Amazon Simple Storage Service (Amazon S3)"),
            Map.entry("sns", "Amazon Simple Notification Service (Amazon SNS)"),
            Map.entry("sqs", "Amazon Simple Queue Service (Amazon SQS)"),
            Map.entry("ec2", "Amazon Elastic Compute Cloud (Amazon EC2)"),
            Map.entry("vpc", "Amazon Virtual Private Cloud (Amazon VPC)"),
            Map.entry("dynamodb", "Amazon DynamoDB"),
            Map.entry("logs", "Amazon CloudWatch Logs"),
            Map.entry("events", "Amazon EventBridge (CloudWatch Events)"));

    private static final Map<String, List<QuotaDefinition>> CURATED_QUOTAS = Map.of(
            "codebuild", List.of(
                    new QuotaDefinition("L-2DC20C30", "Concurrently running builds", GENERIC_QUOTA_VALUE, false)),
            "lambda", List.of(
                    new QuotaDefinition("L-B99A9384", "Concurrent executions", GENERIC_QUOTA_VALUE, false)),
            "organizations", List.of(
                    new QuotaDefinition("L-E619E033", "Maximum number of accounts", 50.0, true)),
            "ec2", List.of(
                    new QuotaDefinition("L-1216C47A",
                            "Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances", 5.0, false)),
            // AWS default values: tooling such as Terraform's aws_servicequotas_service_quota
            // compares its desired value against these before requesting an increase.
            "vpc", List.of(
                    new QuotaDefinition("L-FE5A380F", "NAT gateways per Availability Zone", 5.0, false),
                    new QuotaDefinition("L-2AEEBF1A", "Rules per network ACL", 20.0, false)));

    private static final List<String> GENERIC_QUOTA_NAMES = List.of(
            "Resources per Region",
            "Rate of requests per second",
            "Concurrent operations");

    private final ObjectMapper objectMapper;
    private final int standardOnDemandVcpus;
    private final StorageBackend<String, ObjectNode> requestedQuotaStore;
    private final Object openRequestLock = new Object();

    @Inject
    public ServiceQuotasService(StorageFactory storageFactory, ObjectMapper objectMapper, EmulatorConfig config) {
        this.objectMapper = objectMapper;
        this.standardOnDemandVcpus = config.services().servicequotas().standardOnDemandVcpus();
        ServiceQuotasStartupValidator.validateStandardOnDemandVcpus(standardOnDemandVcpus);
        this.requestedQuotaStore = storageFactory.create("servicequotas",
                "servicequotas-requested-changes.json",
                new TypeReference<Map<String, ObjectNode>>() {});
    }

    public ObjectNode listServiceQuotas(String serviceCode, String quotaCodeFilter, String nextToken,
                                        Integer maxResults, String region, String accountId) {
        return listQuotas(serviceCode, quotaCodeFilter, nextToken, maxResults, region, accountId, true);
    }

    public ObjectNode listAwsDefaultServiceQuotas(String serviceCode, String quotaCodeFilter, String nextToken,
                                                  Integer maxResults, String region, String accountId) {
        return listQuotas(serviceCode, quotaCodeFilter, nextToken, maxResults, region, accountId, false);
    }

    private ObjectNode listQuotas(String serviceCode, String quotaCodeFilter, String nextToken,
                                  Integer maxResults, String region, String accountId, boolean applied) {
        requireServiceCode(serviceCode);
        List<QuotaDefinition> quotas = quotasFor(serviceCode);
        if (quotaCodeFilter != null && !quotaCodeFilter.isEmpty()) {
            quotas = quotas.stream().filter(q -> q.quotaCode().equals(quotaCodeFilter)).toList();
        }
        Page<QuotaDefinition> page = paginate(quotas, nextToken, maxResults);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Quotas");
        for (QuotaDefinition quota : page.items()) {
            array.add(quotaNode(serviceCode, quota, region, accountId, applied));
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public ObjectNode getServiceQuota(String serviceCode, String quotaCode, String region, String accountId) {
        return getQuota(serviceCode, quotaCode, region, accountId, true);
    }

    public ObjectNode getAwsDefaultServiceQuota(String serviceCode, String quotaCode, String region, String accountId) {
        return getQuota(serviceCode, quotaCode, region, accountId, false);
    }

    private ObjectNode getQuota(String serviceCode, String quotaCode, String region, String accountId, boolean applied) {
        requireServiceCode(serviceCode);
        if (quotaCode == null || quotaCode.isEmpty()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: QuotaCode must not be empty.", 400);
        }
        QuotaDefinition quota = quotasFor(serviceCode).stream()
                .filter(q -> q.quotaCode().equals(quotaCode))
                .findFirst()
                .orElseThrow(() -> new AwsException("NoSuchResourceException",
                        "The request failed because the specified service quota does not exist.", 400));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Quota", quotaNode(serviceCode, quota, region, accountId, applied));
        return response;
    }

    List<QuotaDefinition> quotasFor(String serviceCode) {
        List<QuotaDefinition> quotas = new ArrayList<>(CURATED_QUOTAS.getOrDefault(serviceCode, List.of()));
        for (String name : GENERIC_QUOTA_NAMES) {
            quotas.add(new QuotaDefinition(
                    syntheticQuotaCode(serviceCode, name), name, GENERIC_QUOTA_VALUE,
                    "organizations".equals(serviceCode)));
        }
        return quotas;
    }

    /**
     * Deterministic {@code L-XXXXXXXX} code for generated quotas so that a code
     * observed in {@code ListServiceQuotas} always resolves in {@code GetServiceQuota}.
     */
    static String syntheticQuotaCode(String serviceCode, String quotaName) {
        CRC32 crc = new CRC32();
        crc.update((serviceCode + "/" + quotaName).getBytes(StandardCharsets.UTF_8));
        return "L-%08X".formatted(crc.getValue());
    }

    private ObjectNode quotaNode(String serviceCode, QuotaDefinition quota, String region, String accountId,
                                  boolean applied) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ServiceCode", serviceCode);
        node.put("ServiceName", SERVICE_NAMES.getOrDefault(serviceCode, serviceCode));
        node.put("QuotaArn", AwsArnUtils.Arn.of("servicequotas", region, accountId,
                serviceCode + "/" + quota.quotaCode()).toString());
        node.put("QuotaCode", quota.quotaCode());
        node.put("QuotaName", quota.quotaName());
        double value = applied && "ec2".equals(serviceCode) && "L-1216C47A".equals(quota.quotaCode())
                ? standardOnDemandVcpus : quota.value();
        node.put("Value", value);
        node.put("Unit", "None");
        node.put("Adjustable", true);
        node.put("GlobalQuota", quota.globalQuota());
        node.put("QuotaAppliedAtLevel", "ACCOUNT");
        return node;
    }

    /**
     * Unknown-but-well-formed service codes deliberately generate a quota catalog, which makes the
     * shape check load-bearing: without it a code that cannot name any service — {@code "!!!"} —
     * comes back with invented quotas instead of an error. The model constrains ServiceCode to at
     * most 63 characters matching {@code [a-zA-Z][a-zA-Z0-9-]{1,63}} (the pattern's own minimum of
     * two characters is the binding one, the shape's {@code min: 1} notwithstanding).
     */
    private static void requireServiceCode(String serviceCode) {
        if (serviceCode == null || serviceCode.isEmpty()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: ServiceCode must not be empty.", 400);
        }
        if (serviceCode.length() > 63 || !SERVICE_CODE_PATTERN.matcher(serviceCode).matches()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: ServiceCode must match [a-zA-Z][a-zA-Z0-9-]{1,63} "
                            + "and be at most 63 characters.", 400);
        }
    }

    private static <T> Page<T> paginate(List<T> items, String nextToken, Integer maxResults) {
        if (maxResults != null && (maxResults < 1 || maxResults > 100)) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: MaxResults must be between 1 and 100.", 400);
        }
        int start = decodeToken(nextToken);
        if (start < 0 || start > items.size()) {
            throw new AwsException("InvalidPaginationTokenException", "Invalid NextToken.", 400);
        }
        int end = (maxResults == null) ? items.size() : Math.min(items.size(), start + maxResults);
        String next = (end < items.size()) ? encodeToken(end) : null;
        return new Page(items.subList(start, end), next);
    }

    private static String encodeToken(int offset) {
        return Base64Url.encode(Integer.toString(offset));
    }

    private static int decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(Base64Url.decode(token));
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    public ObjectNode listRequestedServiceQuotaChangeHistoryByQuota(String serviceCode, String quotaCode,
                                                                    String nextToken, Integer maxResults,
                                                                    String region) {
        requireServiceCode(serviceCode);
        if (quotaCode == null || quotaCode.isBlank()) {
            throw new AwsException("IllegalArgumentException", "Invalid input: QuotaCode must not be empty.", 400);
        }
        boolean exists = quotasFor(serviceCode).stream().anyMatch(quota -> quota.quotaCode().equals(quotaCode));
        if (!exists) {
            throw new AwsException("NoSuchResourceException",
                    "The request failed because the specified service quota does not exist.", 400);
        }
        List<ObjectNode> matching = new ArrayList<>();
        for (ObjectNode requested : requestedQuotaStore.scan(key -> key.startsWith(region + "/"))) {
            if (serviceCode.equals(requested.path("ServiceCode").asText())
                    && quotaCode.equals(requested.path("QuotaCode").asText())) {
                matching.add(requested);
            }
        }
        Page<ObjectNode> page = paginate(matching, nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("RequestedQuotas");
        for (ObjectNode requested : page.items()) {
            array.add(requested.deepCopy());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    record QuotaDefinition(String quotaCode, String quotaName, double value, boolean globalQuota) {
    }

    private record Page<T>(List<T> items, String nextToken) {
    }

    public ObjectNode requestServiceQuotaIncrease(String serviceCode, String quotaCode, Double desiredValue,
                                                  String contextId, String region, String accountId) {
        requireServiceCode(serviceCode);
        if (quotaCode == null || quotaCode.isEmpty()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: QuotaCode must not be empty.", 400);
        }
        if (desiredValue == null) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: DesiredValue must not be null.", 400);
        }
        if (desiredValue < 0 || desiredValue > 10000000000.0) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: DesiredValue must be between 0 and 10000000000.", 400);
        }
        QuotaDefinition quota = quotasFor(serviceCode).stream()
                .filter(q -> q.quotaCode().equals(quotaCode))
                .findFirst()
                .orElseThrow(() -> new AwsException("NoSuchResourceException",
                        "The request failed because the specified service quota does not exist.", 400));

        String id = UUID.randomUUID().toString();

        long now = Instant.now().getEpochSecond();

        ObjectNode requestedQuota = objectMapper.createObjectNode();
        requestedQuota.put("Id", id);
        requestedQuota.put("ServiceCode", serviceCode);
        requestedQuota.put("ServiceName", SERVICE_NAMES.getOrDefault(serviceCode, serviceCode));
        requestedQuota.put("QuotaCode", quotaCode);
        requestedQuota.put("QuotaName", quota.quotaName());
        requestedQuota.put("QuotaArn", AwsArnUtils.Arn.of("servicequotas", region, accountId,
                serviceCode + "/" + quotaCode).toString());
        requestedQuota.put("DesiredValue", desiredValue);
        requestedQuota.put("Status", "PENDING");
        requestedQuota.put("Requester", "floci-emulator");
        requestedQuota.put("Unit", "None");
        requestedQuota.put("GlobalQuota", quota.globalQuota());
        requestedQuota.put("QuotaRequestedAtLevel", "ACCOUNT");
        requestedQuota.put("Created", now);
        requestedQuota.put("LastUpdated", now);
        if (contextId != null) {
            ObjectNode context = objectMapper.createObjectNode();
            context.put("ContextId", contextId);
            context.put("ContextScope", "RESOURCE");
            requestedQuota.set("QuotaContext", context);
        }

        storeOpenRequest(region, serviceCode, quotaCode, id, requestedQuota);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("RequestedQuota", requestedQuota);
        return response;
    }

    /**
     * AWS allows only one open increase request per quota, so the duplicate check and the
     * write must stand or fall as a unit: concurrent callers for the same quota must not
     * both pass the check.
     */
    private void storeOpenRequest(String region, String serviceCode, String quotaCode, String id,
                                  ObjectNode requestedQuota) {
        synchronized (openRequestLock) {
            boolean hasOpen = requestedQuotaStore.scan(key -> key.startsWith(region + "/")).stream()
                    .anyMatch(r -> serviceCode.equals(r.path("ServiceCode").asText())
                            && quotaCode.equals(r.path("QuotaCode").asText())
                            && "PENDING".equals(r.path("Status").asText()));
            if (hasOpen) {
                throw new AwsException("ResourceAlreadyExistsException",
                        "Only one open service quota increase request is allowed per quota.", 400);
            }
            requestedQuotaStore.put(region + "/" + id, requestedQuota.deepCopy());
        }
    }

    public ObjectNode getRequestedServiceQuotaChange(String requestId, String region) {
        if (requestId == null || requestId.isEmpty()) {
            throw new AwsException("IllegalArgumentException",
                    "Invalid input: RequestId must not be empty.", 400);
        }
        ObjectNode requestedQuota = requestedQuotaStore.get(region + "/" + requestId)
                .orElseThrow(() -> new AwsException("NoSuchResourceException",
                        "The request failed because the specified request ID does not exist.", 400));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RequestedQuota", requestedQuota.deepCopy());
        return response;
    }
}
