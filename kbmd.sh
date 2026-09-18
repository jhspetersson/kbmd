#!/bin/sh
# Runs kbmd in Docker on a local markdown folder:
#   ./kbmd.sh ~/Notes
#   ./kbmd.sh ~/Notes 9000          (other port)
#   REBUILD=1 ./kbmd.sh ~/Notes     (rebuild the image first)
set -e

if [ -z "$1" ]; then
  echo "usage: $0 <markdown-folder> [port]" >&2
  exit 1
fi
mkdir -p "$1"
VAULT=$(cd "$1" && pwd)
PORT=${2:-8787}

if [ -n "$REBUILD" ] || [ -z "$(docker images -q kbmd)" ]; then
  docker build -t kbmd "$(dirname "$0")"
fi

echo "kbmd: $VAULT  ->  http://localhost:$PORT"
# run as the calling user so notes are not created as root
exec docker run --rm -it --user "$(id -u):$(id -g)" -p "127.0.0.1:$PORT:8787" -v "$VAULT:/vault" kbmd
