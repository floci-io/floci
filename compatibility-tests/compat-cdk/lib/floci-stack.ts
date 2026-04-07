import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as path from 'path';
import { Construct } from 'constructs';

export class FlociTestStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    const bucket = new s3.Bucket(this, 'TestBucket', {
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const queue = new sqs.Queue(this, 'TestQueue', {
      queueName: 'floci-cdk-test-queue',
      visibilityTimeout: cdk.Duration.seconds(30),
    });

    const table = new dynamodb.TableV2(this, 'TestTable', {
      tableName: 'floci-cdk-test-table',
      partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    // DynamoDB table with GSI and LSI — validates CloudFormation index provisioning
    const indexTable = new dynamodb.TableV2(this, 'IndexTestTable', {
      tableName: 'floci-cdk-index-table',
      partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'sk', type: dynamodb.AttributeType.STRING },
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      globalSecondaryIndexes: [
        {
          indexName: 'gsi-1',
          partitionKey: { name: 'gsiPk', type: dynamodb.AttributeType.STRING },
          sortKey: { name: 'sk', type: dynamodb.AttributeType.STRING },
          projectionType: dynamodb.ProjectionType.ALL,
        },
      ],
      localSecondaryIndexes: [
        {
          indexName: 'lsi-1',
          sortKey: { name: 'lsiSk', type: dynamodb.AttributeType.STRING },
          projectionType: dynamodb.ProjectionType.KEYS_ONLY,
        },
      ],
    });

    const generatedSecret = new secretsmanager.CfnSecret(this, 'GeneratedSecret', {
      name: 'floci-cdk-generated-secret',
      generateSecretString: {
        secretStringTemplate: '{"username":"admin"}',
        generateStringKey: 'password',
        passwordLength: 24,
        excludeCharacters: 'abc',
      },
    });
    generatedSecret.applyRemovalPolicy(cdk.RemovalPolicy.DESTROY);

    // Custom Docker image Lambda — exercises ECR emulation end-to-end:
    // CDK builds the local Dockerfile, cdk-assets pushes it to the bootstrap
    // ECR repository (via Floci's emulated ECR + registry:2), and Floci's
    // Lambda runner pulls the image at invoke time.
    new lambda.DockerImageFunction(this, 'DockerHelloFn', {
      functionName: 'floci-cdk-docker-hello',
      code: lambda.DockerImageCode.fromImageAsset(path.join(__dirname, '..', 'docker-fn')),
      timeout: cdk.Duration.seconds(15),
      memorySize: 256,
    });

    new cdk.CfnOutput(this, 'BucketName', { value: bucket.bucketName });
    new cdk.CfnOutput(this, 'QueueUrl', { value: queue.queueUrl });
    new cdk.CfnOutput(this, 'TableName', { value: table.tableName });
    new cdk.CfnOutput(this, 'IndexTableName', { value: indexTable.tableName });
    new cdk.CfnOutput(this, 'GeneratedSecretName', { value: generatedSecret.name || 'floci-cdk-generated-secret' });
  }
}
