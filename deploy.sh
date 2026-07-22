#!/bin/sh
set -euxo

ENV="${1:-dev}";
MODE="${2:-push}";
PROJECT_NAME="${3:-dev-dse-eonax}";

git config --global user.name "${GITLAB_USER}"
git config --global user.email "${GITLAB_USER}@eona-x.eu"
git config --global --add credential.helper "store --file ./.git-credentials"
echo "https://${GITLAB_USER}:${GITLAB_TOKEN}@${GITLAB_HOST}" > ./.git-credentials
git config --global http.sslVerify false
git clone https://${GITLAB_USER}:${GITLAB_TOKEN}@${GITLAB_HOST}/eona-x/data-space/argocd-deployments.git

cd argocd-deployments
git fetch origin ${ENV}
git checkout --force origin/${ENV}

EXCLUDE_MODULES_REGEX="kafka-bundle"
grep -rl "${REGISTRY_NS_DSE}" values/dataspace/participants | grep -Ev ${EXCLUDE_MODULES_REGEX} | xargs -n 1 sed -i '/^\s\{2,\}tag:/s/tag:.*/tag: ${IMAGE_TAG}/'

git remote set-url origin https://${GITLAB_USER}:${GITLAB_TOKEN}@${GITLAB_HOST}/eona-x/data-space/argocd-deployments.git
git add values/
COMMIT_MSG="chore(deploy): update dse version to ${IMAGE_TAG}"
git commit -m "${COMMIT_MSG}" || echo "No changes to commit."

if [ "${MODE}" = "merge_request" ]; then
  BRANCH="update-${ENV}-${IMAGE_TAG}"
  git checkout -b "${BRANCH}"
  git push origin "${BRANCH}" \
    -o merge_request.create \
    -o merge_request.target="${ENV}" \
    -o merge_request.title="${COMMIT_MSG}" \
    -o merge_request.remove_source_branch
  exit 0
fi

while true; do
  if git push origin HEAD:${ENV}; then
    echo "Push successful!"
    break
  else
    echo "Push failed. Pulling latest changes and rebasing..."
    # Pull latest changes and rebase
    git pull --rebase origin ${ENV}
    if [ $? -ne 0 ]; then
      echo "Rebase failed. Resolve conflicts manually and retry."
      exit 1
    fi
  fi
done

argocd login ${ARGOCD_HOST_DEV} --username admin --password ${ARGOCD_ADMIN_PASSWORD_DEV}
argocd app sync --project "${PROJECT_NAME}"
