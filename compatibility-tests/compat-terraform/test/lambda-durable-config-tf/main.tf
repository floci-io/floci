# aws_lambda_function.durable_config. The provider updates execution_timeout in place,
# and before it deletes the function it stops every RUNNING durable execution through
# ListDurableExecutionsByFunction, StopDurableExecution and GetDurableExecution.

variable "execution_timeout" {
  type    = number
  default = 60
}

data "archive_file" "handler" {
  type        = "zip"
  output_path = "${path.module}/handler.zip"

  # Starts a long wait, so an execution stays RUNNING until something stops it.
  source {
    filename = "index.py"
    content  = <<-PY
      import boto3


      def handler(event, context):
          if len(event["InitialExecutionState"]["Operations"]) == 1:
              boto3.client("lambda").checkpoint_durable_execution(
                  DurableExecutionArn=event["DurableExecutionArn"],
                  CheckpointToken=event["CheckpointToken"],
                  Updates=[{"Id": "w", "Type": "WAIT", "SubType": "Wait", "Action": "START",
                            "WaitOptions": {"WaitSeconds": 600}}])
          return {"Status": "PENDING"}
    PY
  }
}

resource "aws_iam_role" "durable" {
  name = "floci-tf-lambda-durable"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_lambda_function" "durable" {
  function_name    = "floci-tf-lambda-durable"
  role             = aws_iam_role.durable.arn
  runtime          = "python3.14"
  handler          = "index.handler"
  filename         = data.archive_file.handler.output_path
  source_code_hash = data.archive_file.handler.output_base64sha256
  timeout          = 30

  durable_config {
    execution_timeout = var.execution_timeout
  }
}

output "function_name" {
  value = aws_lambda_function.durable.function_name
}
