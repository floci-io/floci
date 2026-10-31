"""Where Floci's Java sources live: the one place the tools under tools/ name the Java package.

Tools import the paths from here instead of writing src/main/java/<package>/... themselves, so a
package rename changes this file and nothing else in tools/. Registries that list source files
(tools/docs/services.yaml, tools/docs/service_matrix.yaml) hold paths relative to PACKAGE_DIR.
"""
from __future__ import annotations

SOURCE_ROOT = "src/main/java"
PACKAGE_DIR = f"{SOURCE_ROOT}/io/github/hectorvent/floci"
SERVICES_PACKAGE_DIR = f"{PACKAGE_DIR}/services"


def package_source(relative: str) -> str:
    """The repo-relative path of a source file a registry lists relative to PACKAGE_DIR."""
    if relative.startswith(("src/", "/")):
        raise ValueError(f"{relative}: registry paths are relative to {PACKAGE_DIR}/, "
                         f"for example services/sqs/SqsJsonHandler.java")
    return f"{PACKAGE_DIR}/{relative}"
