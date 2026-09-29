package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * #2134: a resource with an explicit, create-only name must survive an identical UpdateStack
 * (reconciled in place, never re-created into "already exists") and a DeleteStack afterwards.
 * One stack per type, each with the smallest template the type accepts; parents a type needs
 * (a bucket, a key, a pool, an API) live in the same template. Docker-backed types are not here.
 * Waits go through {@link CfnStackWaits}: the operations run on an executor.
 */
@QuarkusTest
class CloudFormationNamedResourceIdenticalUpdateIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/probe-role";

    record Case(String label, String template) {
        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                single("Logs::LogGroup", "AWS::Logs::LogGroup", "\"LogGroupName\":\"/probe/%s\",\"RetentionInDays\":7"),
                single("EC2::SecurityGroup", "AWS::EC2::SecurityGroup",
                        "\"GroupName\":\"probe-sg-%s\",\"GroupDescription\":\"probe\""),
                single("CloudWatch::Alarm", "AWS::CloudWatch::Alarm",
                        "\"AlarmName\":\"probe-alarm-%s\",\"ComparisonOperator\":\"GreaterThanThreshold\","
                                + "\"EvaluationPeriods\":1,\"MetricName\":\"CPUUtilization\",\"Namespace\":\"AWS/EC2\","
                                + "\"Period\":60,\"Statistic\":\"Average\",\"Threshold\":90"),
                new Case("CloudTrail::Trail", """
                        {"Resources":{
                          "B":{"Type":"AWS::S3::Bucket","Properties":{"BucketName":"probe-trail-%s"}},
                          "T":{"Type":"AWS::CloudTrail::Trail","Properties":{"TrailName":"probe-trail-%s",
                               "S3BucketName":{"Ref":"B"},"IsLogging":false}}}}"""),
                single("CodeBuild::Project", "AWS::CodeBuild::Project",
                        "\"Name\":\"probe-build-%s\",\"ServiceRole\":\"" + ROLE_ARN + "\","
                                + "\"Artifacts\":{\"Type\":\"NO_ARTIFACTS\"},"
                                + "\"Environment\":{\"Type\":\"LINUX_CONTAINER\",\"ComputeType\":\"BUILD_GENERAL1_SMALL\","
                                + "\"Image\":\"aws/codebuild/standard:7.0\"},"
                                + "\"Source\":{\"Type\":\"NO_SOURCE\",\"BuildSpec\":\"version: 0.2\"}"),
                single("Config::ConfigRule", "AWS::Config::ConfigRule",
                        "\"ConfigRuleName\":\"probe-rule-%s\","
                                + "\"Source\":{\"Owner\":\"AWS\",\"SourceIdentifier\":\"S3_BUCKET_VERSIONING_ENABLED\"}"),
                single("ECR::Repository", "AWS::ECR::Repository", "\"RepositoryName\":\"probe-repo-%s\""),
                new Case("KinesisFirehose::DeliveryStream", """
                        {"Resources":{
                          "B":{"Type":"AWS::S3::Bucket","Properties":{"BucketName":"probe-firehose-%s"}},
                          "D":{"Type":"AWS::KinesisFirehose::DeliveryStream","Properties":{
                               "DeliveryStreamName":"probe-stream-%s","DeliveryStreamType":"DirectPut",
                               "S3DestinationConfiguration":{"BucketARN":{"Fn::GetAtt":["B","Arn"]},
                                   "RoleARN":"arn:aws:iam::000000000000:role/probe-role"}}}}}"""),
                single("IAM::ManagedPolicy", "AWS::IAM::ManagedPolicy",
                        "\"ManagedPolicyName\":\"probe-policy-%s\","
                                + "\"PolicyDocument\":{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                                + "\"Action\":\"s3:ListAllMyBuckets\",\"Resource\":\"*\"}]}"),
                single("IAM::Role", "AWS::IAM::Role",
                        "\"RoleName\":\"probe-role-%s\","
                                + "\"AssumeRolePolicyDocument\":{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                                + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}"),
                single("IAM::User", "AWS::IAM::User", "\"UserName\":\"probe-user-%s\""),
                single("IAM::Group", "AWS::IAM::Group", "\"GroupName\":\"probe-igroup-%s\""),
                new Case("KMS::Alias", """
                        {"Resources":{
                          "K":{"Type":"AWS::KMS::Key","Properties":{"Description":"probe"}},
                          "Al":{"Type":"AWS::KMS::Alias","Properties":{"AliasName":"alias/probe-%s","TargetKeyId":{"Ref":"K"}}}}}"""),
                new Case("Lambda::Function + Alias", """
                        {"Resources":{
                          "F":{"Type":"AWS::Lambda::Function","Properties":{"FunctionName":"probe-fn-%s",
                               "Runtime":"python3.12","Handler":"index.handler","Role":"arn:aws:iam::000000000000:role/probe-role",
                               "Code":{"ZipFile":"def handler(e, c): return 1"}}},
                          "Al":{"Type":"AWS::Lambda::Alias","Properties":{"Name":"live","FunctionName":{"Ref":"F"},"FunctionVersion":"$LATEST"}}}}"""),
                single("SecretsManager::Secret", "AWS::SecretsManager::Secret",
                        "\"Name\":\"probe-secret-%s\",\"SecretString\":\"x\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"a\"}]"),
                single("SSM::Parameter", "AWS::SSM::Parameter",
                        "\"Name\":\"/probe/param/%s\",\"Type\":\"String\",\"Value\":\"v\""),
                single("StepFunctions::StateMachine", "AWS::StepFunctions::StateMachine",
                        "\"StateMachineName\":\"probe-sm-%s\",\"RoleArn\":\"" + ROLE_ARN + "\","
                                + "\"DefinitionString\":\"{\\\"StartAt\\\":\\\"P\\\",\\\"States\\\":{\\\"P\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}}\""),
                single("WAFv2::WebACL", "AWS::WAFv2::WebACL",
                        "\"Name\":\"probe-acl-%s\",\"Scope\":\"REGIONAL\",\"DefaultAction\":{\"Allow\":{}},"
                                + "\"VisibilityConfig\":{\"SampledRequestsEnabled\":false,\"CloudWatchMetricsEnabled\":false,"
                                + "\"MetricName\":\"probe\"}"),
                single("DynamoDB::Table", "AWS::DynamoDB::Table",
                        "\"TableName\":\"probe-table-%s\",\"BillingMode\":\"PAY_PER_REQUEST\","
                                + "\"AttributeDefinitions\":[{\"AttributeName\":\"pk\",\"AttributeType\":\"S\"}],"
                                + "\"KeySchema\":[{\"AttributeName\":\"pk\",\"KeyType\":\"HASH\"}]"),
                single("Scheduler::ScheduleGroup", "AWS::Scheduler::ScheduleGroup", "\"Name\":\"probe-group-%s\""),
                single("EC2::LaunchTemplate", "AWS::EC2::LaunchTemplate",
                        "\"LaunchTemplateName\":\"probe-lt-%s\",\"LaunchTemplateData\":{\"ImageId\":\"ami-12345678\"}"),
                new Case("Cognito::UserPool + UserPoolGroup", """
                        {"Resources":{
                          "U":{"Type":"AWS::Cognito::UserPool","Properties":{"UserPoolName":"probe-pool-%s"}},
                          "Gp":{"Type":"AWS::Cognito::UserPoolGroup","Properties":{"GroupName":"probe-group-%s","UserPoolId":{"Ref":"U"}}}}}"""),
                single("Cognito::IdentityPool", "AWS::Cognito::IdentityPool",
                        "\"IdentityPoolName\":\"probe_ip_%s\",\"AllowUnauthenticatedIdentities\":false"),
                new Case("ApiGatewayV2::Api + Stage", """
                        {"Resources":{
                          "Api":{"Type":"AWS::ApiGatewayV2::Api","Properties":{"Name":"probe-api-%s","ProtocolType":"HTTP"}},
                          "St":{"Type":"AWS::ApiGatewayV2::Stage","Properties":{"ApiId":{"Ref":"Api"},"StageName":"probe","AutoDeploy":true}}}}"""),
                single("SQS::Queue", "AWS::SQS::Queue", "\"QueueName\":\"probe-queue-%s\""),
                single("SNS::Topic", "AWS::SNS::Topic", "\"TopicName\":\"probe-topic-%s\""),
                single("S3::Bucket", "AWS::S3::Bucket", "\"BucketName\":\"probe-bucket-%s\""),
                single("ECS::Cluster", "AWS::ECS::Cluster", "\"ClusterName\":\"probe-cluster-%s\""),
                new Case("Events::EventBus + Rule", """
                        {"Resources":{
                          "Bus":{"Type":"AWS::Events::EventBus","Properties":{"Name":"probe-bus-%s"}},
                          "Ru":{"Type":"AWS::Events::Rule","Properties":{"Name":"probe-rule-%s","EventBusName":{"Ref":"Bus"},
                               "EventPattern":{"source":["probe"]},"State":"ENABLED"}}}}"""),
                single("Kinesis::Stream", "AWS::Kinesis::Stream", "\"Name\":\"probe-kstream-%s\",\"ShardCount\":1"),
                new Case("Glue::Database", """
                        {"Resources":{"Db":{"Type":"AWS::Glue::Database","Properties":{"CatalogId":"000000000000",
                          "DatabaseInput":{"Name":"probe_db_%s"}}}}}"""),
                single("Route53::HostedZone", "AWS::Route53::HostedZone", "\"Name\":\"probe-%s.example.\""),
                single("ServiceDiscovery::HttpNamespace", "AWS::ServiceDiscovery::HttpNamespace", "\"Name\":\"probe-ns-%s\""),
                single("AppConfig::Application", "AWS::AppConfig::Application", "\"Name\":\"probe-app-%s\""),
                single("SES::ConfigurationSet", "AWS::SES::ConfigurationSet", "\"Name\":\"probe-cs-%s\""),
                single("Batch::JobDefinition", "AWS::Batch::JobDefinition",
                        "\"JobDefinitionName\":\"probe-jd-%s\",\"Type\":\"container\","
                                + "\"ContainerProperties\":{\"Image\":\"public.ecr.aws/example/job:latest\"}")
        );
    }

    private static Case single(String label, String type, String props) {
        return new Case(label, "{\"Resources\":{\"R\":{\"Type\":\"" + type + "\",\"Properties\":{" + props + "}}}}");
    }

    @ParameterizedTest
    @MethodSource("cases")
    void identicalUpdateIsANoOpAndDeleteCleansUp(Case c) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "probe-" + suffix;
        String template = c.template().replace("%s", suffix);

        Response create = cfn("CreateStack", stackName, template);
        if (create.statusCode() != 200) {
            fail("CREATE request rejected: " + create.asString());
        }
        expect(stackName, "CREATE_COMPLETE", "CREATE");

        Response update = cfn("UpdateStack", stackName, template);
        boolean noUpdates = update.statusCode() == 400 && update.asString().contains("No updates");
        if (!noUpdates) {
            expect(stackName, "UPDATE_COMPLETE", "UPDATE");
        }

        cfn("DeleteStack", stackName, null);
        CfnStackWaits.awaitStackDeleted(stackName);
    }

    private static void expect(String stackName, String wanted, String phase) {
        CfnStackWaits.StackState got = CfnStackWaits.awaitTerminal(stackName);
        if (!wanted.equals(got.status())) {
            fail(phase + " ended " + got.status() + " " + got.reason() + events(stackName));
        }
    }

    private static Response cfn(String action, String stackName, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName)
                .formParam("Capabilities.member.1", "CAPABILITY_NAMED_IAM");
        if (template != null) {
            request = request.formParam("TemplateBody", template);
        }
        return request.when().post("/");
    }

    private static String events(String stackName) {
        List<String> all = cfn("DescribeStackEvents", stackName, null).xmlPath()
                .getList("**.findAll { it.name() == 'ResourceStatusReason' }", String.class);
        List<String> reasons = new ArrayList<>();
        for (String reason : all) {
            if (reason != null && !reason.isBlank() && !reasons.contains(reason) && reasons.size() < 3) {
                reasons.add(reason);
            }
        }
        return reasons.isEmpty() ? "" : " | " + String.join(" | ", reasons);
    }
}
