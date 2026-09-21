ARG GO_IMAGE
ARG BASE_IMAGE
FROM ${GO_IMAGE} AS nodeadm
WORKDIR /src/nodeadm
COPY source/nodeadm/ ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -mod=readonly -trimpath -ldflags='-s -w' -o /out/nodeadm ./cmd/nodeadm \
 && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -mod=readonly -trimpath -ldflags='-s -w' -o /out/nodeadm-internal ./cmd/nodeadm-internal

FROM ${BASE_IMAGE}
ARG AL2023_RELEASE
RUN --mount=type=cache,target=/var/cache/dnf dnf -y --releasever=${AL2023_RELEASE} \
      --setopt=install_weak_deps=False --setopt=timeout=60 --setopt=retries=3 install \
      systemd containerd runc iptables iproute procps-ng util-linux jq awscli socat tar gzip \
 && dnf clean all
ENV container=docker
STOPSIGNAL SIGRTMIN+3
COPY --from=nodeadm /out/ /usr/bin/
COPY kubelet aws-iam-authenticator /usr/bin/
COPY ecr-credential-provider /etc/eks/image-credential-provider/ecr-credential-provider
COPY kubelet.service runtime.slice native-nodeadm.service /etc/systemd/system/
COPY pause-image.conf /etc/systemd/system/kubelet.service.d/pause-image.conf
COPY entrypoint-native.sh /usr/local/bin/entrypoint-native.sh
COPY pause.tar /etc/eks/pause.tar
COPY cni-plugins-linux-arm64-v1.6.1.tgz /tmp/cni.tgz
COPY inputs.json /etc/eks/floci-image-inputs.json
RUN chmod +x /usr/bin/kubelet /usr/bin/aws-iam-authenticator /etc/eks/image-credential-provider/ecr-credential-provider /usr/local/bin/entrypoint-native.sh \
 && mkdir -p /var/lib/kubelet /opt/cni/bin /etc/eks/nodeadm.d \
 && kubelet --version > /etc/eks/kubelet-version.txt \
 && rpm -qa | sort > /etc/eks/floci-image-packages.txt \
 && tar -xzf /tmp/cni.tgz -C /opt/cni/bin && rm /tmp/cni.tgz \
 && systemctl enable native-nodeadm.service
ENTRYPOINT ["/usr/local/bin/entrypoint-native.sh"]
CMD ["/sbin/init"]
