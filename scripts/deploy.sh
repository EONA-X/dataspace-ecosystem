#!/bin/sh
set -euxo pipefail

ENVIRONMENT="dev"
MODE="push"
ARGOCD_REPO="https://${GITLAB_HOST}/eona-x/data-space/argocd-deployments.git"
ARGOCD_PROJECT="dev-dse-eonax"
ARGOCD_APP=""
HELM_VALUES_PATH="values/dataspace/participants"
MANDATORY_INCLUDE=""
MANDATORY_EXCLUDE="kafka-bundle"
INCLUDE_REGEX=""
EXCLUDE_REGEX=""

while [ $# -gt 0 ]; do
  case "$1" in
    --environment) ENVIRONMENT="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --tag) IMAGE_TAG="$2"; shift 2 ;;
    --argocd-repo) ARGOCD_REPO="$2"; shift 2 ;;
    --argocd-project) ARGOCD_PROJECT="$2"; shift 2 ;;
    --argocd-app) ARGOCD_APP="$2"; shift 2 ;;
    --helm-values-path) HELM_VALUES_PATH="$2"; shift 2 ;;
    --include) INCLUDE_REGEX="$(echo "$2" | tr ',' '|')"; shift 2 ;;
    --exclude) EXCLUDE_REGEX="$(echo "$2" | tr ',' '|')"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

if [ -n "${MANDATORY_INCLUDE}" ] && [ -n "${INCLUDE_REGEX}" ]; then
  INCLUDE_REGEX="${MANDATORY_INCLUDE}|${INCLUDE_REGEX}"
else
  INCLUDE_REGEX="${MANDATORY_INCLUDE}${INCLUDE_REGEX}"
fi

if [ -n "${MANDATORY_EXCLUDE}" ] && [ -n "${EXCLUDE_REGEX}" ]; then
  EXCLUDE_REGEX="${MANDATORY_EXCLUDE}|${EXCLUDE_REGEX}"
else
  EXCLUDE_REGEX="${MANDATORY_EXCLUDE}${EXCLUDE_REGEX}"
fi

git config --global user.name "${GITLAB_USER}"
git config --global user.email "${GITLAB_USER}@eona-x.eu"
git config --global --add credential.helper "store --file ./.git-credentials"
echo "https://${GITLAB_USER}:${GITLAB_TOKEN}@${GITLAB_HOST}" > ./.git-credentials
git config --global http.sslVerify false
ARGOCD_REPO_HOST="$(echo "${ARGOCD_REPO}" | sed -E 's#https?://##')"
git clone "https://${GITLAB_USER}:${GITLAB_TOKEN}@${ARGOCD_REPO_HOST}"

cd argocd-deployments
git fetch origin "${ENVIRONMENT}"
git checkout --force "origin/${ENVIRONMENT}"

grep -rl "${REGISTRY_NS_DSE}" "${HELM_VALUES_PATH}" \
  | { [ -n "${INCLUDE_REGEX}" ] && grep -E "${INCLUDE_REGEX}" || cat; } \
  | { [ -n "${EXCLUDE_REGEX}" ] && grep -Ev "${EXCLUDE_REGEX}" || cat; } \
  | xargs -n 1 sed -i "/^\s\{2,\}tag:/s/tag:.*/tag: ${IMAGE_TAG}/"

git remote set-url origin "https://${GITLAB_USER}:${GITLAB_TOKEN}@${ARGOCD_REPO_HOST}"
git add values/
COMMIT_MSG="chore(deploy): update dse version to ${IMAGE_TAG}"
git commit -m "${COMMIT_MSG}" || echo "No changes to commit."

if [ "${MODE}" = "merge_request" ]; then
  BRANCH="update-${ENVIRONMENT}-${IMAGE_TAG}"
  git checkout -b "${BRANCH}"
  git push origin "${BRANCH}" --force \
    -o merge_request.create \
    -o merge_request.target="${ENVIRONMENT}" \
    -o merge_request.title="${COMMIT_MSG}" \
    -o merge_request.remove_source_branch
  exit 0
fi

MAX_PUSH_ATTEMPTS=5
attempt=1
while [ "${attempt}" -le "${MAX_PUSH_ATTEMPTS}" ]; do
  if git push origin HEAD:"${ENVIRONMENT}"; then
    echo "Push successful!"
    break
  fi
  echo "Push failed. Pulling latest changes and rebasing..."
  if ! git pull --rebase origin "${ENVIRONMENT}"; then
    echo "Rebase failed. Resolve conflicts manually and retry."
    exit 1
  fi
  attempt=$((attempt + 1))
  if [ "${attempt}" -gt "${MAX_PUSH_ATTEMPTS}" ]; then
    echo "Push failed after ${MAX_PUSH_ATTEMPTS} attempts." >&2
    exit 1
  fi
done

argocd login "${ARGOCD_HOST_DEV}" --username admin --password "${ARGOCD_ADMIN_PASSWORD_DEV}"
if [ -n "${ARGOCD_APP}" ]; then
  argocd app sync "${ARGOCD_APP}" --project "${ARGOCD_PROJECT}"
else
  argocd app sync --project "${ARGOCD_PROJECT}"
fi
