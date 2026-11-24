{{/* Release-scoped name: "pagerewind" when the release is called pagerewind, otherwise "<release>-pagerewind". */}}
{{- define "pagerewind.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 50 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 50 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/* Name of one component, e.g. pagerewind-incident. Call with (list $ "incident"). */}}
{{- define "pagerewind.component" -}}
{{- printf "%s-%s" (include "pagerewind.fullname" (index . 0)) (index . 1) -}}
{{- end -}}

{{/* Labels on every object; objects with a selector add pagerewind.selector, the others pagerewind.instance. */}}
{{- define "pagerewind.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
app.kubernetes.io/part-of: pagerewind
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "pagerewind.instance" -}}
{{ include "pagerewind.labels" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* Selector labels of one component. Call with (list $ "incident"). */}}
{{- define "pagerewind.selector" -}}
app.kubernetes.io/name: {{ index . 1 }}
app.kubernetes.io/instance: {{ (index . 0).Release.Name }}
{{- end -}}

{{/* Image of one PageRewind service: registry/pagerewind-<service>:<tag>[@digest]. */}}
{{- define "pagerewind.image" -}}
{{- $root := index . 0 -}}
{{- $service := index . 1 -}}
{{- $tag := $root.Values.image.tag | default $root.Chart.AppVersion -}}
{{- $digest := index ($root.Values.image.digests | default dict) $service | default "" -}}
{{- printf "%s/pagerewind-%s:%s" $root.Values.image.registry $service $tag -}}
{{- if $digest }}@{{ $digest }}{{ end -}}
{{- end -}}

{{- define "pagerewind.secretName" -}}
{{- .Values.secrets.existingSecret | default (printf "%s-secrets" (include "pagerewind.fullname" .)) -}}
{{- end -}}

{{/* A secretKeyRef env var. Call with (list $ "ENV_NAME" "secret-key") or add "optional" as a 4th item. */}}
{{- define "pagerewind.secretEnv" -}}
- name: {{ index . 1 }}
  valueFrom:
    secretKeyRef:
      name: {{ include "pagerewind.secretName" (index . 0) }}
      key: {{ index . 2 }}
      {{- if gt (len .) 3 }}
      optional: true
      {{- end }}
{{- end -}}

{{/* Addresses of the data stores: the bundled one, or the host given in values. */}}
{{- define "pagerewind.postgresHost" -}}
{{- if .Values.postgresql.bundled }}{{ include "pagerewind.component" (list . "postgresql") }}{{ else }}{{ required "postgresql.host is required when postgresql.bundled is false" .Values.postgresql.host }}{{ end -}}
{{- end -}}
{{- define "pagerewind.rabbitmqHost" -}}
{{- if .Values.rabbitmq.bundled }}{{ include "pagerewind.component" (list . "rabbitmq") }}{{ else }}{{ required "rabbitmq.host is required when rabbitmq.bundled is false" .Values.rabbitmq.host }}{{ end -}}
{{- end -}}
{{- define "pagerewind.redisHost" -}}
{{- if .Values.redis.bundled }}{{ include "pagerewind.component" (list . "redis") }}{{ else }}{{ required "redis.host is required when redis.bundled is false" .Values.redis.host }}{{ end -}}
{{- end -}}
{{- define "pagerewind.mailHost" -}}
{{- if .Values.mail.bundled }}{{ include "pagerewind.component" (list . "mailpit") }}{{ else }}{{ required "mail.host is required when mail.bundled is false" .Values.mail.host }}{{ end -}}
{{- end -}}

{{/* The realm inside the cluster. */}}
{{- define "pagerewind.internalRealmUrl" -}}
{{- if .Values.auth.internalRealmUrl }}{{ .Values.auth.internalRealmUrl }}
{{- else if .Values.keycloak.bundled }}http://{{ include "pagerewind.component" (list . "keycloak") }}:8080/realms/pagerewind
{{- else }}{{ fail "auth.internalRealmUrl is required when keycloak.bundled is false" }}
{{- end -}}
{{- end -}}

{{/* Standard probes of a Spring Boot service. */}}
{{- define "pagerewind.springProbes" -}}
startupProbe:
  httpGet: {path: /actuator/health/liveness, port: http}
  periodSeconds: 5
  failureThreshold: 60
livenessProbe:
  httpGet: {path: /actuator/health/liveness, port: http}
  periodSeconds: 15
  failureThreshold: 4
readinessProbe:
  httpGet: {path: /actuator/health/readiness, port: http}
  periodSeconds: 10
{{- end -}}
