#!/usr/bin/env bash
# A repository fake that can accept writes, refuse them, or store something else: all three are tested.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

log="$(mktemp)"; port=$((29000 + RANDOM % 900))
start() { python3 tools/fake_repo.py "$port" "$log" "$@" & server=$!
  for _ in $(seq 1 40); do nc -z 127.0.0.1 "$port" 2>/dev/null && break; sleep 0.1; done; }
stop() { kill $server 2>/dev/null; wait $server 2>/dev/null; }
start; trap 'stop' EXIT

ws="$(mktemp -d)"
write_input() { # write_input <paths> [extra json fragment]
  python3 - "$ws/input.json" "$port" "$1" "${2:-}" <<'PY'
import json, sys
path, port, paths, extra = sys.argv[1:5]
doc = {"sourceUrl": "http://127.0.0.1:" + port, "sourceRepository": "staging", "sourceUsername": "ci",
       "sourceToken": "source-token", "targetUrl": "http://127.0.0.1:" + port, "targetRepository": "release",
       "targetUsername": "ci", "targetToken": "target-token", "paths": paths}
if extra.strip():
    doc.update(json.loads(extra))
json.dump(doc, open(path, "w"))
PY
}

remote() { curl -sS "http://127.0.0.1:$port/$1" 2>/dev/null; }

# --- promote two artifacts, verified by digest
write_input "com/acme/app/1.2.3/app-1.2.3.jar,com/acme/app/1.2.3/app-1.2.3.pom"
run "$ws" >"$ws/atom.log" 2>&1 || fail "promote: $(cat "$ws/output.json") $(tail -3 "$ws/atom.log")"
[ "$(output "$ws" promoted)" = "2" ] || fail "count: $(cat "$ws/output.json")"
[ "$(output "$ws" verified)" = "true" ] || fail "verify flag: $(cat "$ws/output.json")"
[ "$(remote release/com/acme/app/1.2.3/app-1.2.3.jar)" = "jar-bytes" ] || fail "the jar did not arrive"
[ "$(remote release/com/acme/app/1.2.3/app-1.2.3.pom)" = "<project/>" ] || fail "the pom did not arrive"

# the two repositories get their own credentials
python3 - "$log" <<'PY' || fail "each side should use its own token"
import base64, json, sys
lines = [json.loads(l) for l in open(sys.argv[1]).read().strip().splitlines()]
gets = [l for l in lines if l["method"] == "GET" and l["path"].startswith("staging/")]
puts = [l for l in lines if l["method"] == "PUT"]
assert gets and puts, lines
assert gets[0]["auth"] == "Basic " + base64.b64encode(b"ci:source-token").decode(), gets[0]
assert puts[0]["auth"] == "Basic " + base64.b64encode(b"ci:target-token").decode(), puts[0]
PY

# --- a different target directory
write_input "com/acme/app/1.2.3/app-1.2.3.jar" '{"targetPath": "releases/acme"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "targetPath: $(cat "$ws/output.json")"
[ "$(remote release/releases/acme/app-1.2.3.jar)" = "jar-bytes" ] || fail "targetPath was ignored: $(cat "$ws/output.json")"

# --- deleteSource removes the staging copy after a verified promotion
write_input "com/acme/app/1.2.3/app-1.2.3.pom" '{"deleteSource": "true"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "deleteSource: $(cat "$ws/output.json")"
[ "$(remote release/com/acme/app/1.2.3/app-1.2.3.pom)" = "<project/>" ] || fail "the target should stay"
[ -z "$(remote staging/com/acme/app/1.2.3/app-1.2.3.pom)" ] || fail "the staging copy should be gone"

# --- a path that is not there names the repository, not just a status code
write_input "com/acme/app/9.9.9/missing.jar"
run "$ws" >"$ws/atom.log" 2>&1 && fail "a missing artifact must fail"
grep -q "cannot read com/acme/app/9.9.9/missing.jar from staging" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"
grep -q '"errorType":1' "$ws/output.json" || fail "a 404 is a USER error"

# --- a repository that refuses writes says the token lacks deploy rights
stop; start --deny-put
write_input "com/acme/app/1.2.3/app-1.2.3.jar"
run "$ws" >"$ws/atom.log" 2>&1 && fail "a refused write must fail"
grep -q "rights on release" "$ws/output.json" || fail "403 hint: $(cat "$ws/output.json")"

# --- a repository that accepts the write but stores something else is caught by the digest
stop; start --corrupt-target
write_input "com/acme/app/1.2.3/app-1.2.3.jar"
run "$ws" >"$ws/atom.log" 2>&1 && fail "a corrupted copy must fail"
grep -q "differs from the source" "$ws/output.json" || fail "digest mismatch message: $(cat "$ws/output.json")"

# --- and with verify off it is not even noticed, which is the point of the default
write_input "com/acme/app/1.2.3/app-1.2.3.jar" '{"verify": "false"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "verify=false should not compare digests: $(cat "$ws/output.json")"

# --- bad input
stop; start
write_input ""
run "$ws" >"$ws/atom.log" 2>&1 && fail "an empty path list must fail"
grep -q "paths is required" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

echo "smoke ok: verified promotion, per-side credentials, targetPath, deleteSource, 404, deploy rights, digest mismatch, verify=false, bad input"
