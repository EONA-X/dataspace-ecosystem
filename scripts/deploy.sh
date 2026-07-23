#!/bin/sh
set -euxo

ENVIRONMENT="dev"
MODE="push"
ARGOCD_REPO="https://${GITLAB_HOST}/eona-x/data-space/argocd-deployments.git"
ARGOCD_PROJECT="dev-dse-eonax"
ARGOCD_APP=""
HELM_VALUES_PATH="values/dataspace/participants"

while [ $# -gt 0 ]; do
  case "$1" in
    --environment) ENVIRONMENT="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --tag) IMAGE_TAG="$2"; shift 2 ;;
    --argocd-repo) ARGOCD_REPO="$2"; shift 2 ;;
    --argocd-project) ARGOCD_PROJECT="$2"; shift 2 ;;
    --argocd-app) ARGOCD_APP="$2"; shift 2 ;;
    --helm-values-path) HELM_VALUES_PATH="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

git config --global user.name "${GITLAB_USER}"
git config --global user.email "${GITLAB_USER}@eona-x.eu"
git config --global --add credential.helper "store --file ./.git-credentials"
echo "https://${GITLAB_USER}:${GITLAB_TOKEN}@${GITLAB_HOST}" > ./.git-credentials
git config --global http.sslVerify false
git clone "https://${GITLAB_USER}:${GITLAB_TOKEN}@$(echo "${ARGOCD_REPO}" | sed -E 's#https?://##')"

cd argocd-deployments
git fetch origin "${ENVIRONMENT}"
git checkout --force "origin/${ENVIRONMENT}"

EXCLUDE_MODULES_REGEX="kafka-bundle"
grep -rl "${REGISTRY_NS_DSE}" "${HELM_VALUES_PATH}" | grep -Ev "${EXCLUDE_MODULES_REGEX}" | xargs -n 1 sed -i "/^\s\{2,\}tag:/s/tag:.*/tag: ${IMAGE_TAG}/"

git remote set-url origin "https://${GITLAB_USER}:${GITLAB_TOKEN}@$(echo "${ARGOCD_REPO}" | sed -E 's#https?://##')"
git add values/
COMMIT_MSG="chore(deploy): update dse version to ${IMAGE_TAG}"
git commit -m "${COMMIT_MSG}" || echo "No changes to commit."

if [ "${MODE}" = "merge_request" ]; then
  BRANCH="update-${ENVIRONMENT}-${IMAGE_TAG}"
  git checkout -b "${BRANCH}"
  git push origin "${BRANCH}" \
    -o merge_request.create \
    -o merge_request.target="${ENVIRONMENT}" \
    -o merge_request.title="${COMMIT_MSG}" \
    -o merge_request.remove_source_branch
  exit 0
fi

while true; do
  if git push origin HEAD:"${ENVIRONMENT}"; then
    echo "Push successful!"
    break
  else
    echo "Push failed. Pulling latest changes and rebasing..."
    git pull --rebase origin "${ENVIRONMENT}"
    if [ $? -ne 0 ]; then
      echo "Rebase failed. Resolve conflicts manually and retry."
      exit 1
    fi
  fi
done

argocd login "${ARGOCD_HOST_DEV}" --username admin --password "${ARGOCD_ADMIN_PASSWORD_DEV}"
if [ -n "${ARGOCD_APP}" ]; then
  argocd app sync "${ARGOCD_APP}" --project "${ARGOCD_PROJECT}"
else
  argocd app sync --project "${ARGOCD_PROJECT}"
fi
