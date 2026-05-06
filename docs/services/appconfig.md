# AppConfig

Floci supports AWS AppConfig and AppConfigData for local configuration management.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateApplication` |  |
| `GetApplication` |  |
| `ListApplications` |  |
| `DeleteApplication` |  |
| `CreateEnvironment` |  |
| `GetEnvironment` |  |
| `ListEnvironments` |  |
| `CreateConfigurationProfile` |  |
| `GetConfigurationProfile` |  |
| `ListConfigurationProfiles` |  |
| `CreateHostedConfigurationVersion` |  |
| `GetHostedConfigurationVersion` |  |
| `CreateDeploymentStrategy` |  |
| `GetDeploymentStrategy` |  |
| `StartDeployment` |  |
| `GetDeployment` |  |
| `StartConfigurationSession` |  |
| `GetLatestConfiguration` |  |
<!-- floci:actions:end -->

## Management Plane (AppConfig)

The management plane allows you to create and manage applications, environments, configuration profiles, and hosted configuration versions.

## Data Plane (AppConfigData) {#data-plane}

The data plane is used by applications to retrieve the active configuration for an environment and profile.

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
