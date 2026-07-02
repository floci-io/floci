package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackInstance;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.model.StackSet;
import io.github.hectorvent.floci.services.cloudformation.model.StackSetOperation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * Handles CloudFormation Query-protocol API calls (form-encoded POST, XML response).
 */
@ApplicationScoped
public class CloudFormationQueryHandler {

    private static final Logger LOG = Logger.getLogger(CloudFormationQueryHandler.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final String CF_NS = "http://cloudformation.amazonaws.com/doc/2010-05-15/";

    private final CloudFormationService cfnService;
    private final StackSetService stackSetService;

    @Inject
    public CloudFormationQueryHandler(CloudFormationService cfnService, StackSetService stackSetService) {
        this.cfnService = cfnService;
        this.stackSetService = stackSetService;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        return switch (action) {
            case "DescribeStacks" -> describeStacks(params, region);
            case "CreateStack" -> createStack(params, region);
            case "UpdateStack" -> updateStack(params, region);
            case "DeleteStack" -> deleteStack(params, region);
            case "CreateChangeSet" -> createChangeSet(params, region);
            case "DescribeChangeSet" -> describeChangeSet(params, region);
            case "ExecuteChangeSet" -> executeChangeSet(params, region);
            case "DeleteChangeSet" -> deleteChangeSet(params, region);
            case "ListChangeSets" -> listChangeSets(params, region);
            case "DescribeStackEvents" -> describeStackEvents(params, region);
            case "DescribeStackResources" -> describeStackResources(params, region);
            case "ListStackResources" -> listStackResources(params, region);
            case "GetTemplate" -> getTemplate(params, region);
            case "ValidateTemplate" -> validateTemplate(params);
            case "ListStacks" -> listStacks(params, region);
            case "ListExports" -> listExports(params, region);
            case "SetStackPolicy" -> Response.ok(emptyResult("SetStackPolicyResponse")).build();
            case "GetStackPolicy" -> Response.ok(emptyResult("GetStackPolicyResponse")).build();
            case "DescribeStackResource" -> describeStackResource(params, region);
            case "CreateStackSet" -> createStackSet(params);
            case "DescribeStackSet" -> describeStackSet(params);
            case "ListStackSets" -> listStackSets();
            case "UpdateStackSet" -> updateStackSet(params);
            case "DeleteStackSet" -> deleteStackSet(params);
            case "CreateStackInstances" -> createStackInstances(params, region);
            case "ListStackInstances" -> listStackInstances(params);
            case "DescribeStackInstance" -> describeStackInstance(params);
            case "DeleteStackInstances" -> deleteStackInstances(params);
            case "ListStackSetOperations" -> listStackSetOperations(params);
            case "DescribeStackSetOperation" -> describeStackSetOperation(params);
            default -> xmlError("UnknownAction", "Action " + action + " is not supported.", 400);
        };
    }

    // ── DescribeStacks ────────────────────────────────────────────────────────

    private Response describeStacks(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        try {
            List<Stack> stacks = cfnService.describeStacks(stackName, region);
            XmlBuilder xml = new XmlBuilder()
                    .start("DescribeStacksResponse", CF_NS)
                    .start("DescribeStacksResult")
                    .start("Stacks");
            for (Stack s : stacks) {
                xml.raw(stackToXml(s));
            }
            xml.end("Stacks").end("DescribeStacksResult")
               .raw(AwsQueryResponse.responseMetadata())
               .end("DescribeStacksResponse");
            return Response.ok(xml.build()).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    // ── CreateStack ───────────────────────────────────────────────────────────

    private Response createStack(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        String templateBody = params.getFirst("TemplateBody");
        String templateUrl = params.getFirst("TemplateURL");
        Map<String, String> parameters = extractParameters(params);
        List<String> capabilities = extractList(params, "Capabilities.member.");
        Map<String, String> tags = extractTags(params);

        cfnService.createChangeSet(stackName, "initial-create", "CREATE",
                templateBody, templateUrl, parameters, capabilities, tags, region);
        awaitExecution(cfnService.executeChangeSet(stackName, "initial-create", region));

        Stack stack = cfnService.describeStacks(stackName, region).get(0);
        String xml = new XmlBuilder()
                .start("CreateStackResponse", CF_NS)
                .start("CreateStackResult")
                .elem("StackId", stack.getStackId())
                .end("CreateStackResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("CreateStackResponse")
                .build();
        return Response.ok(xml).type("text/xml").build();
    }

    // ── UpdateStack ───────────────────────────────────────────────────────────

    private Response updateStack(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        String templateBody = params.getFirst("TemplateBody");
        String templateUrl = params.getFirst("TemplateURL");
        Map<String, String> parameters = extractParameters(params);
        List<String> capabilities = extractList(params, "Capabilities.member.");

        ChangeSet cs = cfnService.createChangeSet(stackName, "update-" + UUID.randomUUID().toString().substring(0, 8),
                "UPDATE", templateBody, templateUrl, parameters, capabilities, Map.of(), region);
        awaitExecution(cfnService.executeChangeSet(stackName, cs.getChangeSetName(), region));

        Stack stack = cfnService.describeStacks(stackName, region).get(0);
        String xml = new XmlBuilder()
                .start("UpdateStackResponse", CF_NS)
                .start("UpdateStackResult")
                .elem("StackId", stack.getStackId())
                .end("UpdateStackResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("UpdateStackResponse")
                .build();
        return Response.ok(xml).type("text/xml").build();
    }

    // ── DeleteStack ───────────────────────────────────────────────────────────

    private Response deleteStack(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        cfnService.deleteStack(stackName, region);
        String xml = new XmlBuilder()
                .start("DeleteStackResponse", CF_NS)
                .raw(AwsQueryResponse.responseMetadata())
                .end("DeleteStackResponse")
                .build();
        return Response.ok(xml).type("text/xml").build();
    }

    // ── CreateChangeSet ───────────────────────────────────────────────────────

    private Response createChangeSet(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        String changeSetName = params.getFirst("ChangeSetName");
        String changeSetType = params.getFirst("ChangeSetType");
        String templateBody = params.getFirst("TemplateBody");
        String templateUrl = params.getFirst("TemplateURL");
        Map<String, String> parameters = extractParameters(params);
        List<String> capabilities = extractList(params, "Capabilities.member.");
        Map<String, String> tags = extractTags(params);

        ChangeSet cs = cfnService.createChangeSet(stackName, changeSetName, changeSetType,
                templateBody, templateUrl, parameters, capabilities, tags, region);

        String xml = new XmlBuilder()
                .start("CreateChangeSetResponse", CF_NS)
                .start("CreateChangeSetResult")
                .elem("Id", cs.getChangeSetId())
                .elem("StackId", cs.getStackId())
                .end("CreateChangeSetResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("CreateChangeSetResponse")
                .build();
        return Response.ok(xml).type("text/xml").build();
    }

    // ── DescribeChangeSet ─────────────────────────────────────────────────────

    private Response describeChangeSet(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        String changeSetName = params.getFirst("ChangeSetName");
        try {
            ChangeSet cs = cfnService.describeChangeSet(stackName, changeSetName, region);
            String xml = new XmlBuilder()
                    .start("DescribeChangeSetResponse", CF_NS)
                    .start("DescribeChangeSetResult")
                    .elem("ChangeSetId", cs.getChangeSetId())
                    .elem("ChangeSetName", cs.getChangeSetName())
                    .elem("StackId", cs.getStackId())
                    .elem("StackName", cs.getStackName())
                    .elem("Status", cs.getStatus())
                    .elem("ExecutionStatus", cs.getExecutionStatus())
                    .raw("<Changes/>")
                    .elem("CreationTime", ISO.format(cs.getCreationTime()))
                    .end("DescribeChangeSetResult")
                    .raw(AwsQueryResponse.responseMetadata())
                    .end("DescribeChangeSetResponse")
                    .build();
            return Response.ok(xml).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    // ── ExecuteChangeSet ──────────────────────────────────────────────────────

    private Response executeChangeSet(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        String changeSetName = params.getFirst("ChangeSetName");
        try {
            cfnService.executeChangeSet(stackName, changeSetName, region);
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
        String xml = new XmlBuilder()
                .start("ExecuteChangeSetResponse", CF_NS)
                .raw("<ExecuteChangeSetResult/>")
                .raw(AwsQueryResponse.responseMetadata())
                .end("ExecuteChangeSetResponse")
                .build();
        return Response.ok(xml).type("text/xml").build();
    }

    // ── DeleteChangeSet ───────────────────────────────────────────────────────

    private Response deleteChangeSet(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        String changeSetName = params.getFirst("ChangeSetName");
        try {
            cfnService.deleteChangeSet(stackName, changeSetName, region);
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
        String xml = new XmlBuilder()
                .start("DeleteChangeSetResponse", CF_NS)
                .raw("<DeleteChangeSetResult/>")
                .raw(AwsQueryResponse.responseMetadata())
                .end("DeleteChangeSetResponse")
                .build();
        return Response.ok(xml).type("text/xml").build();
    }

    // ── ListChangeSets ────────────────────────────────────────────────────────

    private Response listChangeSets(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        XmlBuilder xml = new XmlBuilder()
                .start("ListChangeSetsResponse", CF_NS)
                .start("ListChangeSetsResult")
                .start("Summaries");
        try {
            List<Stack> stacks = cfnService.describeStacks(stackName, region);
            if (!stacks.isEmpty()) {
                for (ChangeSet cs : stacks.get(0).getChangeSets().values()) {
                    xml.start("member")
                       .elem("ChangeSetName", cs.getChangeSetName())
                       .elem("ChangeSetId", cs.getChangeSetId())
                       .elem("Status", cs.getStatus())
                       .elem("ExecutionStatus", cs.getExecutionStatus())
                       .end("member");
                }
            }
        } catch (Exception e) {
            LOG.debugv("Stack not found for listChangeSets: {0}", e.getMessage());
        }
        xml.end("Summaries").end("ListChangeSetsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("ListChangeSetsResponse");
        return Response.ok(xml.build()).type("text/xml").build();
    }

    // ── DescribeStackEvents ───────────────────────────────────────────────────

    private Response describeStackEvents(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        try {
            List<StackEvent> events = cfnService.describeStackEvents(stackName, region);
            XmlBuilder xml = new XmlBuilder()
                    .start("DescribeStackEventsResponse", CF_NS)
                    .start("DescribeStackEventsResult")
                    .start("StackEvents");
            for (StackEvent e : events) {
                xml.start("member")
                   .elem("EventId", e.getEventId())
                   .elem("StackId", e.getStackId())
                   .elem("StackName", e.getStackName())
                   .elem("LogicalResourceId", e.getLogicalResourceId())
                   .elem("PhysicalResourceId", e.getPhysicalResourceId())
                   .elem("ResourceType", e.getResourceType())
                   .elem("ResourceStatus", e.getResourceStatus())
                   .elem("ResourceStatusReason", e.getResourceStatusReason())
                   .elem("Timestamp", ISO.format(e.getTimestamp()))
                   .end("member");
            }
            xml.end("StackEvents").end("DescribeStackEventsResult")
               .raw(AwsQueryResponse.responseMetadata())
               .end("DescribeStackEventsResponse");
            return Response.ok(xml.build()).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    // ── DescribeStackResources ────────────────────────────────────────────────

    private Response describeStackResources(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        try {
            List<StackResource> resources = cfnService.describeStackResources(stackName, region);
            return stackResourcesXml(resources, stackName, region);
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response listStackResources(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        try {
            List<StackResource> resources = cfnService.describeStackResources(stackName, region);
            XmlBuilder xml = new XmlBuilder()
                    .start("ListStackResourcesResponse", CF_NS)
                    .start("ListStackResourcesResult")
                    .start("StackResourceSummaries");
            for (StackResource r : resources) {
                xml.start("member")
                   .elem("LogicalResourceId", r.getLogicalId())
                   .elem("PhysicalResourceId", r.getPhysicalId())
                   .elem("ResourceType", r.getResourceType())
                   .elem("ResourceStatus", r.getStatus())
                   .elem("LastUpdatedTimestamp", ISO.format(r.getTimestamp()))
                   .end("member");
            }
            xml.end("StackResourceSummaries").end("ListStackResourcesResult")
               .raw(AwsQueryResponse.responseMetadata())
               .end("ListStackResourcesResponse");
            return Response.ok(xml.build()).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response describeStackResource(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        String logicalId = params.getFirst("LogicalResourceId");
        try {
            List<StackResource> resources = cfnService.describeStackResources(stackName, region);
            StackResource res = resources.stream()
                    .filter(r -> logicalId.equals(r.getLogicalId()))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Resource " + logicalId + " does not exist in stack " + stackName, 400));
            String xml = new XmlBuilder()
                    .start("DescribeStackResourceResponse", CF_NS)
                    .start("DescribeStackResourceResult")
                    .start("StackResourceDetail")
                    .elem("LogicalResourceId", res.getLogicalId())
                    .elem("PhysicalResourceId", res.getPhysicalId())
                    .elem("ResourceType", res.getResourceType())
                    .elem("ResourceStatus", res.getStatus())
                    .elem("LastUpdatedTimestamp", ISO.format(res.getTimestamp()))
                    .end("StackResourceDetail")
                    .end("DescribeStackResourceResult")
                    .raw(AwsQueryResponse.responseMetadata())
                    .end("DescribeStackResourceResponse")
                    .build();
            return Response.ok(xml).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    // ── GetTemplate ───────────────────────────────────────────────────────────

    private Response getTemplate(MultivaluedMap<String, String> params, String region) {
        String stackName = params.getFirst("StackName");
        try {
            String template = cfnService.getTemplate(stackName, region);
            String xml = new XmlBuilder()
                    .start("GetTemplateResponse", CF_NS)
                    .start("GetTemplateResult")
                    .elem("TemplateBody", template)
                    .end("GetTemplateResult")
                    .raw(AwsQueryResponse.responseMetadata())
                    .end("GetTemplateResponse")
                    .build();
            return Response.ok(xml).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    // ── ValidateTemplate ──────────────────────────────────────────────────────

    private Response validateTemplate(MultivaluedMap<String, String> params) {
        String xml = new XmlBuilder()
                .start("ValidateTemplateResponse", CF_NS)
                .start("ValidateTemplateResult")
                .raw("<Parameters/><Capabilities/><CapabilitiesReason/>")
                .end("ValidateTemplateResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("ValidateTemplateResponse")
                .build();
        return Response.ok(xml).type("text/xml").build();
    }

    // ── ListStacks ────────────────────────────────────────────────────────────

    private Response listStacks(MultivaluedMap<String, String> params, String region) {
        List<Stack> stacks = cfnService.listStacks(region);
        XmlBuilder xml = new XmlBuilder()
                .start("ListStacksResponse", CF_NS)
                .start("ListStacksResult")
                .start("StackSummaries");
        for (Stack s : stacks) {
            xml.start("member")
               .elem("StackId", s.getStackId())
               .elem("StackName", s.getStackName())
               .elem("StackStatus", s.getStatus())
               .elem("CreationTime", ISO.format(s.getCreationTime()))
               .end("member");
        }
        xml.end("StackSummaries").end("ListStacksResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("ListStacksResponse");
        return Response.ok(xml.build()).type("text/xml").build();
    }

    // ── ListExports ─────────────────────────────────────────────────────────

    private Response listExports(MultivaluedMap<String, String> params, String region) {
        var exportEntries = cfnService.listExports(region);
        XmlBuilder xml = new XmlBuilder()
                .start("ListExportsResponse", CF_NS)
                .start("ListExportsResult")
                .start("Exports");
        for (var entry : exportEntries.values()) {
            xml.start("member")
               .elem("ExportingStackId", entry.exportingStackId())
               .elem("Name", entry.name())
               .elem("Value", entry.value())
               .end("member");
        }
        xml.end("Exports").end("ListExportsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("ListExportsResponse");
        return Response.ok(xml.build()).type("text/xml").build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String stackToXml(Stack s) {
        XmlBuilder xml = new XmlBuilder().start("member")
                .elem("StackId", s.getStackId())
                .elem("StackName", s.getStackName())
                .elem("StackStatus", s.getStatus())
                .elem("CreationTime", ISO.format(s.getCreationTime()));
        if (s.getLastUpdatedTime() != null) {
            xml.elem("LastUpdatedTime", ISO.format(s.getLastUpdatedTime()));
        }
        if (s.getStatusReason() != null) {
            xml.elem("StackStatusReason", s.getStatusReason());
        }
        xml.start("Capabilities");
        for (String cap : s.getCapabilities()) {
            xml.elem("member", cap);
        }
        xml.end("Capabilities");
        xml.start("Outputs");
        s.getOutputs().forEach((k, v) -> {
            xml.start("member")
               .elem("OutputKey", k)
               .elem("OutputValue", v);
            String exportName = s.getOutputExportNames().get(k);
            if (exportName != null) {
                xml.elem("ExportName", exportName);
            }
            xml.end("member");
        });
        xml.end("Outputs");
        xml.start("Tags");
        s.getTags().forEach((k, v) ->
                xml.start("member")
                   .elem("Key", k)
                   .elem("Value", v)
                   .end("member"));
        xml.end("Tags");
        xml.end("member");
        return xml.build();
    }

    private Response stackResourcesXml(List<StackResource> resources, String stackName, String region) {
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeStackResourcesResponse", CF_NS)
                .start("DescribeStackResourcesResult")
                .start("StackResources");
        for (StackResource r : resources) {
            xml.start("member")
               .elem("StackName", stackName)
               .elem("LogicalResourceId", r.getLogicalId())
               .elem("PhysicalResourceId", r.getPhysicalId())
               .elem("ResourceType", r.getResourceType())
               .elem("ResourceStatus", r.getStatus())
               .elem("Timestamp", ISO.format(r.getTimestamp()))
               .end("member");
        }
        xml.end("StackResources").end("DescribeStackResourcesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeStackResourcesResponse");
        return Response.ok(xml.build()).type("text/xml").build();
    }

    private Map<String, String> extractParameters(MultivaluedMap<String, String> params) {
        Map<String, String> result = new HashMap<>();
        int i = 1;
        while (true) {
            String key = params.getFirst("Parameters.member." + i + ".ParameterKey");
            String value = params.getFirst("Parameters.member." + i + ".ParameterValue");
            if (key == null) {
                break;
            }
            result.put(key, value != null ? value : "");
            i++;
        }
        return result;
    }

    private Map<String, String> extractTags(MultivaluedMap<String, String> params) {
        Map<String, String> result = new HashMap<>();
        int i = 1;
        while (true) {
            String key = params.getFirst("Tags.member." + i + ".Key");
            String value = params.getFirst("Tags.member." + i + ".Value");
            if (key == null) {
                break;
            }
            result.put(key, value != null ? value : "");
            i++;
        }
        return result;
    }

    private List<String> extractList(MultivaluedMap<String, String> params, String prefix) {
        List<String> result = new ArrayList<>();
        int i = 1;
        while (true) {
            String val = params.getFirst(prefix + i);
            if (val == null) {
                break;
            }
            result.add(val);
            i++;
        }
        return result;
    }

    private String emptyResult(String responseName) {
        return new XmlBuilder()
                .start(responseName, CF_NS)
                .raw(AwsQueryResponse.responseMetadata())
                .end(responseName)
                .build();
    }

    // ── StackSets ─────────────────────────────────────────────────────────────

    private Response createStackSet(MultivaluedMap<String, String> params) {
        try {
            StackSet ss = stackSetService.createStackSet(
                    params.getFirst("StackSetName"),
                    cfnService.resolveTemplateBody(params.getFirst("TemplateBody"), params.getFirst("TemplateURL")),
                    extractParameters(params),
                    extractList(params, "Capabilities.member."),
                    extractTags(params),
                    params.getFirst("Description"));
            String xml = new XmlBuilder()
                    .start("CreateStackSetResponse", CF_NS)
                    .start("CreateStackSetResult")
                    .elem("StackSetId", ss.getStackSetId())
                    .end("CreateStackSetResult")
                    .raw(AwsQueryResponse.responseMetadata())
                    .end("CreateStackSetResponse")
                    .build();
            return Response.ok(xml).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response describeStackSet(MultivaluedMap<String, String> params) {
        try {
            StackSet ss = stackSetService.describeStackSet(params.getFirst("StackSetName"));
            XmlBuilder xml = new XmlBuilder()
                    .start("DescribeStackSetResponse", CF_NS)
                    .start("DescribeStackSetResult")
                    .start("StackSet")
                    .elem("StackSetName", ss.getStackSetName())
                    .elem("StackSetId", ss.getStackSetId())
                    .elem("Status", ss.getStatus())
                    .elem("Description", ss.getDescription())
                    .elem("TemplateBody", ss.getTemplateBody())
                    .elem("PermissionModel", ss.getPermissionModel());
            appendCapabilities(xml, ss.getCapabilities());
            appendParameters(xml, ss.getParameters());
            xml.end("StackSet").end("DescribeStackSetResult")
               .raw(AwsQueryResponse.responseMetadata())
               .end("DescribeStackSetResponse");
            return Response.ok(xml.build()).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response listStackSets() {
        XmlBuilder xml = new XmlBuilder()
                .start("ListStackSetsResponse", CF_NS)
                .start("ListStackSetsResult")
                .start("Summaries");
        for (StackSet ss : stackSetService.listStackSets()) {
            xml.start("member")
               .elem("StackSetName", ss.getStackSetName())
               .elem("StackSetId", ss.getStackSetId())
               .elem("Status", ss.getStatus())
               .elem("Description", ss.getDescription())
               .end("member");
        }
        xml.end("Summaries").end("ListStackSetsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("ListStackSetsResponse");
        return Response.ok(xml.build()).type("text/xml").build();
    }

    private Response updateStackSet(MultivaluedMap<String, String> params) {
        try {
            StackSetOperation op = stackSetService.updateStackSet(
                    params.getFirst("StackSetName"),
                    cfnService.resolveTemplateBody(params.getFirst("TemplateBody"), params.getFirst("TemplateURL")),
                    extractParameters(params),
                    extractList(params, "Capabilities.member."),
                    extractTags(params),
                    params.getFirst("Description"));
            return operationResponse("UpdateStackSet", op.getOperationId());
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response deleteStackSet(MultivaluedMap<String, String> params) {
        try {
            stackSetService.deleteStackSet(params.getFirst("StackSetName"));
            return Response.ok(emptyResult("DeleteStackSetResponse")).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response createStackInstances(MultivaluedMap<String, String> params, String region) {
        try {
            StackSetOperation op = stackSetService.createStackInstances(
                    params.getFirst("StackSetName"),
                    extractList(params, "Accounts.member."),
                    resolveRegions(params, region));
            return operationResponse("CreateStackInstances", op.getOperationId());
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response listStackInstances(MultivaluedMap<String, String> params) {
        try {
            List<StackInstance> list = stackSetService.listStackInstances(
                    params.getFirst("StackSetName"),
                    params.getFirst("StackInstanceAccount"),
                    params.getFirst("StackInstanceRegion"));
            XmlBuilder xml = new XmlBuilder()
                    .start("ListStackInstancesResponse", CF_NS)
                    .start("ListStackInstancesResult")
                    .start("Summaries");
            for (StackInstance inst : list) {
                xml.start("member")
                   .elem("StackSetId", inst.getStackSetId())
                   .elem("Account", inst.getAccount())
                   .elem("Region", inst.getRegion())
                   .elem("StackId", inst.getStackId())
                   .elem("Status", inst.getStatus())
                   .start("StackInstanceStatus")
                     .elem("DetailedStatus", inst.getDetailedStatus())
                   .end("StackInstanceStatus")
                   .elem("StatusReason", inst.getStatusReason())
                   .end("member");
            }
            xml.end("Summaries").end("ListStackInstancesResult")
               .raw(AwsQueryResponse.responseMetadata())
               .end("ListStackInstancesResponse");
            return Response.ok(xml.build()).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response describeStackInstance(MultivaluedMap<String, String> params) {
        try {
            StackInstance inst = stackSetService.describeStackInstance(
                    params.getFirst("StackSetName"),
                    params.getFirst("StackInstanceAccount"),
                    params.getFirst("StackInstanceRegion"));
            String xml = new XmlBuilder()
                    .start("DescribeStackInstanceResponse", CF_NS)
                    .start("DescribeStackInstanceResult")
                    .start("StackInstance")
                      .elem("StackSetId", inst.getStackSetId())
                      .elem("Account", inst.getAccount())
                      .elem("Region", inst.getRegion())
                      .elem("StackId", inst.getStackId())
                      .elem("Status", inst.getStatus())
                      .start("StackInstanceStatus")
                        .elem("DetailedStatus", inst.getDetailedStatus())
                      .end("StackInstanceStatus")
                      .elem("StatusReason", inst.getStatusReason())
                    .end("StackInstance")
                    .end("DescribeStackInstanceResult")
                    .raw(AwsQueryResponse.responseMetadata())
                    .end("DescribeStackInstanceResponse")
                    .build();
            return Response.ok(xml).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response deleteStackInstances(MultivaluedMap<String, String> params) {
        try {
            StackSetOperation op = stackSetService.deleteStackInstances(
                    params.getFirst("StackSetName"),
                    extractList(params, "Accounts.member."),
                    extractList(params, "Regions.member."),
                    Boolean.parseBoolean(params.getFirst("RetainStacks")));
            return operationResponse("DeleteStackInstances", op.getOperationId());
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response listStackSetOperations(MultivaluedMap<String, String> params) {
        try {
            List<StackSetOperation> ops = stackSetService.listStackSetOperations(params.getFirst("StackSetName"));
            XmlBuilder xml = new XmlBuilder()
                    .start("ListStackSetOperationsResponse", CF_NS)
                    .start("ListStackSetOperationsResult")
                    .start("Summaries");
            for (StackSetOperation op : ops) {
                xml.start("member")
                   .elem("OperationId", op.getOperationId())
                   .elem("Action", op.getAction())
                   .elem("Status", op.getStatus())
                   .elem("CreationTimestamp", ISO.format(op.getCreationTimestamp()));
                if (op.getEndTimestamp() != null) {
                    xml.elem("EndTimestamp", ISO.format(op.getEndTimestamp()));
                }
                xml.end("member");
            }
            xml.end("Summaries").end("ListStackSetOperationsResult")
               .raw(AwsQueryResponse.responseMetadata())
               .end("ListStackSetOperationsResponse");
            return Response.ok(xml.build()).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response describeStackSetOperation(MultivaluedMap<String, String> params) {
        try {
            StackSetOperation op = stackSetService.describeStackSetOperation(
                    params.getFirst("StackSetName"), params.getFirst("OperationId"));
            XmlBuilder xml = new XmlBuilder()
                    .start("DescribeStackSetOperationResponse", CF_NS)
                    .start("DescribeStackSetOperationResult")
                    .start("StackSetOperation")
                      .elem("OperationId", op.getOperationId())
                      .elem("Action", op.getAction())
                      .elem("Status", op.getStatus())
                      .elem("CreationTimestamp", ISO.format(op.getCreationTimestamp()));
            if (op.getEndTimestamp() != null) {
                xml.elem("EndTimestamp", ISO.format(op.getEndTimestamp()));
            }
            xml.end("StackSetOperation").end("DescribeStackSetOperationResult")
               .raw(AwsQueryResponse.responseMetadata())
               .end("DescribeStackSetOperationResponse");
            return Response.ok(xml.build()).type("text/xml").build();
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response operationResponse(String action, String operationId) {
        String xml = new XmlBuilder()
                .start(action + "Response", CF_NS)
                .start(action + "Result")
                .elem("OperationId", operationId)
                .end(action + "Result")
                .raw(AwsQueryResponse.responseMetadata())
                .end(action + "Response")
                .build();
        return Response.ok(xml).type("text/xml").build();
    }

    /** Falls back to the request region when no explicit Regions are supplied. */
    private List<String> resolveRegions(MultivaluedMap<String, String> params, String region) {
        List<String> regions = extractList(params, "Regions.member.");
        return regions.isEmpty() ? List.of(region) : regions;
    }

    private void appendCapabilities(XmlBuilder xml, List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return;
        }
        xml.start("Capabilities");
        for (String c : capabilities) {
            xml.elem("member", c);
        }
        xml.end("Capabilities");
    }

    private void appendParameters(XmlBuilder xml, Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return;
        }
        xml.start("Parameters");
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            xml.start("member")
               .elem("ParameterKey", e.getKey())
               .elem("ParameterValue", e.getValue())
               .end("member");
        }
        xml.end("Parameters");
    }

    private void awaitExecution(Future<?> future) {
        try {
            future.get();
        } catch (Exception e) {
            LOG.warnv("Stack execution failed: {0}", e.getMessage());
        }
    }

    private Response xmlError(String code, String message, int status) {
        String xml = new XmlBuilder()
                .start("ErrorResponse", CF_NS)
                .start("Error")
                .elem("Type", "Sender")
                .elem("Code", code)
                .elem("Message", message)
                .end("Error")
                .raw(AwsQueryResponse.responseMetadata())
                .end("ErrorResponse")
                .build();
        return Response.status(status).entity(xml).type("text/xml").build();
    }
}
