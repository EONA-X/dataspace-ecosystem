#!/bin/sh
# MUST be sourced (`. scripts/ssh-setup.sh`), never executed. ssh-agent's exports
# (SSH_AUTH_SOCK, SSH_AGENT_PID) must land in the calling job's shell, not a subshell that
# dies right after -- the job's later `ssh`/gradle steps rely on that same agent socket.
set -eux

command -v ssh-agent >/dev/null || (apt-get update -qq && apt-get install -qq -y openssh-client)
eval $(ssh-agent -s)
# LOURMEL_E2E_SSH_KEY is a File-type CI/CD variable -- its value is a path to the key file.
# GitLab writes that file 0644/0664; ssh-add refuses keys readable by group/other.
chmod 600 "$LOURMEL_E2E_SSH_KEY"
# GitLab's File-variable mechanism drops the key's trailing newline -- OpenSSH's parser
# needs it ("error in libcrypto" otherwise, even on a valid key).
[ -z "$(tail -c1 "$LOURMEL_E2E_SSH_KEY")" ] || echo >> "$LOURMEL_E2E_SSH_KEY"
ssh-add "$LOURMEL_E2E_SSH_KEY"
mkdir -p ~/.ssh && chmod 700 ~/.ssh

# Pinned, not ssh-keyscan'd fresh each run (TOFU would trust a MITM on this path).
# Explicit check, not just `set -u`: empty here almost always means the variable is
# flagged "Protected" while this job runs on a non-protected ref (only `main` is
# protected in this project) -- uncheck "Protect variable" to match LOURMEL_E2E_SSH_KEY.
if [ -z "${LOURMEL_E2E_VM_KNOWN_HOSTS:-}" ]; then
  echo "LOURMEL_E2E_VM_KNOWN_HOSTS is empty/unset -- check Settings > CI/CD > Variables" >&2
  exit 1
fi
echo "$LOURMEL_E2E_VM_KNOWN_HOSTS" >> ~/.ssh/known_hosts
