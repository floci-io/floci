#!/usr/bin/env bash
# check-isa-level.sh <binary> [highest allowed level]
#
# The amd64 binary must run on x86-64-v2 CPUs (no AVX2). glibc refuses to load a binary whose
# "x86 ISA needed" property names a level the CPU lacks ("CPU ISA level is lower than required").
# The linker merges that property over every object it links, so one object from the build host's
# toolchain raises it whatever -march says. This reads the property and fails when it names a level
# above the allowed one (default 2, x86-64-v2). A binary without the property passes: glibc then
# checks nothing. Starting the binary cannot catch this, because the runner's CPU has v3.
set -euo pipefail

BIN=$1
MAX=${2:-2}

case $MAX in
    [1-4]) ;;
    *) echo "FAIL: the highest allowed level must be 1 to 4, got '$MAX'"; exit 1 ;;
esac

notes=$(readelf --notes --wide "$BIN")
line=$(grep 'x86 ISA needed:' <<<"$notes" || true)
if [ -z "$line" ]; then
    echo "ok: $BIN has no x86 ISA needed property, so glibc checks no ISA level"
    exit 0
fi

needed=$(grep -o 'x86 ISA needed: [^,]*\(, x86-64-[a-z0-9]*\)*' <<<"$line")
highest=0
for level in $(grep -o 'x86-64-[a-z0-9]*' <<<"$needed" || true); do
    case $level in
        x86-64-baseline) n=1 ;;
        x86-64-v[2-9]) n=${level#x86-64-v} ;;
        *) echo "FAIL: cannot read the level '$level' in: $line"; exit 1 ;;
    esac
    if [ "$n" -gt "$highest" ]; then
        highest=$n
    fi
done
if [ "$highest" -gt "$MAX" ]; then
    echo "FAIL: $BIN needs x86-64-v$highest, above the x86-64-v$MAX limit. readelf: $line"
    exit 1
fi
echo "ok: $needed (limit x86-64-v$MAX)"
