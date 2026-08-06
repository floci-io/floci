package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationResourceProvisioner;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CloudControlService {

    /** Cloud Control has no account context in the request; Floci's default test account. */
    private static final String ACCOUNT = "000000000000";

    private final S3Service s3Service;
    private final Ec2Service ec2Service;
    private final IamService iamService;
    private final CloudFormationResourceProvisioner provisioner;
    private final ObjectMapper mapper;
    /** How many finished request tokens to keep before evicting the oldest. */
    private static final int MAX_RETAINED_REQUESTS = 1000;

    /**
     * Types whose delete needs state captured at create time — a custom resource's ServiceToken and
     * properties, a nodegroup's cluster name, an inline policy's principals. Deleting one of these
     * from type and identifier alone is a no-op, so Cloud Control must not report SUCCESS for it.
     */
    private static final java.util.Set<String> ATTRIBUTE_BACKED_DELETES =
            java.util.Set.of("AWS::EKS::Nodegroup", "AWS::IAM::Policy");

    /** RequestToken → ProgressEvent. Cloud Control is async; clients poll by token. */
    private final Map<String, ProgressEvent> requests = new ConcurrentHashMap<>();
    /** Token insertion order, so the map can be bounded without losing in-flight requests. */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> requestOrder =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /**
     * What CreateResource provisioned, keyed by region/type/identifier. Carries the attributes the
     * delete path needs and the model the read path returns for types outside {@link #listResources}.
     * Entries are dropped when the resource is deleted.
     */
    private final Map<String, CreatedResource> created = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Create-time state for a resource this service provisioned. */
    private record CreatedResource(Map<String, String> attributes, String model) {}

    private static String createdKey(String region, String typeName, String identifier) {
        return region + "|" + typeName + "|" + identifier;
    }

    @Inject
    public CloudControlService(S3Service s3Service, Ec2Service ec2Service,
                               IamService iamService, CloudFormationResourceProvisioner provisioner,
                               ObjectMapper mapper) {
        this.s3Service = s3Service;
        this.ec2Service = ec2Service;
        this.iamService = iamService;
        this.provisioner = provisioner;
        this.mapper = mapper;
    }

    /**
     * Cloud Control {@code CreateResource}. Cloud Control is asynchronous: the call returns an
     * IN_PROGRESS ProgressEvent + a request token immediately, and provisioning runs in the
     * background. Clients poll {@link #requestStatus} until SUCCESS/FAILED. This matters because
     * some resources (e.g. an EC2 instance, which launches a container) take longer than a client's
     * synchronous-call deadline — a synchronous create would time out on the caller.
     */
    public ProgressEvent createResource(String region, String typeName, String desiredStateJson) {
        // DesiredState is a required member of CreateResourceInput. Defaulting an absent one to an
        // empty object provisioned a resource the caller never described.
        if (desiredStateJson == null || desiredStateJson.isBlank()) {
            throw new AwsException("InvalidRequestException", "DesiredState is required.", 400);
        }
        JsonNode props;
        try {
            props = mapper.readTree(desiredStateJson);
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", "DesiredState is not valid JSON.", 400);
        }
        String token = UUID.randomUUID().toString();
        ProgressEvent pending = new ProgressEvent(typeName, null, token, "CREATE", "IN_PROGRESS", null, null);
        record(pending);
        executor.submit(() -> {
            try {
                var resource = provisioner.provisionStandalone(typeName, props, region, ACCOUNT);
                if (resource == null || resource.getPhysicalId() == null) {
                    record(pending.failed("CreateResource is not supported for " + typeName + "."));
                } else {
                    String model = resourceModel(region, typeName, resource.getPhysicalId(), props);
                    // Kept so GetResource can read back a type the read side does not list, and so
                    // DeleteResource has the attributes its delete path needs.
                    created.put(createdKey(region, typeName, resource.getPhysicalId()),
                            new CreatedResource(
                                    resource.getAttributes() == null
                                            ? Map.of() : Map.copyOf(resource.getAttributes()),
                                    model));
                    record(new ProgressEvent(typeName, resource.getPhysicalId(),
                            token, "CREATE", "SUCCESS", null, model));
                }
            } catch (Exception e) {
                record(pending.failed(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
        return pending;
    }

    /**
     * The created resource's state, as Cloud Control returns in a ProgressEvent's ResourceModel —
     * clients read it to resolve references (e.g. a subnet's SubnetId) without a second call. Prefer
     * the read side (which carries the schema property names); fall back to the desired state echoed
     * with the primary identifier, which is what dependents key on.
     */
    private String resourceModel(String region, String typeName, String physicalId, JsonNode desiredState) {
        try {
            for (ResourceDescription d : listResources(region, typeName)) {
                if (physicalId.equals(d.identifier())) {
                    return d.properties();
                }
            }
        } catch (Exception ignored) {
            // fall through to the desired-state echo
        }
        ObjectNode model = desiredState != null && desiredState.isObject()
                ? ((ObjectNode) desiredState).deepCopy() : mapper.createObjectNode();
        model.put(primaryIdentifierField(typeName), physicalId);
        return propertiesString(model);
    }

    /** The read-only primary identifier property name for the common EC2/IAM types. */
    private static String primaryIdentifierField(String typeName) {
        return switch (typeName) {
            case "AWS::EC2::VPC" -> "VpcId";
            case "AWS::EC2::Subnet" -> "SubnetId";
            case "AWS::EC2::SecurityGroup" -> "GroupId";
            case "AWS::EC2::Instance" -> "InstanceId";
            case "AWS::EC2::InternetGateway" -> "InternetGatewayId";
            case "AWS::EC2::RouteTable" -> "RouteTableId";
            case "AWS::EC2::LaunchTemplate" -> "LaunchTemplateId";
            default -> "Id";
        };
    }

    /** Cloud Control {@code DeleteResource}. Deletes are quick, so this stays synchronous. */
    public ProgressEvent deleteResource(String region, String typeName, String identifier) {
        String key = createdKey(region, typeName, identifier);
        CreatedResource state = created.get(key);
        Map<String, String> attributes = state == null ? Map.of() : state.attributes();

        boolean custom = typeName != null
                && (typeName.startsWith("Custom::") || "AWS::CloudFormation::CustomResource".equals(typeName));
        if (attributes.isEmpty() && (custom || ATTRIBUTE_BACKED_DELETES.contains(typeName))) {
            // The delete would no-op. Reporting SUCCESS over a resource that is still there is the
            // worse failure, so surface it instead.
            return record(new ProgressEvent(typeName, identifier, UUID.randomUUID().toString(),
                    "DELETE", "FAILED",
                    "DeleteResource for " + typeName + " needs create-time state that Cloud Control does "
                    + "not hold for " + identifier + ".", null));
        }

        provisioner.deleteStandalone(typeName, identifier, region, attributes);
        created.remove(key);
        return record(new ProgressEvent(typeName, identifier,
                UUID.randomUUID().toString(), "DELETE", "SUCCESS", null, null));
    }

    /** Cloud Control {@code GetResourceRequestStatus}. */
    public ProgressEvent requestStatus(String requestToken) {
        ProgressEvent event = requests.get(requestToken);
        if (event == null) {
            throw new AwsException("RequestTokenNotFoundException",
                    "Request token " + requestToken + " was not found.", 404);
        }
        return event;
    }

    /** Cloud Control {@code GetResource}: a single resource from the read side, by identifier. */
    public ResourceDescription getResource(String region, String typeName, String identifier) {
        for (ResourceDescription d : listResources(region, typeName)) {
            if (d.identifier().equals(identifier)) {
                return d;
            }
        }
        // CreateResource provisions the whole CFN type set while the read side lists six types, so
        // fall back to what the create recorded — otherwise a successful create is unreadable.
        CreatedResource state = created.get(createdKey(region, typeName, identifier));
        if (state != null) {
            return new ResourceDescription(identifier, state.model());
        }
        throw new AwsException("ResourceNotFoundException",
                "Resource " + identifier + " of type " + typeName + " was not found.", 404);
    }

    /**
     * Stores a request's latest state, evicting the oldest finished tokens once the map grows past
     * {@link #MAX_RETAINED_REQUESTS}. In-flight tokens are never evicted — a client still polling
     * must not get RequestTokenNotFound.
     */
    private ProgressEvent record(ProgressEvent event) {
        if (requests.put(event.requestToken(), event) == null) {
            requestOrder.add(event.requestToken());
        }
        while (requests.size() > MAX_RETAINED_REQUESTS) {
            String oldest = requestOrder.poll();
            if (oldest == null) {
                break;
            }
            ProgressEvent existing = requests.get(oldest);
            if (existing != null && "IN_PROGRESS".equals(existing.operationStatus())) {
                requestOrder.add(oldest); // still running — keep it and move on
                break;
            }
            requests.remove(oldest);
        }
        return event;
    }

    public record ProgressEvent(String typeName, String identifier, String requestToken,
                                String operation, String operationStatus, String statusMessage,
                                String resourceModel) {
        ProgressEvent failed(String message) {
            return new ProgressEvent(typeName, identifier, requestToken, operation, "FAILED", message, resourceModel);
        }
    }

    public List<ResourceDescription> listResources(String region, String typeName) {
        return switch (typeName) {
            case "AWS::S3::Bucket" -> s3Buckets();
            case "AWS::EC2::VPC" -> vpcs(region);
            case "AWS::EC2::Subnet" -> subnets(region);
            case "AWS::EC2::SecurityGroup" -> securityGroups(region);
            case "AWS::IAM::Role" -> roles();
            case "AWS::IAM::User" -> users();
            case "AWS::EC2::Instance" -> instances(region);
            case "AWS::EC2::LaunchTemplate" -> launchTemplates(region);
            case "AWS::IAM::InstanceProfile" -> instanceProfiles();
            default -> List.of();
        };
    }

    /**
     * Cloud Control reports a resource's current model, not an echo of what was asked for, so an
     * instance has to carry the values only the running resource has — its addresses, its state,
     * and the subnet and security groups it actually landed in. A caller that provisions through
     * Cloud Control and then reads the resource back has no other way to reach them.
     */
    private List<ResourceDescription> instances(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Reservation reservation : ec2Service.describeInstances(region, List.of(), Map.of())) {
            for (Instance instance : reservation.getInstances()) {
                ObjectNode properties = mapper.createObjectNode();
                properties.put("InstanceId", instance.getInstanceId());
                putIfPresent(properties, "ImageId", instance.getImageId());
                putIfPresent(properties, "InstanceType", instance.getInstanceType());
                putIfPresent(properties, "SubnetId", instance.getSubnetId());
                putIfPresent(properties, "VpcId", instance.getVpcId());
                putIfPresent(properties, "PrivateIp", instance.getPrivateIpAddress());
                putIfPresent(properties, "PublicIp", instance.getPublicIpAddress());
                putIfPresent(properties, "AvailabilityZone",
                        instance.getPlacement() == null ? null : instance.getPlacement().getAvailabilityZone());
                if (instance.getState() != null) {
                    putIfPresent(properties, "State", instance.getState().getName());
                }
                if (instance.getSecurityGroups() != null && !instance.getSecurityGroups().isEmpty()) {
                    var groups = properties.putArray("SecurityGroupIds");
                    for (GroupIdentifier g : instance.getSecurityGroups()) {
                        if (g.getGroupId() != null) groups.add(g.getGroupId());
                    }
                }
                resources.add(new ResourceDescription(instance.getInstanceId(), propertiesString(properties)));
            }
        }
        return resources;
    }

    private List<ResourceDescription> launchTemplates(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (LaunchTemplate lt : ec2Service.describeLaunchTemplates(region, List.of(), List.of(), Map.of())) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("LaunchTemplateId", lt.getLaunchTemplateId());
            putIfPresent(properties, "LaunchTemplateName", lt.getLaunchTemplateName());
            putIfPresent(properties, "LatestVersionNumber", lt.getLatestVersionNumber());
            putIfPresent(properties, "DefaultVersionNumber", lt.getDefaultVersionNumber());
            resources.add(new ResourceDescription(lt.getLaunchTemplateId(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> instanceProfiles() {
        List<ResourceDescription> resources = new ArrayList<>();
        for (InstanceProfile profile : iamService.listInstanceProfiles("/")) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("InstanceProfileName", profile.getInstanceProfileName());
            putIfPresent(properties, "Arn", profile.getArn());
            putIfPresent(properties, "Path", profile.getPath());
            resources.add(new ResourceDescription(profile.getInstanceProfileName(), propertiesString(properties)));
        }
        return resources;
    }

    /** Cloud Control omits a property it has no value for rather than reporting a null. */
    private static void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null && !value.isBlank()) node.put(name, value);
    }

    private List<ResourceDescription> s3Buckets() {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Bucket bucket : s3Service.listBuckets()) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("BucketName", bucket.getName());
            resources.add(new ResourceDescription(bucket.getName(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> vpcs(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Vpc vpc : ec2Service.describeVpcs(region, List.of(), Map.of())) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("VpcId", vpc.getVpcId());
            properties.put("CidrBlock", vpc.getCidrBlock());
            properties.put("InstanceTenancy", vpc.getInstanceTenancy());
            addTags(properties, vpc.getTags());
            resources.add(new ResourceDescription(vpc.getVpcId(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> subnets(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (Subnet subnet : ec2Service.describeSubnets(region, List.of(), Map.of())) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("SubnetId", subnet.getSubnetId());
            properties.put("VpcId", subnet.getVpcId());
            properties.put("CidrBlock", subnet.getCidrBlock());
            properties.put("AvailabilityZone", subnet.getAvailabilityZone());
            addTags(properties, subnet.getTags());
            resources.add(new ResourceDescription(subnet.getSubnetId(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> securityGroups(String region) {
        List<ResourceDescription> resources = new ArrayList<>();
        for (SecurityGroup group : ec2Service.describeSecurityGroups(region, List.of(), List.of(), Map.of())) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("GroupId", group.getGroupId());
            properties.put("GroupName", group.getGroupName());
            properties.put("GroupDescription", group.getDescription());
            properties.put("VpcId", group.getVpcId());
            addTags(properties, group.getTags());
            resources.add(new ResourceDescription(group.getGroupId(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> roles() {
        List<ResourceDescription> resources = new ArrayList<>();
        for (IamRole role : iamService.listRoles("/")) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("Arn", role.getArn());
            properties.put("RoleName", role.getRoleName());
            properties.put("Path", role.getPath());
            resources.add(new ResourceDescription(role.getRoleName(), propertiesString(properties)));
        }
        return resources;
    }

    private List<ResourceDescription> users() {
        List<ResourceDescription> resources = new ArrayList<>();
        for (IamUser user : iamService.listUsers("/")) {
            ObjectNode properties = mapper.createObjectNode();
            properties.put("Arn", user.getArn());
            properties.put("UserName", user.getUserName());
            properties.put("Path", user.getPath());
            resources.add(new ResourceDescription(user.getUserName(), propertiesString(properties)));
        }
        return resources;
    }

    private String propertiesString(ObjectNode properties) {
        try {
            return mapper.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalFailure",
                    "Failed to serialize CloudControl resource properties.", 500);
        }
    }

    private void addTags(ObjectNode properties, List<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        List<Tag> validTags = tags.stream()
                .filter(tag -> tag != null && tag.getKey() != null && !tag.getKey().isBlank())
                .toList();
        if (validTags.isEmpty()) {
            return;
        }
        var tagArray = properties.putArray("Tags");
        for (Tag tag : validTags) {
            tagArray.addObject()
                    .put("Key", tag.getKey())
                    .put("Value", tag.getValue() == null ? "" : tag.getValue());
        }
    }

    public record ResourceDescription(String identifier, String properties) {}
}
