# Built for amd64 and arm64 by .github/workflows/push-zstd-image.yml. The base layers come from a
# registry in gzip and the COPY layer is built here. No RUN, so arm64 needs no emulation.
FROM registry.access.redhat.com/ubi9-micro:9.8
COPY docker/healthcheck.sh /usr/local/bin/
