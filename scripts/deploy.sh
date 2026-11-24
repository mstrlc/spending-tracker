#!/usr/bin/env bash
# Deploys the app to a Docker host over ssh (see "Self-hosting" in the README).
#
#   scripts/deploy.sh             build images for the host's platform here, copy the changed ones there, restart
#   scripts/deploy.sh api|web     only build and copy that image
#   scripts/deploy.sh --no-build  reuse the images last built here
#   scripts/deploy.sh --force     copy the images even if the host already has them
#
# An unchanged image rebuilds from the build cache in seconds with the same ID, so only images whose ID differs from
# the one last copied (recorded in <DEPLOY_DIR>/.images on the host) are sent. --provenance=false keeps the ID stable:
# the provenance attestation carries a build timestamp.
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

build=1 force=0 apps=()
for arg in "$@"; do
  case $arg in
    --no-build) build=0 ;;
    --force) force=1 ;;
    api | web) apps+=("$arg") ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done
(( ${#apps[@]} )) || apps=(api web)

# Lines of "<app> <image id>" for the images last copied to the host.
shipped=$(ssh "$host" "test -f $dir/.env && { cat $dir/.images 2>/dev/null || true; }") || {
  echo "$host:$dir/.env is missing: copy deploy/server.env.example there and fill it in." >&2
  exit 1
}
shipped_id() { awk -v app="$1" '$1 == app { print $2 }' <<<"$shipped"; }

for app in "${apps[@]}"; do
  (( build )) || break
  echo "Building the $platform $app image…"
  case $app in
    api) docker buildx build --provenance=false --platform "$platform" -f server/Dockerfile -t spending-tracker-api:latest --load . ;;
    web) docker buildx build --provenance=false --platform "$platform" -t spending-tracker-web:latest --load web ;;
  esac
done

images=() record=""
for app in api web; do
  id=$(shipped_id "$app")
  if [[ " ${apps[*]} " == *" $app "* ]]; then
    local_id=$(docker image inspect -f '{{.Id}}' "spending-tracker-$app:latest")
    if (( force )) || [[ $local_id != "$id" ]]; then
      images+=("spending-tracker-$app:latest")
      id=$local_id
    else
      echo "The $app image is unchanged, not copying it."
    fi
  fi
  [[ -n $id ]] && record+="$app $id"$'\n'
done

if (( ${#images[@]} )); then
  echo "Copying ${images[*]} to ${host}…"
  docker save "${images[@]}" | gzip -1 | ssh "$host" 'gunzip | docker load'
  printf '%s' "$record" | ssh "$host" "cat > $dir/.images"
fi

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
