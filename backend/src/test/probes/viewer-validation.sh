#!/usr/bin/env bash
set -euo pipefail
# Run from repository root. Only this invocation's temporary output is cleaned.
result_dir="${1:-$(mktemp -d /tmp/pis-t33-results-XXXXXX)}"
mkdir -p "$result_dir"
java -Xmx128m backend/src/test/probes/ViewerValidationProbe.java > "$result_dir/provider-run-1.txt" 2>&1
java -Xmx128m backend/src/test/probes/ViewerValidationProbe.java > "$result_dir/provider-run-2.txt" 2>&1
python3 - "$result_dir" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1])
a=[s for s in (p/'provider-run-1.txt').read_text().splitlines() if s.startswith('FIXTURE ')]
b=[s for s in (p/'provider-run-2.txt').read_text().splitlines() if s.startswith('FIXTURE ')]
assert len(a)==2 and a==b, 'independent JVM digest/bytes/pixels mismatch'
print('PASS independent provider JVM restart equality; full application restart NOT RUN')
print((p/'provider-run-1.txt').read_text())
print((p/'provider-run-2.txt').read_text())
PY
