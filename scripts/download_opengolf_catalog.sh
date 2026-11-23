#!/usr/bin/env bash
set -euo pipefail

asset="app/src/main/assets/opengolfapi-us.geojson.gz"
expected_compressed="c3b3bd4b1b3894fe5b9a6f0e84df43472f55aaad2602faeb58ef56a709db1a39"
expected_geojson="fb4aa24aa8bce2fe338812bc316a2067720c6ffb7874429f5c037b31578300db"

mkdir -p "$(dirname "$asset")"

if [[ -f "$asset" ]] && [[ "$(sha256sum "$asset" | cut -d ' ' -f 1)" == "$expected_compressed" ]]; then
  echo "OpenGolfAPI catalog already present and verified."
else
  temporary="${asset}.download"
  rm -f "$temporary"
  curl --fail --location --retry 3 \
    https://github.com/opengolfapi/data/releases/download/v2.1.0/opengolfapi-us.geojson.gz \
    -o "$temporary"
  echo "$expected_compressed  $temporary" | sha256sum --check
  gzip --test "$temporary"
  actual_geojson="$(gzip --decompress --stdout "$temporary" | sha256sum | cut -d ' ' -f 1)"
  if [[ "$actual_geojson" != "$expected_geojson" ]]; then
    echo "OpenGolfAPI GeoJSON content checksum mismatch." >&2
    rm -f "$temporary"
    exit 1
  fi
  mv "$temporary" "$asset"
fi

echo "Verified OpenGolfAPI v2.1.0 US catalog:"
ls -lh "$asset"
