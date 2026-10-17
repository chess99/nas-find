#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_directory=$(mktemp -d "${TMPDIR:-/tmp}/nas-find-menu-test.XXXXXX")
trap 'rm -rf "$test_directory"' EXIT
clang -fobjc-arc -Werror -mmacosx-version-min=11.0 \
  -framework AppKit -framework Quartz -framework UniformTypeIdentifiers \
  tests/file-menu-macos.m -o "$test_directory/file-menu-test"
"$test_directory/file-menu-test"
