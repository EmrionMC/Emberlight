#!/bin/bash
# Builds the test areas into a running server's world through its console FIFO (the test server's start.sh makes one).
# usage: build.sh SERVER_DIR   (fresh world with level-seed=2026; pause-when-empty-seconds=0 while it builds)
set -eu
D=$(dirname "$(readlink -f "$0")")
S=${1:?usage: build.sh SERVER_DIR}
[ -p "$S/console" ] || { echo "no console FIFO in $S (start the server first)"; exit 1; }
python3 "$D/build_commands.py" | while IFS= read -r line; do
	case "$line" in
		"#wait "*) sleep "${line#\#wait }" ;;
		*) printf '%s\n' "$line" > "$S/console" ;;
	esac
done
level=$(sed -n 's/^level-name=//p' "$S/server.properties")
python3 "$D/build_commands.py" --datapack "$S/$level"
sleep 5
printf 'reload\n' > "$S/console"
echo "built; check $S/server.out for errors"
