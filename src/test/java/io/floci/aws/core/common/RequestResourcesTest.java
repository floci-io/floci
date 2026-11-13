package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How {@link RequestResources} builds the ARNs of the resources a request names. */
class RequestResourcesTest {

    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private static final String MACHINE = "arn:aws:states:us-east-1:000000000000:stateMachine:orders";

    @Test
    void inferredLandingZoneIdentifiersKeepRawContextAcrossOperations() {
        String arn = "arn:aws:controltower:::landingzone/missing";
        for (String operation : List.of("GetLandingZone", "UpdateLandingZone", "ResetLandingZone", "DeleteLandingZone")) {
            assertEquals(List.of(arn), of("controltower:" + operation,
                    json("{\"landingZoneIdentifier\":\"" + arn + "\"}")));
        }
    }

    @Test
    void controlTowerIdentifiersKeepFullArnsAndLandingZoneIds() {
        String prefix = "arn:aws:controltower:us-east-1:000000000000:";
        for (String operation : List.of("GetLandingZone", "UpdateLandingZone", "ResetLandingZone", "DeleteLandingZone")) {
            for (String identifier : List.of("zone-one", prefix + "landingzone/zone-one")) {
                assertEquals(List.of(prefix + "landingzone/zone-one"), of("controltower:" + operation,
                        json("{\"landingZoneIdentifier\":\"" + identifier + "\"}")));
            }
        }
        for (String kind : List.of("Baseline", "Control")) {
            String member = "enabled" + kind + "Identifier";
            String arn = prefix + "enabled" + kind.toLowerCase() + "/one";
            for (String verb : List.of("Get", "Update", "Reset")) {
                assertEquals(List.of(arn), of("controltower:" + verb + "Enabled" + kind,
                        json("{\"" + member + "\":\"" + arn + "\"}")));
            }
        }
        assertEquals(List.of(prefix + "enabledcontrol/*"), of("controltower:EnableControl",
                json("{\"controlIdentifier\":\"arn:aws:controlcatalog:::control/catalog-one\"}")));
        assertTrue(RequestResources.actsOnNoResource("controltower:ListLandingZones"));
        assertTrue(RequestResources.actsOnNoResource("controltower:CreateLandingZone"));
        assertEquals(List.of("arn:aws-us-gov:controltower:us-gov-west-1:111122223333:landingzone/zone-one"),
                RequestResources.of("controltower:GetLandingZone", json("{\"landingZoneIdentifier\":\"zone-one\"}"),
                        "aws-us-gov", "us-gov-west-1", "111122223333"));
    }

    @Test
    void inferredControlArnsIgnoreOtherResourceTypesAndKeepTheCreatedWildcard() {
        String enabled = "arn:aws:controltower:us-east-1:000000000000:enabledcontrol/one";
        String references = "\"controlIdentifier\":\"arn:aws:controlcatalog:::control/catalog-one\","
                + "\"targetIdentifier\":\"arn:aws:organizations::111122223333:ou/o-one/ou-one\"";
        assertEquals(List.of(enabled), of("controltower:DisableControl",
                json("{" + references + ",\"enabledControlIdentifier\":\"" + enabled + "\"}")));
        assertEquals(List.of("arn:aws:controltower:us-east-1:000000000000:enabledcontrol/*"),
                of("controltower:EnableControl", json("{" + references + "}")));
    }

    @Test
    void sharedBareTagsReadTheirActualBodyOrQueryWithoutChangingArnPathPrecedence() {
        String arn = "arn:aws:controltower:us-east-1:000000000000:landingzone/one";
        String spoof = arn + "other";
        for (String method : List.of("POST", "GET", "DELETE")) {
            String operation = "controltower:" + switch (method) {
                case "POST" -> "TagResource";
                case "GET" -> "ListTagsForResource";
                default -> "UntagResource";
            };
            ContainerRequestContext bare = rest(method, "{\"resourceArn\":\"" + (method.equals("POST") ? arn : spoof)
                    + "\"}", Map.of("resourceArn", arn));
            when(bare.getUriInfo().getRequestUri()).thenReturn(URI.create("http://localhost/tags///"));
            when(bare.getUriInfo().getBaseUri()).thenReturn(URI.create("http://localhost/"));
            assertEquals(List.of(arn), of(operation, bare));
            ContainerRequestContext path = rest(method, "{\"resourceArn\":\"" + spoof + "\"}", Map.of("resourceArn", spoof));
            when(path.getUriInfo().getRequestUri()).thenReturn(URI.create("http://localhost/tags/" + arn));
            when(path.getUriInfo().getBaseUri()).thenReturn(URI.create("http://localhost/"));
            assertEquals(List.of(arn), RequestResources.of(operation, path, "aws", "us-east-1", "000000000000",
                    null, Map.of("resourceArn", arn)));
            assertEquals(List.of(), of(operation, path));
        }
        ContainerRequestContext absentQuery = rest("GET", "{\"resourceArn\":\"" + arn + "\"}", Map.of());
        when(absentQuery.getUriInfo().getRequestUri()).thenReturn(URI.create("http://localhost/tags"));
        when(absentQuery.getUriInfo().getBaseUri()).thenReturn(URI.create("http://localhost/"));
        assertEquals(List.of(), of("controltower:ListTagsForResource", absentQuery));
    }

    @Test
    void sharedSignerBareTagsUseTheSameLocatedMemberContract() {
        String arn = "arn:aws:signer:us-east-1:000000000000:/signing-profiles/profile";
        ContainerRequestContext request = rest("POST", "{\"resourceArn\":\"" + arn + "\"}", Map.of());
        when(request.getUriInfo().getRequestUri()).thenReturn(URI.create("http://localhost/tags"));
        when(request.getUriInfo().getBaseUri()).thenReturn(URI.create("http://localhost/"));
        assertEquals(List.of(arn), of("signer:TagResource", request));
    }

    @Test
    void generatedIdTargetsReadModelListsAndSerializedQueryNamesInOrder() throws Exception {
        String instance = "arn:aws:ec2:us-east-1:000000000000:instance/i-one";
        for (String operation : List.of("ec2:CreateTags", "ec2:DeleteTags")) {
            assertTrue(RequestResources.hasIndividualTargets(operation));
            assertFalse(RequestResources.actsOnNoResource(operation));
            assertEquals(List.of(instance, "*", instance), of(operation,
                    json("{\"Resources\":[\"i-one\",\"vpc-one\",\"i-one\"]}")));
            assertEquals(List.of(instance, "*", instance), RequestResources.ofBody(operation,
                    new ObjectMapper().readTree("{\"Resources\":[\"i-one\",\"vpc-one\",\"i-one\"]}"),
                    "aws", "us-east-1", "000000000000", null));
            assertEquals(List.of(), of(operation, json("{\"Resources\":\"i-one\"}")));
        }
        assertFalse(RequestResources.hasIndividualTargets("ec2:DescribeInstances"));
        assertEquals(List.of(), RequestResources.ofBody("ec2:TerminateInstances",
                new ObjectMapper().readTree("{\"InstanceIds\":[\"i-one\"]}"), "aws", "us-east-1",
                "000000000000", null));
    }

    @Test
    void listTablesCorrectionReadsTheCollectionAndLeavesOtherDynamoOperationsHandBuilt() {
        assertTrue(RequestResources.reads("dynamodb:ListTables"));
        assertFalse(RequestResources.reads("dynamodb:BatchWriteItem"));
        assertFalse(RequestResources.reads("dynamodb:ExecuteStatement"));
        assertFalse(RequestResources.reads(null));
        for (String body : List.of("{}", "{\"TableName\":\"ignored\",\"ExclusiveStartTableName\":\"cursor\"}")) {
            assertEquals(List.of("arn:aws-us-gov:dynamodb:us-gov-west-1:111122223333:table/*"),
                    RequestResources.of("dynamodb:ListTables", json(body), "aws-us-gov", "us-gov-west-1",
                            "111122223333"));
        }
    }

    @Test
    void aMemberHoldingAnArnOfATypeTheActionActsOnNamesThatResource() {
        assertEquals(List.of(MACHINE), of("states:DescribeStateMachine",
                json("{\"stateMachineArn\":\"" + MACHINE + "\"}")));
    }

    /** {@code CreateStateMachine}'s {@code roleArn} is an IAM role, no resource of Step Functions. */
    @Test
    void anArnOfAnotherTypeIsNotChecked() {
        assertEquals(List.of(MACHINE), of("states:CreateStateMachine", json(
                "{\"name\":\"orders\",\"roleArn\":\"arn:aws:iam::000000000000:role/sfn\",\"definition\":\"{}\"}")));
    }

    /**
     * A handler may act on the resource the ARN names or on one of that name in the caller's account or the
     * request's Region, or both (ECR's tag handlers keep the ARN's account in the request's Region), so each is
     * checked.
     */
    @Test
    void anArnOfAnotherAccountOrRegionIsCheckedAsGivenAndAsTheCallersOwn() {
        String other = "arn:aws:states:eu-west-1:111122223333:stateMachine:orders";
        assertEquals(List.of(other, "arn:aws:states:us-east-1:111122223333:stateMachine:orders",
                "arn:aws:states:eu-west-1:000000000000:stateMachine:orders", MACHINE), of("states:DescribeStateMachine",
                json("{\"stateMachineArn\":\"" + other + "\"}")));
        assertEquals(List.of("arn:aws:ecr:eu-west-1:111122223333:repository/web",
                "arn:aws:ecr:us-east-1:111122223333:repository/web", "arn:aws:ecr:eu-west-1:000000000000:repository/web",
                "arn:aws:ecr:us-east-1:000000000000:repository/web"), of("ecr:TagResource",
                json("{\"resourceArn\":\"arn:aws:ecr:eu-west-1:111122223333:repository/web\",\"tags\":[]}")));
    }

    /**
     * A resource of a type AWS owns, whose format's account is {@code aws} (an IAM Identity Center application
     * provider), has no copy in the caller's account, so its ARN is checked as given alone; an ARN of any other type
     * giving that account is still checked as the caller's own too.
     */
    @Test
    void anArnOfATypeAwsOwnsIsCheckedInItsAccountAlone() {
        String provider = "arn:aws:sso::aws:applicationProvider/custom";
        assertEquals(List.of(provider), of("sso:DescribeApplicationProvider",
                json("{\"ApplicationProviderArn\":\"" + provider + "\"}")));
        String machine = "arn:aws:states:us-east-1:aws:stateMachine:orders";
        assertEquals(List.of(machine, MACHINE), of("states:DescribeStateMachine",
                json("{\"stateMachineArn\":\"" + machine + "\"}")));
    }

    /**
     * An ARN naming another account whose service acts on that account's resource for the caller (the sink an OAM
     * link is made to, a RAM invitation sent to it) is that resource alone, never the caller's of that name too; one
     * the service holds no such resource under is still checked as the caller's own too.
     */
    @Test
    void anArnOfAnotherAccountItsServiceActsOnThereIsCheckedAsGivenAlone() {
        String sink = "arn:aws:oam:us-east-1:111122223333:sink/abc";
        String invitation = "arn:aws:ram:us-east-1:111122223333:resource-share-invitation/i-1";
        String link = "{\"SinkIdentifier\":\"" + sink + "\",\"LabelTemplate\":\"$AccountName\"}";
        String accept = "{\"resourceShareInvitationArn\":\"" + invitation + "\"}";

        assertEquals(List.of(sink), RequestResources.of("oam:CreateLink", json(link), "aws", "us-east-1",
                "000000000000", reaching("oam", sink)));
        assertEquals(List.of(invitation), RequestResources.of("ram:AcceptResourceShareInvitation", json(accept), "aws",
                "us-east-1", "000000000000", reaching("ram", invitation)));
        assertEquals(List.of(sink, "arn:aws:oam:us-east-1:000000000000:sink/abc"), RequestResources.of("oam:CreateLink",
                json(link), "aws", "us-east-1", "000000000000", reaching("oam", "arn:aws:oam:us-east-1:111122223333:sink/x")));
        assertEquals(List.of(sink, "arn:aws:oam:us-east-1:000000000000:sink/abc"), of("oam:CreateLink", json(link)));
    }

    /**
     * A request leaving out the member naming the resource its service then uses one of its own for is checked on
     * the one the service finds: Resource Explorer's {@code Search} and {@code ListResources} with no {@code ViewArn},
     * on the Region's default view; none when it has none, and the view a request names when it names one.
     */
    @Test
    void aRequestNamingNoViewIsCheckedOnTheDefaultViewItsServiceFinds() {
        String view = "arn:aws:resource-explorer-2:us-east-1:000000000000:view/default-view/1";
        String named = "arn:aws:resource-explorer-2:us-east-1:000000000000:view/other/2";
        StoredResourceArns explorer = new StoredResourceArns() {
            @Override
            public String namespace() {
                return "resource-explorer-2";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return Optional.empty();
            }

            @Override
            public Optional<String> findDefault(String type, String region) {
                return "view".equals(type) && "us-east-1".equals(region) ? Optional.of(view) : Optional.empty();
            }
        };
        for (String operation : List.of("resource-explorer-2:Search", "resource-explorer-2:ListResources")) {
            assertEquals(List.of(view), RequestResources.of(operation, json("{\"QueryString\":\"\"}"), "aws",
                    "us-east-1", "000000000000", explorer));
            assertEquals(List.of(view), RequestResources.of(operation, json("{\"ViewArn\":null}"), "aws",
                    "us-east-1", "000000000000", explorer));
            assertEquals(List.of(named), RequestResources.of(operation, json("{\"ViewArn\":\"" + named + "\"}"), "aws",
                    "us-east-1", "000000000000", explorer));
            assertEquals(List.of(), RequestResources.of(operation, json("{}"), "aws", "eu-west-1", "000000000000",
                    explorer));
            assertEquals(List.of(), of(operation, json("{}")));
        }
    }

    /**
     * A field of each value of a map of structures names one resource each, beside the others its request names: an
     * FIS template's actions, by the {@code actionId} of each, as the template's create and update act on them.
     */
    @Test
    void eachValueOfAMapNamesTheResourceItsFieldNames() {
        String prefix = "arn:aws:fis:us-east-1:000000000000:";
        String actions = "\"actions\":{\"stop\":{\"actionId\":\"aws:ec2:stop-instances\"},"
                + "\"wait\":{\"actionId\":\"aws:fis:wait\",\"parameters\":{\"duration\":\"PT1M\"}}}";
        assertEquals(List.of(prefix + "experiment-template/*", prefix + "action/aws:ec2:stop-instances",
                prefix + "action/aws:fis:wait"), of("fis:CreateExperimentTemplate", json("{" + actions + "}")));
        assertEquals(List.of(prefix + "experiment-template/EXT1", prefix + "action/aws:ec2:stop-instances",
                prefix + "action/aws:fis:wait"), RequestResources.of("fis:UpdateExperimentTemplate",
                json("{" + actions + "}"), "aws", "us-east-1", "000000000000", null, Map.of("id", "EXT1")));
        assertEquals(List.of(prefix + "experiment-template/EXT1"), RequestResources.of("fis:UpdateExperimentTemplate",
                json("{\"actions\":{}}"), "aws", "us-east-1", "000000000000", null, Map.of("id", "EXT1")));
    }

    /**
     * A create whose action acts on no type tags the resource it makes where its tagging action acts on that type,
     * and is itself checked on {@code *}: Resource Explorer's {@code CreateView} tags its view; a Cloud Map namespace
     * create, whose {@code TagResource} acts on no type, tags nothing of its own, so its tags go where it is checked.
     */
    @Test
    void aCreateActingOnNoTypeTagsWhatItMakesWhereItsTaggingActionActsOnIt() {
        ContainerRequestContext view = json("{\"ViewName\":\"team\"}");
        String made = "arn:aws:resource-explorer-2:us-east-1:000000000000:view/team/*";
        assertEquals(List.of(made), RequestResources.created("resource-explorer-2:CreateView",
                "resource-explorer-2:TagResource", view, "aws", "us-east-1", "000000000000", Map.of()));
        assertEquals(List.of(made), created("resource-explorer-2:CreateView", view));
        assertEquals(List.of(), of("resource-explorer-2:CreateView", view));
        assertEquals(List.of("arn:aws:oam:us-east-1:000000000000:sink/*"), RequestResources.created("oam:CreateSink",
                "oam:TagResource", json("{\"Name\":\"s\"}"), "aws", "us-east-1", "000000000000", Map.of()));
        ContainerRequestContext namespace = json("{\"Name\":\"ns\"}");
        assertEquals(List.of(), RequestResources.created("servicediscovery:CreateHttpNamespace",
                "servicediscovery:TagResource", namespace, "aws", "us-east-1", "000000000000", Map.of()));
        assertEquals(List.of("arn:aws:servicediscovery:us-east-1:000000000000:namespace/*"),
                created("servicediscovery:CreateHttpNamespace", namespace));
    }

    /** A namespace's stored ARNs that find nothing, and act on the resource {@code arn} names in its own account. */
    private static StoredResourceArns reaching(String namespace, String arn) {
        return new StoredResourceArns() {
            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return Optional.empty();
            }

            @Override
            public boolean reachedInItsAccount(String reached, String accountId) {
                return reached.equals(arn) && "000000000000".equals(accountId);
            }
        };
    }

    @Test
    void aTypesVariablesAreFilledFromTheRequestAndAListGivesAResourceForEachValue() {
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/web",
                "arn:aws:ecr:us-east-1:000000000000:repository/api"), of("ecr:DescribeRepositories", json(
                "{\"repositoryNames\":[\"web\",\"api\"]}")));
        assertEquals(List.of("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_abc"),
                of("cognito-idp:DescribeUserPool", json("{\"UserPoolId\":\"us-east-1_abc\"}")));
    }

    /** A value that is itself an ARN of the type names that resource. */
    @Test
    void aValueThatIsAnArnOfTheTypeNamesItsResource() {
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/web"), of("ecr:DescribeRepositories", json(
                "{\"repositoryNames\":[\"arn:aws:ecr:us-east-1:000000000000:repository/web\"]}")));
    }

    /** The service mints a new target group's id, so it is open. */
    @Test
    void aVariableTheRequestDoesNotFillIsOpen() {
        assertEquals(List.of("arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/web/*"),
                of("elasticloadbalancing:CreateTargetGroup",
                        form("Action=CreateTargetGroup&Name=web&Protocol=HTTP&Port=80")));
    }

    /** A v2 load balancer's type, application when none is given, picks the ARN it is created under. */
    @Test
    void aLoadBalancerIsCreatedUnderTheArnOfItsType() {
        String prefix = "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/";
        assertEquals(List.of(prefix + "app/web/*"), of("elasticloadbalancing:CreateLoadBalancer",
                form("Action=CreateLoadBalancer&Name=web")));
        assertEquals(List.of(prefix + "net/web/*"), of("elasticloadbalancing:CreateLoadBalancer",
                form("Action=CreateLoadBalancer&Name=web&Type=network")));
        assertEquals(List.of(prefix + "gwy/web/*"), of("elasticloadbalancing:CreateLoadBalancer",
                form("Action=CreateLoadBalancer&Name=web&Type=gateway")));
        assertEquals(List.of(), of("elasticloadbalancing:CreateLoadBalancer",
                form("Action=CreateLoadBalancer&Name=web&Type=other")));
        // The classic API names it by LoadBalancerName.
        assertEquals(List.of(prefix + "classic"), of("elasticloadbalancing:CreateLoadBalancer",
                form("Action=CreateLoadBalancer&LoadBalancerName=classic&Listeners.member.1.Protocol=HTTP")));
    }

    /**
     * A listener is made under a load balancer, and a rule under a listener: their ARNs are the parent's with its
     * type word replaced and their own id open, of the parent's type.
     */
    @Test
    void aResourceCreatedUnderAParentIsNamedFromTheParentsArn() {
        String prefix = "arn:aws:elasticloadbalancing:us-east-1:000000000000:";
        assertEquals(List.of(prefix + "listener/app/web/abc/*"), created("elasticloadbalancing:CreateListener", form(
                "Action=CreateListener&LoadBalancerArn=" + prefix + "loadbalancer/app/web/abc&Protocol=HTTP&Port=80")));
        assertEquals(List.of(prefix + "listener/net/web/abc/*"), created("elasticloadbalancing:CreateListener", form(
                "Action=CreateListener&LoadBalancerArn=" + prefix + "loadbalancer/net/web/abc&Protocol=TCP&Port=80")));
        assertEquals(List.of(prefix + "listener-rule/app/web/abc/def/*"), created("elasticloadbalancing:CreateRule",
                form("Action=CreateRule&ListenerArn=" + prefix + "listener/app/web/abc/def&Priority=1")));
        // A parent of another account is named as given and as the caller's own.
        assertEquals(List.of("arn:aws:elasticloadbalancing:us-east-1:111122223333:listener/app/web/abc/*",
                prefix + "listener/app/web/abc/*"), created("elasticloadbalancing:CreateListener", form(
                "Action=CreateListener&LoadBalancerArn=arn:aws:elasticloadbalancing:us-east-1:111122223333:"
                        + "loadbalancer/app/web/abc")));
        // An ARN of no parent, a create of a type its action acts on, and no create name nothing.
        assertEquals(List.of(), created("elasticloadbalancing:CreateListener",
                form("Action=CreateListener&LoadBalancerArn=" + prefix + "targetgroup/web/abc")));
        assertEquals(List.of(), created("elasticloadbalancing:CreateTargetGroup",
                form("Action=CreateTargetGroup&Name=web")));
        assertEquals(List.of(), created("elasticloadbalancing:DeleteListener",
                form("Action=DeleteListener&ListenerArn=" + prefix + "listener/app/web/abc/def")));
    }

    /**
     * The operation is the one the request is dispatched as: an {@code X-Amz-Target} or {@code Action} naming
     * another, whose members name an allowed resource, does not change what it is checked on.
     */
    @Test
    void theOperationIsTheOneTheRequestIsDispatchedAsNotOneTheRequestNames() {
        ContainerRequestContext request = json("{\"name\":\"allowed\",\"stateMachineArn\":\"" + MACHINE + "\"}");
        when(request.getHeaderString("X-Amz-Target")).thenReturn("AWSStepFunctions.CreateStateMachine");
        assertEquals(List.of(MACHINE), of("states:DescribeStateMachine", request));
        String group = "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/web/1";
        assertEquals(List.of(group), of("elasticloadbalancing:DeleteTargetGroup",
                form("Action=CreateTargetGroup&Name=allowed&TargetGroupArn=" + group)));
        assertEquals(List.of(), created("elasticloadbalancing:DeleteLoadBalancer", form("Action=CreateListener"
                + "&LoadBalancerArn=arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/web/abc")));
    }

    @Test
    void aFormsListIsReadFromOneToTheFirstMissingNumber() {
        String first = "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/a/1";
        String second = "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/b/2";
        assertEquals(List.of(first, second), of("elasticloadbalancing:AddTags", form("Action=AddTags"
                + "&ResourceArns.member.1=" + first + "&ResourceArns.member.2=" + second
                + "&ResourceArns.member.4=arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/c/3"
                + "&Tags.member.1.Key=team")));
        // A classic load balancer goes by its name.
        assertEquals(List.of("arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/classic"),
                of("elasticloadbalancing:DeleteLoadBalancer",
                        form("Action=DeleteLoadBalancer&LoadBalancerName=classic")));
    }

    /**
     * A member is read as its handler reads it: a list only from a list, so a single name for one gives none, as
     * ECR's handler then lists every repository, a number, boolean or null as its text, and a structure as none.
     */
    @Test
    void aMemberIsReadAsItsHandlerReadsIt() {
        assertEquals(List.of(), of("ecr:DescribeRepositories", json("{\"repositoryNames\":\"allowed\"}")));
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/123",
                "arn:aws:ecr:us-east-1:000000000000:repository/null"),
                of("ecr:DescribeRepositories", json("{\"repositoryNames\":[123,{\"a\":1},null]}")));
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/7"),
                of("ecr:DeleteRepository", json("{\"repositoryName\":7}")));
        assertEquals(List.of(), of("ecr:DeleteRepository", json("{\"repositoryName\":[\"web\"]}")));
    }

    /**
     * ECR's {@code registryId} selects the registry its handler acts in, so the repositories are that account's;
     * one that is no account id names none.
     */
    @Test
    void aRepositoryIsInTheRegistryTheRequestNames() {
        assertEquals(List.of("arn:aws:ecr:us-east-1:111122223333:repository/web"), of("ecr:DescribeRepositories",
                json("{\"registryId\":\"111122223333\",\"repositoryNames\":[\"web\"]}")));
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/web"), of("ecr:DescribeRepositories",
                json("{\"repositoryNames\":[\"web\"]}")));
        assertEquals(List.of(), of("ecr:DescribeRepositories",
                json("{\"registryId\":\"1:2\",\"repositoryNames\":[\"web\"]}")));
        // A JSON null is no registry, as ECR reads it: the caller's.
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/web"), of("ecr:DescribeRepositories",
                json("{\"registryId\":null,\"repositoryNames\":[\"web\"]}")));
        // The scanning configuration's request has no registryId: its repositories are the caller's.
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/web"),
                of("ecr:BatchGetRepositoryScanningConfiguration",
                        json("{\"registryId\":\"111122223333\",\"repositoryNames\":[\"web\"]}")));
    }

    /**
     * {@code StartExecution} takes Step Functions Local's {@code <stateMachineArn>#<test case>} and runs the
     * machine the part before the {@code #} names, so that machine is checked; other operations read it whole.
     */
    @Test
    void aStateMachinesTestCaseIsCheckedOnTheMachineItRuns() {
        assertEquals(List.of(MACHINE), of("states:StartExecution",
                json("{\"stateMachineArn\":\"" + MACHINE + "#HappyPath\"}")));
        assertEquals(List.of(MACHINE), of("states:StartSyncExecution", json("{\"stateMachineArn\":\"" + MACHINE + "#\"}")));
        assertEquals(List.of(MACHINE + "#x"), of("states:DescribeStateMachine",
                json("{\"stateMachineArn\":\"" + MACHINE + "#x\"}")));
        assertEquals(List.of("arn:aws:states:us-east-1:000000000000:execution:orders:*"), created("states:StartExecution",
                json("{\"stateMachineArn\":\"" + MACHINE + "#HappyPath\"}")));
    }

    /**
     * Step Functions checks a version or an alias on its state machine's ARN, so a request naming one is checked on
     * the state machine, and an execution it starts is the state machine's; other types keep their ARN whole.
     */
    @Test
    void aVersionOrAnAliasIsCheckedOnItsStateMachine() {
        assertEquals(List.of(MACHINE), of("states:DescribeStateMachineAlias",
                json("{\"stateMachineAliasArn\":\"" + MACHINE + ":PROD\"}")));
        assertEquals(List.of(MACHINE), of("states:DeleteStateMachineVersion",
                json("{\"stateMachineVersionArn\":\"" + MACHINE + ":3\"}")));
        assertEquals(List.of(MACHINE), of("states:DescribeStateMachine", json("{\"stateMachineArn\":\"" + MACHINE + ":1\"}")));
        assertEquals(List.of(MACHINE), of("states:StartExecution",
                json("{\"stateMachineArn\":\"" + MACHINE + ":PROD#HappyPath\"}")));
        assertEquals(List.of("arn:aws:states:us-east-1:000000000000:execution:orders:*"), created("states:StartExecution",
                json("{\"stateMachineArn\":\"" + MACHINE + ":PROD\"}")));
        String execution = "arn:aws:states:us-east-1:000000000000:execution:orders:run-1";
        assertEquals(List.of(execution), of("states:DescribeExecution", json("{\"executionArn\":\"" + execution + "\"}")));
    }

    /**
     * An ARN inside a structure, or inside each of a list's structures, names its resource as a top-level one does:
     * {@code SetRulePriorities} acts on the rules its priorities name, read to the first element without one, as
     * its handler loops, and an alias acts on the state machine its versions are of.
     */
    @Test
    void anArnInsideAStructureNamesItsResource() {
        String prefix = "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener-rule/app/web/abc/def/";
        assertEquals(List.of(prefix + "1", prefix + "2"), of("elasticloadbalancing:SetRulePriorities", form(
                "Action=SetRulePriorities&RulePriorities.member.1.RuleArn=" + prefix + "1&RulePriorities.member.1.Priority=5"
                        + "&RulePriorities.member.2.RuleArn=" + prefix + "2&RulePriorities.member.2.Priority=6"
                        + "&RulePriorities.member.3.Priority=7&RulePriorities.member.4.RuleArn=" + prefix + "4")));
        assertEquals(List.of(prefix + "1"), of("elasticloadbalancing:SetRulePriorities",
                json("{\"RulePriorities\":[{\"RuleArn\":\"" + prefix + "1\"},\"" + prefix + "2\",{\"Priority\":1}]}")));
        assertEquals(List.of(MACHINE), of("states:CreateStateMachineAlias", json("{\"name\":\"PROD\","
                + "\"routingConfiguration\":[{\"stateMachineVersionArn\":\"" + MACHINE + ":1\",\"weight\":90},"
                + "{\"stateMachineVersionArn\":\"" + MACHINE + ":2\",\"weight\":10}]}")));
        assertEquals(List.of(MACHINE), of("states:UpdateStateMachineAlias", json("{\"stateMachineAliasArn\":\""
                + MACHINE + ":PROD\",\"routingConfiguration\":[{\"stateMachineVersionArn\":\"" + MACHINE + ":2\"}]}")));
        // An ARN there of a type the action does not act on is not checked: a rule's target group.
        String listener = "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/web/abc/def";
        assertEquals(List.of(listener), of("elasticloadbalancing:CreateRule", form("Action=CreateRule&ListenerArn="
                + listener + "&Actions.member.1.Type=forward&Actions.member.1.TargetGroupArn="
                + "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/web/1")));
    }

    /**
     * An EventBridge rule is on the event bus its request names, by name or ARN, or on the default one when it names
     * none, whose rules have no bus in their ARN; a bus of another account names that account's rule, as given and
     * as the caller's own, which Floci acts on.
     */
    @Test
    void aRuleIsOnTheEventBusItsRequestNames() {
        String events = "arn:aws:events:us-east-1:000000000000:";
        assertEquals(List.of(events + "rule/r"), of("events:DescribeRule", json("{\"Name\":\"r\"}")));
        assertEquals(List.of(events + "rule/r"), of("events:DescribeRule",
                json("{\"Name\":\"r\",\"EventBusName\":\"default\"}")));
        assertEquals(List.of(events + "rule/orders/r"), of("events:DescribeRule",
                json("{\"Name\":\"r\",\"EventBusName\":\"orders\"}")));
        assertEquals(List.of(events + "rule/orders/r"), of("events:DescribeRule",
                json("{\"Name\":\"r\",\"EventBusName\":\"" + events + "event-bus/orders\"}")));
        assertEquals(List.of(events + "rule/r"), of("events:DescribeRule",
                json("{\"Name\":\"r\",\"EventBusName\":\"" + events + "event-bus/default\"}")));
        assertEquals(List.of("arn:aws:events:eu-west-1:111122223333:rule/orders/r",
                "arn:aws:events:us-east-1:111122223333:rule/orders/r", "arn:aws:events:eu-west-1:000000000000:rule/orders/r",
                events + "rule/orders/r"), of("events:DescribeRule", json("{\"Name\":\"r\",\"EventBusName\":"
                + "\"arn:aws:events:eu-west-1:111122223333:event-bus/orders\"}")));
        // A target's rule is named by Rule.
        assertEquals(List.of(events + "rule/orders/r"), of("events:PutTargets", json("{\"Rule\":\"r\","
                + "\"EventBusName\":\"orders\",\"Targets\":[{\"Id\":\"1\",\"Arn\":\"arn:aws:sqs:us-east-1:000000000000:q\"}]}")));
        // A request naming no event bus describes the default one.
        assertEquals(List.of(events + "event-bus/default"), of("events:DescribeEventBus", json("{}")));
        assertEquals(List.of(events + "event-bus/orders"), of("events:DescribeEventBus", json("{\"Name\":\"orders\"}")));
        assertEquals(List.of(events + "event-bus/orders"), of("events:DescribeEventBus",
                json("{\"Name\":\"" + events + "event-bus/orders\"}")));
    }

    /**
     * A rule on another account's default event bus is that account's, checked as the caller's too, and a bus ARN
     * leaving its account and Region empty, or a null bus, names the caller's, which the handler acts on.
     */
    @Test
    void aRuleIsOnTheEventBusItsHandlerActsOn() {
        String events = "arn:aws:events:us-east-1:000000000000:";
        assertEquals(List.of("arn:aws:events:us-east-1:111122223333:rule/r", events + "rule/r"),
                of("events:DescribeRule", json("{\"Name\":\"r\",\"EventBusName\":"
                + "\"arn:aws:events:us-east-1:111122223333:event-bus/default\"}")));
        assertEquals(List.of(events + "rule/orders/r"), of("events:DescribeRule",
                json("{\"Name\":\"r\",\"EventBusName\":\"arn:aws:events:::event-bus/orders\"}")));
        assertEquals(List.of(events + "rule/r"), of("events:DescribeRule",
                json("{\"Name\":\"r\",\"EventBusName\":null}")));
        assertEquals(List.of(events + "event-bus/orders"), of("events:DescribeEventBus",
                json("{\"Name\":\"arn:aws:events:::event-bus/orders\"}")));
        assertEquals(List.of(events + "event-bus/default"), of("events:DescribeEventBus", json("{\"Name\":null}")));
    }

    /**
     * Each {@code PutEvents} entry is published to the event bus it names, by name or ARN, in the account and Region
     * that ARN gives, or to the default one when it names none, gives a null or a blank; a request publishing nothing
     * names no bus.
     */
    @Test
    void eachEntryIsOnTheEventBusItNames() {
        String events = "arn:aws:events:us-east-1:000000000000:";
        assertEquals(List.of(events + "event-bus/orders", events + "event-bus/default", events + "event-bus/audit"),
                of("events:PutEvents", json("{\"Entries\":[{\"EventBusName\":\"orders\"},{\"Source\":\"s\"},"
                        + "{\"EventBusName\":\"audit\"},{\"EventBusName\":\"orders\"}]}")));
        assertEquals(List.of(events + "event-bus/default"), of("events:PutEvents",
                json("{\"Entries\":[{\"EventBusName\":null},{\"EventBusName\":\"\"},{\"EventBusName\":\" \"}]}")));
        // Each ARN puts its entry's bus in its own account and Region, checked as the caller's too.
        assertEquals(List.of("arn:aws:events:eu-west-1:111122223333:event-bus/orders",
                "arn:aws:events:us-east-1:111122223333:event-bus/orders",
                "arn:aws:events:eu-west-1:000000000000:event-bus/orders", events + "event-bus/orders",
                events + "event-bus/audit", "arn:aws:events:us-east-1:444455556666:event-bus/billing",
                events + "event-bus/billing"), of("events:PutEvents", json("{\"Entries\":["
                + "{\"EventBusName\":\"arn:aws:events:eu-west-1:111122223333:event-bus/orders\"},"
                + "{\"EventBusName\":\"audit\"},"
                + "{\"EventBusName\":\"arn:aws:events:us-east-1:444455556666:event-bus/billing\"}]}")));
        assertEquals(List.of(events + "event-bus/orders"), of("events:PutEvents",
                json("{\"Entries\":[{\"EventBusName\":\"arn:aws:events:::event-bus/orders\"}]}")));
        assertEquals(List.of(), of("events:PutEvents", json("{\"Entries\":[]}")));
        assertEquals(List.of(), of("events:PutEvents", json("{}")));
        // A form's entries run from 1 to the first it gives nothing of.
        assertEquals(List.of(events + "event-bus/orders", events + "event-bus/default"), of("events:PutEvents",
                form("Action=PutEvents&Entries.member.1.EventBusName=orders&Entries.member.2.Source=s"
                        + "&Entries.member.4.EventBusName=late")));
    }

    /**
     * An EventBridge V2 request, checked as the same {@code events} actions, names its bus or subscriber by ARN and
     * never the default bus, and a create makes an {@code event-busv2}, whose id V2 mints. EventBridge's own requests
     * still name their bus by Name.
     */
    @Test
    void anEventBridgeV2RequestIsOnTheBusOrSubscriberItsArnNames() {
        String events = "arn:aws:events:us-east-1:000000000000:";
        String bus = events + "event-busv2/orders/a1b2c3d4";
        String subscriber = events + "subscriber/audit/e5f6g7h8";
        assertEquals(List.of(bus), of("events:PutEvents",
                v2("{\"EventBusArn\":\"" + bus + "\",\"Entries\":[{\"Source\":\"s\",\"DetailType\":\"d\"}]}")));
        assertEquals(List.of(bus), of("events:DescribeEventBus", v2("{\"EventBusArn\":\"" + bus + "\"}")));
        assertEquals(List.of(bus), of("events:DeleteEventBus", v2("{\"EventBusArn\":\"" + bus + "\"}")));
        assertEquals(List.of(subscriber), of("events:DescribeSubscriber",
                v2("{\"SubscriberArn\":\"" + subscriber + "\"}")));
        assertEquals(List.of(subscriber), of("events:UpdateSubscriber",
                v2("{\"SubscriberArn\":\"" + subscriber + "\",\"State\":\"STOPPED\"}")));
        assertEquals(List.of(subscriber), of("events:DeleteSubscriber",
                v2("{\"SubscriberArn\":\"" + subscriber + "\"}")));
        // A subscriber is made on the bus it names, its target and role naming neither.
        assertEquals(List.of(bus, events + "subscriber/audit/*"), of("events:CreateSubscriber",
                v2("{\"Name\":\"audit\",\"EventBusArn\":\"" + bus + "\",\"InvokeConfiguration\":{"
                        + "\"TargetArn\":\"arn:aws:sqs:us-east-1:000000000000:q\","
                        + "\"RoleArn\":\"arn:aws:iam::000000000000:role/r\"}}")));
        assertEquals(List.of(events + "subscriber/audit/*"), created("events:CreateSubscriber",
                v2("{\"Name\":\"audit\",\"EventBusArn\":\"" + bus + "\"}")));
        assertEquals(List.of(events + "event-busv2/orders/*"), of("events:CreateEventBus",
                v2("{\"Name\":\"orders\"}")));
        assertEquals(List.of(), created("events:CreateEventBus", v2("{\"Name\":\"orders\"}")));

        assertEquals(List.of(events + "event-bus/orders"), of("events:CreateEventBus", v1("{\"Name\":\"orders\"}")));
        assertEquals(List.of(events + "event-bus/orders"), created("events:CreateEventBus",
                v1("{\"Name\":\"orders\",\"DeadLetterConfig\":{\"Arn\":\"" + events + "event-bus/other\"}}")));
        assertEquals(List.of(events + "event-bus/orders"), of("events:DeleteEventBus", v1("{\"Name\":\"orders\"}")));
        assertEquals(List.of(events + "event-bus/orders"), of("events:UpdateEventBus",
                v1("{\"Name\":\"orders\",\"Description\":\"d\"}")));
        assertEquals(List.of("arn:aws:events:us-east-1::event-source/aws.partner/acme/orders"),
                of("events:DescribeEventSource", v1("{\"Name\":\"aws.partner/acme/orders\"}")));
    }

    /**
     * Which API's resources a request is checked on is the API serving it, never a member it gives: an EventBridge
     * request naming a V2 bus's ARN beside its own bus, or giving V2's ClientToken, is checked on the bus its handler
     * acts on, and a request no service is known to serve is checked on both.
     */
    @Test
    void aMemberOfTheOtherApiDoesNotChooseTheResourceChecked() {
        String events = "arn:aws:events:us-east-1:000000000000:";
        String v2Bus = events + "event-busv2/open/a1b2c3d4";
        String withV2Arn = "{\"Name\":\"locked\",\"EventBusArn\":\"" + v2Bus + "\"}";
        for (String operation : List.of("events:DeleteEventBus", "events:DescribeEventBus", "events:UpdateEventBus")) {
            assertEquals(List.of(events + "event-bus/locked"), of(operation, v1(withV2Arn)), operation);
            assertEquals(List.of(v2Bus, events + "event-bus/locked"), of(operation, json(withV2Arn)), operation);
        }
        assertEquals(List.of(events + "event-bus/locked"), of("events:PutEvents", v1("{\"EventBusArn\":\"" + v2Bus
                + "\",\"Entries\":[{\"EventBusName\":\"locked\",\"Source\":\"s\"}]}")));
        assertEquals(List.of(events + "event-bus/locked"), of("events:CreateEventBus",
                v1("{\"Name\":\"locked\",\"ClientToken\":\"t\"}")));
        assertEquals(List.of(v2Bus), of("events:DeleteEventBus", v2(withV2Arn)));
    }

    @Test
    void incompleteStoredSelectorsRetainNoResourceThroughTheDefaultLookup() {
        for (String operation : List.of("events:DescribeConnection", "application-autoscaling:DeleteScalingPolicy",
                "redshift-data:ListSchemas")) {
            AtomicInteger lookups = new AtomicInteger();
            StoredResourceArns stored = new StoredResourceArns() {
                @Override
                public String namespace() {
                    return operation.substring(0, operation.indexOf(':'));
                }

                @Override
                public Optional<String> find(String type, List<String> names, String region) {
                    assertEquals(List.of(), names);
                    lookups.incrementAndGet();
                    return Optional.empty();
                }
            };
            assertEquals(List.of(), RequestResources.of(operation, json("{}"), "aws", "us-east-1",
                    "000000000000", stored), operation);
            assertEquals(operation.startsWith("events:") ? 0 : 1, lookups.get(), operation);
        }
    }

    /**
     * A connection or an API destination, whose ARN EventBridge follows with an id it mints after the name, is the
     * one stored under the name the request gives, and has that id open when none is or nothing looks it up.
     */
    @Test
    void aResourceWhoseIdItsServiceMintsIsTheOneStoredUnderItsName() {
        String events = "arn:aws:events:us-east-1:000000000000:";
        StoredResourceArns stored = new StoredResourceArns() {
            @Override
            public String namespace() {
                return "events";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return List.of("web").equals(names) && "us-east-1".equals(region)
                        ? Optional.of(events + type + "/web/1234") : Optional.empty();
            }
        };
        assertEquals(List.of(events + "connection/web/1234"), RequestResources.of("events:DescribeConnection",
                json("{\"Name\":\"web\"}"), "aws", "us-east-1", "000000000000", stored));
        assertEquals(List.of(events + "api-destination/web/1234"), RequestResources.of(
                "events:DescribeApiDestination", json("{\"Name\":\"web\"}"), "aws", "us-east-1", "000000000000", stored));
        assertEquals(List.of(events + "connection/other/*"), RequestResources.of("events:DescribeConnection",
                json("{\"Name\":\"other\"}"), "aws", "us-east-1", "000000000000", stored));
        assertEquals(List.of(events + "connection/web/*"), of("events:DescribeConnection", json("{\"Name\":\"web\"}")));
        // A create names the connection it uses by its ARN, the id given, and has its own id open.
        assertEquals(List.of(events + "connection/web/1234", events + "api-destination/new/*"), RequestResources.of(
                "events:CreateApiDestination", json("{\"Name\":\"new\",\"ConnectionArn\":\"" + events
                + "connection/web/1234\"}"), "aws", "us-east-1", "000000000000", stored));
    }

    /**
     * A key is named by its id or by an alias, and an alias names the key it points at, so a request naming a key by
     * an alias is checked on the key the alias names, as the handler resolves it: an allow scoped to that key passes
     * and a deny scoped to it holds, whichever of the two names the request used. The alias itself is checked on its
     * own ARN, and the tagging operations on the key their {@code ResourceArn} names.
     */
    @Test
    void aKeyIsCheckedOnTheKeyItsNameNames() {
        String key = "arn:aws:payment-cryptography:us-east-1:000000000000:key/abc123";
        String alias = "arn:aws:payment-cryptography:us-east-1:000000000000:alias/team/card-key";
        StoredResourceArns stored = new StoredResourceArns() {
            @Override
            public String namespace() {
                return "payment-cryptography";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                if (!"us-east-1".equals(region)) {
                    return Optional.empty();
                }
                return switch (type) {
                    case "key" -> List.of("alias/team/card-key").equals(names) || List.of("abc123").equals(names)
                            ? Optional.of(key) : Optional.empty();
                    case "alias" -> List.of("alias/team/card-key").equals(names) ? Optional.of(alias) : Optional.empty();
                    default -> Optional.empty();
                };
            }
        };
        // A key named by an alias is checked on the key the alias points at.
        assertEquals(List.of(key), RequestResources.of("payment-cryptography:GetKey",
                json("{\"KeyIdentifier\":\"alias/team/card-key\"}"), "aws", "us-east-1", "000000000000", stored));
        // A key named by its id is checked on that key.
        assertEquals(List.of(key), RequestResources.of("payment-cryptography:GetKey",
                json("{\"KeyIdentifier\":\"abc123\"}"), "aws", "us-east-1", "000000000000", stored));
        // A key named by its ARN is checked on that ARN.
        assertEquals(List.of(key), RequestResources.of("payment-cryptography:GetKey",
                json("{\"KeyIdentifier\":\"" + key + "\"}"), "aws", "us-east-1", "000000000000", stored));
        // The alias operations are checked on the alias they name.
        assertEquals(List.of(alias), RequestResources.of("payment-cryptography:GetAlias",
                json("{\"AliasName\":\"alias/team/card-key\"}"), "aws", "us-east-1", "000000000000", stored));
        // The tagging operations are checked on the key their ResourceArn names.
        assertEquals(List.of(key), RequestResources.of("payment-cryptography:TagResource",
                json("{\"ResourceArn\":\"" + key + "\"}"), "aws", "us-east-1", "000000000000", stored));
    }

    /**
     * A snapshot another account shares is named by its name and the {@code ownerAccount} of that account: it is that
     * account's snapshot the request acts on, the one {@code findInAccount} gives or else the owner's ARN with its id
     * open, and a request naming no owner acts on the caller's own.
     */
    @Test
    void aResourceFoundByMembersIsInTheAccountItsAccountMemberGives() {
        String own = "arn:aws:redshift-serverless:us-east-1:000000000000:snapshot/own-id";
        String theirs = "arn:aws:redshift-serverless:us-east-1:111111111111:snapshot/their-id";
        StoredResourceArns stored = new StoredResourceArns() {
            @Override
            public String namespace() {
                return "redshift-serverless";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return List.of("snap").equals(names) ? Optional.of(own) : Optional.empty();
            }

            @Override
            public Optional<String> findInAccount(String type, List<String> names, String account, String region) {
                return "111111111111".equals(account) && List.of("snap").equals(names)
                        ? Optional.of(theirs) : Optional.empty();
            }
        };
        assertEquals(List.of(own), RequestResources.of("redshift-serverless:GetSnapshot",
                json("{\"snapshotName\":\"snap\"}"), "aws", "us-east-1", "000000000000", stored));
        assertEquals(List.of(theirs), RequestResources.of("redshift-serverless:GetSnapshot",
                json("{\"snapshotName\":\"snap\",\"ownerAccount\":\"111111111111\"}"), "aws", "us-east-1",
                "000000000000", stored));
        // A snapshot the owner has none of by that name has its id open in the owner's account.
        assertEquals(List.of("arn:aws:redshift-serverless:us-east-1:222222222222:snapshot/*"),
                RequestResources.of("redshift-serverless:GetSnapshot",
                        json("{\"snapshotName\":\"other\",\"ownerAccount\":\"222222222222\"}"), "aws",
                        "us-east-1", "000000000000", stored));
        // An owner that is no account id names no resource.
        assertEquals(List.of(), RequestResources.of("redshift-serverless:GetSnapshot",
                json("{\"snapshotName\":\"snap\",\"ownerAccount\":\"nope\"}"), "aws", "us-east-1",
                "000000000000", stored));
    }

    /**
     * A scalable target's ARN is all the id Application Auto Scaling mints, while its requests name it by its service
     * namespace, resource id and scalable dimension: it is the one stored under those, or else has its id open, as a
     * new one has; a request leaving one out names none, and one naming it by its ARN names it as given.
     */
    @Test
    void aResourceWhoseArnIsAllAMintedIdIsTheOneStoredUnderTheMembersNamingIt() {
        String targets = "arn:aws:application-autoscaling:us-east-1:000000000000:scalable-target/";
        StoredResourceArns stored = new StoredResourceArns() {
            @Override
            public String namespace() {
                return "application-autoscaling";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return "ScalableTarget".equals(type) && "us-east-1".equals(region)
                        && List.of("ecs", "service/default/web", "ecs:service:DesiredCount").equals(names)
                        ? Optional.of(targets + "0123abcd") : Optional.empty();
            }
        };
        String web = "\"ServiceNamespace\":\"ecs\",\"ResourceId\":\"service/default/web\","
                + "\"ScalableDimension\":\"ecs:service:DesiredCount\"";
        assertEquals(List.of(targets + "0123abcd"), RequestResources.of("application-autoscaling:PutScalingPolicy",
                json("{\"PolicyName\":\"p\"," + web + "}"), "aws", "us-east-1", "000000000000", stored));
        assertEquals(List.of(targets + "*"), RequestResources.of("application-autoscaling:RegisterScalableTarget",
                json("{" + web.replace("/web", "/api") + "}"), "aws", "us-east-1", "000000000000", stored));
        assertEquals(List.of(targets + "*"), of("application-autoscaling:DeregisterScalableTarget", json("{" + web + "}")));
        assertEquals(List.of(), RequestResources.of("application-autoscaling:DeregisterScalableTarget",
                json("{\"ServiceNamespace\":\"ecs\",\"ResourceId\":\"service/default/web\"}"), "aws", "us-east-1",
                "000000000000", stored));
        assertEquals(List.of(), RequestResources.of("application-autoscaling:DeregisterScalableTarget",
                json("{" + web.replace("\"ecs\",", "null,") + "}"), "aws", "us-east-1", "000000000000", stored));
        assertEquals(List.of(targets + "0123abcd"), RequestResources.of("application-autoscaling:TagResource",
                json("{\"ResourceARN\":\"" + targets + "0123abcd\",\"Tags\":{}}"), "aws", "us-east-1", "000000000000",
                stored));
    }

    /**
     * A Data API request runs on the cluster its {@code ClusterIdentifier} names, or on the serverless workgroup
     * stored under the name or ARN its {@code WorkgroupName} gives, whose ARN ends in the id Redshift Serverless
     * mints; its {@code SecretArn} names neither. A request naming its session by {@code SessionId} runs on the
     * cluster or workgroup the caller's session runs on, as the service gives it, and names none when there is no such
     * session, or the service gives an ARN of neither.
     */
    @Test
    void aDataApiRequestIsOnTheClusterOrTheStoredWorkgroupItNames() {
        String workgroups = "arn:aws:redshift-serverless:us-east-1:000000000000:workgroup/";
        String cluster = "arn:aws:redshift:us-east-1:000000000000:cluster:warehouse";
        String id = "0f1e2d3c-4b5a-4978-8a9b-0c1d2e3f4a5b";
        Map<String, String> sessions = Map.of("11111111-2222-4333-8444-555555555555", workgroups + id,
                "66666666-7777-4888-9999-000000000000", cluster,
                "77777777-8888-4999-8000-111111111111", "arn:aws:secretsmanager:us-east-1:000000000000:secret:db-AbCdEf");
        StoredResourceArns stored = new StoredResourceArns() {
            @Override
            public String namespace() {
                return "redshift-data";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return "workgroup".equals(type) && "us-east-1".equals(region)
                        && (List.of("analytics").equals(names) || List.of(workgroups + id).equals(names))
                        ? Optional.of(workgroups + id) : Optional.empty();
            }

            @Override
            public List<String> through(String member, String value, String region) {
                return "SessionId".equals(member) && "us-east-1".equals(region)
                        ? Optional.ofNullable(sessions.get(value)).stream().toList() : List.of();
            }
        };
        String secret = ",\"SecretArn\":\"arn:aws:secretsmanager:us-east-1:000000000000:secret:db-AbCdEf\"";
        assertEquals(List.of("arn:aws:redshift:us-east-1:000000000000:cluster:warehouse"), RequestResources.of(
                "redshift-data:ExecuteStatement", json("{\"Sql\":\"select 1\",\"ClusterIdentifier\":\"warehouse\""
                        + secret + "}"), "aws", "us-east-1", "000000000000", stored));
        for (String named : List.of("analytics", workgroups + id)) {
            assertEquals(List.of(workgroups + id), RequestResources.of("redshift-data:ListDatabases",
                    json("{\"Database\":\"dev\",\"WorkgroupName\":\"" + named + "\"" + secret + "}"), "aws",
                    "us-east-1", "000000000000", stored), named);
        }
        assertEquals(List.of(workgroups + "*"), RequestResources.of("redshift-data:ListDatabases",
                json("{\"Database\":\"dev\",\"WorkgroupName\":\"other\"}"), "aws", "us-east-1", "000000000000",
                stored));
        assertEquals(List.of(workgroups + id), RequestResources.of("redshift-data:ExecuteStatement",
                json("{\"Sql\":\"select 1\",\"SessionId\":\"11111111-2222-4333-8444-555555555555\"}"), "aws",
                "us-east-1", "000000000000", stored));
        assertEquals(List.of(workgroups + id), RequestResources.of("redshift-data:ExecuteStatement",
                json("{\"Sql\":\"select 1\",\"SessionId\":\"11111111-2222-4333-8444-555555555555\","
                        + "\"WorkgroupName\":\"analytics\"}"), "aws", "us-east-1", "000000000000", stored));
        assertEquals(List.of(cluster), RequestResources.of("redshift-data:BatchExecuteStatement",
                json("{\"Sqls\":[\"select 1\"],\"SessionId\":\"66666666-7777-4888-9999-000000000000\"}"), "aws",
                "us-east-1", "000000000000", stored));
        for (String session : List.of(id, "77777777-8888-4999-8000-111111111111")) {
            assertEquals(List.of(), RequestResources.of("redshift-data:ExecuteStatement",
                    json("{\"Sql\":\"select 1\",\"SessionId\":\"" + session + "\"}"), "aws", "us-east-1",
                    "000000000000", stored), session);
        }
        assertEquals(List.of(), RequestResources.of("redshift-data:ExecuteStatement",
                json("{\"Sql\":\"select 1\",\"SessionId\":\"11111111-2222-4333-8444-555555555555\"}"), "aws",
                "eu-west-1", "000000000000", stored));
    }

    /**
     * An EMR request modifying instance groups without its {@code ClusterId} is on the cluster each group is in, as its
     * service finds it, and none for a group in no cluster; with one, on that cluster alone, and with a null one as
     * with none. A permission set provisioned to all the accounts it is provisioned to is on each of them, a null
     * {@code TargetId} naming none, and one provisioned to an account on that account alone.
     */
    @Test
    void aResourceIsNamedThroughEachValueOfAListAndOnlyWhenTheRequestNamesItNoOtherWay() {
        String clusters = "arn:aws:elasticmapreduce:us-east-1:000000000000:cluster/";
        Map<String, String> groups = Map.of("ig-A", clusters + "j-A", "ig-B", clusters + "j-B");
        StoredResourceArns emr = new StoredResourceArns() {
            @Override
            public String namespace() {
                return "elasticmapreduce";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return Optional.empty();
            }

            @Override
            public List<String> through(String member, String value, String region) {
                return "InstanceGroups[].InstanceGroupId".equals(member)
                        ? Optional.ofNullable(groups.get(value)).stream().toList() : List.of();
            }
        };
        String modified = "\"InstanceGroups\":[{\"InstanceGroupId\":\"ig-A\",\"InstanceCount\":2},"
                + "{\"InstanceGroupId\":\"ig-B\",\"InstanceCount\":2},{\"InstanceGroupId\":\"ig-none\"}]";
        assertEquals(List.of(clusters + "j-A", clusters + "j-B"), RequestResources.of(
                "elasticmapreduce:ModifyInstanceGroups", json("{" + modified + "}"), "aws", "us-east-1",
                "000000000000", emr));
        assertEquals(List.of(clusters + "j-A"), RequestResources.of("elasticmapreduce:ModifyInstanceGroups",
                json("{\"ClusterId\":\"j-A\"," + modified + "}"), "aws", "us-east-1", "000000000000", emr));
        // A null ClusterId names no cluster of its own, as the handler reads it; an InstanceGroups that is not a
        // list names no group, which the handler refuses.
        assertEquals(List.of(clusters + "j-A", clusters + "j-B"), RequestResources.of(
                "elasticmapreduce:ModifyInstanceGroups", json("{\"ClusterId\":null," + modified + "}"), "aws",
                "us-east-1", "000000000000", emr));
        assertEquals(List.of(), RequestResources.of("elasticmapreduce:ModifyInstanceGroups",
                json("{\"InstanceGroups\":{\"first\":{\"InstanceGroupId\":\"ig-A\"}}}"), "aws", "us-east-1",
                "000000000000", emr));

        String instance = "arn:aws:sso:::instance/ssoins-1111111111111111";
        String permissionSet = "arn:aws:sso:::permissionSet/ssoins-1111111111111111/ps-2222222222222222";
        StoredResourceArns sso = new StoredResourceArns() {
            @Override
            public String namespace() {
                return "sso";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return Optional.empty();
            }

            @Override
            public List<String> through(String member, String value, String region) {
                return "PermissionSetArn".equals(member) && permissionSet.equals(value)
                        ? List.of("arn:aws:sso:::account/111111111111", "arn:aws:sso:::account/222222222222")
                        : List.of();
            }
        };
        String provisioned = "{\"InstanceArn\":\"" + instance + "\",\"PermissionSetArn\":\"" + permissionSet + "\",";
        assertEquals(List.of(instance, permissionSet, "arn:aws:sso:::account/111111111111",
                "arn:aws:sso:::account/222222222222"), RequestResources.of("sso:ProvisionPermissionSet",
                json(provisioned + "\"TargetType\":\"ALL_PROVISIONED_ACCOUNTS\"}"), "aws", "us-east-1",
                "000000000000", sso));
        assertEquals(List.of(instance, permissionSet, "arn:aws:sso:::account/111111111111",
                "arn:aws:sso:::account/222222222222"), RequestResources.of("sso:ProvisionPermissionSet",
                json(provisioned + "\"TargetType\":\"ALL_PROVISIONED_ACCOUNTS\",\"TargetId\":null}"), "aws",
                "us-east-1", "000000000000", sso));
        assertEquals(List.of(instance, permissionSet, "arn:aws:sso:::account/333333333333"), RequestResources.of(
                "sso:ProvisionPermissionSet", json(provisioned + "\"TargetType\":\"AWS_ACCOUNT\","
                        + "\"TargetId\":\"333333333333\"}"), "aws", "us-east-1", "000000000000", sso));
    }

    /**
     * A resource an IAM Identity Center create makes in an instance has the id of the instance its request names by
     * its ARN, and its own id open, so a grant on the instance's permission sets covers it.
     */
    @Test
    void aResourceMadeInAnInstanceHasTheInstancesId() {
        String instance = "arn:aws:sso:::instance/ssoins-1111111111111111";
        assertEquals(List.of(instance, "arn:aws:sso:::permissionSet/ssoins-1111111111111111/*"),
                of("sso:CreatePermissionSet", json("{\"InstanceArn\":\"" + instance + "\",\"Name\":\"admins\"}")));
        assertEquals(List.of("arn:aws:sso:::permissionSet/ssoins-1111111111111111/*"),
                RequestResources.created("sso:CreatePermissionSet",
                        json("{\"InstanceArn\":\"" + instance + "\",\"Name\":\"admins\"}"), "aws", "us-east-1",
                        "000000000000"));
        assertEquals(List.of(instance, "arn:aws:sso::000000000000:trustedTokenIssuer/ssoins-1111111111111111/*"),
                of("sso:CreateTrustedTokenIssuer", json("{\"InstanceArn\":\"" + instance + "\",\"Name\":\"idp\","
                        + "\"TrustedTokenIssuerType\":\"OIDC_JWT\"}")));
    }

    /** A request value is a name, so a {@code ${...}} in it is kept, not read as a variable of the format. */
    @Test
    void aValueIsTakenLiterally() {
        assertEquals(List.of("arn:aws:ecr:us-east-1:000000000000:repository/${Account}"),
                of("ecr:DescribeRepositories", json(
                "{\"repositoryNames\":[\"${Account}\"]}")));
    }

    /**
     * A pipeline is the one its request's {@code pipeline.name} names; its {@code roleArn}, the role CodePipeline
     * assumes, names none, even when it holds a pipeline's ARN. A webhook is the one {@code webhook.name} names, on
     * the pipeline {@code webhook.targetPipeline} names, and its tags go on the webhook alone.
     */
    @Test
    void aPipelineIsTheOneItsStructuresNameNames() {
        String codePipeline = "arn:aws:codepipeline:us-east-1:000000000000:";
        assertEquals(List.of(codePipeline + "b"), of("codepipeline:UpdatePipeline",
                json("{\"pipeline\":{\"name\":\"b\",\"roleArn\":\"" + codePipeline + "a\"}}")));
        assertEquals(List.of(codePipeline + "b"), of("codepipeline:CreatePipeline", form("Action=CreatePipeline"
                + "&pipeline.name=b&pipeline.roleArn=" + codePipeline + "a")));
        assertEquals(List.of(), of("codepipeline:UpdatePipeline", json("{\"pipeline\":{\"roleArn\":\"" + codePipeline
                + "a\"}}")));
        assertEquals(List.of(codePipeline + "webhook:w", codePipeline + "p"), of("codepipeline:PutWebhook",
                json("{\"webhook\":{\"name\":\"w\",\"targetPipeline\":\"p\"}}")));
        // A webhook's tags go on the webhook, not on the pipeline it connects to.
        assertEquals(List.of(codePipeline + "webhook:w"), created("codepipeline:PutWebhook",
                json("{\"webhook\":{\"name\":\"w\",\"targetPipeline\":\"p\"}}")));
    }

    /** A custom action type is {@code Custom}'s, the owner CodePipeline gives every one, which its request does not. */
    @Test
    void aCustomActionTypeIsCustoms() {
        assertEquals(List.of("arn:aws:codepipeline:us-east-1:000000000000:actiontype:Custom/Build/MyProvider/1"),
                of("codepipeline:CreateCustomActionType",
                        json("{\"category\":\"Build\",\"provider\":\"MyProvider\",\"version\":\"1\"}")));
        assertEquals(List.of("arn:aws:codepipeline:us-east-1:000000000000:actiontype:Custom/Build/MyProvider/1"),
                of("codepipeline:DeleteCustomActionType",
                        json("{\"category\":\"Build\",\"provider\":\"MyProvider\",\"version\":\"1\"}")));
        assertEquals(List.of(), of("codepipeline:DeleteCustomActionType", json("{}")));
    }

    /**
     * A Cloud Map service is created in the namespace {@code NamespaceId} names, or {@code DnsConfig.NamespaceId}
     * when it names none or gives a JSON null, as the handler reads them, and tags go on the service it creates,
     * not on the namespace.
     */
    @Test
    void aServiceIsCreatedInTheNamespaceItsHandlerReads() {
        String cloudMap = "arn:aws:servicediscovery:us-east-1:000000000000:";
        assertEquals(List.of(cloudMap + "namespace/ns-a", cloudMap + "service/*"), of("servicediscovery:CreateService",
                json("{\"Name\":\"s\",\"NamespaceId\":\"ns-a\"}")));
        assertEquals(List.of(cloudMap + "namespace/ns-b", cloudMap + "service/*"), of("servicediscovery:CreateService",
                json("{\"Name\":\"s\",\"DnsConfig\":{\"NamespaceId\":\"ns-b\"}}")));
        assertEquals(List.of(cloudMap + "namespace/ns-a", cloudMap + "service/*"), of("servicediscovery:CreateService",
                json("{\"Name\":\"s\",\"NamespaceId\":\"ns-a\",\"DnsConfig\":{\"NamespaceId\":\"ns-b\"}}")));
        // A JSON null is no namespace, as the handler reads it, which then takes DnsConfig's.
        assertEquals(List.of(cloudMap + "namespace/ns-b", cloudMap + "service/*"), of("servicediscovery:CreateService",
                json("{\"Name\":\"s\",\"NamespaceId\":null,\"DnsConfig\":{\"NamespaceId\":\"ns-b\"}}")));
        assertEquals(List.of(cloudMap + "service/*"), created("servicediscovery:CreateService",
                json("{\"Name\":\"s\",\"NamespaceId\":\"ns-a\"}")));
    }

    /** A CodeBuild build is checked on its project, which its id names before its {@code :}. */
    @Test
    void aBuildIsCheckedOnItsProject() {
        String project = "arn:aws:codebuild:us-east-1:000000000000:project/";
        assertEquals(List.of(project + "a"), of("codebuild:StopBuild", json("{\"id\":\"a:1\"}")));
        assertEquals(List.of(project + "a", project + "b"), of("codebuild:BatchGetBuilds",
                json("{\"ids\":[\"a:1\",\"b:2\",\"a:3\"]}")));
        assertEquals(List.of(project + "a"), of("codebuild:RetryBuild", form("Action=RetryBuild&id=a:1")));
    }

    /** A list taking a name or an ARN for each value names each value's resource, whichever it gives. */
    @Test
    void aListMixingNamesAndArnsNamesEachValuesResource() {
        String project = "arn:aws:codebuild:us-east-1:000000000000:project/";
        assertEquals(List.of(project + "b", project + "a"), of("codebuild:BatchGetProjects",
                json("{\"names\":[\"a\",\"" + project + "b\"]}")));
        assertEquals(List.of(project + "a", project + "b"), of("codebuild:BatchGetProjects",
                json("{\"names\":[\"" + project + "a\",\"" + project + "b\"]}")));
    }

    @Test
    void identifierInferenceDoesNotAuthorizeUnrelatedArnPatternMembers() {
        String prefix = "arn:aws:";
        assertEquals(List.of(prefix + "athena:us-east-1:000000000000:workgroup/team"), of("athena:StartSession",
                json("{\"WorkGroup\":\"team\",\"ExecutionRole\":\"arn:aws:iam::000000000000:role/runner\"}")));
        assertEquals(List.of(prefix + "kinesisanalytics:us-east-1:000000000000:application/job"),
                of("kinesisanalytics:UpdateApplication", json("{\"ApplicationName\":\"job\","
                        + "\"ServiceExecutionRoleUpdate\":\"arn:aws:iam::000000000000:role/runner\"}")));
        String subscription = prefix + "ce::000000000000:anomalysubscription/subscription";
        assertEquals(List.of(subscription), of("ce:UpdateAnomalySubscription",
                json("{\"SubscriptionArn\":\"" + subscription + "\",\"MonitorArnList\":["
                        + "\"arn:aws:ce::000000000000:anomalymonitor/monitor\"]}")));
        assertEquals(List.of(prefix + "quicksight:us-east-1:000000000000:dashboard/dashboard"),
                RequestResources.of("quicksight:UpdateDashboardLinks", json("{\"LinkEntities\":["
                        + "\"arn:aws:quicksight:us-east-1:000000000000:analysis/analysis\"]}"),
                        "aws", "us-east-1", "000000000000", null, Map.of("DashboardId", "dashboard")));
        String application = prefix + "sso::000000000000:application/ssoins-1234567890123456/apl-1234567890123456";
        assertEquals(List.of(application), of("sso:PutApplicationAccessScope",
                json("{\"ApplicationArn\":\"" + application + "\",\"AuthorizedTargets\":["
                        + "\"arn:aws:sso::000000000000:application/ssoins-1234567890123456/apl-9999999999999999\"]}")));
    }

    /**
     * A member two resources share names both: Redshift's {@code SnapshotClusterIdentifier} fills the cluster and the
     * snapshot's cluster, the snapshot's name open when the request gives none. GetClusterCredentials' database is
     * named only when its {@code DbName} is given, though {@code ClusterIdentifier} fills its cluster.
     */
    @Test
    void aMemberTwoResourcesShareNamesBoth() {
        String redshift = "arn:aws:redshift:us-east-1:000000000000:";
        assertEquals(List.of(redshift + "cluster:prod", redshift + "snapshot:prod/backup-1"),
                of("redshift:DeleteClusterSnapshot",
                        form("Action=DeleteClusterSnapshot&SnapshotClusterIdentifier=prod&SnapshotIdentifier=backup-1")));
        assertEquals(List.of(redshift + "cluster:prod", redshift + "snapshot:prod/*"),
                of("redshift:DeleteClusterSnapshot", form("Action=DeleteClusterSnapshot&SnapshotClusterIdentifier=prod")));
        assertEquals(List.of(redshift + "dbuser:prod/analyst"), of("redshift:GetClusterCredentials",
                form("Action=GetClusterCredentials&ClusterIdentifier=prod&DbUser=analyst")));
        assertEquals(List.of(redshift + "dbname:prod/dev", redshift + "dbuser:prod/analyst"),
                of("redshift:GetClusterCredentials",
                        form("Action=GetClusterCredentials&ClusterIdentifier=prod&DbUser=analyst&DbName=dev")));
    }

    @Test
    void redshiftRestoreFromClusterSnapshotUsesOwnerAccountForSourceSnapshot() {
        String own = "arn:aws:redshift:us-east-1:000000000000:";
        String owner = "arn:aws:redshift:us-east-1:111122223333:";
        assertEquals(List.of(own + "cluster:restored", owner + "snapshot:source/snap"),
                of("redshift:RestoreFromClusterSnapshot",
                        form("Action=RestoreFromClusterSnapshot&ClusterIdentifier=restored&SnapshotIdentifier=snap"
                                + "&SnapshotClusterIdentifier=source&OwnerAccount=111122223333")));
        assertEquals(List.of(own + "cluster:restored", own + "snapshot:source/snap"),
                of("redshift:RestoreFromClusterSnapshot",
                        form("Action=RestoreFromClusterSnapshot&ClusterIdentifier=restored&SnapshotIdentifier=snap"
                                + "&SnapshotClusterIdentifier=source")));
    }

    @Test
    void aRequestNamingNoResourceOrOfANamespaceTheTableDoesNotReadIsCheckedOnStar() {
        assertEquals(List.of(), of("ecr:DescribeRepositories", json("{}")));
        assertEquals(List.of(), of("ecr:GetAuthorizationToken", json("{}")));
        assertEquals(List.of(), of("ecr:NoSuchOperation", json("{\"repositoryName\":\"web\"}")));
        assertEquals(List.of(), of(null, json("{\"repositoryName\":\"web\"}")));
        assertEquals(List.of(), of("lambda:GetFunction", json("{\"FunctionName\":\"f\"}")));
    }

    private static List<String> created(String operation, ContainerRequestContext request) {
        return RequestResources.created(operation, request, "aws", "us-east-1", "000000000000");
    }

    private static List<String> of(String operation, ContainerRequestContext request) {
        return RequestResources.of(operation, request, "aws", "us-east-1", "000000000000");
    }

    private static ContainerRequestContext json(String body) {
        return request("application/x-amz-json-1.1", body);
    }

    /**
     * A REST request's member the table locates in its path is read from the labels of the route it was routed by,
     * one it locates in its query from the query, and one in its body from the body beside them: OpenSearch's
     * {@code DescribeDomain} names its domain in the path, {@code ListTags} its ARN in {@code ?arn}, and
     * {@code UpdateDomainConfig} its domain in the path under a JSON body. With no label given, the path member names
     * nothing, so the request is checked on {@code *}; and a located member is read from where it is alone, a body
     * giving it under its name naming nothing: {@code GetCompatibleVersions} with a domain in its body and none in its
     * query, which its handler answers with every domain's versions, names no domain.
     */
    @Test
    void aRestRequestsPathAndQueryMembersAreReadWhereTheTableLocatesThem() {
        String domain = "arn:aws:es:us-east-1:111111111111:domain/logs";
        assertEquals(List.of(domain), RequestResources.of("es:DescribeDomain", rest("GET", null, Map.of()), "aws",
                "us-east-1", "111111111111", null, Map.of("DomainName", "logs")));
        assertEquals(List.of(), RequestResources.of("es:DescribeDomain", rest("GET", null, Map.of()), "aws",
                "us-east-1", "111111111111", null, Map.of()));
        assertEquals(List.of(domain), RequestResources.of("es:ListTags", rest("GET", null, Map.of("arn", domain)), "aws",
                "us-east-1", "111111111111", null, Map.of()));
        assertEquals(List.of(domain), RequestResources.of("es:UpdateDomainConfig",
                rest("POST", "{\"EngineVersion\":\"OpenSearch_2.11\"}", Map.of()), "aws", "us-east-1", "111111111111",
                null, Map.of("DomainName", "logs")));
        assertEquals(List.of(), RequestResources.of("es:GetCompatibleVersions",
                rest("GET", "{\"DomainName\":\"logs\"}", Map.of()), "aws", "us-east-1", "111111111111", null, Map.of()));
        assertEquals(List.of(domain), RequestResources.of("es:GetCompatibleVersions",
                rest("GET", "{\"DomainName\":\"other\"}", Map.of("domainName", "logs")), "aws", "us-east-1",
                "111111111111", null, Map.of()));
        assertEquals(List.of(domain), RequestResources.of("es:DescribeDomain",
                rest("GET", "{\"DomainName\":\"other\"}", Map.of()), "aws", "us-east-1", "111111111111", null,
                Map.of("DomainName", "logs")));
    }

    /**
     * A REST request sent as a form, which a handler that does not refuse one still routes by its path, has the members
     * the table locates read from its path and its query, as a JSON body has: {@code UpdateDomainConfig} names the
     * domain its path gives, not the form's, and with no label given names nothing, a form giving nothing else naming
     * nothing either; {@code GetCompatibleVersions} and {@code ListTags} name the one their query gives, not the form's.
     */
    @Test
    void aRestRequestSentAsAFormIsReadWhereTheTableLocatesItsMembers() {
        String domain = "arn:aws:es:us-east-1:111111111111:domain/logs";
        String form = "application/x-www-form-urlencoded";
        assertEquals(List.of(domain), RequestResources.of("es:UpdateDomainConfig",
                rest("POST", form, "DomainName=other&EngineVersion=OpenSearch_2.11", Map.of()), "aws", "us-east-1",
                "111111111111", null, Map.of("DomainName", "logs")));
        assertEquals(List.of(), RequestResources.of("es:UpdateDomainConfig",
                rest("POST", form, "DomainName=other&EngineVersion=OpenSearch_2.11", Map.of()), "aws", "us-east-1",
                "111111111111", null, Map.of()));
        assertEquals(List.of(), RequestResources.of("es:UpdateDomainConfig", rest("POST", form, "DomainName=other",
                Map.of()), "aws", "us-east-1", "111111111111", null, Map.of()));
        assertEquals(List.of(domain), RequestResources.of("es:GetCompatibleVersions",
                rest("POST", form, "DomainName=other", Map.of("domainName", "logs")), "aws", "us-east-1",
                "111111111111", null, Map.of()));
        assertEquals(List.of(domain), RequestResources.of("es:ListTags",
                rest("POST", form, "ARN=arn:aws:es:us-east-1:111111111111:domain/other", Map.of("arn", domain)),
                "aws", "us-east-1", "111111111111", null, Map.of()));
    }

    /**
     * An Access Analyzer request names its analyzer by the label of its path ({@code GetAnalyzer}) or by its body's
     * {@code analyzerName} ({@code CreateAnalyzer}), and an archive rule by both, {@code CreateArchiveRule} taking the
     * analyzer from its path, where a body's {@code analyzerName} names nothing, and the rule from its body.
     */
    @Test
    void anAccessAnalyzerRequestNamesTheAnalyzerItsPathOrBodyGives() {
        String analyzer = "arn:aws:access-analyzer:us-east-1:111111111111:analyzer/a";
        assertEquals(List.of(analyzer), RequestResources.of("access-analyzer:GetAnalyzer", rest("GET", null, Map.of()),
                "aws", "us-east-1", "111111111111", null, Map.of("analyzerName", "a")));
        assertEquals(List.of(analyzer), RequestResources.of("access-analyzer:CreateAnalyzer",
                rest("PUT", "{\"analyzerName\":\"a\",\"type\":\"ACCOUNT\"}", Map.of()), "aws", "us-east-1",
                "111111111111", null, Map.of()));
        assertEquals(List.of(analyzer + "/archive-rule/r"), RequestResources.of("access-analyzer:CreateArchiveRule",
                rest("PUT", "{\"analyzerName\":\"other\",\"ruleName\":\"r\"}", Map.of()), "aws", "us-east-1",
                "111111111111", null, Map.of("analyzerName", "a")));
    }

    private static ContainerRequestContext rest(String method, String body, Map<String, String> query) {
        return rest(method, "application/json", body, query);
    }

    private static ContainerRequestContext rest(String method, String mediaType, String body,
                                                Map<String, String> query) {
        ContainerRequestContext request = body == null ? mock(ContainerRequestContext.class) : request(mediaType, body);
        when(request.getMethod()).thenReturn(method);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        query.forEach(parameters::add);
        when(uriInfo.getQueryParameters()).thenReturn(parameters);
        when(request.getUriInfo()).thenReturn(uriInfo);
        return request;
    }

    private static ContainerRequestContext form(String body) {
        return request("application/x-www-form-urlencoded", body);
    }

    /** The JSON body as EventBridge's JSON 1.1 handler serves it. */
    private static ContainerRequestContext v1(String body) {
        return RequestResourceMembersTest.servedBy(json(body), "events");
    }

    /** The JSON body as EventBridge V2's SDKs send it, in CBOR to the RPC v2 handler that serves it. */
    private static ContainerRequestContext v2(String body) {
        try {
            return RequestResourceMembersTest.servedBy(request("application/cbor",
                    CBOR.writeValueAsBytes(new ObjectMapper().readTree(body))), "eventsv2");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ContainerRequestContext request(String mediaType, String body) {
        return request(mediaType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static ContainerRequestContext request(String mediaType, byte[] bytes) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getMediaType()).thenReturn(MediaType.valueOf(mediaType));
        when(request.getEntityStream()).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        return request;
    }
}
