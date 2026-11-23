#!/usr/bin/env bash
# Points deploy/pagerewind at one build of every PageRewind image, pinned by digest:
#   newTag: sha-<commit>@sha256:<digest>
# The tag keeps it readable; the digest makes it exact, so the image that runs is the image that was
# scanned and signed, even if someone moves the tag later. Used by the images workflow's deploy job.
#
# Usage: scripts/pin-images.sh <tag> [owner]     e.g. scripts/pin-images.sh sha-41a4f5b
set -euo pipefail

tag="${1:?usage: pin-images.sh <tag> [owner]}"
owner="$(echo "${2:-yohan-senadheera}" | tr '[:upper:]' '[:lower:]')"
file="$(dirname "$0")/../deploy/pagerewind/kustomization.yaml"
services=(gateway ingest incident schedule notify status capture)

for service in "${services[@]}"; do
  image="ghcr.io/$owner/pagerewind-$service"
  digest="$(docker buildx imagetools inspect "$image:$tag" --format '{{json .Manifest.Digest}}' | tr -d '"')"
  if ! [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "No digest for $image:$tag" >&2
    exit 1
  fi
  # The newTag line right after this image's name line.
  sed -i -E "\#^  - name: $image\$#{n;s#^(    newTag: ).*#\1$tag@$digest#}" "$file"
  echo "$service -> $tag@$digest"
done

pinned="$(grep -c -E "^    newTag: $tag@sha256:[0-9a-f]{64}$" "$file")"
if [ "$pinned" -ne "${#services[@]}" ]; then
  echo "Expected ${#services[@]} pinned images, found $pinned" >&2
  exit 1
fi
