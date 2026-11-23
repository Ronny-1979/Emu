#!/bin/bash
# Prüft Run-Ahead und Bildverzögerung mit einem Schein-Kern am PC.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
g++ -std=c++17 -Wall -Wextra -O1 -o /tmp/polo_test_native "$HERE/native/test_native.cpp"
/tmp/polo_test_native
