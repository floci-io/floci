"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.FlociTestStack = void 0;
const cdk = require("aws-cdk-lib");
const s3 = require("aws-cdk-lib/aws-s3");
const sqs = require("aws-cdk-lib/aws-sqs");
const dynamodb = require("aws-cdk-lib/aws-dynamodb");
const secretsmanager = require("aws-cdk-lib/aws-secretsmanager");
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
        new cdk.CfnOutput(this, 'BucketName', { value: bucket.bucketName });
        new cdk.CfnOutput(this, 'QueueUrl', { value: queue.queueUrl });
        new cdk.CfnOutput(this, 'TableName', { value: table.tableName });
        new cdk.CfnOutput(this, 'IndexTableName', { value: indexTable.tableName });
        new cdk.CfnOutput(this, 'GeneratedSecretName', { value: generatedSecret.name || 'floci-cdk-generated-secret' });
    }
}
exports.FlociTestStack = FlociTestStack;
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiZmxvY2ktc3RhY2suanMiLCJzb3VyY2VSb290IjoiIiwic291cmNlcyI6WyJmbG9jaS1zdGFjay50cyJdLCJuYW1lcyI6W10sIm1hcHBpbmdzIjoiOzs7QUFBQSxtQ0FBbUM7QUFDbkMseUNBQXlDO0FBQ3pDLDJDQUEyQztBQUMzQyxxREFBcUQ7QUFDckQsaUVBQWlFO0FBR2pFLE1BQWEsY0FBZSxTQUFRLEdBQUcsQ0FBQyxLQUFLO0lBQzNDLFlBQVksS0FBZ0IsRUFBRSxFQUFVLEVBQUUsS0FBc0I7UUFDOUQsS0FBSyxDQUFDLEtBQUssRUFBRSxFQUFFLEVBQUUsS0FBSyxDQUFDLENBQUM7UUFFeEIsTUFBTSxNQUFNLEdBQUcsSUFBSSxFQUFFLENBQUMsTUFBTSxDQUFDLElBQUksRUFBRSxZQUFZLEVBQUU7WUFDL0MsYUFBYSxFQUFFLEdBQUcsQ0FBQyxhQUFhLENBQUMsT0FBTztTQUN6QyxDQUFDLENBQUM7UUFFSCxNQUFNLEtBQUssR0FBRyxJQUFJLEdBQUcsQ0FBQyxLQUFLLENBQUMsSUFBSSxFQUFFLFdBQVcsRUFBRTtZQUM3QyxTQUFTLEVBQUUsc0JBQXNCO1lBQ2pDLGlCQUFpQixFQUFFLEdBQUcsQ0FBQyxRQUFRLENBQUMsT0FBTyxDQUFDLEVBQUUsQ0FBQztTQUM1QyxDQUFDLENBQUM7UUFFSCxNQUFNLEtBQUssR0FBRyxJQUFJLFFBQVEsQ0FBQyxPQUFPLENBQUMsSUFBSSxFQUFFLFdBQVcsRUFBRTtZQUNwRCxTQUFTLEVBQUUsc0JBQXNCO1lBQ2pDLFlBQVksRUFBRSxFQUFFLElBQUksRUFBRSxJQUFJLEVBQUUsSUFBSSxFQUFFLFFBQVEsQ0FBQyxhQUFhLENBQUMsTUFBTSxFQUFFO1lBQ2pFLGFBQWEsRUFBRSxHQUFHLENBQUMsYUFBYSxDQUFDLE9BQU87U0FDekMsQ0FBQyxDQUFDO1FBRUgsZ0ZBQWdGO1FBQ2hGLE1BQU0sVUFBVSxHQUFHLElBQUksUUFBUSxDQUFDLE9BQU8sQ0FBQyxJQUFJLEVBQUUsZ0JBQWdCLEVBQUU7WUFDOUQsU0FBUyxFQUFFLHVCQUF1QjtZQUNsQyxZQUFZLEVBQUUsRUFBRSxJQUFJLEVBQUUsSUFBSSxFQUFFLElBQUksRUFBRSxRQUFRLENBQUMsYUFBYSxDQUFDLE1BQU0sRUFBRTtZQUNqRSxPQUFPLEVBQUUsRUFBRSxJQUFJLEVBQUUsSUFBSSxFQUFFLElBQUksRUFBRSxRQUFRLENBQUMsYUFBYSxDQUFDLE1BQU0sRUFBRTtZQUM1RCxhQUFhLEVBQUUsR0FBRyxDQUFDLGFBQWEsQ0FBQyxPQUFPO1lBQ3hDLHNCQUFzQixFQUFFO2dCQUN0QjtvQkFDRSxTQUFTLEVBQUUsT0FBTztvQkFDbEIsWUFBWSxFQUFFLEVBQUUsSUFBSSxFQUFFLE9BQU8sRUFBRSxJQUFJLEVBQUUsUUFBUSxDQUFDLGFBQWEsQ0FBQyxNQUFNLEVBQUU7b0JBQ3BFLE9BQU8sRUFBRSxFQUFFLElBQUksRUFBRSxJQUFJLEVBQUUsSUFBSSxFQUFFLFFBQVEsQ0FBQyxhQUFhLENBQUMsTUFBTSxFQUFFO29CQUM1RCxjQUFjLEVBQUUsUUFBUSxDQUFDLGNBQWMsQ0FBQyxHQUFHO2lCQUM1QzthQUNGO1lBQ0QscUJBQXFCLEVBQUU7Z0JBQ3JCO29CQUNFLFNBQVMsRUFBRSxPQUFPO29CQUNsQixPQUFPLEVBQUUsRUFBRSxJQUFJLEVBQUUsT0FBTyxFQUFFLElBQUksRUFBRSxRQUFRLENBQUMsYUFBYSxDQUFDLE1BQU0sRUFBRTtvQkFDL0QsY0FBYyxFQUFFLFFBQVEsQ0FBQyxjQUFjLENBQUMsU0FBUztpQkFDbEQ7YUFDRjtTQUNGLENBQUMsQ0FBQztRQUVILE1BQU0sZUFBZSxHQUFHLElBQUksY0FBYyxDQUFDLFNBQVMsQ0FBQyxJQUFJLEVBQUUsaUJBQWlCLEVBQUU7WUFDNUUsSUFBSSxFQUFFLDRCQUE0QjtZQUNsQyxvQkFBb0IsRUFBRTtnQkFDcEIsb0JBQW9CLEVBQUUsc0JBQXNCO2dCQUM1QyxpQkFBaUIsRUFBRSxVQUFVO2dCQUM3QixjQUFjLEVBQUUsRUFBRTtnQkFDbEIsaUJBQWlCLEVBQUUsS0FBSzthQUN6QjtTQUNGLENBQUMsQ0FBQztRQUNILGVBQWUsQ0FBQyxrQkFBa0IsQ0FBQyxHQUFHLENBQUMsYUFBYSxDQUFDLE9BQU8sQ0FBQyxDQUFDO1FBRTlELElBQUksR0FBRyxDQUFDLFNBQVMsQ0FBQyxJQUFJLEVBQUUsWUFBWSxFQUFFLEVBQUUsS0FBSyxFQUFFLE1BQU0sQ0FBQyxVQUFVLEVBQUUsQ0FBQyxDQUFDO1FBQ3BFLElBQUksR0FBRyxDQUFDLFNBQVMsQ0FBQyxJQUFJLEVBQUUsVUFBVSxFQUFFLEVBQUUsS0FBSyxFQUFFLEtBQUssQ0FBQyxRQUFRLEVBQUUsQ0FBQyxDQUFDO1FBQy9ELElBQUksR0FBRyxDQUFDLFNBQVMsQ0FBQyxJQUFJLEVBQUUsV0FBVyxFQUFFLEVBQUUsS0FBSyxFQUFFLEtBQUssQ0FBQyxTQUFTLEVBQUUsQ0FBQyxDQUFDO1FBQ2pFLElBQUksR0FBRyxDQUFDLFNBQVMsQ0FBQyxJQUFJLEVBQUUsZ0JBQWdCLEVBQUUsRUFBRSxLQUFLLEVBQUUsVUFBVSxDQUFDLFNBQVMsRUFBRSxDQUFDLENBQUM7UUFDM0UsSUFBSSxHQUFHLENBQUMsU0FBUyxDQUFDLElBQUksRUFBRSxxQkFBcUIsRUFBRSxFQUFFLEtBQUssRUFBRSxlQUFlLENBQUMsSUFBSSxJQUFJLDRCQUE0QixFQUFFLENBQUMsQ0FBQztJQUNsSCxDQUFDO0NBQ0Y7QUEzREQsd0NBMkRDIiwic291cmNlc0NvbnRlbnQiOlsiaW1wb3J0ICogYXMgY2RrIGZyb20gJ2F3cy1jZGstbGliJztcbmltcG9ydCAqIGFzIHMzIGZyb20gJ2F3cy1jZGstbGliL2F3cy1zMyc7XG5pbXBvcnQgKiBhcyBzcXMgZnJvbSAnYXdzLWNkay1saWIvYXdzLXNxcyc7XG5pbXBvcnQgKiBhcyBkeW5hbW9kYiBmcm9tICdhd3MtY2RrLWxpYi9hd3MtZHluYW1vZGInO1xuaW1wb3J0ICogYXMgc2VjcmV0c21hbmFnZXIgZnJvbSAnYXdzLWNkay1saWIvYXdzLXNlY3JldHNtYW5hZ2VyJztcbmltcG9ydCB7IENvbnN0cnVjdCB9IGZyb20gJ2NvbnN0cnVjdHMnO1xuXG5leHBvcnQgY2xhc3MgRmxvY2lUZXN0U3RhY2sgZXh0ZW5kcyBjZGsuU3RhY2sge1xuICBjb25zdHJ1Y3RvcihzY29wZTogQ29uc3RydWN0LCBpZDogc3RyaW5nLCBwcm9wcz86IGNkay5TdGFja1Byb3BzKSB7XG4gICAgc3VwZXIoc2NvcGUsIGlkLCBwcm9wcyk7XG5cbiAgICBjb25zdCBidWNrZXQgPSBuZXcgczMuQnVja2V0KHRoaXMsICdUZXN0QnVja2V0Jywge1xuICAgICAgcmVtb3ZhbFBvbGljeTogY2RrLlJlbW92YWxQb2xpY3kuREVTVFJPWSxcbiAgICB9KTtcblxuICAgIGNvbnN0IHF1ZXVlID0gbmV3IHNxcy5RdWV1ZSh0aGlzLCAnVGVzdFF1ZXVlJywge1xuICAgICAgcXVldWVOYW1lOiAnZmxvY2ktY2RrLXRlc3QtcXVldWUnLFxuICAgICAgdmlzaWJpbGl0eVRpbWVvdXQ6IGNkay5EdXJhdGlvbi5zZWNvbmRzKDMwKSxcbiAgICB9KTtcblxuICAgIGNvbnN0IHRhYmxlID0gbmV3IGR5bmFtb2RiLlRhYmxlVjIodGhpcywgJ1Rlc3RUYWJsZScsIHtcbiAgICAgIHRhYmxlTmFtZTogJ2Zsb2NpLWNkay10ZXN0LXRhYmxlJyxcbiAgICAgIHBhcnRpdGlvbktleTogeyBuYW1lOiAncGsnLCB0eXBlOiBkeW5hbW9kYi5BdHRyaWJ1dGVUeXBlLlNUUklORyB9LFxuICAgICAgcmVtb3ZhbFBvbGljeTogY2RrLlJlbW92YWxQb2xpY3kuREVTVFJPWSxcbiAgICB9KTtcblxuICAgIC8vIER5bmFtb0RCIHRhYmxlIHdpdGggR1NJIGFuZCBMU0kg4oCUIHZhbGlkYXRlcyBDbG91ZEZvcm1hdGlvbiBpbmRleCBwcm92aXNpb25pbmdcbiAgICBjb25zdCBpbmRleFRhYmxlID0gbmV3IGR5bmFtb2RiLlRhYmxlVjIodGhpcywgJ0luZGV4VGVzdFRhYmxlJywge1xuICAgICAgdGFibGVOYW1lOiAnZmxvY2ktY2RrLWluZGV4LXRhYmxlJyxcbiAgICAgIHBhcnRpdGlvbktleTogeyBuYW1lOiAncGsnLCB0eXBlOiBkeW5hbW9kYi5BdHRyaWJ1dGVUeXBlLlNUUklORyB9LFxuICAgICAgc29ydEtleTogeyBuYW1lOiAnc2snLCB0eXBlOiBkeW5hbW9kYi5BdHRyaWJ1dGVUeXBlLlNUUklORyB9LFxuICAgICAgcmVtb3ZhbFBvbGljeTogY2RrLlJlbW92YWxQb2xpY3kuREVTVFJPWSxcbiAgICAgIGdsb2JhbFNlY29uZGFyeUluZGV4ZXM6IFtcbiAgICAgICAge1xuICAgICAgICAgIGluZGV4TmFtZTogJ2dzaS0xJyxcbiAgICAgICAgICBwYXJ0aXRpb25LZXk6IHsgbmFtZTogJ2dzaVBrJywgdHlwZTogZHluYW1vZGIuQXR0cmlidXRlVHlwZS5TVFJJTkcgfSxcbiAgICAgICAgICBzb3J0S2V5OiB7IG5hbWU6ICdzaycsIHR5cGU6IGR5bmFtb2RiLkF0dHJpYnV0ZVR5cGUuU1RSSU5HIH0sXG4gICAgICAgICAgcHJvamVjdGlvblR5cGU6IGR5bmFtb2RiLlByb2plY3Rpb25UeXBlLkFMTCxcbiAgICAgICAgfSxcbiAgICAgIF0sXG4gICAgICBsb2NhbFNlY29uZGFyeUluZGV4ZXM6IFtcbiAgICAgICAge1xuICAgICAgICAgIGluZGV4TmFtZTogJ2xzaS0xJyxcbiAgICAgICAgICBzb3J0S2V5OiB7IG5hbWU6ICdsc2lTaycsIHR5cGU6IGR5bmFtb2RiLkF0dHJpYnV0ZVR5cGUuU1RSSU5HIH0sXG4gICAgICAgICAgcHJvamVjdGlvblR5cGU6IGR5bmFtb2RiLlByb2plY3Rpb25UeXBlLktFWVNfT05MWSxcbiAgICAgICAgfSxcbiAgICAgIF0sXG4gICAgfSk7XG5cbiAgICBjb25zdCBnZW5lcmF0ZWRTZWNyZXQgPSBuZXcgc2VjcmV0c21hbmFnZXIuQ2ZuU2VjcmV0KHRoaXMsICdHZW5lcmF0ZWRTZWNyZXQnLCB7XG4gICAgICBuYW1lOiAnZmxvY2ktY2RrLWdlbmVyYXRlZC1zZWNyZXQnLFxuICAgICAgZ2VuZXJhdGVTZWNyZXRTdHJpbmc6IHtcbiAgICAgICAgc2VjcmV0U3RyaW5nVGVtcGxhdGU6ICd7XCJ1c2VybmFtZVwiOlwiYWRtaW5cIn0nLFxuICAgICAgICBnZW5lcmF0ZVN0cmluZ0tleTogJ3Bhc3N3b3JkJyxcbiAgICAgICAgcGFzc3dvcmRMZW5ndGg6IDI0LFxuICAgICAgICBleGNsdWRlQ2hhcmFjdGVyczogJ2FiYycsXG4gICAgICB9LFxuICAgIH0pO1xuICAgIGdlbmVyYXRlZFNlY3JldC5hcHBseVJlbW92YWxQb2xpY3koY2RrLlJlbW92YWxQb2xpY3kuREVTVFJPWSk7XG5cbiAgICBuZXcgY2RrLkNmbk91dHB1dCh0aGlzLCAnQnVja2V0TmFtZScsIHsgdmFsdWU6IGJ1Y2tldC5idWNrZXROYW1lIH0pO1xuICAgIG5ldyBjZGsuQ2ZuT3V0cHV0KHRoaXMsICdRdWV1ZVVybCcsIHsgdmFsdWU6IHF1ZXVlLnF1ZXVlVXJsIH0pO1xuICAgIG5ldyBjZGsuQ2ZuT3V0cHV0KHRoaXMsICdUYWJsZU5hbWUnLCB7IHZhbHVlOiB0YWJsZS50YWJsZU5hbWUgfSk7XG4gICAgbmV3IGNkay5DZm5PdXRwdXQodGhpcywgJ0luZGV4VGFibGVOYW1lJywgeyB2YWx1ZTogaW5kZXhUYWJsZS50YWJsZU5hbWUgfSk7XG4gICAgbmV3IGNkay5DZm5PdXRwdXQodGhpcywgJ0dlbmVyYXRlZFNlY3JldE5hbWUnLCB7IHZhbHVlOiBnZW5lcmF0ZWRTZWNyZXQubmFtZSB8fCAnZmxvY2ktY2RrLWdlbmVyYXRlZC1zZWNyZXQnIH0pO1xuICB9XG59XG4iXX0=