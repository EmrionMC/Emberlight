#!/bin/sh
# SPDX-License-Identifier: MIT
# Builds the Emberlight DLSS shim and installs it with NVIDIA's DLSS, DLSS-FG and DLSS-RR libraries (the manual alternative to the
# shim bundled in the mod jar plus the in-game model download).
# usage: build-dlss.sh <minecraft game dir>      (needs git, a C/C++ compiler and the Vulkan headers)
#        build-dlss.sh --shim <output file>      only builds the shim; this is how the bundled copy is made
# Any change to the .c/.cpp files here must rebuild BOTH bundled shims (mod/src/main/resources/natives/): this script
# (--shim) for linux-x86_64/libemrion_ngx.so and build-dlss.ps1 on Windows for windows-x86_64/emrion_ngx.dll.
# DLSS_SDK=<checkout of NVIDIA/DLSS at the tag> skips the download.
# Downloads the DLSS SDK only from NVIDIA's GitHub (github.com/NVIDIA/DLSS). By running this you accept NVIDIA's
# RTX SDK licence (LICENSE.txt in that repository).
set -eu
TAG=${DLSS_TAG:-v310.9.16}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
if [ "${1:-}" = --shim ]; then SHIM=${2:?usage: build-dlss.sh --shim <output file>}; GAME=; else
	GAME=${1:?usage: build-dlss.sh <game dir>}; SHIM=$GAME/emberlight/dlss/libemrion_ngx.so; fi
SDK=${DLSS_SDK:-$WORK/dlss}
if [ -z "${DLSS_SDK:-}" ]; then
	git clone -q --depth 1 --branch "$TAG" --filter=blob:none --sparse https://github.com/NVIDIA/DLSS.git "$SDK"
	git -C "$SDK" sparse-checkout set --no-cone '/include/*' '/LICENSE.txt' '/lib/Linux_x86_64/libnvsdk_ngx.a' \
		${GAME:+'/lib/Linux_x86_64/rel/libnvidia-ngx-dlss.so.*' '/lib/Linux_x86_64/rel/libnvidia-ngx-dlssg.so.*' '/lib/Linux_x86_64/rel/libnvidia-ngx-dlssd.so.*'}
fi
mkdir -p "$(dirname "$SHIM")"
${CC:-cc} -O2 -c -fPIC -fvisibility=hidden -I"$SDK/include" -o "$WORK/emrion_ngx.o" "$HERE/emrion_ngx.c"
${CXX:-c++} -O2 -c -fPIC -fvisibility=hidden -I"$SDK/include" -o "$WORK/emrion_ngx_dlssg.o" "$HERE/emrion_ngx_dlssg.cpp"
${CXX:-c++} -shared -fvisibility=hidden -o "$SHIM" "$WORK/emrion_ngx.o" "$WORK/emrion_ngx_dlssg.o" \
	"$SDK/lib/Linux_x86_64/libnvsdk_ngx.a" -ldl
[ -n "$GAME" ] || { echo "Built $SHIM"; exit 0; }
OUT="$GAME/emberlight/dlss"
cp "$SDK/lib/Linux_x86_64/rel/"libnvidia-ngx-dlss.so.* "$SDK/lib/Linux_x86_64/rel/"libnvidia-ngx-dlssg.so.* "$SDK/lib/Linux_x86_64/rel/"libnvidia-ngx-dlssd.so.* "$OUT/"
cp "$SDK/LICENSE.txt" "$OUT/NVIDIA-RTX-SDK-LICENSE.txt"
echo "Installed DLSS into $OUT"
