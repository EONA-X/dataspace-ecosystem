#!/usr/bin/env bash
# Run via ssh on the e2e VM itself before each test:e2e run (see test.yml). Vault can reseal
# on its own (pod restart/eviction) without the VM ever rebooting -- confirmed live on
# lourmel-e2e, sealed for 35h with nobody noticing until a pipeline actually needed it.
# Self-contained: discovers participant namespaces instead of needing them passed in.
set -uo pipefail

for ns in $(kubectl get ns -o name | grep '^namespace/e2e-dse-' | cut -d/ -f2); do
  p="${ns#e2e-dse-}"
  vault_pod="${p}-vault-0"

  if ! kubectl get pod "$vault_pod" -n "$ns" &>/dev/null; then
    continue
  fi

  # `vault status` exits 2 when sealed -- not a real error, so don't let it trip us up.
  SEALED_JSON=$(kubectl exec "$vault_pod" -n "$ns" -- vault status -format=json 2>/dev/null)
  SEALED=$(echo "$SEALED_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('sealed', True))" 2>/dev/null || echo "True")

  if [ "$SEALED" = "True" ]; then
    echo "[vault] ${p}: sealed, unsealing..."
    KEY=$(kubectl get secret "${p}-vault-unseal-key" -n "$ns" -o jsonpath='{.data.key}' 2>/dev/null | base64 -d)
    if [ -z "$KEY" ]; then
      echo "[vault] ${p}: WARNING no stored unseal key found, skipping."
      continue
    fi
    kubectl exec "$vault_pod" -n "$ns" -- vault operator unseal "$KEY" >/dev/null
    echo "[vault] ${p}: unsealed."
  else
    echo "[vault] ${p}: already unsealed."
  fi

  STUCK=$(kubectl get pods -n "$ns" --no-headers 2>/dev/null | awk '$3 == "CrashLoopBackOff" {print $1}')
  if [ -n "$STUCK" ]; then
    echo "[vault] ${p}: restarting crashlooping pods: $STUCK"
    # shellcheck disable=SC2086 -- intentional word-splitting, $STUCK is a list of pod names
    kubectl delete pod $STUCK -n "$ns"
  fi
done
