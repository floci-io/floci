package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
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

@ApplicationScoped
public class CloudControlService {

    private final S3Service s3Service;
    private final Ec2Service ec2Service;
    private final IamService iamService;
    private final ObjectMapper mapper;

    @Inject
    public CloudControlService(S3Service s3Service, Ec2Service ec2Service,
                               IamService iamService, ObjectMapper mapper) {
        this.s3Service = s3Service;
        this.ec2Service = ec2Service;
        this.iamService = iamService;
        this.mapper = mapper;
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

    public record ResourceDescription(String identifier, String properties) {}
}
