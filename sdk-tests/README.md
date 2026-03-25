# SDK Integration Tests

Multi-language AWS SDK integration tests for floci. These tests validate that floci's S3 emulation is compatible with official AWS SDKs.

## Prerequisites

| Tool | Version | Installation |
|------|---------|--------------|
| Just | 1.38.0+ | `cargo install just` or [just.systems](https://just.systems) |
| Python | 3.11+ | [python.org](https://python.org) |
| Node.js | 20+ | [nodejs.org](https://nodejs.org) |
| AWS CLI | v2 | [aws.amazon.com/cli](https://aws.amazon.com/cli) |
| jq | 1.6+ | `apt install jq` / `brew install jq` |
| Git | any | Required to clone bats-core (via `just setup-bash`) |

### Verify Prerequisites

```bash
just --version
python3 --version
node --version
aws --version
jq --version
```

## Quick Start

### 1. Start floci

```bash
# From repository root
./mvnw quarkus:dev
```

Or using Docker:

```bash
docker run -p 4566:4566 hectorvent/floci
```

### 2. Install Dependencies

```bash
cd sdk-tests
just setup
```

### 3. Run All Tests

```bash
just test-all
```

## Running Tests

### All Languages

```bash
just test-all
```

### Individual Languages

```bash
# Python (boto3 + pytest)
just test-python

# TypeScript (AWS SDK v3 + vitest)
just test-typescript

# Bash (AWS CLI + bats-core)
just test-bash
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `FLOCI_ENDPOINT` | `http://localhost:4566` | floci endpoint URL |
| `AWS_ACCESS_KEY_ID` | `test` | AWS access key |
| `AWS_SECRET_ACCESS_KEY` | `test` | AWS secret key |
| `AWS_DEFAULT_REGION` | `us-east-1` | AWS region |

### Using Custom Endpoint

```bash
FLOCI_ENDPOINT=http://192.168.1.100:4566 just test-all
```

### Using .env File

Copy `.env.example` to `.env` and modify as needed:

```bash
cp .env.example .env
```

## Available Commands

```bash
just              # List all commands
just setup        # Install all dependencies
just setup-python # Install Python dependencies only
just setup-typescript # Install TypeScript dependencies only
just setup-bash   # Install Bash dependencies (clones bats-core)
just test-all     # Run all SDK tests
just test-python  # Run Python tests only
just test-typescript # Run TypeScript tests only
just test-bash    # Run Bash tests only
just clean        # Remove build artifacts and dependencies
```

## Adding Tests for New Services

### Python

Create `python/tests/test_<service>.py`:

```python
import uuid

def test_<operation>(s3_client):
    """Test description."""
    # Use s3_client fixture from conftest.py
    # Create unique resource names with uuid.uuid4().hex[:8]
    pass
```

### TypeScript

Create `typescript/tests/<service>.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';

describe('<Service> Operations', () => {
  it('should <operation>', async () => {
    // Test implementation
  });
});
```

### Bash

Create `bash/test/<service>.bats`:

```bash
#!/usr/bin/env bats

load 'test_helper/common-setup'

setup_file() {
    # Setup shared resources (runs once before all tests)
}

teardown_file() {
    # Cleanup shared resources (runs once after all tests)
}

@test "<Service>: <operation>" {
    run <aws_command>
    assert_success

    # For JSON responses, use jq assertions
    run jq -e '.Field' <<< "$output"
    assert_success
}
```

No need to make the script executable - bats handles this automatically.

## Troubleshooting

### "Connection refused" errors

floci is not running or not accessible.

```bash
# Verify floci is running
curl http://localhost:4566

# Start floci if needed
./mvnw quarkus:dev
```

### Python: "Module not found" errors

Dependencies not installed.

```bash
just setup-python
```

### TypeScript: npm errors

Dependencies not installed or Node.js version mismatch.

```bash
just setup-typescript

# Or manually
cd typescript && npm install
```

### Bash: "aws: command not found"

AWS CLI not installed.

```bash
# macOS
brew install awscli

# Ubuntu/Debian
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install
```

### Bash: "jq: command not found"

jq not installed.

```bash
# macOS
brew install jq

# Ubuntu/Debian
sudo apt install jq
```

### Bash: bats-core not found or test errors

Bats dependencies not installed. Run setup:

```bash
just setup-bash
```

This clones bats-core, bats-support, and bats-assert into `bash/lib/`.

## CI Integration

Tests automatically run on pull requests when `sdk-tests/**` or `src/**` files are modified. See `.github/workflows/sdk-tests.yml`.

## Directory Structure

```
sdk-tests/
├── justfile              # Task runner configuration
├── .env.example          # Example environment configuration
├── README.md             # This file
├── python/
│   ├── requirements.txt  # Python dependencies
│   ├── pytest.ini        # pytest configuration
│   ├── conftest.py       # Shared fixtures
│   └── tests/
│       └── test_s3.py    # S3 integration tests
├── typescript/
│   ├── package.json      # Node.js dependencies
│   ├── tsconfig.json     # TypeScript configuration
│   ├── vitest.config.ts  # vitest configuration
│   └── tests/
│       └── s3.test.ts    # S3 integration tests
└── bash/
    ├── lib/              # Cloned via `just setup-bash` (gitignored)
    │   ├── bats-core/    # bats-core test framework
    │   ├── bats-support/ # bats helper library
    │   └── bats-assert/  # bats assertion library
    └── test/
        ├── test_helper/
        │   └── common-setup.bash  # Shared setup and AWS CLI helpers
        └── s3.bats       # S3 integration tests
```
