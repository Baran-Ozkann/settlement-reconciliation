#!/usr/bin/env bash
# ci/check-rules.sh - project rules a compiler and a test suite do not see, modelled on the
# ledger-payment-core script of the same name.
#
# Every check distinguishes "found nothing" from "could not look". grep exits 0 on a match, 1 on no
# match, and 2 or more when it could not complete the search - most often because a path in the
# list does not exist - and it exits 2 even when it also found matches. Reading any non-zero status
# as "clean" would silently switch a check off, so only 1 counts as clean and everything else fails
# the build.
set -uo pipefail
violation=0

guard_error() {
  local desc="$1" status="$2" out="$3"
  echo "GUARD ERROR: could not run check '$desc' (exit $status)"
  echo "$out" | head -5
  violation=1
}

# Fails when grep finds the pattern.
deny() {
  local desc="$1"; shift
  local out status
  out=$(grep -rn "$@" 2>&1)
  status=$?
  case $status in
    0)
      echo "RULE VIOLATION: $desc"
      echo "$out" | head -20
      violation=1
      ;;
    1)
      : # no match, clean
      ;;
    *)
      guard_error "$desc" "$status" "$out"
      ;;
  esac
}

# As deny, but a hit under the given path prefix is allowed. grep's --exclude-dir matches a
# directory's base name anywhere in the tree, which is looser than one allowed location, so the
# filtering is done on the reported path instead.
deny_outside() {
  local allowed="$1" desc="$2"; shift 2
  local out status offending
  out=$(grep -rn "$@" 2>&1)
  status=$?
  case $status in
    0)
      offending=$(printf '%s\n' "$out" | grep -v "^$allowed")
      if [ -n "$offending" ]; then
        echo "RULE VIOLATION: $desc"
        echo "$offending" | head -20
        violation=1
      fi
      ;;
    1)
      : # no match, clean
      ;;
    *)
      guard_error "$desc" "$status" "$out"
      ;;
  esac
}

# The range the message check reads. On a branch, origin/main..HEAD is exactly what the branch adds.
# On main once everything is pushed that range is empty, and a check against it would pass by
# reading nothing, so fall back to the whole history of HEAD there.
range='origin/main..HEAD'
if ! git rev-parse --verify --quiet origin/main >/dev/null 2>&1 ||
   [ -z "$(git rev-list --max-count=1 "$range" 2>/dev/null)" ]; then
  range='HEAD'
fi

# deny() greps the working tree, so it cannot see a commit message. This reads the log.
deny_log() {
  local desc="$1"; shift
  local shas sha msg messages out status

  shas=$(git rev-list "$range" 2>&1)
  status=$?
  if [ $status -ne 0 ]; then
    guard_error "listing the commits in '$range'" "$status" "$shas"
    return
  fi

  # Each line carries its commit, so a hit names the commit to fix.
  messages=''
  for sha in $shas; do
    msg=$(git log -1 --format='%B' "$sha" 2>&1)
    status=$?
    if [ $status -ne 0 ]; then
      guard_error "reading the message of $sha" "$status" "$msg"
      return
    fi
    messages+=$(printf '%s\n' "$msg" | sed "s|^|${sha:0:9} |")$'\n'
  done

  out=$(printf '%s' "$messages" | grep "$@")
  status=$?
  case $status in
    0)
      echo "RULE VIOLATION: $desc (range $range)"
      echo "$out" | head -20
      violation=1
      ;;
    1)
      : # no match, clean
      ;;
    *)
      guard_error "$desc" "$status" "$out"
      ;;
  esac
}

# INV-8. ArchUnit sees fields, signatures and calls; only the source shows a local variable.
deny "floating point in the money path (domain, application)" \
  -E '\b(double|float|Double|Float)\b' --include='*.java' \
  src/main/java/com/baran/recon/domain src/main/java/com/baran/recon/application

# INV-8, TDD 6: BigDecimal exists only to convert CSV decimals exactly, inside the parser.
deny_outside 'src/main/java/com/baran/recon/adapters/in/file/' \
  "BigDecimal outside the file-parsing adapter" \
  -E '\bBigDecimal\b' --include='*.java' src/main/java

deny "TODO/FIXME left in source" \
  -E 'TODO|FIXME' --include='*.java' --include='*.sql' --include='*.yml' --include='*.yaml' \
  --include='*.properties' --include='*.xml' src pom.xml

deny "JPA/Hibernate dependency present (TDD 5.1.1)" \
  -E 'starter-data-jpa|spring-data-jpa|hibernate|jakarta\.persistence' pom.xml

# TDD 9.1: a reused container keeps a previous run's schema and rows, and the ledger once had a
# suite that passed with Flyway switched off because of it.
deny "Testcontainers reuse switched on" \
  -E 'withReuse\(true\)|testcontainers\.reuse\.enable\s*=\s*true' src

# Test containers publish on loopback only, and survive this rule even if the test that inspects
# the bindings is deleted. A container built anywhere but the one factory skips its port binding;
# a binding to any address but loopback, or host networking, publishes beyond this machine.
deny "test container built outside LoopbackContainers" \
  -E 'new (PostgreSQLContainer|KafkaContainer|ConfluentKafkaContainer|GenericContainer)\b' \
  --include='*.java' --exclude='LoopbackContainers.java' src/test/java

deny "test container port bound to an address other than loopback" \
  -P 'Binding\.(bindPort|empty)\(|Binding\.bindIp(AndPort)?\((?!"127\.0\.0\.1"|LOOPBACK\b)|withNetworkMode\("host"\)' \
  --include='*.java' src/test/java

# CLAUDE.md 3.2: a published port without the 127.0.0.1 prefix is published on every interface.
deny "docker compose port published beyond loopback" \
  -E '^\s*-\s*"?(\$\{[A-Z_]+(:-[0-9]+)?\}|[0-9]+):' docker-compose.yml

deny "AI tool reference in committed content" \
  -iE 'co-authored-by|generated (by|with)|anthropic|copilot|chatgpt' \
  --exclude='reference-ledger-tdd.md' \
  src docs contracts

# CLAUDE.md is a filename the history legitimately names, so the tool is matched only where it
# cannot be one: a trailer, a vendor domain, a session link.
deny_log "AI tool reference in a commit message" \
  -iE 'co-authored-by|generated (by|with)|anthropic|copilot|chatgpt|claude\.ai|claude-session'

exit $violation
