FROM node:22-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npx tsc

FROM node:22-alpine
RUN apk add --no-cache aws-cli bash python3 git jq docker-cli
WORKDIR /app

# Install bats and helpers
RUN git clone --depth 1 https://github.com/bats-core/bats-core.git /opt/bats-core \
    && git clone --depth 1 https://github.com/bats-core/bats-support.git /opt/bats-support \
    && git clone --depth 1 https://github.com/bats-core/bats-assert.git /opt/bats-assert

COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/bin ./bin
COPY --from=builder /app/lib ./lib
COPY package.json cdk.json tsconfig.json ./
COPY docker-fn/ docker-fn/
COPY run.sh ./
COPY run-bats-in-container.sh ./
COPY test/ test/

ENV PATH="/app/node_modules/.bin:$PATH"
ENV BATS_LIB_PATH=/opt
ENV FLOCI_ENDPOINT=http://floci:4566
ENV AWS_ENDPOINT_URL=http://floci:4566
ENV AWS_ACCESS_KEY_ID=test
ENV AWS_SECRET_ACCESS_KEY=test
ENV AWS_DEFAULT_REGION=us-east-1
ENV LOCALSTACK_HOSTNAME=floci
ENV EDGE_PORT=4566

RUN chmod +x /app/run-bats-in-container.sh && mkdir -p /results
ENTRYPOINT ["./run-bats-in-container.sh"]
