bucket = "tfstate"
key    = "floci-compat.tfstate"
region = "us-east-1"

endpoint                    = "http://localhost:4566"
access_key                  = "test"
secret_key                  = "test"
skip_credentials_validation = true
skip_region_validation      = true
force_path_style            = true

dynamodb_endpoint = "http://localhost:4566"
dynamodb_table    = "tflock"
