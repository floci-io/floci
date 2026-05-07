# API Gateway

Floci supports both API Gateway v1 (REST APIs) and API Gateway v2 (HTTP APIs).

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `GetMethodResponse` |
| `PutMethodResponse` |
| `GetIntegrationResponse` |
| `PutIntegrationResponse` |
| `GetAuthorizer` |
| `GetAuthorizers` |
| `GetStage` |
| `GetStages` |
| `CreateRestApi` |
| `PutRestApi` |
| `GetRestApis` |
| `GetRestApi` |
| `UpdateRestApi` |
| `DeleteRestApi` |
| `GetResources` |
| `GetResource` |
| `UpdateResource` |
| `CreateResource` |
| `DeleteResource` |
| `PutMethod` |
| `GetMethod` |
| `UpdateMethod` |
| `DeleteMethod` |
| `PutIntegration` |
| `GetIntegration` |
| `UpdateIntegration` |
| `DeleteIntegration` |
| `CreateDeployment` |
| `GetDeployments` |
| `GetDeployment` |
| `CreateStage` |
| `UpdateStage` |
| `DeleteStage` |
| `CreateAuthorizer` |
| `CreateApiKey` |
| `GetApiKeys` |
| `CreateUsagePlan` |
| `GetUsagePlans` |
| `DeleteUsagePlan` |
| `CreateUsagePlanKey` |
| `GetUsagePlanKeys` |
| `GetUsagePlanKey` |
| `DeleteUsagePlanKey` |
| `CreateRequestValidator` |
| `GetRequestValidators` |
| `GetRequestValidator` |
| `DeleteRequestValidator` |
| `CreateModel` |
| `GetModels` |
| `GetModel` |
| `DeleteModel` |
| `CreateDomainName` |
| `GetDomainNames` |
| `GetDomainName` |
| `DeleteDomainName` |
| `CreateBasePathMapping` |
| `GetBasePathMappings` |
| `GetBasePathMapping` |
| `DeleteBasePathMapping` |
| `CreateApi` |
| `GetApis` |
| `GetApi` |
| `DeleteApi` |
| `UpdateApi` |
| `CreateRoute` |
| `GetRoutes` |
| `GetRoute` |
| `DeleteRoute` |
| `UpdateRoute` |
| `CreateIntegration` |
| `GetIntegrations` |
| `UpdateV2Integration` |
| `CreateRouteResponse` |
| `GetRouteResponse` |
| `GetRouteResponses` |
| `UpdateRouteResponse` |
| `DeleteRouteResponse` |
| `CreateIntegrationResponse` |
| `GetIntegrationResponses` |
| `UpdateIntegrationResponse` |
| `DeleteIntegrationResponse` |
| `CreateV2Authorizer` |
| `GetV2Authorizers` |
| `GetV2Authorizer` |
| `DeleteV2Authorizer` |
| `UpdateV2Authorizer` |
| `CreateV2Stage` |
| `GetV2Stages` |
| `GetV2Stage` |
| `DeleteV2Stage` |
| `UpdateV2Stage` |
| `CreateV2Deployment` |
| `GetV2Deployments` |
| `GetV2Deployment` |
| `DeleteV2Deployment` |
| `UpdateV2Deployment` |
| `CreateV2Model` |
| `GetV2Models` |
| `GetV2Model` |
| `UpdateV2Model` |
| `DeleteV2Model` |
| `TagResource` |
| `UntagResource` |
| `GetTagsForResource` |
| `DeleteAuthorizer` |
| `UpdateAuthorizer` |
| `DeleteDeployment` |
| `UpdateDeployment` |
| `UpdateModel` |
| `GetTags` |
<!-- floci:actions:end -->

## API Gateway v1 (REST APIs) {#v1}

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/restapis/...`

### Not Implemented

These management-plane operations have no handler in v1. Calls will return `404` or an error:

- Authorizer lifecycle: `TestInvokeAuthorizer`
- API key detail: `GetApiKey`, `UpdateApiKey`, `DeleteApiKey`, `ImportApiKeys`
- Usage plan detail: `GetUsagePlan`, `UpdateUsagePlan`
- Model templates: `GetModelTemplate`
- Gateway Responses (the entire family: `PutGatewayResponse`, `GetGatewayResponse`, etc.)
- Documentation parts and versions (the entire family, 10 operations)
- VPC Links (5 operations)
- Client Certificates (5 operations)
- Account: `GetAccount`, `UpdateAccount`
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

## API Gateway v2 (HTTP and WebSocket APIs) {#v2}

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v2/apis/...`

Both HTTP and WebSocket protocol types are supported for the management plane. WebSocket data-plane (actual connection handling) is not yet implemented.

### Not Implemented

- WebSocket data-plane: actual WebSocket connection handling, `@connections` management API
- `ReimportApi`, `ExportApi`, `GetApiMapping`, `CreateApiMapping`, `DeleteApiMapping`
- v2 domain names: `GetDomainName`, `CreateDomainName`, `DeleteDomainName` (v1 domain name operations are supported)
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
```
