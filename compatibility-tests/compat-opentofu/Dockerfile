FROM ghcr.io/opentofu/opentofu:1.8 AS opentofu

# Same CLI as compat-terraform: 2.15 predates the S3 checksum type (x-amz-checksum-type, December 2024)
# and cannot show ChecksumType in head-object / get-object-attributes output.
FROM amazon/aws-cli:2.34.32

# Copy tofu binary from official OpenTofu image
COPY --from=opentofu /usr/local/bin/tofu /usr/local/bin/tofu

RUN yum install -y git jq

WORKDIR /app

# Install bats and helpers
RUN git clone --depth 1 https://github.com/bats-core/bats-core.git /opt/bats-core \
    && git clone --depth 1 https://github.com/bats-core/bats-support.git /opt/bats-support \
    && git clone --depth 1 https://github.com/bats-core/bats-assert.git /opt/bats-assert

COPY . .

ENV FLOCI_ENDPOINT=http://floci:4566
ENV AWS_ENDPOINT_URL=http://floci:4566
ENV AWS_ACCESS_KEY_ID=test
ENV AWS_SECRET_ACCESS_KEY=test
ENV AWS_DEFAULT_REGION=us-east-1
ENV BATS_LIB_PATH=/opt

RUN chmod +x /app/run-bats-in-container.sh && mkdir -p /results
ENTRYPOINT ["./run-bats-in-container.sh"]
