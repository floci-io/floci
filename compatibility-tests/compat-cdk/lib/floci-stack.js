"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.FlociTestStack = void 0;
const cdk = require("aws-cdk-lib");
const s3 = require("aws-cdk-lib/aws-s3");
const sqs = require("aws-cdk-lib/aws-sqs");
const dynamodb = require("aws-cdk-lib/aws-dynamodb");
const secretsmanager = require("aws-cdk-lib/aws-secretsmanager");
const lambda = require("aws-cdk-lib/aws-lambda");
const ec2 = require("aws-cdk-lib/aws-ec2");
const elbv2 = require("aws-cdk-lib/aws-elasticloadbalancingv2");
const servicediscovery = require("aws-cdk-lib/aws-servicediscovery");
const apigateway = require("aws-cdk-lib/aws-apigateway");
const apigwv2 = require("aws-cdk-lib/aws-apigatewayv2");
const apigwv2Integrations = require("aws-cdk-lib/aws-apigatewayv2-integrations");
const acm = require("aws-cdk-lib/aws-certificatemanager");
const route53 = require("aws-cdk-lib/aws-route53");
const route53Targets = require("aws-cdk-lib/aws-route53-targets");
const path = require("path");
class FlociTestStack extends cdk.Stack {
    constructor(scope, id, props) {
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
            target: route53.RecordTarget.fromAlias(new route53Targets.ApiGatewayv2DomainProperties(httpApiDomain.regionalDomainName, httpApiDomain.regionalHostedZoneId)),
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
exports.FlociTestStack = FlociTestStack;
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiZmxvY2ktc3RhY2suanMiLCJzb3VyY2VSb290IjoiIiwic291cmNlcyI6WyJmbG9jaS1zdGFjay50cyJdLCJuYW1lcyI6W10sIm1hcHBpbmdzIjoiOzs7QUFBQSxtQ0FBbUM7QUFDbkMseUNBQXlDO0FBQ3pDLDJDQUEyQztBQUMzQyxxREFBcUQ7QUFDckQsaUVBQWlFO0FBQ2pFLGlEQUFpRDtBQUNqRCwyQ0FBMkM7QUFDM0MsZ0VBQWdFO0FBQ2hFLHFFQUFxRTtBQUNyRSx5REFBeUQ7QUFDekQsd0RBQXdEO0FBQ3hELGlGQUFpRjtBQUNqRiwwREFBMEQ7QUFDMUQsbURBQW1EO0FBQ25ELGtFQUFrRTtBQUNsRSw2QkFBNkI7QUFHN0IsTUFBYSxjQUFlLFNBQVEsR0FBRyxDQUFDLEtBQUs7SUFDM0MsWUFBWSxLQUFnQixFQUFFLEVBQVUsRUFBRSxLQUFzQjtRQUM5RCxLQUFLLENBQUMsS0FBSyxFQUFFLEVBQUUsRUFBRSxLQUFLLENBQUMsQ0FBQztRQUV4QixNQUFNLE1BQU0sR0FBRyxJQUFJLEVBQUUsQ0FBQyxNQUFNLENBQUMsSUFBSSxFQUFFLFlBQVksRUFBRTtZQUMvQyxhQUFhLEVBQUUsR0FBRyxDQUFDLGFBQWEsQ0FBQyxPQUFPO1NBQ3pDLENBQUMsQ0FBQztRQUVILE1BQU0sS0FBSyxHQUFHLElBQUksR0FBRyxDQUFDLEtBQUssQ0FBQyxJQUFJLEVBQUUsV0FBVyxFQUFFO1lBQzdDLFNBQVMsRUFBRSxzQkFBc0I7WUFDakMsaUJBQWlCLEVBQUUsR0FBRyxDQUFDLFFBQVEsQ0FBQyxPQUFPLENBQUMsRUFBRSxDQUFDO1NBQzVDLENBQUMsQ0FBQztRQUVILE1BQU0sS0FBSyxHQUFHLElBQUksUUFBUSxDQUFDLE9BQU8sQ0FBQyxJQUFJLEVBQUUsV0FBVyxFQUFFO1lBQ3BELFNBQVMsRUFBRSxzQkFBc0I7WUFDakMsWUFBWSxFQUFFLEVBQUUsSUFBSSxFQUFFLElBQUksRUFBRSxJQUFJLEVBQUUsUUFBUSxDQUFDLGFBQWEsQ0FBQyxNQUFNLEVBQUU7WUFDakUsYUFBYSxFQUFFLEdBQUcsQ0FBQyxhQUFhLENBQUMsT0FBTztTQUN6QyxDQUFDLENBQUM7UUFFSCxnRkFBZ0Y7UUFDaEYsTUFBTSxVQUFVLEdBQUcsSUFBSSxRQUFRLENBQUMsT0FBTyxDQUFDLElBQUksRUFBRSxnQkFBZ0IsRUFBRTtZQUM5RCxTQUFTLEVBQUUsdUJBQXVCO1lBQ2xDLFlBQVksRUFBRSxFQUFFLElBQUksRUFBRSxJQUFJLEVBQUUsSUFBSSxFQUFFLFFBQVEsQ0FBQyxhQUFhLENBQUMsTUFBTSxFQUFFO1lBQ2pFLE9BQU8sRUFBRSxFQUFFLElBQUksRUFBRSxJQUFJLEVBQUUsSUFBSSxFQUFFLFFBQVEsQ0FBQyxhQUFhLENBQUMsTUFBTSxFQUFFO1lBQzVELGFBQWEsRUFBRSxHQUFHLENBQUMsYUFBYSxDQUFDLE9BQU87WUFDeEMsc0JBQXNCLEVBQUU7Z0JBQ3RCO29CQUNFLFNBQVMsRUFBRSxPQUFPO29CQUNsQixZQUFZLEVBQUUsRUFBRSxJQUFJLEVBQUUsT0FBTyxFQUFFLElBQUksRUFBRSxRQUFRLENBQUMsYUFBYSxDQUFDLE1BQU0sRUFBRTtvQkFDcEUsT0FBTyxFQUFFLEVBQUUsSUFBSSxFQUFFLElBQUksRUFBRSxJQUFJLEVBQUUsUUFBUSxDQUFDLGFBQWEsQ0FBQyxNQUFNLEVBQUU7b0JBQzVELGNBQWMsRUFBRSxRQUFRLENBQUMsY0FBYyxDQUFDLEdBQUc7aUJBQzVDO2FBQ0Y7WUFDRCxxQkFBcUIsRUFBRTtnQkFDckI7b0JBQ0UsU0FBUyxFQUFFLE9BQU87b0JBQ2xCLE9BQU8sRUFBRSxFQUFFLElBQUksRUFBRSxPQUFPLEVBQUUsSUFBSSxFQUFFLFFBQVEsQ0FBQyxhQUFhLENBQUMsTUFBTSxFQUFFO29CQUMvRCxjQUFjLEVBQUUsUUFBUSxDQUFDLGNBQWMsQ0FBQyxTQUFTO2lCQUNsRDthQUNGO1NBQ0YsQ0FBQyxDQUFDO1FBRUgsTUFBTSxlQUFlLEdBQUcsSUFBSSxjQUFjLENBQUMsU0FBUyxDQUFDLElBQUksRUFBRSxpQkFBaUIsRUFBRTtZQUM1RSxJQUFJLEVBQUUsNEJBQTRCO1lBQ2xDLG9CQUFvQixFQUFFO2dCQUNwQixvQkFBb0IsRUFBRSxzQkFBc0I7Z0JBQzVDLGlCQUFpQixFQUFFLFVBQVU7Z0JBQzdCLGNBQWMsRUFBRSxFQUFFO2dCQUNsQixpQkFBaUIsRUFBRSxLQUFLO2FBQ3pCO1NBQ0YsQ0FBQyxDQUFDO1FBQ0gsZUFBZSxDQUFDLGtCQUFrQixDQUFDLEdBQUcsQ0FBQyxhQUFhLENBQUMsT0FBTyxDQUFDLENBQUM7UUFFOUQsbUVBQW1FO1FBQ25FLHlFQUF5RTtRQUN6RSxzRUFBc0U7UUFDdEUsZ0RBQWdEO1FBQ2hELElBQUksTUFBTSxDQUFDLG1CQUFtQixDQUFDLElBQUksRUFBRSxlQUFlLEVBQUU7WUFDcEQsWUFBWSxFQUFFLHdCQUF3QjtZQUN0QyxJQUFJLEVBQUUsTUFBTSxDQUFDLGVBQWUsQ0FBQyxjQUFjLENBQUMsSUFBSSxDQUFDLElBQUksQ0FBQyxTQUFTLEVBQUUsSUFBSSxFQUFFLFdBQVcsQ0FBQyxDQUFDO1lBQ3BGLE9BQU8sRUFBRSxHQUFHLENBQUMsUUFBUSxDQUFDLE9BQU8sQ0FBQyxFQUFFLENBQUM7WUFDakMsVUFBVSxFQUFFLEdBQUc7U0FDaEIsQ0FBQyxDQUFDO1FBRUgsa0ZBQWtGO1FBQ2xGLDJFQUEyRTtRQUMzRSx5RUFBeUU7UUFDekUsTUFBTSxHQUFHLEdBQUcsSUFBSSxHQUFHLENBQUMsR0FBRyxDQUFDLElBQUksRUFBRSxTQUFTLEVBQUU7WUFDdkMsT0FBTyxFQUFFLGVBQWU7WUFDeEIsTUFBTSxFQUFFLENBQUM7WUFDVCxXQUFXLEVBQUUsQ0FBQztZQUNkLG1CQUFtQixFQUFFO2dCQUNuQixFQUFFLElBQUksRUFBRSxRQUFRLEVBQUUsVUFBVSxFQUFFLEdBQUcsQ0FBQyxVQUFVLENBQUMsTUFBTSxFQUFFLFFBQVEsRUFBRSxFQUFFLEVBQUU7Z0JBQ25FLEVBQUUsSUFBSSxFQUFFLFVBQVUsRUFBRSxVQUFVLEVBQUUsR0FBRyxDQUFDLFVBQVUsQ0FBQyxnQkFBZ0IsRUFBRSxRQUFRLEVBQUUsRUFBRSxFQUFFO2FBQ2hGO1NBQ0YsQ0FBQyxDQUFDO1FBRUgsTUFBTSxHQUFHLEdBQUcsSUFBSSxLQUFLLENBQUMsdUJBQXVCLENBQUMsSUFBSSxFQUFFLFNBQVMsRUFBRTtZQUM3RCxnQkFBZ0IsRUFBRSxlQUFlO1lBQ2pDLEdBQUc7WUFDSCxjQUFjLEVBQUUsS0FBSztZQUNyQixVQUFVLEVBQUUsRUFBRSxVQUFVLEVBQUUsR0FBRyxDQUFDLFVBQVUsQ0FBQyxnQkFBZ0IsRUFBRTtTQUM1RCxDQUFDLENBQUM7UUFFSCwrRkFBK0Y7UUFDL0YsTUFBTSxTQUFTLEdBQUcsSUFBSSxnQkFBZ0IsQ0FBQyxtQkFBbUIsQ0FBQyxJQUFJLEVBQUUsZUFBZSxFQUFFO1lBQ2hGLElBQUksRUFBRSxvQkFBb0I7WUFDMUIsR0FBRztTQUNKLENBQUMsQ0FBQztRQUNILE1BQU0sT0FBTyxHQUFHLFNBQVMsQ0FBQyxhQUFhLENBQUMsYUFBYSxFQUFFO1lBQ3JELElBQUksRUFBRSxlQUFlO1lBQ3JCLGFBQWEsRUFBRSxnQkFBZ0IsQ0FBQyxhQUFhLENBQUMsQ0FBQztZQUMvQyxNQUFNLEVBQUUsR0FBRyxDQUFDLFFBQVEsQ0FBQyxPQUFPLENBQUMsRUFBRSxDQUFDO1NBQ2pDLENBQUMsQ0FBQztRQUVILGlHQUFpRztRQUNqRyw2RkFBNkY7UUFDN0YsdUZBQXVGO1FBQ3ZGLE1BQU0sT0FBTyxHQUFHLElBQUksT0FBTyxDQUFDLGdCQUFnQixDQUFDLElBQUksRUFBRSxTQUFTLEVBQUUsRUFBRSxRQUFRLEVBQUUsMkJBQTJCLEVBQUUsQ0FBQyxDQUFDO1FBQ3pHLE1BQU0sYUFBYSxHQUFHLElBQUksT0FBTyxDQUFDLFVBQVUsQ0FBQyxJQUFJLEVBQUUsZUFBZSxFQUFFO1lBQ2xFLFVBQVUsRUFBRSxnQ0FBZ0M7WUFDNUMsV0FBVyxFQUFFLElBQUksR0FBRyxDQUFDLFdBQVcsQ0FBQyxJQUFJLEVBQUUsb0JBQW9CLEVBQUU7Z0JBQzNELFVBQVUsRUFBRSxnQ0FBZ0M7Z0JBQzVDLFVBQVUsRUFBRSxHQUFHLENBQUMscUJBQXFCLENBQUMsT0FBTyxDQUFDLE9BQU8sQ0FBQzthQUN2RCxDQUFDO1NBQ0gsQ0FBQyxDQUFDO1FBQ0gsTUFBTSxPQUFPLEdBQUcsSUFBSSxPQUFPLENBQUMsT0FBTyxDQUFDLElBQUksRUFBRSxhQUFhLEVBQUU7WUFDdkQsa0JBQWtCLEVBQUUsSUFBSSxtQkFBbUIsQ0FBQyxrQkFBa0IsQ0FBQyxTQUFTLEVBQUUscUJBQXFCLENBQUM7WUFDaEcsb0JBQW9CLEVBQUUsRUFBRSxVQUFVLEVBQUUsYUFBYSxFQUFFO1NBQ3BELENBQUMsQ0FBQztRQUNILElBQUksT0FBTyxDQUFDLE9BQU8sQ0FBQyxJQUFJLEVBQUUsY0FBYyxFQUFFO1lBQ3hDLElBQUksRUFBRSxPQUFPO1lBQ2IsVUFBVSxFQUFFLGdDQUFnQztZQUM1QyxNQUFNLEVBQUUsT0FBTyxDQUFDLFlBQVksQ0FBQyxTQUFTLENBQUMsSUFBSSxjQUFjLENBQUMsNEJBQTRCLENBQ3BGLGFBQWEsQ0FBQyxrQkFBa0IsRUFBRSxhQUFhLENBQUMsb0JBQW9CLENBQUMsQ0FBQztTQUN6RSxDQUFDLENBQUM7UUFDSCxNQUFNLE9BQU8sR0FBRyxJQUFJLFVBQVUsQ0FBQyxPQUFPLENBQUMsSUFBSSxFQUFFLGFBQWEsQ0FBQyxDQUFDO1FBQzVELE9BQU8sQ0FBQyxJQUFJLENBQUMsV0FBVyxDQUFDLE9BQU8sQ0FBQyxDQUFDLFNBQVMsQ0FBQyxLQUFLLEVBQUUsSUFBSSxVQUFVLENBQUMsZUFBZSxDQUFDLHFCQUFxQixDQUFDLENBQUMsQ0FBQztRQUMxRyxJQUFJLFVBQVUsQ0FBQyxVQUFVLENBQUMsSUFBSSxFQUFFLGVBQWUsRUFBRTtZQUMvQyxVQUFVLEVBQUUsZ0NBQWdDO1lBQzVDLFdBQVcsRUFBRSxJQUFJLEdBQUcsQ0FBQyxXQUFXLENBQUMsSUFBSSxFQUFFLG9CQUFvQixFQUFFO2dCQUMzRCxVQUFVLEVBQUUsZ0NBQWdDO2dCQUM1QyxVQUFVLEVBQUUsR0FBRyxDQUFDLHFCQUFxQixDQUFDLE9BQU8sQ0FBQyxPQUFPLENBQUM7YUFDdkQsQ0FBQztZQUNGLE9BQU8sRUFBRSxPQUFPO1lBQ2hCLFFBQVEsRUFBRSxXQUFXO1NBQ3RCLENBQUMsQ0FBQztRQUVILElBQUksR0FBRyxDQUFDLFNBQVMsQ0FBQyxJQUFJLEVBQUUsWUFBWSxFQUFFLEVBQUUsS0FBSyxFQUFFLE1BQU0sQ0FBQyxVQUFVLEVBQUUsQ0FBQyxDQUFDO1FBQ3BFLElBQUksR0FBRyxDQUFDLFNBQVMsQ0FBQyxJQUFJLEVBQUUsVUFBVSxFQUFFLEVBQUUsS0FBSyxFQUFFLEtBQUssQ0FBQyxRQUFRLEVBQUUsQ0FBQyxDQUFDO1FBQy9ELElBQUksR0FBRyxDQUFDLFNBQVMsQ0FBQyxJQUFJLEVBQUUsV0FBVyxFQUFFLEVBQUUsS0FBSyxFQUFFLEtBQUssQ0FBQyxTQUFTLEVBQUUsQ0FBQyxDQUFDO1FBQ2pFLElBQUksR0FBRyxDQUFDLFNBQVMsQ0FBQyxJQUFJLEVBQUUsZ0JBQWdCLEVBQUUsRUFBRSxLQUFLLEVBQUUsVUFBVSxDQUFDLFNBQVMsRUFBRSxDQUFDLENBQUM7UUFDM0UsSUFBSSxHQUFHLENBQUMsU0FBUyxDQUFDLElBQUksRUFBRSxxQkFBcUIsRUFBRSxFQUFFLEtBQUssRUFBRSxlQUFlLENBQUMsSUFBSSxJQUFJLDRCQUE0QixFQUFFLENBQUMsQ0FBQztRQUNoSCxJQUFJLEdBQUcsQ0FBQyxTQUFTLENBQUMsSUFBSSxFQUFFLE9BQU8sRUFBRSxFQUFFLEtBQUssRUFBRSxHQUFHLENBQUMsS0FBSyxFQUFFLENBQUMsQ0FBQztRQUN2RCxJQUFJLEdBQUcsQ0FBQyxTQUFTLENBQUMsSUFBSSxFQUFFLFFBQVEsRUFBRSxFQUFFLEtBQUssRUFBRSxHQUFHLENBQUMsZUFBZSxFQUFFLENBQUMsQ0FBQztRQUNsRSxJQUFJLEdBQUcsQ0FBQyxTQUFTLENBQUMsSUFBSSxFQUFFLGFBQWEsRUFBRSxFQUFFLEtBQUssRUFBRSxTQUFTLENBQUMsV0FBVyxFQUFFLENBQUMsQ0FBQztRQUN6RSxJQUFJLEdBQUcsQ0FBQyxTQUFTLENBQUMsSUFBSSxFQUFFLFdBQVcsRUFBRSxFQUFFLEtBQUssRUFBRSxPQUFPLENBQUMsU0FBUyxFQUFFLENBQUMsQ0FBQztRQUNuRSxJQUFJLEdBQUcsQ0FBQyxTQUFTLENBQUMsSUFBSSxFQUFFLFdBQVcsRUFBRSxFQUFFLEtBQUssRUFBRSxPQUFPLENBQUMsS0FBSyxFQUFFLENBQUMsQ0FBQztRQUMvRCxJQUFJLEdBQUcsQ0FBQyxTQUFTLENBQUMsSUFBSSxFQUFFLFdBQVcsRUFBRSxFQUFFLEtBQUssRUFBRSxPQUFPLENBQUMsU0FBUyxFQUFFLENBQUMsQ0FBQztJQUNyRSxDQUFDO0NBQ0Y7QUE1SUQsd0NBNElDIiwic291cmNlc0NvbnRlbnQiOlsiaW1wb3J0ICogYXMgY2RrIGZyb20gJ2F3cy1jZGstbGliJztcbmltcG9ydCAqIGFzIHMzIGZyb20gJ2F3cy1jZGstbGliL2F3cy1zMyc7XG5pbXBvcnQgKiBhcyBzcXMgZnJvbSAnYXdzLWNkay1saWIvYXdzLXNxcyc7XG5pbXBvcnQgKiBhcyBkeW5hbW9kYiBmcm9tICdhd3MtY2RrLWxpYi9hd3MtZHluYW1vZGInO1xuaW1wb3J0ICogYXMgc2VjcmV0c21hbmFnZXIgZnJvbSAnYXdzLWNkay1saWIvYXdzLXNlY3JldHNtYW5hZ2VyJztcbmltcG9ydCAqIGFzIGxhbWJkYSBmcm9tICdhd3MtY2RrLWxpYi9hd3MtbGFtYmRhJztcbmltcG9ydCAqIGFzIGVjMiBmcm9tICdhd3MtY2RrLWxpYi9hd3MtZWMyJztcbmltcG9ydCAqIGFzIGVsYnYyIGZyb20gJ2F3cy1jZGstbGliL2F3cy1lbGFzdGljbG9hZGJhbGFuY2luZ3YyJztcbmltcG9ydCAqIGFzIHNlcnZpY2VkaXNjb3ZlcnkgZnJvbSAnYXdzLWNkay1saWIvYXdzLXNlcnZpY2VkaXNjb3ZlcnknO1xuaW1wb3J0ICogYXMgYXBpZ2F0ZXdheSBmcm9tICdhd3MtY2RrLWxpYi9hd3MtYXBpZ2F0ZXdheSc7XG5pbXBvcnQgKiBhcyBhcGlnd3YyIGZyb20gJ2F3cy1jZGstbGliL2F3cy1hcGlnYXRld2F5djInO1xuaW1wb3J0ICogYXMgYXBpZ3d2MkludGVncmF0aW9ucyBmcm9tICdhd3MtY2RrLWxpYi9hd3MtYXBpZ2F0ZXdheXYyLWludGVncmF0aW9ucyc7XG5pbXBvcnQgKiBhcyBhY20gZnJvbSAnYXdzLWNkay1saWIvYXdzLWNlcnRpZmljYXRlbWFuYWdlcic7XG5pbXBvcnQgKiBhcyByb3V0ZTUzIGZyb20gJ2F3cy1jZGstbGliL2F3cy1yb3V0ZTUzJztcbmltcG9ydCAqIGFzIHJvdXRlNTNUYXJnZXRzIGZyb20gJ2F3cy1jZGstbGliL2F3cy1yb3V0ZTUzLXRhcmdldHMnO1xuaW1wb3J0ICogYXMgcGF0aCBmcm9tICdwYXRoJztcbmltcG9ydCB7IENvbnN0cnVjdCB9IGZyb20gJ2NvbnN0cnVjdHMnO1xuXG5leHBvcnQgY2xhc3MgRmxvY2lUZXN0U3RhY2sgZXh0ZW5kcyBjZGsuU3RhY2sge1xuICBjb25zdHJ1Y3RvcihzY29wZTogQ29uc3RydWN0LCBpZDogc3RyaW5nLCBwcm9wcz86IGNkay5TdGFja1Byb3BzKSB7XG4gICAgc3VwZXIoc2NvcGUsIGlkLCBwcm9wcyk7XG5cbiAgICBjb25zdCBidWNrZXQgPSBuZXcgczMuQnVja2V0KHRoaXMsICdUZXN0QnVja2V0Jywge1xuICAgICAgcmVtb3ZhbFBvbGljeTogY2RrLlJlbW92YWxQb2xpY3kuREVTVFJPWSxcbiAgICB9KTtcblxuICAgIGNvbnN0IHF1ZXVlID0gbmV3IHNxcy5RdWV1ZSh0aGlzLCAnVGVzdFF1ZXVlJywge1xuICAgICAgcXVldWVOYW1lOiAnZmxvY2ktY2RrLXRlc3QtcXVldWUnLFxuICAgICAgdmlzaWJpbGl0eVRpbWVvdXQ6IGNkay5EdXJhdGlvbi5zZWNvbmRzKDMwKSxcbiAgICB9KTtcblxuICAgIGNvbnN0IHRhYmxlID0gbmV3IGR5bmFtb2RiLlRhYmxlVjIodGhpcywgJ1Rlc3RUYWJsZScsIHtcbiAgICAgIHRhYmxlTmFtZTogJ2Zsb2NpLWNkay10ZXN0LXRhYmxlJyxcbiAgICAgIHBhcnRpdGlvbktleTogeyBuYW1lOiAncGsnLCB0eXBlOiBkeW5hbW9kYi5BdHRyaWJ1dGVUeXBlLlNUUklORyB9LFxuICAgICAgcmVtb3ZhbFBvbGljeTogY2RrLlJlbW92YWxQb2xpY3kuREVTVFJPWSxcbiAgICB9KTtcblxuICAgIC8vIER5bmFtb0RCIHRhYmxlIHdpdGggR1NJIGFuZCBMU0kg4oCUIHZhbGlkYXRlcyBDbG91ZEZvcm1hdGlvbiBpbmRleCBwcm92aXNpb25pbmdcbiAgICBjb25zdCBpbmRleFRhYmxlID0gbmV3IGR5bmFtb2RiLlRhYmxlVjIodGhpcywgJ0luZGV4VGVzdFRhYmxlJywge1xuICAgICAgdGFibGVOYW1lOiAnZmxvY2ktY2RrLWluZGV4LXRhYmxlJyxcbiAgICAgIHBhcnRpdGlvbktleTogeyBuYW1lOiAncGsnLCB0eXBlOiBkeW5hbW9kYi5BdHRyaWJ1dGVUeXBlLlNUUklORyB9LFxuICAgICAgc29ydEtleTogeyBuYW1lOiAnc2snLCB0eXBlOiBkeW5hbW9kYi5BdHRyaWJ1dGVUeXBlLlNUUklORyB9LFxuICAgICAgcmVtb3ZhbFBvbGljeTogY2RrLlJlbW92YWxQb2xpY3kuREVTVFJPWSxcbiAgICAgIGdsb2JhbFNlY29uZGFyeUluZGV4ZXM6IFtcbiAgICAgICAge1xuICAgICAgICAgIGluZGV4TmFtZTogJ2dzaS0xJyxcbiAgICAgICAgICBwYXJ0aXRpb25LZXk6IHsgbmFtZTogJ2dzaVBrJywgdHlwZTogZHluYW1vZGIuQXR0cmlidXRlVHlwZS5TVFJJTkcgfSxcbiAgICAgICAgICBzb3J0S2V5OiB7IG5hbWU6ICdzaycsIHR5cGU6IGR5bmFtb2RiLkF0dHJpYnV0ZVR5cGUuU1RSSU5HIH0sXG4gICAgICAgICAgcHJvamVjdGlvblR5cGU6IGR5bmFtb2RiLlByb2plY3Rpb25UeXBlLkFMTCxcbiAgICAgICAgfSxcbiAgICAgIF0sXG4gICAgICBsb2NhbFNlY29uZGFyeUluZGV4ZXM6IFtcbiAgICAgICAge1xuICAgICAgICAgIGluZGV4TmFtZTogJ2xzaS0xJyxcbiAgICAgICAgICBzb3J0S2V5OiB7IG5hbWU6ICdsc2lTaycsIHR5cGU6IGR5bmFtb2RiLkF0dHJpYnV0ZVR5cGUuU1RSSU5HIH0sXG4gICAgICAgICAgcHJvamVjdGlvblR5cGU6IGR5bmFtb2RiLlByb2plY3Rpb25UeXBlLktFWVNfT05MWSxcbiAgICAgICAgfSxcbiAgICAgIF0sXG4gICAgfSk7XG5cbiAgICBjb25zdCBnZW5lcmF0ZWRTZWNyZXQgPSBuZXcgc2VjcmV0c21hbmFnZXIuQ2ZuU2VjcmV0KHRoaXMsICdHZW5lcmF0ZWRTZWNyZXQnLCB7XG4gICAgICBuYW1lOiAnZmxvY2ktY2RrLWdlbmVyYXRlZC1zZWNyZXQnLFxuICAgICAgZ2VuZXJhdGVTZWNyZXRTdHJpbmc6IHtcbiAgICAgICAgc2VjcmV0U3RyaW5nVGVtcGxhdGU6ICd7XCJ1c2VybmFtZVwiOlwiYWRtaW5cIn0nLFxuICAgICAgICBnZW5lcmF0ZVN0cmluZ0tleTogJ3Bhc3N3b3JkJyxcbiAgICAgICAgcGFzc3dvcmRMZW5ndGg6IDI0LFxuICAgICAgICBleGNsdWRlQ2hhcmFjdGVyczogJ2FiYycsXG4gICAgICB9LFxuICAgIH0pO1xuICAgIGdlbmVyYXRlZFNlY3JldC5hcHBseVJlbW92YWxQb2xpY3koY2RrLlJlbW92YWxQb2xpY3kuREVTVFJPWSk7XG5cbiAgICAvLyBDdXN0b20gRG9ja2VyIGltYWdlIExhbWJkYSDigJQgZXhlcmNpc2VzIEVDUiBlbXVsYXRpb24gZW5kLXRvLWVuZDpcbiAgICAvLyBDREsgYnVpbGRzIHRoZSBsb2NhbCBEb2NrZXJmaWxlLCBjZGstYXNzZXRzIHB1c2hlcyBpdCB0byB0aGUgYm9vdHN0cmFwXG4gICAgLy8gRUNSIHJlcG9zaXRvcnkgKHZpYSBGbG9jaSdzIGVtdWxhdGVkIEVDUiArIHJlZ2lzdHJ5OjIpLCBhbmQgRmxvY2knc1xuICAgIC8vIExhbWJkYSBydW5uZXIgcHVsbHMgdGhlIGltYWdlIGF0IGludm9rZSB0aW1lLlxuICAgIG5ldyBsYW1iZGEuRG9ja2VySW1hZ2VGdW5jdGlvbih0aGlzLCAnRG9ja2VySGVsbG9GbicsIHtcbiAgICAgIGZ1bmN0aW9uTmFtZTogJ2Zsb2NpLWNkay1kb2NrZXItaGVsbG8nLFxuICAgICAgY29kZTogbGFtYmRhLkRvY2tlckltYWdlQ29kZS5mcm9tSW1hZ2VBc3NldChwYXRoLmpvaW4oX19kaXJuYW1lLCAnLi4nLCAnZG9ja2VyLWZuJykpLFxuICAgICAgdGltZW91dDogY2RrLkR1cmF0aW9uLnNlY29uZHMoMTUpLFxuICAgICAgbWVtb3J5U2l6ZTogMjU2LFxuICAgIH0pO1xuXG4gICAgLy8gVlBDICsgaW50ZXJuYWwgQUxCIOKAlCByZWdyZXNzaW9uIGZvciBpc3N1ZSAjMTI5NzogQ2xvdWRGb3JtYXRpb24gbXVzdCBjcmVhdGUgdGhlXG4gICAgLy8gRUMyIFZQQy9zdWJuZXRzIGZvciByZWFsIChpbiBFQzIpLCBvdGhlcndpc2UgdGhlIEFMQiB0aGF0IHJlZmVyZW5jZXMgdGhlXG4gICAgLy8gQ0RLLWdlbmVyYXRlZCBzdWJuZXRzIGZhaWxzIHdpdGggXCJUaGUgc3VibmV0IElEICcuLi4nIGRvZXMgbm90IGV4aXN0XCIuXG4gICAgY29uc3QgdnBjID0gbmV3IGVjMi5WcGModGhpcywgJ1Rlc3RWcGMnLCB7XG4gICAgICB2cGNOYW1lOiAnZmxvY2ktY2RrLXZwYycsXG4gICAgICBtYXhBenM6IDIsXG4gICAgICBuYXRHYXRld2F5czogMCxcbiAgICAgIHN1Ym5ldENvbmZpZ3VyYXRpb246IFtcbiAgICAgICAgeyBuYW1lOiAncHVibGljJywgc3VibmV0VHlwZTogZWMyLlN1Ym5ldFR5cGUuUFVCTElDLCBjaWRyTWFzazogMjQgfSxcbiAgICAgICAgeyBuYW1lOiAnaXNvbGF0ZWQnLCBzdWJuZXRUeXBlOiBlYzIuU3VibmV0VHlwZS5QUklWQVRFX0lTT0xBVEVELCBjaWRyTWFzazogMjQgfSxcbiAgICAgIF0sXG4gICAgfSk7XG5cbiAgICBjb25zdCBhbGIgPSBuZXcgZWxidjIuQXBwbGljYXRpb25Mb2FkQmFsYW5jZXIodGhpcywgJ1Rlc3RBbGInLCB7XG4gICAgICBsb2FkQmFsYW5jZXJOYW1lOiAnZmxvY2ktY2RrLWFsYicsXG4gICAgICB2cGMsXG4gICAgICBpbnRlcm5ldEZhY2luZzogZmFsc2UsXG4gICAgICB2cGNTdWJuZXRzOiB7IHN1Ym5ldFR5cGU6IGVjMi5TdWJuZXRUeXBlLlBSSVZBVEVfSVNPTEFURUQgfSxcbiAgICB9KTtcblxuICAgIC8vIENsb3VkIE1hcCBuYW1lc3BhY2UgKyBzZXJ2aWNlOiByZWdyZXNzaW9uIGZvciBodHRwczovL2dpdGh1Yi5jb20vZmxvY2ktaW8vZmxvY2kvaXNzdWVzLzQ1OTYuXG4gICAgY29uc3QgbmFtZXNwYWNlID0gbmV3IHNlcnZpY2VkaXNjb3ZlcnkuUHJpdmF0ZURuc05hbWVzcGFjZSh0aGlzLCAnVGVzdE5hbWVzcGFjZScsIHtcbiAgICAgIG5hbWU6ICdmbG9jaS1jZGsuaW50ZXJuYWwnLFxuICAgICAgdnBjLFxuICAgIH0pO1xuICAgIGNvbnN0IHNlcnZpY2UgPSBuYW1lc3BhY2UuY3JlYXRlU2VydmljZSgnVGVzdFNlcnZpY2UnLCB7XG4gICAgICBuYW1lOiAnZmxvY2ktY2RrLXN2YycsXG4gICAgICBkbnNSZWNvcmRUeXBlOiBzZXJ2aWNlZGlzY292ZXJ5LkRuc1JlY29yZFR5cGUuQSxcbiAgICAgIGRuc1R0bDogY2RrLkR1cmF0aW9uLnNlY29uZHMoMzApLFxuICAgIH0pO1xuXG4gICAgLy8gQVBJIEdhdGV3YXkgY3VzdG9tIGRvbWFpbnM6IGFuIEhUVFAgQVBJIG1hcHBlZCBhdCB0aGUgcm9vdCBvZiBhbiBBV1M6OkFwaUdhdGV3YXlWMjo6RG9tYWluTmFtZVxuICAgIC8vIHdpdGggYW4gYWxpYXMgcmVjb3JkIHRvIGl0LCBhbmQgYSBSRVNUIEFQSSB1bmRlciBhIGtleSB3aXRoIHNldmVyYWwgbGV2ZWxzLCB3aGljaCBDREsgbWFwc1xuICAgIC8vIHdpdGggQVdTOjpBcGlHYXRld2F5VjI6OkFwaU1hcHBpbmcgYmVjYXVzZSBhIGJhc2UgcGF0aCBtYXBwaW5nIHRha2VzIG9uZSBsZXZlbCBvbmx5LlxuICAgIGNvbnN0IGFwaVpvbmUgPSBuZXcgcm91dGU1My5QdWJsaWNIb3N0ZWRab25lKHRoaXMsICdBcGlab25lJywgeyB6b25lTmFtZTogJ2Zsb2NpLWNkay1hcGkuZXhhbXBsZS5jb20nIH0pO1xuICAgIGNvbnN0IGh0dHBBcGlEb21haW4gPSBuZXcgYXBpZ3d2Mi5Eb21haW5OYW1lKHRoaXMsICdIdHRwQXBpRG9tYWluJywge1xuICAgICAgZG9tYWluTmFtZTogJ2h0dHAuZmxvY2ktY2RrLWFwaS5leGFtcGxlLmNvbScsXG4gICAgICBjZXJ0aWZpY2F0ZTogbmV3IGFjbS5DZXJ0aWZpY2F0ZSh0aGlzLCAnSHR0cEFwaUNlcnRpZmljYXRlJywge1xuICAgICAgICBkb21haW5OYW1lOiAnaHR0cC5mbG9jaS1jZGstYXBpLmV4YW1wbGUuY29tJyxcbiAgICAgICAgdmFsaWRhdGlvbjogYWNtLkNlcnRpZmljYXRlVmFsaWRhdGlvbi5mcm9tRG5zKGFwaVpvbmUpLFxuICAgICAgfSksXG4gICAgfSk7XG4gICAgY29uc3QgaHR0cEFwaSA9IG5ldyBhcGlnd3YyLkh0dHBBcGkodGhpcywgJ1Rlc3RIdHRwQXBpJywge1xuICAgICAgZGVmYXVsdEludGVncmF0aW9uOiBuZXcgYXBpZ3d2MkludGVncmF0aW9ucy5IdHRwVXJsSW50ZWdyYXRpb24oJ0JhY2tlbmQnLCAnaHR0cHM6Ly9leGFtcGxlLmNvbScpLFxuICAgICAgZGVmYXVsdERvbWFpbk1hcHBpbmc6IHsgZG9tYWluTmFtZTogaHR0cEFwaURvbWFpbiB9LFxuICAgIH0pO1xuICAgIG5ldyByb3V0ZTUzLkFSZWNvcmQodGhpcywgJ0h0dHBBcGlBbGlhcycsIHtcbiAgICAgIHpvbmU6IGFwaVpvbmUsXG4gICAgICByZWNvcmROYW1lOiAnaHR0cC5mbG9jaS1jZGstYXBpLmV4YW1wbGUuY29tJyxcbiAgICAgIHRhcmdldDogcm91dGU1My5SZWNvcmRUYXJnZXQuZnJvbUFsaWFzKG5ldyByb3V0ZTUzVGFyZ2V0cy5BcGlHYXRld2F5djJEb21haW5Qcm9wZXJ0aWVzKFxuICAgICAgICBodHRwQXBpRG9tYWluLnJlZ2lvbmFsRG9tYWluTmFtZSwgaHR0cEFwaURvbWFpbi5yZWdpb25hbEhvc3RlZFpvbmVJZCkpLFxuICAgIH0pO1xuICAgIGNvbnN0IHJlc3RBcGkgPSBuZXcgYXBpZ2F0ZXdheS5SZXN0QXBpKHRoaXMsICdUZXN0UmVzdEFwaScpO1xuICAgIHJlc3RBcGkucm9vdC5hZGRSZXNvdXJjZSgnaXRlbXMnKS5hZGRNZXRob2QoJ0dFVCcsIG5ldyBhcGlnYXRld2F5Lkh0dHBJbnRlZ3JhdGlvbignaHR0cHM6Ly9leGFtcGxlLmNvbScpKTtcbiAgICBuZXcgYXBpZ2F0ZXdheS5Eb21haW5OYW1lKHRoaXMsICdSZXN0QXBpRG9tYWluJywge1xuICAgICAgZG9tYWluTmFtZTogJ3Jlc3QuZmxvY2ktY2RrLWFwaS5leGFtcGxlLmNvbScsXG4gICAgICBjZXJ0aWZpY2F0ZTogbmV3IGFjbS5DZXJ0aWZpY2F0ZSh0aGlzLCAnUmVzdEFwaUNlcnRpZmljYXRlJywge1xuICAgICAgICBkb21haW5OYW1lOiAncmVzdC5mbG9jaS1jZGstYXBpLmV4YW1wbGUuY29tJyxcbiAgICAgICAgdmFsaWRhdGlvbjogYWNtLkNlcnRpZmljYXRlVmFsaWRhdGlvbi5mcm9tRG5zKGFwaVpvbmUpLFxuICAgICAgfSksXG4gICAgICBtYXBwaW5nOiByZXN0QXBpLFxuICAgICAgYmFzZVBhdGg6ICdvcmRlcnMvdjEnLFxuICAgIH0pO1xuXG4gICAgbmV3IGNkay5DZm5PdXRwdXQodGhpcywgJ0J1Y2tldE5hbWUnLCB7IHZhbHVlOiBidWNrZXQuYnVja2V0TmFtZSB9KTtcbiAgICBuZXcgY2RrLkNmbk91dHB1dCh0aGlzLCAnUXVldWVVcmwnLCB7IHZhbHVlOiBxdWV1ZS5xdWV1ZVVybCB9KTtcbiAgICBuZXcgY2RrLkNmbk91dHB1dCh0aGlzLCAnVGFibGVOYW1lJywgeyB2YWx1ZTogdGFibGUudGFibGVOYW1lIH0pO1xuICAgIG5ldyBjZGsuQ2ZuT3V0cHV0KHRoaXMsICdJbmRleFRhYmxlTmFtZScsIHsgdmFsdWU6IGluZGV4VGFibGUudGFibGVOYW1lIH0pO1xuICAgIG5ldyBjZGsuQ2ZuT3V0cHV0KHRoaXMsICdHZW5lcmF0ZWRTZWNyZXROYW1lJywgeyB2YWx1ZTogZ2VuZXJhdGVkU2VjcmV0Lm5hbWUgfHwgJ2Zsb2NpLWNkay1nZW5lcmF0ZWQtc2VjcmV0JyB9KTtcbiAgICBuZXcgY2RrLkNmbk91dHB1dCh0aGlzLCAnVnBjSWQnLCB7IHZhbHVlOiB2cGMudnBjSWQgfSk7XG4gICAgbmV3IGNkay5DZm5PdXRwdXQodGhpcywgJ0FsYkFybicsIHsgdmFsdWU6IGFsYi5sb2FkQmFsYW5jZXJBcm4gfSk7XG4gICAgbmV3IGNkay5DZm5PdXRwdXQodGhpcywgJ05hbWVzcGFjZUlkJywgeyB2YWx1ZTogbmFtZXNwYWNlLm5hbWVzcGFjZUlkIH0pO1xuICAgIG5ldyBjZGsuQ2ZuT3V0cHV0KHRoaXMsICdTZXJ2aWNlSWQnLCB7IHZhbHVlOiBzZXJ2aWNlLnNlcnZpY2VJZCB9KTtcbiAgICBuZXcgY2RrLkNmbk91dHB1dCh0aGlzLCAnSHR0cEFwaUlkJywgeyB2YWx1ZTogaHR0cEFwaS5hcGlJZCB9KTtcbiAgICBuZXcgY2RrLkNmbk91dHB1dCh0aGlzLCAnUmVzdEFwaUlkJywgeyB2YWx1ZTogcmVzdEFwaS5yZXN0QXBpSWQgfSk7XG4gIH1cbn1cbiJdfQ==