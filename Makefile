PYTHON ?= $(if $(wildcard .venv/bin/python),.venv/bin/python,python3)

.PHONY: docs-sync docs-test docs-check

docs-sync:
	$(PYTHON) tools/docs/regen_action_docs.py

docs-test:
	PYTHONPATH=tools/docs $(PYTHON) -m pytest tools/docs/

docs-check:
	$(PYTHON) tools/docs/regen_action_docs.py --strict
	@if ! git diff --quiet -- docs/; then \
	  echo ""; \
	  echo "ERROR: docs/services/*.md is out of sync with handler source."; \
	  echo "Run 'make docs-sync' locally and commit the result."; \
	  echo ""; \
	  git diff --stat -- docs/; \
	  exit 1; \
	fi
