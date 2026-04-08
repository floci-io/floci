FROM golang:1.24-alpine
RUN apk add --no-cache ca-certificates git
WORKDIR /app

# Install gotestsum for JUnit XML output
RUN go install gotest.tools/gotestsum@latest

COPY go.mod ./
COPY . .

# Resolve test dependencies and download modules
RUN go mod tidy && go mod download

ENV FLOCI_ENDPOINT=http://floci:4566
ENV AWS_ACCESS_KEY_ID=test
ENV AWS_SECRET_ACCESS_KEY=test
ENV AWS_DEFAULT_REGION=us-east-1

ENTRYPOINT ["gotestsum", "--format", "testname", "--junitfile", "/results/junit.xml", "./tests/..."]
