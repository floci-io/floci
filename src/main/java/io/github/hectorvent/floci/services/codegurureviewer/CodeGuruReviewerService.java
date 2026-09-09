package io.github.hectorvent.floci.services.codegurureviewer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codegurureviewer.model.RepositoryAssociation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon CodeGuru Reviewer repository associations.
 *
 * <p>An association reaches the terminal {@code Associated} state as soon as
 * {@code AssociateRepository} returns, so the provider waiter that polls
 * {@code DescribeRepositoryAssociation} completes on its first attempt instead of spinning
 * through an {@code Associating} state the emulator would never leave.
 */
@ApplicationScoped
public class CodeGuruReviewerService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(CodeGuruReviewerService.class);

    private static final Pattern NAME = Pattern.compile("\\S[\\w.-]*");
    private static final Pattern OWNER = Pattern.compile("\\S(.*\\S)?");
    private static final Set<String> ENCRYPTION_OPTIONS = Set.of("AWS_OWNED_CMK", "CUSTOMER_MANAGED_CMK");
    private static final String ASSOCIATED = "Associated";
    private static final String DISASSOCIATED = "Disassociated";
    private static final String ARN_RESOURCE_PREFIX = "association:";

    private final StorageBackend<String, RepositoryAssociation> associations;
    private final RegionResolver regionResolver;

    @Inject
    public CodeGuruReviewerService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.associations = storageFactory.create("codegurureviewer", "codegurureviewer-associations.json",
                new TypeReference<Map<String, RepositoryAssociation>>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────── Repository associations ────────────────────────

    /**
     * {@code Repository} is a union: exactly one of {@code CodeCommit}, {@code Bitbucket},
     * {@code GitHubEnterpriseServer} or {@code S3Bucket} may be set, and the association's
     * {@code Name}, {@code Owner} and {@code ProviderType} follow from whichever it is.
     */
    public RepositoryAssociation associateRepository(JsonNode repository, JsonNode kmsKeyDetails,
                                                     Map<String, String> tags, String region) {
        if (repository == null || !repository.isObject()) {
            throw new AwsException("ValidationException", "Repository is required.", 400);
        }

        RepositoryAssociation association = new RepositoryAssociation();
        String accountId = regionResolver.getAccountId();
        int members = 0;

        JsonNode codeCommit = repository.get("CodeCommit");
        if (codeCommit != null && codeCommit.isObject()) {
            members++;
            association.setProviderType("CodeCommit");
            association.setName(requireName(codeCommit, "Repository.CodeCommit.Name"));
            association.setOwner(accountId);
        }
        JsonNode bitbucket = repository.get("Bitbucket");
        if (bitbucket != null && bitbucket.isObject()) {
            members++;
            applyThirdParty(association, bitbucket, "Bitbucket");
        }
        JsonNode gitHubEnterprise = repository.get("GitHubEnterpriseServer");
        if (gitHubEnterprise != null && gitHubEnterprise.isObject()) {
            members++;
            applyThirdParty(association, gitHubEnterprise, "GitHubEnterpriseServer");
        }
        JsonNode s3Bucket = repository.get("S3Bucket");
        if (s3Bucket != null && s3Bucket.isObject()) {
            members++;
            association.setProviderType("S3Bucket");
            association.setName(requireName(s3Bucket, "Repository.S3Bucket.Name"));
            association.setOwner(accountId);
            String bucketName = text(s3Bucket, "BucketName");
            if (bucketName == null || bucketName.isBlank()) {
                throw new AwsException("ValidationException", "Repository.S3Bucket.BucketName is required.", 400);
            }
            association.setS3BucketName(bucketName);
        }

        if (members != 1) {
            throw new AwsException("ValidationException",
                    "Repository must set exactly one of CodeCommit, Bitbucket, GitHubEnterpriseServer, S3Bucket.",
                    400);
        }
        if (findByRepository(association.getProviderType(), association.getOwner(), association.getName(), region)
                != null) {
            throw new AwsException("ConflictException",
                    "Repository " + association.getName() + " is already associated.", 409);
        }

        applyKmsKeyDetails(association, kmsKeyDetails);

        String associationId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        association.setAssociationId(associationId);
        association.setAssociationArn(regionResolver.buildArn("codeguru-reviewer", region,
                ARN_RESOURCE_PREFIX + associationId));
        association.setState(ASSOCIATED);
        association.setStateReason("Pull Request Notification configuration successful");
        association.setCreatedTimeStamp(now);
        association.setLastUpdatedTimeStamp(now);
        association.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());

        associations.put(storageKey(region, associationId), association);
        LOG.infov("Associated CodeGuru Reviewer repository: {0} ({1})",
                association.getName(), association.getProviderType());
        return association;
    }

    public RepositoryAssociation describeRepositoryAssociation(String associationArn, String region) {
        return associations.get(lookupKey(associationArn, region))
                .orElseThrow(() -> associationNotFound(associationArn));
    }

    public RepositoryAssociation disassociateRepository(String associationArn, String region) {
        RepositoryAssociation association = describeRepositoryAssociation(associationArn, region);
        associations.delete(storageKey(region, association.getAssociationId()));
        association.setState(DISASSOCIATED);
        association.setLastUpdatedTimeStamp(Instant.now());
        LOG.infov("Disassociated CodeGuru Reviewer repository: {0}", association.getName());
        return association;
    }

    public PaginatedResult<RepositoryAssociation> listRepositoryAssociations(
            List<String> providerTypes, List<String> states, List<String> names, List<String> owners,
            Integer maxResults, String nextToken, String region) {
        String regionPrefix = region + "::";
        List<RepositoryAssociation> matching = associations.scan(key -> key.startsWith(regionPrefix)).stream()
                .filter(association -> matches(providerTypes, association.getProviderType()))
                .filter(association -> matches(states, association.getState()))
                .filter(association -> matches(names, association.getName()))
                .filter(association -> matches(owners, association.getOwner()))
                .sorted(Comparator.comparing(RepositoryAssociation::getName)
                        .thenComparing(RepositoryAssociation::getAssociationId))
                .toList();
        return Pagination.paginate(matching, RepositoryAssociation::getAssociationId,
                maxResults, nextToken, 100, "ValidationException");
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Override
    public String serviceKey() {
        return "codeguru-reviewer";
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = forTagging(arn, region).getTags();
        return tags != null ? tags : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        RepositoryAssociation association = forTagging(arn, region);
        if (association.getTags() == null) {
            association.setTags(new HashMap<>());
        }
        association.getTags().putAll(tags);
        associations.put(storageKey(region, association.getAssociationId()), association);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        RepositoryAssociation association = forTagging(arn, region);
        if (association.getTags() != null && tagKeys != null) {
            tagKeys.forEach(association.getTags()::remove);
        }
        associations.put(storageKey(region, association.getAssociationId()), association);
    }

    /**
     * The tag operations declare {@code ResourceNotFoundException} where the association
     * operations declare {@code NotFoundException}, so the lookup is repeated here with the
     * error code the tag path's model actually names.
     */
    private RepositoryAssociation forTagging(String arn, String region) {
        return associations.get(lookupKey(arn, region))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Repository association " + arn + " does not exist.", 404));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private void applyThirdParty(RepositoryAssociation association, JsonNode repository, String providerType) {
        association.setProviderType(providerType);
        association.setName(requireName(repository, "Repository." + providerType + ".Name"));

        String owner = text(repository, "Owner");
        if (owner == null || !OWNER.matcher(owner).matches() || owner.length() > 100) {
            throw new AwsException("ValidationException",
                    "Repository." + providerType + ".Owner is required and must be 1-100 characters.", 400);
        }
        association.setOwner(owner);

        String connectionArn = text(repository, "ConnectionArn");
        if (connectionArn == null || connectionArn.isBlank()) {
            throw new AwsException("ValidationException",
                    "Repository." + providerType + ".ConnectionArn is required.", 400);
        }
        association.setConnectionArn(connectionArn);
    }

    private void applyKmsKeyDetails(RepositoryAssociation association, JsonNode kmsKeyDetails) {
        String encryptionOption = "AWS_OWNED_CMK";
        String kmsKeyId = null;
        if (kmsKeyDetails != null && kmsKeyDetails.isObject()) {
            String requested = text(kmsKeyDetails, "EncryptionOption");
            if (requested != null) {
                if (!ENCRYPTION_OPTIONS.contains(requested)) {
                    throw new AwsException("ValidationException",
                            "KMSKeyDetails.EncryptionOption must be one of AWS_OWNED_CMK, CUSTOMER_MANAGED_CMK.",
                            400);
                }
                encryptionOption = requested;
            }
            kmsKeyId = text(kmsKeyDetails, "KMSKeyId");
        }
        if ("CUSTOMER_MANAGED_CMK".equals(encryptionOption) && (kmsKeyId == null || kmsKeyId.isBlank())) {
            throw new AwsException("ValidationException",
                    "KMSKeyDetails.KMSKeyId is required when EncryptionOption is CUSTOMER_MANAGED_CMK.", 400);
        }
        association.setEncryptionOption(encryptionOption);
        association.setKmsKeyId(kmsKeyId);
    }

    private RepositoryAssociation findByRepository(String providerType, String owner, String name, String region) {
        String regionPrefix = region + "::";
        return associations.scan(key -> key.startsWith(regionPrefix)).stream()
                .filter(association -> providerType.equals(association.getProviderType())
                        && owner.equals(association.getOwner())
                        && name.equals(association.getName()))
                .findFirst()
                .orElse(null);
    }

    private String lookupKey(String associationArn, String region) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(associationArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException",
                    "AssociationArn is not a valid CodeGuru Reviewer association ARN: " + associationArn, 400);
        }
        if (!"codeguru-reviewer".equals(arn.service()) || !arn.resource().startsWith(ARN_RESOURCE_PREFIX)) {
            throw new AwsException("ValidationException",
                    "AssociationArn is not a valid CodeGuru Reviewer association ARN: " + associationArn, 400);
        }
        String arnRegion = arn.region().isEmpty() ? region : arn.region();
        return storageKey(arnRegion, arn.resource().substring(ARN_RESOURCE_PREFIX.length()));
    }

    private String requireName(JsonNode repository, String field) {
        String name = text(repository, "Name");
        if (name == null || !NAME.matcher(name).matches() || name.length() > 100) {
            throw new AwsException("ValidationException",
                    field + " is required and must be 1-100 characters matching ^\\S[\\w.-]*$.", 400);
        }
        return name;
    }

    private boolean matches(List<String> filter, String value) {
        return filter == null || filter.isEmpty() || filter.contains(value);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private AwsException associationNotFound(String associationArn) {
        return new AwsException("NotFoundException",
                "Repository association " + associationArn + " does not exist.", 404);
    }

    private String storageKey(String region, String associationId) {
        return region + "::" + associationId;
    }
}
