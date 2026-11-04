#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
ARTIFACTS="$ROOT/target/fsr-layer/test-artifacts"
mkdir -p "$ARTIFACTS"

fail() { echo "FAIL: $*" >&2; exit 1; }
command -v Xvfb >/dev/null || fail "Xvfb is required"
command -v import >/dev/null || fail "ImageMagick import is required"
command -v convert >/dev/null || fail "ImageMagick convert is required"
command -v vkcube >/dev/null || fail "vkcube is required"
lavapipe_icd=
for candidate in /usr/share/vulkan/icd.d/lvp_icd.x86_64.json /usr/share/vulkan/icd.d/lvp_icd.json; do
    if [[ -f "$candidate" ]]; then lavapipe_icd=$candidate; break; fi
done
[[ -n "$lavapipe_icd" ]] || fail "lavapipe ICD manifest is missing"
[[ -f /usr/share/vulkan/explicit_layer.d/VkLayer_khronos_validation.json ]] || fail "vulkan-validationlayers is required"

scripts/build_fsr_layer.sh
layer="$ROOT/target/fsr-layer/linux"
display=
for n in $(seq 90 199); do
    if [[ ! -e "/tmp/.X${n}-lock" && ! -S "/tmp/.X11-unix/X${n}" ]]; then display=$n; break; fi
done
[[ -n "$display" ]] || fail "no free X display found"
Xvfb ":$display" -screen 0 1024x768x24 -nolisten tcp >"$ARTIFACTS/xvfb.log" 2>&1 &
xvfb_pid=$!
cleanup() { kill "$xvfb_pid" 2>/dev/null || true; wait "$xvfb_pid" 2>/dev/null || true; }
trap cleanup EXIT
export DISPLAY=":$display"
ready="$ARTIFACTS/.xvfb-ready.png"
for _ in $(seq 1 50); do
    if import -window root "$ready" >/dev/null 2>&1; then break; fi
    sleep 0.1
done
[[ -f "$ready" ]] || fail "Xvfb did not become ready"
rm -f "$ready"

mkdir -p "$ARTIFACTS/config-scale" "$ARTIFACTS/config-auto"
printf '[fsr]\nsharpness=0.2\nscale=2.0\nfake_mode_switch=false\nlog=true\n' >"$ARTIFACTS/config-scale/bifrost_fsr.ini"
printf '[fsr]\nsharpness=0.2\nscale=auto\nfake_mode_switch=false\nlog=true\n' >"$ARTIFACTS/config-auto/bifrost_fsr.ini"
export VK_ICD_FILENAMES="$lavapipe_icd"
export VK_LAYER_PATH="$layer:/usr/share/vulkan/explicit_layer.d"
export VK_INSTANCE_LAYERS=VK_LAYER_BIFROST_fsr1:VK_LAYER_KHRONOS_validation

set +e
BIFROST_FSR_CONFIG_DIR="$ARTIFACTS/config-scale" vkcube --c 2000 --width 512 --height 384 \
    >"$ARTIFACTS/scale.stdout.log" 2>"$ARTIFACTS/scale.stderr.log" &
cube_pid=$!
colors=1
for _ in $(seq 1 30); do
    if ! kill -0 "$cube_pid" 2>/dev/null; then break; fi
    import -window root "$ARTIFACTS/scale.png" >"$ARTIFACTS/scale-screenshot.log" 2>&1 || true
    if [[ -f "$ARTIFACTS/scale.png" ]]; then
        colors=$(convert "$ARTIFACTS/scale.png" -format '%k' info:)
        if [[ "$colors" =~ ^[0-9]+$ && "$colors" -gt 1 ]]; then break; fi
    fi
    sleep 0.05
done
kill "$cube_pid" 2>/dev/null || true
wait "$cube_pid"
status=$?
set -e
[[ $status -eq 0 || $status -eq 143 ]] || fail "scaled vkcube exited with status $status (see $ARTIFACTS/scale.stderr.log)"
grep -q '256x192 -> 512x384' "$ARTIFACTS/config-scale/bifrost_fsr.log" || fail "scaled swapchain was not logged"
if grep -Eq 'VUID-|Validation Error' "$ARTIFACTS/scale.stderr.log"; then fail "validation error in scaled run"; fi
[[ "$colors" =~ ^[0-9]+$ && "$colors" -gt 1 ]] || fail "scaled screenshot is uniformly black"

set +e
BIFROST_FSR_CONFIG_DIR="$ARTIFACTS/config-auto" vkcube --c 120 --width 512 --height 384 \
    >"$ARTIFACTS/auto.stdout.log" 2>"$ARTIFACTS/auto.stderr.log"
status=$?
set -e
[[ $status -eq 0 ]] || fail "auto-scale vkcube exited with status $status (see $ARTIFACTS/auto.stderr.log)"
grep -q 'pass-through swapchain' "$ARTIFACTS/config-auto/bifrost_fsr.log" || fail "auto scale did not pass through"
if grep -Eq 'VUID-|Validation Error' "$ARTIFACTS/auto.stderr.log"; then fail "validation error in auto-scale run"; fi
echo "FSR layer checks passed"
