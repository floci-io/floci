package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationResourceProvisioner;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
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
    /** RequestToken → ProgressEvent. Cloud Control is async; clients poll by token. */
    private final Map<String, ProgressEvent> requests = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newFixedThreadPool(4);

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
        JsonNode props;
        try {
            props = (desiredStateJson == null || desiredStateJson.isBlank())
                    ? mapper.createObjectNode() : mapper.readTree(desiredStateJson);
        } catch (Exception e) {
            throw new AwsException("InvalidRequest", "DesiredState is not valid JSON.", 400);
        }
        String token = UUID.randomUUID().toString();
        ProgressEvent pending = new ProgressEvent(typeName, null, token, "CREATE", "IN_PROGRESS", null, null);
        requests.put(token, pending);
        executor.submit(() -> {
            try {
                var resource = provisioner.provisionStandalone(typeName, props, region, ACCOUNT);
                if (resource == null || resource.getPhysicalId() == null) {
                    requests.put(token, pending.failed("CreateResource is not supported for " + typeName + "."));
                } else {
                    String model = resourceModel(region, typeName, resource.getPhysicalId(), props);
                    requests.put(token, new ProgressEvent(typeName, resource.getPhysicalId(),
                            token, "CREATE", "SUCCESS", null, model));
                }
            } catch (Exception e) {
                requests.put(token, pending.failed(e.getMessage() == null ? e.toString() : e.getMessage()));
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
        provisioner.deleteStandalone(typeName, identifier, region);
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
        throw new AwsException("ResourceNotFoundException",
                "Resource " + identifier + " of type " + typeName + " was not found.", 404);
    }

    private ProgressEvent record(ProgressEvent event) {
        requests.put(event.requestToken(), event);
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
            default -> List.of();
        };
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
