#!/bin/bash
# Container health probe. Bash only, because ubi9-micro has no curl.
exec 3<>/dev/tcp/127.0.0.1/4566 || exit 1
printf 'GET /_floci/init HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
IFS= read -r -d '' response <&3
[[ $response == "HTTP/1."?" 200"* ]] || exit 1
[[ $response == *'"ready":true'* ]] && exit 0
# Ready hooks still running. Report healthy 5 s after start anyway, as the first 5 s check always did,
# so a long hook never turns the container unhealthy.
read -r uptime _ < /proc/uptime
stat=$(< /proc/1/stat)
read -ra fields <<< "${stat##*) }"
# Field 22 of /proc/1/stat is the start time in clock ticks, 100 per second like the uptime digits.
(( ${uptime/./} - fields[19] >= 500 ))
