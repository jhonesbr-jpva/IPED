#!/bin/sh
# Case-root shim for the Eclipse RCP analysis UI on Linux (feature 004, task
# T054; case-launcher-packaging contract). Mirror of IPED-SearchApp.exe
# (Windows): launches the RCP UI bundled under the case on the SYSTEM Java 21
# (Linux distribution model — no bundled jre is used), passing the case folder
# as the application argument.
HERE="$(cd "$(dirname "$0")" && pwd)"
exec "$HERE/iped/ui/iped-ui" "$HERE" "$@"
