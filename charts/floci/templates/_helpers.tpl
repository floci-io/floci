{{/*
Chart name, truncated to the 63 characters a DNS label allows.
*/}}
{{- define "floci.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Full resource name: "<release>-<chart>", or the release name alone when it already
contains the chart name.
*/}}
{{- define "floci.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "floci.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "floci.selectorLabels" -}}
app.kubernetes.io/name: {{ include "floci.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Common labels. app.kubernetes.io/managed-by is Helm here; Floci's own Lambda pods carry
managed-by=floci, and the orphan sweep selects on that value, so the two never overlap.
*/}}
{{- define "floci.labels" -}}
helm.sh/chart: {{ include "floci.chart" . }}
{{ include "floci.selectorLabels" . }}
app.kubernetes.io/version: {{ include "floci.imageTag" . | trunc 63 | trimSuffix "-" | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "floci.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "floci.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Image tag: image.tag or the appVersion, plus "-<variant>" when image.variant is set, which
follows the published scheme (2.4.0-compat, latest-compat, nightly-compat).
*/}}
{{- define "floci.imageTag" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag | toString }}
{{- with .Values.image.variant }}
{{- $tag = printf "%s-%s" $tag . }}
{{- end }}
{{- $tag }}
{{- end }}

{{- define "floci.image" -}}
{{- if .Values.image.digest }}
{{- printf "%s@%s" .Values.image.repository .Values.image.digest }}
{{- else }}
{{- printf "%s:%s" .Values.image.repository (include "floci.imageTag" .) }}
{{- end }}
{{- end }}

{{- define "floci.helperImage" -}}
{{- printf "%s:%s" .Values.helperImage.repository .Values.helperImage.tag }}
{{- end }}

{{- define "floci.cleanupHook" -}}
{{- if and .Values.cleanup.enabled .Values.rbac.create (include "floci.kubernetesExecutor" .) }}true{{ end }}
{{- end }}

{{- define "floci.lambdaExecutor" -}}
{{- $executor := .Values.floci.lambda.executor | toString }}
{{- if not (has $executor (list "docker" "kubernetes")) }}
{{- fail (printf "floci.lambda.executor must be \"docker\" or \"kubernetes\", got %q" $executor) }}
{{- end }}
{{- $executor }}
{{- end }}

{{- define "floci.kubernetesExecutor" -}}
{{- if eq (include "floci.lambdaExecutor" .) "kubernetes" }}true{{ end }}
{{- end }}

{{- define "floci.lambdaNamespace" -}}
{{- default .Release.Namespace .Values.floci.lambda.kubernetes.namespace }}
{{- end }}

{{- define "floci.storageMode" -}}
{{- $mode := .Values.floci.storage.mode | toString }}
{{- if not $mode }}
{{- $mode = ternary "persistent" "memory" (.Values.persistence.enabled | default false) }}
{{- end }}
{{- if not (has $mode (list "memory" "persistent" "hybrid" "wal")) }}
{{- fail (printf "floci.storage.mode must be one of memory, persistent, hybrid, wal; got %q" $mode) }}
{{- end }}
{{- $mode }}
{{- end }}

{{- define "floci.serviceHost" -}}
{{- printf "%s.%s.svc.%s" (include "floci.fullname" .) .Release.Namespace .Values.floci.clusterDomain }}
{{- end }}

{{/*
Plain-HTTP URL of the Service. Floci answers HTTP on its port even with TLS enabled.
*/}}
{{- define "floci.serviceUrl" -}}
{{- printf "http://%s:%v" (include "floci.serviceHost" .) .Values.service.port }}
{{- end }}

{{- define "floci.baseUrl" -}}
{{- default (include "floci.serviceUrl" .) .Values.floci.baseUrl }}
{{- end }}

{{/*
The base URL Floci actually puts in responses, mirroring EmulatorConfig.effectiveBaseUrl():
floci.hostname replaces the host, and TLS turns http:// into https://.
*/}}
{{- define "floci.effectiveBaseUrl" -}}
{{- $url := include "floci.baseUrl" . }}
{{- with .Values.floci.hostname }}
{{- $url = regexReplaceAll "://[^:/]+(:[0-9]+)?" $url (printf "://%s${1}" .) }}
{{- end }}
{{- if and .Values.floci.tls.enabled (hasPrefix "http://" $url) }}
{{- $url = printf "https://%s" (trimPrefix "http://" $url) }}
{{- end }}
{{- $url }}
{{- end }}

{{/*
Phase name to hook directory. "stop" is Floci's stop.d (shutdown.d is an alias).
*/}}
{{- define "floci.initHookDirs" -}}
{{- dict "boot" "boot.d" "start" "start.d" "ready" "ready.d" "stop" "stop.d" | toJson }}
{{- end }}

{{/*
ConfigMap entries for the init hooks, as a JSON list of {key, path, content}. Validates the
phase names and file extensions so a typo fails the render instead of being ignored by
Floci at runtime.
*/}}
{{- define "floci.initHookEntries" -}}
{{- $dirs := include "floci.initHookDirs" . | fromJson }}
{{- $entries := list }}
{{- range $phase, $scripts := .Values.initHooks }}
{{- if not (hasKey $dirs $phase) }}
{{- fail (printf "initHooks.%s is not a hook phase; use boot, start, ready or stop" $phase) }}
{{- end }}
{{- range $file, $content := $scripts }}
{{- if not (regexMatch "^[A-Za-z0-9][A-Za-z0-9._-]*\\.(sh|py)$" $file) }}
{{- fail (printf "initHooks.%s.%s: hook file names must end in .sh or .py and use only letters, digits, '.', '_' and '-'" $phase $file) }}
{{- end }}
{{- $entries = append $entries (dict "key" (printf "%s-%s" $phase $file) "path" (printf "%s/%s" (get $dirs $phase) $file) "content" $content) }}
{{- end }}
{{- end }}
{{- $entries | toJson }}
{{- end }}

{{- define "floci.hasInitHooks" -}}
{{- if gt (len (include "floci.initHookEntries" . | fromJsonArray)) 0 }}true{{ end }}
{{- end }}

{{/*
Container environment. The chart's derived variables go in first and floci.env entries
override them, so a variable is never listed twice. Sorted by name for a stable render.
*/}}
{{- define "floci.env" -}}
{{- $env := dict }}
{{- $_ := set $env "FLOCI_BASE_URL" (include "floci.baseUrl" .) }}
{{- with .Values.floci.hostname }}
{{- $_ := set $env "FLOCI_HOSTNAME" . }}
{{- end }}
{{- with .Values.floci.defaultRegion }}
{{- $_ := set $env "FLOCI_DEFAULT_REGION" . }}
{{- end }}
{{- with .Values.floci.defaultAccountId }}
{{- $_ := set $env "FLOCI_DEFAULT_ACCOUNT_ID" (toString .) }}
{{- end }}
{{- $_ := set $env "FLOCI_STORAGE_MODE" (include "floci.storageMode" .) }}
{{- $_ := set $env "FLOCI_STORAGE_PERSISTENT_PATH" "/app/data" }}
{{- if .Values.floci.tls.enabled }}
{{- $_ := set $env "FLOCI_TLS_ENABLED" "true" }}
{{- end }}
{{- $_ := set $env "FLOCI_SERVICES_LAMBDA_EXECUTOR" (include "floci.lambdaExecutor" .) }}
{{- if include "floci.kubernetesExecutor" . }}
{{- $k8s := .Values.floci.lambda.kubernetes }}
{{- $_ := set $env "FLOCI_SERVICES_LAMBDA_KUBERNETES_NAMESPACE" (include "floci.lambdaNamespace" .) }}
{{- with $k8s.initImage }}
{{- $_ := set $env "FLOCI_SERVICES_LAMBDA_KUBERNETES_INIT_IMAGE" . }}
{{- end }}
{{- with $k8s.labels }}
{{- $_ := set $env "FLOCI_SERVICES_LAMBDA_KUBERNETES_LABELS" (join "," .) }}
{{- end }}
{{- with $k8s.flociAddress }}
{{- $_ := set $env "FLOCI_SERVICES_LAMBDA_KUBERNETES_FLOCI_ADDRESS" . }}
{{- end }}
{{- end }}
{{- range $name, $value := .Values.floci.env }}
{{- /* YAML numbers arrive as float64, which toString prints as 1e+06; keep integers whole. */}}
{{- if and (kindIs "float64" $value) (eq $value (floor $value)) }}
{{- $value = int64 $value }}
{{- end }}
{{- $_ := set $env $name (toString $value) }}
{{- end }}
{{- range $name := keys $env | sortAlpha }}
- name: {{ $name }}
  value: {{ get $env $name | quote }}
{{- end }}
{{- end }}
