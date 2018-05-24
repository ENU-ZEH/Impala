#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Build Impala with the a variety of common build configurations and check that the build
# succeeds. Intended for use as a precommit test to make sure nothing got broken.
#
# Assumes that ninja and ccache are installed.
#
# Usage: build-all-flag-combinations.sh [--dryrun]

set -euo pipefail
trap 'echo Error in $0 at line $LINENO: $(cd "'$PWD'" && awk "NR == $LINENO" $0)' ERR

. bin/impala-config.sh

# These are configurations for buildall:
CONFIGS=(
  # Test gcc builds with and without -so:
  "-skiptests -noclean"
  "-skiptests -noclean -so"
  "-skiptests -noclean -release"
  "-skiptests -noclean -release -so -ninja"
  # clang sanitizer builds:
  "-skiptests -noclean -asan"
  "-skiptests -noclean -ubsan -so -ninja"
  "-skiptests -noclean -tsan"
)

FAILED=""

for CONFIG in "${CONFIGS[@]}"; do
  DESCRIPTION="Options $CONFIG"

  if [[ $# == 1 && $1 == "--dryrun" ]]; then
    echo $DESCRIPTION
    continue
  fi

  if ! ./bin/clean.sh; then
    echo "Clean failed"
    exit 1
  fi
  echo "Building with OPTIONS: $DESCRIPTION"
  if ! time -p ./buildall.sh $CONFIG; then
    echo "Build failed: $DESCRIPTION"
    FAILED="${FAILED}:${DESCRIPTION}"
  fi
  ccache -s
done

if [[ "$FAILED" != "" ]]
then
  echo "The following builds failed:"
  echo "$FAILED"
  exit 1
fi
