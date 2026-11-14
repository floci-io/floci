#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/eks-request-shapes.json from botocore's EKS model.

EKS refuses a request body member of the wrong JSON type before it validates anything. The Java side
(`EksRequestShapes`) needs the body shapes to do the same, so this script keeps only what that check reads:
the input shape of every operation and, for every shape reachable from one, its type and the shapes of its
members, list elements or map values. A cluster's endpoint access and creator admin flags are booleans there,
which is what keeps a number from reaching a cluster as `true` while the IAM condition keys of the same
members carry no value.

The model is botocore's `eks/2017-11-01/service-2.json` (Apache-2.0, https://github.com/boto/botocore). It is
located, in order, at `--botocore-data`, the `local/aws/botocore` checkout, then the installed `botocore`
package, which `requirements.txt` pins so CI regenerates the same bytes.

Run from anywhere in the repo:
    python3 tools/aws/regen_eks_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_eks_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/eks-request-shapes.json"
MODEL = Path("eks/2017-11-01")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT, description=__doc__)


if __name__ == "__main__":
    sys.exit(main())
