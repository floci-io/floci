# AppSync

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v1/apis/...`

Floci implements the AWS AppSync Management API, providing local emulation of GraphQL API configuration, schema management, data source binding, resolver mapping, and API key provisioning.

## Supported Operations

### GraphQL API

| Operation | Description |
|---|---|
| `CreateGraphqlApi` | Create a GraphQL API |
| `GetGraphqlApi` | Get a GraphQL API by ID |
| `UpdateGraphqlApi` | Update a GraphQL API |
| `DeleteGraphqlApi` | Delete a GraphQL API and all child resources |
| `ListGraphqlApis` | List all GraphQL APIs |

### Schema

| Operation | Description |
|---|---|
| `StartSchemaCreation` | Start schema creation (always synchronous) |
| `GetSchemaCreationStatus` | Get schema creation status |
| `GetIntrospectionSchema` | Get the introspection schema |

### Data Sources

| Operation | Description |
|---|---|
| `CreateDataSource` | Create a data source |
| `GetDataSource` | Get a data source by name |
| `UpdateDataSource` | Update a data source |
| `DeleteDataSource` | Delete a data source |
| `ListDataSources` | List all data sources for an API |

### Resolvers

| Operation | Description |
|---|---|
| `CreateResolver` | Create a resolver |
| `GetResolver` | Get a resolver by type and field |
| `UpdateResolver` | Update a resolver |
| `DeleteResolver` | Delete a resolver |
| `ListResolvers` | List all resolvers for an API |
| `ListResolversByType` | List resolvers for a specific type |
| `ListResolversByFunction` | List resolvers attached to a specific function |

### Functions

| Operation | Description |
|---|---|
| `CreateFunction` | Create a function configuration |
| `GetFunction` | Get a function by ID |
| `UpdateFunction` | Update a function |
| `DeleteFunction` | Delete a function |
| `ListFunctions` | List all functions for an API |

### Types

| Operation | Description |
|---|---|
| `CreateType` | Create a type |
| `GetType` | Get a type by name |
| `UpdateType` | Update a type |
| `DeleteType` | Delete a type |
| `ListTypes` | List all types for an API |

### API Keys

| Operation | Description |
|---|---|
| `CreateApiKey` | Create an API key |
| `GetApiKey` | Get an API key by ID |
| `UpdateApiKey` | Update an API key |
| `DeleteApiKey` | Delete an API key |
| `ListApiKeys` | List all API keys for an API |

### Tags

| Operation | Description |
|---|---|
| `TagResource` | Add tags to a resource |
| `UntagResource` | Remove tags from a resource |
| `ListTagsForResource` | List tags on a resource |

### Environment Variables

| Operation | Description |
|---|---|
| `GetEnvironmentVariables` | Get environment variables for an API |
| `PutEnvironmentVariables` | Set environment variables for an API |

## Pagination

All `List` operations support cursor-based pagination via query parameters:

| Parameter | Description |
|---|---|
| `maxResults` | Maximum number of items to return |
| `nextToken` | Opaque token for the next page |

The `nextToken` is a Base64 URL-encoded integer offset. A missing token starts from offset 0. An invalid token returns `InvalidNextTokenException` (400).

```bash
# First page
aws appsync list-graphql-apis \
  --max-results 10 \
  --endpoint-url $AWS_ENDPOINT_URL

# Next page (use the nextToken from previous response)
aws appsync list-graphql-apis \
  --max-results 10 \
  --next-token "eyJvZmZzZXQiOjEwfQ==" \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Cascade Delete

Deleting a GraphQL API (`DeleteGraphqlApi`) automatically deletes all child resources:

- Schema and schema creation status
- All data sources
- All resolvers
- All functions
- All types
- All API keys

This matches AWS behavior where deleting an API removes its entire configuration.

## Not Implemented

These AWS AppSync operations are not yet implemented:

- Real-time subscriptions (WebSocket)
- Pipeline resolvers
- Custom domain names
- Additional authentication providers management
- Data source service role validation

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APPSYNC_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a GraphQL API
aws appsync create-graphql-api \
  --name my-api \
  --authentication-type API_KEY \
  --endpoint-url $AWS_ENDPOINT_URL

# Start schema creation
aws appsync start-schema-creation \
  --api-id API_ID \
  --definition 'type Query { hello: String }' \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a data source (NONE type for local resolvers)
aws appsync create-data-source \
  --api-id API_ID \
  --name my-datasource \
  --type NONE \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a resolver
aws appsync create-resolver \
  --api-id API_ID \
  --type-name Query \
  --field-name hello \
  --data-source-name my-datasource \
  --endpoint-url $AWS_ENDPOINT_URL

# Create an API key
aws appsync create-api-key \
  --api-id API_ID \
  --description "Test key" \
  --endpoint-url $AWS_ENDPOINT_URL

# List all APIs
aws appsync list-graphql-apis \
  --endpoint-url $AWS_ENDPOINT_URL
```
