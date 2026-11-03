#!/usr/bin/env bash
# Scan the full git history of this repo before it is (or while it is) public.
# Prints only counts, files, commit ids and pattern numbers, never matched text.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
config="$script_dir/../.gitleaks.toml"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail=0

echo "== gitleaks"
if ! gitleaks git --log-opts=--all --redact --config "$config" . ; then
  echo "gitleaks: findings"
  fail=1
fi

# Added lines and commit messages of the whole history, as two aligned files:
# meta.tsv (commit, file) and body.txt (line text).
git log --all -p --format='@@commit %H' -- . \
  ':(exclude)scripts/public_check.sh' \
  ':(exclude).gitleaks.toml' \
  ':(exclude)scripts/tests/test_public_check.py' \
  ':(exclude)core/api/src/test' |
  awk -v meta="$tmp/meta.tsv" -v body="$tmp/body.txt" '
    /^@@commit / { commit = substr($0, 10); file = "(message)"; next }
    /^\+\+\+ / { f = substr($0, 5); sub(/^b\//, "", f); file = f; next }
    /^--- / { next }
    /^\+/ { print commit "\t" file > meta; print substr($0, 2) > body; next }
    /^    / { print commit "\t(message)" > meta; print substr($0, 5) > body }
  '
touch "$tmp/meta.tsv" "$tmp/body.txt"

report() { # report <label> <pattern file>
  local label="$1" pfile="$2" nums
  nums="$(grep -nEf "$pfile" "$tmp/body.txt" | cut -d: -f1 || true)"
  if [ -n "$nums" ]; then
    echo "$label: $(printf '%s\n' "$nums" | wc -l) matching added line(s)"
    printf '%s\n' "$nums" |
      awk -F'\t' 'NR==FNR { want[$1] = 1; next } (FNR in want) { print $1 "\t" $2 }' - "$tmp/meta.tsv" |
      sort -u | sed 's/^/  commit /; s/\t/ file /'
    fail=1
  fi
}

echo "== history patterns"
patterns=(
  '(^|[^0-9.])10\.[0-9]+\.[0-9]+\.[0-9]+'
  '(^|[^0-9.])192\.168\.'
  '(^|[^0-9.])172\.(1[6-9]|2[0-9]|3[01])\.'
  '\.local\b'
  '\.svc\b'
  'Bearer [A-Za-z0-9]'
  '-----BEGIN'
)
for i in "${!patterns[@]}"; do
  printf '%s\n' "${patterns[$i]}" > "$tmp/p.txt"
  report "builtin pattern $((i + 1))" "$tmp/p.txt"
done

echo "== keystores"
ks="$(git log --all --name-only --format='@@commit %H' |
  awk '/^@@commit / { c = substr($0, 10); next } /\.(jks|keystore)$/ { print "  commit " c " file " $0 }' | sort -u)"
if [ -n "$ks" ]; then
  echo "keystore files in history: $(printf '%s\n' "$ks" | wc -l)"
  printf '%s\n' "$ks"
  fail=1
fi

if [ -n "${PUBLIC_DENYLIST:-}" ]; then
  echo "== denylist"
  n=0
  while IFS= read -r line || [ -n "$line" ]; do
    [ -z "$line" ] && continue
    n=$((n + 1))
    printf '%s\n' "$line" > "$tmp/p.txt"
    report "denylist pattern $n" "$tmp/p.txt"
  done <<< "$PUBLIC_DENYLIST"
fi

echo "== identity"
bad="$(git log --all --format='%H|%an|%ae|%cn|%ce' | awk -F'|' '
  function ok(name, email) {
    if (email ~ /\[bot\]@users\.noreply\.github\.com$/) return 1
    if (email ~ /@users\.noreply\.github\.com$/ && name == "szymonrychu") return 1
    return 0
  }
  !ok($2, $3) { print $1 " author"; next }
  !(ok($4, $5) || ($4 == "GitHub" && $5 == "noreply@github.com")) { print $1 " committer" }
')"
if [ -n "$bad" ]; then
  echo "identity violations: $(printf '%s\n' "$bad" | wc -l)"
  printf '%s\n' "$bad" | sed 's/^/  commit /'
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "public-check: FAILED"
  exit 1
fi
echo "public-check: ok"
