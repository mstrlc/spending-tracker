#!/usr/bin/env bash
# Deploys the app to a Docker host over ssh (see "Self-hosting" in the README).
#
#   scripts/deploy.sh             build images for the host's platform here, load them there, restart the app
#   scripts/deploy.sh --no-build  reuse the images last built here
#
# Reads deploy/local.env (gitignored): DEPLOY_HOST (an ssh destination), and optionally DEPLOY_DIR (default
# spending-tracker, relative to the remote home) and DEPLOY_PLATFORM (default linux/arm64). The host needs Docker
# with Compose and <DEPLOY_DIR>/.env (from deploy/server.env.example). New migrations run when the API starts.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
[[ -f deploy/local.env ]] && source deploy/local.env
host=${DEPLOY_HOST:?set DEPLOY_HOST in deploy/local.env}
dir=${DEPLOY_DIR:-spending-tracker}
platform=${DEPLOY_PLATFORM:-linux/arm64}

ssh "$host" "test -f $dir/.env" || {
  echo "$host:$dir/.env is missing: copy deploy/server.env.example there and fill it in." >&2
  exit 1
}

if [[ ${1:-} != --no-build ]]; then
  echo "Building $platform images…"
  docker buildx build --platform "$platform" -f server/Dockerfile -t spending-tracker-api:latest --load .
  docker buildx build --platform "$platform" -t spending-tracker-web:latest --load web
fi

echo "Copying images to ${host}…"
docker save spending-tracker-api:latest spending-tracker-web:latest | gzip -1 | ssh "$host" 'gunzip | docker load'

echo "Updating the app on ${host}…"
scp -q deploy/compose.prod.yml "$host:$dir/compose.yml"
scp -q deploy/backup.sh "$host:$dir/backup.sh"
ssh "$host" "cd $dir && chmod 700 backup.sh && docker compose up -d --wait --remove-orphans && docker image prune -f >/dev/null"

# The nightly backup runs as root, as backup directories are often root-owned. Installed once, kept on redeploys.
backup="\$HOME/$dir/backup.sh"
ssh "$host" "b=$backup; line=\"30 3 * * * \$b >> \${b%.sh}.log 2>&1\";
  if sudo -n true 2>/dev/null; then
    (sudo crontab -l 2>/dev/null | grep -vF \"\$b\"; echo \"\$line\") | sudo crontab -
  else
    echo \"No passwordless sudo: add this to root's crontab for nightly backups: \$line\"
  fi"

echo "Deployed."
