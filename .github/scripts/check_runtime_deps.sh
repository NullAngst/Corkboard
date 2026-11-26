#!/usr/bin/env bash
# Fails when the release APK would carry any library besides the Kotlin standard library.
#
# Corkboard is built on Android's own framework only. Test libraries (JUnit, org.json) stay out of
# the APK; this makes sure of it, and makes any new runtime library a deliberate change to the
# list below instead of something that slips in.
set -euo pipefail
export LC_ALL=C

# group:name pairs allowed at runtime. kotlin-stdlib comes with Kotlin; annotations comes with it.
allowed='^(org\.jetbrains\.kotlin:kotlin-stdlib|org\.jetbrains:annotations)$'

missing=""
for c in sed sort grep; do
    command -v "$c" >/dev/null 2>&1 || missing="$missing $c"
done
if [ -n "$missing" ]; then
    printf 'Missing commands:%s\n' "$missing" >&2
    exit 1
fi

tree=$(bash ./gradlew --no-daemon -q :app:dependencies --configuration releaseRuntimeClasspath)

# Tree lines look like "+--- group:name:1.0" or "|    \--- group:name:1.0 -> 1.1 (*)".
deps=$(printf '%s\n' "$tree" | sed -n 's/^[|+\\ -]*\([A-Za-z0-9_.-]*:[A-Za-z0-9_.-]*\):.*/\1/p' | sort -u)

# Reading the tree must have worked: Kotlin's standard library is always there.
if ! printf '%s\n' "$deps" | grep -q '^org\.jetbrains\.kotlin:kotlin-stdlib$'; then
    printf 'Could not read the release dependencies. Gradle printed:\n%s\n' "$tree" >&2
    exit 1
fi

extra=$(printf '%s\n' "$deps" | grep -Ev "$allowed" || true)
if [ -n "$extra" ]; then
    printf 'The release build pulls in libraries besides the Kotlin standard library:\n%s\n' "$extra" >&2
    printf 'If that is intended, add them to "allowed" in %s and explain why in changes.md.\n' "$0" >&2
    exit 1
fi

printf 'Release runtime libraries:\n%s\n' "$deps"
