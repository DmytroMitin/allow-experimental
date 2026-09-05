#!/usr/bin/env bash

m4c_require_toolchain_values() {
  local java_feature="$1"
  local sbt_version="$2"

  if test "$java_feature" != 25; then
    echo "M4C TOOLCHAIN FAIL: peer build requires JDK feature 25, found $java_feature" >&2
    return 1
  fi
  if test "$sbt_version" != 1.12.15; then
    echo "M4C TOOLCHAIN FAIL: pinned peer requires sbt 1.12.15, found $sbt_version" >&2
    return 1
  fi
}

m4c_java_bin() {
  if test -n "${JAVA_HOME:-}"; then
    printf '%s\n' "$JAVA_HOME/bin/java"
  else
    command -v java
  fi
}

m4c_java_feature() {
  local java_bin="$1"
  "$java_bin" -XshowSettings:properties -version 2>&1 | awk \
    '$1 == "java.specification.version" && $2 == "=" { print $3; exit }'
}

m4c_peer_sbt_version() {
  local build_properties="$1"
  sed -n 's/^sbt\.version=//p' "$build_properties"
}

m4c_check_peer_toolchain() {
  local peer_root="$1"
  local build_properties="$peer_root/project/build.properties"
  local java_bin
  local java_feature
  local sbt_version

  test -x "$(m4c_java_bin)" || {
    echo "M4C TOOLCHAIN FAIL: Java executable is missing" >&2
    return 1
  }
  test -f "$build_properties" || {
    echo "M4C TOOLCHAIN FAIL: missing pinned peer build properties: $build_properties" >&2
    return 1
  }

  java_bin=$(m4c_java_bin)
  java_feature=$(m4c_java_feature "$java_bin")
  sbt_version=$(m4c_peer_sbt_version "$build_properties")
  m4c_require_toolchain_values "$java_feature" "$sbt_version"
  printf 'M4C TOOLCHAIN PASS: java=%s feature=%s sbt=%s peer=%s\n' \
    "$java_bin" "$java_feature" "$sbt_version" "$peer_root"
}
