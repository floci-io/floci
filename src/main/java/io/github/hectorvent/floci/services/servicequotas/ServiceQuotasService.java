package io.github.hectorvent.floci.services.servicequotas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.time.Instant;

/**
 * Service Quotas emulation backed by a generated in-memory catalog.
 *
 * <p>Every service code resolves to a quota list: a curated set with real AWS
 * quota codes where tooling depends on them (CodeBuild's {@code L-2DC20C30}
 * "Concurrently running builds", Lambda's {@code L-B99A9384} "Concurrent
 * executions"), plus a deterministic generic set for any other service code.
 * All values are deliberately generous so local pipelines that gate on quota
 * headroom (for example Landing Zone Accelerator) never stall on a limit the
 * emulator does not enforce. Applied and default quotas return the same data.
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
            Map.entry("dynamodb", "Amazon DynamoDB"),
            Map.entry("logs", "Amazon CloudWatch Logs"),
            Map.entry("events", "Amazon EventBridge (CloudWatch Events)"));

    private static final Map<String, List<QuotaDefinition>> CURATED_QUOTAS = Map.of(
            "codebuild", List.of(
                    new QuotaDefinition("L-2DC20C30", "Concurrently running builds", GENERIC_QUOTA_VALUE)),
            "lambda", List.of(
                    new QuotaDefinition("L-B99A9384", "Concurrent executions", GENERIC_QUOTA_VALUE)));

    private static final List<String> GENERIC_QUOTA_NAMES = List.of(
            "Resources per Region",
            "Rate of requests per second",
            "Concurrent operations");

    private final ObjectMapper objectMapper;

    @Inject
    public ServiceQuotasService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode listServiceQuotas(String serviceCode, String quotaCodeFilter, String nextToken,
                                        Integer maxResults, String region, String accountId) {
        requireServiceCode(serviceCode);
        List<QuotaDefinition> quotas = quotasFor(serviceCode);
        if (quotaCodeFilter != null && !quotaCodeFilter.isEmpty()) {
            quotas = quotas.stream().filter(q -> q.quotaCode().equals(quotaCodeFilter)).toList();
        }
        Page page = paginate(quotas, nextToken, maxResults);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Quotas");
        for (QuotaDefinition quota : page.items()) {
            array.add(quotaNode(serviceCode, quota, region, accountId));
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public ObjectNode getServiceQuota(String serviceCode, String quotaCode, String region, String accountId) {
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
        response.set("Quota", quotaNode(serviceCode, quota, region, accountId));
        return response;
    }

    List<QuotaDefinition> quotasFor(String serviceCode) {
        List<QuotaDefinition> quotas = new ArrayList<>(CURATED_QUOTAS.getOrDefault(serviceCode, List.of()));
        for (String name : GENERIC_QUOTA_NAMES) {
            quotas.add(new QuotaDefinition(syntheticQuotaCode(serviceCode, name), name, GENERIC_QUOTA_VALUE));
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

    private ObjectNode quotaNode(String serviceCode, QuotaDefinition quota, String region, String accountId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ServiceCode", serviceCode);
        node.put("ServiceName", SERVICE_NAMES.getOrDefault(serviceCode, serviceCode));
        node.put("QuotaArn", "arn:aws:servicequotas:" + region + ":" + accountId + ":"
                + serviceCode + "/" + quota.quotaCode());
        node.put("QuotaCode", quota.quotaCode());
        node.put("QuotaName", quota.quotaName());
        node.put("Value", quota.value());
        node.put("Unit", "None");
        node.put("Adjustable", true);
        node.put("GlobalQuota", false);
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

    private static Page paginate(List<QuotaDefinition> items, String nextToken, Integer maxResults) {
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
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            return Integer.parseInt(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    record QuotaDefinition(String quotaCode, String quotaName, double value) {
    }

    private record Page(List<QuotaDefinition> items, String nextToken) {
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

        CRC32 crc = new CRC32();
        crc.update((serviceCode + "/" + quotaCode).getBytes(StandardCharsets.UTF_8));
        String id = "%08X".formatted(crc.getValue());

        long now = Instant.now().getEpochSecond();

        ObjectNode requestedQuota = objectMapper.createObjectNode();
        requestedQuota.put("Id", id);
        requestedQuota.put("ServiceCode", serviceCode);
        requestedQuota.put("ServiceName", SERVICE_NAMES.getOrDefault(serviceCode, serviceCode));
        requestedQuota.put("QuotaCode", quotaCode);
        requestedQuota.put("QuotaName", quota.quotaName());
        requestedQuota.put("QuotaArn", "arn:aws:servicequotas:" + region + ":" + accountId + ":"
                + serviceCode + "/" + quotaCode);
        requestedQuota.put("DesiredValue", desiredValue);
        requestedQuota.put("Status", "PENDING");
        requestedQuota.put("Requester", "floci-emulator");
        requestedQuota.put("Unit", "None");
        requestedQuota.put("GlobalQuota", false);
        requestedQuota.put("QuotaRequestedAtLevel", "ACCOUNT");
        requestedQuota.put("Created", now);
        requestedQuota.put("LastUpdated", now);
        if (contextId != null) {
            ObjectNode context = objectMapper.createObjectNode();
            context.put("ContextId", contextId);
            context.put("ContextScope", "RESOURCE");
            requestedQuota.set("QuotaContext", context);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("RequestedQuota", requestedQuota);
        return response;
    }
}
