#!/bin/sh
set -eux

# Pushes (dockerPush) only the launcher modules in $1, tagged $2. Companion to
# build-package.sh -- split so a module whose image build-package.sh skipped (already on
# Harbor, schedule trigger) is also skipped here: dockerPush needs the image build-package.sh
# just dockerize'd locally, so the two must agree on which modules were actually built.
# They do, for free: the registry state this check reads doesn't change between the two
# scripts' calls within the same job (build-package.sh builds, never pushes).

PAIRS_FILE="${1:-scripts/e2e-module-pairs.txt}"
TAG="${2:-sha-${CI_COMMIT_SHA}}"

image_exists() {
  command -v curl >/dev/null || return 1
  auth="$(grep -o '"auth":"[^"]*"' ./gradle/config.json 2>/dev/null | head -1 | cut -d'"' -f4 | base64 -d 2>/dev/null)" || return 1
  [ -n "${auth}" ] || return 1
  token="$(curl -sf -u "${auth}" \
    "https://${REGISTRY_HOST}/service/token?service=harbor-registry&scope=repository:${REGISTRY_NS_DSE}/${1}-postgresql-hashicorpvault:pull" \
    2>/dev/null | grep -o '"token":"[^"]*"' | cut -d'"' -f4)" || return 1
  [ -n "${token}" ] || return 1
  curl -sf -o /dev/null -H "Authorization: Bearer ${token}" \
    "https://${REGISTRY_HOST}/v2/${REGISTRY_NS_DSE}/${1}-postgresql-hashicorpvault/manifests/${TAG}"
}

TASKS=""
for m in $(cut -d: -f2 "${PAIRS_FILE}" | sort -u); do
  if [ "${CI_PIPELINE_SOURCE:-}" = "schedule" ] && image_exists "$m"; then
    echo "push-package: ${m}:${TAG} already on Harbor, skip"
    continue
  fi
  TASKS="$TASKS :launchers:${m}:${m}-postgresql-hashicorpvault:dockerPush"
done

if [ -z "$TASKS" ]; then
  echo "push-package: nothing to push"
  exit 0
fi

./gradlew $TASKS \
  -PregistryHost="${REGISTRY_HOST}" \
  -PregistryNs="${REGISTRY_NS_DSE}" \
  -PimageTags="${TAG}" \
  -Pdocker.image.tags="${TAG}" \
  -Pdocker.registry="${REGISTRY_HOST}/${REGISTRY_NS_DSE}"
