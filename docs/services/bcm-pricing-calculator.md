# BCM Pricing Calculator (`bcm-pricing-calculator:*`)

**Protocol:** AWS JSON 1.0
**Header:** `X-Amz-Target: AWSBCMPricingCalculator.<Action>`
**Endpoint prefix:** `bcm-pricing-calculator`

Floci emulates the workload-estimate path of the in-console AWS Pricing Calculator. Usage lines are priced from Floci's bundled AWS Pricing snapshots, so estimates stay deterministic and require no external network access.

## Supported operations

| Operation | Notes |
|---|---|
| `CreateWorkloadEstimate` | Creates an account-scoped workload estimate and honors `clientToken` idempotency |
| `BatchCreateWorkloadEstimateUsage` | Adds 1 to 25 usage lines and recalculates the estimate total |
| `GetWorkloadEstimate` | Returns the estimate status, rate metadata, currency, and total cost |
| `DeleteWorkloadEstimate` | Idempotent; also removes the estimate's usage lines |

## Pricing behavior

`BatchCreateWorkloadEstimateUsage` resolves each usage line by `serviceCode`, `usageType`, and, when supplied, `operation`. The matching On-Demand price dimension from the Pricing service snapshot determines the unit and USD rate. The usage amount is multiplied by that rate and the estimate total is recalculated synchronously.

Floci currently models `BEFORE_DISCOUNTS` with the bundled public On-Demand rates. The AWS API also accepts `AFTER_DISCOUNTS` and `AFTER_DISCOUNTS_AND_COMMITMENTS`; these values are preserved on the estimate, but Floci does not have account-specific negotiated rates or purchase commitments to alter the calculated amount.

## Validation and lifecycle

- Workload estimate names use AWS's `[a-zA-Z0-9-]+` shape and are limited to 64 characters.
- Estimate identifiers use the AWS 36-character lowercase UUID shape.
- `clientToken` supports AWS idempotency semantics. Reusing a token with conflicting create parameters returns `ConflictException`.
- Batch requests accept 1 to 25 entries.
- Usage keys are at most 10 alphanumeric characters and `usageAccountId` is exactly 12 digits.
- A successful local calculation is stored as `VALID`; Floci performs the calculation synchronously rather than exposing a temporary `UPDATING` state.
- Currency is `USD`.
- Deleting an already-absent valid identifier succeeds, matching the idempotent delete contract.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BCM_PRICING_CALCULATOR_ENABLED` | `true` | Enable or disable the service |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

estimate_id=$(aws bcm-pricing-calculator create-workload-estimate \
  --name example-estimate \
  --rate-type BEFORE_DISCOUNTS \
  --client-token example-create \
  --query id --output text)

aws bcm-pricing-calculator batch-create-workload-estimate-usage \
  --workload-estimate-id "$estimate_id" \
  --client-token example-usage \
  --usage '[{"serviceCode":"AmazonEC2","usageType":"BoxUsage:t3.micro","operation":"","key":"ec2a","usageAccountId":"000000000000","amount":730}]'

aws bcm-pricing-calculator get-workload-estimate --identifier "$estimate_id"
aws bcm-pricing-calculator delete-workload-estimate --identifier "$estimate_id"
```
