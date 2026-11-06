# Verifies that a DynamoDB Kinesis streaming destination keeps the precision it was created with.
#
# EnableKinesisStreamingDestination ignored ApproximateCreationDateTimePrecision and
# DescribeKinesisStreamingDestination always reported MILLISECOND. The attribute is ForceNew
# in the provider, so a MICROSECOND destination was destroyed and recreated on every apply.
#
# The table deliberately leaves DynamoDB Streams off: enabling the destination used to switch
# them on, which aws_dynamodb_table then planned to switch off again on every run.

variable "precision" {
  type    = string
  default = "MICROSECOND"
}

resource "aws_kinesis_stream" "events" {
  name             = "floci-ddb-kinesis-precision"
  shard_count      = 1
  retention_period = 24
}

resource "aws_dynamodb_table" "items" {
  name         = "floci-ddb-kinesis-precision"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }
}

resource "aws_dynamodb_kinesis_streaming_destination" "items" {
  stream_arn                               = aws_kinesis_stream.events.arn
  table_name                               = aws_dynamodb_table.items.name
  approximate_creation_date_time_precision = var.precision
}

output "table_name" {
  value = aws_dynamodb_table.items.name
}

output "stream_arn" {
  value = aws_kinesis_stream.events.arn
}
