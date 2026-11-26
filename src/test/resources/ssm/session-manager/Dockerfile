FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    systemd systemd-sysv ca-certificates curl iproute2 procps openssh-server openssh-client socat postgresql-client \
    && rm -rf /var/lib/apt/lists/*
RUN agent_arch="$(dpkg --print-architecture)" \
    && case "$agent_arch" in arm64) plugin_arch=arm64;; amd64) plugin_arch=64bit;; *) exit 1;; esac \
    && curl -fsSL "https://s3.us-east-1.amazonaws.com/amazon-ssm-us-east-1/3.3.5828.0/debian_${agent_arch}/amazon-ssm-agent.deb" -o /tmp/agent.deb \
    && curl -fsSL "https://s3.amazonaws.com/session-manager-downloads/plugin/1.2.896.0/ubuntu_${plugin_arch}/session-manager-plugin.deb" -o /tmp/plugin.deb \
    && apt-get install -y /tmp/agent.deb /tmp/plugin.deb \
    && rm /tmp/agent.deb /tmp/plugin.deb \
    && systemctl disable amazon-ssm-agent.service
RUN useradd -m -s /bin/sh session-test && passwd -d session-test \
    && mkdir -p /run/sshd /home/session-test/.ssh \
    && printf 'PasswordAuthentication no\nPermitRootLogin no\n' > /etc/ssh/sshd_config.d/session-test.conf
