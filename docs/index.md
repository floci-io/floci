# Floci

<p align="center">
  <img src="assets/floci.svg" alt="Floci" width="500" />
</p>

<p align="center"><em>Light, fluffy, and always free</em></p>

---

Floci is a fast, free, and open-source local AWS service emulator built for developers who need reliable AWS services in development and CI without cost, complexity, or vendor lock-in.

## Supported Services

See the [Services Overview](services/index.md) for the full list of emulated services, per-service operation counts, endpoints, and protocol details.

## Why Floci?

**No account required.** No auth tokens, no sign-ups, no telemetry. Pull the image and start building.

**No feature gates.** Every feature is available to everyone — no community-edition restrictions.

**No CI restrictions.** Run in your CI pipeline with zero limitations. No credits, no quotas, no paid tiers.

**Truly open source.** MIT licensed. Fork it, extend it, embed it. No "community edition" sunset coming.

## Quick Start

```yaml title="docker-compose.yml"
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
    volumes:
      # Local directory bind mount (default)
      - ./data:/app/data
      
      # OR named volume (optional):
      # - floci-data:/app/data

#volumes:
#  floci-data:
```

```bash
docker compose up -d
aws --endpoint-url http://localhost:4566 s3 mb s3://my-bucket
```

Every emulated service is immediately available at `http://localhost:4566`.

[Get started →](getting-started/quick-start.md){ .md-button .md-button--primary }
[View services →](services/index.md){ .md-button }
