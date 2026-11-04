#!/usr/bin/env bash
# Put the local stack back to its seeded demo data: the demo CPs, covenants and results that the Core
# migrations load, with none of the uploads, checks, tasks or e-mails added since (by you or by the
# browser tests).
#
# It deletes two Docker volumes: sqldata (the SQL Server database) and dpsdata (DPS documents and
# jobs). The docling model cache is kept, so DPS does not download its models again. Keycloak users
# come from deploy/keycloak/covenant-realm.json and are recreated as they were.
#
#   scripts/reset-demo-data.sh            asks before deleting anything
#   scripts/reset-demo-data.sh --yes      no question (for scripts)
#   scripts/reset-demo-data.sh --dry-run  prints what it would run, changes nothing
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.real.yml --env-file deploy/.env)
VOLUMES=(sqldata dpsdata)

yes=false
dry=false
for arg in "$@"; do
  case "$arg" in
    --yes) yes=true ;;
    --dry-run) dry=true ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

run() {
  if $dry; then
    echo "+ $*"
  else
    "$@"
  fi
}

if [ ! -f deploy/.env ]; then
  echo "deploy/.env is missing; copy deploy/.env.example and fill it in first." >&2
  exit 1
fi

project=$("${COMPOSE[@]}" config --format json 2>/dev/null | python -c "import json,sys;print(json.load(sys.stdin)['name'])" \
  || echo covenant-platform)

if ! $yes && ! $dry; then
  echo "This deletes all local covenant data (database volume ${project}_sqldata and DPS volume ${project}_dpsdata)"
  echo "and brings the stack back with the seeded demo data only."
  read -r -p "Type RESET to go on: " answer
  [ "$answer" = "RESET" ] || { echo "Nothing changed."; exit 1; }
fi

run "${COMPOSE[@]}" down
for v in "${VOLUMES[@]}"; do
  run docker volume rm -f "${project}_${v}"
done
run "${COMPOSE[@]}" up -d

if $dry; then
  echo "+ wait for http://localhost:9310/readyz and the stack-init container"
  exit 0
fi

echo "Waiting for Core (migrations load the demo data)..."
for _ in $(seq 1 120); do
  if curl -sf http://localhost:9310/readyz >/dev/null; then
    break
  fi
  sleep 5
done
curl -sf http://localhost:9310/readyz >/dev/null || { echo "Core did not become ready; see: docker compose logs core" >&2; exit 1; }

echo "Waiting for stack-init (points the demo data sources at SQL Server)..."
for _ in $(seq 1 60); do
  state=$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "${project}-stack-init-1" 2>/dev/null || echo "missing 1")
  case "$state" in
    "exited 0") echo "Done. Open http://localhost:8080"; exit 0 ;;
    exited*) echo "stack-init failed ($state); see: docker logs ${project}-stack-init-1" >&2; exit 1 ;;
  esac
  sleep 5
done
echo "stack-init is still running; check: docker logs ${project}-stack-init-1" >&2
exit 1
