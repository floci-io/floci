package io.github.hectorvent.floci.services.lightsail;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class LightsailJsonHandler {

    private static final Set<String> ACTIONS = Set.of(
            "AllocateStaticIp", "AttachCertificateToDistribution", "AttachDisk", "AttachInstancesToLoadBalancer",
            "AttachLoadBalancerTlsCertificate", "AttachStaticIp", "CloseInstancePublicPorts", "CopySnapshot",
            "CreateBucket", "CreateBucketAccessKey", "CreateCertificate", "CreateCloudFormationStack",
            "CreateContactMethod", "CreateContainerService", "CreateContainerServiceDeployment",
            "CreateContainerServiceRegistryLogin", "CreateDisk", "CreateDiskFromSnapshot", "CreateDiskSnapshot",
            "CreateDistribution", "CreateDomain", "CreateDomainEntry", "CreateGUISessionAccessDetails",
            "CreateInstanceSnapshot", "CreateInstances", "CreateInstancesFromSnapshot", "CreateKeyPair",
            "CreateLoadBalancer", "CreateLoadBalancerTlsCertificate", "CreateRelationalDatabase",
            "CreateRelationalDatabaseFromSnapshot", "CreateRelationalDatabaseSnapshot", "DeleteAlarm",
            "DeleteAutoSnapshot", "DeleteBucket", "DeleteBucketAccessKey", "DeleteCertificate",
            "DeleteContactMethod", "DeleteContainerImage", "DeleteContainerService", "DeleteDisk",
            "DeleteDiskSnapshot", "DeleteDistribution", "DeleteDomain", "DeleteDomainEntry", "DeleteInstance",
            "DeleteInstanceSnapshot", "DeleteKeyPair", "DeleteKnownHostKeys", "DeleteLoadBalancer",
            "DeleteLoadBalancerTlsCertificate", "DeleteRelationalDatabase", "DeleteRelationalDatabaseSnapshot",
            "DetachCertificateFromDistribution", "DetachDisk", "DetachInstancesFromLoadBalancer", "DetachStaticIp",
            "DisableAddOn", "DownloadDefaultKeyPair", "EnableAddOn", "ExportSnapshot", "GetActiveNames",
            "GetAlarms", "GetAutoSnapshots", "GetBlueprints", "GetBucketAccessKeys", "GetBucketBundles",
            "GetBucketMetricData", "GetBuckets", "GetBundles", "GetCertificates", "GetCloudFormationStackRecords",
            "GetContactMethods", "GetContainerAPIMetadata", "GetContainerImages", "GetContainerLog",
            "GetContainerServiceDeployments", "GetContainerServiceMetricData", "GetContainerServicePowers",
            "GetContainerServices", "GetCostEstimate", "GetDisk", "GetDiskSnapshot", "GetDiskSnapshots",
            "GetDisks", "GetDistributionBundles", "GetDistributionLatestCacheReset", "GetDistributionMetricData",
            "GetDistributions", "GetDomain", "GetDomains", "GetExportSnapshotRecords", "GetInstance",
            "GetInstanceAccessDetails", "GetInstanceMetricData", "GetInstancePortStates", "GetInstanceSnapshot",
            "GetInstanceSnapshots", "GetInstanceState", "GetInstances", "GetKeyPair", "GetKeyPairs",
            "GetLoadBalancer", "GetLoadBalancerMetricData", "GetLoadBalancerTlsCertificates",
            "GetLoadBalancerTlsPolicies", "GetLoadBalancers", "GetOperation", "GetOperations",
            "GetOperationsForResource", "GetRegions", "GetRelationalDatabase", "GetRelationalDatabaseBlueprints",
            "GetRelationalDatabaseBundles", "GetRelationalDatabaseEvents", "GetRelationalDatabaseLogEvents",
            "GetRelationalDatabaseLogStreams", "GetRelationalDatabaseMasterUserPassword",
            "GetRelationalDatabaseMetricData", "GetRelationalDatabaseParameters", "GetRelationalDatabaseSnapshot",
            "GetRelationalDatabaseSnapshots", "GetRelationalDatabases", "GetSetupHistory", "GetStaticIp",
            "GetStaticIps", "ImportKeyPair", "IsVpcPeered", "OpenInstancePublicPorts", "PeerVpc", "PutAlarm",
            "PutInstancePublicPorts", "RebootInstance", "RebootRelationalDatabase", "RegisterContainerImage",
            "ReleaseStaticIp", "ResetDistributionCache", "SendContactMethodVerification", "SetIpAddressType",
            "SetResourceAccessForBucket", "SetupInstanceHttps", "StartGUISession", "StartInstance",
            "StartRelationalDatabase", "StopGUISession", "StopInstance", "StopRelationalDatabase", "TagResource",
            "TestAlarm", "UnpeerVpc", "UntagResource", "UpdateBucket", "UpdateBucketBundle",
            "UpdateContainerService", "UpdateDistribution", "UpdateDistributionBundle", "UpdateDomainEntry",
            "UpdateInstanceMetadataOptions", "UpdateLoadBalancerAttribute", "UpdateRelationalDatabase",
            "UpdateRelationalDatabaseParameters"
    );

    private static final Map<String, String> EMPTY_LIST_RESULTS = Map.ofEntries(
            Map.entry("GetAlarms", "alarms"),
            Map.entry("GetAutoSnapshots", "autoSnapshots"),
            Map.entry("GetBucketAccessKeys", "accessKeys"),
            Map.entry("GetBucketBundles", "bundles"),
            Map.entry("GetBucketMetricData", "metricData"),
            Map.entry("GetBuckets", "buckets"),
            Map.entry("GetCertificates", "certificates"),
            Map.entry("GetCloudFormationStackRecords", "cloudFormationStackRecords"),
            Map.entry("GetContactMethods", "contactMethods"),
            Map.entry("GetContainerAPIMetadata", "metadata"),
            Map.entry("GetContainerImages", "containerImages"),
            Map.entry("GetContainerLog", "logEvents"),
            Map.entry("GetContainerServiceDeployments", "deployments"),
            Map.entry("GetContainerServiceMetricData", "metricData"),
            Map.entry("GetContainerServicePowers", "powers"),
            Map.entry("GetContainerServices", "containerServices"),
            Map.entry("GetCostEstimate", "resourcesBudgetEstimate"),
            Map.entry("GetDiskSnapshots", "diskSnapshots"),
            Map.entry("GetDistributionBundles", "bundles"),
            Map.entry("GetDistributionMetricData", "metricData"),
            Map.entry("GetDistributions", "distributions"),
            Map.entry("GetDomains", "domains"),
            Map.entry("GetExportSnapshotRecords", "exportSnapshotRecords"),
            Map.entry("GetInstanceMetricData", "metricData"),
            Map.entry("GetInstanceSnapshots", "instanceSnapshots"),
            Map.entry("GetLoadBalancerMetricData", "metricData"),
            Map.entry("GetLoadBalancerTlsCertificates", "tlsCertificates"),
            Map.entry("GetLoadBalancerTlsPolicies", "tlsPolicies"),
            Map.entry("GetLoadBalancers", "loadBalancers"),
            Map.entry("GetRelationalDatabaseBlueprints", "blueprints"),
            Map.entry("GetRelationalDatabaseBundles", "bundles"),
            Map.entry("GetRelationalDatabaseEvents", "relationalDatabaseEvents"),
            Map.entry("GetRelationalDatabaseLogEvents", "resourceLogEvents"),
            Map.entry("GetRelationalDatabaseLogStreams", "logStreams"),
            Map.entry("GetRelationalDatabaseMetricData", "metricData"),
            Map.entry("GetRelationalDatabaseParameters", "parameters"),
            Map.entry("GetRelationalDatabaseSnapshots", "relationalDatabaseSnapshots"),
            Map.entry("GetRelationalDatabases", "relationalDatabases"),
            Map.entry("GetSetupHistory", "setupHistory")
    );

    private final LightsailService service;

    @Inject
    public LightsailJsonHandler(LightsailService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateInstances" -> ok(service.createInstances(region, request));
            case "GetInstances" -> ok(service.getInstances(region));
            case "GetInstance" -> ok(service.getInstance(region, required(request, "instanceName")));
            case "DeleteInstance" -> ok(service.deleteInstance(region, required(request, "instanceName")));
            case "StartInstance" -> ok(service.startInstance(region, required(request, "instanceName")));
            case "StopInstance" -> ok(service.stopInstance(region, required(request, "instanceName")));
            case "RebootInstance" -> ok(service.rebootInstance(region, required(request, "instanceName")));
            case "GetInstanceState" -> ok(service.getInstanceState(region, required(request, "instanceName")));
            case "GetInstancePortStates" -> ok(service.getInstancePortStates(region, required(request, "instanceName")));
            case "OpenInstancePublicPorts" -> ok(service.putInstancePorts(region, requiredInstanceName(request), request, true));
            case "PutInstancePublicPorts" -> ok(service.putInstancePorts(region, requiredInstanceName(request), request, false));
            case "CloseInstancePublicPorts" -> ok(service.closeInstancePorts(region, requiredInstanceName(request), request));
            case "CreateDisk" -> ok(service.createDisk(region, request));
            case "GetDisks" -> ok(service.getDisks(region));
            case "GetDisk" -> ok(service.getDisk(region, required(request, "diskName")));
            case "AttachDisk" -> ok(service.attachDisk(region, request));
            case "DetachDisk" -> ok(service.detachDisk(region, required(request, "diskName")));
            case "DeleteDisk" -> ok(service.deleteDisk(region, required(request, "diskName")));
            case "AllocateStaticIp" -> ok(service.allocateStaticIp(region, required(request, "staticIpName")));
            case "GetStaticIps" -> ok(service.getStaticIps(region));
            case "GetStaticIp" -> ok(service.getStaticIp(region, required(request, "staticIpName")));
            case "AttachStaticIp" -> ok(service.attachStaticIp(region, request));
            case "DetachStaticIp" -> ok(service.detachStaticIp(region, required(request, "staticIpName")));
            case "ReleaseStaticIp" -> ok(service.releaseStaticIp(region, required(request, "staticIpName")));
            case "CreateKeyPair" -> ok(service.createKeyPair(region, request));
            case "ImportKeyPair" -> ok(service.importKeyPair(region, request));
            case "DownloadDefaultKeyPair" -> ok(service.downloadDefaultKeyPair(region));
            case "GetKeyPairs" -> ok(service.getKeyPairs(region));
            case "GetKeyPair" -> ok(service.getKeyPair(region, required(request, "keyPairName")));
            case "DeleteKeyPair" -> ok(service.deleteKeyPair(region, required(request, "keyPairName")));
            case "GetRegions" -> ok(service.getRegions(request));
            case "GetBlueprints" -> ok(service.getBlueprints());
            case "GetBundles" -> ok(service.getBundles());
            case "TagResource" -> ok(service.tagResource(region, request));
            case "UntagResource" -> ok(service.untagResource(region, request));
            case "GetActiveNames" -> ok(service.getActiveNames(region));
            case "GetOperations" -> ok(service.getOperations(region));
            case "GetOperationsForResource" -> ok(service.getOperationsForResource(region, required(request, "resourceName")));
            case "GetOperation" -> ok(service.getOperation(region, required(request, "operationId")));
            case "IsVpcPeered" -> ok(service.isVpcPeered());
            default -> handleDefault(action);
        };
    }

    private Response handleDefault(String action) {
        String fieldName = EMPTY_LIST_RESULTS.get(action);
        if (fieldName != null) {
            return ok(service.emptyList(fieldName));
        }
        if (ACTIONS.contains(action)) {
            throw new AwsException("UnsupportedOperation",
                    "Operation " + action + " is recognized by Amazon Lightsail but is not implemented in Floci.", 400);
        }
        throw new AwsException("UnknownOperationException", "Unknown operation " + action, 404);
    }

    private static Response ok(JsonNode response) {
        return Response.ok(response).build();
    }

    private static String requiredInstanceName(JsonNode request) {
        return required(request, "instanceName");
    }

    private static String required(JsonNode request, String field) {
        String value = request.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidInputException", field + " is required", 400);
        }
        return value;
    }
}
