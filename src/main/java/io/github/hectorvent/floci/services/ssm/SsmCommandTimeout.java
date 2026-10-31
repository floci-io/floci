package io.github.hectorvent.floci.services.ssm;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Separates the supervisor's deadline signal from an arbitrary user exit status. */
final class SsmCommandTimeout {
    private final String marker = "\036floci-ssm-timeout-" + UUID.randomUUID() + "\037";
    private final byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);
    private final BoundedOutputCollector stderr;
    private int matched;
    private boolean timedOut;

    SsmCommandTimeout(int limit) {
        stderr = new BoundedOutputCollector(limit);
    }

    String wrap(String script, int seconds) {
        String command = "sh -c " + quote(script);
        if (seconds < 1) {
            return command;
        }
        // Keep GNU timeout's monitored shell alive until -k kills the entire group, even if its child exits first.
        String completedCommand = "trap 'while :; do sleep 1; done' TERM; " + command
                + " & child=$!; wait \"$child\" 2>/dev/null; result=$?; printf '%s\\n' \"$result\" > \"$1/completed\"; exit \"$result\"";
        return """
                if ! command -v mktemp >/dev/null 2>&1; then
                  exec %s
                fi
                state=$(mktemp -d 2>/dev/null) || exec %s
                if ! command -v setsid >/dev/null 2>&1; then
                  case "$(timeout --version 2>/dev/null)" in
                    *"GNU coreutils"*)
                      timeout -k 1s %s sh -c %s sh "$state"
                      result=$?
                      if [ -f "$state/completed" ] && read -r completed_result < "$state/completed"; then
                        result=$completed_result
                      else
                        case "$result" in 124|137|143) printf '%%s' %s >&2 ;; esac
                      fi
                      rm -rf "$state"
                      exit "$result"
                      ;;
                    *) rmdir "$state"; exec %s ;;
                  esac
                fi
                setsid %s &
                child=$!
                (
                  timer=
                  trap 'if [ -n "$timer" ]; then kill "$timer" 2>/dev/null; wait "$timer" 2>/dev/null; fi; exit 0' TERM
                  sleep %s &
                  timer=$!
                  wait "$timer"
                  if mkdir "$state/finished" 2>/dev/null; then
                    : > "$state/timed-out"
                    kill -TERM "-$child" 2>/dev/null
                    sleep 1
                    kill -KILL "-$child" 2>/dev/null
                  fi
                ) &
                watchdog=$!
                wait "$child" 2>/dev/null
                result=$?
                if mkdir "$state/finished" 2>/dev/null; then
                  kill "$watchdog" 2>/dev/null
                fi
                wait "$watchdog" 2>/dev/null
                if [ -f "$state/timed-out" ]; then
                  printf '%%s' %s >&2
                fi
                rm -rf "$state"
                exit "$result"
                """.formatted(command, command, seconds, quote(completedCommand), quote(marker), command,
                command, seconds, quote(marker));
    }

    synchronized void writeError(byte[] payload) {
        int start = 0;
        for (int i = 0; i < payload.length; i++) {
            byte value = payload[i];
            if (value == markerBytes[matched]) {
                if (matched == 0 && i > start) {
                    stderr.write(Arrays.copyOfRange(payload, start, i));
                }
                matched++;
                start = i + 1;
                if (matched == markerBytes.length) {
                    timedOut = true;
                    matched = 0;
                }
            } else if (matched > 0) {
                stderr.write(Arrays.copyOf(markerBytes, matched));
                matched = 0;
                start = i;
                if (value == markerBytes[0]) {
                    matched = 1;
                    start = i + 1;
                }
            }
        }
        if (payload.length > start) {
            stderr.write(Arrays.copyOfRange(payload, start, payload.length));
        }
    }

    synchronized String standardError() {
        if (matched > 0) {
            stderr.write(Arrays.copyOf(markerBytes, matched));
            matched = 0;
        }
        return stderr.content();
    }

    synchronized boolean timedOut() {
        return timedOut;
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
