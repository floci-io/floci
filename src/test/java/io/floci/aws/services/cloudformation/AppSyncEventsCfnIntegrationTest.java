package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.appsync.model.Api;
import software.amazon.awssdk.services.appsync.model.AuthMode;
import software.amazon.awssdk.services.appsync.model.AuthenticationType;
import software.amazon.awssdk.services.appsync.model.ChannelNamespace;
import software.amazon.awssdk.services.appsync.model.NotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deploys an AppSync Events API the way the CDK's {@code CfnApi} and {@code CfnChannelNamespace}
 * synthesize it: an API with IAM publishing and a Lambda authorizer for connect and subscribe in one
 * stack, exporting its id and ARN, and a second stack that imports them for a channel namespace and
 * for the {@code AWS::Lambda::Permission} that lets AppSync invoke the authorizer.
 *
 * <p>The outputs pin the exact {@code Ref} and {@code Fn::GetAtt} values: a type the registry does
 * not serve is stubbed as {@code CREATE_COMPLETE} with a made-up ARN, and an attribute a provisioner
 * leaves unset resolves to the literal {@code "LogicalId.Attr"}, so asserting the stack status alone
 * could not tell either apart from a working provisioner.
 */
@QuarkusTest
class AppSyncEventsCfnIntegrationTest {

    private static final String ACCOUNT = "741648700018";
    private static final String BASE_STACK = "appsync-events-cfn-base";
    private static final String NAMESPACE_STACK = "appsync-events-cfn-ns";

    private static final String BASE_TEMPLATE = """
        {
          "Resources": {
            "LiveEventsApi": {
              "Type": "AWS::AppSync::Api",
              "Properties": {
                "Name": "%s",
                "EventConfig": {
                  "AuthProviders": [
                    {"AuthType": "AWS_IAM"},
                    {"AuthType": "AWS_LAMBDA", "LambdaAuthorizerConfig": {
                      "AuthorizerUri": {"Fn::Sub": "arn:${AWS::Partition}:lambda:${AWS::Region}:${AWS::AccountId}:function:my-authorizer"},
                      "AuthorizerResultTtlInSeconds": 300}}
                  ],
                  "ConnectionAuthModes": [{"AuthType": "AWS_LAMBDA"}],
                  "DefaultPublishAuthModes": [{"AuthType": "AWS_IAM"}],
                  "DefaultSubscribeAuthModes": [{"AuthType": "%s"}],
                  "LogConfig": {"LogLevel": "ERROR",
                    "CloudWatchLogsRoleArn": {"Fn::Sub": "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/appsync-logs"}}
                },
                "Tags": [{"Key": "env", "Value": "local"}]
              }
            }
          },
          "Outputs": {
            "Ref": {"Value": {"Ref": "LiveEventsApi"}},
            "ApiId": {"Value": {"Fn::GetAtt": ["LiveEventsApi", "ApiId"]},
                      "Export": {"Name": "LiveEventsApiId"}},
            "ApiArn": {"Value": {"Fn::GetAtt": ["LiveEventsApi", "ApiArn"]},
                       "Export": {"Name": "LiveEventsApiArn"}},
            "DnsHttp": {"Value": {"Fn::GetAtt": ["LiveEventsApi", "Dns.Http"]}},
            "DnsRealtime": {"Value": {"Fn::GetAtt": ["LiveEventsApi", "Dns.Realtime"]}}
          }
        }
        """;

    /** {@code %s} is the namespace's publish auth mode; {@code %s} after it ends the Resources block. */
    private static final String NAMESPACE_TEMPLATE = """
        {
          "Resources": {
            "AuthorizerRole": {
              "Type": "AWS::IAM::Role",
              "Properties": {
                "AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [
                  {"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}
              }
            },
            "Authorizer": {
              "Type": "AWS::Lambda::Function",
              "Properties": {
                "FunctionName": "my-authorizer",
                "Runtime": "python3.12",
                "Handler": "index.handler",
                "Role": {"Fn::GetAtt": ["AuthorizerRole", "Arn"]},
                "Code": {"ZipFile": "def handler(e, c): return {'isAuthorized': True}"}
              }
            },
            "AuthorizerPermission": {
              "Type": "AWS::Lambda::Permission",
              "Properties": {
                "FunctionName": {"Ref": "Authorizer"},
                "Action": "lambda:InvokeFunction",
                "Principal": "appsync.amazonaws.com",
                "SourceArn": {"Fn::ImportValue": "LiveEventsApiArn"}
              }
            },
            "Namespace": {
              "Type": "AWS::AppSync::ChannelNamespace",
              "Properties": {
                "ApiId": {"Fn::ImportValue": "LiveEventsApiId"},
                "Name": "v7-6-0",
                "PublishAuthModes": [{"AuthType": "%s"}]
              }
            }%s
          },
          "Outputs": {
            "Ref": {"Value": {"Ref": "Namespace"}},
            "ChannelNamespaceArn": {"Value": {"Fn::GetAtt": ["Namespace", "ChannelNamespaceArn"]}}
          }
        }
        """;

    @Test
    void eventsApiAndImportedNamespaceDeployUpdateRollBackAndDelete() {
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, Region.US_EAST_1);
             AppSyncClient appSync = AwsTestClients.create(AppSyncClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            Stack base = CfnStackWaits.createStack(cfn, BASE_STACK,
                    BASE_TEMPLATE.formatted("live-events", "AWS_LAMBDA"), "CREATE_COMPLETE");
            String apiId = CfnStackWaits.output(base, "ApiId");
            String apiArn = "arn:aws:appsync:us-east-1:" + ACCOUNT + ":apis/" + apiId;
            assertEquals(apiArn, CfnStackWaits.output(base, "Ref"));
            assertEquals(apiArn, CfnStackWaits.output(base, "ApiArn"));
            assertEquals(apiId + ".appsync-api.us-east-1.amazonaws.com", CfnStackWaits.output(base, "DnsHttp"));
            assertEquals(apiId + ".appsync-realtime-api.us-east-1.amazonaws.com",
                    CfnStackWaits.output(base, "DnsRealtime"));
            Api api = appSync.getApi(request -> request.apiId(apiId)).api();
            assertEquals("live-events", api.name());
            assertEquals("arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:my-authorizer",
                    api.eventConfig().authProviders().get(1).lambdaAuthorizerConfig().authorizerUri());
            assertEquals(300, api.eventConfig().authProviders().get(1).lambdaAuthorizerConfig()
                    .authorizerResultTtlInSeconds());
            assertEquals(Map.of("env", "local"), api.tags());

            Stack namespaceStack = CfnStackWaits.createStack(cfn, NAMESPACE_STACK,
                    NAMESPACE_TEMPLATE.formatted("AWS_IAM", ""), "CREATE_COMPLETE");
            String namespaceArn = apiArn + "/channelNamespace/v7-6-0";
            assertEquals(namespaceArn, CfnStackWaits.output(namespaceStack, "Ref"));
            assertEquals(namespaceArn, CfnStackWaits.output(namespaceStack, "ChannelNamespaceArn"));
            ChannelNamespace ns = appSync.getChannelNamespace(request -> request.apiId(apiId).name("v7-6-0"))
                    .channelNamespace();
            assertEquals(List.of(mode(AuthenticationType.AWS_IAM)), ns.publishAuthModes());

            Stack renamed = CfnStackWaits.updateStack(cfn, BASE_STACK,
                    BASE_TEMPLATE.formatted("live-events-renamed", "AWS_IAM"), "UPDATE_COMPLETE");
            assertEquals(apiId, CfnStackWaits.output(renamed, "ApiId"), "an Events API update is in place");
            Api updated = appSync.getApi(request -> request.apiId(apiId)).api();
            assertEquals("live-events-renamed", updated.name());
            assertEquals(AuthenticationType.AWS_IAM, updated.eventConfig().defaultSubscribeAuthModes().get(0).authType());

            CfnStackWaits.updateStack(cfn, NAMESPACE_STACK,
                    NAMESPACE_TEMPLATE.formatted("AWS_LAMBDA", CfnStackWaits.failingResourceAfter("Namespace")),
                    "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(List.of(mode(AuthenticationType.AWS_IAM)),
                    appSync.getChannelNamespace(request -> request.apiId(apiId).name("v7-6-0"))
                            .channelNamespace().publishAuthModes(),
                    "the rolled-back update put the namespace's publish modes back");

            CfnStackWaits.deleteStack(cfn, NAMESPACE_STACK);
            assertThrows(NotFoundException.class,
                    () -> appSync.getChannelNamespace(request -> request.apiId(apiId).name("v7-6-0")));
            CfnStackWaits.deleteStack(cfn, BASE_STACK);
            assertThrows(NotFoundException.class, () -> appSync.getApi(request -> request.apiId(apiId)));
            assertTrue(appSync.listApis(request -> request.maxResults(25)).apis().stream()
                    .noneMatch(listed -> apiId.equals(listed.apiId())));
        }
    }

    private static AuthMode mode(AuthenticationType type) {
        return AuthMode.builder().authType(type).build();
    }
}
