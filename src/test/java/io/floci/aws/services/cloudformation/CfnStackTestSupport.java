package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;
import software.amazon.awssdk.services.cloudformation.model.Parameter;

import java.util.List;

/**
 * What the replacement integration tests share: a resource's physical id after a stack operation,
 * what a change set says about replacing one resource, and the templates they build.
 */
final class CfnStackTestSupport {

    static final String PROFILE_TYPE = "AWS::AppConfig::ConfigurationProfile";

    private CfnStackTestSupport() {
    }

    static String physicalId(CloudFormationClient client, String stack, String logicalId) {
        return client.describeStackResource(request -> request.stackName(stack).logicalResourceId(logicalId))
                .stackResourceDetail().physicalResourceId();
    }

    /** The {@code Replacement} value of every change the change set lists for {@code logicalId}. */
    static List<String> replacement(CloudFormationClient client, String stack, String changeSet,
                                    String logicalId) {
        return client.describeChangeSet(request -> request.stackName(stack).changeSetName(changeSet)).changes()
                .stream().filter(change -> logicalId.equals(change.resourceChange().logicalResourceId()))
                .map(change -> change.resourceChange().replacementAsString()).toList();
    }

    /** Creates an UPDATE change set named {@code changeSet} for {@code template}. */
    static void createUpdateChangeSet(CloudFormationClient client, String stack, String changeSet,
                                      String template, Parameter... parameters) {
        client.createChangeSet(request -> request.stackName(stack).changeSetName(changeSet)
                .changeSetType(ChangeSetType.UPDATE).templateBody(template).parameters(parameters));
    }

    /** Executes {@code changeSet} and waits for the stack to finish updating. */
    static void executeAndAwaitUpdate(CloudFormationClient client, String stack, String changeSet) {
        client.executeChangeSet(request -> request.stackName(stack).changeSetName(changeSet));
        CfnStackWaits.awaitStatus(client, stack, "UPDATE_COMPLETE");
    }

    /** Turns a template written with single quotes into JSON. */
    static String json(String template) {
        return template.replace('\'', '"');
    }

    /** The resource {@code App}: an AppConfig application named {@code name}. */
    static String appConfigApplication(String name) {
        return "\"App\": {\"Type\": \"AWS::AppConfig::Application\", \"Properties\": {\"Name\": \"" + name + "\"}}";
    }

    /**
     * The properties of an AppConfig profile in {@code applicationId} (a JSON value) named settings,
     * followed by {@code rest}.
     */
    static String profileProperties(String applicationId, String rest) {
        return "\"ApplicationId\": " + applicationId + ", \"Name\": \"settings\", " + rest;
    }

    /** A template whose resource {@code Subject} is the one under test, after {@code extraResources}. */
    static String subjectStack(String extraResources, String parameters, String type, String properties) {
        String extra = extraResources.isEmpty() ? "" : extraResources + ",";
        String params = parameters.isEmpty() ? "" : "\"Parameters\": {" + parameters + "}, ";
        return "{" + params + "\"Resources\": {" + extra + "\"Subject\": {\"Type\": \"" + type
                + "\", \"Properties\": {" + properties + "}}}}";
    }

    /** An AppConfig profile {@code Subject} of the application {@code appName}; {@code rest} follows the profile's name. */
    static String profileStack(String appName, String applicationId, String rest) {
        return subjectStack(appConfigApplication(appName), "", PROFILE_TYPE, profileProperties(applicationId, rest));
    }

    /** A VPC and one subnet in {@code cidrPrefix}.0.0/16, as resources followed by a comma. */
    static String vpcAndSubnet(String cidrPrefix) {
        return "'Vpc': {'Type': 'AWS::EC2::VPC', 'Properties': {'CidrBlock': '" + cidrPrefix + ".0.0/16'}},"
                + "'Subnet': {'Type': 'AWS::EC2::Subnet', 'Properties': {'VpcId': {'Ref': 'Vpc'},"
                + " 'CidrBlock': '" + cidrPrefix + ".0.0/24'}},";
    }

    /** An ElastiCache subnet group in {@code Subnet}, as a resource followed by a comma. */
    static String subnetGroup(String logicalId, String name) {
        return "'" + logicalId + "': {'Type': 'AWS::ElastiCache::SubnetGroup', 'Properties': {"
                + "'CacheSubnetGroupName': '" + name + "', 'Description': 'example',"
                + " 'SubnetIds': [{'Ref': 'Subnet'}]}},";
    }
}
