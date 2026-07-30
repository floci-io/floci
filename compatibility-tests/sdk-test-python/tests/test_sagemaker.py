import time
import uuid

import boto3
import pytest
from botocore.config import Config


def client(service):
    return boto3.client(
        service,
        endpoint_url="http://localhost:4566",
        region_name="us-east-1",
        aws_access_key_id="test",
        aws_secret_access_key="test",
        config=Config(retries={"max_attempts": 1}),
    )


def test_sagemaker_control_plane_and_training():
    sm = client("sagemaker")
    s3 = client("s3")
    suffix = uuid.uuid4().hex[:8]
    model = f"compat-sm-{suffix}"
    cfg = f"compat-sm-cfg-{suffix}"
    bucket = f"compat-sm-{suffix}"

    sm.create_model(ModelName=model, PrimaryContainer={"Image": "public.ecr.aws/docker/library/busybox:stable"}, ExecutionRoleArn="arn:aws:iam::000000000000:role/r")
    assert sm.describe_model(ModelName=model)["ModelName"] == model
    sm.create_endpoint_config(EndpointConfigName=cfg, ProductionVariants=[{"VariantName": "AllTraffic", "ModelName": model, "InitialInstanceCount": 1, "InstanceType": "ml.t2.medium"}])
    assert any(c["EndpointConfigName"] == cfg for c in sm.list_endpoint_configs()["EndpointConfigs"])

    s3.create_bucket(Bucket=bucket)
    s3.put_object(Bucket=bucket, Key="input/data.txt", Body=b"hello")
    job = f"compat-train-{suffix}"
    sm.create_training_job(
        TrainingJobName=job,
        AlgorithmSpecification={
            "TrainingImage": "public.ecr.aws/docker/library/busybox:stable",
            "ContainerEntrypoint": ["/bin/sh", "-c"],
            "ContainerArguments": ["mkdir -p /opt/ml/model && echo ok > /opt/ml/model/model.txt"],
        },
        InputDataConfig=[{"ChannelName": "train", "DataSource": {"S3DataSource": {"S3Uri": f"s3://{bucket}/input"}}, "TrainingInputMode": "File"}],
        OutputDataConfig={"S3OutputPath": f"s3://{bucket}/output"},
        ResourceConfig={"InstanceType": "ml.m5.large", "InstanceCount": 1, "VolumeSizeInGB": 1},
        StoppingCondition={"MaxRuntimeInSeconds": 60},
    )
    deadline = time.time() + 120
    while time.time() < deadline:
        desc = sm.describe_training_job(TrainingJobName=job)
        if desc["TrainingJobStatus"] == "Completed":
            artifact = desc["ModelArtifacts"]["S3ModelArtifacts"]
            key = artifact.split(f"s3://{bucket}/", 1)[1]
            assert s3.get_object(Bucket=bucket, Key=key)["Body"].read()
            break
        if desc["TrainingJobStatus"] == "Failed":
            pytest.fail(desc.get("FailureReason", "training failed"))
        time.sleep(1)
    else:
        pytest.fail("Timed out waiting for SageMaker training job")

    sm.delete_endpoint_config(EndpointConfigName=cfg)
    sm.delete_model(ModelName=model)
