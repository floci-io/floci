#!/bin/bash
# Container health probe. Bash only, because ubi9-micro has no curl.
exec 3<>/dev/tcp/127.0.0.1/4566 || exit 1
printf 'GET /_floci/health HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
read -r _proto status _rest <&3
[ "$status" = 200 ]
