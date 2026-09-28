# Floci repo tasks.
#
# Action-table docs: docs/services/*.md "Supported Actions" tables are generated
# from handler source. See tools/docs/.
#
# Service-matrix docs: docs/services/index.md's Service Matrix table is checked
# against ResolvedServiceCatalog.java so a registered service can't ship undocumented.
#
# Partition literals: hardcoded arn:aws: prefixes, amazonaws.com hosts and hosted-zone
# ids in src/main are inventoried against tools/partition/baseline.tsv so the
# commercial-partition assumption can only shrink. See tools/partition/.
#
# AWS partition data: src/main/resources/aws/partitions.json is generated from botocore's
# published partition metadata (plus two CDK region rules) by tools/aws/regen_partitions.py.

PYTHON ?= python3

# Native builds. The binary is built inside the Quarkus builder container (works on macOS,
# yields a Linux binary for the host arch) with the flags CI's compatibility workflow uses:
# -Ob is the quick profile, and -H:-AOTSingleCallsiteInline is required on Mandrel 25.0.4+,
# where Quarkus 3.39 turns single-callsite inlining on and the generated REST invokers for
# AwsQueryController.dispatch and AwsJson11Controller.handle then outgrow the aarch64 branch
# range (BranchTargetOutOfBoundsException). The flag exists only on Mandrel, so it lives here
# and in the workflows rather than in application.yml.
ARCH := $(shell uname -m | sed -e 's/x86_64/amd64/' -e 's/aarch64/arm64/')
NATIVE_BUILDER ?= quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25
NATIVE_ARGS ?= -Ob,-H:-AOTSingleCallsiteInline
NATIVE_IMAGE ?= floci:local-native
COMPOSE_NATIVE := docker compose -f docker-compose.yml -f docker/compose.native.yml
SUITES ?= sdk-test-java

# Host builds (make native-host): the installed GraalVM or Mandrel, found the way Quarkus finds
# it, GRAALVM_HOME first, then JAVA_HOME, then the PATH. The inlining flag above is unknown to
# GraalVM, so it is added only when that native-image is Mandrel.
comma := ,
NATIVE_IMAGE_BIN = $(or $(wildcard $(GRAALVM_HOME)/bin/native-image),$(wildcard $(JAVA_HOME)/bin/native-image),$(shell command -v native-image 2>/dev/null))
NATIVE_HOST_ARGS ?= -Ob
NATIVE_HOST_FLAGS = $(NATIVE_HOST_ARGS)$(if $(findstring andrel,$(shell "$(NATIVE_IMAGE_BIN)" --version 2>/dev/null)),$(comma)-H:-AOTSingleCallsiteInline)
NATIVE_BIN = $(firstword $(wildcard target/*-runner))
PREFIX ?= $(HOME)/.local

.DEFAULT_GOAL := help
.PHONY: help dev build test native native-host run-native native-install native-image native-up native-down native-logs clean-sidecars clean-volumes compat docker-tests \
        docs-sync docs-check docs-test partition-check partition-baseline partition-audit partition-test \
        aws-data-sync aws-data-check aws-data-test

help: ## List the targets below
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-14s %s\n", $$1, $$2}'

dev: ## Run Floci in Quarkus dev mode (hot reload on port 4566)
	./mvnw quarkus:dev

build: ## Build the JVM package without running tests
	./mvnw clean package -DskipTests

test: ## Run the test suite; T=Class or T=Class#method narrows it (make test T=SsmIntegrationTest)
	./mvnw test $(if $(T),-Dtest='$(T)' -Dsurefire.failIfNoSpecifiedTests=false,)

native: ## Build the native binary with CI's flags and stage it in native/$(ARCH)/ (about 4 min)
	./mvnw clean package -Dnative -DskipTests -B \
		-Dquarkus.native.container-build=true \
		-Dquarkus.native.builder-image=$(NATIVE_BUILDER) \
		-Dquarkus.native.additional-build-args-append="$(NATIVE_ARGS)"
	mkdir -p native/$(ARCH)
	cp target/*-runner native/$(ARCH)/application
# The builder image has no zlib-static, so a container-built binary links zlib dynamically and
# ubi9-micro ships no libz.so.1; stage it next to the binary, where the runtime image's
# LD_LIBRARY_PATH finds it. CI builds with a host Mandrel that links zlib statically.
	docker run --rm --entrypoint cat $(NATIVE_BUILDER) /usr/lib64/libz.so.1 > native/$(ARCH)/libz.so.1

native-host: ## Build a native binary for this machine with the installed GraalVM or Mandrel, no Docker: target/floci-<version>-runner
	@test -n "$(NATIVE_IMAGE_BIN)" || { echo "native-image not found: install GraalVM or Mandrel (for example 'sdk install java 25.0.3-graal') or set GRAALVM_HOME"; exit 1; }
	@echo "native-image: $(NATIVE_IMAGE_BIN) (flags $(NATIVE_HOST_FLAGS))"
	./mvnw clean package -Dnative -DskipTests -B \
		-Dquarkus.native.additional-build-args-append="$(NATIVE_HOST_FLAGS)"
	@echo "built $(NATIVE_BIN) for $$(uname -sm)"

run-native: ## Run the host binary from native-host on port 4566, state under ./data
	@test -n "$(NATIVE_BIN)" || { echo "no target/*-runner: run 'make native-host' first"; exit 1; }
	@file "$(NATIVE_BIN)" | grep -q ELF && [ "$$(uname -s)" != Linux ] && { echo "$(NATIVE_BIN) is a Linux binary from 'make native'; run 'make native-host' for this machine"; exit 1; } || true
	$(NATIVE_BIN)

native-install: ## Copy the host binary to $(PREFIX)/bin/floci (PREFIX defaults to ~/.local)
	@test -n "$(NATIVE_BIN)" || { echo "no target/*-runner: run 'make native-host' first"; exit 1; }
	mkdir -p $(PREFIX)/bin
	cp "$(NATIVE_BIN)" $(PREFIX)/bin/floci
	@echo "installed $(PREFIX)/bin/floci"

native-image: ## Package native/$(ARCH)/ as $(NATIVE_IMAGE) with docker/Dockerfile.native-package
	@test -f native/$(ARCH)/application || { echo "native/$(ARCH)/application is missing: run 'make native' first"; exit 1; }
	docker build -f docker/Dockerfile.native-package --build-arg VERSION=local -t $(NATIVE_IMAGE) .

native-up: ## Start $(NATIVE_IMAGE) with docker compose (docker/compose.native.yml overlay) and wait for health
	$(COMPOSE_NATIVE) up -d --no-build
	@for i in $$(seq 1 60); do \
		curl -fsS -o /dev/null http://localhost:4566/_floci/health 2>/dev/null && { echo "Floci is healthy on http://localhost:4566"; exit 0; }; \
		sleep 2; \
	done; echo "Floci did not become healthy in time"; $(COMPOSE_NATIVE) logs --tail=40 floci; exit 1

native-down: ## Stop the compose stack started by native-up, then the sidecar containers Floci left behind
	$(COMPOSE_NATIVE) down
	$(MAKE) clean-sidecars

clean-sidecars: ## Remove the containers Floci started (label floci=true) that outlived it: ECR registry, EC2 instances, RDS, sidecars
	@ids=$$(docker ps -aq --filter label=floci=true --filter label=floci_emulator=floci-aws); \
	if [ -n "$$ids" ]; then docker rm -f $$ids; else echo "no Floci-managed containers left"; fi

clean-volumes: ## Remove the named volumes Floci created (label floci=true): ECR registry, RDS, OpenSearch and MSK data. Gives the next start a fresh state
	@vols=$$(docker volume ls -q --filter label=floci=true --filter label=floci_emulator=floci-aws); \
	if [ -n "$$vols" ]; then docker volume rm $$vols; else echo "no Floci-managed volumes left"; fi

native-logs: ## Tail the running Floci container's log
	$(COMPOSE_NATIVE) logs -f --tail=100 floci

compat: ## Run compatibility suites against the running Floci: SUITES="sdk-test-java compat-cdk". The suites assume a fresh state: make clean-sidecars clean-volumes before native-up
	FLOCI_START=0 COMPAT_SUITES="$(SUITES)" docker/run-docker-tests.sh

docker-tests: ## Start the JVM image with compose and run every compatibility suite in Docker
	docker/run-docker-tests.sh

docs-sync: ## Regenerate the action tables in docs/services from handler source (in place)
	$(PYTHON) tools/docs/regen_action_docs.py
	$(PYTHON) tools/docs/regen_cfn_resource_types.py

docs-check: ## CI gate: regenerate and fail if anything is stale, unregistered, or undocumented
	@$(PYTHON) tools/docs/regen_action_docs.py --strict || { \
		echo ""; \
		echo "error: action-table regeneration reported problems (see warnings above)."; \
		exit 1; \
	}
	@$(PYTHON) tools/docs/regen_cfn_resource_types.py --strict || { \
		echo ""; \
		echo "error: the CloudFormation resource-type table is stale or reported problems."; \
		echo "       Run 'make docs-sync' and commit the result."; \
		exit 1; \
	}
	@git diff --exit-code -- docs/ || { \
		echo ""; \
		echo "error: docs/services action tables are out of date."; \
		echo "       Run 'make docs-sync' and commit the result."; \
		exit 1; \
	}
	@$(PYTHON) tools/docs/check_service_matrix.py --strict || { \
		echo ""; \
		echo "error: the Service Matrix in docs/services/index.md is out of sync (see warnings above)."; \
		exit 1; \
	}
	@! grep -rn -- '-jvm' docs README.md CONTRIBUTING.md || { \
		echo ""; \
		echo "error: docs name a '-jvm' image tag; release.yml publishes only x.y.z, latest and their -compat twins."; \
		exit 1; \
	}

docs-test: ## Run the docs tooling's unit tests
	$(PYTHON) -m pytest tools/docs -q

partition-check: ## CI gate: partition literals in src/main must match tools/partition/baseline.tsv
	@$(PYTHON) tools/partition/partition_literals.py --check || { \
		echo ""; \
		echo "error: partition literals drifted from tools/partition/baseline.tsv (see above)."; \
		echo "       Fix the new literal, or run 'make partition-baseline' after removing some."; \
		exit 1; \
	}

partition-baseline: ## Regenerate tools/partition/baseline.tsv from the current tree (commit the result)
	$(PYTHON) tools/partition/partition_literals.py --write-baseline

partition-audit: ## Print the per-package table of remaining partition literals
	@$(PYTHON) tools/partition/partition_literals.py --audit

partition-test: ## Run the partition tooling's unit tests
	$(PYTHON) -m pytest tools/partition -q

aws-data-sync: ## Regenerate src/main/resources/aws/partitions.json from botocore (commit the result)
	$(PYTHON) tools/aws/regen_partitions.py

aws-data-check: ## CI gate: the vendored partition data must match a fresh generation
	@$(PYTHON) tools/aws/regen_partitions.py --check || { \
		echo ""; \
		echo "error: src/main/resources/aws/partitions.json is out of date."; \
		echo "       Run 'make aws-data-sync' and commit the result."; \
		exit 1; \
	}

aws-data-test: ## Run the partition-data generator's unit tests
	$(PYTHON) -m pytest tools/aws -q
