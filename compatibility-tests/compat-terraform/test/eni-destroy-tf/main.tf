# Minimal VPC + Subnet + Security Group to verify
# terraform destroy succeeds after DescribeNetworkInterfaces fix.
# Previously https://github.com/floci-io/floci/issues/1031

resource "aws_vpc" "test" {
  cidr_block = "10.0.0.0/16"

  tags = {
    Name = "floci-eni-destroy-vpc"
  }
}

resource "aws_subnet" "test" {
  vpc_id     = aws_vpc.test.id
  cidr_block = "10.0.1.0/24"

  tags = {
    Name = "floci-eni-destroy-subnet"
  }
}

resource "aws_security_group" "test" {
  name        = "floci-eni-destroy-sg"
  description = "ENI destroy test SG"
  vpc_id      = aws_vpc.test.id

  tags = {
    Name = "floci-eni-destroy-sg"
  }
}
