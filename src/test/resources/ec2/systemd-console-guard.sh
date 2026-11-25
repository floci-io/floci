#!/bin/sh
set -eu

# Never start a getty: this fixture must remain safe when the launcher regresses.
reject() {
    printf 'EC2 console guard rejected launch: %s\n' "$1" >&2
    exit 78
}

for path in /dev/console $(seq 0 63 | sed 's|^|/dev/tty|'); do
    [ -c "$path" ] || reject "$path is not a character device"
    number=$(stat -Lc '%t:%T' "$path")
    [ "$number" = 1:3 ] || reject "$path is not /dev/null ($number)"
    awk -v path="$path" '$5 == path && $6 ~ /(^|,)ro(,|$)/ { found=1 } END { exit !found }' \
        /proc/self/mountinfo || reject "$path is not mounted read-only"
done

[ "${container:-}" = docker ] || reject 'container=docker is missing'
for unit in getty@.service autovt@.service console-getty.service container-getty@.service serial-getty@.service getty-static.service; do
    case " $* " in
        *" systemd.mask=$unit "*) ;;
        *) reject "$unit is not masked" ;;
    esac
done

touch /run/floci-console-guard-passed
exec tail -f /dev/null
