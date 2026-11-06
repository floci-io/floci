# Elastic Beanstalk

Floci implements the AWS Elastic Beanstalk management API as a Query-protocol service with stored application, application version, and environment state.

**Protocol:** Query — `POST /` with `Action=` form parameter, credential scope `elasticbeanstalk`

The handler also accepts Elastic Beanstalk's documented `Operation=` parameter alias.

## Supported Operations (14 total)

| Operation | Notes |
|---|---|
| `CreateApplication` | Creates an application with the default configuration template |
| `DescribeApplications` | Lists all applications or filters by `ApplicationNames.member.N` |
| `UpdateApplication` | Updates application description |
| `DeleteApplication` | Deletes an application; `TerminateEnvByForce=true` terminates active environments first |
| `CreateApplicationVersion` | Stores version metadata and optional `SourceBundle.S3Bucket` / `SourceBundle.S3Key` |
| `DescribeApplicationVersions` | Lists versions, optionally filtered by application and version labels |
| `DeleteApplicationVersion` | Removes a stored application version |
| `CreateEnvironment` | Creates an environment in immediate `Ready` / `Green` state |
| `DescribeEnvironments` | Lists environments by application, name, ID, version, and `IncludeDeleted` |
| `UpdateEnvironment` | Updates description, version label, platform fields, and option settings |
| `TerminateEnvironment` | Marks an environment as `Terminated` |
| `DescribeConfigurationSettings` | Returns stored environment option settings |
| `CheckDNSAvailability` | Checks CNAME availability against stored environments |
| `ListAvailableSolutionStacks` | Returns a small built-in platform list |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ELASTICBEANSTALK_ENABLED` | `true` | Enable or disable the service |

## Usage Example

```bash
aws elasticbeanstalk create-application \
  --application-name sample-app

aws elasticbeanstalk create-application-version \
  --application-name sample-app \
  --version-label v1 \
  --source-bundle S3Bucket=source-bucket,S3Key=app-v1.zip

aws elasticbeanstalk create-environment \
  --application-name sample-app \
  --environment-name sample-env \
  --version-label v1 \
  --solution-stack-name "64bit Amazon Linux 2023 v4.3.0 running Docker"

aws elasticbeanstalk describe-environments \
  --application-name sample-app
```
