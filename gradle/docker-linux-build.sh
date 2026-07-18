#!/usr/bin/env bash
# Full Linux build + package inside an official Gradle JDK container.
# Invoked by build.bat --linux when Docker is the backend (no host JDK required).
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export APPIMAGE_EXTRACT_AND_RUN=1

missing=
command -v file >/dev/null 2>&1 || missing="$missing file"
if [ "${BUILD_DEB:-false}" = "true" ]; then
  command -v fakeroot >/dev/null 2>&1 || missing="$missing fakeroot"
fi
if [ -n "$missing" ]; then
  apt-get update -qq
  apt-get install -y -qq $missing
fi

cd /workspace
command -v gradle >/dev/null 2>&1 || { echo "Missing gradle in Docker image." >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "Missing Java in Docker image." >&2; exit 1; }

GRADLE_ARGS=(--no-daemon --project-cache-dir /tmp/skcraft-project-cache clean build)
if [ "${SKIP_PACKAGE:-false}" != "true" ]; then
  GRADLE_ARGS+=(:launcher-bootstrap:packageLinux)
fi
if [ "${BUILD_DEB:-false}" = "true" ]; then
  GRADLE_ARGS+=(-PbuildDeb=true)
fi

echo "Running in Docker: gradle ${GRADLE_ARGS[*]}"
gradle "${GRADLE_ARGS[@]}"
echo "Linux artifacts (if packaged): launcher-bootstrap/build/installer/linux"
