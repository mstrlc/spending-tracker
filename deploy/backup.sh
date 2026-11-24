#!/usr/bin/env bash
# Nightly database dump on the Docker host, installed in root's crontab by scripts/deploy.sh. Writes a pg_dump
# custom-format file to BACKUP_DIR (from the .env next to this script) and keeps the last 30 days.
# Restore: docker exec -i spending-postgres pg_restore -U spending_tracker -d spending_tracker --clean --if-exists < FILE
#
# Off-site copy (optional): with BACKUP_REMOTE and BACKUP_AGE_RECIPIENT set, the dump is also encrypted with age to
# that public key and uploaded with rclone, using rclone.conf next to this script. The remote keeps 30 days of
# dailies and the 1st of each month for a year. Only the public key is on this host, so it can't read the copies.
# Restore one: rclone --config rclone.conf cat REMOTE/FILE.age | age -d -i KEY_FILE > FILE
set -euo pipefail
umask 077

here=$(cd "$(dirname "$0")" && pwd)
setting() { sed -n "s/^$1=//p" "$here/.env" | tail -1; }
dir=$(setting BACKUP_DIR)
[[ -n $dir ]] || { echo "BACKUP_DIR is not set in $here/.env" >&2; exit 1; }

mkdir -p "$dir"
chmod 700 "$dir"
name="spending_tracker-$(date +%F).dump"
file="$dir/$name"
docker exec spending-postgres pg_dump -U spending_tracker -Fc spending_tracker > "$file.part"
mv "$file.part" "$file"
find "$dir" -name 'spending_tracker-*.dump' -mtime +30 -delete

remote=$(setting BACKUP_REMOTE)
[[ -n $remote ]] || exit 0
recipient=$(setting BACKUP_AGE_RECIPIENT)
[[ -n $recipient ]] || { echo "BACKUP_REMOTE is set but BACKUP_AGE_RECIPIENT isn't; not uploading unencrypted" >&2; exit 1; }
rclone() { command rclone --config "$here/rclone.conf" "$@"; }

age -r "$recipient" < "$file" | rclone rcat "$remote/$name.age"
rclone delete "$remote" --min-age 31d \
  --filter '- spending_tracker-*-01.dump.age' --filter '+ spending_tracker-*.dump.age' --filter '- *'
rclone delete "$remote" --min-age 366d --include 'spending_tracker-*.dump.age'
echo "$(date '+%F %T') uploaded $name.age to $remote"
