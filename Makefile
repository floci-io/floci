PYTHON ?= $(if $(wildcard .venv/bin/python),.venv/bin/python,python3)

.PHONY: docs-sync docs-test

docs-sync:
	$(PYTHON) tools/docs/regen_action_docs.py

docs-test:
	PYTHONPATH=tools/docs $(PYTHON) -m pytest tools/docs/
