package io.floci.aws.services.rolesanywhere;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.rolesanywhere.model.AttributeMapping;
import io.floci.aws.services.rolesanywhere.model.Crl;
import io.floci.aws.services.rolesanywhere.model.NotificationSetting;
import io.floci.aws.services.rolesanywhere.model.Profile;
import io.floci.aws.services.rolesanywhere.model.Subject;
import io.floci.aws.services.rolesanywhere.model.TrustAnchor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CRLException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The IAM Roles Anywhere control plane: trust anchors, profiles, certificate revocation lists, subjects, their tags
 * and the notification settings of a trust anchor.
 *
 * <p>Every resource is regional and named by a UUID; a request may name a trust anchor, a profile or a CRL by that
 * UUID, by its ARN or by the ARN of its alias. Requests are checked against the constraints of the botocore model
 * and refused with the Smithy validation message SDKs expect. Notification settings are stored and reported but
 * nothing is ever emitted for them. {@code CreateSession}, which vends credentials to a certificate, is not part of
 * this service yet, so no subject is ever written here: the subjects are read-only.
 */
@ApplicationScoped
public class RolesAnywhereService implements Resettable, StoredResourceArns {

    private static final Logger LOG = Logger.getLogger(RolesAnywhereService.class);
    private static final String SERVICE = "rolesanywhere";
    static final String TRUST_ANCHOR = "trust-anchor";
    static final String PROFILE = "profile";
    static final String CRL = "crl";
    static final String SUBJECT = "subject";
    private static final String ALIAS = "alias/";

    private static final Pattern UUID_PATTERN = Pattern.compile("[a-f0-9]{8}-([a-z0-9]{4}-){3}[a-z0-9]{12}");
    private static final String UUID_SHAPE = ".*[a-f0-9]{8}-([a-z0-9]{4}-){3}[a-z0-9]{12}.*";
    private static final String RESOURCE_NAME = "[ a-zA-Z0-9-_]*";
    private static final String ALIAS_NAME = "(?!.*//)([A-Za-z]([A-Za-z0-9_/-]*[A-Za-z0-9_-])?)?";
    private static final String TAG_PATTERN = "[ a-zA-Z0-9_.:/=+@-]*";
    /** The model's own patterns, quoted verbatim in the Smithy messages; each accepts every partition. */
    private static final String TRUST_ANCHOR_IDENTIFIER = "([a-f0-9]{8}-([a-z0-9]{4}-){3}[a-z0-9]{12}|arn:aws(-[^:]+)?:rolesanywhere(:.*){2}:(trust-anchor|alias/trust-anchor)/.*)"; // partition-literal: the model's pattern, which matches every partition
    private static final String PROFILE_IDENTIFIER = "([a-f0-9]{8}-([a-z0-9]{4}-){3}[a-z0-9]{12}|arn:aws(-[^:]+)?:rolesanywhere(:.*){2}:(profile|alias/profile)/.*)"; // partition-literal: the model's pattern, which matches every partition
    private static final String CRL_IDENTIFIER = "([a-f0-9]{8}-([a-z0-9]{4}-){3}[a-z0-9]{12}|arn:aws(-[^:]+)?:rolesanywhere(:.*){2}:(crl|alias/crl)/.*)"; // partition-literal: the model's pattern, which matches every partition
    private static final String TRUST_ANCHOR_IDENTIFIER_ARN = "arn:aws(-[^:]+)?:rolesanywhere(:.*){2}(:trust-anchor.*|:alias/trust-anchor/.*)"; // partition-literal: the model's pattern, which matches every partition
    private static final String ROLE_ARN = "arn:aws(-[^:]+)?:iam(:.*){2}(:role.*)"; // partition-literal: the model's pattern, which matches every partition

    static final List<String> SOURCE_TYPES = List.of("AWS_ACM_PCA", "CERTIFICATE_BUNDLE", "SELF_SIGNED_REPOSITORY");
    static final List<String> CERTIFICATE_FIELDS = List.of("x509Subject", "x509Issuer", "x509SAN");
    static final List<String> NOTIFICATION_EVENTS = List.of("CA_CERTIFICATE_EXPIRY", "END_ENTITY_CERTIFICATE_EXPIRY");
    static final List<String> NOTIFICATION_CHANNELS = List.of("ALL");
    private static final List<String> TIP_STATUSES = List.of("ENABLED", "DISABLED");
    private static final List<String> TIP_SUBJECT_TYPES = List.of("user", "workload");
    private static final List<String> TIP_SUBJECT_SOURCES = List.of("x509SAN/URI", "x509Subject/CN");

    /**
     * The attribute mappings a new profile starts with, one per certificate field: the subject and issuer RDNs and
     * the SAN name types that become session tags. AWS's default list could not be checked against a live account
     * from where this was written; the service page records that.
     */
    static final Map<String, List<String>> DEFAULT_ATTRIBUTE_MAPPINGS = defaultAttributeMappings();

    static final int DEFAULT_NOTIFICATION_THRESHOLD = 45;
    static final int DEFAULT_DURATION_SECONDS = 3600;
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_TAGS = 200;
    private static final int MAX_CRL_BYTES = 300000;
    private static final int MAX_CERTIFICATE_DATA = 204800;

    private final AccountAwareStorageBackend<TrustAnchor> trustAnchors;
    private final AccountAwareStorageBackend<Profile> profiles;
    private final AccountAwareStorageBackend<Crl> crls;
    private final AccountAwareStorageBackend<Subject> subjects;
    private final RegionResolver regionResolver;
    private final Supplier<String> callerArn;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    public RolesAnywhereService(StorageFactory storageFactory, RegionResolver regionResolver,
                                IamService iamService, RequestContext requestContext) {
        this(storageFactory.create(SERVICE, "rolesanywhere-trust-anchors.json",
                        new TypeReference<Map<String, TrustAnchor>>() {}),
                storageFactory.create(SERVICE, "rolesanywhere-profiles.json",
                        new TypeReference<Map<String, Profile>>() {}),
                storageFactory.create(SERVICE, "rolesanywhere-crls.json",
                        new TypeReference<Map<String, Crl>>() {}),
                storageFactory.create(SERVICE, "rolesanywhere-subjects.json",
                        new TypeReference<Map<String, Subject>>() {}),
                regionResolver,
                () -> iamService.resolveCallerArn(requestContext.getAccessKeyId())
                        .orElseGet(() -> regionResolver.buildGlobalArn("iam", regionResolver.getAccountId(), "root")));
    }

    /** For tests: the caller is the root user of the backends' account. */
    RolesAnywhereService(AccountAwareStorageBackend<TrustAnchor> trustAnchors,
                         AccountAwareStorageBackend<Profile> profiles,
                         AccountAwareStorageBackend<Crl> crls,
                         AccountAwareStorageBackend<Subject> subjects,
                         RegionResolver regionResolver) {
        this(trustAnchors, profiles, crls, subjects, regionResolver,
                () -> regionResolver.buildGlobalArn("iam", trustAnchors.accountId(), "root"));
    }

    RolesAnywhereService(AccountAwareStorageBackend<TrustAnchor> trustAnchors,
                         AccountAwareStorageBackend<Profile> profiles,
                         AccountAwareStorageBackend<Crl> crls,
                         AccountAwareStorageBackend<Subject> subjects,
                         RegionResolver regionResolver, Supplier<String> callerArn) {
        this.trustAnchors = trustAnchors;
        this.profiles = profiles;
        this.crls = crls;
        this.subjects = subjects;
        this.regionResolver = regionResolver;
        this.callerArn = callerArn;
    }

    /** A page of results, with the token a caller passes back to continue. */
    public record Page<T>(List<T> items, String nextToken) {
    }

    // ------------------------------------------------------------ trust anchors

    public synchronized TrustAnchor createTrustAnchor(JsonNode request, String region) {
        List<String> violations = new ArrayList<>();
        String name = resourceName(request, "name", true, violations);
        JsonNode source = request.path("source");
        if (absent(source)) {
            violations.add(SmithyValidationErrors.notNull("source"));
        }
        String aliasName = aliasName(request, violations);
        Boolean enabled = optionalBoolean(request, "enabled", violations);
        Boolean crlDpRevocationEnabled = optionalBoolean(request, "crlDpRevocationEnabled", violations);
        List<NotificationSetting> requestedSettings = notificationSettings(request, false, violations);
        Map<String, String> tags = tagList(request, "tags", false, violations);
        throwIfAny(violations);
        String[] parsedSource = source(source);

        String accountId = trustAnchors.accountId();
        requireAliasFree(trustAnchors, TRUST_ANCHOR, aliasName, null, region, TrustAnchor::getAliasName,
                TrustAnchor::getTrustAnchorId);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        TrustAnchor anchor = new TrustAnchor();
        anchor.setTrustAnchorId(id);
        anchor.setTrustAnchorArn(regionResolver.buildArn(SERVICE, region, accountId, TRUST_ANCHOR + "/" + id));
        anchor.setName(name);
        anchor.setSourceType(parsedSource[0]);
        anchor.setX509CertificateData(parsedSource[1]);
        anchor.setEnabled(enabled == null || enabled);
        anchor.setCrlDpRevocationEnabled(crlDpRevocationEnabled == null ? Boolean.FALSE : crlDpRevocationEnabled);
        anchor.setCreatedAt(now);
        anchor.setUpdatedAt(now);
        anchor.setNotificationSettings(defaultNotificationSettings());
        String configuredBy = accountId;
        for (NotificationSetting setting : requestedSettings) {
            putSetting(anchor.getNotificationSettings(), setting, configuredBy);
        }
        setAlias(anchor.getAliasName(), aliasName, region, accountId, TRUST_ANCHOR, anchor::setAliasName,
                anchor::setAliasArn, anchor::setPreviousAliasName, false);
        anchor.setTags(tags);
        trustAnchors.put(storageKey(region, id), anchor);
        LOG.debugv("Created trust anchor {0} ({1}) of type {2}", id, name, parsedSource[0]);
        return anchor;
    }

    public TrustAnchor getTrustAnchor(String identifier, String region) {
        return requireTrustAnchor(identifier, "trustAnchorId", region);
    }

    public Page<TrustAnchor> listTrustAnchors(String pageSize, String nextToken, String region) {
        return page(inRegion(trustAnchors, region), anchor -> cursor(anchor.getCreatedAt(),
                anchor.getTrustAnchorId()), pageSize, nextToken, "trustanchors");
    }

    public synchronized TrustAnchor updateTrustAnchor(String identifier, JsonNode request, String region) {
        TrustAnchor anchor = requireTrustAnchor(identifier, "trustAnchorId", region);
        List<String> violations = new ArrayList<>();
        String name = resourceName(request, "name", false, violations);
        JsonNode source = request.path("source");
        String aliasName = aliasName(request, violations);
        Boolean crlDpRevocationEnabled = optionalBoolean(request, "crlDpRevocationEnabled", violations);
        throwIfAny(violations);
        String[] parsedSource = absent(source) ? null : source(source);
        requireAliasFree(trustAnchors, TRUST_ANCHOR, aliasName, anchor.getTrustAnchorId(), region,
                TrustAnchor::getAliasName, TrustAnchor::getTrustAnchorId);
        if (name != null) {
            anchor.setName(name);
        }
        if (parsedSource != null) {
            anchor.setSourceType(parsedSource[0]);
            anchor.setX509CertificateData(parsedSource[1]);
        }
        if (crlDpRevocationEnabled != null) {
            anchor.setCrlDpRevocationEnabled(crlDpRevocationEnabled);
        }
        setAlias(anchor.getAliasName(), aliasName, region, trustAnchors.accountId(), TRUST_ANCHOR,
                anchor::setAliasName, anchor::setAliasArn, anchor::setPreviousAliasName, true);
        anchor.setUpdatedAt(Instant.now());
        trustAnchors.put(storageKey(region, anchor.getTrustAnchorId()), anchor);
        return anchor;
    }

    /** Deletes the trust anchor and the CRLs imported against it, which cannot outlive the anchor they revoke for. */
    public synchronized TrustAnchor deleteTrustAnchor(String identifier, String region) {
        TrustAnchor anchor = requireTrustAnchor(identifier, "trustAnchorId", region);
        for (Crl crl : inRegion(crls, region)) {
            if (anchor.getTrustAnchorArn().equals(crl.getTrustAnchorArn())) {
                crls.delete(storageKey(region, crl.getCrlId()));
            }
        }
        trustAnchors.delete(storageKey(region, anchor.getTrustAnchorId()));
        LOG.debugv("Deleted trust anchor {0}", anchor.getTrustAnchorId());
        return anchor;
    }

    public synchronized TrustAnchor setTrustAnchorEnabled(String identifier, boolean enabled, String region) {
        TrustAnchor anchor = requireTrustAnchor(identifier, "trustAnchorId", region);
        anchor.setEnabled(enabled);
        anchor.setUpdatedAt(Instant.now());
        trustAnchors.put(storageKey(region, anchor.getTrustAnchorId()), anchor);
        return anchor;
    }

    /** Replaces each setting of the same event and channel, recording the caller's account as who configured it. */
    public synchronized TrustAnchor putNotificationSettings(JsonNode request, String region) {
        List<String> violations = new ArrayList<>();
        String trustAnchorId = trustAnchorUuid(request, violations);
        List<NotificationSetting> settings = notificationSettings(request, true, violations);
        throwIfAny(violations);
        TrustAnchor anchor = requireTrustAnchor(trustAnchorId, "trustAnchorId", region);
        for (NotificationSetting setting : settings) {
            putSetting(anchor.getNotificationSettings(), setting, trustAnchors.accountId());
        }
        anchor.setUpdatedAt(Instant.now());
        trustAnchors.put(storageKey(region, anchor.getTrustAnchorId()), anchor);
        return anchor;
    }

    /** Restores the default setting of each key named, a key naming no channel restoring the event's on every channel. */
    public synchronized TrustAnchor resetNotificationSettings(JsonNode request, String region) {
        List<String> violations = new ArrayList<>();
        String trustAnchorId = trustAnchorUuid(request, violations);
        JsonNode keys = request.path("notificationSettingKeys");
        List<String[]> parsedKeys = new ArrayList<>();
        if (absent(keys)) {
            violations.add(SmithyValidationErrors.notNull("notificationSettingKeys"));
        } else if (!keys.isArray()) {
            violations.add(notAList("notificationSettingKeys"));
        } else {
            addIfPresent(violations, SmithyValidationErrors.sizeOutOfRange("notificationSettingKeys", keys.size(), 0, 50));
            for (int i = 0; i < keys.size(); i++) {
                String path = "notificationSettingKeys." + (i + 1) + ".member";
                JsonNode key = keys.get(i);
                String event = enumText(key, "event", path + ".event", NOTIFICATION_EVENTS, true, violations);
                String channel = enumText(key, "channel", path + ".channel", NOTIFICATION_CHANNELS, false, violations);
                parsedKeys.add(new String[] {event, channel});
            }
        }
        throwIfAny(violations);
        TrustAnchor anchor = requireTrustAnchor(trustAnchorId, "trustAnchorId", region);
        String principal = ServicePrincipals.of(SERVICE);
        for (String[] key : parsedKeys) {
            for (NotificationSetting setting : anchor.getNotificationSettings()) {
                if (setting.getEvent().equals(key[0]) && (key[1] == null || key[1].equals(setting.getChannel()))) {
                    setting.setEnabled(true);
                    setting.setThreshold(DEFAULT_NOTIFICATION_THRESHOLD);
                    setting.setConfiguredBy(principal);
                }
            }
        }
        anchor.setUpdatedAt(Instant.now());
        trustAnchors.put(storageKey(region, anchor.getTrustAnchorId()), anchor);
        return anchor;
    }

    // ----------------------------------------------------------------- profiles

    public synchronized Profile createProfile(JsonNode request, String region) {
        List<String> violations = new ArrayList<>();
        String name = resourceName(request, "name", true, violations);
        List<String> roleArns = roleArns(request, true, violations);
        List<String> managedPolicyArns = managedPolicyArns(request, violations);
        Integer durationSeconds = durationSeconds(request, violations);
        String sessionPolicy = sessionPolicy(request, false, violations);
        Boolean requireInstanceProperties = optionalBoolean(request, "requireInstanceProperties", violations);
        Boolean enabled = optionalBoolean(request, "enabled", violations);
        Boolean acceptRoleSessionName = optionalBoolean(request, "acceptRoleSessionName", violations);
        String aliasName = aliasName(request, violations);
        JsonNode tipConfiguration = tipConfiguration(request, violations);
        Map<String, String> tags = tagList(request, "tags", false, violations);
        throwIfAny(violations);
        requireSessionPolicyDocument(sessionPolicy);

        String accountId = profiles.accountId();
        requireAliasFree(profiles, PROFILE, aliasName, null, region, Profile::getAliasName, Profile::getProfileId);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Profile profile = new Profile();
        profile.setProfileId(id);
        profile.setProfileArn(regionResolver.buildArn(SERVICE, region, accountId, PROFILE + "/" + id));
        profile.setName(name);
        profile.setRoleArns(roleArns);
        profile.setManagedPolicyArns(managedPolicyArns == null ? List.of() : managedPolicyArns);
        profile.setDurationSeconds(durationSeconds == null ? DEFAULT_DURATION_SECONDS : durationSeconds);
        profile.setSessionPolicy(sessionPolicy);
        profile.setRequireInstanceProperties(requireInstanceProperties == null ? Boolean.FALSE
                : requireInstanceProperties);
        profile.setEnabled(enabled == null || enabled);
        profile.setAcceptRoleSessionName(acceptRoleSessionName == null ? Boolean.FALSE : acceptRoleSessionName);
        profile.setTipConfiguration(tipConfiguration);
        profile.setCreatedBy(callerArn.get());
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        List<AttributeMapping> mappings = new ArrayList<>();
        DEFAULT_ATTRIBUTE_MAPPINGS.forEach((field, specifiers) -> mappings.add(new AttributeMapping(field, specifiers)));
        profile.setAttributeMappings(mappings);
        setAlias(null, aliasName, region, accountId, PROFILE, profile::setAliasName, profile::setAliasArn,
                profile::setPreviousAliasName, false);
        profile.setTags(tags);
        profiles.put(storageKey(region, id), profile);
        LOG.debugv("Created profile {0} ({1})", id, name);
        return profile;
    }

    public Profile getProfile(String identifier, String region) {
        return requireProfile(identifier, region);
    }

    public Page<Profile> listProfiles(String pageSize, String nextToken, String region) {
        return page(inRegion(profiles, region), profile -> cursor(profile.getCreatedAt(), profile.getProfileId()),
                pageSize, nextToken, "profiles");
    }

    public synchronized Profile updateProfile(String identifier, JsonNode request, String region) {
        Profile profile = requireProfile(identifier, region);
        List<String> violations = new ArrayList<>();
        String name = resourceName(request, "name", false, violations);
        List<String> roleArns = roleArns(request, false, violations);
        List<String> managedPolicyArns = managedPolicyArns(request, violations);
        Integer durationSeconds = durationSeconds(request, violations);
        String sessionPolicy = sessionPolicy(request, true, violations);
        Boolean acceptRoleSessionName = optionalBoolean(request, "acceptRoleSessionName", violations);
        String aliasName = aliasName(request, violations);
        JsonNode tipConfiguration = tipConfiguration(request, violations);
        throwIfAny(violations);
        requireSessionPolicyDocument(sessionPolicy);
        requireAliasFree(profiles, PROFILE, aliasName, profile.getProfileId(), region, Profile::getAliasName,
                Profile::getProfileId);
        if (name != null) {
            profile.setName(name);
        }
        if (roleArns != null) {
            profile.setRoleArns(roleArns);
        }
        if (managedPolicyArns != null) {
            profile.setManagedPolicyArns(managedPolicyArns);
        }
        if (durationSeconds != null) {
            profile.setDurationSeconds(durationSeconds);
        }
        if (sessionPolicy != null) {
            profile.setSessionPolicy(sessionPolicy);
        }
        if (acceptRoleSessionName != null) {
            profile.setAcceptRoleSessionName(acceptRoleSessionName);
        }
        if (tipConfiguration != null) {
            profile.setTipConfiguration(tipConfiguration);
        }
        setAlias(profile.getAliasName(), aliasName, region, profiles.accountId(), PROFILE, profile::setAliasName,
                profile::setAliasArn, profile::setPreviousAliasName, true);
        profile.setUpdatedAt(Instant.now());
        profiles.put(storageKey(region, profile.getProfileId()), profile);
        return profile;
    }

    public synchronized Profile deleteProfile(String identifier, String region) {
        Profile profile = requireProfile(identifier, region);
        profiles.delete(storageKey(region, profile.getProfileId()));
        LOG.debugv("Deleted profile {0}", profile.getProfileId());
        return profile;
    }

    public synchronized Profile setProfileEnabled(String identifier, boolean enabled, String region) {
        Profile profile = requireProfile(identifier, region);
        profile.setEnabled(enabled);
        profile.setUpdatedAt(Instant.now());
        profiles.put(storageKey(region, profile.getProfileId()), profile);
        return profile;
    }

    /** Replaces the rules of one certificate field, adding the field when the profile maps none of it yet. */
    public synchronized Profile putAttributeMapping(String identifier, JsonNode request, String region) {
        Profile profile = requireProfile(identifier, region);
        List<String> violations = new ArrayList<>();
        String field = enumText(request, "certificateField", "certificateField", CERTIFICATE_FIELDS, true,
                violations);
        JsonNode rules = request.path("mappingRules");
        List<String> specifiers = new ArrayList<>();
        if (absent(rules)) {
            violations.add(SmithyValidationErrors.notNull("mappingRules"));
        } else if (!rules.isArray()) {
            violations.add(notAList("mappingRules"));
        } else {
            for (int i = 0; i < rules.size(); i++) {
                String path = "mappingRules." + (i + 1) + ".member.specifier";
                String specifier = text(rules.get(i), "specifier", path, violations);
                if (specifier == null) {
                    violations.add(SmithyValidationErrors.notNull(path));
                    continue;
                }
                addIfPresent(violations, SmithyValidationErrors.lengthOutOfRange(path, specifier, 0, 60));
                specifiers.add(specifier);
            }
        }
        throwIfAny(violations);
        AttributeMapping mapping = mappingOf(profile, field);
        if (mapping == null) {
            profile.getAttributeMappings().add(new AttributeMapping(field, new ArrayList<>(new LinkedHashSet<>(specifiers))));
        } else {
            mapping.setSpecifiers(new ArrayList<>(new LinkedHashSet<>(specifiers)));
        }
        profile.setUpdatedAt(Instant.now());
        profiles.put(storageKey(region, profile.getProfileId()), profile);
        return profile;
    }

    /** Removes the named specifiers of a field, or the whole field's mapping when the request names none. */
    public synchronized Profile deleteAttributeMapping(String identifier, String certificateField,
                                                       List<String> specifiers, String region) {
        Profile profile = requireProfile(identifier, region);
        if (certificateField == null) {
            throw SmithyValidationErrors.validationException(SmithyValidationErrors.notNull("certificateField"));
        }
        if (!CERTIFICATE_FIELDS.contains(certificateField)) {
            throw SmithyValidationErrors.validationException(SmithyValidationErrors.notInEnum(certificateField,
                    "certificateField", CERTIFICATE_FIELDS));
        }
        AttributeMapping mapping = mappingOf(profile, certificateField);
        if (mapping != null) {
            if (specifiers == null || specifiers.isEmpty()) {
                profile.getAttributeMappings().remove(mapping);
            } else {
                List<String> remaining = new ArrayList<>(mapping.getSpecifiers());
                remaining.removeAll(specifiers);
                mapping.setSpecifiers(remaining);
            }
        }
        profile.setUpdatedAt(Instant.now());
        profiles.put(storageKey(region, profile.getProfileId()), profile);
        return profile;
    }

    // --------------------------------------------------------------------- CRLs

    public synchronized Crl importCrl(JsonNode request, String region) {
        List<String> violations = new ArrayList<>();
        String name = resourceName(request, "name", true, violations);
        byte[] crlData = crlData(request, true, violations);
        String trustAnchorArn = text(request, "trustAnchorArn", "trustAnchorArn", violations);
        if (trustAnchorArn == null) {
            violations.add(SmithyValidationErrors.notNull("trustAnchorArn"));
        } else {
            addIfPresent(violations, lengthAndPattern("trustAnchorArn", trustAnchorArn, 1, 1011,
                    TRUST_ANCHOR_IDENTIFIER_ARN));
        }
        Boolean enabled = optionalBoolean(request, "enabled", violations);
        String aliasName = aliasName(request, violations);
        Map<String, String> tags = tagList(request, "tags", false, violations);
        throwIfAny(violations);
        requireCrl(crlData);
        TrustAnchor anchor = requireTrustAnchor(trustAnchorArn, "trustAnchorArn", region);

        String accountId = crls.accountId();
        requireAliasFree(crls, CRL, aliasName, null, region, Crl::getAliasName, Crl::getCrlId);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Crl crl = new Crl();
        crl.setCrlId(id);
        crl.setCrlArn(regionResolver.buildArn(SERVICE, region, accountId, CRL + "/" + id));
        crl.setName(name);
        crl.setCrlData(Base64.getEncoder().encodeToString(crlData));
        crl.setTrustAnchorArn(anchor.getTrustAnchorArn());
        crl.setEnabled(enabled == null || enabled);
        crl.setCreatedAt(now);
        crl.setUpdatedAt(now);
        setAlias(null, aliasName, region, accountId, CRL, crl::setAliasName, crl::setAliasArn,
                crl::setPreviousAliasName, false);
        crl.setTags(tags);
        crls.put(storageKey(region, id), crl);
        LOG.debugv("Imported CRL {0} ({1}) for trust anchor {2}", id, name, anchor.getTrustAnchorId());
        return crl;
    }

    public Crl getCrl(String identifier, String region) {
        return requireCrl(identifier, region);
    }

    public Page<Crl> listCrls(String pageSize, String nextToken, String region) {
        return page(inRegion(crls, region), crl -> cursor(crl.getCreatedAt(), crl.getCrlId()), pageSize, nextToken,
                "crls");
    }

    public synchronized Crl updateCrl(String identifier, JsonNode request, String region) {
        Crl crl = requireCrl(identifier, region);
        List<String> violations = new ArrayList<>();
        String name = resourceName(request, "name", false, violations);
        byte[] crlData = crlData(request, false, violations);
        String aliasName = aliasName(request, violations);
        throwIfAny(violations);
        if (crlData != null) {
            requireCrl(crlData);
        }
        requireAliasFree(crls, CRL, aliasName, crl.getCrlId(), region, Crl::getAliasName, Crl::getCrlId);
        if (name != null) {
            crl.setName(name);
        }
        if (crlData != null) {
            crl.setCrlData(Base64.getEncoder().encodeToString(crlData));
        }
        setAlias(crl.getAliasName(), aliasName, region, crls.accountId(), CRL, crl::setAliasName, crl::setAliasArn,
                crl::setPreviousAliasName, true);
        crl.setUpdatedAt(Instant.now());
        crls.put(storageKey(region, crl.getCrlId()), crl);
        return crl;
    }

    public synchronized Crl deleteCrl(String identifier, String region) {
        Crl crl = requireCrl(identifier, region);
        crls.delete(storageKey(region, crl.getCrlId()));
        return crl;
    }

    public synchronized Crl setCrlEnabled(String identifier, boolean enabled, String region) {
        Crl crl = requireCrl(identifier, region);
        crl.setEnabled(enabled);
        crl.setUpdatedAt(Instant.now());
        crls.put(storageKey(region, crl.getCrlId()), crl);
        return crl;
    }

    // ----------------------------------------------------------------- subjects

    public Subject getSubject(String subjectId, String region) {
        String violation = lengthAndPattern("subjectId", subjectId, 36, 36, UUID_SHAPE);
        if (violation != null) {
            throw SmithyValidationErrors.validationException(violation);
        }
        return subjects.get(storageKey(region, subjectId))
                .orElseThrow(() -> notFound("Subject " + subjectId + " not found."));
    }

    public Page<Subject> listSubjects(String pageSize, String nextToken, String region) {
        return page(inRegion(subjects, region), subject -> cursor(subject.getCreatedAt(), subject.getSubjectId()),
                pageSize, nextToken, "subjects");
    }

    // --------------------------------------------------------------------- tags

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        List<String> violations = new ArrayList<>();
        resourceArn(resourceArn, violations);
        throwIfAny(violations);
        return new LinkedHashMap<>(tagsOf(resourceArn, region));
    }

    /** Gives the resource a new tag map, so a reader of {@link #storedTags} holds one whole set. */
    public synchronized void tagResource(JsonNode request, String region) {
        List<String> violations = new ArrayList<>();
        String resourceArn = text(request, "resourceArn", "resourceArn", violations);
        resourceArn(resourceArn, violations);
        Map<String, String> tags = tagList(request, "tags", true, violations);
        throwIfAny(violations);
        Map<String, String> merged = new LinkedHashMap<>(tagsOf(resourceArn, region));
        merged.putAll(tags);
        if (merged.size() > MAX_TAGS) {
            throw new AwsException("TooManyTagsException",
                    "The resource " + resourceArn + " would have more than " + MAX_TAGS + " tags.", 400);
        }
        writeTags(resourceArn, region, merged);
    }

    public synchronized void untagResource(JsonNode request, String region) {
        List<String> violations = new ArrayList<>();
        String resourceArn = text(request, "resourceArn", "resourceArn", violations);
        resourceArn(resourceArn, violations);
        JsonNode keys = request.path("tagKeys");
        List<String> tagKeys = new ArrayList<>();
        if (absent(keys)) {
            violations.add(SmithyValidationErrors.notNull("tagKeys"));
        } else if (!keys.isArray()) {
            violations.add(notAList("tagKeys"));
        } else {
            addIfPresent(violations, SmithyValidationErrors.sizeOutOfRange("tagKeys", keys.size(), 0, MAX_TAGS));
            for (int i = 0; i < keys.size(); i++) {
                String path = "tagKeys." + (i + 1) + ".member";
                JsonNode key = keys.get(i);
                if (!key.isTextual()) {
                    violations.add(notAString(path));
                    continue;
                }
                addIfPresent(violations, lengthAndPattern(path, key.asText(), 1, 128, TAG_PATTERN));
                tagKeys.add(key.asText());
            }
        }
        throwIfAny(violations);
        Map<String, String> remaining = new LinkedHashMap<>(tagsOf(resourceArn, region));
        tagKeys.forEach(remaining::remove);
        writeTags(resourceArn, region, remaining);
    }

    // ------------------------------------------------------- StoredResourceArns

    @Override
    public String namespace() {
        return SERVICE;
    }

    /** A request names each resource by its id or its ARN, which the request-resource table fills directly. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The stored ARN of the trust anchor, profile or CRL a request names by its ARN or its alias ARN rather than its
     * UUID. The request-resource table fills the type's ARN format with the path label as spelled, so such a request
     * renders {@code ...:trust-anchor/arn:...:trust-anchor%2F<uuid>} or {@code ...:trust-anchor/arn:...:alias%2F
     * trust-anchor%2F<alias>}; the label is read back as the handler reads it, in the outer ARN's account, Region and
     * partition. Empty for a UUID label, whose rendered ARN is already the stored one, and for anything that names
     * no resource held there.
     */
    @Override
    public Optional<String> resolveStoredArn(String arn) {
        try {
            if (!AwsArnUtils.isArnFor(arn, SERVICE)) {
                return Optional.empty();
            }
            AwsArnUtils.Arn outer = AwsArnUtils.parse(arn);
            String[] typeAndRest = outer.resource().split("/", 2);
            if (typeAndRest.length != 2 || !Set.of(TRUST_ANCHOR, PROFILE, CRL).contains(typeAndRest[0])
                    || UUID_PATTERN.matcher(typeAndRest[1]).matches()) {
                return Optional.empty();
            }
            String type = typeAndRest[0];
            String label = typeAndRest[1].replace("%2F", "/").replace("%2f", "/").replace("%25", "%");
            if (!AwsArnUtils.isArnFor(label, SERVICE)) {
                return Optional.empty();
            }
            AwsArnUtils.Arn inner = AwsArnUtils.parse(label);
            if (!inner.partition().equals(outer.partition()) || !inner.region().equals(outer.region())
                    || !inner.accountId().equals(outer.accountId()) || inner.accountId().isEmpty()
                    || inner.region().isEmpty()) {
                return Optional.empty();
            }
            String resource = inner.resource();
            String aliasPrefix = ALIAS + type + "/";
            return switch (type) {
                case TRUST_ANCHOR -> storedArnOf(trustAnchors, inner, resource, aliasPrefix,
                        TrustAnchor::getTrustAnchorArn, TrustAnchor::getAliasName);
                case PROFILE -> storedArnOf(profiles, inner, resource, aliasPrefix, Profile::getProfileArn,
                        Profile::getAliasName);
                default -> storedArnOf(crls, inner, resource, aliasPrefix, Crl::getCrlArn, Crl::getAliasName);
            };
        } catch (RuntimeException e) {
            LOG.debugv("Could not resolve {0} to a stored Roles Anywhere resource: {1}", arn, e.getMessage());
            return Optional.empty();
        }
    }

    private static <T> Optional<String> storedArnOf(AccountAwareStorageBackend<T> store, AwsArnUtils.Arn inner,
                                                    String resource, String aliasPrefix, Function<T, String> arnOf,
                                                    Function<T, String> aliasOf) {
        String typePrefix = aliasPrefix.substring(ALIAS.length());
        if (resource.startsWith(typePrefix)) {
            String id = resource.substring(typePrefix.length());
            return AccountAwareStorageBackend.readInAccount(store, inner.accountId(), storageKey(inner.region(), id))
                    .map(arnOf);
        }
        if (resource.startsWith(aliasPrefix)) {
            String alias = resource.substring(aliasPrefix.length());
            String regionPrefix = inner.region() + "::";
            for (T candidate : store.scanForAccount(inner.accountId(), key -> key.startsWith(regionPrefix))) {
                if (alias.equals(aliasOf.apply(candidate))) {
                    return Optional.ofNullable(arnOf.apply(candidate));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The tags of the trust anchor, profile or CRL whose ARN is {@code arn}, read in the ARN's account and Region and
     * matched on the whole ARN; empty for a subject, an alias ARN, another service's ARN or a resource not held.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, SERVICE)) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (parsed.accountId().isEmpty() || parsed.region().isEmpty()) {
            return Map.of();
        }
        String[] typeAndId = parsed.resource().split("/", 2);
        if (typeAndId.length != 2) {
            return Map.of();
        }
        String key = storageKey(parsed.region(), typeAndId[1]);
        Optional<Map<String, String>> tags = switch (typeAndId[0]) {
            case TRUST_ANCHOR -> AccountAwareStorageBackend.readInAccount(trustAnchors, parsed.accountId(), key)
                    .filter(anchor -> arn.equals(anchor.getTrustAnchorArn())).map(TrustAnchor::getTags);
            case PROFILE -> AccountAwareStorageBackend.readInAccount(profiles, parsed.accountId(), key)
                    .filter(profile -> arn.equals(profile.getProfileArn())).map(Profile::getTags);
            case CRL -> AccountAwareStorageBackend.readInAccount(crls, parsed.accountId(), key)
                    .filter(crl -> arn.equals(crl.getCrlArn())).map(Crl::getTags);
            default -> Optional.empty();
        };
        return tags.<Map<String, String>>map(LinkedHashMap::new).orElse(Map.of());
    }

    @Override
    public void clear() {
        trustAnchors.clear();
        profiles.clear();
        crls.clear();
        subjects.clear();
    }

    // --------------------------------------------------------------- resolution

    /**
     * The trust anchor an identifier names in the caller's account and the Region: by its UUID, its ARN or the ARN
     * of its alias. An ARN of another account, Region or partition names none here.
     */
    private TrustAnchor requireTrustAnchor(String identifier, String member, String region) {
        String pattern = "trustAnchorArn".equals(member) ? TRUST_ANCHOR_IDENTIFIER_ARN : TRUST_ANCHOR_IDENTIFIER;
        return resolve(trustAnchors, TRUST_ANCHOR, identifier, member, pattern, region, TrustAnchor::getAliasName,
                "Trust anchor");
    }

    private Profile requireProfile(String identifier, String region) {
        return resolve(profiles, PROFILE, identifier, "profileId", PROFILE_IDENTIFIER, region, Profile::getAliasName,
                "Profile");
    }

    private Crl requireCrl(String identifier, String region) {
        return resolve(crls, CRL, identifier, "crlId", CRL_IDENTIFIER, region, Crl::getAliasName, "CRL");
    }

    private <T> T resolve(AccountAwareStorageBackend<T> store, String type, String identifier, String member,
                          String pattern, String region, Function<T, String> aliasOf, String label) {
        String violation = identifier == null ? SmithyValidationErrors.notNull(member)
                : lengthAndPattern(member, identifier, 1, 1011, pattern);
        if (violation != null) {
            throw SmithyValidationErrors.validationException(violation);
        }
        AwsException notFound = notFound(label + " " + identifier + " not found.");
        if (UUID_PATTERN.matcher(identifier).matches()) {
            return store.get(storageKey(region, identifier)).orElseThrow(() -> notFound);
        }
        AwsArnUtils.Arn arn = localArn(identifier, region).orElseThrow(() -> notFound);
        String resource = arn.resource();
        if (resource.startsWith(type + "/")) {
            return store.get(storageKey(region, resource.substring(type.length() + 1))).orElseThrow(() -> notFound);
        }
        String aliasPrefix = ALIAS + type + "/";
        if (resource.startsWith(aliasPrefix)) {
            String alias = resource.substring(aliasPrefix.length());
            for (T candidate : inRegion(store, region)) {
                if (alias.equals(aliasOf.apply(candidate))) {
                    return candidate;
                }
            }
        }
        throw notFound;
    }

    /** The ARN when it is a Roles Anywhere ARN of the caller's account, the Region and its partition. */
    private Optional<AwsArnUtils.Arn> localArn(String value, String region) {
        if (!AwsArnUtils.isArnFor(value, SERVICE)) {
            return Optional.empty();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
        if (!arn.region().equals(region) || !arn.accountId().equals(trustAnchors.accountId())
                || !arn.partition().equals(regionResolver.partitionForRegion(region))) {
            return Optional.empty();
        }
        return Optional.of(arn);
    }

    private Map<String, String> tagsOf(String resourceArn, String region) {
        String[] target = tagTarget(resourceArn, region);
        String key = storageKey(region, target[1]);
        return switch (target[0]) {
            case TRUST_ANCHOR -> trustAnchors.get(key).map(TrustAnchor::getTags)
                    .orElseThrow(() -> taggedNotFound(resourceArn));
            case PROFILE -> profiles.get(key).map(Profile::getTags).orElseThrow(() -> taggedNotFound(resourceArn));
            default -> crls.get(key).map(Crl::getTags).orElseThrow(() -> taggedNotFound(resourceArn));
        };
    }

    private void writeTags(String resourceArn, String region, Map<String, String> tags) {
        String[] target = tagTarget(resourceArn, region);
        String key = storageKey(region, target[1]);
        switch (target[0]) {
            case TRUST_ANCHOR -> {
                TrustAnchor anchor = trustAnchors.get(key).orElseThrow(() -> taggedNotFound(resourceArn));
                anchor.setTags(tags);
                trustAnchors.put(key, anchor);
            }
            case PROFILE -> {
                Profile profile = profiles.get(key).orElseThrow(() -> taggedNotFound(resourceArn));
                profile.setTags(tags);
                profiles.put(key, profile);
            }
            default -> {
                Crl crl = crls.get(key).orElseThrow(() -> taggedNotFound(resourceArn));
                crl.setTags(tags);
                crls.put(key, crl);
            }
        }
    }

    /** The type and id of the taggable resource an ARN names: a trust anchor, a profile or a CRL, never a subject. */
    private String[] tagTarget(String resourceArn, String region) {
        if (!AwsArnUtils.isArn(resourceArn)) {
            throw validation("The resourceArn " + resourceArn + " is not an ARN.");
        }
        AwsArnUtils.Arn arn = localArn(resourceArn, region).orElseThrow(() -> taggedNotFound(resourceArn));
        String[] typeAndId = arn.resource().split("/", 2);
        if (typeAndId.length != 2 || typeAndId[1].isEmpty()) {
            throw taggedNotFound(resourceArn);
        }
        if (SUBJECT.equals(typeAndId[0])) {
            throw validation("Subjects cannot be tagged: " + resourceArn + ".");
        }
        if (!Set.of(TRUST_ANCHOR, PROFILE, CRL).contains(typeAndId[0])) {
            throw taggedNotFound(resourceArn);
        }
        return typeAndId;
    }

    private static AwsException taggedNotFound(String resourceArn) {
        return notFound("Resource " + resourceArn + " not found.");
    }

    // ------------------------------------------------------------------ aliases

    private <T> void requireAliasFree(AccountAwareStorageBackend<T> store, String type, String aliasName,
                                      String ownId, String region, Function<T, String> aliasOf,
                                      Function<T, String> idOf) {
        if (aliasName == null || aliasName.isEmpty()) {
            return;
        }
        for (T candidate : inRegion(store, region)) {
            if (aliasName.equals(aliasOf.apply(candidate)) && !idOf.apply(candidate).equals(ownId)) {
                throw validation("The alias " + aliasName + " is already used by another " + type
                        + " in this account and Region.");
            }
        }
    }

    /**
     * Applies an alias a create or update names: an empty name clears it, and on an update a change records the
     * alias it replaced as {@code previousAliasName}.
     */
    private void setAlias(String current, String requested, String region, String accountId, String type,
                          Consumer<String> name, Consumer<String> arn,
                          Consumer<String> previous, boolean update) {
        if (requested == null) {
            return;
        }
        String next = requested.isEmpty() ? null : requested;
        if (update && current != null && !current.equals(next)) {
            previous.accept(current);
        }
        name.accept(next);
        arn.accept(next == null ? null : regionResolver.buildArn(SERVICE, region, accountId,
                ALIAS + type + "/" + next));
    }

    // --------------------------------------------------------- request parsing

    /** The source a request gives: its type and its certificate data, which must be one or more PEM certificates. */
    private static String[] source(JsonNode source) {
        List<String> violations = new ArrayList<>();
        if (!source.isObject()) {
            throw validation("source must be a JSON object.");
        }
        String sourceType = enumText(source, "sourceType", "source.sourceType", SOURCE_TYPES, true, violations);
        JsonNode data = source.path("sourceData");
        String certificateData = null;
        String acmPcaArn = null;
        if (absent(data)) {
            violations.add(SmithyValidationErrors.notNull("source.sourceData"));
        } else if (!data.isObject()) {
            violations.add("Value at 'source.sourceData' failed to satisfy constraint: Member must be a structure");
        } else {
            certificateData = text(data, "x509CertificateData", "source.sourceData.x509CertificateData", violations);
            if (certificateData != null) {
                addIfPresent(violations, SmithyValidationErrors.lengthOutOfRange(
                        "source.sourceData.x509CertificateData", certificateData, 1, MAX_CERTIFICATE_DATA));
            }
            acmPcaArn = text(data, "acmPcaArn", "source.sourceData.acmPcaArn", violations);
        }
        throwIfAny(violations);
        if (certificateData != null && acmPcaArn != null) {
            throw validation("sourceData must set exactly one of x509CertificateData and acmPcaArn.");
        }
        if ("AWS_ACM_PCA".equals(sourceType)) {
            throw validation("Floci does not emulate AWS Private Certificate Authority, so the acmPcaArn of an "
                    + "AWS_ACM_PCA trust anchor cannot be resolved. Use a CERTIFICATE_BUNDLE or "
                    + "SELF_SIGNED_REPOSITORY source with the CA certificate as x509CertificateData.");
        }
        if (certificateData == null) {
            throw validation("A " + sourceType + " trust anchor requires sourceData.x509CertificateData.");
        }
        requireCertificates(certificateData);
        return new String[] {sourceType, certificateData};
    }

    private static void requireCertificates(String pem) {
        if (!pem.contains("-----BEGIN CERTIFICATE-----")) {
            throw validation("x509CertificateData must be one or more PEM-encoded X.509 certificates.");
        }
        try {
            Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            if (certificates.isEmpty() || !(certificates.iterator().next() instanceof X509Certificate)) {
                throw validation("x509CertificateData must be one or more PEM-encoded X.509 certificates.");
            }
        } catch (CertificateException e) {
            throw validation("x509CertificateData is not a valid PEM-encoded X.509 certificate: " + e.getMessage());
        }
    }

    private static void requireCrl(byte[] data) {
        try {
            CertificateFactory.getInstance("X.509").generateCRL(new ByteArrayInputStream(data));
        } catch (CRLException | CertificateException | RuntimeException e) {
            throw validation("crlData is not a valid X.509 certificate revocation list.");
        }
    }

    private static byte[] crlData(JsonNode request, boolean required, List<String> violations) {
        String encoded = text(request, "crlData", "crlData", violations);
        if (encoded == null) {
            if (required && absent(request.path("crlData"))) {
                violations.add(SmithyValidationErrors.notNull("crlData"));
            }
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            violations.add("Value at 'crlData' failed to satisfy constraint: Member must be base64 encoded");
            return null;
        }
        if (decoded.length < 1 || decoded.length > MAX_CRL_BYTES) {
            violations.add(decoded.length < 1
                    ? "Value at 'crlData' failed to satisfy constraint: Member must have length greater than or equal to 1"
                    : "Value at 'crlData' failed to satisfy constraint: Member must have length less than or equal to "
                            + MAX_CRL_BYTES);
            return null;
        }
        return decoded;
    }

    private static String resourceName(JsonNode request, String member, boolean required, List<String> violations) {
        String name = text(request, member, member, violations);
        if (name == null) {
            if (required && absent(request.path(member))) {
                violations.add(SmithyValidationErrors.notNull(member));
            }
            return null;
        }
        addIfPresent(violations, lengthAndPattern(member, name, 1, 255, RESOURCE_NAME));
        return name;
    }

    private static String aliasName(JsonNode request, List<String> violations) {
        String alias = text(request, "aliasName", "aliasName", violations);
        if (alias != null) {
            addIfPresent(violations, lengthAndPattern("aliasName", alias, 0, 256, ALIAS_NAME));
        }
        return alias;
    }

    private static String trustAnchorUuid(JsonNode request, List<String> violations) {
        String id = text(request, "trustAnchorId", "trustAnchorId", violations);
        if (id == null) {
            if (absent(request.path("trustAnchorId"))) {
                violations.add(SmithyValidationErrors.notNull("trustAnchorId"));
            }
            return null;
        }
        addIfPresent(violations, lengthAndPattern("trustAnchorId", id, 36, 36, UUID_SHAPE));
        return id;
    }

    private static List<NotificationSetting> notificationSettings(JsonNode request, boolean required,
                                                                  List<String> violations) {
        JsonNode settings = request.path("notificationSettings");
        List<NotificationSetting> parsed = new ArrayList<>();
        if (absent(settings)) {
            if (required) {
                violations.add(SmithyValidationErrors.notNull("notificationSettings"));
            }
            return parsed;
        }
        if (!settings.isArray()) {
            violations.add(notAList("notificationSettings"));
            return parsed;
        }
        addIfPresent(violations, SmithyValidationErrors.sizeOutOfRange("notificationSettings", settings.size(), 0, 50));
        for (int i = 0; i < settings.size(); i++) {
            String path = "notificationSettings." + (i + 1) + ".member";
            JsonNode setting = settings.get(i);
            Boolean enabled = optionalBoolean(setting, "enabled", path + ".enabled", violations);
            if (enabled == null && absent(setting.path("enabled"))) {
                violations.add(SmithyValidationErrors.notNull(path + ".enabled"));
            }
            String event = enumText(setting, "event", path + ".event", NOTIFICATION_EVENTS, true, violations);
            String channel = enumText(setting, "channel", path + ".channel", NOTIFICATION_CHANNELS, false, violations);
            Integer threshold = optionalInteger(setting, "threshold", path + ".threshold", violations);
            if (threshold != null) {
                addIfPresent(violations, SmithyValidationErrors.outOfRange(path + ".threshold", threshold, 1, 360));
            }
            parsed.add(new NotificationSetting(event, channel, enabled != null && enabled, threshold, null));
        }
        return parsed;
    }

    private static List<String> roleArns(JsonNode request, boolean required, List<String> violations) {
        JsonNode roles = request.path("roleArns");
        if (absent(roles)) {
            if (required) {
                violations.add(SmithyValidationErrors.notNull("roleArns"));
            }
            return null;
        }
        return stringList(roles, "roleArns", 250, 1, 1011, ROLE_ARN, violations);
    }

    private static List<String> managedPolicyArns(JsonNode request, List<String> violations) {
        JsonNode policies = request.path("managedPolicyArns");
        if (absent(policies)) {
            return null;
        }
        return stringList(policies, "managedPolicyArns", 50, 1, 200, null, violations);
    }

    private static List<String> stringList(JsonNode list, String member, int maxSize, int minLength, int maxLength,
                                           String pattern, List<String> violations) {
        List<String> values = new ArrayList<>();
        if (!list.isArray()) {
            violations.add(notAList(member));
            return values;
        }
        addIfPresent(violations, SmithyValidationErrors.sizeOutOfRange(member, list.size(), 0, maxSize));
        for (int i = 0; i < list.size(); i++) {
            String path = member + "." + (i + 1) + ".member";
            JsonNode value = list.get(i);
            if (!value.isTextual()) {
                violations.add(notAString(path));
                continue;
            }
            addIfPresent(violations, lengthAndPattern(path, value.asText(), minLength, maxLength, pattern));
            values.add(value.asText());
        }
        return values;
    }

    private static Integer durationSeconds(JsonNode request, List<String> violations) {
        Integer duration = optionalInteger(request, "durationSeconds", "durationSeconds", violations);
        if (duration != null) {
            addIfPresent(violations, SmithyValidationErrors.outOfRange("durationSeconds", duration, 900, 43200));
        }
        return duration;
    }

    private static String sessionPolicy(JsonNode request, boolean update, List<String> violations) {
        String policy = text(request, "sessionPolicy", "sessionPolicy", violations);
        if (policy != null && update) {
            addIfPresent(violations, SmithyValidationErrors.lengthOutOfRange("sessionPolicy", policy, 1, 100000));
        }
        return policy;
    }

    private void requireSessionPolicyDocument(String policy) {
        if (policy == null) {
            return;
        }
        try {
            JsonNode document = objectMapper.readTree(policy);
            if (document == null || !document.isObject()) {
                throw validation("sessionPolicy must be a JSON policy document.");
            }
        } catch (IOException e) {
            throw validation("sessionPolicy must be a JSON policy document.");
        }
    }

    private static JsonNode tipConfiguration(JsonNode request, List<String> violations) {
        JsonNode tip = request.path("tipConfiguration");
        if (absent(tip)) {
            return null;
        }
        if (!tip.isObject()) {
            violations.add("Value at 'tipConfiguration' failed to satisfy constraint: Member must be a structure");
            return null;
        }
        enumText(tip, "status", "tipConfiguration.status", TIP_STATUSES, true, violations);
        enumText(tip, "subjectType", "tipConfiguration.subjectType", TIP_SUBJECT_TYPES, false, violations);
        enumText(tip, "subjectSource", "tipConfiguration.subjectSource", TIP_SUBJECT_SOURCES, false, violations);
        return tip.deepCopy();
    }

    /** A list of {@code {key, value}} tags as a map in request order, checked against the model's tag shapes. */
    private static Map<String, String> tagList(JsonNode request, String member, boolean required,
                                               List<String> violations) {
        JsonNode tags = request.path(member);
        Map<String, String> parsed = new LinkedHashMap<>();
        if (absent(tags)) {
            if (required) {
                violations.add(SmithyValidationErrors.notNull(member));
            }
            return parsed;
        }
        if (!tags.isArray()) {
            violations.add(notAList(member));
            return parsed;
        }
        addIfPresent(violations, SmithyValidationErrors.sizeOutOfRange(member, tags.size(), 0, MAX_TAGS));
        for (int i = 0; i < tags.size(); i++) {
            String path = member + "." + (i + 1) + ".member";
            JsonNode tag = tags.get(i);
            String key = text(tag, "key", path + ".key", violations);
            String value = text(tag, "value", path + ".value", violations);
            if (key == null) {
                violations.add(SmithyValidationErrors.notNull(path + ".key"));
            } else {
                addIfPresent(violations, lengthAndPattern(path + ".key", key, 1, 128, TAG_PATTERN));
            }
            if (value == null) {
                violations.add(SmithyValidationErrors.notNull(path + ".value"));
            } else {
                addIfPresent(violations, lengthAndPattern(path + ".value", value, 0, 256, TAG_PATTERN));
            }
            if (key != null && value != null) {
                parsed.put(key, value);
            }
        }
        return parsed;
    }

    private static void resourceArn(String resourceArn, List<String> violations) {
        if (resourceArn == null) {
            violations.add(SmithyValidationErrors.notNull("resourceArn"));
            return;
        }
        addIfPresent(violations, SmithyValidationErrors.lengthOutOfRange("resourceArn", resourceArn, 1, 1011));
    }

    // ------------------------------------------------------------ JSON helpers

    private static boolean absent(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    /** A string member, or null when it is absent; a member that is not a string is a violation. */
    private static String text(JsonNode request, String member, String path, List<String> violations) {
        if (request == null || !request.isObject()) {
            return null;
        }
        JsonNode node = request.path(member);
        if (absent(node)) {
            return null;
        }
        if (!node.isTextual()) {
            violations.add(notAString(path));
            return null;
        }
        return node.asText();
    }

    private static String enumText(JsonNode request, String member, String path, List<String> allowed,
                                   boolean required, List<String> violations) {
        String value = text(request, member, path, violations);
        if (value == null) {
            if (required && (request == null || absent(request.path(member)))) {
                violations.add(SmithyValidationErrors.notNull(path));
            }
            return null;
        }
        if (!allowed.contains(value)) {
            violations.add(SmithyValidationErrors.notInEnum(value, path, allowed));
            return null;
        }
        return value;
    }

    private static Boolean optionalBoolean(JsonNode request, String member, List<String> violations) {
        return optionalBoolean(request, member, member, violations);
    }

    private static Boolean optionalBoolean(JsonNode request, String member, String path, List<String> violations) {
        if (request == null || !request.isObject()) {
            return null;
        }
        JsonNode node = request.path(member);
        if (absent(node)) {
            return null;
        }
        if (!node.isBoolean()) {
            violations.add("Value '" + node.asText() + "' at '" + path
                    + "' failed to satisfy constraint: Member must be a boolean");
            return null;
        }
        return node.booleanValue();
    }

    private static Integer optionalInteger(JsonNode request, String member, String path, List<String> violations) {
        if (request == null || !request.isObject()) {
            return null;
        }
        JsonNode node = request.path(member);
        if (absent(node)) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            violations.add("Value '" + node.asText() + "' at '" + path
                    + "' failed to satisfy constraint: Member must be an integer");
            return null;
        }
        return node.intValue();
    }

    private static String lengthAndPattern(String path, String value, int min, int max, String pattern) {
        String length = SmithyValidationErrors.lengthOutOfRange(path, value, min, max);
        if (length != null) {
            return length;
        }
        if (pattern != null && !Pattern.compile(pattern).matcher(value).matches()) {
            return SmithyValidationErrors.pattern(path, value, pattern);
        }
        return null;
    }

    private static String notAString(String path) {
        return "Value at '" + path + "' failed to satisfy constraint: Member must be a string";
    }

    private static String notAList(String path) {
        return "Value at '" + path + "' failed to satisfy constraint: Member must be a list";
    }

    private static void addIfPresent(List<String> violations, String violation) {
        if (violation != null) {
            violations.add(violation);
        }
    }

    private static void throwIfAny(List<String> violations) {
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<NotificationSetting> defaultNotificationSettings() {
        List<NotificationSetting> settings = new ArrayList<>();
        for (String event : NOTIFICATION_EVENTS) {
            settings.add(new NotificationSetting(event, "ALL", true, DEFAULT_NOTIFICATION_THRESHOLD,
                    ServicePrincipals.of(SERVICE)));
        }
        return settings;
    }

    /** Replaces the setting of the same event and channel, or adds it; an omitted threshold keeps the one held. */
    private static void putSetting(List<NotificationSetting> settings, NotificationSetting requested,
                                   String configuredBy) {
        String channel = requested.getChannel() == null ? "ALL" : requested.getChannel();
        Iterator<NotificationSetting> existing = settings.iterator();
        Integer threshold = requested.getThreshold();
        int position = settings.size();
        int index = 0;
        while (existing.hasNext()) {
            NotificationSetting setting = existing.next();
            if (setting.getEvent().equals(requested.getEvent()) && channel.equals(setting.getChannel())) {
                if (threshold == null) {
                    threshold = setting.getThreshold();
                }
                existing.remove();
                position = index;
                break;
            }
            index++;
        }
        settings.add(position, new NotificationSetting(requested.getEvent(), channel, requested.isEnabled(),
                threshold == null ? DEFAULT_NOTIFICATION_THRESHOLD : threshold, configuredBy));
    }

    private static AttributeMapping mappingOf(Profile profile, String field) {
        for (AttributeMapping mapping : profile.getAttributeMappings()) {
            if (field.equals(mapping.getCertificateField())) {
                return mapping;
            }
        }
        return null;
    }

    private static Map<String, List<String>> defaultAttributeMappings() {
        List<String> distinguishedName = List.of("CN", "OU", "O", "C", "ST", "L");
        Map<String, List<String>> mappings = new LinkedHashMap<>();
        mappings.put("x509Subject", distinguishedName);
        mappings.put("x509Issuer", distinguishedName);
        mappings.put("x509SAN", List.of("Name/DNS", "Name/URI"));
        return Collections.unmodifiableMap(mappings);
    }

    private static <T> List<T> inRegion(AccountAwareStorageBackend<T> store, String region) {
        return new ArrayList<>(store.scan(key -> key.startsWith(region + "::")));
    }

    private static <T> Page<T> page(List<T> items, Function<T, String> cursorOf, String pageSize, String nextToken,
                                    String namespace) {
        Integer size = Pagination.parseMaxResults(pageSize, "pageSize", "ValidationException");
        if (size != null && size < 1) {
            throw validation("pageSize must be at least 1, got " + size + ".");
        }
        if (nextToken != null) {
            String violation = SmithyValidationErrors.lengthOutOfRange("nextToken", nextToken, 1, 10000);
            if (violation != null) {
                throw SmithyValidationErrors.validationException(violation);
            }
        }
        PaginatedResult<T> page = Pagination.paginate(items, cursorOf, size == null ? DEFAULT_PAGE_SIZE : size,
                nextToken, namespace, token -> validation("Invalid nextToken."));
        return new Page<>(page.items(), page.nextToken());
    }

    /** A resource's position in its list: its creation time to the nanosecond, then its id, which no two share. */
    private static String cursor(Instant createdAt, String id) {
        return String.format("%012d%09d:%s", createdAt.getEpochSecond(), createdAt.getNano(), id);
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }
}
