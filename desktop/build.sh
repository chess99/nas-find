#!/bin/sh
set -eu
cd "$(dirname "$0")"
case "${1:-build}" in
  test) node sync.mjs; cargo test --manifest-path src-tauri/Cargo.toml ;;
  build) npm run build ;;
  dev) npm run dev ;;
  *) echo 'Usage: ./build.sh [test|build|dev]' >&2; exit 2 ;;
esac
