#!/usr/bin/env bash
# Protocol tests of the fake Disco: runs each tests/fake/*.probe script
# with disco-probe (a scripted ARSDK controller, fake-sc2/src/probe.c)
# against a fresh fake-sc2, in its own container on spare ports, so a
# running `make fake` is left alone. No emulator needed.
# usage: scripts/fake-test.sh <sdk image> <out dir> [probe files...]
set -euo pipefail
IMAGE=$1; OUT=$2; shift 2
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAME=disco-fpv-fake-test
MUX_PORT=${FAKE_TEST_PORT:-4331}
CTRL_PORT=$((MUX_PORT + 1))
VIDEO_PORT=${FAKE_TEST_VIDEO_PORT:-55014}
BIN=/work/sdk/out/olympe-linux/staging/usr/bin
LIB=/work/sdk/out/olympe-linux/staging/usr/lib
PROBES=("$@")
[ ${#PROBES[@]} -gt 0 ] || PROBES=("$ROOT"/tests/fake/*.probe)

mkdir -p "$OUT"
cleanup() {
	docker logs "$NAME" > "$OUT/fake-test-sc2.log" 2>&1 || true
	docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

failed=0
for probe in "${PROBES[@]}"; do
	name=$(basename "$probe" .probe)
	# a fresh fake for each script: no state carried over
	docker rm -f "$NAME" >/dev/null 2>&1 || true
	docker run -d --name "$NAME" --network host -v "$ROOT":/work \
		-e LD_LIBRARY_PATH=$LIB -e ULOG_LEVEL=I -e ULOG_STDERR=y "$IMAGE" \
		$BIN/fake-sc2 --mux --mux-port "$MUX_PORT" --ctrl-port "$CTRL_PORT" \
		--video-port "$VIDEO_PORT" --no-publisher-avahi --no-publisher-net >/dev/null
	sleep 1
	start=$(date +%s)
	if docker exec -i -e LD_LIBRARY_PATH=$LIB -e ULOG_LEVEL=W "$NAME" \
			$BIN/disco-probe --port "$MUX_PORT" --ctrl-port "$CTRL_PORT" \
			< "$probe" > "$OUT/probe-$name.log" 2>&1; then
		echo "PASS $name ($(( $(date +%s) - start ))s)"
	else
		echo "FAIL $name: $(grep -m1 '^FAIL' "$OUT/probe-$name.log" || tail -1 "$OUT/probe-$name.log")"
		echo "     log: $OUT/probe-$name.log"
		failed=1
	fi
done
exit $failed
