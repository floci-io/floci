package io.github.hectorvent.floci.services.iam;

import java.util.List;

/**
 * Catalog of commonly-used AWS managed policies seeded at startup.
 * Policy documents use a permissive wildcard because floci does not
 * enforce IAM policy evaluation.
 */
final class AwsManagedPolicies {

    static final String ARN_PREFIX = "arn:aws:iam::aws:policy";

    static final String PERMISSIVE_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";

    record ManagedPolicyDef(String name, String path, String description) {
        String arn() {
            return ARN_PREFIX + path + name;
        }
    }

    static final List<ManagedPolicyDef> POLICIES = List.of(
        // General access policies
        new ManagedPolicyDef("AdministratorAccess", "/",
                "Provides full access to AWS services and resources."),
        new ManagedPolicyDef("PowerUserAccess", "/",
                "Provides full access to AWS services and resources, but does not allow management of Users and groups."),
        new ManagedPolicyDef("ReadOnlyAccess", "/",
                "Provides read-only access to AWS services and resources."),
        new ManagedPolicyDef("IAMFullAccess", "/",
                "Provides full access to IAM."),
        new ManagedPolicyDef("AmazonS3FullAccess", "/",
                "Provides full access to all buckets via the AWS Management Console."),
        new ManagedPolicyDef("AmazonS3ReadOnlyAccess", "/",
                "Provides read-only access to all buckets via the AWS Management Console."),
        new ManagedPolicyDef("AmazonDynamoDBFullAccess", "/",
                "Provides full access to Amazon DynamoDB via the AWS Management Console."),
        new ManagedPolicyDef("AmazonEC2FullAccess", "/",
                "Provides full access to Amazon EC2 via the AWS Management Console."),
        new ManagedPolicyDef("AmazonEC2ContainerRegistryReadOnly", "/",
                "Provides read-only access to Amazon EC2 Container Registry repositories."),
        new ManagedPolicyDef("AmazonSQSFullAccess", "/",
                "Provides full access to Amazon SQS via the AWS Management Console."),
        new ManagedPolicyDef("AmazonSNSFullAccess", "/",
                "Provides full access to Amazon SNS via the AWS Management Console."),
        new ManagedPolicyDef("AmazonVPCFullAccess", "/",
                "Provides full access to Amazon VPC via the AWS Management Console."),
        new ManagedPolicyDef("CloudWatchFullAccess", "/",
                "Provides full access to CloudWatch."),
        new ManagedPolicyDef("CloudWatchAgentServerPolicy", "/",
                "Provides permissions required to use the CloudWatch agent on servers."),
        new ManagedPolicyDef("AWSLambdaFullAccess", "/",
                "Provides full access to Lambda, S3, DynamoDB, CloudWatch Metrics and Logs."),

        // Lambda execution role policies
        new ManagedPolicyDef("AWSLambdaBasicExecutionRole", "/service-role/",
                "Provides write permissions to CloudWatch Logs."),
        new ManagedPolicyDef("AWSLambdaBasicDurableExecutionRolePolicy", "/service-role/",
                "Provides write permissions to CloudWatch Logs and read/write permissions to durable execution APIs for Lambda durable functions."),
        new ManagedPolicyDef("AWSLambdaDynamoDBExecutionRole", "/service-role/",
                "Provides list and read access to DynamoDB streams and write permissions to CloudWatch Logs."),
        new ManagedPolicyDef("AWSLambdaKinesisExecutionRole", "/service-role/",
                "Provides list and read access to Kinesis streams and write permissions to CloudWatch Logs."),
        new ManagedPolicyDef("AWSLambdaMSKExecutionRole", "/service-role/",
                "Provides permissions required to access an MSK cluster within a VPC, manage network interfaces, and write to CloudWatch Logs."),
        new ManagedPolicyDef("AWSLambdaSQSQueueExecutionRole", "/service-role/",
                "Provides receive message, delete message, and read attribute access to SQS queues, and write permissions to CloudWatch Logs."),
        new ManagedPolicyDef("AWSLambdaVPCAccessExecutionRole", "/service-role/",
                "Provides minimum permissions for a Lambda function to execute while accessing a resource within a VPC."),

        // ECS / EKS execution role policies
        new ManagedPolicyDef("AmazonECSTaskExecutionRolePolicy", "/service-role/",
                "Provides the Amazon ECS container agent and Fargate agent permissions to make AWS API calls on your behalf."),
        new ManagedPolicyDef("AmazonEKSFargatePodExecutionRolePolicy", "/",
                "Provides access to other AWS service resources required to run Amazon EKS pods on AWS Fargate."),

        // EKS cluster and node group policies (required by the EKS console/SDK and
        // the terraform-aws-modules/eks module — see #1092).
        new ManagedPolicyDef("AmazonEKSClusterPolicy", "/",
                "Provides Kubernetes the permissions it requires to manage resources on your behalf."),
        new ManagedPolicyDef("AmazonEKSServicePolicy", "/",
                "This policy allows Amazon Elastic Container Service for Kubernetes to create and manage the necessary resources to operate EKS Clusters."),
        new ManagedPolicyDef("AmazonEKSVPCResourceController", "/",
                "Policy used by VPC Resource Controller to manage ENI and IPs for worker nodes."),
        new ManagedPolicyDef("AmazonEKSWorkerNodePolicy", "/",
                "This policy allows Amazon EKS worker nodes to connect to Amazon EKS Clusters."),
        new ManagedPolicyDef("AmazonEKS_CNI_Policy", "/",
                "Provides the Amazon VPC CNI Plugin the permissions it requires to modify the IP address configuration on your EKS worker nodes."),

        // S3 Object Lambda execution role policy
        new ManagedPolicyDef("AmazonS3ObjectLambdaExecutionRolePolicy", "/service-role/",
                "Provides write permissions to CloudWatch Logs for S3 Object Lambda access points."),

        // CloudWatch Lambda execution role policies
        new ManagedPolicyDef("CloudWatchLambdaInsightsExecutionRolePolicy", "/",
                "Allows Lambda Insights to create and write to CloudWatch Logs log groups for Lambda Insights monitoring."),
        new ManagedPolicyDef("CloudWatchLambdaApplicationSignalsExecutionRolePolicy", "/",
                "Provides write access to X-Ray and CloudWatch Application Signals log group."),

        // Config execution role policy
        new ManagedPolicyDef("AWSConfigRulesExecutionRole", "/service-role/",
                "Allows AWS Config Rules Lambda functions to call AWS services and read the configuration of AWS resources."),

        // MSK replicator execution role policy
        new ManagedPolicyDef("AWSMSKReplicatorExecutionRole", "/service-role/",
                "Grants permissions to Amazon MSK Replicator to replicate data between MSK Clusters."),

        // SSM Automation execution role policies
        new ManagedPolicyDef("AWS-SSM-DiagnosisAutomation-ExecutionRolePolicy", "/",
                "Provides permissions for AWS Systems Manager diagnosis automation execution."),
        new ManagedPolicyDef("AWS-SSM-RemediationAutomation-ExecutionRolePolicy", "/",
                "Provides permissions for AWS Systems Manager remediation automation execution."),
        new ManagedPolicyDef("AmazonSSMManagedInstanceCore", "/",
                "Provides permissions required for instances to use AWS Systems Manager core service functionality."),

        // SageMaker execution role policies
        new ManagedPolicyDef("AmazonSageMakerGeospatialExecutionRole", "/service-role/",
                "Provides full access to Amazon SageMaker Geospatial capabilities and related services."),
        new ManagedPolicyDef("AmazonSageMakerCanvasEMRServerlessExecutionRolePolicy", "/",
                "Provides access for Amazon SageMaker Canvas to manage EMR Serverless resources."),

        // SageMaker Studio execution role policies
        new ManagedPolicyDef("SageMakerStudioBedrockFunctionExecutionRolePolicy", "/service-role/",
                "Provides permissions for SageMaker Studio Bedrock function execution role."),
        new ManagedPolicyDef("SageMakerStudioDomainExecutionRolePolicy", "/service-role/",
                "Provides permissions for the SageMaker Studio domain execution role."),
        new ManagedPolicyDef("SageMakerStudioQueryExecutionRolePolicy", "/service-role/",
                "Provides permissions for SageMaker Studio query execution role."),

        // Amazon DataZone execution role policy
        new ManagedPolicyDef("AmazonDataZoneDomainExecutionRolePolicy", "/service-role/",
                "Provides permissions for the Amazon DataZone domain execution role."),

        // Amazon Bedrock execution role policy
        new ManagedPolicyDef("AmazonBedrockAgentCoreMemoryBedrockModelInferenceExecutionRolePolicy", "/",
                "Provides Bedrock Model inference permissions to Bedrock agent core memory."),

        // AWS Partner Central execution role policy
        new ManagedPolicyDef("AWSPartnerCentralSellingResourceSnapshotJobExecutionRolePolicy", "/",
                "Provides permissions for AWS Partner Central resource snapshot job execution role.")
    );

    private AwsManagedPolicies() {}
}
