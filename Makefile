# Floci repo tasks.
#
# Action-table docs: docs/services/*.md "Supported Actions" tables are generated
# from handler source. See tools/docs/.

PYTHON ?= python3

.PHONY: docs-sync docs-check docs-test

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
