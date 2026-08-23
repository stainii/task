#!/bin/bash
# Builds both images, deriving the toolchain versions rather than repeating them.
#
#     deploy/build-images.sh [tag]        (default tag: the current commit)
#
# THE POINT OF THIS FILE IS THE TWO `sed` LINES. The JDK is pinned in .sdkmanrc and the Node version
# in .nvmrc; ci.yml already reads both at run time instead of copying them, and ToolchainPinsTest
# fails the build if the JDK's two existing copies disagree. The Dockerfiles therefore take their
# base image version as a build argument with no default, and this is the one place that fills it in
# — used by the publish workflow and by a human rebuilding an image by hand, so both do it the same
# way. Hard-coding a version in a Dockerfile would be a silent third copy: a half-moved JDK compiles
# and runs, it simply targets the wrong release for ever.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

TAG="${1:-$(git rev-parse HEAD)}"
REGISTRY="${TASK_REGISTRY:-ghcr.io/stainii}"

JAVA_VERSION="$(sed -n 's/^java=\([0-9][0-9]*\).*/\1/p' .sdkmanrc)"
NODE_VERSION="$(tr -d '[:space:]' < task-front-end/.nvmrc)"

[ -n "$JAVA_VERSION" ] || { echo "could not read java= from .sdkmanrc" >&2; exit 1; }
[ -n "$NODE_VERSION" ] || { echo "could not read task-front-end/.nvmrc" >&2; exit 1; }

echo "Building $TAG on JDK $JAVA_VERSION / Node $NODE_VERSION"

docker build \
    --build-arg "JAVA_VERSION=$JAVA_VERSION" \
    --tag "$REGISTRY/task-back-end:$TAG" \
    task-back-end

docker build \
    --build-arg "NODE_VERSION=$NODE_VERSION" \
    --tag "$REGISTRY/task-front-end:$TAG" \
    task-front-end

echo "Built $REGISTRY/task-back-end:$TAG and $REGISTRY/task-front-end:$TAG"
