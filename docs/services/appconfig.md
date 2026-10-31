# AppConfig

Floci supports AWS AppConfig and AppConfigData for local configuration management.

## Management Plane (AppConfig)

The management plane allows you to create and manage applications, environments, configuration profiles, and hosted configuration versions.

### Supported Operations

- `CreateApplication`
- `GetApplication`
- `ListApplications`
- `DeleteApplication`
- `CreateEnvironment`
- `GetEnvironment`
- `ListEnvironments`
- `DeleteEnvironment`
- `CreateConfigurationProfile`
- `GetConfigurationProfile`
- `ListConfigurationProfiles`
- `DeleteConfigurationProfile`
- `CreateHostedConfigurationVersion`
- `GetHostedConfigurationVersion`
- `DeleteHostedConfigurationVersion`
- `CreateDeploymentStrategy`
- `GetDeploymentStrategy`
- `ListDeploymentStrategies`
- `DeleteDeploymentStrategy`
- `StartDeployment`
- `GetDeployment`
- `ListDeployments` (`GET /applications/{ApplicationId}/environments/{EnvironmentId}/deployments`)
- `GetConfiguration` (deprecated by AWS in favor of the [data plane](#data-plane))

`GetConfiguration` accepts application, environment and configuration profile names or IDs, and requires
`client_id`. It returns the deployed version's content with its `Content-Type` and `Configuration-Version`;
feature flags are resolved as on the [data plane](#data-plane), including Ion for a caller that accepts it.
An exact resource ID takes precedence over another resource's name. If multiple resources in the
selected scope share a name, name lookup returns `BadRequestException`; use the intended resource's ID.
This also applies to the management operations and `StartConfigurationSession` that use these lookups.
A `client_configuration_version` equal to the deployed version returns HTTP 204 with an empty body
and `Configuration-Version`, without `Content-Type` or `Content-Length`. An empty configuration
that the caller has not received still returns HTTP 200 and its stored content type.
A missing or undeployed environment returns `BadRequestException`; an unknown application or configuration profile
returns `ResourceNotFoundException`.
Only hosted configurations are served: a deployed version with no hosted configuration version behind it,
such as one from an S3 or SSM source, returns `ResourceNotFoundException`.

`CreateEnvironment` keeps up to five `Monitors`, each a CloudWatch `AlarmArn` with an optional IAM role
`AlarmRoleArn`, and environment reads return them; an environment created without monitors returns none.
Floci does not evaluate the alarms or roll a deployment back. More than five monitors, or an `AlarmRoleArn`
that is not an IAM role ARN, returns `BadRequestException`.
`StartDeployment` and `GetDeployment` return the deployed profile's name and location URI as
`ConfigurationName` and `ConfigurationLocationUri`. Deployments stored by earlier Floci versions did not
record them, so on those neither is set.
They also return the deployment strategy's `DeploymentDurationInMinutes`, `FinalBakeTimeInMinutes`,
`GrowthFactor` and `GrowthType` as the deployment started with them, and `ListDeployments` reports each
deployment with the profile name and strategy settings it recorded, also once its strategy is deleted.
A deployment stored by an earlier Floci version returns no strategy settings from `GetDeployment`, and
`ListDeployments` reports the strategy's current settings while the strategy exists.
A configuration profile created without `Type` reports no type, as AWS documents no default.

## Data Plane (AppConfigData) {#data-plane}

The data plane is used by applications to retrieve the active configuration for an environment and profile.

### Supported Operations

- `StartConfigurationSession`
- `GetLatestConfiguration`

Basic `AWS.AppConfig.FeatureFlags` profiles are returned in AWS retrieval-time JSON format.

Feature flags with variants are returned, as AWS does, in Amazon Ion (`application/ion; type=AWS.AppConfig.FeatureFlags`)
to a caller whose `Accept` header asks for it, as the AWS AppConfig Agent's does: the caller evaluates the variants'
rules against its own context (`?flag=name&context=key=value`). The layout is the one AWS documents for the agent's
[local development mode](https://docs.aws.amazon.com/appconfig/latest/userguide/appconfig-agent-how-to-use-local-development-samples.html#appconfig-agent-how-to-use-local-development-samples-multi-variant):
each flag with variants is a list of its variants, and each basic flag in the same profile is its retrieval-time JSON
as a string. Rules of the form `(eq $attribute "value")` are
encoded; a profile with any other rule is returned unchanged, as JSON, and Floci logs a warning, as it does for a
caller that does not accept Ion.

## CloudFormation

Floci provisions these resource types:

- `AWS::AppConfig::Application`
- `AWS::AppConfig::Environment`
- `AWS::AppConfig::ConfigurationProfile`
- `AWS::AppConfig::DeploymentStrategy`

`Ref` returns the AppConfig ID of each, which `Fn::GetAtt` also returns as `ApplicationId`, `EnvironmentId`,
`ConfigurationProfileId` and `Id`. A configuration profile's `KmsKeyArn` is not set.

An update that changes only mutable properties keeps the ID and changes the resource in place: the name and
description of an application, environment or configuration profile, an environment's `Monitors`, an application's
`Tags`, and a deployment strategy's description, duration, final bake time, growth factor and growth type. A property
the template drops is cleared, or set back to its default (`FinalBakeTimeInMinutes` 0, `GrowthType` `LINEAR`). A
change to a createOnly property (`ApplicationId` on an environment or configuration profile, `LocationUri` or `Type`
on a configuration profile, `Name` or `ReplicateTo` on a deployment strategy) replaces the resource: the new one is
created and the old one is deleted once the stack update completes. A failed stack update puts back what it changed in
place and deletes what it created.

Deleting an environment deletes its deployments, and deleting a configuration profile deletes its hosted configuration
versions. Floci keeps tags on applications only, so `Tags` on the other three types are accepted and not stored, as
`TagResource` treats them; so are a configuration profile's `KmsKeyIdentifier`, `RetrievalRoleArn` and `Validators`,
and `DeletionProtectionCheck`. `ReplicateTo: SSM_DOCUMENT` writes no SSM document.
`AWS::AppConfig::HostedConfigurationVersion`, `Deployment`, `Extension` and `ExtensionAssociation` are not provisioned.

A stack created before Floci provisioned these types holds stubs for them, and an update creates the real resource in
place of a stub only when it changes that resource. An update that changes an environment or configuration profile of
a stubbed application therefore fails with `ResourceNotFoundException` unless it changes the application too.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APPCONFIG_ENABLED` | `true` | Enable or disable the AppConfig management plane |
| `FLOCI_SERVICES_APPCONFIGDATA_ENABLED` | `true` | Enable or disable the AppConfigData retrieval plane |

## Example Usage

### 1. Create an Application and Environment

```bash
# Create application
aws appconfig create-application --name my-app --endpoint-url http://localhost:4566

# Create environment
aws appconfig create-environment --application-id <app-id> --name dev --endpoint-url http://localhost:4566
```

### 2. Create a Hosted Configuration

```bash
# Create configuration profile
aws appconfig create-configuration-profile \
  --application-id <app-id> \
  --name my-profile \
  --location-uri hosted \
  --type AWS.Freeform \
  --endpoint-url http://localhost:4566

# Create hosted configuration version
aws appconfig create-hosted-configuration-version \
  --application-id <app-id> \
  --configuration-profile-id <profile-id> \
  --content "{\"foo\": \"bar\"}" \
  --content-type application/json \
  --endpoint-url http://localhost:4566
```

### 3. Deploy the Configuration

```bash
# Create immediate deployment strategy
aws appconfig create-deployment-strategy \
  --name immediate \
  --deployment-duration-in-minutes 0 \
  --growth-factor 100 \
  --final-bake-time-in-minutes 0 \
  --endpoint-url http://localhost:4566

# Start deployment
aws appconfig start-deployment \
  --application-id <app-id> \
  --environment-id <env-id> \
  --configuration-profile-id <profile-id> \
  --configuration-version 1 \
  --deployment-strategy-id <strategy-id> \
  --endpoint-url http://localhost:4566
```

### 4. Retrieve Configuration via Data Plane

```bash
# Start configuration session
TOKEN=$(aws appconfigdata start-configuration-session \
  --application-identifier <app-id> \
  --environment-identifier <env-id> \
  --configuration-profile-identifier <profile-id> \
  --query "InitialConfigurationToken" --output text \
  --endpoint-url http://localhost:4566)

# Get latest configuration
aws appconfigdata get-latest-configuration \
  --configuration-token $TOKEN \
  --endpoint-url http://localhost:4566
```
