#!/usr/bin/env bash
# Runs a CI step; on failure publishes the last log lines as a GitHub error annotation
# (annotations are readable through the checks API even where raw logs are not).
# Usage: tools/ci-step.sh "<title>" <command> [args...]
title="$1"; shift
log=$(mktemp)
set +e
"$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
set -e
if [ "$status" -ne 0 ]; then
  tail_text=$(grep -v "^Picked up JAVA_TOOL_OPTIONS" "$log" | tail -n 60 | sed -e 's/%/%25/g' -e 's/\r//g' | sed ':a;N;$!ba;s/\n/%0A/g')
  echo "::error title=$title::$tail_text"
fi
exit "$status"
