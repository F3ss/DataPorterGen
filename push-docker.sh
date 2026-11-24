#!/bin/sh
set -eu

cd "$(dirname "$0")"

DOCKER_IMAGE="viktorfess/dataportergen:${1:-latest}"

docker login --username viktorfess
docker build --tag "$DOCKER_IMAGE" .
docker push "$DOCKER_IMAGE"

echo "Published: $DOCKER_IMAGE"
