package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Expands {@code AWS::Serverless-2016-10-31} SAM resource types into standard CloudFormation
 * resources. Inline policy documents in {@code Policies} are silently ignored — only ARN
 * references are attached as managed policies on the generated execution role.
 */
class SamTransformProcessor {

    private static final Logger LOG = Logger.getLogger(SamTransformProcessor.class);
    private static final String SAM_TRANSFORM = "AWS::Serverless-2016-10-31";

    private final ObjectMapper objectMapper;

    SamTransformProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    boolean hasSamTransform(JsonNode template) {
        JsonNode transform = template.path("Transform");
        if (transform.isTextual()) {
            return SAM_TRANSFORM.equals(transform.asText());
        }
        if (transform.isArray()) {
            for (JsonNode t : transform) {
                if (SAM_TRANSFORM.equals(t.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    JsonNode expandSamTemplate(JsonNode template) {
        if (!hasSamTransform(template)) {
            return template;
        }

        ObjectNode expanded = template.deepCopy();
        expanded.remove("Transform");

        // Globals is a SAM-only top-level section: capture it for merging into resources, then strip
        // it from the emitted CloudFormation template up front so it is removed on every return path
        // (including the early return below when Resources is absent or not an object).
        JsonNode globals = expanded.path("Globals");
        expanded.remove("Globals");

        JsonNode resources = expanded.path("Resources");
        if (!resources.isObject()) {
            return expanded;
        }

        ObjectNode expandedResources = (ObjectNode) resources;
        List<String> samLogicalIds = new ArrayList<>();
        resources.fieldNames().forEachRemaining(logicalId -> {
            String type = resources.path(logicalId).path("Type").asText("");
            if (type.startsWith("AWS::Serverless::")) {
                samLogicalIds.add(logicalId);
            }
        });

        // Collect implicit-API routes from function Api events before the functions are expanded.
        List<ApiRoute> apiRoutes = collectApiRoutes(samLogicalIds, resources);

        // Collect explicit HttpApi routes (Function Events of Type HttpApi bound to an
        // AWS::Serverless::HttpApi via ApiId: {Ref: <logicalId>}) before either side is expanded,
        // so expansion order between the Function and the HttpApi resource doesn't matter.
        List<HttpApiRoute> httpApiRoutes = collectHttpApiRoutes(samLogicalIds, resources);

        for (String logicalId : samLogicalIds) {
            JsonNode resDef = resources.get(logicalId);
            String type = resDef.path("Type").asText();
            JsonNode properties = resDef.path("Properties");

            switch (type) {
                case "AWS::Serverless::Function" ->
                        expandServerlessFunction(logicalId, mergeGlobals(globals, "Function", properties), expandedResources);
                case "AWS::Serverless::SimpleTable" ->
                        expandServerlessSimpleTable(logicalId, mergeGlobals(globals, "SimpleTable", properties), expandedResources);
                case "AWS::Serverless::Api" ->
                        expandServerlessApi(logicalId, mergeGlobals(globals, "Api", properties), expandedResources);
                case "AWS::Serverless::HttpApi" ->
                        expandServerlessHttpApi(logicalId, mergeGlobals(globals, "HttpApi", properties),
                                httpApiRoutes, expandedResources);
                default -> LOG.debugv("Unsupported SAM resource type: {0} ({1})", type, logicalId);
            }
        }

        // Synthesize the implicit REST API (RestApi + resources + methods + AWS_PROXY integrations +
        // deployment + stage + lambda permissions) for functions that declare Api events without an
        // explicit RestApiId — matching SAM's implicit-API behavior so the deployed service is reachable.
        if (!apiRoutes.isEmpty()) {
            generateImplicitApi(apiRoutes, globals(template), expandedResources);
        }

        return expanded;
    }

    private JsonNode globals(JsonNode template) {
        return template.path("Globals");
    }

    private record ApiRoute(String functionLogicalId, String path, String httpMethod) {}

    private List<ApiRoute> collectApiRoutes(List<String> samLogicalIds, JsonNode resources) {
        List<ApiRoute> routes = new ArrayList<>();
        for (String logicalId : samLogicalIds) {
            JsonNode resDef = resources.get(logicalId);
            if (!"AWS::Serverless::Function".equals(resDef.path("Type").asText())) {
                continue;
            }
            JsonNode events = resDef.path("Properties").path("Events");
            if (!events.isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> it = events.fields();
            while (it.hasNext()) {
                JsonNode ev = it.next().getValue();
                if (!"Api".equals(ev.path("Type").asText())) {
                    continue;
                }
                JsonNode p = ev.path("Properties");
                JsonNode restApiId = p.path("RestApiId");
                if (!restApiId.isMissingNode() && !restApiId.isNull()) {
                    continue; // bound to an explicit API — not an implicit-API route (null == implicit)
                }
                JsonNode pathNode = p.path("Path");
                if (!pathNode.isTextual()) {
                    continue; // implicit routing needs a literal path; skip intrinsics (Ref/Fn::Sub)
                }
                JsonNode methodNode = p.path("Method");
                String method = methodNode.isTextual() ? methodNode.asText() : "ANY";
                routes.add(new ApiRoute(logicalId, pathNode.asText(), method));
            }
        }
        return routes;
    }

    private record HttpApiRoute(String functionLogicalId, String apiLogicalId, String path, String httpMethod) {}

    /**
     * Collects HttpApi-typed Function events bound to an explicit {@code AWS::Serverless::HttpApi}
     * via {@code ApiId: {Ref: <logicalId>}}. Events with no {@code ApiId} (SAM's implicit HttpApi,
     * auto-created rather than declared as its own resource) are intentionally not handled here —
     * that is a separate expansion path, mirroring how {@link #collectApiRoutes} and
     * {@link #generateImplicitApi} are already split out from {@link #expandServerlessApi}'s
     * explicit-REST-API handling.
     */
    private List<HttpApiRoute> collectHttpApiRoutes(List<String> samLogicalIds, JsonNode resources) {
        List<HttpApiRoute> routes = new ArrayList<>();
        for (String logicalId : samLogicalIds) {
            JsonNode resDef = resources.get(logicalId);
            if (!"AWS::Serverless::Function".equals(resDef.path("Type").asText())) {
                continue;
            }
            JsonNode events = resDef.path("Properties").path("Events");
            if (!events.isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> it = events.fields();
            while (it.hasNext()) {
                JsonNode ev = it.next().getValue();
                if (!"HttpApi".equals(ev.path("Type").asText())) {
                    continue;
                }
                JsonNode p = ev.path("Properties");
                String apiLogicalId = p.path("ApiId").path("Ref").asText(null);
                if (apiLogicalId == null) {
                    continue; // no explicit ApiId (implicit HttpApi) — not handled by this path
                }
                JsonNode pathNode = p.path("Path");
                if (!pathNode.isTextual()) {
                    continue; // needs a literal path; skip intrinsics (Ref/Fn::Sub)
                }
                JsonNode methodNode = p.path("Method");
                String method = methodNode.isTextual() ? methodNode.asText() : "ANY";
                routes.add(new HttpApiRoute(logicalId, apiLogicalId, pathNode.asText(), method));
            }
        }
        return routes;
    }

    private void expandServerlessHttpApi(String logicalId, JsonNode properties,
                                         List<HttpApiRoute> allRoutes, ObjectNode resources) {
        resources.remove(logicalId);

        ObjectNode apiDef = objectMapper.createObjectNode();
        apiDef.put("Type", "AWS::ApiGatewayV2::Api");
        ObjectNode apiProps = objectMapper.createObjectNode();
        JsonNode name = properties.path("Name");
        apiProps.set("Name", !name.isMissingNode() ? name.deepCopy() : objectMapper.getNodeFactory().textNode(logicalId));
        apiProps.put("ProtocolType", "HTTP");
        copyIfPresent(properties, "Description", apiProps);
        apiDef.set("Properties", apiProps);
        resources.set(logicalId, apiDef);

        // Auth.Authorizers -> one AWS::ApiGatewayV2::Authorizer per entry, keyed by SAM authorizer
        // name so routes referencing Auth.DefaultAuthorizer (the only form handled here — per-route
        // Auth overrides are not expanded) can resolve the logical id to Ref.
        Map<String, String> authorizerLogicalIdsByName = new java.util.LinkedHashMap<>();
        JsonNode auth = properties.path("Auth");
        JsonNode authorizers = auth.path("Authorizers");
        if (authorizers.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = authorizers.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String authorizerName = entry.getKey();
                String authorizerLogicalId = uniqueId(logicalId + sanitize(authorizerName) + "Authorizer", resources);
                resources.set(authorizerLogicalId, buildHttpApiAuthorizer(logicalId, authorizerName, entry.getValue()));
                authorizerLogicalIdsByName.put(authorizerName, authorizerLogicalId);
            }
        }
        String defaultAuthorizerName = auth.path("DefaultAuthorizer").asText(null);
        String defaultAuthorizerLogicalId = defaultAuthorizerName != null
                ? authorizerLogicalIdsByName.get(defaultAuthorizerName) : null;

        String stageLogicalId = uniqueId(logicalId + "ApiDefaultStage", resources);
        ObjectNode stageDef = objectMapper.createObjectNode();
        stageDef.put("Type", "AWS::ApiGatewayV2::Stage");
        ObjectNode stageProps = objectMapper.createObjectNode();
        stageProps.set("ApiId", ref(logicalId));
        stageProps.put("StageName", "$default");
        stageProps.put("AutoDeploy", true);
        stageDef.set("Properties", stageProps);
        resources.set(stageLogicalId, stageDef);

        for (HttpApiRoute route : allRoutes) {
            if (!logicalId.equals(route.apiLogicalId())) {
                continue;
            }
            expandHttpApiRoute(logicalId, route, defaultAuthorizerLogicalId, resources);
        }
    }

    private ObjectNode buildHttpApiAuthorizer(String apiLogicalId, String authorizerName, JsonNode samAuthorizer) {
        ObjectNode authDef = objectMapper.createObjectNode();
        authDef.put("Type", "AWS::ApiGatewayV2::Authorizer");
        ObjectNode authProps = objectMapper.createObjectNode();
        authProps.set("ApiId", ref(apiLogicalId));
        authProps.put("Name", authorizerName);
        authProps.put("AuthorizerType", "JWT");

        JsonNode jwtConfig = samAuthorizer.path("JwtConfiguration");
        ArrayNode identitySource = objectMapper.createArrayNode();
        identitySource.add("$request.header.Authorization");
        authProps.set("IdentitySource", identitySource);

        if (jwtConfig.isObject()) {
            ObjectNode jwtProps = objectMapper.createObjectNode();
            JsonNode issuer = jwtConfig.path("issuer");
            if (!issuer.isMissingNode()) {
                jwtProps.set("Issuer", issuer.deepCopy());
            }
            JsonNode audience = jwtConfig.path("audience");
            if (audience.isArray()) {
                jwtProps.set("Audience", audience.deepCopy());
            }
            authProps.set("JwtConfiguration", jwtProps);
        }

        authDef.set("Properties", authProps);
        return authDef;
    }

    private void expandHttpApiRoute(String apiLogicalId, HttpApiRoute route,
                                    String defaultAuthorizerLogicalId, ObjectNode resources) {
        String integrationLogicalId = uniqueId(
                apiLogicalId + "Integration" + sanitize(route.functionLogicalId()), resources);
        ObjectNode integDef = objectMapper.createObjectNode();
        integDef.put("Type", "AWS::ApiGatewayV2::Integration");
        ObjectNode integProps = objectMapper.createObjectNode();
        integProps.set("ApiId", ref(apiLogicalId));
        integProps.put("IntegrationType", "AWS_PROXY");
        integProps.set("IntegrationUri", lambdaInvokeUri(route.functionLogicalId()));
        integProps.put("PayloadFormatVersion", "2.0");
        integDef.set("Properties", integProps);
        resources.set(integrationLogicalId, integDef);

        String routeKey = route.httpMethod().toUpperCase() + " " + route.path();
        String routeLogicalId = uniqueId(
                apiLogicalId + "Route" + sanitize(route.functionLogicalId()) + sanitize(route.path()), resources);
        ObjectNode routeDef = objectMapper.createObjectNode();
        routeDef.put("Type", "AWS::ApiGatewayV2::Route");
        ObjectNode routeProps = objectMapper.createObjectNode();
        routeProps.set("ApiId", ref(apiLogicalId));
        routeProps.put("RouteKey", routeKey);
        if (defaultAuthorizerLogicalId != null) {
            routeProps.put("AuthorizationType", "JWT");
            routeProps.set("AuthorizerId", ref(defaultAuthorizerLogicalId));
        } else {
            routeProps.put("AuthorizationType", "NONE");
        }
        ObjectNode join = objectMapper.createObjectNode();
        ArrayNode joinArgs = objectMapper.createArrayNode();
        joinArgs.add("/");
        ArrayNode joinValues = objectMapper.createArrayNode();
        joinValues.add("integrations");
        joinValues.add(ref(integrationLogicalId));
        joinArgs.add(joinValues);
        join.set("Fn::Join", joinArgs);
        routeProps.set("Target", join);
        routeDef.set("Properties", routeProps);
        resources.set(routeLogicalId, routeDef);

        String permissionLogicalId = uniqueId(
                route.functionLogicalId() + "HttpApiPermission" + sanitize(apiLogicalId), resources);
        ObjectNode perm = objectMapper.createObjectNode();
        perm.put("Type", "AWS::Lambda::Permission");
        ObjectNode permProps = objectMapper.createObjectNode();
        permProps.set("FunctionName", ref(route.functionLogicalId()));
        permProps.put("Action", "lambda:InvokeFunction");
        permProps.put("Principal", "apigateway.amazonaws.com");
        perm.set("Properties", permProps);
        resources.set(permissionLogicalId, perm);
    }

    private void generateImplicitApi(List<ApiRoute> routes, JsonNode globals, ObjectNode resources) {
        // Collision-safe: reuse "ServerlessRestApi" when free, otherwise a suffixed id, so an existing
        // resource with that logical id is never silently overwritten.
        final String apiId = uniqueId("ServerlessRestApi", resources);

        ObjectNode api = objectMapper.createObjectNode();
        api.put("Type", "AWS::ApiGateway::RestApi");
        ObjectNode apiProps = objectMapper.createObjectNode();
        JsonNode globalName = globals.path("Api").path("Name");
        if (globalName.isMissingNode() || globalName.isNull()) {
            apiProps.put("Name", apiId);
        } else {
            apiProps.set("Name", globalName.deepCopy());
        }
        api.set("Properties", apiProps);
        resources.set(apiId, api);

        Map<String, String> pathToResource = new java.util.LinkedHashMap<>();
        List<String> methodIds = new ArrayList<>();
        java.util.Set<String> permissionFns = new java.util.LinkedHashSet<>();
        java.util.Set<String> seenRoutes = new java.util.LinkedHashSet<>();

        for (ApiRoute r : routes) {
            String method = r.httpMethod().toUpperCase();
            if (!seenRoutes.add(r.path() + " " + method)) {
                continue; // API Gateway allows one method per verb per resource — skip duplicate (path, method)
            }
            String resourceId = ensureResourcePath(apiId, r.path(), pathToResource, resources);

            String methodLogicalId = uniqueId(apiId + "Method" + sanitize(r.path()) + capitalize(method.toLowerCase()), resources);
            ObjectNode m = objectMapper.createObjectNode();
            m.put("Type", "AWS::ApiGateway::Method");
            ObjectNode mp = objectMapper.createObjectNode();
            mp.set("RestApiId", ref(apiId));
            mp.set("ResourceId", resourceId == null ? getAtt(apiId, "RootResourceId") : ref(resourceId));
            mp.put("HttpMethod", method);
            mp.put("AuthorizationType", "NONE");
            ObjectNode integ = objectMapper.createObjectNode();
            integ.put("Type", "AWS_PROXY");
            integ.put("IntegrationHttpMethod", "POST");
            integ.set("Uri", lambdaInvokeUri(r.functionLogicalId()));
            mp.set("Integration", integ);
            m.set("Properties", mp);
            resources.set(methodLogicalId, m);
            methodIds.add(methodLogicalId);
            permissionFns.add(r.functionLogicalId());
        }

        for (String fn : permissionFns) {
            ObjectNode perm = objectMapper.createObjectNode();
            perm.put("Type", "AWS::Lambda::Permission");
            ObjectNode pp = objectMapper.createObjectNode();
            pp.set("FunctionName", ref(fn));
            pp.put("Action", "lambda:InvokeFunction");
            pp.put("Principal", "apigateway.amazonaws.com");
            perm.set("Properties", pp);
            resources.set(uniqueId(fn + "ApiPermission", resources), perm);
        }

        String deploymentId = uniqueId(apiId + "Deployment", resources);
        ObjectNode dep = objectMapper.createObjectNode();
        dep.put("Type", "AWS::ApiGateway::Deployment");
        ObjectNode dp = objectMapper.createObjectNode();
        dp.set("RestApiId", ref(apiId));
        dep.set("Properties", dp);
        ArrayNode dependsOn = objectMapper.createArrayNode();
        methodIds.forEach(dependsOn::add);
        dep.set("DependsOn", dependsOn);
        resources.set(deploymentId, dep);

        ObjectNode stage = objectMapper.createObjectNode();
        stage.put("Type", "AWS::ApiGateway::Stage");
        ObjectNode sp = objectMapper.createObjectNode();
        sp.set("RestApiId", ref(apiId));
        sp.set("DeploymentId", ref(deploymentId));
        sp.put("StageName", "Prod");
        stage.set("Properties", sp);
        resources.set(uniqueId(apiId + "ProdStage", resources), stage);
    }

    /** Builds the API Gateway resource chain for a path; returns the leaf resource logical id (null = root). */
    private String ensureResourcePath(String apiId, String path, Map<String, String> pathToResource, ObjectNode resources) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.isEmpty()) {
            return null;
        }
        String cumulative = "";
        String parentResourceId = null;
        String leaf = null;
        for (String segment : trimmed.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            cumulative = cumulative + "/" + segment;
            String resId = pathToResource.get(cumulative);
            if (resId == null) {
                resId = uniqueId(apiId + "Resource" + sanitize(cumulative), resources);
                ObjectNode res = objectMapper.createObjectNode();
                res.put("Type", "AWS::ApiGateway::Resource");
                ObjectNode rp = objectMapper.createObjectNode();
                rp.set("RestApiId", ref(apiId));
                rp.set("ParentId", parentResourceId == null ? getAtt(apiId, "RootResourceId") : ref(parentResourceId));
                rp.put("PathPart", segment);
                res.set("Properties", rp);
                resources.set(resId, res);
                pathToResource.put(cumulative, resId);
            }
            parentResourceId = resId;
            leaf = resId;
        }
        return leaf;
    }

    private ObjectNode ref(String logicalId) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("Ref", logicalId);
        return n;
    }

    private ObjectNode getAtt(String logicalId, String attribute) {
        ObjectNode n = objectMapper.createObjectNode();
        ArrayNode a = objectMapper.createArrayNode();
        a.add(logicalId);
        a.add(attribute);
        n.set("Fn::GetAtt", a);
        return n;
    }

    /** Two-arg Fn::Sub producing the AWS_PROXY integration URI for a function (the form floci resolves). */
    private ObjectNode lambdaInvokeUri(String functionLogicalId) {
        ObjectNode sub = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add("arn:aws:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/${FnArn}/invocations");
        ObjectNode vars = objectMapper.createObjectNode();
        vars.set("FnArn", getAtt(functionLogicalId, "Arn"));
        arr.add(vars);
        sub.set("Fn::Sub", arr);
        return sub;
    }

    private String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        boolean upper = true;
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                b.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        return b.length() == 0 ? "Root" : b.toString();
    }

    private String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String uniqueId(String base, ObjectNode resources) {
        String id = base;
        int i = 2;
        while (resources.has(id)) {
            id = base + i++;
        }
        return id;
    }

    /**
     * Merges the matching {@code Globals.<section>} block into a resource's own {@code Properties},
     * with the resource's own values taking precedence (per the SAM Globals specification). Returns
     * {@code properties} unchanged when there is no matching globals block.
     *
     * <p>Nested objects (e.g. {@code Environment.Variables}, {@code Tags}) are merged key-wise, so a
     * resource only overrides the individual keys it sets and global entries are preserved — matching
     * SAM's map-merge behavior. Scalar and array-valued properties (e.g. {@code Policies},
     * {@code Layers}) are overridden wholesale; SAM's additive list-append for those is not implemented.
     */
    private JsonNode mergeGlobals(JsonNode globals, String section, JsonNode properties) {
        JsonNode sectionGlobals = globals.path(section);
        if (!sectionGlobals.isObject()) {
            return properties;
        }
        if (!properties.isObject()) {
            return sectionGlobals.deepCopy();
        }
        return deepMerge((ObjectNode) sectionGlobals.deepCopy(), (ObjectNode) properties);
    }

    /**
     * Recursively merges {@code override} into {@code base}: when both sides hold an object for the
     * same key, the objects are merged key-wise; otherwise the override value replaces the base value.
     * {@code base} is mutated and returned.
     */
    private ObjectNode deepMerge(ObjectNode base, ObjectNode override) {
        override.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode overrideValue = entry.getValue();
            JsonNode baseValue = base.get(key);
            if (baseValue != null && baseValue.isObject() && overrideValue.isObject()) {
                base.set(key, deepMerge((ObjectNode) baseValue, (ObjectNode) overrideValue));
            } else {
                base.set(key, overrideValue.deepCopy());
            }
        });
        return base;
    }

    private void expandServerlessFunction(String logicalId, JsonNode properties, ObjectNode resources) {
        resources.remove(logicalId);

        boolean hasExplicitRole = !properties.path("Role").isMissingNode()
                && !properties.path("Role").isNull();
        String roleLogicalId = logicalId + "Role";

        if (!hasExplicitRole) {
            ObjectNode roleResource = createExecutionRole(properties);
            resources.set(roleLogicalId, roleResource);
        }

        ObjectNode lambdaResource = createLambdaFunction(logicalId, roleLogicalId, properties, hasExplicitRole);
        resources.set(logicalId, lambdaResource);

        JsonNode events = properties.path("Events");
        if (events.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> eventFields = events.fields();
            while (eventFields.hasNext()) {
                Map.Entry<String, JsonNode> entry = eventFields.next();
                expandFunctionEvent(logicalId, entry.getKey(), entry.getValue(), resources);
            }
        }
    }

    private ObjectNode createExecutionRole(JsonNode properties) {
        ObjectNode roleDef = objectMapper.createObjectNode();
        roleDef.put("Type", "AWS::IAM::Role");

        ObjectNode roleProps = objectMapper.createObjectNode();

        ObjectNode assumePolicy = objectMapper.createObjectNode();
        assumePolicy.put("Version", "2012-10-17");
        ArrayNode statements = objectMapper.createArrayNode();
        ObjectNode stmt = objectMapper.createObjectNode();
        stmt.put("Effect", "Allow");
        ObjectNode principal = objectMapper.createObjectNode();
        principal.put("Service", "lambda.amazonaws.com");
        stmt.set("Principal", principal);
        stmt.put("Action", "sts:AssumeRole");
        statements.add(stmt);
        assumePolicy.set("Statement", statements);
        roleProps.set("AssumeRolePolicyDocument", assumePolicy);

        ArrayNode managedPolicies = objectMapper.createArrayNode();
        managedPolicies.add("arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole");

        JsonNode userPolicies = properties.path("Policies");
        if (userPolicies.isArray()) {
            for (JsonNode policy : userPolicies) {
                if (policy.isTextual()) {
                    managedPolicies.add(policy.asText());
                }
            }
        } else if (userPolicies.isTextual()) {
            managedPolicies.add(userPolicies.asText());
        }
        roleProps.set("ManagedPolicyArns", managedPolicies);

        roleDef.set("Properties", roleProps);
        return roleDef;
    }

    private ObjectNode createLambdaFunction(String logicalId, String roleLogicalId,
                                            JsonNode properties, boolean hasExplicitRole) {
        ObjectNode lambdaDef = objectMapper.createObjectNode();
        lambdaDef.put("Type", "AWS::Lambda::Function");

        ObjectNode lambdaProps = objectMapper.createObjectNode();

        copyIfPresent(properties, "FunctionName", lambdaProps);
        copyIfPresent(properties, "PackageType", lambdaProps);
        copyIfPresent(properties, "Handler", lambdaProps);
        copyIfPresent(properties, "Runtime", lambdaProps);
        copyIfPresent(properties, "ImageConfig", lambdaProps);

        lambdaProps.set("Code", buildLambdaCode(properties));

        if (hasExplicitRole) {
            lambdaProps.set("Role", properties.get("Role").deepCopy());
        } else {
            ObjectNode roleRef = objectMapper.createObjectNode();
            ArrayNode getAtt = objectMapper.createArrayNode();
            getAtt.add(roleLogicalId);
            getAtt.add("Arn");
            roleRef.set("Fn::GetAtt", getAtt);
            lambdaProps.set("Role", roleRef);
        }

        copyIfPresent(properties, "Timeout", lambdaProps);
        copyIfPresent(properties, "MemorySize", lambdaProps);
        copyIfPresent(properties, "Environment", lambdaProps);
        copyIfPresent(properties, "Layers", lambdaProps);
        copyIfPresent(properties, "Tags", lambdaProps);
        copyIfPresent(properties, "Architectures", lambdaProps);
        copyIfPresent(properties, "ReservedConcurrentExecutions", lambdaProps);
        copyIfPresent(properties, "EphemeralStorage", lambdaProps);

        JsonNode tracing = properties.path("Tracing");
        if (!tracing.isMissingNode()) {
            ObjectNode tracingConfig = objectMapper.createObjectNode();
            tracingConfig.set("Mode", tracing.deepCopy());
            lambdaProps.set("TracingConfig", tracingConfig);
        }

        lambdaDef.set("Properties", lambdaProps);
        return lambdaDef;
    }

    private ObjectNode buildLambdaCode(JsonNode properties) {
        ObjectNode code = objectMapper.createObjectNode();

        JsonNode inlineCode = properties.path("InlineCode");
        if (!inlineCode.isMissingNode()) {
            code.set("ZipFile", inlineCode.deepCopy());
            return code;
        }

        JsonNode codeUri = properties.path("CodeUri");
        if (codeUri.isTextual()) {
            String uri = codeUri.asText();
            if (uri.startsWith("s3://")) {
                String withoutScheme = uri.substring(5);
                int slash = withoutScheme.indexOf('/');
                if (slash > 0) {
                    code.put("S3Bucket", withoutScheme.substring(0, slash));
                    code.put("S3Key", withoutScheme.substring(slash + 1));
                }
            } else {
                code.put("ZipFile", "// SAM local code: " + uri);
            }
            return code;
        }

        if (codeUri.isObject()) {
            JsonNode bucket = codeUri.path("Bucket");
            if (!bucket.isMissingNode()) code.set("S3Bucket", bucket.deepCopy());
            JsonNode key = codeUri.path("Key");
            if (!key.isMissingNode()) code.set("S3Key", key.deepCopy());
            JsonNode version = codeUri.path("Version");
            if (!version.isMissingNode()) code.set("S3ObjectVersion", version.deepCopy());
            return code;
        }

        JsonNode imageUri = properties.path("ImageUri");
        if (!imageUri.isMissingNode()) {
            code.set("ImageUri", imageUri.deepCopy());
            return code;
        }

        code.put("ZipFile", "// No code specified");
        return code;
    }

    private void expandFunctionEvent(String functionLogicalId, String eventName,
                                     JsonNode eventDef, ObjectNode resources) {
        String eventType = eventDef.path("Type").asText("");
        JsonNode eventProps = eventDef.path("Properties");

        switch (eventType) {
            case "SQS", "Kinesis", "DynamoDB" ->
                    expandEventSourceMapping(functionLogicalId, eventName, eventProps, resources);
            case "Api" ->
                    LOG.debugv("SAM Api event for {0}.{1} — handled by Api resource",
                            functionLogicalId, eventName);
            case "HttpApi" ->
                    LOG.debugv("SAM HttpApi event for {0}.{1} — handled by HttpApi resource",
                            functionLogicalId, eventName);
            default ->
                    LOG.debugv("SAM event type {0} for {1}.{2} not expanded",
                            eventType, functionLogicalId, eventName);
        }
    }

    private void expandEventSourceMapping(String functionLogicalId, String eventName,
                                          JsonNode eventProps, ObjectNode resources) {
        String esmLogicalId = functionLogicalId + eventName;

        ObjectNode esmDef = objectMapper.createObjectNode();
        esmDef.put("Type", "AWS::Lambda::EventSourceMapping");

        ObjectNode esmProps = objectMapper.createObjectNode();

        ObjectNode funcRef = objectMapper.createObjectNode();
        funcRef.put("Ref", functionLogicalId);
        esmProps.set("FunctionName", funcRef);

        JsonNode sourceArn = eventProps.path("Queue");
        if (sourceArn.isMissingNode()) {
            sourceArn = eventProps.path("Stream");
        }
        if (!sourceArn.isMissingNode()) {
            esmProps.set("EventSourceArn", sourceArn.deepCopy());
        }

        copyIfPresent(eventProps, "BatchSize", esmProps);
        copyIfPresent(eventProps, "Enabled", esmProps);

        esmDef.set("Properties", esmProps);
        ArrayNode dependsOn = objectMapper.createArrayNode();
        dependsOn.add(functionLogicalId);
        esmDef.set("DependsOn", dependsOn);

        resources.set(esmLogicalId, esmDef);
    }

    private void expandServerlessSimpleTable(String logicalId, JsonNode properties, ObjectNode resources) {
        resources.remove(logicalId);

        ObjectNode tableDef = objectMapper.createObjectNode();
        tableDef.put("Type", "AWS::DynamoDB::Table");

        ObjectNode tableProps = objectMapper.createObjectNode();

        copyIfPresent(properties, "TableName", tableProps);

        JsonNode primaryKey = properties.path("PrimaryKey");
        ArrayNode keySchema = objectMapper.createArrayNode();
        ArrayNode attrDefs = objectMapper.createArrayNode();

        if (primaryKey.isObject()) {
            String pkName = primaryKey.path("Name").asText("id");
            String pkType = mapSamAttributeType(primaryKey.path("Type").asText("String"));

            ObjectNode hashKey = objectMapper.createObjectNode();
            hashKey.put("AttributeName", pkName);
            hashKey.put("KeyType", "HASH");
            keySchema.add(hashKey);

            ObjectNode hashAttr = objectMapper.createObjectNode();
            hashAttr.put("AttributeName", pkName);
            hashAttr.put("AttributeType", pkType);
            attrDefs.add(hashAttr);
        } else {
            ObjectNode hashKey = objectMapper.createObjectNode();
            hashKey.put("AttributeName", "id");
            hashKey.put("KeyType", "HASH");
            keySchema.add(hashKey);

            ObjectNode hashAttr = objectMapper.createObjectNode();
            hashAttr.put("AttributeName", "id");
            hashAttr.put("AttributeType", "S");
            attrDefs.add(hashAttr);
        }

        tableProps.set("KeySchema", keySchema);
        tableProps.set("AttributeDefinitions", attrDefs);
        tableProps.put("BillingMode", "PAY_PER_REQUEST");

        copyIfPresent(properties, "Tags", tableProps);

        tableDef.set("Properties", tableProps);
        resources.set(logicalId, tableDef);
    }

    private void expandServerlessApi(String logicalId, JsonNode properties, ObjectNode resources) {
        resources.remove(logicalId);

        ObjectNode apiDef = objectMapper.createObjectNode();
        apiDef.put("Type", "AWS::ApiGateway::RestApi");
        ObjectNode apiProps = objectMapper.createObjectNode();

        JsonNode name = properties.path("Name");
        if (!name.isMissingNode()) {
            apiProps.set("Name", name.deepCopy());
        } else {
            apiProps.put("Name", logicalId);
        }
        copyIfPresent(properties, "Description", apiProps);

        apiDef.set("Properties", apiProps);
        resources.set(logicalId, apiDef);

        String deploymentLogicalId = logicalId + "Deployment";
        ObjectNode deployDef = objectMapper.createObjectNode();
        deployDef.put("Type", "AWS::ApiGateway::Deployment");
        ObjectNode deployProps = objectMapper.createObjectNode();
        ObjectNode restApiRef = objectMapper.createObjectNode();
        restApiRef.put("Ref", logicalId);
        deployProps.set("RestApiId", restApiRef);
        deployDef.set("Properties", deployProps);
        ArrayNode deployDeps = objectMapper.createArrayNode();
        deployDeps.add(logicalId);
        deployDef.set("DependsOn", deployDeps);
        resources.set(deploymentLogicalId, deployDef);

        String stageLogicalId = logicalId + "Stage";
        ObjectNode stageDef = objectMapper.createObjectNode();
        stageDef.put("Type", "AWS::ApiGateway::Stage");
        ObjectNode stageProps = objectMapper.createObjectNode();
        stageProps.set("RestApiId", restApiRef.deepCopy());
        ObjectNode deployRef = objectMapper.createObjectNode();
        deployRef.put("Ref", deploymentLogicalId);
        stageProps.set("DeploymentId", deployRef);

        JsonNode stageName = properties.path("StageName");
        if (!stageName.isMissingNode()) {
            stageProps.set("StageName", stageName.deepCopy());
        } else {
            stageProps.put("StageName", "Prod");
        }

        stageDef.set("Properties", stageProps);
        ArrayNode stageDeps = objectMapper.createArrayNode();
        stageDeps.add(deploymentLogicalId);
        stageDef.set("DependsOn", stageDeps);
        resources.set(stageLogicalId, stageDef);
    }

    private void copyIfPresent(JsonNode source, String field, ObjectNode target) {
        JsonNode value = source.path(field);
        if (!value.isMissingNode() && !value.isNull()) {
            target.set(field, value.deepCopy());
        }
    }

    private String mapSamAttributeType(String samType) {
        return switch (samType) {
            case "String" -> "S";
            case "Number" -> "N";
            case "Binary" -> "B";
            default -> "S";
        };
    }
}
