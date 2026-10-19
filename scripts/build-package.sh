#!/bin/sh
# No pipes here, so no need for -o pipefail (dash, this image's /bin/sh, doesn't support it --
# unlike deploy.sh's shell, which does use pipes and runs under a different, ash-based image).
set -eux

# Builds (shadowJar+dockerize, no push -- see push-package.sh) only the launcher modules in
# $1, tagged $2. Scoped on purpose: an unscoped root-level `./gradlew shadowJar dockerize`
# (what deploy:docker-push uses) walks every subproject including :plugins:buildGoPlugins,
# which needs `make` and isn't guaranteed present everywhere, and builds modules e2e doesn't
# even deploy.
#
# Module list derived from e2e-module-pairs.txt (participant:module) rather than kept as its
# own hand-maintained file -- a second file can't drift out of sync with the one that drives
# everything else (ArgoCD refresh/wait-image/wait-healthy in test.yml) if it doesn't exist.

PAIRS_FILE="${1:-scripts/e2e-module-pairs.txt}"
TAG="${2:-sha-${CI_COMMIT_SHA}}"

# Only on schedule (testing main's current HEAD): deploy:docker-push already built+pushed
# this exact commit's image at merge time, so re-building here is pure waste. Skip per
# module whose ${TAG} is already on Harbor. Never true yet on a manual MR trigger -- that
# commit hasn't merged, so no image exists for it anywhere. Any failure in this check (curl
# missing, auth parsing, network) falls through to "doesn't exist" -- fails open to rebuilding,
# never to silently skipping a module that's actually missing.
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
    echo "build-package: ${m}:${TAG} already on Harbor, skip"
    continue
  fi
  TASKS="$TASKS :launchers:${m}:${m}-postgresql-hashicorpvault:shadowJar"
  TASKS="$TASKS :launchers:${m}:${m}-postgresql-hashicorpvault:dockerize"
done

if [ -z "$TASKS" ]; then
  echo "build-package: nothing to build"
  exit 0
fi

# No --build-cache here on purpose: CACHE_POLICY=pull + the restored GitLab cache only
# cover 0_gradle/wrapper/..., never cache/caches/build-cache-1 -- so every entry Gradle's
# local build cache writes during this job is pure disk waste, never reused. Across ~10
# modules that filled the runner's disk (see #21611: "No space left on device" on module
# 9/10, after 8 successful builds).
./gradlew $TASKS \
  -PregistryHost="${REGISTRY_HOST}" \
  -PregistryNs="${REGISTRY_NS_DSE}" \
  -PimageTags="${TAG}" \
  -Pdocker.image.tags="${TAG}" \
  -Pdocker.registry="${REGISTRY_HOST}/${REGISTRY_NS_DSE}"
