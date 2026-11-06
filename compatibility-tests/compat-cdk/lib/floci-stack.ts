import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as servicediscovery from 'aws-cdk-lib/aws-servicediscovery';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as apigwv2 from 'aws-cdk-lib/aws-apigatewayv2';
import * as apigwv2Integrations from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import * as acm from 'aws-cdk-lib/aws-certificatemanager';
import * as route53 from 'aws-cdk-lib/aws-route53';
import * as route53Targets from 'aws-cdk-lib/aws-route53-targets';
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

    // VPC + internal ALB — regression for issue #1297: CloudFormation must create the
    // EC2 VPC/subnets for real (in EC2), otherwise the ALB that references the
    // CDK-generated subnets fails with "The subnet ID '...' does not exist".
    const vpc = new ec2.Vpc(this, 'TestVpc', {
      vpcName: 'floci-cdk-vpc',
      maxAzs: 2,
      natGateways: 0,
      subnetConfiguration: [
        { name: 'public', subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24 },
        { name: 'isolated', subnetType: ec2.SubnetType.PRIVATE_ISOLATED, cidrMask: 24 },
      ],
    });

    const alb = new elbv2.ApplicationLoadBalancer(this, 'TestAlb', {
      loadBalancerName: 'floci-cdk-alb',
      vpc,
      internetFacing: false,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
    });

    // Cloud Map namespace + service: regression for https://github.com/floci-io/floci/issues/4596.
    const namespace = new servicediscovery.PrivateDnsNamespace(this, 'TestNamespace', {
      name: 'floci-cdk.internal',
      vpc,
    });
    const service = namespace.createService('TestService', {
      name: 'floci-cdk-svc',
      dnsRecordType: servicediscovery.DnsRecordType.A,
      dnsTtl: cdk.Duration.seconds(30),
    });

    // API Gateway custom domains: an HTTP API mapped at the root of an AWS::ApiGatewayV2::DomainName
    // with an alias record to it, and a REST API under a key with several levels, which CDK maps
    // with AWS::ApiGatewayV2::ApiMapping because a base path mapping takes one level only.
    const apiZone = new route53.PublicHostedZone(this, 'ApiZone', { zoneName: 'floci-cdk-api.example.com' });
    const httpApiDomain = new apigwv2.DomainName(this, 'HttpApiDomain', {
      domainName: 'http.floci-cdk-api.example.com',
      certificate: new acm.Certificate(this, 'HttpApiCertificate', {
        domainName: 'http.floci-cdk-api.example.com',
        validation: acm.CertificateValidation.fromDns(apiZone),
      }),
    });
    const httpApi = new apigwv2.HttpApi(this, 'TestHttpApi', {
      defaultIntegration: new apigwv2Integrations.HttpUrlIntegration('Backend', 'https://example.com'),
      defaultDomainMapping: { domainName: httpApiDomain },
    });
    new route53.ARecord(this, 'HttpApiAlias', {
      zone: apiZone,
      recordName: 'http.floci-cdk-api.example.com',
      target: route53.RecordTarget.fromAlias(new route53Targets.ApiGatewayv2DomainProperties(
        httpApiDomain.regionalDomainName, httpApiDomain.regionalHostedZoneId)),
    });
    const restApi = new apigateway.RestApi(this, 'TestRestApi');
    restApi.root.addResource('items').addMethod('GET', new apigateway.HttpIntegration('https://example.com'));
    new apigateway.DomainName(this, 'RestApiDomain', {
      domainName: 'rest.floci-cdk-api.example.com',
      certificate: new acm.Certificate(this, 'RestApiCertificate', {
        domainName: 'rest.floci-cdk-api.example.com',
        validation: acm.CertificateValidation.fromDns(apiZone),
      }),
      mapping: restApi,
      basePath: 'orders/v1',
    });

    new cdk.CfnOutput(this, 'BucketName', { value: bucket.bucketName });
    new cdk.CfnOutput(this, 'QueueUrl', { value: queue.queueUrl });
    new cdk.CfnOutput(this, 'TableName', { value: table.tableName });
    new cdk.CfnOutput(this, 'IndexTableName', { value: indexTable.tableName });
    new cdk.CfnOutput(this, 'GeneratedSecretName', { value: generatedSecret.name || 'floci-cdk-generated-secret' });
    new cdk.CfnOutput(this, 'VpcId', { value: vpc.vpcId });
    new cdk.CfnOutput(this, 'AlbArn', { value: alb.loadBalancerArn });
    new cdk.CfnOutput(this, 'NamespaceId', { value: namespace.namespaceId });
    new cdk.CfnOutput(this, 'ServiceId', { value: service.serviceId });
    new cdk.CfnOutput(this, 'HttpApiId', { value: httpApi.apiId });
    new cdk.CfnOutput(this, 'RestApiId', { value: restApi.restApiId });
  }
}
