package io.floci.aws.services.signer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.TagHandler;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.signer.model.SigningJob;
import io.floci.aws.services.signer.model.SigningProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The AWS Signer control plane: signing profiles, their cross-account permissions, and the signing
 * jobs started against a profile.
 *
 * <p>Signing profiles and jobs are metadata. Floci keeps the platform, the certificate a profile
 * signs with, the overrides and the S3 source and destination a job names, so SDK calls and
 * infrastructure-as-code round-trip, but no cryptographic signature is produced: a job started here
 * completes as {@code Succeeded} with the destination recorded as its signed object, and no object
 * is written to S3. That limit is documented on the service page and asserted by the integration
 * test, so nothing reports a signature that does not exist as a valid one.
 */
@ApplicationScoped
public class SignerService implements Resettable, TagHandler {

    private static final Logger LOG = Logger.getLogger(SignerService.class);
    private static final String SERVICE = "signer";
    private static final String PROFILE_RESOURCE = "/signing-profiles/";
    private static final String JOB_RESOURCE = "/signing-jobs/";

    private static final Pattern PROFILE_NAME = Pattern.compile("[0-9a-zA-Z_]{2,64}");
    private static final Pattern PROFILE_VERSION = Pattern.compile("[0-9a-zA-Z]{10}");
    private static final Pattern TAG_KEY = Pattern.compile("^(?!aws:)[a-zA-Z+-=._:/]+$");
    private static final Pattern STATEMENT_ID = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final Pattern CERTIFICATE_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":acm:[a-z0-9-]+:[0-9]+:certificate/.+");

    private static final Set<String> VALIDITY_TYPES = Set.of("DAYS", "MONTHS", "YEARS");
    private static final Map<String, Integer> MAX_VALIDITY =
            Map.of("DAYS", 4105, "MONTHS", 135, "YEARS", 11);
    private static final Set<String> PROFILE_STATUSES = Set.of("Active", "Canceled", "Revoked");
    private static final Set<String> JOB_STATUSES = Set.of("InProgress", "Failed", "Succeeded");
    private static final Set<String> CATEGORIES = Set.of("AWSIoT", "AWSLambda", "Notation-OCI");
    /**
     * The actions AWS lets a profile permission name. {@code signer:SignPayload} is grantable even
     * though the operation itself is not served: a policy that allows signing a payload is valid,
     * and refusing the grant would break templates that provision it.
     */
    private static final Set<String> PROFILE_PERMISSION_ACTIONS = Set.of("signer:StartSigningJob",
            "signer:SignPayload", "signer:GetSigningProfile", "signer:RevokeSignature");

    private static final int MAX_TAGS = 50;
    /** botocore caps both list operations' {@code maxResults} at 25, which is also their page size. */
    private static final int MAX_PAGE_SIZE = 25;
    private static final String ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final AccountAwareStorageBackend<SigningProfile> profiles;
    private final AccountAwareStorageBackend<SigningJob> jobs;
    private final boolean mockSigningJobs;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public SignerService(StorageFactory storageFactory, EmulatorConfig config) {
        this(storageFactory.create(SERVICE, "signer-signing-profiles.json",
                        new TypeReference<Map<String, SigningProfile>>() {}),
                storageFactory.create(SERVICE, "signer-signing-jobs.json",
                        new TypeReference<Map<String, SigningJob>>() {}),
                config.services().signer().mockSigningJobs());
    }

    SignerService(AccountAwareStorageBackend<SigningProfile> profiles,
                  AccountAwareStorageBackend<SigningJob> jobs) {
        this(profiles, jobs, false);
    }

    SignerService(AccountAwareStorageBackend<SigningProfile> profiles,
                  AccountAwareStorageBackend<SigningJob> jobs, boolean mockSigningJobs) {
        this.profiles = profiles;
        this.jobs = jobs;
        this.mockSigningJobs = mockSigningJobs;
    }

    /** A page of results, with the token a caller passes back to continue. */
    public record Page<T>(List<T> items, String nextToken) {
    }

    // ---------------------------------------------------------------- profiles

    public SigningProfile putSigningProfile(String profileName, JsonNode request, String region) {
        validateProfileName(profileName);
        requireObject(request, "Request payload");
        SignerPlatforms.Platform platform = requirePlatform(requiredText(request, "platformId"));
        String certificateArn = optionalCertificateArn(request);
        String[] validity = optionalValidityPeriod(request);
        JsonNode overrides = optionalOverrides(request, platform);
        Map<String, String> signingParameters = optionalStringMap(request, "signingParameters");
        Map<String, String> requestedTags = optionalTags(request);
        return applyProfile(profileName, platform, certificateArn, validity, overrides,
                signingParameters, requestedTags, region);
    }

    /**
     * Creates or updates a profile from typed values, for a caller that does not speak the wire
     * protocol: the CloudFormation provisioner for {@code AWS::Signer::SigningProfile}. A template
     * cannot set signing material, overrides or signing parameters, so those stay empty. Validation
     * is the same one the API applies, so a template that names an unknown platform or an
     * out-of-range validity period fails the stack with the Signer message.
     */
    public SigningProfile putSigningProfile(String profileName, String platformId, String validityType,
                                            Integer validityValue, Map<String, String> tags,
                                            String region) {
        validateProfileName(profileName);
        SignerPlatforms.Platform platform = requirePlatform(platformId);
        String[] validity = typedValidityPeriod(validityType, validityValue);
        Map<String, String> requestedTags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
        validateTags(requestedTags);
        return applyProfile(profileName, platform, null, validity, null,
                new LinkedHashMap<>(), requestedTags, region);
    }

    private SigningProfile applyProfile(String profileName, SignerPlatforms.Platform platform,
                                        String certificateArn, String[] validity, JsonNode overrides,
                                        Map<String, String> signingParameters,
                                        Map<String, String> requestedTags, String region) {
        SigningProfile profile = profiles.get(storageKey(region, profileName)).orElse(null);
        boolean created = profile == null;
        if (created) {
            profile = new SigningProfile();
            profile.setProfileName(profileName);
            profile.setStatus("Active");
            profile.setRevisionId(newRevisionId());
            profile.setTags(new LinkedHashMap<>(requestedTags));
        } else if (!requestedTags.isEmpty()) {
            profile.setTags(new LinkedHashMap<>(requestedTags));
        }
        String accountId = profiles.accountId();
        String arn = AwsArnUtils.Arn.of(SERVICE, region, accountId, PROFILE_RESOURCE + profileName)
                .toString();
        String version = newProfileVersion();
        profile.setArn(arn);
        profile.setPlatformId(platform.platformId());
        profile.setPlatformDisplayName(platform.displayName());
        profile.setProfileVersion(version);
        profile.setProfileVersionArn(arn + "/" + version);
        if (certificateArn != null) {
            profile.setSigningMaterialCertificateArn(certificateArn);
        }
        if (validity[0] != null) {
            profile.setSignatureValidityPeriodType(validity[0]);
            profile.setSignatureValidityPeriodValue(Integer.valueOf(validity[1]));
        }
        if (overrides != null) {
            profile.setOverrides(overrides);
        }
        if (!signingParameters.isEmpty()) {
            profile.setSigningParameters(signingParameters);
        }
        profile.setStatus("Active");
        profile.setStatusReason(null);
        profile.setRevocationEffectiveFrom(null);
        profile.setRevokedAt(null);
        profile.setRevokedBy(null);
        profiles.put(storageKey(region, profileName), profile);
        LOG.debugv("{0} signing profile {1} version {2}", created ? "Created" : "Updated",
                profileName, version);
        return profile;
    }

    public SigningProfile getSigningProfile(String profileName, String region) {
        validateProfileName(profileName);
        return requireProfile(profileName, region);
    }

    public Page<SigningProfile> listSigningProfiles(String region, String platformId,
                                                    List<String> statuses, Boolean includeCanceled,
                                                    Integer maxResults, String nextToken) {
        List<String> requestedStatuses = statuses == null ? List.of() : statuses;
        for (String status : requestedStatuses) {
            if (!PROFILE_STATUSES.contains(status)) {
                throw validation("status " + status + " is not one of " + PROFILE_STATUSES + ".");
            }
        }
        boolean canceledWanted = Boolean.TRUE.equals(includeCanceled);
        List<SigningProfile> matching = new ArrayList<>();
        for (SigningProfile profile : sortedProfiles(region)) {
            if (platformId != null && !platformId.equals(profile.getPlatformId())) {
                continue;
            }
            if (!requestedStatuses.isEmpty() && !requestedStatuses.contains(profile.getStatus())) {
                continue;
            }
            if ("Canceled".equals(profile.getStatus()) && !canceledWanted
                    && !requestedStatuses.contains("Canceled")) {
                continue;
            }
            matching.add(profile);
        }
        PaginatedResult<SigningProfile> page = Pagination.paginate(matching,
                SigningProfile::getProfileName, maxResults, nextToken, MAX_PAGE_SIZE,
                "ValidationException");
        return new Page<>(page.items(), page.nextToken());
    }

    /** The profile, when it exists, for a caller that has to tell a first create from a takeover. */
    public Optional<SigningProfile> findSigningProfile(String profileName, String region) {
        return profiles.get(storageKey(region, profileName));
    }

    /**
     * Writes a profile record for a CloudFormation resource that was provisioned before this
     * service existed, keeping the identifiers that stack already handed out instead of minting a
     * version: the adoption a later tags-only update needs.
     */
    public SigningProfile adoptSigningProfile(String profileName, String platformId, String version,
                                              String validityType, Integer validityValue,
                                              String region) {
        validateProfileName(profileName);
        SignerPlatforms.Platform platform = platformOf(platformId);
        String arn = AwsArnUtils.Arn.of(SERVICE, region, profiles.accountId(),
                PROFILE_RESOURCE + profileName).toString();
        SigningProfile profile = new SigningProfile();
        profile.setProfileName(profileName);
        profile.setArn(arn);
        profile.setPlatformId(platform.platformId());
        profile.setPlatformDisplayName(platform.displayName());
        profile.setProfileVersion(version);
        profile.setProfileVersionArn(arn + "/" + version);
        profile.setSignatureValidityPeriodType(validityType);
        profile.setSignatureValidityPeriodValue(validityValue);
        profile.setStatus("Active");
        profile.setRevisionId(newRevisionId());
        profiles.put(storageKey(region, profileName), profile);
        LOG.debugv("Adopted signing profile {0} version {1} from CloudFormation", profileName, version);
        return profile;
    }

    /**
     * Cancels a profile, which AWS keeps rather than deletes: {@code GetSigningProfile} still
     * answers it, with the same ARN and version, as {@code Canceled}, and it stops accepting jobs
     * until {@code PutSigningProfile} creates a new version of it. Listing hides it unless the
     * caller asks for canceled profiles.
     */
    public void cancelSigningProfile(String profileName, String region) {
        validateProfileName(profileName);
        SigningProfile profile = requireProfile(profileName, region);
        if ("Revoked".equals(profile.getStatus())) {
            throw conflict("Signing profile " + profileName + " is revoked and cannot be canceled.");
        }
        if ("Canceled".equals(profile.getStatus())) {
            return;
        }
        profile.setStatus("Canceled");
        profiles.put(storageKey(region, profileName), profile);
        LOG.debugv("Canceled signing profile {0} version {1}", profileName,
                profile.getProfileVersion());
    }

    public SigningProfile revokeSigningProfile(String profileName, JsonNode request, String region) {
        validateProfileName(profileName);
        requireObject(request, "Request payload");
        SigningProfile profile = requireProfile(profileName, region);
        String profileVersion = requiredText(request, "profileVersion");
        String reason = requiredText(request, "reason");
        long effectiveTime = requiredTimestamp(request, "effectiveTime");
        if (!profileVersionMatches(profile, profileVersion)) {
            throw validation("profileVersion " + profileVersion
                    + " is not the profile's current version.");
        }
        if ("Revoked".equals(profile.getStatus())) {
            throw conflict("Signing profile " + profileName + " is already revoked.");
        }
        profile.setStatus("Revoked");
        profile.setStatusReason(reason);
        profile.setRevocationEffectiveFrom(effectiveTime);
        profile.setRevokedAt(Instant.now().getEpochSecond());
        profile.setRevokedBy(profiles.accountId());
        profiles.put(storageKey(region, profileName), profile);
        return profile;
    }

    // ------------------------------------------------------------- permissions

    public String addProfilePermission(String profileName, JsonNode request, String region) {
        validateProfileName(profileName);
        requireObject(request, "Request payload");
        SigningProfile profile = requireProfile(profileName, region);
        String action = requiredText(request, "action");
        if (!PROFILE_PERMISSION_ACTIONS.contains(action)) {
            throw validation("action " + action + " is not one of " + PROFILE_PERMISSION_ACTIONS
                    + ".");
        }
        String principal = requiredText(request, "principal");
        String statementId = requiredText(request, "statementId");
        if (!STATEMENT_ID.matcher(statementId).matches()) {
            throw validation("statementId must be 1 to 64 characters from [a-zA-Z0-9_-], got "
                    + statementId + ".");
        }
        String requestedRevision = optionalText(request, "revisionId");
        if (requestedRevision != null && !requestedRevision.equals(profile.getRevisionId())) {
            throw conflict("revisionId " + requestedRevision + " is not the profile's current revision.");
        }
        String profileVersion = optionalText(request, "profileVersion");
        if (profileVersion != null && !profileVersionMatches(profile, profileVersion)) {
            throw validation("profileVersion " + profileVersion
                    + " is not the profile's current version.");
        }
        for (SigningProfile.ProfilePermission permission : profile.getPermissions()) {
            if (permission.getStatementId().equals(statementId)) {
                throw conflict("A permission with statementId " + statementId + " already exists.");
            }
        }
        if (profile.getPermissions().size() >= 20) {
            throw validation("A signing profile supports at most 20 permissions.");
        }
        profile.getPermissions().add(new SigningProfile.ProfilePermission(
                action, principal, statementId, profile.getProfileVersion()));
        profile.setRevisionId(newRevisionId());
        profiles.put(storageKey(region, profileName), profile);
        return profile.getRevisionId();
    }

    public List<SigningProfile.ProfilePermission> listProfilePermissions(String profileName,
                                                                         String region) {
        validateProfileName(profileName);
        return requireProfile(profileName, region).getPermissions();
    }

    public String removeProfilePermission(String profileName, String statementId, String revisionId,
                                          String region) {
        validateProfileName(profileName);
        SigningProfile profile = requireProfile(profileName, region);
        if (revisionId == null || revisionId.isBlank()) {
            throw validation("revisionId is required.");
        }
        if (!revisionId.equals(profile.getRevisionId())) {
            throw conflict("revisionId " + revisionId + " is not the profile's current revision.");
        }
        boolean removed = profile.getPermissions()
                .removeIf(permission -> permission.getStatementId().equals(statementId));
        if (!removed) {
            throw notFound("Permission " + statementId + " does not exist on profile "
                    + profileName + ".");
        }
        profile.setRevisionId(newRevisionId());
        profiles.put(storageKey(region, profileName), profile);
        return profile.getRevisionId();
    }

    // ------------------------------------------------------------------- jobs

    public SigningJob startSigningJob(JsonNode request, String region) {
        requireObject(request, "Request payload");
        String profileName = requiredText(request, "profileName");
        validateProfileName(profileName);
        String clientRequestToken = requiredText(request, "clientRequestToken");
        for (SigningJob existing : sortedJobs(region)) {
            if (clientRequestToken.equals(existing.getClientRequestToken())) {
                return existing;
            }
        }
        SigningProfile profile = requireProfile(profileName, region);
        if (!"Active".equals(profile.getStatus())) {
            throw validation("Signing profile " + profileName
                    + " is " + profile.getStatus() + " and cannot start a signing job.");
        }
        JsonNode source = requireObject(request.path("source"), "source");
        JsonNode sourceS3 = requireObject(source.path("s3"), "source.s3");
        String sourceBucket = requiredText(sourceS3, "bucketName");
        String sourceKey = requiredText(sourceS3, "key");
        String sourceVersion = requiredText(sourceS3, "version");
        JsonNode destination = requireObject(request.path("destination"), "destination");
        JsonNode destinationS3 = requireObject(destination.path("s3"), "destination.s3");
        String destinationBucket = requiredText(destinationS3, "bucketName");
        String destinationPrefix = optionalText(destinationS3, "prefix");

        String jobId = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        String accountId = jobs.accountId();
        SigningJob job = new SigningJob();
        job.setJobId(jobId);
        job.setClientRequestToken(clientRequestToken);
        job.setJobArn(AwsArnUtils.Arn.of(SERVICE, region, accountId, JOB_RESOURCE + jobId).toString());
        job.setProfileName(profileName);
        job.setProfileVersion(profile.getProfileVersion());
        job.setPlatformId(profile.getPlatformId());
        job.setPlatformDisplayName(profile.getPlatformDisplayName());
        job.setCreatedAt(now);
        job.setCompletedAt(now);
        job.setRequestedBy(accountId);
        job.setJobOwner(accountId);
        job.setJobInvoker(accountId);
        job.setSourceBucketName(sourceBucket);
        job.setSourceKey(sourceKey);
        job.setSourceVersion(sourceVersion);
        job.setDestinationBucketName(destinationBucket);
        job.setDestinationPrefix(destinationPrefix);
        if (mockSigningJobs) {
            job.setStatus("Succeeded");
            job.setSignedObjectBucketName(destinationBucket);
            job.setSignedObjectKey(signedObjectKey(destinationPrefix, sourceKey));
            job.setSignatureExpiresAt(
                    signatureExpiresAt(profile, platformOf(profile.getPlatformId()), now));
            LOG.debugv("Recorded signing job {0} for profile {1} as succeeded, as configured",
                    jobId, profileName);
        } else {
            // Nothing signs the artifact, so a succeeded job would advertise a key that is not
            // there and every download of it would fail. AWS fails a job the same way, with the
            // reason on the job, and Floci keeps that shape rather than an empty success.
            job.setStatus("Failed");
            job.setStatusReason("Floci does not sign artifacts: no signed object was written to "
                    + "s3://" + destinationBucket + "/"
                    + signedObjectKey(destinationPrefix, sourceKey)
                    + ". Set floci.services.signer.mock-signing-jobs=true to record jobs as "
                    + "succeeded without an artifact.");
            LOG.debugv("Signing job {0} for profile {1} failed: signing is not emulated", jobId,
                    profileName);
        }
        jobs.put(storageKey(region, jobId), job);
        return job;
    }

    public SigningJob describeSigningJob(String jobId, String region) {
        return requireJob(jobId, region);
    }

    public Page<SigningJob> listSigningJobs(String region, String status, String platformId,
                                            Boolean isRevoked, Integer maxResults, String nextToken) {
        if (status != null && !JOB_STATUSES.contains(status)) {
            throw validation("status " + status + " is not one of " + JOB_STATUSES + ".");
        }
        List<SigningJob> matching = new ArrayList<>();
        for (SigningJob job : sortedJobs(region)) {
            if (status != null && !status.equals(job.getStatus())) {
                continue;
            }
            if (platformId != null && !platformId.equals(job.getPlatformId())) {
                continue;
            }
            if (isRevoked != null && isRevoked.booleanValue() != job.isRevoked()) {
                continue;
            }
            matching.add(job);
        }
        PaginatedResult<SigningJob> page = Pagination.paginate(matching,
                SignerService::jobCursor, maxResults, nextToken, MAX_PAGE_SIZE, "ValidationException");
        return new Page<>(page.items(), page.nextToken());
    }

    public SigningJob revokeSignature(String jobId, JsonNode request, String region) {
        requireObject(request, "Request payload");
        SigningJob job = requireJob(jobId, region);
        String reason = requiredText(request, "reason");
        SignerPlatforms.Platform platform = platformOf(job.getPlatformId());
        if (!platform.revocationSupported()) {
            throw validation("Signing platform " + platform.platformId()
                    + " does not support revocation.");
        }
        if (!"Succeeded".equals(job.getStatus())) {
            throw validation("Signing job " + jobId + " is " + job.getStatus()
                    + " and has no signature to revoke.");
        }
        if (job.isRevoked()) {
            throw conflict("Signing job " + jobId + " is already revoked.");
        }
        job.setRevoked(true);
        job.setRevocationReason(reason);
        job.setRevokedAt(Instant.now().getEpochSecond());
        job.setRevokedBy(jobs.accountId());
        jobs.put(storageKey(region, jobId), job);
        return job;
    }

    /**
     * The revocation status of the signatures a caller asks about. Every signature a Floci job
     * records is one Floci did not produce, so nothing this service issued can be revoked: the
     * answer is always an empty list, which is what AWS answers for signatures it cannot match.
     */
    public List<String> getRevocationStatus() {
        return List.of();
    }

    // -------------------------------------------------------------- platforms

    public List<SignerPlatforms.Platform> listSigningPlatforms(String category) {
        if (category != null && !CATEGORIES.contains(category)) {
            throw validation("category " + category + " is not one of " + CATEGORIES + ".");
        }
        if (category == null) {
            return SignerPlatforms.all();
        }
        return SignerPlatforms.all().stream()
                .filter(platform -> platform.category().equals(category))
                .toList();
    }

    public SignerPlatforms.Platform getSigningPlatform(String platformId) {
        return SignerPlatforms.find(platformId)
                .orElseThrow(() -> notFound("Signing platform " + platformId + " does not exist."));
    }

    // ------------------------------------------------------------------- tags

    /**
     * Sets a profile's tags to exactly the given set, which is what CloudFormation does with the
     * {@code Tags} property on an update that changed nothing else. Unlike {@link #tagResource}, it
     * removes the tags the caller left out.
     */
    public void replaceTags(String profileName, Map<String, String> tags, String region) {
        validateProfileName(profileName);
        SigningProfile profile = requireProfile(profileName, region);
        Map<String, String> resolved = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
        validateTags(resolved);
        profile.setTags(resolved);
        profiles.put(storageKey(region, profileName), profile);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return new LinkedHashMap<>(requireProfileFromArn(region, arn).getTags());
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        validateTags(tags);
        SigningProfile profile = requireProfileFromArn(region, arn);
        Map<String, String> merged = new LinkedHashMap<>(profile.getTags());
        merged.putAll(tags);
        if (merged.size() > MAX_TAGS) {
            throw validation("A signing profile supports at most " + MAX_TAGS + " tags.");
        }
        profile.setTags(merged);
        profiles.put(storageKey(region, profile.getProfileName()), profile);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        SigningProfile profile = requireProfileFromArn(region, arn);
        Map<String, String> remaining = new LinkedHashMap<>(profile.getTags());
        tagKeys.forEach(remaining::remove);
        profile.setTags(remaining);
        profiles.put(storageKey(region, profile.getProfileName()), profile);
    }

    @Override
    public void clear() {
        profiles.clear();
        jobs.clear();
    }

    // ---------------------------------------------------------------- helpers

    private SigningProfile requireProfile(String profileName, String region) {
        return profiles.get(storageKey(region, profileName))
                .orElseThrow(() -> notFound("Signing profile " + profileName + " does not exist."));
    }

    private SigningProfile requireProfileFromArn(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (RuntimeException e) {
            throw validation("The ARN " + arn + " is not a signing profile ARN.");
        }
        if (!SERVICE.equals(parsed.service()) || !region.equals(parsed.region())) {
            throw notFound("No signing profile matches the ARN " + arn + " in " + region + ".");
        }
        if (!profiles.accountId().equals(parsed.accountId())) {
            throw notFound("No signing profile matches the ARN " + arn + " in account "
                    + profiles.accountId() + ".");
        }
        String resource = parsed.resource().startsWith("/") ? parsed.resource() : "/" + parsed.resource();
        if (!resource.startsWith(PROFILE_RESOURCE)) {
            throw validation("The ARN " + arn + " does not identify a signing profile.");
        }
        return requireProfile(resource.substring(PROFILE_RESOURCE.length()), region);
    }

    private SigningJob requireJob(String jobId, String region) {
        return jobs.get(storageKey(region, jobId))
                .orElseThrow(() -> notFound("Signing job " + jobId + " does not exist."));
    }

    private List<SigningProfile> sortedProfiles(String region) {
        List<SigningProfile> result =
                profiles.scan(key -> key.startsWith(region + "::")).stream().toList();
        List<SigningProfile> sorted = new ArrayList<>(result);
        sorted.sort((left, right) -> left.getProfileName().compareTo(right.getProfileName()));
        return sorted;
    }

    private List<SigningJob> sortedJobs(String region) {
        List<SigningJob> result = jobs.scan(key -> key.startsWith(region + "::")).stream().toList();
        List<SigningJob> sorted = new ArrayList<>(result);
        sorted.sort((left, right) -> {
            int byCreation = left.getCreatedAt().compareTo(right.getCreatedAt());
            return byCreation != 0 ? byCreation : left.getJobId().compareTo(right.getJobId());
        });
        return sorted;
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    /** A job's position in the list: its creation time and id, which no two jobs share. */
    private static String jobCursor(SigningJob job) {
        return job.getCreatedAt() + ":" + job.getJobId();
    }

    private void validateProfileName(String profileName) {
        if (profileName == null || !PROFILE_NAME.matcher(profileName).matches()) {
            throw validation("profileName must be 2 to 64 characters from [0-9a-zA-Z_], got "
                    + profileName + ".");
        }
    }

    private boolean profileVersionMatches(SigningProfile profile, String profileVersion) {
        if (!PROFILE_VERSION.matcher(profileVersion).matches()) {
            return false;
        }
        return profileVersion.equals(profile.getProfileVersion());
    }

    private String newProfileVersion() {
        StringBuilder version = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            version.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return version.toString();
    }

    private String newRevisionId() {
        return UUID.randomUUID().toString();
    }

    /**
     * The form the Signer model unmarshals for its timestamp members: epoch seconds, as a JSON
     * number. An ISO 8601 string is coerced by the AWS CLI, which hides the mismatch, but every
     * strict SDK rejects the whole response, so nothing here emits one.
     */
    private static long epochSeconds(Instant instant) {
        return instant.getEpochSecond();
    }

    private static String signedObjectKey(String prefix, String sourceKey) {
        return prefix == null || prefix.isEmpty() ? sourceKey : prefix + sourceKey;
    }

    /**
     * When the signature a job produces expires: the profile's validity period, or the one Signer
     * applies by default when the profile sets none (135 months, on the platforms that expire
     * signatures). A platform whose signatures carry no validity answers nothing.
     */
    private static Long signatureExpiresAt(SigningProfile profile, SignerPlatforms.Platform platform,
                                           long now) {
        Instant created = Instant.ofEpochSecond(now);
        String type = profile.getSignatureValidityPeriodType();
        int value;
        if (type == null) {
            if (platform.defaultValidityMonths() < 1) {
                return null;
            }
            type = "MONTHS";
            value = platform.defaultValidityMonths();
        } else {
            value = profile.getSignatureValidityPeriodValue().intValue();
        }
        return switch (type) {
            case "DAYS" -> epochSeconds(created.plus(value, ChronoUnit.DAYS));
            case "MONTHS" -> epochSeconds(created.plus(value * 30L, ChronoUnit.DAYS));
            case "YEARS" -> epochSeconds(created.plus(value * 365L, ChronoUnit.DAYS));
            default -> null;
        };
    }

    private static SignerPlatforms.Platform platformOf(String platformId) {
        return SignerPlatforms.find(platformId)
                .orElseThrow(() -> validation("Signing platform "
                        + platformId + " is not a platform Floci serves."));
    }

    private static String optionalCertificateArn(JsonNode request) {
        JsonNode material = request.path("signingMaterial");
        if (material.isMissingNode() || material.isNull()) {
            return null;
        }
        requireObject(material, "signingMaterial");
        String certificateArn = requiredText(material, "certificateArn");
        if (!CERTIFICATE_ARN.matcher(certificateArn).matches()) {
            throw validation("signingMaterial.certificateArn must be an ACM certificate ARN, got "
                    + certificateArn + ".");
        }
        return certificateArn;
    }

    private static String[] optionalValidityPeriod(JsonNode request) {
        JsonNode period = request.path("signatureValidityPeriod");
        if (period.isMissingNode() || period.isNull()) {
            return new String[] {null, null};
        }
        requireObject(period, "signatureValidityPeriod");
        String type = requiredText(period, "type");
        if (!VALIDITY_TYPES.contains(type)) {
            throw validation("signatureValidityPeriod.type must be one of " + VALIDITY_TYPES
                    + ", got " + type + ".");
        }
        JsonNode valueNode = period.path("value");
        if (!valueNode.isIntegralNumber()) {
            throw validation("signatureValidityPeriod.value must be an integer.");
        }
        int value = valueNode.intValue();
        int maximum = MAX_VALIDITY.get(type).intValue();
        if (value < 1 || value > maximum) {
            throw validation("signatureValidityPeriod.value must be between 1 and " + maximum
                    + " for " + type + ", got " + value + ".");
        }
        return new String[] {type, String.valueOf(value)};
    }

    private static SignerPlatforms.Platform requirePlatform(String platformId) {
        return SignerPlatforms.find(platformId)
                .orElseThrow(() -> validation("Signing platform "
                        + platformId + " is not a platform Floci serves."));
    }

    private static String[] typedValidityPeriod(String type, Integer value) {
        if (type == null && value == null) {
            return new String[] {null, null};
        }
        if (type == null || !VALIDITY_TYPES.contains(type)) {
            throw validation("signatureValidityPeriod.type must be one of " + VALIDITY_TYPES
                    + ", got " + type + ".");
        }
        if (value == null) {
            throw validation("signatureValidityPeriod.value must be an integer.");
        }
        int maximum = MAX_VALIDITY.get(type).intValue();
        if (value.intValue() < 1 || value.intValue() > maximum) {
            throw validation("signatureValidityPeriod.value must be between 1 and " + maximum
                    + " for " + type + ", got " + value + ".");
        }
        return new String[] {type, String.valueOf(value)};
    }

    private static JsonNode optionalOverrides(JsonNode request, SignerPlatforms.Platform platform) {
        JsonNode overrides = request.path("overrides");
        if (overrides.isMissingNode() || overrides.isNull()) {
            return null;
        }
        requireObject(overrides, "overrides");
        JsonNode configuration = overrides.path("signingConfiguration");
        if (!configuration.isMissingNode() && !configuration.isNull()) {
            requireObject(configuration, "overrides.signingConfiguration");
            String hashAlgorithm = optionalText(configuration, "hashAlgorithm");
            if (hashAlgorithm != null && !platform.hashAlgorithms().contains(hashAlgorithm)) {
                throw validation("hashAlgorithm " + hashAlgorithm + " is not accepted by platform "
                        + platform.platformId() + ", which allows " + platform.hashAlgorithms() + ".");
            }
            String encryptionAlgorithm = optionalText(configuration, "encryptionAlgorithm");
            if (encryptionAlgorithm != null
                    && !platform.encryptionAlgorithms().contains(encryptionAlgorithm)) {
                throw validation("encryptionAlgorithm " + encryptionAlgorithm
                        + " is not accepted by platform " + platform.platformId()
                        + ", which allows " + platform.encryptionAlgorithms() + ".");
            }
        }
        String imageFormat = optionalText(overrides, "signingImageFormat");
        if (imageFormat != null && !platform.imageFormats().contains(imageFormat)) {
            throw validation("signingImageFormat " + imageFormat + " is not accepted by platform "
                    + platform.platformId() + ", which allows " + platform.imageFormats() + ".");
        }
        return overrides;
    }

    private static Map<String, String> optionalStringMap(JsonNode request, String member) {
        JsonNode node = request.path(member);
        if (node.isMissingNode() || node.isNull()) {
            return new LinkedHashMap<>();
        }
        requireObject(node, member);
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isValueNode()) {
                throw validation(member + "." + entry.getKey() + " must be a string.");
            }
            values.put(entry.getKey(), entry.getValue().asText());
        });
        return values;
    }

    private static Map<String, String> optionalTags(JsonNode request) {
        JsonNode tags = request.path("tags");
        if (tags.isMissingNode() || tags.isNull()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> values = optionalStringMap(request, "tags");
        validateTags(values);
        return values;
    }

    private static void validateTags(Map<String, String> tags) {
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            String key = tag.getKey() == null ? "" : tag.getKey();
            if (!TAG_KEY.matcher(key).matches()) {
                throw validation("Tag key " + key
                        + " must be 1 to 128 characters and must not use the aws: prefix.");
            }
            if (tag.getValue() == null || tag.getValue().length() > 256) {
                throw validation("Tag value for " + key + " must be at most 256 characters.");
            }
        }
        if (tags.size() > MAX_TAGS) {
            throw validation("A signing profile supports at most " + MAX_TAGS + " tags.");
        }
    }

    private static JsonNode requireObject(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            throw validation(name + " must be a JSON object.");
        }
        return node;
    }

    private static String requiredText(JsonNode request, String member) {
        String value = optionalText(request, member);
        if (value == null) {
            throw validation(member + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode request, String member) {
        JsonNode node = request.path(member);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw validation(member + " must be a string.");
        }
        String value = node.asText();
        if (value.isEmpty()) {
            throw validation(member + " must not be empty.");
        }
        return value;
    }

    private static long requiredTimestamp(JsonNode request, String member) {
        JsonNode node = request.path(member);
        if (node.isMissingNode() || node.isNull()) {
            throw validation(member + " is required.");
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        if (!node.isTextual()) {
            throw validation(member + " must be an ISO 8601 timestamp or epoch seconds.");
        }
        try {
            return Instant.parse(node.asText()).getEpochSecond();
        } catch (RuntimeException e) {
            throw validation(member + " must be an ISO 8601 timestamp, got " + node.asText() + ".");
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
