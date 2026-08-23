package io.github.hectorvent.floci.services.datasync.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Optional;

/**
 * The DataSync location families, one per {@code CreateLocation*} operation.
 *
 * <p>Each constant carries the operation suffix that names its Create/Describe/Update
 * trio, the URI scheme AWS builds the location's {@code LocationUri} from, the members
 * the model marks required on create, and the members its {@code DescribeLocation*}
 * response echoes back from the create request.
 */
@RegisterForReflection
public enum DataSyncLocationType {

    AZURE_BLOB("AzureBlob", "azure-blob",
            List.of("ContainerUrl", "AuthenticationType"),
            List.of("AuthenticationType", "BlobType", "AccessTier", "AgentArns",
                    "CmkSecretConfig", "CustomSecretConfig")),

    EFS("Efs", "efs",
            List.of("EfsFilesystemArn", "Ec2Config"),
            List.of("Ec2Config", "AccessPointArn", "FileSystemAccessRoleArn", "InTransitEncryption")),

    FSX_LUSTRE("FsxLustre", "fsxl",
            List.of("FsxFilesystemArn", "SecurityGroupArns"),
            List.of("SecurityGroupArns")),

    FSX_ONTAP("FsxOntap", "fsxn",
            List.of("Protocol", "SecurityGroupArns", "StorageVirtualMachineArn"),
            List.of("Protocol", "SecurityGroupArns", "StorageVirtualMachineArn", "FsxFilesystemArn")),

    FSX_OPEN_ZFS("FsxOpenZfs", "fsxz",
            List.of("FsxFilesystemArn", "Protocol", "SecurityGroupArns"),
            List.of("SecurityGroupArns", "Protocol")),

    FSX_WINDOWS("FsxWindows", "fsxw",
            List.of("FsxFilesystemArn", "SecurityGroupArns", "User", "Password"),
            List.of("SecurityGroupArns", "User", "Domain")),

    HDFS("Hdfs", "hdfs",
            List.of("NameNodes", "AuthenticationType", "AgentArns"),
            List.of("NameNodes", "BlockSize", "ReplicationFactor", "KmsKeyProviderUri",
                    "QopConfiguration", "AuthenticationType", "SimpleUser", "KerberosPrincipal",
                    "AgentArns")),

    NFS("Nfs", "nfs",
            List.of("Subdirectory", "ServerHostname", "OnPremConfig"),
            List.of("OnPremConfig", "MountOptions")),

    OBJECT_STORAGE("ObjectStorage", "object-storage",
            List.of("ServerHostname", "BucketName"),
            List.of("AccessKey", "ServerPort", "ServerProtocol", "AgentArns", "ServerCertificate",
                    "CmkSecretConfig", "CustomSecretConfig")),

    S3("S3", "s3",
            List.of("S3BucketArn", "S3Config"),
            List.of("S3StorageClass", "S3Config", "AgentArns")),

    SMB("Smb", "smb",
            List.of("Subdirectory", "ServerHostname", "AgentArns"),
            List.of("AgentArns", "User", "Domain", "MountOptions", "DnsIpAddresses",
                    "KerberosPrincipal", "AuthenticationType", "CmkSecretConfig", "CustomSecretConfig"));

    private final String operationSuffix;
    private final String uriScheme;
    private final List<String> requiredMembers;
    private final List<String> describeMembers;

    DataSyncLocationType(String operationSuffix, String uriScheme,
                         List<String> requiredMembers, List<String> describeMembers) {
        this.operationSuffix = operationSuffix;
        this.uriScheme = uriScheme;
        this.requiredMembers = requiredMembers;
        this.describeMembers = describeMembers;
    }

    public static Optional<DataSyncLocationType> fromOperationSuffix(String suffix) {
        for (DataSyncLocationType type : values()) {
            if (type.operationSuffix.equals(suffix)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    public String operationSuffix() {
        return operationSuffix;
    }

    public String uriScheme() {
        return uriScheme;
    }

    public List<String> requiredMembers() {
        return requiredMembers;
    }

    public List<String> describeMembers() {
        return describeMembers;
    }
}
