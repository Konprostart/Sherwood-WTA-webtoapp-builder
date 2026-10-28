#!/usr/bin/env bash
# Pins the release signer: the APK must have exactly one signer, and that signer's
# certificate SHA-256 must equal the expected digest. Any other valid signature,
# including a debug key, is rejected.
#
# Usage: check-signer-pin.sh <apksigner> <apk> <expected-sha256>
set -euo pipefail

apksigner="$1"
apk="$2"
expected_raw="${3:-}"

if [ -z "$expected_raw" ]; then
  echo "::error::Repository variable RELEASE_CERT_SHA256 is not set; refusing to publish an unpinned release"
  exit 1
fi

normalize() { tr -d ':[:space:]' | tr 'A-F' 'a-f'; }

expected="$(printf '%s' "$expected_raw" | normalize)"
if ! printf '%s' "$expected" | grep -Eq '^[0-9a-f]{64}$'; then
  echo "::error::RELEASE_CERT_SHA256 is not a SHA-256 digest"
  exit 1
fi

output="$("$apksigner" verify --print-certs "$apk")"

if printf '%s\n' "$output" | grep -q '^Signer #2 '; then
  echo "::error::APK has more than one signer; refusing to publish"
  exit 1
fi

actual="$(printf '%s\n' "$output" \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
  | normalize)"

if [ -z "$actual" ]; then
  echo "::error::Could not read the signer certificate digest from apksigner output"
  exit 1
fi

if [ "$actual" != "$expected" ]; then
  echo "::error::Signer certificate SHA-256 $actual does not match the pinned release certificate"
  exit 1
fi

echo "Signer pin OK: $actual"
