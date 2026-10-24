#!/usr/bin/env bash
# Usage: check-release-version.sh <tag> <jar>...
# Fails unless every jar's fabric.mod.json version, minus the +<mc> suffix, equals the tag
# minus its leading "v". Beta.24 shipped a jar that reported 0.1.0-alpha.1.
set -euo pipefail

if [ "$#" -lt 2 ]; then
	echo "usage: $0 <tag> <jar>..." >&2
	exit 2
fi

tag="$1"
shift
expected="${tag#v}"
status=0
python=$(command -v python3 || command -v python)

for jar in "$@"; do
	version=$(unzip -p "$jar" fabric.mod.json | "$python" -c 'import json, sys; print(json.load(sys.stdin)["version"])')
	if [ "${version%%+*}" = "$expected" ]; then
		echo "ok: $(basename "$jar") reports $version (tag $tag)"
	else
		echo "MISMATCH: $(basename "$jar") reports $version, tag $tag expects $expected" >&2
		status=1
	fi
done

exit "$status"
