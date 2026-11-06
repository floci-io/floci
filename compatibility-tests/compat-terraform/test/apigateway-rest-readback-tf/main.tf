# Verifies that a REST API gateway converges: a second plan reports no changes.
#
# GetMethod dropped requestParameters, GetRestApi dropped policy, and GetStage dropped
# accessLogSettings, tracingEnabled and tags. Terraform planned the same three in-place
# updates on every run, and because the deployment's redeployment trigger hashes the
# method, the re-apply failed with "Provider produced inconsistent final plan".

variable "extra_request_parameter" {
  type    = bool
  default = false
}

variable "stage_env" {
  type    = string
  default = "local"
}

resource "aws_api_gateway_rest_api" "api" {
  name = "floci-apigw-rest-readback"
}

resource "aws_api_gateway_resource" "proxy" {
  rest_api_id = aws_api_gateway_rest_api.api.id
  parent_id   = aws_api_gateway_rest_api.api.root_resource_id
  path_part   = "{proxy+}"
}

resource "aws_api_gateway_method" "proxy" {
  rest_api_id   = aws_api_gateway_rest_api.api.id
  resource_id   = aws_api_gateway_resource.proxy.id
  http_method   = "ANY"
  authorization = "NONE"

  request_parameters = merge(
    { "method.request.path.proxy" = true },
    var.extra_request_parameter ? { "method.request.header.X-Tenant" = false } : {},
  )
}

resource "aws_api_gateway_integration" "proxy" {
  rest_api_id = aws_api_gateway_rest_api.api.id
  resource_id = aws_api_gateway_resource.proxy.id
  http_method = aws_api_gateway_method.proxy.http_method
  type        = "MOCK"

  request_templates = {
    "application/json" = "{\"statusCode\": 200}"
  }
}

resource "aws_api_gateway_rest_api_policy" "api" {
  rest_api_id = aws_api_gateway_rest_api.api.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = "*"
      Action    = "execute-api:Invoke"
      Resource  = "${aws_api_gateway_rest_api.api.execution_arn}/*"
    }]
  })
}

# The common module pattern from the bug report: the trigger hashes the method, so a
# method that reads back differently than it was written breaks the apply itself.
resource "aws_api_gateway_deployment" "api" {
  rest_api_id = aws_api_gateway_rest_api.api.id

  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_resource.proxy,
      aws_api_gateway_method.proxy,
      aws_api_gateway_integration.proxy,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "local" {
  rest_api_id          = aws_api_gateway_rest_api.api.id
  deployment_id        = aws_api_gateway_deployment.api.id
  stage_name           = "local"
  xray_tracing_enabled = true

  access_log_settings {
    destination_arn = "arn:aws:logs:us-east-1:000000000000:log-group:floci-apigw-rest-readback"
    format          = "$context.requestId $context.status"
  }

  tags = {
    env = var.stage_env
  }
}

output "rest_api_id" {
  value = aws_api_gateway_rest_api.api.id
}

output "resource_id" {
  value = aws_api_gateway_resource.proxy.id
}
