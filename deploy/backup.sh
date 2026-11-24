#!/usr/bin/env bash
# Nightly database dump on the Docker host, installed in root's crontab by scripts/deploy.sh. Writes a pg_dump
# custom-format file to BACKUP_DIR (from the .env next to this script) and keeps the last 30 days.
# Restore: docker exec -i spending-postgres pg_restore -U spending_tracker -d spending_tracker --clean --if-exists < FILE
set -euo pipefail
umask 077

here=$(cd "$(dirname "$0")" && pwd)
dir=$(sed -n 's/^BACKUP_DIR=//p' "$here/.env" | tail -1)
[[ -n $dir ]] || { echo "BACKUP_DIR is not set in $here/.env" >&2; exit 1; }

mkdir -p "$dir"
chmod 700 "$dir"
file="$dir/spending_tracker-$(date +%F).dump"
docker exec spending-postgres pg_dump -U spending_tracker -Fc spending_tracker > "$file.part"
mv "$file.part" "$file"
find "$dir" -name 'spending_tracker-*.dump' -mtime +30 -delete
