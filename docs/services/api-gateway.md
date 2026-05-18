# API Gateway

Floci supports both API Gateway v1 (REST APIs) and API Gateway v2 (HTTP APIs).

## API Gateway v1 (REST APIs) {#v1}

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/restapis/...`

### Supported Operations

| Category | Operations |
|---|---|
| **APIs** | CreateRestApi, ImportRestApi, PutRestApi, GetRestApi, GetRestApis, UpdateRestApi, DeleteRestApi |
| **Resources** | CreateResource, GetResource, GetResources, UpdateResource, DeleteResource |
| **Methods** | PutMethod, GetMethod, UpdateMethod, DeleteMethod |
| **Method Responses** | PutMethodResponse, GetMethodResponse |
| **Integrations** | PutIntegration, GetIntegration, UpdateIntegration, DeleteIntegration |
| **Integration Responses** | PutIntegrationResponse, GetIntegrationResponse |
| **Deployments** | CreateDeployment, GetDeployments |
| **Stages** | CreateStage, GetStage, GetStages, UpdateStage, DeleteStage |
| **Authorizers** | CreateAuthorizer, GetAuthorizer, GetAuthorizers |
| **API Keys** | CreateApiKey, GetApiKeys |
| **Usage Plans** | CreateUsagePlan, GetUsagePlans, DeleteUsagePlan |
| **Usage Plan Keys** | CreateUsagePlanKey, GetUsagePlanKey, GetUsagePlanKeys, DeleteUsagePlanKey |
| **Request Validators** | CreateRequestValidator, GetRequestValidator, GetRequestValidators, DeleteRequestValidator |
| **Models** | CreateModel, GetModel, GetModels, DeleteModel |
| **Domain Names** | CreateDomainName, GetDomainName, GetDomainNames, DeleteDomainName |
| **Base Path Mappings** | CreateBasePathMapping, GetBasePathMapping, GetBasePathMappings, DeleteBasePathMapping |
| **Account** | GetAccount, UpdateAccount |
| **Tags** | TagResource, UntagResource, GetTags (ListTagsForResource) |

### Not Implemented

These management-plane operations have no handler in v1. Calls will return `404` or an error:

- Deployment detail and lifecycle: `GetDeployment`, `UpdateDeployment`, `DeleteDeployment`
- Authorizer lifecycle: `UpdateAuthorizer`, `DeleteAuthorizer`, `TestInvokeAuthorizer`
- API key detail: `GetApiKey`, `UpdateApiKey`, `DeleteApiKey`, `ImportApiKeys`
- Usage plan detail: `GetUsagePlan`, `UpdateUsagePlan`
- Model updates and templates: `UpdateModel`, `GetModelTemplate`
- Gateway Responses (the entire family: `PutGatewayResponse`, `GetGatewayResponse`, etc.)
- Documentation parts and versions (the entire family, 10 operations)
- VPC Links (5 operations)
- Client Certificates (5 operations)
- `GetExport` / `ImportDocumentationParts`

The execute plane (actual proxied HTTP traffic via `/restapis/{id}/{stage}/_user_request_/…`) is implemented separately and is not counted as management-plane operations.

### Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a REST API
API_ID=$(aws apigateway create-rest-api \
  --name "My API" \
  --query id --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Get the root resource
ROOT_ID=$(aws apigateway get-resources \
  --rest-api-id $API_ID \
  --query 'items[?path==`/`].id' --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create a resource
RESOURCE_ID=$(aws apigateway create-resource \
  --rest-api-id $API_ID \
  --parent-id $ROOT_ID \
  --path-part users \
  --query id --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Add a GET method
aws apigateway put-method \
  --rest-api-id $API_ID \
  --resource-id $RESOURCE_ID \
  --http-method GET \
  --authorization-type NONE \
  --endpoint-url $AWS_ENDPOINT_URL

# Add a Lambda integration
aws apigateway put-integration \
  --rest-api-id $API_ID \
  --resource-id $RESOURCE_ID \
  --http-method GET \
  --type AWS_PROXY \
  --integration-http-method POST \
  --uri "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:my-function/invocations" \
  --endpoint-url $AWS_ENDPOINT_URL

# Deploy to a stage
aws apigateway create-deployment \
  --rest-api-id $API_ID \
  --stage-name dev \
  --endpoint-url $AWS_ENDPOINT_URL

# Call the deployed API
curl http://localhost:4566/restapis/$API_ID/dev/_user_request_/users
```

---

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APIGATEWAY_ENABLED` | `true` | Enable or disable API Gateway v1 (REST APIs) |
| `FLOCI_SERVICES_APIGATEWAYV2_ENABLED` | `true` | Enable or disable API Gateway v2 (HTTP and WebSocket APIs) |

## API Gateway v2 (HTTP and WebSocket APIs) {#v2}

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v2/apis/...`

Both HTTP and WebSocket protocol types are fully supported, including the WebSocket data-plane (real connection handling, message routing, and the `@connections` management API).

### Supported Operations

| Category | Operations |
|---|---|
| **APIs** | CreateApi, GetApi, GetApis, UpdateApi, DeleteApi |
| **Routes** | CreateRoute, GetRoute, GetRoutes, UpdateRoute, DeleteRoute |
| **Route Responses** | CreateRouteResponse, GetRouteResponse, GetRouteResponses, UpdateRouteResponse, DeleteRouteResponse |
| **Integrations** | CreateIntegration, GetIntegration, GetIntegrations, UpdateIntegration, DeleteIntegration |
| **Integration Responses** | CreateIntegrationResponse, GetIntegrationResponse, GetIntegrationResponses, UpdateIntegrationResponse, DeleteIntegrationResponse |
| **Authorizers** | CreateAuthorizer, GetAuthorizer, GetAuthorizers, UpdateAuthorizer, DeleteAuthorizer |
| **Stages** | CreateStage, GetStage, GetStages, UpdateStage, DeleteStage |
| **Deployments** | CreateDeployment, GetDeployment, GetDeployments, UpdateDeployment, DeleteDeployment |
| **Models** | CreateModel, GetModel, GetModels, UpdateModel, DeleteModel |
| **Tags** | TagResource, UntagResource, GetTags |

### WebSocket Data-Plane {#websocket-data-plane}

Floci supports real WebSocket connections for API Gateway v2 WebSocket APIs. Clients connect via:

```
ws://localhost:4566/ws/{apiId}/{stageName}
```

#### Supported Features

| Feature | Status |
|---------|--------|
| `$connect` route with Lambda integration | ✅ |
| `$disconnect` route with Lambda integration | ✅ |
| `$default` route (fallback) | ✅ |
| Custom routes via `routeSelectionExpression` | ✅ |
| Route response selection expression | ✅ |
| Lambda REQUEST authorizer on `$connect` | ✅ |
| Identity source validation (header/querystring) | ✅ |
| `@connections` POST (send message to client) | ✅ |
| `@connections` GET (get connection info) | ✅ |
| `@connections` DELETE (disconnect client) | ✅ |
| Stage variable substitution in integration URIs | ✅ |
| AWS_PROXY integration (Lambda) | ✅ |
| AWS integration (Lambda with VTL templates) | ✅ |
| HTTP_PROXY integration | ✅ |
| HTTP integration (with VTL templates) | ✅ |
| MOCK integration | ✅ |
| GoneException (410) for disconnected connections | ✅ |
| Binary frame support (`isBase64Encoded: true`) | ✅ |
| `$connect` response headers propagation | ✅ |
| 128 KB payload size limit enforcement | ✅ |
| 10-minute idle timeout | ✅ |
| 2-hour max connection duration | ✅ |

#### @connections Management API

The `@connections` API allows server-side code (e.g., Lambda functions) to send messages to connected clients, retrieve connection metadata, or disconnect clients:

```
POST   /execute-api/{apiId}/{stageName}/@connections/{connectionId}  — Send message
GET    /execute-api/{apiId}/{stageName}/@connections/{connectionId}  — Get connection info
DELETE /execute-api/{apiId}/{stageName}/@connections/{connectionId}  — Disconnect client
```

#### Behavior Notes

- **Connection URL**: Floci uses `ws://localhost:4566/ws/{apiId}/{stage}` instead of AWS's `wss://{api-id}.execute-api.{region}.amazonaws.com/{stage}`.
- **Idle timeout**: 10 minutes (matching AWS default). Not configurable per-API.
- **Max connection duration**: 2 hours (matching AWS). Connections are closed automatically.
- **Payload size limit**: 128 KB per frame (matching AWS). Oversized messages receive an error frame.

### Not Implemented

- `ReimportApi`, `ExportApi`, `GetApiMapping`, `CreateApiMapping`, `DeleteApiMapping`
- `GetDomainName`, `CreateDomainName`, `DeleteDomainName`
- `CreateVpcLink`, `GetVpcLink`, `GetVpcLinks`, `UpdateVpcLink`, `DeleteVpcLink`

### Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create an HTTP API
API_ID=$(aws apigatewayv2 create-api \
  --name "My HTTP API" \
  --protocol-type HTTP \
  --query ApiId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create a Lambda integration
INTEGRATION_ID=$(aws apigatewayv2 create-integration \
  --api-id $API_ID \
  --integration-type AWS_PROXY \
  --integration-uri "arn:aws:lambda:us-east-1:000000000000:function:my-function" \
  --payload-format-version 2.0 \
  --query IntegrationId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create a route
aws apigatewayv2 create-route \
  --api-id $API_ID \
  --route-key "GET /users" \
  --target "integrations/$INTEGRATION_ID" \
  --endpoint-url $AWS_ENDPOINT_URL

# Deploy
aws apigatewayv2 create-stage \
  --api-id $API_ID \
  --stage-name dev \
  --auto-deploy \
  --endpoint-url $AWS_ENDPOINT_URL
```

#### WebSocket API

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a WebSocket API
WS_API_ID=$(aws apigatewayv2 create-api \
  --name "My WebSocket API" \
  --protocol-type WEBSOCKET \
  --route-selection-expression '$request.body.action' \
  --query ApiId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create a Lambda integration
WS_INTEGRATION_ID=$(aws apigatewayv2 create-integration \
  --api-id $WS_API_ID \
  --integration-type AWS_PROXY \
  --integration-uri "arn:aws:lambda:us-east-1:000000000000:function:my-ws-handler" \
  --query IntegrationId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create $connect, $disconnect, and $default routes
aws apigatewayv2 create-route \
  --api-id $WS_API_ID \
  --route-key '$connect' \
  --target "integrations/$WS_INTEGRATION_ID" \
  --endpoint-url $AWS_ENDPOINT_URL

aws apigatewayv2 create-route \
  --api-id $WS_API_ID \
  --route-key '$disconnect' \
  --target "integrations/$WS_INTEGRATION_ID" \
  --endpoint-url $AWS_ENDPOINT_URL

aws apigatewayv2 create-route \
  --api-id $WS_API_ID \
  --route-key '$default' \
  --route-response-selection-expression '$default' \
  --target "integrations/$WS_INTEGRATION_ID" \
  --endpoint-url $AWS_ENDPOINT_URL

# Deploy
aws apigatewayv2 create-stage \
  --api-id $WS_API_ID \
  --stage-name prod \
  --endpoint-url $AWS_ENDPOINT_URL

# Connect via WebSocket (using wscat or any WebSocket client)
# wscat -c ws://localhost:4566/ws/$WS_API_ID/prod

# Send a message to a connected client via @connections API
# curl -X POST http://localhost:4566/execute-api/$WS_API_ID/prod/@connections/$CONNECTION_ID \
#   -d "Hello from server"

# Get connection info
# curl http://localhost:4566/execute-api/$WS_API_ID/prod/@connections/$CONNECTION_ID

# Disconnect a client
# curl -X DELETE http://localhost:4566/execute-api/$WS_API_ID/prod/@connections/$CONNECTION_ID
```
