{{/*
Shared spec of the `helm test` pods: AWS CLI v2 against the Service's in-cluster DNS name,
under Floci's own pod security context (podSecurityContext) and a restricted container one. Called with a dict holding the
root context ("root"), the test name ("name"), the shell script ("script") and optional
extra volumes and mounts.
*/}}
{{- define "floci.testPod" -}}
{{- $root := .root }}
apiVersion: v1
kind: Pod
metadata:
  name: {{ include "floci.fullname" $root }}-test-{{ .name }}
  namespace: {{ $root.Release.Namespace }}
  labels:
    {{- include "floci.labels" $root | nindent 4 }}
    app.kubernetes.io/component: test
  annotations:
    helm.sh/hook: test
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  enableServiceLinks: false
  {{- with $root.Values.podSecurityContext }}
  securityContext:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  containers:
    - name: aws-cli
      image: {{ include "floci.helperImage" $root | quote }}
      imagePullPolicy: {{ $root.Values.helperImage.pullPolicy }}
      command: ["/bin/sh", "-ec"]
      args:
        - |
          {{- .script | trim | nindent 10 }}
      env:
        - name: FLOCI_ENDPOINT
          value: {{ include "floci.serviceUrl" $root | quote }}
        - name: FLOCI_EFFECTIVE_BASE_URL
          value: {{ include "floci.effectiveBaseUrl" $root | quote }}
        - name: AWS_ACCESS_KEY_ID
          value: test
        - name: AWS_SECRET_ACCESS_KEY
          value: test
        - name: AWS_DEFAULT_REGION
          value: {{ $root.Values.floci.defaultRegion | default "us-east-1" | quote }}
        - name: AWS_PAGER
          value: ""
        - name: HOME
          value: /tmp
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop:
            - ALL
      resources:
        requests:
          cpu: 50m
          memory: 128Mi
        limits:
          memory: 256Mi
      volumeMounts:
        - name: tmp
          mountPath: /tmp
        {{- with .volumeMounts }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
  volumes:
    - name: tmp
      emptyDir: {}
    {{- with .volumes }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
{{- end }}
