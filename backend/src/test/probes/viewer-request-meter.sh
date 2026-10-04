#!/usr/bin/env bash
set -euo pipefail
meter_classes=$(mktemp -d /tmp/pis-viewer-meter-XXXXXX)
trap 'rm -rf "$meter_classes"' EXIT
java com.sun.tools.javac.Main -d "$meter_classes" backend/src/main/java/com/pis/viewer/ViewerRequestMeter.java backend/src/test/probes/ViewerRequestMeterProbe.java
java -Xmx64m -cp "$meter_classes" ViewerRequestMeterProbe
