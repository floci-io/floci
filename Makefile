# Floci repo tasks.
#
# Run `make` (or `make help`) for the full target list.
#
# Emulator lifecycle targets drive the published Docker image; development
# targets build and test from source. Action-table docs: docs/services/*.md
# "Supported Actions" tables are generated from handler source (tools/docs/).

PYTHON ?= python3
IMAGE ?= floci/floci:latest
CONTAINER ?= floci
PORT ?= 4566
ENDPOINT ?= http://localhost:$(PORT)

.DEFAULT_GOAL := help

.PHONY: help doctor install start stop restart status logs env \
	dev build test docker-build up down \
	docs-sync docs-check docs-test

help: ## List available targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_-]+:.*## / {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

##@ Emulator lifecycle (published image)

doctor: ## Diagnose the local environment (Docker, ports, firewall, AWS env)
	bin/floci-doctor

install: ## Pull the Floci image (override with IMAGE=ghcr.io/floci-io/floci:latest)
	docker pull $(IMAGE)

start: ## Start Floci in Docker (detached, Docker-backed services enabled)
	docker run -d --name $(CONTAINER) \
		-p $(PORT):4566 \
		-v /var/run/docker.sock:/var/run/docker.sock \
		-u root \
		$(IMAGE)
	@echo "Floci starting on $(ENDPOINT) — check with 'make status'"

stop: ## Stop and remove the Floci container
	docker rm -f $(CONTAINER)

restart: stop start ## Restart the Floci container

status: ## Check emulator health
	@curl -fsS $(ENDPOINT)/_floci/health && echo || { echo "Floci is not responding on $(ENDPOINT) — run 'make doctor'"; exit 1; }

logs: ## Tail emulator logs
	docker logs -f $(CONTAINER)

env: ## Print AWS env exports — usage: eval $(make -s env)
	@bin/awslocal env

##@ Development (source checkout)

dev: ## Run from source in Quarkus dev mode (live reload)
	./mvnw quarkus:dev

build: ## Build the application jar (skips tests)
	./mvnw clean package -DskipTests

test: ## Run the test suite
	./mvnw test

docker-build: ## Build the Docker image from source
	docker compose build

up: ## Build from source and start via Docker Compose
	docker compose up -d

down: ## Stop the Docker Compose stack
	docker compose down

##@ Documentation tooling

docs-sync: ## Regenerate the action tables in docs/services from handler source (in place)
	$(PYTHON) tools/docs/regen_action_docs.py

docs-check: ## CI gate: regenerate and fail if anything is stale or a handler is unregistered
	@$(PYTHON) tools/docs/regen_action_docs.py --strict || { \
		echo ""; \
		echo "error: action-table regeneration reported problems (see warnings above)."; \
		exit 1; \
	}
	@git diff --exit-code -- docs/ || { \
		echo ""; \
		echo "error: docs/services action tables are out of date."; \
		echo "       Run 'make docs-sync' and commit the result."; \
		exit 1; \
	}

docs-test: ## Run the action-table tooling tests
	$(PYTHON) -m pytest tools/docs -q
