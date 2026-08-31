"""Integration tests for Amazon Bedrock Batch Inference."""

import json
import pytest
from botocore.exceptions import ClientError


@pytest.mark.bedrock
class TestBedrockBatchInference:
    """Tests for Bedrock Model Invocation Jobs."""

    def test_create_and_get_model_invocation_job(self, bedrock_client, test_bucket, unique_name):
        """Test creating and getting a model invocation job."""
        job_name = f"batch-job-{unique_name}"
        input_key = f"input-{unique_name}.jsonl"
        output_prefix = f"output-{unique_name}"
        input_s3_uri = f"s3://{test_bucket}/{input_key}"
        output_s3_uri = f"s3://{test_bucket}/{output_prefix}"

        # Create job
        response = bedrock_client.create_model_invocation_job(
            jobName=job_name,
            modelId="amazon.titan-text-express-v1",
            roleArn="arn:aws:iam::000000000000:role/BedrockBatchRole",
            inputDataConfig={"s3InputDataConfig": {"s3Uri": input_s3_uri}},
            outputDataConfig={"s3OutputDataConfig": {"s3Uri": output_s3_uri}},
            tags=[{"key": "Environment", "value": "test"}],
        )

        assert "jobArn" in response
        job_arn = response["jobArn"]
        assert ":model-invocation-job/" in job_arn

        # Get job by ARN
        job = bedrock_client.get_model_invocation_job(jobIdentifier=job_arn)
        assert job["jobArn"] == job_arn
        assert job["jobName"] == job_name
        assert job["modelId"] == "amazon.titan-text-express-v1"
        assert job["roleArn"] == "arn:aws:iam::000000000000:role/BedrockBatchRole"
        assert "status" in job
        assert job["inputDataConfig"]["s3InputDataConfig"]["s3Uri"] == input_s3_uri
        assert job["outputDataConfig"]["s3OutputDataConfig"]["s3Uri"] == output_s3_uri
        assert "submitTime" in job

    def test_get_nonexistent_job(self, bedrock_client):
        """Test getting a nonexistent job raises ResourceNotFoundException."""
        with pytest.raises(ClientError) as exc_info:
            bedrock_client.get_model_invocation_job(
                jobIdentifier="arn:aws:bedrock:us-east-1:000000000000:model-invocation-job/nonexistent999"
            )
        assert exc_info.value.response["Error"]["Code"] in ["ResourceNotFoundException", "NotFoundException"]

    def test_list_model_invocation_jobs(self, bedrock_client, test_bucket, unique_name):
        """Test listing model invocation jobs with filtering."""
        job_name_1 = f"list-job-1-{unique_name}"
        job_name_2 = f"list-job-2-{unique_name}"

        bedrock_client.create_model_invocation_job(
            jobName=job_name_1,
            modelId="amazon.titan-text-express-v1",
            roleArn="arn:aws:iam::000000000000:role/BedrockBatchRole",
            inputDataConfig={"s3InputDataConfig": {"s3Uri": f"s3://{test_bucket}/in1.jsonl"}},
            outputDataConfig={"s3OutputDataConfig": {"s3Uri": f"s3://{test_bucket}/out1"}},
        )

        bedrock_client.create_model_invocation_job(
            jobName=job_name_2,
            modelId="anthropic.claude-3-sonnet-20240229-v1:0",
            roleArn="arn:aws:iam::000000000000:role/BedrockBatchRole",
            inputDataConfig={"s3InputDataConfig": {"s3Uri": f"s3://{test_bucket}/in2.jsonl"}},
            outputDataConfig={"s3OutputDataConfig": {"s3Uri": f"s3://{test_bucket}/out2"}},
        )

        # List all jobs
        response = bedrock_client.list_model_invocation_jobs()
        assert "invocationJobSummaries" in response
        job_names = [j["jobName"] for j in response["invocationJobSummaries"]]
        assert job_name_1 in job_names
        assert job_name_2 in job_names

        # Filter by name contains
        filtered = bedrock_client.list_model_invocation_jobs(nameContains=job_name_1)
        filtered_names = [j["jobName"] for j in filtered["invocationJobSummaries"]]
        assert job_name_1 in filtered_names
        assert job_name_2 not in filtered_names

    def test_stop_model_invocation_job(self, bedrock_client, test_bucket, unique_name):
        """Test stopping a model invocation job."""
        job_name = f"stop-job-{unique_name}"

        response = bedrock_client.create_model_invocation_job(
            jobName=job_name,
            modelId="amazon.titan-text-express-v1",
            roleArn="arn:aws:iam::000000000000:role/BedrockBatchRole",
            inputDataConfig={"s3InputDataConfig": {"s3Uri": f"s3://{test_bucket}/in.jsonl"}},
            outputDataConfig={"s3OutputDataConfig": {"s3Uri": f"s3://{test_bucket}/out"}},
        )
        job_arn = response["jobArn"]

        # Stop the job — if it already reached a terminal status before stop arrived, skip stop
        current = bedrock_client.get_model_invocation_job(jobIdentifier=job_arn)
        terminal = {"Completed", "Failed", "Stopped", "Expired", "PartiallyCompleted"}
        if current["status"] not in terminal:
            bedrock_client.stop_model_invocation_job(jobIdentifier=job_arn)

        # Verify job is in a stopped or already-terminal state
        job = bedrock_client.get_model_invocation_job(jobIdentifier=job_arn)
        assert job["status"] in ["Stopping", "Stopped", "Failed"]
        assert "endTime" in job

    def test_batch_inference_execution_with_s3(self, bedrock_client, s3_client, test_bucket, unique_name):
        """Test full S3 batch inference execution including output and manifest files."""
        import time
        input_key = f"prompts-{unique_name}.jsonl"
        output_prefix = f"batch-results-{unique_name}"

        # Prepare JSONL lines
        records = [
            {"recordId": "rec-1", "modelInput": {"inputText": "Tell me a short joke"}},
            {"recordId": "rec-2", "modelInput": {"inputText": "What is 2+2?"}},
        ]
        jsonl_body = "\n".join(json.dumps(r) for r in records)

        # Upload input JSONL to S3
        s3_client.put_object(
            Bucket=test_bucket,
            Key=input_key,
            Body=jsonl_body.encode("utf-8"),
        )

        input_s3_uri = f"s3://{test_bucket}/{input_key}"
        output_s3_uri = f"s3://{test_bucket}/{output_prefix}"

        # Submit batch job
        job_name = f"exec-job-{unique_name}"
        create_resp = bedrock_client.create_model_invocation_job(
            jobName=job_name,
            modelId="amazon.titan-text-express-v1",
            roleArn="arn:aws:iam::000000000000:role/BedrockBatchRole",
            inputDataConfig={"s3InputDataConfig": {"s3Uri": input_s3_uri}},
            outputDataConfig={"s3OutputDataConfig": {"s3Uri": output_s3_uri}},
        )
        job_arn = create_resp["jobArn"]
        job_id = job_arn.split("/")[-1]

        # Poll until job reaches a terminal status
        terminal = {"Completed", "Failed", "Stopped", "Expired", "PartiallyCompleted"}
        deadline = time.time() + 30
        job = None
        while time.time() < deadline:
            job = bedrock_client.get_model_invocation_job(jobIdentifier=job_arn)
            if job["status"] in terminal:
                break
            time.sleep(0.5)

        assert job["status"] == "Completed"

        # Check output JSONL in S3 at <output_prefix>/<jobId>/<input_key>.out
        output_key = f"{output_prefix}/{job_id}/{input_key}.out"
        out_obj = s3_client.get_object(Bucket=test_bucket, Key=output_key)
        out_lines = out_obj["Body"].read().decode("utf-8").strip().split("\n")
        assert len(out_lines) == 2

        out_rec1 = json.loads(out_lines[0])
        assert out_rec1["recordId"] == "rec-1"
        assert "modelOutput" in out_rec1

        # Check manifest.json.out in S3 at <output_prefix>/<jobId>/manifest.json.out
        manifest_key = f"{output_prefix}/{job_id}/manifest.json.out"
        manifest_obj = s3_client.get_object(Bucket=test_bucket, Key=manifest_key)
        manifest_data = json.loads(manifest_obj["Body"].read().decode("utf-8"))

        assert manifest_data["totalRecordCount"] == 2
        assert manifest_data["processedRecordCount"] == 2
        assert manifest_data["successRecordCount"] == 2
        assert manifest_data["errorRecordCount"] == 0
