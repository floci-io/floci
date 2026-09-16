package io.github.hectorvent.floci.services.ssoadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.ssoadmin.model.Assignment;
import io.github.hectorvent.floci.services.ssoadmin.model.AssignmentOperation;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationAssignment;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSet;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSetProvisioningOperation;
import io.github.hectorvent.floci.services.ssoadmin.model.SsoApplication;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class SsoAdminJsonHandler {
    private final SsoAdminService service;
    private final ObjectMapper mapper;

    @Inject
    public SsoAdminJsonHandler(SsoAdminService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String callerAccountId, String region) {
        return switch (action) {
            case "ListInstances" -> listInstances(callerAccountId);
            case "CreateInstance" -> createInstance(request, callerAccountId, region);
            case "UpdateInstance" -> updateInstance(request, callerAccountId);
            case "DescribeInstance" -> describeInstance(request);
            case "DeleteInstance" -> deleteInstance(request, callerAccountId);
            case "CreateInstanceAccessControlAttributeConfiguration" -> createInstanceAccessControlAttributeConfiguration(request);
            case "DescribeInstanceAccessControlAttributeConfiguration" -> describeInstanceAccessControlAttributeConfiguration(request);
            case "UpdateInstanceAccessControlAttributeConfiguration" -> updateInstanceAccessControlAttributeConfiguration(request);
            case "DeleteInstanceAccessControlAttributeConfiguration" -> deleteInstanceAccessControlAttributeConfiguration(request);
            case "CreateTrustedTokenIssuer" -> createTrustedTokenIssuer(request, callerAccountId);
            case "UpdateTrustedTokenIssuer" -> updateTrustedTokenIssuer(request);
            case "DescribeTrustedTokenIssuer" -> describeTrustedTokenIssuer(request);
            case "ListTrustedTokenIssuers" -> listTrustedTokenIssuers(request);
            case "DeleteTrustedTokenIssuer" -> deleteTrustedTokenIssuer(request);
            case "AddRegion" -> addRegion(request);
            case "RemoveRegion" -> removeRegion(request, region);
            case "DescribeRegion" -> describeRegion(request);
            case "ListRegions" -> listRegions(request);
            case "ListTagsForResource" -> listTagsForResource(request);
            case "TagResource" -> tagResource(request);
            case "UntagResource" -> untagResource(request);
            case "CreateApplication" -> createApplication(request, callerAccountId, region);
            case "UpdateApplication" -> updateApplication(request);
            case "DescribeApplication" -> describeApplication(request);
            case "ListApplications" -> listApplications(request, callerAccountId);
            case "CreateApplicationAssignment" -> createApplicationAssignment(request);
            case "DescribeApplicationAssignment" -> describeApplicationAssignment(request);
            case "DescribeApplicationProvider" -> describeApplicationProvider(request, region);
            case "ListApplicationProviders" -> listApplicationProviders(request, region);
            case "ListApplicationAssignments" -> listApplicationAssignments(request);
            case "ListApplicationAssignmentsForPrincipal" -> listApplicationAssignmentsForPrincipal(request, callerAccountId);
            case "DeleteApplication" -> deleteApplication(request);
            case "GetApplicationAssignmentConfiguration" -> getApplicationAssignmentConfiguration(request);
            case "PutApplicationAssignmentConfiguration" -> putApplicationAssignmentConfiguration(request);
            case "PutApplicationAccessScope" -> putApplicationAccessScope(request);
            case "GetApplicationAccessScope" -> getApplicationAccessScope(request);
            case "ListApplicationAccessScopes" -> listApplicationAccessScopes(request);
            case "DeleteApplicationAccessScope" -> deleteApplicationAccessScope(request);
            case "DeleteApplicationAssignment" -> deleteApplicationAssignment(request);
            case "GetApplicationAuthenticationMethod" -> getApplicationAuthenticationMethod(request);
            case "ListApplicationAuthenticationMethods" -> listApplicationAuthenticationMethods(request);
            case "PutApplicationAuthenticationMethod" -> putApplicationAuthenticationMethod(request);
            case "DeleteApplicationAuthenticationMethod" -> deleteApplicationAuthenticationMethod(request);
            case "GetApplicationGrant" -> getApplicationGrant(request);
            case "ListApplicationGrants" -> listApplicationGrants(request);
            case "GetApplicationSessionConfiguration" -> getApplicationSessionConfiguration(request);
            case "PutApplicationSessionConfiguration" -> putApplicationSessionConfiguration(request);
            case "PutApplicationGrant" -> putApplicationGrant(request);
            case "DeleteApplicationGrant" -> deleteApplicationGrant(request);
            case "ListPermissionSets" -> listPermissionSets(request);
            case "CreatePermissionSet" -> createPermissionSet(request);
            case "DeletePermissionSet" -> deletePermissionSet(request);
            case "DescribePermissionSet" -> describePermissionSet(request);
            case "UpdatePermissionSet" -> updatePermissionSet(request);
            case "ListManagedPoliciesInPermissionSet" -> listManagedPolicies(request);
            case "AttachManagedPolicyToPermissionSet" -> attachManagedPolicy(request);
            case "AttachCustomerManagedPolicyReferenceToPermissionSet" -> attachCustomerManagedPolicyReference(request);
            case "DetachCustomerManagedPolicyReferenceFromPermissionSet" -> detachCustomerManagedPolicyReference(request);
            case "ListCustomerManagedPolicyReferencesInPermissionSet" -> listCustomerManagedPolicyReferences(request);
            case "DetachManagedPolicyFromPermissionSet" -> detachManagedPolicy(request);
            case "DeleteInlinePolicyFromPermissionSet" -> deleteInlinePolicy(request);
            case "DeletePermissionsBoundaryFromPermissionSet" -> deletePermissionsBoundary(request);
            case "GetInlinePolicyForPermissionSet" -> getInlinePolicy(request);
            case "GetPermissionsBoundaryForPermissionSet" -> getPermissionsBoundary(request);
            case "PutInlinePolicyToPermissionSet" -> putInlinePolicy(request);
            case "PutPermissionsBoundaryToPermissionSet" -> putPermissionsBoundary(request);
            case "ListAccountAssignments" -> listAccountAssignments(request);
            case "ListAccountAssignmentsForPrincipal" -> listAccountAssignmentsForPrincipal(request, callerAccountId);
            case "ProvisionPermissionSet" -> provisionPermissionSet(request);
            case "DescribePermissionSetProvisioningStatus" -> describePermissionSetProvisioningStatus(request);
            case "ListPermissionSetProvisioningStatus" -> listPermissionSetProvisioningStatus(request);
            case "ListPermissionSetsProvisionedToAccount" -> listPermissionSetsProvisionedToAccount(request);
            case "ListAccountsForProvisionedPermissionSet" -> listAccountsForProvisionedPermissionSet(request);
            case "CreateAccountAssignment" -> createAccountAssignment(request);
            case "DeleteAccountAssignment" -> deleteAccountAssignment(request);
            case "DescribeAccountAssignmentCreationStatus" -> describeAssignment(request);
            case "ListAccountAssignmentCreationStatus" -> listAccountAssignmentCreationStatus(request);
            case "DescribeAccountAssignmentDeletionStatus" -> describeAssignmentDeletion(request);
            case "ListAccountAssignmentDeletionStatus" -> listAccountAssignmentDeletionStatus(request);
            default -> throw new AwsException("UnknownOperationException", "Operation " + action + " is not supported.", 400);
        };
    }

    private Response listInstances(String callerAccountId) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode instances = response.putArray("Instances");
        service.listInstances(callerAccountId).forEach(instance -> {
            ObjectNode node = instances.addObject();
            node.put("InstanceArn", instance.instanceArn());
            node.put("IdentityStoreId", instance.identityStoreId());
            var updateState = service.instanceUpdateState(instance);
            if (updateState.name() != null) {
                node.put("Name", updateState.name());
            }
            node.put("OwnerAccountId", instance.ownerAccountId());
            node.put("CreatedDate", instance.createdDateEpochMillis() / 1000.0d);
            node.put("PrimaryRegion", instance.primaryRegion());
            ArrayNode regionNodes = node.putArray("Regions");
            service.listRegionsForInstance(instance).forEach(region -> {
                ObjectNode regionNode = regionNodes.addObject();
                regionNode.put("RegionName", region.regionName());
                regionNode.put("Status", region.status());
                regionNode.put("IsPrimaryRegion", region.primaryRegion());
                regionNode.put("AddedDate", service.regionAddedDateEpochSeconds(region));
            });
            node.put("Status", instance.status());
            if (instance.statusReason() != null) {
                node.put("StatusReason", instance.statusReason());
            }
        });
        return Response.ok(response).build();
    }

    private Response createInstance(JsonNode request, String callerAccountId, String region) {
        var instance = service.createInstance(request, callerAccountId, region);
        return Response.ok(mapper.createObjectNode().put("InstanceArn", instance.instanceArn())).build();
    }

    private Response updateInstance(JsonNode request, String callerAccountId) {
        service.updateInstance(request, callerAccountId);
        return Response.ok().build();
    }

    private Response describeInstance(JsonNode request) {
        var instance = service.describeInstance(request);
        var updateState = service.instanceUpdateState(instance);
        ObjectNode response = mapper.createObjectNode();
        response.put("CreatedDate", instance.createdDateEpochMillis() / 1000.0d);
        response.put("IdentityStoreId", instance.identityStoreId());
        response.put("InstanceArn", instance.instanceArn());
        if (updateState.name() != null) {
            response.put("Name", updateState.name());
        }
        response.put("OwnerAccountId", instance.ownerAccountId());
        response.put("PermissionSetsEnabled", updateState.permissionSetsEnabled());
        ObjectNode encryption = response.putObject("EncryptionConfigurationDetails");
        encryption.put("EncryptionStatus", updateState.encryptionStatus());
        encryption.put("KeyType", updateState.keyType());
        if (updateState.kmsKeyArn() != null) {
            encryption.put("KmsKeyArn", updateState.kmsKeyArn());
        }
        if (updateState.encryptionStatusReason() != null) {
            encryption.put("EncryptionStatusReason", updateState.encryptionStatusReason());
        }
        response.put("Status", instance.status());
        if (instance.statusReason() != null) {
            response.put("StatusReason", instance.statusReason());
        }
        return Response.ok(response).build();
    }

    private Response deleteInstance(JsonNode request, String callerAccountId) {
        service.deleteInstance(request, callerAccountId);
        return Response.ok().build();
    }

    private Response createInstanceAccessControlAttributeConfiguration(JsonNode request) {
        service.createInstanceAccessControlAttributeConfiguration(request);
        return Response.ok().build();
    }

    private Response describeInstanceAccessControlAttributeConfiguration(JsonNode request) {
        var configuration = service.describeInstanceAccessControlAttributeConfiguration(request);
        ObjectNode response = mapper.createObjectNode();
        ObjectNode configurationNode = response.putObject("InstanceAccessControlAttributeConfiguration");
        ArrayNode attributes = configurationNode.putArray("AccessControlAttributes");
        configuration.accessControlAttributes().forEach(attribute -> {
            ObjectNode attributeNode = attributes.addObject();
            attributeNode.put("Key", attribute.key());
            attributeNode.putObject("Value").putArray("Source").add(attribute.source());
        });
        response.put("Status", configuration.status());
        if (configuration.statusReason() != null) {
            response.put("StatusReason", configuration.statusReason());
        }
        return Response.ok(response).build();
    }

    private Response updateInstanceAccessControlAttributeConfiguration(JsonNode request) {
        service.updateInstanceAccessControlAttributeConfiguration(request);
        return Response.ok().build();
    }

    private Response deleteInstanceAccessControlAttributeConfiguration(JsonNode request) {
        service.deleteInstanceAccessControlAttributeConfiguration(request);
        return Response.ok().build();
    }

    private Response createTrustedTokenIssuer(JsonNode request, String callerAccountId) {
        var issuer = service.createTrustedTokenIssuer(request, callerAccountId);
        return Response.ok(mapper.createObjectNode()
                .put("TrustedTokenIssuerArn", issuer.trustedTokenIssuerArn())).build();
    }

    private Response updateTrustedTokenIssuer(JsonNode request) {
        service.updateTrustedTokenIssuer(request);
        return Response.ok().build();
    }

    private Response describeTrustedTokenIssuer(JsonNode request) {
        var issuer = service.describeTrustedTokenIssuer(request);
        ObjectNode response = trustedTokenIssuerMetadataNode(issuer);
        ObjectNode configuration = response.putObject("TrustedTokenIssuerConfiguration");
        var oidc = issuer.oidcJwtConfiguration();
        ObjectNode oidcNode = configuration.putObject("OidcJwtConfiguration");
        oidcNode.put("ClaimAttributePath", oidc.claimAttributePath());
        oidcNode.put("IdentityStoreAttributePath", oidc.identityStoreAttributePath());
        oidcNode.put("IssuerUrl", oidc.issuerUrl());
        oidcNode.put("JwksRetrievalOption", oidc.jwksRetrievalOption());
        return Response.ok(response).build();
    }

    private Response listTrustedTokenIssuers(JsonNode request) {
        var page = service.listTrustedTokenIssuers(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode issuers = response.putArray("TrustedTokenIssuers");
        page.items().forEach(issuer -> issuers.add(trustedTokenIssuerMetadataNode(issuer)));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode trustedTokenIssuerMetadataNode(io.github.hectorvent.floci.services.ssoadmin.model.TrustedTokenIssuer issuer) {
        ObjectNode response = mapper.createObjectNode();
        response.put("Name", issuer.name());
        response.put("TrustedTokenIssuerArn", issuer.trustedTokenIssuerArn());
        response.put("TrustedTokenIssuerType", issuer.trustedTokenIssuerType());
        return response;
    }

    private Response deleteTrustedTokenIssuer(JsonNode request) {
        service.deleteTrustedTokenIssuer(request);
        return Response.ok().build();
    }

    private Response addRegion(JsonNode request) {
        var region = service.addRegion(request);
        return Response.ok(mapper.createObjectNode().put("Status", region.status())).build();
    }

    private Response removeRegion(JsonNode request, String requestRegion) {
        var region = service.removeRegion(request, requestRegion);
        return Response.ok(mapper.createObjectNode().put("Status", region.status())).build();
    }

    private Response describeRegion(JsonNode request) {
        return Response.ok(regionNode(service.describeRegion(request))).build();
    }

    private Response listRegions(JsonNode request) {
        var page = service.listRegions(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode regions = response.putArray("Regions");
        page.items().forEach(region -> regions.add(regionNode(region)));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode regionNode(io.github.hectorvent.floci.services.ssoadmin.model.RegionMetadata region) {
        ObjectNode response = mapper.createObjectNode();
        response.put("AddedDate", service.regionAddedDateEpochSeconds(region));
        response.put("IsPrimaryRegion", region.primaryRegion());
        response.put("RegionName", region.regionName());
        response.put("Status", region.status());
        return response;
    }

    private Response listTagsForResource(JsonNode request) {
        var page = service.listTagsForResource(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode tags = response.putArray("Tags");
        page.items().forEach(tag -> tags.addObject()
                .put("Key", tag.getKey())
                .put("Value", tag.getValue()));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response tagResource(JsonNode request) {
        service.tagResource(request);
        return Response.ok().build();
    }

    private Response untagResource(JsonNode request) {
        service.untagResource(request);
        return Response.ok().build();
    }

    private Response deleteApplication(JsonNode request) {
        service.deleteApplication(SsoAdminService.required(request, "ApplicationArn"));
        return Response.ok().build();
    }

    private Response getApplicationAssignmentConfiguration(JsonNode request) {
        return Response.ok(mapper.createObjectNode()
                .put("AssignmentRequired", service.getApplicationAssignmentConfiguration(request))).build();
    }

    private Response putApplicationAssignmentConfiguration(JsonNode request) {
        service.putApplicationAssignmentConfiguration(request);
        return Response.ok().build();
    }

    private Response putApplicationAccessScope(JsonNode request) {
        service.putApplicationAccessScope(request);
        return Response.ok().build();
    }

    private Response getApplicationAccessScope(JsonNode request) {
        var accessScope = service.getApplicationAccessScope(request);
        ObjectNode response = mapper.createObjectNode();
        response.put("Scope", accessScope.scope());
        ArrayNode targets = response.putArray("AuthorizedTargets");
        accessScope.authorizedTargets().forEach(targets::add);
        return Response.ok(response).build();
    }

    private Response listApplicationAccessScopes(JsonNode request) {
        var page = service.listApplicationAccessScopes(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode scopes = response.putArray("Scopes");
        page.items().forEach(accessScope -> {
            ObjectNode scope = scopes.addObject();
            scope.put("Scope", accessScope.scope());
            ArrayNode targets = scope.putArray("AuthorizedTargets");
            accessScope.authorizedTargets().forEach(targets::add);
        });
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response deleteApplicationAccessScope(JsonNode request) {
        service.deleteApplicationAccessScope(request);
        return Response.ok().build();
    }

    private Response createApplicationAssignment(JsonNode request) {
        service.createApplicationAssignment(request);
        return Response.ok().build();
    }

    private Response describeApplicationAssignment(JsonNode request) {
        ApplicationAssignment assignment = service.describeApplicationAssignment(request);
        ObjectNode response = mapper.createObjectNode();
        response.put("ApplicationArn", assignment.applicationArn());
        response.put("PrincipalId", assignment.principalId());
        response.put("PrincipalType", assignment.principalType());
        return Response.ok(response).build();
    }

    private Response describeApplicationProvider(JsonNode request, String region) {
        return Response.ok(applicationProviderNode(service.describeApplicationProvider(request, region))).build();
    }

    private Response listApplicationProviders(JsonNode request, String region) {
        var page = service.listApplicationProviders(request, region);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode providers = response.putArray("ApplicationProviders");
        page.items().forEach(provider -> providers.add(applicationProviderNode(provider)));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode applicationProviderNode(String applicationProviderArn) {
        ObjectNode response = mapper.createObjectNode();
        response.put("ApplicationProviderArn", applicationProviderArn);
        response.put("FederationProtocol", "OAUTH");
        return response;
    }

    private Response listApplicationAssignments(JsonNode request) {
        var page = service.listApplicationAssignments(request);
        return applicationAssignmentsResponse(page);
    }

    private Response listApplicationAssignmentsForPrincipal(JsonNode request, String callerAccountId) {
        var page = service.listApplicationAssignmentsForPrincipal(request, callerAccountId);
        return applicationAssignmentsResponse(page);
    }

    private Response applicationAssignmentsResponse(PaginatedResult<ApplicationAssignment> page) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode assignments = response.putArray("ApplicationAssignments");
        page.items().forEach(assignment -> {
            ObjectNode node = assignments.addObject();
            node.put("ApplicationArn", assignment.applicationArn());
            node.put("PrincipalId", assignment.principalId());
            node.put("PrincipalType", assignment.principalType());
        });
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response deleteApplicationAssignment(JsonNode request) {
        service.deleteApplicationAssignment(request);
        return Response.ok().build();
    }

    private Response getApplicationAuthenticationMethod(JsonNode request) {
        var method = service.getApplicationAuthenticationMethod(request);
        ObjectNode response = mapper.createObjectNode();
        response.set("AuthenticationMethod", method.authenticationMethod());
        return Response.ok(response).build();
    }

    private Response listApplicationAuthenticationMethods(JsonNode request) {
        var page = service.listApplicationAuthenticationMethods(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode methods = response.putArray("AuthenticationMethods");
        page.items().forEach(method -> {
            ObjectNode item = methods.addObject();
            item.put("AuthenticationMethodType", method.authenticationMethodType());
            item.set("AuthenticationMethod", method.authenticationMethod());
        });
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response putApplicationAuthenticationMethod(JsonNode request) {
        service.putApplicationAuthenticationMethod(request);
        return Response.ok().build();
    }

    private Response deleteApplicationAuthenticationMethod(JsonNode request) {
        service.deleteApplicationAuthenticationMethod(request);
        return Response.ok().build();
    }

    private Response getApplicationGrant(JsonNode request) {
        var grant = service.getApplicationGrant(request);
        return Response.ok(mapper.createObjectNode().set("Grant", grant.grant())).build();
    }

    private Response listApplicationGrants(JsonNode request) {
        var page = service.listApplicationGrants(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode grants = response.putArray("Grants");
        page.items().forEach(grant -> {
            ObjectNode item = grants.addObject();
            item.put("GrantType", grant.grantType());
            item.set("Grant", grant.grant());
        });
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response getApplicationSessionConfiguration(JsonNode request) {
        return Response.ok(mapper.createObjectNode().put("UserBackgroundSessionApplicationStatus",
                service.getApplicationSessionConfiguration(request))).build();
    }

    private Response putApplicationSessionConfiguration(JsonNode request) {
        service.putApplicationSessionConfiguration(request);
        return Response.ok().build();
    }

    private Response putApplicationGrant(JsonNode request) {
        service.putApplicationGrant(request);
        return Response.ok().build();
    }

    private Response deleteApplicationGrant(JsonNode request) {
        service.deleteApplicationGrant(request);
        return Response.ok().build();
    }

    private Response createApplication(JsonNode request, String callerAccountId, String region) {
        SsoApplication application = service.createApplication(request, callerAccountId, region);
        ObjectNode response = mapper.createObjectNode();
        response.put("ApplicationArn", application.applicationArn());
        response.put("IdentityStoreArn", application.identityStoreArn());
        response.put("InstanceArn", application.instanceArn());
        return Response.ok(response).build();
    }

    private Response updateApplication(JsonNode request) {
        service.updateApplication(request);
        return Response.ok().build();
    }

    private Response describeApplication(JsonNode request) {
        return Response.ok(applicationNode(service.describeApplication(request))).build();
    }

    private Response listApplications(JsonNode request, String callerAccountId) {
        var page = service.listApplications(request, callerAccountId);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode applications = response.putArray("Applications");
        page.items().forEach(application -> applications.add(applicationNode(application)));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode applicationNode(SsoApplication application) {
        ObjectNode response = mapper.createObjectNode();
        response.put("ApplicationAccount", application.applicationAccount());
        response.put("ApplicationArn", application.applicationArn());
        response.put("ApplicationProviderArn", application.applicationProviderArn());
        response.put("CreatedDate", application.createdDateEpochMillis() / 1000.0d);
        response.put("CreatedFrom", application.createdFrom());
        if (application.description() != null) {
            response.put("Description", application.description());
        }
        response.put("IdentityStoreArn", application.identityStoreArn());
        response.put("InstanceArn", application.instanceArn());
        response.put("Name", application.name());
        if (application.portalOptions() != null) {
            ObjectNode portal = response.putObject("PortalOptions");
            if (application.portalOptions().visibility() != null) {
                portal.put("Visibility", application.portalOptions().visibility());
            }
            if (application.portalOptions().signInOptions() != null) {
                ObjectNode signIn = portal.putObject("SignInOptions");
                signIn.put("Origin", application.portalOptions().signInOptions().origin());
                if (application.portalOptions().signInOptions().applicationUrl() != null) {
                    signIn.put("ApplicationUrl", application.portalOptions().signInOptions().applicationUrl());
                }
            }
        }
        response.put("Status", application.status());
        return response;
    }

    private Response listPermissionSets(JsonNode request) {
        var page = service.listPermissionSets(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode arns = response.putArray("PermissionSets");
        page.items().forEach(p -> arns.add(p.arn()));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response createPermissionSet(JsonNode request) {
        PermissionSet p = service.createPermissionSet(request);
        ObjectNode response = mapper.createObjectNode();
        response.set("PermissionSet", permissionSetNode(p));
        return Response.ok(response).build();
    }

    private Response deletePermissionSet(JsonNode request) {
        service.deletePermissionSet(
                SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "PermissionSetArn"));
        return Response.ok().build();
    }

    private Response describePermissionSet(JsonNode request) {
        PermissionSet p = service.getPermissionSet(
                SsoAdminService.required(request, "InstanceArn"), SsoAdminService.required(request, "PermissionSetArn"));
        ObjectNode response = mapper.createObjectNode();
        response.set("PermissionSet", permissionSetNode(p));
        return Response.ok(response).build();
    }

    private Response updatePermissionSet(JsonNode request) {
        service.updatePermissionSet(request);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response listManagedPolicies(JsonNode request) {
        var page = service.listManagedPolicies(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode policies = response.putArray("AttachedManagedPolicies");
        page.items().forEach(policy -> policies.addObject()
                .put("Arn", policy.getKey()).put("Name", policy.getValue()));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response attachManagedPolicy(JsonNode request) {
        service.attachPolicy(SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "PermissionSetArn"), SsoAdminService.required(request, "ManagedPolicyArn"));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response attachCustomerManagedPolicyReference(JsonNode request) {
        service.attachCustomerManagedPolicyReference(request);
        return Response.ok().build();
    }

    private Response detachCustomerManagedPolicyReference(JsonNode request) {
        service.detachCustomerManagedPolicyReference(request);
        return Response.ok().build();
    }

    private Response listCustomerManagedPolicyReferences(JsonNode request) {
        var page = service.listCustomerManagedPolicyReferences(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode references = response.putArray("CustomerManagedPolicyReferences");
        page.items().forEach(reference -> references.addObject()
                .put("Name", reference.name())
                .put("Path", reference.path()));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response detachManagedPolicy(JsonNode request) {
        service.detachPolicy(SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "PermissionSetArn"), SsoAdminService.required(request, "ManagedPolicyArn"));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response deleteInlinePolicy(JsonNode request) {
        service.deleteInlinePolicy(SsoAdminService.required(request, "InstanceArn"), SsoAdminService.required(request, "PermissionSetArn"));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response deletePermissionsBoundary(JsonNode request) {
        service.deletePermissionsBoundary(
                SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "PermissionSetArn"));
        return Response.ok().build();
    }

    private Response getInlinePolicy(JsonNode request) {
        PermissionSet permissionSet = service.getPermissionSet(
                SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "PermissionSetArn"));
        ObjectNode response = mapper.createObjectNode();
        response.put("InlinePolicy", permissionSet.inlinePolicy() == null ? "" : permissionSet.inlinePolicy());
        return Response.ok(response).build();
    }

    private Response getPermissionsBoundary(JsonNode request) {
        var boundary = service.getPermissionsBoundary(
                SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "PermissionSetArn"));
        ObjectNode response = mapper.createObjectNode();
        ObjectNode boundaryNode = response.putObject("PermissionsBoundary");
        if (boundary.managedPolicyArn() != null) {
            boundaryNode.put("ManagedPolicyArn", boundary.managedPolicyArn());
        } else {
            boundaryNode.putObject("CustomerManagedPolicyReference")
                    .put("Name", boundary.customerManagedPolicyReference().name())
                    .put("Path", boundary.customerManagedPolicyReference().path());
        }
        return Response.ok(response).build();
    }

    private Response putInlinePolicy(JsonNode request) {
        service.putInlinePolicy(SsoAdminService.required(request, "InstanceArn"), SsoAdminService.required(request, "PermissionSetArn"),
                SsoAdminService.required(request, "InlinePolicy"));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response putPermissionsBoundary(JsonNode request) {
        service.putPermissionsBoundary(request);
        return Response.ok().build();
    }

    private Response listAccountAssignments(JsonNode request) {
        var page = service.listAssignments(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode array = response.putArray("AccountAssignments");
        for (Assignment a : page.items()) {
            array.addObject().put("AccountId", a.accountId()).put("PermissionSetArn", a.permissionSetArn())
                    .put("PrincipalId", a.principalId()).put("PrincipalType", a.principalType());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response listAccountAssignmentsForPrincipal(JsonNode request, String callerAccountId) {
        var page = service.listAssignmentsForPrincipal(request, callerAccountId);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode array = response.putArray("AccountAssignments");
        for (Assignment assignment : page.items()) {
            array.addObject()
                    .put("AccountId", assignment.accountId())
                    .put("PermissionSetArn", assignment.permissionSetArn())
                    .put("PrincipalId", assignment.principalId())
                    .put("PrincipalType", assignment.principalType());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response provisionPermissionSet(JsonNode request) {
        PermissionSetProvisioningOperation operation = service.provisionPermissionSet(request);
        ObjectNode response = mapper.createObjectNode();
        ObjectNode status = response.putObject("PermissionSetProvisioningStatus");
        status.put("RequestId", operation.requestId());
        status.put("Status", operation.status());
        status.put("CreatedDate", operation.createdDateEpochMillis() / 1000.0d);
        if (operation.accountId() != null) {
            status.put("AccountId", operation.accountId());
        }
        status.put("PermissionSetArn", operation.permissionSetArn());
        if (operation.failureReason() != null) {
            status.put("FailureReason", operation.failureReason());
        }
        return Response.ok(response).build();
    }

    private Response describePermissionSetProvisioningStatus(JsonNode request) {
        PermissionSetProvisioningOperation operation = service.getPermissionSetProvisioningOperation(
                SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "ProvisionPermissionSetRequestId"));
        ObjectNode response = mapper.createObjectNode();
        ObjectNode status = response.putObject("PermissionSetProvisioningStatus");
        status.put("RequestId", operation.requestId());
        status.put("Status", operation.status());
        status.put("CreatedDate", operation.createdDateEpochMillis() / 1000.0d);
        if (operation.accountId() != null) {
            status.put("AccountId", operation.accountId());
        }
        if (operation.permissionSetArn() != null) {
            status.put("PermissionSetArn", operation.permissionSetArn());
        }
        if (operation.failureReason() != null) {
            status.put("FailureReason", operation.failureReason());
        }
        return Response.ok(response).build();
    }

    private Response listPermissionSetProvisioningStatus(JsonNode request) {
        var page = service.listPermissionSetProvisioningStatus(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode statuses = response.putArray("PermissionSetsProvisioningStatus");
        for (PermissionSetProvisioningOperation operation : page.items()) {
            ObjectNode status = statuses.addObject();
            status.put("RequestId", operation.requestId());
            status.put("Status", operation.status());
            status.put("CreatedDate", operation.createdDateEpochMillis() / 1000.0d);
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response listPermissionSetsProvisionedToAccount(JsonNode request) {
        var page = service.listPermissionSetsProvisionedToAccount(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode permissionSets = response.putArray("PermissionSets");
        page.items().forEach(permissionSets::add);
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response listAccountsForProvisionedPermissionSet(JsonNode request) {
        var page = service.listAccountsForProvisionedPermissionSet(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode accountIds = response.putArray("AccountIds");
        page.items().forEach(accountIds::add);
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response createAccountAssignment(JsonNode request) {
        AssignmentOperation op = service.createAssignment(request);
        ObjectNode response = mapper.createObjectNode();
        response.set("AccountAssignmentCreationStatus", assignmentOperationNode(op));
        return Response.ok(response).build();
    }

    private Response deleteAccountAssignment(JsonNode request) {
        var operation = service.deleteAssignment(request);
        ObjectNode response = mapper.createObjectNode();
        ObjectNode status = response.putObject("AccountAssignmentDeletionStatus");
        status.put("RequestId", operation.requestId());
        status.put("Status", operation.status());
        status.put("CreatedDate", operation.createdDateEpochMillis() / 1000.0d);
        status.put("TargetId", operation.accountId());
        status.put("TargetType", "AWS_ACCOUNT");
        status.put("PermissionSetArn", operation.permissionSetArn());
        status.put("PrincipalId", operation.principalId());
        status.put("PrincipalType", operation.principalType());
        if (operation.failureReason() != null) {
            status.put("FailureReason", operation.failureReason());
        }
        return Response.ok(response).build();
    }

    private Response describeAssignment(JsonNode request) {
        AssignmentOperation op = service.getAssignmentOperation(
                SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "AccountAssignmentCreationRequestId"));
        ObjectNode response = mapper.createObjectNode();
        response.set("AccountAssignmentCreationStatus", assignmentOperationNode(op));
        return Response.ok(response).build();
    }

    private Response listAccountAssignmentCreationStatus(JsonNode request) {
        var page = service.listAccountAssignmentCreationStatus(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode statuses = response.putArray("AccountAssignmentsCreationStatus");
        for (AssignmentOperation operation : page.items()) {
            ObjectNode status = statuses.addObject();
            status.put("RequestId", operation.requestId());
            status.put("Status", operation.status());
            status.put("CreatedDate", operation.createdDateEpochMillis() / 1000.0d);
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response describeAssignmentDeletion(JsonNode request) {
        var operation = service.getAssignmentDeletionOperation(
                SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "AccountAssignmentDeletionRequestId"));
        ObjectNode response = mapper.createObjectNode();
        ObjectNode status = response.putObject("AccountAssignmentDeletionStatus");
        status.put("RequestId", operation.requestId());
        status.put("Status", operation.status());
        status.put("CreatedDate", operation.createdDateEpochMillis() / 1000.0d);
        status.put("TargetId", operation.accountId());
        status.put("TargetType", "AWS_ACCOUNT");
        status.put("PermissionSetArn", operation.permissionSetArn());
        status.put("PrincipalId", operation.principalId());
        status.put("PrincipalType", operation.principalType());
        if (operation.failureReason() != null) {
            status.put("FailureReason", operation.failureReason());
        }
        return Response.ok(response).build();
    }

    private Response listAccountAssignmentDeletionStatus(JsonNode request) {
        var page = service.listAccountAssignmentDeletionStatus(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode statuses = response.putArray("AccountAssignmentsDeletionStatus");
        page.items().forEach(operation -> {
            ObjectNode status = statuses.addObject();
            status.put("RequestId", operation.requestId());
            status.put("Status", operation.status());
            status.put("CreatedDate", operation.createdDateEpochMillis() / 1000.0d);
        });
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode permissionSetNode(PermissionSet p) {
        ObjectNode node = mapper.createObjectNode();
        node.put("PermissionSetArn", p.arn());
        node.put("Name", p.name());
        if (p.description() != null) {
            node.put("Description", p.description());
        }
        node.put("SessionDuration", p.sessionDuration());
        return node;
    }

    private ObjectNode assignmentOperationNode(AssignmentOperation op) {
        ObjectNode node = mapper.createObjectNode();
        node.put("RequestId", op.requestId()); node.put("Status", op.status());
        node.put("CreatedDate", op.createdDateEpochMillis() / 1000.0d);
        node.put("TargetId", op.accountId()); node.put("TargetType", "AWS_ACCOUNT");
        node.put("PermissionSetArn", op.permissionSetArn()); node.put("PrincipalId", op.principalId());
        node.put("PrincipalType", op.principalType());
        if (op.failureReason() != null) {
            node.put("FailureReason", op.failureReason());
        }
        return node;
    }
}
