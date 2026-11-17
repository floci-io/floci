package io.floci.aws.services.emr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.emr.model.EmrStudio;
import io.floci.aws.services.emr.model.EmrStudioSessionMapping;
import io.floci.aws.services.identitystore.IdentityStoreService;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import io.floci.aws.services.ssoadmin.model.SsoInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * EMR Studios and their session mappings (Amazon EMR Management Guide, "EMR Studio"). A Studio is kept with the VPC,
 * subnets and security groups it names, each of which must be in Floci's EC2, and a Studio that authenticates with IAM
 * Identity Center maps that instance's users and groups to it, each found in its identity store. Floci serves no
 * Studio: its URL is on Floci's own address, where nothing answers.
 */
@ApplicationScoped
public class EmrStudioService {

    static final String AUTH_SSO = "SSO";
    static final String AUTH_IAM = "IAM";
    static final List<String> AUTH_MODES = List.of(AUTH_SSO, AUTH_IAM);
    static final List<String> IDENTITY_TYPES = List.of("USER", "GROUP");
    static final List<String> USER_ASSIGNMENTS = List.of("REQUIRED", "OPTIONAL");
    // "A Studio can have a maximum of 5 subnets."
    static final int MAX_SUBNETS = 5;
    // A Studio's ARN's resource, before its id.
    static final String STUDIO_RESOURCE = "studio/";
    // Where a Studio's URL points; nothing answers there, as Floci serves no Studio.
    static final String URL_PATH = "/_aws/emr/studios/";
    // A page of ListStudios or ListStudioSessionMappings: no source gives AWS's size; EMR's other Marker lists give 50.
    static final int PAGE_SIZE = 50;
    private static final int MAX_NAME_LENGTH = 256;
    private static final int ID_LENGTH = 25;

    private final AccountAwareStorageBackend<EmrStudio> store;
    private final RegionResolver regionResolver;
    private final Ec2Service ec2;
    private final SsoAdminService ssoAdmin;
    private final IdentityStoreService identityStore;
    private final Clock clock;
    private final String baseUrl;
    // The last sequence issued, so that none is issued twice in this process.
    private long lastSequence;

    @Inject
    public EmrStudioService(StorageFactory storageFactory, RegionResolver regionResolver, Ec2Service ec2,
                            SsoAdminService ssoAdmin, IdentityStoreService identityStore, EmulatorConfig config) {
        this(storageFactory.create("emr", "emr-studios.json", new TypeReference<Map<String, EmrStudio>>() {}),
                regionResolver, ec2, ssoAdmin, identityStore, Clock.systemUTC(), config.effectiveBaseUrl());
    }

    EmrStudioService(AccountAwareStorageBackend<EmrStudio> store, RegionResolver regionResolver, Ec2Service ec2,
                     SsoAdminService ssoAdmin, IdentityStoreService identityStore, Clock clock, String baseUrl) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.ec2 = ec2;
        this.ssoAdmin = ssoAdmin;
        this.identityStore = identityStore;
        this.clock = clock;
        String base = baseUrl == null ? "" : baseUrl;
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * Creates a Studio. The model's required members and enums are checked first, with the ValidationException AWS's
     * validation layer answers. Then the API reference's and the guide's rules, each refused with
     * InvalidRequestException, the one error the model declares, in Floci's own wording: "A Studio can have a maximum
     * of 5 subnets"; "The subnets must belong to the VPC specified by VpcId", and each security group "must be in the
     * same VPC"; "When you use IAM Identity Center authentication, you must specify a --user-role", and "Only specify
     * a UserRole when you use IAM Identity Center authentication"; the IdP members, for "when you use IAM
     * authentication", only with it.
     */
    public synchronized EmrStudio createStudio(EmrStudio requested) {
        List<String> violations = new ArrayList<>();
        requireMember(violations, "name", requested.getName());
        requireMember(violations, "authMode", requested.getAuthMode());
        requireMember(violations, "vpcId", requested.getVpcId());
        if (requested.getSubnetIds() == null) {
            violations.add(SmithyValidationErrors.notNull("subnetIds"));
        }
        requireMember(violations, "serviceRole", requested.getServiceRole());
        requireMember(violations, "workspaceSecurityGroupId", requested.getWorkspaceSecurityGroupId());
        requireMember(violations, "engineSecurityGroupId", requested.getEngineSecurityGroupId());
        requireMember(violations, "defaultS3Location", requested.getDefaultS3Location());
        checkEnum(violations, "authMode", requested.getAuthMode(), AUTH_MODES);
        checkEnum(violations, "idcUserAssignment", requested.getIdcUserAssignment(), USER_ASSIGNMENTS);
        checkLength(violations, "name", requested.getName());
        checkLength(violations, "description", requested.getDescription());
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        boolean sso = AUTH_SSO.equals(requested.getAuthMode());
        if (sso && requested.getUserRole() == null) {
            throw EmrService.invalid("A Studio that uses IAM Identity Center authentication needs a UserRole.");
        }
        if (!sso && requested.getUserRole() != null) {
            throw EmrService.invalid("A UserRole is only for a Studio that uses IAM Identity Center authentication.");
        }
        if (sso && (requested.getIdpAuthUrl() != null || requested.getIdpRelayStateParameterName() != null)) {
            throw EmrService.invalid("IdpAuthUrl and IdpRelayStateParameterName are only for a Studio that uses IAM"
                    + " authentication.");
        }
        String region = regionResolver.getRegion();
        checkNetwork(region, requested.getVpcId(), requested.getSubnetIds(), null);
        checkSecurityGroup(region, requested.getVpcId(), requested.getWorkspaceSecurityGroupId());
        checkSecurityGroup(region, requested.getVpcId(), requested.getEngineSecurityGroupId());
        if (sso) {
            identityStoreOf(requested.getIdcInstanceArn());
        }

        EmrStudio studio = copy(requested);
        String id = "es-" + EmrService.randomId(ID_LENGTH);
        studio.setStudioId(id);
        studio.setStudioArn(regionResolver.buildArn("elasticmapreduce", region, STUDIO_RESOURCE + id));
        studio.setUrl(baseUrl + URL_PATH + id);
        studio.setCreationTime(now());
        studio.setRegion(region);
        studio.setSequence(nextSequence());
        studio.setSubnetIds(new ArrayList<>(new LinkedHashSet<>(requested.getSubnetIds())));
        if (sso && studio.getTrustedIdentityPropagationEnabled() == null) {
            // "The default value is false."
            studio.setTrustedIdentityPropagationEnabled(false);
        }
        studio.setSessionMappings(new ArrayList<>());
        store.put(id, studio);
        return copy(studio);
    }

    public synchronized EmrStudio describeStudio(String studioId) {
        return copy(requireStudio(studioId));
    }

    /**
     * The Region's Studios in the order they were made, a page at a time after the request's Marker. No source gives
     * AWS's order.
     */
    public synchronized PaginatedResult<EmrStudio> listStudios(String marker) {
        List<EmrStudio> studios = store.scan(key -> true).stream()
                .filter(studio -> regionResolver.getRegion().equals(studio.getRegion()))
                .toList();
        PaginatedResult<EmrStudio> page = Pagination.paginate(studios,
                studio -> String.format("%020d", studio.getSequence()), PAGE_SIZE, marker, "ListStudios",
                token -> EmrService.invalid("Invalid Marker."));
        return new PaginatedResult<>(page.items().stream().map(EmrStudioService::copy).toList(), page.nextToken());
    }

    /**
     * Updates a Studio's name, description, subnets, default S3 location and encryption key, as given. Its subnets
     * "can include new subnet IDs, but must also include all of the subnet IDs previously associated with the
     * Studio", at most 5, in its VPC.
     */
    public synchronized EmrStudio updateStudio(String studioId, String name, String description,
                                               List<String> subnetIds, String defaultS3Location,
                                               String encryptionKeyArn) {
        List<String> violations = new ArrayList<>();
        checkLength(violations, "name", name);
        checkLength(violations, "description", description);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        EmrStudio studio = requireStudio(studioId);
        if (subnetIds != null) {
            checkNetwork(studio.getRegion(), studio.getVpcId(), subnetIds, studio.getSubnetIds());
            studio.setSubnetIds(new ArrayList<>(new LinkedHashSet<>(subnetIds)));
        }
        if (name != null) {
            studio.setName(name);
        }
        if (description != null) {
            studio.setDescription(description);
        }
        if (defaultS3Location != null) {
            studio.setDefaultS3Location(defaultS3Location);
        }
        if (encryptionKeyArn != null) {
            studio.setEncryptionKeyArn(encryptionKeyArn);
        }
        store.put(studioId, studio);
        return copy(studio);
    }

    /**
     * Adds tags to a Studio, a tag call's {@code ResourceId} being "a cluster identifier or an Amazon EMR Studio ID".
     */
    public synchronized void addTags(String studioId, Map<String, String> tags) {
        EmrStudio studio = requireStudio(studioId);
        Map<String, String> updated = new LinkedHashMap<>(studio.getTags());
        updated.putAll(tags);
        studio.setTags(updated);
        store.put(studioId, studio);
    }

    public synchronized void removeTags(String studioId, List<String> keys) {
        EmrStudio studio = requireStudio(studioId);
        Map<String, String> updated = new LinkedHashMap<>(studio.getTags());
        keys.forEach(updated::remove);
        studio.setTags(updated);
        store.put(studioId, studio);
    }

    /** Whether a tag call's {@code ResourceId} names a Studio rather than a cluster. */
    static boolean isStudioId(String resourceId) {
        return resourceId != null && resourceId.startsWith("es-");
    }

    /** The ARN of the Studio of that id in the Region, for IAM; empty when there is none. */
    public synchronized Optional<String> studioArn(String studioId, String region) {
        return store.get(studioId).filter(studio -> region.equals(studio.getRegion())).map(EmrStudio::getStudioArn);
    }

    /** The tags held on the Studio stored under {@code arn}, in the account and Region it names, for IAM. */
    public Map<String, String> storedTags(String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        if (!resource.startsWith(STUDIO_RESOURCE) || resource.indexOf('/', STUDIO_RESOURCE.length()) >= 0) {
            return Map.of();
        }
        Map<String, String> held = new LinkedHashMap<>();
        String studioId = resource.substring(STUDIO_RESOURCE.length());
        AccountAwareStorageBackend.readInAccount(store, parsed.accountId(), studioId)
                .filter(studio -> parsed.region().equals(studio.getRegion()) && arn.equals(studio.getStudioArn()))
                .map(EmrStudio::getTags)
                .ifPresent(tags -> tags.forEach((key, value) -> {
                    if (key != null && value != null) {
                        held.put(key, value);
                    }
                }));
        return held;
    }

    /** Removes a Studio "from the Studio metadata store", its session mappings with it. */
    public synchronized void deleteStudio(String studioId) {
        requireStudio(studioId);
        store.delete(studioId);
    }

    // ──────────────────────────── Session mappings ────────────────────────────

    /**
     * Maps a user or group of the Studio's IAM Identity Center instance to it, with a session policy, as "Use
     * CreateStudioSessionMapping to assign users to a Studio when you use IAM Identity Center authentication". The
     * identity is named by "Either IdentityName or IdentityId ... but not both", and must be in the instance's
     * identity store, the user by its UserName and the group by its DisplayName. One already mapped is refused.
     * Each refusal is InvalidRequestException in Floci's own wording.
     */
    public synchronized EmrStudioSessionMapping createSessionMapping(String studioId, Identity identity,
                                                                     String sessionPolicyArn) {
        requireMappingMembers(identity, sessionPolicyArn, true);
        EmrStudio studio = requireStudio(studioId);
        if (!AUTH_SSO.equals(studio.getAuthMode())) {
            throw EmrService.invalid("Studio " + studioId + " uses IAM authentication; session mappings are for a"
                    + " Studio that uses IAM Identity Center authentication.");
        }
        Identity resolved = resolve(studio, identity);
        if (mappingOf(studio, resolved).isPresent()) {
            throw EmrService.invalid("The " + resolved.type().toLowerCase() + " " + resolved.id()
                    + " is already mapped to Studio " + studioId + ".");
        }
        Instant now = now();
        EmrStudioSessionMapping mapping = new EmrStudioSessionMapping();
        mapping.setIdentityId(resolved.id());
        mapping.setIdentityName(resolved.name());
        mapping.setIdentityType(resolved.type());
        mapping.setSessionPolicyArn(sessionPolicyArn);
        mapping.setCreationTime(now);
        mapping.setLastModifiedTime(now);
        mapping.setSequence(nextSequence());
        studio.getSessionMappings().add(mapping);
        store.put(studioId, studio);
        return copy(mapping);
    }

    /**
     * A mapping, found by the id or the name it was made with, without asking the identity store again: one whose
     * user or group has since gone can still be read, updated and removed.
     */
    public synchronized EmrStudioSessionMapping getSessionMapping(String studioId, Identity identity) {
        requireMappingMembers(identity, null, false);
        EmrStudio studio = requireStudio(studioId);
        return copy(requireMapping(studio, identity));
    }

    public synchronized EmrStudioSessionMapping updateSessionMapping(String studioId, Identity identity,
                                                                     String sessionPolicyArn) {
        requireMappingMembers(identity, sessionPolicyArn, true);
        EmrStudio studio = requireStudio(studioId);
        EmrStudioSessionMapping mapping = requireMapping(studio, identity);
        mapping.setSessionPolicyArn(sessionPolicyArn);
        mapping.setLastModifiedTime(now());
        store.put(studioId, studio);
        return copy(mapping);
    }

    public synchronized void deleteSessionMapping(String studioId, Identity identity) {
        requireMappingMembers(identity, null, false);
        EmrStudio studio = requireStudio(studioId);
        EmrStudioSessionMapping mapping = requireMapping(studio, identity);
        studio.getSessionMappings().remove(mapping);
        store.put(studioId, studio);
    }

    /**
     * The session mappings of the Studio, or of every Studio in the Region when the request names none, filtered by
     * {@code identityType}, by Studio in the order they were made and then each Studio's in the order they were made,
     * a page at a time after the request's Marker. No source gives AWS's order.
     */
    public synchronized PaginatedResult<StudioMapping> listSessionMappings(String studioId, String identityType,
                                                                           String marker) {
        List<String> violations = new ArrayList<>();
        checkEnum(violations, "identityType", identityType, IDENTITY_TYPES);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        List<EmrStudio> studios = studioId != null ? List.of(requireStudio(studioId))
                : store.scan(key -> true).stream()
                        .filter(studio -> regionResolver.getRegion().equals(studio.getRegion()))
                        .sorted(Comparator.comparingLong(EmrStudio::getSequence))
                        .toList();
        List<StudioMapping> mappings = new ArrayList<>();
        for (EmrStudio studio : studios) {
            for (EmrStudioSessionMapping mapping : studio.getSessionMappings()) {
                if (identityType == null || identityType.equals(mapping.getIdentityType())) {
                    mappings.add(new StudioMapping(studio.getStudioId(), studio.getSequence(), mapping));
                }
            }
        }
        String namespace = String.join("|", "ListStudioSessionMappings", String.valueOf(studioId),
                String.valueOf(identityType));
        PaginatedResult<StudioMapping> page = Pagination.paginate(mappings, StudioMapping::cursor, PAGE_SIZE, marker,
                namespace, token -> EmrService.invalid("Invalid Marker."));
        return new PaginatedResult<>(page.items().stream()
                .map(item -> new StudioMapping(item.studioId(), item.studioSequence(), copy(item.mapping())))
                .toList(), page.nextToken());
    }

    /** A session mapping, with the Studio it maps to and that Studio's place among the account's. */
    public record StudioMapping(String studioId, long studioSequence, EmrStudioSessionMapping mapping) {
        // Sorts as the listing does: by Studio, then by mapping, each in the order they were made.
        String cursor() {
            return String.format("%020d%020d", studioSequence, mapping.getSequence());
        }
    }

    /** The identity a session mapping call names: its type, and its id or its name. */
    public record Identity(String type, String id, String name) {
    }

    private void requireMappingMembers(Identity identity, String sessionPolicyArn, boolean policyRequired) {
        List<String> violations = new ArrayList<>();
        if (identity.type() == null) {
            violations.add(SmithyValidationErrors.notNull("identityType"));
        }
        if (policyRequired && sessionPolicyArn == null) {
            violations.add(SmithyValidationErrors.notNull("sessionPolicyArn"));
        }
        checkEnum(violations, "identityType", identity.type(), IDENTITY_TYPES);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        if ((identity.id() == null) == (identity.name() == null)) {
            throw EmrService.invalid("Either IdentityName or IdentityId must be specified, but not both.");
        }
    }

    // The identity, with its id and its name, as the Studio's identity store holds it.
    private Identity resolve(EmrStudio studio, Identity identity) {
        String storeId = identityStoreOf(studio.getIdcInstanceArn());
        boolean user = "USER".equals(identity.type());
        Optional<Identity> found;
        if (user) {
            found = (identity.id() != null ? identityStore.findUser(storeId, identity.id())
                    : identityStore.findUserByName(storeId, identity.name()))
                    .map(u -> new Identity(identity.type(), u.userId(), u.userName()));
        } else {
            found = (identity.id() != null ? identityStore.findGroup(storeId, identity.id())
                    : identityStore.findGroupByDisplayName(storeId, identity.name()))
                    .map(g -> new Identity(identity.type(), g.groupId(), g.displayName()));
        }
        return found.orElseThrow(() -> EmrService.invalid("The " + (user ? "user " : "group ")
                + (identity.id() != null ? identity.id() : identity.name())
                + " is not in the Studio's IAM Identity Center identity store."));
    }

    // The identity store of the Studio's IAM Identity Center instance: the one it names, or else the account's.
    private String identityStoreOf(String idcInstanceArn) {
        List<SsoInstance> instances = ssoAdmin.listInstances(regionResolver.getAccountId());
        return instances.stream()
                .filter(instance -> idcInstanceArn == null || idcInstanceArn.equals(instance.instanceArn()))
                .map(SsoInstance::identityStoreId)
                .findFirst()
                .orElseThrow(() -> EmrService.invalid(idcInstanceArn == null
                        ? "The account has no IAM Identity Center instance."
                        : "IAM Identity Center instance " + idcInstanceArn + " does not exist."));
    }

    private static Optional<EmrStudioSessionMapping> mappingOf(EmrStudio studio, Identity identity) {
        return studio.getSessionMappings().stream()
                .filter(mapping -> identity.type().equals(mapping.getIdentityType())
                        && identity.id().equals(mapping.getIdentityId()))
                .findFirst();
    }

    // The mapping of the identity a call names, by the id or the name it was mapped with.
    private static EmrStudioSessionMapping requireMapping(EmrStudio studio, Identity identity) {
        return studio.getSessionMappings().stream()
                .filter(mapping -> identity.type().equals(mapping.getIdentityType()))
                .filter(mapping -> identity.id() != null ? identity.id().equals(mapping.getIdentityId())
                        : identity.name().equals(mapping.getIdentityName()))
                .findFirst()
                .orElseThrow(() -> EmrService.invalid("The " + identity.type().toLowerCase() + " "
                        + (identity.id() != null ? identity.id() : identity.name()) + " is not mapped to Studio "
                        + studio.getStudioId() + "."));
    }

    /**
     * A sequence above every one issued before, in this process or an earlier one, so that the number of a deleted
     * Studio or mapping is never issued again and a listing's marker never skips a newer one: the microseconds of
     * now, or one more than the last.
     */
    private long nextSequence() {
        lastSequence = Math.max(clock.instant().toEpochMilli() * 1000, lastSequence + 1);
        return lastSequence;
    }

    // The subnets, at most 5 and each in the VPC, which must exist; on an update, a superset of the current ones.
    private void checkNetwork(String region, String vpcId, List<String> subnetIds, List<String> current) {
        if (subnetIds.isEmpty()) {
            throw EmrService.invalid("A Studio needs at least one subnet.");
        }
        if (new LinkedHashSet<>(subnetIds).size() > MAX_SUBNETS) {
            throw EmrService.invalid("A Studio can have a maximum of " + MAX_SUBNETS + " subnets.");
        }
        if (current != null && !subnetIds.containsAll(current)) {
            throw EmrService.invalid("The subnets must include all of the subnet IDs previously associated with"
                    + " the Studio.");
        }
        if (ec2.describeVpcs(region, List.of(vpcId), Map.of()).isEmpty()) {
            throw EmrService.invalid("VPC " + vpcId + " does not exist.");
        }
        List<Subnet> subnets = ec2.describeSubnets(region, List.copyOf(new LinkedHashSet<>(subnetIds)), Map.of());
        for (String subnetId : subnetIds) {
            Subnet subnet = subnets.stream().filter(s -> subnetId.equals(s.getSubnetId())).findFirst()
                    .orElseThrow(() -> EmrService.invalid("Subnet " + subnetId + " does not exist."));
            if (!vpcId.equals(subnet.getVpcId())) {
                throw EmrService.invalid("Subnet " + subnetId + " is not in VPC " + vpcId + ".");
            }
        }
    }

    private void checkSecurityGroup(String region, String vpcId, String groupId) {
        SecurityGroup group = ec2.describeSecurityGroups(region, List.of(groupId), List.of(), Map.of()).stream()
                .findFirst()
                .orElseThrow(() -> EmrService.invalid("Security group " + groupId + " does not exist."));
        if (!vpcId.equals(group.getVpcId())) {
            throw EmrService.invalid("Security group " + groupId + " is not in VPC " + vpcId + ".");
        }
    }

    private EmrStudio requireStudio(String studioId) {
        if (studioId == null) {
            throw SmithyValidationErrors.validationException(SmithyValidationErrors.notNull("studioId"));
        }
        return store.get(studioId)
                .filter(studio -> regionResolver.getRegion().equals(studio.getRegion()))
                .orElseThrow(() -> EmrService.invalid("Studio " + studioId + " does not exist."));
    }

    private static void requireMember(List<String> violations, String member, String value) {
        if (value == null) {
            violations.add(SmithyValidationErrors.notNull(member));
        }
    }

    private static void checkEnum(List<String> violations, String member, String value, List<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            violations.add(SmithyValidationErrors.notInEnum(value, member, allowed));
        }
    }

    private static void checkLength(List<String> violations, String member, String value) {
        if (value != null && value.length() > MAX_NAME_LENGTH) {
            violations.add(SmithyValidationErrors.lengthOutOfRange(member, value, 0, MAX_NAME_LENGTH));
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static EmrStudio copy(EmrStudio studio) {
        return EmrService.SNAPSHOT_MAPPER.convertValue(studio, EmrStudio.class);
    }

    private static EmrStudioSessionMapping copy(EmrStudioSessionMapping mapping) {
        return EmrService.SNAPSHOT_MAPPER.convertValue(mapping, EmrStudioSessionMapping.class);
    }
}
