#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

[[ ${EUID:-$(id -u)} -eq 0 ]] || { printf 'run as root from the server console\n' >&2; exit 77; }
[[ $# -eq 2 ]] || { printf 'usage: bootstrap-aioj-host.sh <repository-root> <deploy-public-key-file>\n' >&2; exit 64; }

source_root=$(readlink -f -- "$1")
key_file=$(readlink -f -- "$2")
[[ -f $source_root/deploy/compose.production.yml ]] || { printf 'invalid repository/release root\n' >&2; exit 66; }
[[ -f $key_file ]] || { printf 'missing deployment public key\n' >&2; exit 66; }

public_key=$(<"$key_file")
[[ $public_key == ssh-ed25519\ * ]] || { printf 'deployment key must be Ed25519\n' >&2; exit 65; }

if ! id aioj-deploy >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash aioj-deploy
fi
passwd -l aioj-deploy >/dev/null
usermod --shell /bin/bash aioj-deploy

deploy_groups=$(id -nG aioj-deploy)
if grep -Eq '(^|[[:space:]])(docker|sudo)([[:space:]]|$)' <<<"$deploy_groups"; then
  printf 'refusing unsafe existing aioj-deploy group membership: %s\n' "$deploy_groups" >&2
  exit 65
fi

install -d -o root -g root -m 0755 /opt/aioj
install -d -o root -g root -m 0700 /opt/aioj/env /opt/aioj/deploy-history /opt/aioj/backups
install -m 0644 "$source_root/deploy/compose.production.yml" /opt/aioj/compose.production.yml
install -m 0755 "$source_root/scripts/deploy/aioj-deploy" /usr/local/sbin/aioj-deploy
install -m 0755 "$source_root/scripts/deploy/aioj-deploy-gate" /usr/local/sbin/aioj-deploy-gate
install -m 0755 "$source_root/scripts/deploy/aioj-health-check" /usr/local/sbin/aioj-health-check

install -d -o aioj-deploy -g aioj-deploy -m 0700 /home/aioj-deploy/.ssh
printf 'restrict,command="sudo -n /usr/local/sbin/aioj-deploy-gate" %s\n' "$public_key" > /home/aioj-deploy/.ssh/authorized_keys
chown aioj-deploy:aioj-deploy /home/aioj-deploy/.ssh/authorized_keys
chmod 0600 /home/aioj-deploy/.ssh/authorized_keys

cat >/etc/sudoers.d/aioj-deploy <<'EOF'
Defaults:aioj-deploy env_keep += "SSH_ORIGINAL_COMMAND"
aioj-deploy ALL=(root) NOPASSWD: /usr/local/sbin/aioj-deploy-gate
EOF
chmod 0440 /etc/sudoers.d/aioj-deploy
visudo -cf /etc/sudoers.d/aioj-deploy >/dev/null

if [[ ! -f /opt/aioj/env/app.env ]]; then
  install -m 0600 "$source_root/deploy/env/production.env.example" /opt/aioj/env/app.env.example
fi

printf 'Bootstrap complete. Configure /opt/aioj/env/app.env and root GHCR read credentials before deployment.\n'
