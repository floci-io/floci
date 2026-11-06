FROM amazon/aws-cli:latest

RUN yum install -y git jq bc openssl parallel

WORKDIR /app

# Install bats and helpers
RUN git clone --depth 1 https://github.com/bats-core/bats-core.git /opt/bats-core \
    && git clone --depth 1 https://github.com/bats-core/bats-support.git /opt/bats-support \
    && git clone --depth 1 https://github.com/bats-core/bats-assert.git /opt/bats-assert

COPY test/ test/
COPY run-bats-in-container.sh /usr/local/bin/run-bats-in-container.sh

ENV FLOCI_ENDPOINT=http://floci:4566
ENV BATS_LIB_PATH=/opt

RUN chmod +x /usr/local/bin/run-bats-in-container.sh && mkdir -p /results
ENTRYPOINT ["run-bats-in-container.sh"]
