FROM rust:1-alpine
RUN apk add --no-cache musl-dev pkgconf openssl-dev ca-certificates
WORKDIR /app

# Install cargo-nextest
RUN cargo install cargo-nextest --locked

COPY Cargo.toml Cargo.lock ./
# pre-fetch dependencies
RUN mkdir src tests && echo "" > src/lib.rs && cargo fetch && rm -rf src tests

COPY . .

ENV FLOCI_ENDPOINT=http://floci:4566
ENV AWS_ACCESS_KEY_ID=test
ENV AWS_SECRET_ACCESS_KEY=test
ENV AWS_DEFAULT_REGION=us-east-1

RUN mkdir -p /results
ENTRYPOINT ["cargo", "nextest", "run", "--profile", "ci"]
