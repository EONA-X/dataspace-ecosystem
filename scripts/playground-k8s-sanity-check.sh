#!/bin/sh
# Pre-test health check for a playground-style ArgoCD+k8s environment (lourmel-e2e today,
# any other playground VM tomorrow): self-heals vault/stuck pods, forces an ArgoCD refresh,
# then waits for the new image + a Healthy app before letting the test suite run against it.
# Assumes SSH is already usable (see scripts/ssh-setup.sh) and reachable over ${VM_IP}.
set -eux

PAIRS_FILE="${1:-scripts/e2e-module-pairs.txt}"
VM_IP="${2:-${E2E_VM_IP}}"
APP_PREFIX="${3:-playground-e2e}"
NAMESPACE_PREFIX="${4:-e2e-dse}"

ssh "ubuntu@${VM_IP}" "bash -s" < scripts/vault-sanity-check.sh

for pair in $(cat "${PAIRS_FILE}"); do
  participant="${pair%%:*}"
  module="${pair##*:}"
  app="${APP_PREFIX}-${participant}-${module}"
  ssh "ubuntu@${VM_IP}" "kubectl annotate application ${app} -n argocd argocd.argoproj.io/refresh=hard --overwrite"
done

# Right after refresh=hard the app is still Healthy on its OLD pods -- sync hasn't started.
# Wait for the new image tag in the Deployment spec itself (not ArgoCD's status.summary.images:
# a replicaCount-0 app never populates that field and would time out for an unrelated reason).
for pair in $(cat "${PAIRS_FILE}"); do
  participant="${pair%%:*}"
  module="${pair##*:}"
  deployment="${participant}-${module//-/}"
  namespace="${NAMESPACE_PREFIX}-${participant}"
  echo "[wait-image] ${deployment}: waiting for sha-${CI_COMMIT_SHA}..."
  ssh "ubuntu@${VM_IP}" "
    for i in \$(seq 1 60); do
      if kubectl get deployment ${deployment} -n ${namespace} -o jsonpath='{.spec.template.spec.containers[0].image}' | grep -q 'sha-${CI_COMMIT_SHA}'; then
        echo '[wait-image] ${deployment}: present after '\$((i * 5))'s'
        exit 0
      fi
      sleep 5
    done
    echo '[wait-image] ${deployment}: TIMED OUT after 300s waiting for sha-${CI_COMMIT_SHA} -- did test:e2e-build-push-deploy actually run for this commit?'
    exit 1
  "
done

for pair in $(cat "${PAIRS_FILE}"); do
  participant="${pair%%:*}"
  module="${pair##*:}"
  app="${APP_PREFIX}-${participant}-${module}"
  ssh "ubuntu@${VM_IP}" "kubectl wait --for=jsonpath='{.status.health.status}'=Healthy application/${app} -n argocd --timeout=180s"
done
