#!/usr/bin/env bash
# Creates the app's release signing key, once, outside the repository:
# <dir>/release.jks (PKCS12, RSA 4096) and <dir>/release.properties (for
# Gradle's -PreleaseSigning, paths as seen in the build container's
# /signing). Back the directory up: every update of the app must be signed
# with the same key.
# usage: scripts/release-key.sh <app build image> <dir>
set -euo pipefail
IMAGE=$1
DIR=$2
if [ -f "$DIR/release.properties" ]; then
	echo "$DIR/release.properties exists: keeping it"
	exit 1
fi
mkdir -p "$DIR"
chmod 700 "$DIR"
PW=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
docker run --rm -v "$DIR":/signing -u "$(id -u):$(id -g)" "$IMAGE" \
	keytool -genkeypair -keystore /signing/release.jks -storetype PKCS12 \
	-storepass "$PW" -keypass "$PW" -alias discofpv -keyalg RSA -keysize 4096 \
	-validity 10000 -dname "CN=Disco FPV, O=disco-flying-wing-fpv"
umask 077
printf 'storeFile=/signing/release.jks\nstorePassword=%s\nkeyAlias=discofpv\nkeyPassword=%s\n' "$PW" "$PW" \
	> "$DIR/release.properties"
chmod 600 "$DIR/release.jks" "$DIR/release.properties"
echo "release key in $DIR: back it up"
