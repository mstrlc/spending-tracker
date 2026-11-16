#!/usr/bin/env bash
# Per-checkout dev environment, so several agents can each run the app in their own git worktree without touching
# each other. Every checkout gets a slot: the main checkout is slot 0 (API 8080, web 5173, database
# spending_tracker), each worktree a slot from 1 up with its own API port, web port and database in the shared
# Postgres container. See "Worktrees" in AGENTS.md.
#
#   scripts/dev.sh setup [--copy]   claim a slot, write .env.local and .claude/launch.json, create the database
#                                   (--copy fills it from the main checkout's database instead of starting empty)
#   scripts/dev.sh api              run the API on this checkout's port and database
#   scripts/dev.sh web              run the web app on this checkout's port, proxying to this checkout's API
#   scripts/dev.sh gen-api          regenerate web/src/api/schema.d.ts from this checkout's running API
#   scripts/dev.sh status           list every slot, its ports and whether they're listening
#   scripts/dev.sh teardown         drop this worktree's database and free its slot
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
COMMON=$(cd "$(git rev-parse --git-common-dir)" && pwd)
MAIN=$(dirname "$COMMON")
REGISTRY="$COMMON/dev-slots"
LOCK="$COMMON/dev-slots.lock"
DB_USER=spending_tracker
COMPOSE=(docker compose -f "$MAIN/docker-compose.yml")

api_port() { if [ "$1" -eq 0 ]; then echo 8080; else echo $((8100 + $1)); fi; }
web_port() { if [ "$1" -eq 0 ]; then echo 5173; else echo $((5300 + $1)); fi; }
db_name() { if [ "$1" -eq 0 ]; then echo spending_tracker; else echo "spending_tracker_wt$1"; fi; }
listening() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

lock() {
  local tries=0
  until mkdir "$LOCK" 2>/dev/null; do
    tries=$((tries + 1))
    [ "$tries" -gt 50 ] && { echo "dev.sh: $LOCK is held; remove it if no setup is running" >&2; exit 1; }
    sleep 0.1
  done
  trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT
}

# This checkout's slot from the registry, or nothing.
registered_slot() {
  [ "$ROOT" = "$MAIN" ] && { echo 0; return; }
  [ -f "$REGISTRY" ] && awk -F'\t' -v p="$ROOT" '$2 == p { print $1 }' "$REGISTRY"
  return 0
}

# Drops registry entries whose worktree no longer exists.
prune() {
  [ -f "$REGISTRY" ] || return 0
  local tmp
  tmp=$(mktemp)
  while IFS=$'\t' read -r slot path; do
    [ -d "$path" ] && printf '%s\t%s\n' "$slot" "$path"
  done < "$REGISTRY" > "$tmp"
  mv "$tmp" "$REGISTRY"
}

claim_slot() {
  local slot
  slot=$(registered_slot)
  if [ -n "$slot" ]; then echo "$slot"; return; fi
  prune
  slot=1
  while :; do
    if ! { [ -f "$REGISTRY" ] && cut -f1 "$REGISTRY" | grep -qx "$slot"; } &&
      ! listening "$(api_port "$slot")" && ! listening "$(web_port "$slot")"; then
      break
    fi
    slot=$((slot + 1))
  done
  printf '%s\t%s\n' "$slot" "$ROOT" >> "$REGISTRY"
  echo "$slot"
}

load_env() {
  [ -f "$ROOT/.env.local" ] || { echo "dev.sh: no .env.local here; run scripts/dev.sh setup first" >&2; exit 1; }
  set -a
  # The main checkout's .env holds local secrets (GoCardless keys); this checkout's .env.local wins over it.
  [ -f "$MAIN/.env" ] && . "$MAIN/.env"
  . "$ROOT/.env.local"
  set +a
}

write_launch() {
  local api=$1 web=$2
  mkdir -p "$ROOT/.claude"
  cat > "$ROOT/.claude/launch.json" <<EOF
{
  "version": "0.0.1",
  "configurations": [
    {
      "name": "api",
      "runtimeExecutable": "scripts/dev.sh",
      "runtimeArgs": ["api"],
      "port": $api,
      "autoPort": false
    },
    {
      "name": "web",
      "runtimeExecutable": "scripts/dev.sh",
      "runtimeArgs": ["web"],
      "port": $web,
      "autoPort": false
    }
  ]
}
EOF
}

ensure_db() {
  local db=$1 copy=$2
  "${COMPOSE[@]}" up -d --wait postgres >/dev/null
  local exists
  exists=$("${COMPOSE[@]}" exec -T postgres psql -U "$DB_USER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$db'")
  if [ "$exists" = 1 ]; then
    if [ "$copy" = 1 ]; then echo "Database $db already exists; left as it is (teardown and set up again to copy afresh)."; fi
    return 0
  fi
  "${COMPOSE[@]}" exec -T postgres createdb -U "$DB_USER" "$db"
  if [ "$copy" = 1 ]; then
    "${COMPOSE[@]}" exec -T postgres sh -c "pg_dump -U $DB_USER --no-owner spending_tracker | psql -q -U $DB_USER -d $db" >/dev/null
    echo "Copied the main checkout's database into $db."
  fi
}

cmd_setup() {
  local copy=0
  [ "${1:-}" = "--copy" ] && copy=1
  local slot
  lock
  slot=$(claim_slot)
  rmdir "$LOCK" 2>/dev/null || true
  trap - EXIT
  local api web db
  api=$(api_port "$slot"); web=$(web_port "$slot"); db=$(db_name "$slot")
  cat > "$ROOT/.env.local" <<EOF
# Written by scripts/dev.sh setup: this checkout's slot. Rerun setup rather than editing.
DEV_SLOT=$slot
PORT=$api
WEB_PORT=$web
DB_URL=jdbc:postgresql://localhost:5433/$db
APP_URL=http://localhost:$web
API_URL=http://localhost:$api
EOF
  write_launch "$api" "$web"
  [ "$slot" -eq 0 ] && copy=0
  ensure_db "$db" "$copy"
  if [ ! -d "$ROOT/web/node_modules" ]; then
    pnpm --dir "$ROOT/web" install --frozen-lockfile >/dev/null
  fi
  echo "Slot $slot: API http://localhost:$api, web http://localhost:$web, database $db."
}

cmd_api() {
  load_env
  cd "$ROOT"
  exec ./gradlew --console=plain -q :server:run
}

cmd_web() {
  load_env
  exec pnpm --dir "$ROOT/web" exec vite --port "$WEB_PORT" --strictPort
}

cmd_gen_api() {
  load_env
  exec pnpm --dir "$ROOT/web" exec openapi-typescript "$API_URL/api/openapi.json" -o src/api/schema.d.ts
}

cmd_status() {
  prune
  printf '%-5s %-6s %-6s %-26s %s\n' slot api web database checkout
  {
    printf '0\t%s\n' "$MAIN"
    [ -f "$REGISTRY" ] && sort -n "$REGISTRY"
  } | while IFS=$'\t' read -r slot path; do
    local api web a w
    api=$(api_port "$slot"); web=$(web_port "$slot")
    a="$api"; listening "$api" && a="$api*"
    w="$web"; listening "$web" && w="$web*"
    printf '%-5s %-6s %-6s %-26s %s\n' "$slot" "$a" "$w" "$(db_name "$slot")" "$path"
  done
  echo "(* listening)"
}

cmd_teardown() {
  local slot
  slot=$(registered_slot)
  [ -z "$slot" ] && { echo "This checkout has no slot."; return; }
  [ "$slot" -eq 0 ] && { echo "dev.sh: the main checkout keeps slot 0 and its database" >&2; exit 1; }
  local db
  db=$(db_name "$slot")
  "${COMPOSE[@]}" exec -T postgres dropdb -U "$DB_USER" --if-exists --force "$db"
  lock
  awk -F'\t' -v p="$ROOT" '$2 != p' "$REGISTRY" > "$REGISTRY.tmp" && mv "$REGISTRY.tmp" "$REGISTRY"
  rm -f "$ROOT/.env.local" "$ROOT/.claude/launch.json"
  echo "Dropped $db and freed slot $slot."
}

case "${1:-}" in
  setup) shift; cmd_setup "$@" ;;
  api) cmd_api ;;
  web) cmd_web ;;
  gen-api) cmd_gen_api ;;
  status) cmd_status ;;
  teardown) cmd_teardown ;;
  *) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
