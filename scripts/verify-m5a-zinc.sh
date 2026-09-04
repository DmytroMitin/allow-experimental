#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo "Usage: bash scripts/verify-m5a-zinc.sh" >&2
  exit 2
fi

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
work="$root/target/scala-3.9.0/m5a-verification/zinc"
fixture="$work/fixture"
evidence="$work/evidence"
socket_runtime=$(mktemp -d /tmp/allow-m5a-sbt.XXXXXX)
trap 'rm -rf -- "$socket_runtime"' EXIT
annotation39="$root/annotation/target/scala-3.9.0/allow-experimental-annotation_3-0.1.0-M0-SNAPSHOT.jar"
plugin39="$root/plugin/target/scala-3.9.0/allow-experimental-plugin_3-0.1.0-M0-SNAPSHOT.jar"
annotation38="$root/annotation/target/scala-3.8.4/allow-experimental-annotation_3-0.1.0-M0-SNAPSHOT.jar"
plugin38="$root/plugin/target/scala-3.8.4/allow-experimental-plugin_3-0.1.0-M0-SNAPSHOT.jar"

for input in "$annotation39" "$plugin39" "$annotation38" "$plugin38"; do
  if [[ ! -f "$input" ]]; then
    echo "M5A ZINC FAIL: missing exact-lane input $input" >&2
    exit 1
  fi
done

rm -rf -- "$work"
mkdir -p -- \
  "$fixture/project" \
  "$fixture/provider/src/main/scala/m5azinc" \
  "$fixture/allowed/src/main/scala/m5azinc" \
  "$fixture/consumer/src/main/scala/m5azinc" \
  "$fixture/fresh-downstream/src/main/scala/m5azinc" \
  "$fixture/plugin-absent/src/main/scala/m5azinc" \
  "$fixture/wrong-plugin/src/main/scala/m5azinc" \
  "$fixture/wrong-marker/src/main/scala/m5azinc" \
  "$fixture/.sbt-global" \
  "$evidence"

printf '%s\n' 'sbt.version=1.11.7' > "$fixture/project/build.properties"

cat > "$fixture/build.sbt" <<EOF
import sbt._
import Keys._

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "m5a.fixture"
ThisBuild / version := "0.0.0-task-local"
ThisBuild / publish / skip := true

lazy val recordM5AInputs = taskKey[Unit]("Record exact task-local M5A fixture inputs")

lazy val provider = project.in(file("provider"))

lazy val allowed = project.in(file("allowed"))
  .dependsOn(provider)
  .settings(
    Compile / unmanagedJars += file("$annotation39"),
    Compile / scalacOptions ++= Seq(
      "-Xplugin:$plugin39",
      "-Xplugin-require:allow-experimental",
      "-color:never"
    )
  )

lazy val consumer = project.in(file("consumer"))
  .dependsOn(allowed)
  .settings(
    Compile / dependencyClasspath ~= (_.filterNot { attributed =>
      val path = attributed.data.getCanonicalPath
      path == "$annotation39" || path == "$plugin39"
    })
  )

lazy val freshDownstream = project.in(file("fresh-downstream"))
  .dependsOn(allowed)
  .settings(
    Compile / dependencyClasspath ~= (_.filterNot { attributed =>
      val path = attributed.data.getCanonicalPath
      path == "$annotation39" || path == "$plugin39"
    })
  )

lazy val pluginAbsent = project.in(file("plugin-absent"))
  .dependsOn(provider)
  .settings(Compile / unmanagedJars += file("$annotation39"))

lazy val wrongPlugin = project.in(file("wrong-plugin"))
  .dependsOn(provider)
  .settings(
    Compile / unmanagedJars += file("$annotation39"),
    Compile / scalacOptions ++= Seq(
      "-Xplugin:$plugin38",
      "-Xplugin-require:allow-experimental",
      "-color:never"
    )
  )

lazy val wrongMarker = project.in(file("wrong-marker"))
  .dependsOn(provider)
  .settings(
    Compile / unmanagedJars += file("$annotation38"),
    Compile / scalacOptions ++= Seq(
      "-Xplugin:$plugin39",
      "-Xplugin-require:allow-experimental",
      "-color:never"
    )
  )

lazy val root = project.in(file("."))
  .aggregate(provider, allowed, consumer)
  .settings(
    recordM5AInputs := {
      val out = baseDirectory.value / "m5a-inputs.txt"
      val consumerCp = (consumer / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      val freshDownstreamCp = (freshDownstream / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      val allowedCp = (allowed / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      val lines = Seq(
        s"sbtVersion=\${sbtVersion.value}",
        s"scalaVersion=\${scalaVersion.value}",
        s"javaVersion=\${System.getProperty("java.version")}",
        s"javaVendor=\${System.getProperty("java.vendor")}",
        "annotation39=$annotation39",
        "plugin39=$plugin39",
        "annotation38=$annotation38",
        "plugin38=$plugin38"
      ) ++ allowedCp.map("allowedClasspath=" + _) ++
        consumerCp.map("consumerClasspath=" + _) ++
        freshDownstreamCp.map("freshDownstreamClasspath=" + _)
      IO.writeLines(out, lines)
      require(!consumerCp.exists(path => path.contains("allow-experimental-annotation") || path.contains("allow-experimental-plugin")),
        s"consumer leaked Allow artifact: \${consumerCp.mkString(",")}")
      require(!freshDownstreamCp.exists(path => path.contains("allow-experimental-annotation") || path.contains("allow-experimental-plugin")),
        s"fresh downstream leaked Allow artifact: \${freshDownstreamCp.mkString(",")}")
    }
  )
EOF

write_provider() {
  local mode="$1"
  if [[ "$mode" == ordinary ]]; then
    cat > "$fixture/provider/src/main/scala/m5azinc/Provider.scala" <<'EOF'
package m5azinc

object Provider:
  def provider(): Int = 1
EOF
  elif [[ "$mode" == experimental ]]; then
    cat > "$fixture/provider/src/main/scala/m5azinc/Provider.scala" <<'EOF'
package m5azinc

import scala.annotation.experimental

object Provider:
  @experimental def provider(): Int = 1
EOF
  else
    echo "unknown provider mode: $mode" >&2
    exit 2
  fi
}

write_allowed() {
  local mode="$1"
  if [[ "$mode" == unmarked ]]; then
    cat > "$fixture/allowed/src/main/scala/m5azinc/Allowed.scala" <<'EOF'
package m5azinc

object Allowed:
  def allowed(): Int = Provider.provider()
EOF
  elif [[ "$mode" == marked ]]; then
    cat > "$fixture/allowed/src/main/scala/m5azinc/Allowed.scala" <<'EOF'
package m5azinc

import io.github.dmytromitin.allowexperimental.allowExperimental

object Allowed:
  @allowExperimental def allowed(): Int = Provider.provider()
EOF
  elif [[ "$mode" == sibling ]]; then
    cat > "$fixture/allowed/src/main/scala/m5azinc/Allowed.scala" <<'EOF'
package m5azinc

import io.github.dmytromitin.allowexperimental.allowExperimental

object Allowed:
  @allowExperimental def allowed(): Int = Provider.provider()
  def forbiddenSibling(): Int = Provider.provider()
EOF
  else
    echo "unknown allowed mode: $mode" >&2
    exit 2
  fi
}

cat > "$fixture/consumer/src/main/scala/m5azinc/Consumer.scala" <<'EOF'
package m5azinc

object Consumer:
  def use(): Int = Allowed.allowed()
EOF

cat > "$fixture/fresh-downstream/src/main/scala/m5azinc/FreshDownstream.scala" <<'EOF'
package m5azinc

object FreshDownstream:
  def freshUse(): Int = Allowed.allowed()
EOF

for project in plugin-absent wrong-plugin wrong-marker; do
  cat > "$fixture/$project/src/main/scala/m5azinc/Use.scala" <<'EOF'
package m5azinc

import io.github.dmytromitin.allowexperimental.allowExperimental

object Use:
  @allowExperimental def allowed(): Int = Provider.provider()
EOF
done

sbt_command=(sbt -batch -Dsbt.supershell=false -Dsbt.server.autostart=false "-Dsbt.global.base=$fixture/.sbt-global")

run_fixture_sbt() {
  (cd "$fixture" && XDG_RUNTIME_DIR="$socket_runtime" "${sbt_command[@]}" "$@")
}

record_sources() {
  local label="$1"
  sha256sum \
    "$fixture/provider/src/main/scala/m5azinc/Provider.scala" \
    "$fixture/allowed/src/main/scala/m5azinc/Allowed.scala" \
    "$fixture/consumer/src/main/scala/m5azinc/Consumer.scala" \
    "$fixture/fresh-downstream/src/main/scala/m5azinc/FreshDownstream.scala" \
    > "$evidence/$label-sources.sha256"
}

record_outputs() {
  local label="$1"
  find "$fixture" -path '*/target/scala-3.9.0/classes/*' -type f -print0 \
    | sort -z \
    | xargs -0 -r sha256sum > "$evidence/$label-outputs.sha256"
  find "$fixture" -path '*/target/scala-3.9.0/classes/*' -type f \
    -printf '%p|%s|%T@\n' | sort > "$evidence/$label-output-metadata.txt"
}

run_success() {
  local label="$1"
  shift
  record_sources "$label-before"
  if ! run_fixture_sbt "$@" > "$evidence/$label.log" 2>&1; then
    sed -n '1,240p' "$evidence/$label.log" >&2
    echo "M5A ZINC FAIL: $label expected success" >&2
    exit 1
  fi
  record_outputs "$label-after"
}

run_failure() {
  local label="$1"
  shift
  record_sources "$label-before"
  if run_fixture_sbt "$@" > "$evidence/$label.log" 2>&1; then
    echo "M5A ZINC FAIL: $label unexpectedly succeeded" >&2
    exit 1
  fi
  if ! grep -Fq 'marked @experimental' "$evidence/$label.log"; then
    sed -n '1,240p' "$evidence/$label.log" >&2
    echo "M5A ZINC FAIL: $label lacked the ordinary experimental diagnostic" >&2
    exit 1
  fi
  record_outputs "$label-after"
}

write_provider ordinary
write_allowed unmarked
run_success E0-baseline clean compile recordM5AInputs

write_provider experimental
run_failure E1-provider-becomes-experimental allowed/compile consumer/compile

write_allowed marked
run_success E2-permission-add allowed/compile consumer/compile recordM5AInputs

write_allowed unmarked
run_failure E3-permission-remove allowed/compile consumer/compile

write_allowed marked
run_success E4-second-repair allowed/compile consumer/compile

write_allowed sibling
run_failure E5-sibling-negative allowed/compile consumer/compile

write_allowed marked
run_success E5-sibling-repair allowed/compile consumer/compile

write_provider ordinary
run_success E6-provider-ordinary allowed/compile consumer/compile

write_provider experimental
run_success E6-provider-experimental allowed/compile consumer/compile

run_success E6-fresh-downstream freshDownstream/compile recordM5AInputs
if ! grep -Eq 'compiling 1 Scala source to .*/fresh-downstream/target/scala-3.9.0/classes' \
    "$evidence/E6-fresh-downstream.log"; then
  echo "M5A ZINC FAIL: fresh downstream was not actually compiled after the supported plugin-produced API" >&2
  exit 1
fi

record_outputs E7-noop-before
run_success E7-noop compile recordM5AInputs
record_outputs E7-noop-after
if ! cmp -s "$evidence/E7-noop-before-outputs.sha256" "$evidence/E7-noop-after-outputs.sha256"; then
  echo "M5A ZINC FAIL: no-op compile changed class/TASTy output hashes" >&2
  exit 1
fi
if grep -Eq '\[info\] compiling [0-9]+ Scala source' "$evidence/E7-noop.log"; then
  echo "M5A ZINC FAIL: no-op compile unexpectedly recompiled Scala sources" >&2
  exit 1
fi

run_failure F1-plugin-absent pluginAbsent/compile

record_sources F2-wrong-plugin-before
if run_fixture_sbt wrongPlugin/compile > "$evidence/F2-wrong-plugin.log" 2>&1; then
  wrong_plugin=UNSAFE_PASS
else
  if grep -Fq 'plugin built for exact Scala 3.8.4 cannot run on Scala 3.9.0' "$evidence/F2-wrong-plugin.log"; then
    wrong_plugin=REJECTED_FAIL_CLOSED
  else
    wrong_plugin=LINKAGE_FAIL_CLOSED
  fi
fi
record_outputs F2-wrong-plugin-after

record_sources F3-wrong-marker-before
if run_fixture_sbt wrongMarker/compile > "$evidence/F3-wrong-marker.log" 2>&1; then
  wrong_marker=COMPATIBLE_OBSERVED
else
  wrong_marker=REJECTED
fi
record_outputs F3-wrong-marker-after

cp "$fixture/m5a-inputs.txt" "$evidence/m5a-inputs.txt"
sha256sum "$annotation39" "$plugin39" "$annotation38" "$plugin38" \
  > "$evidence/artifact-inputs.sha256"
{
  printf '%s\n' \
    'SBT_ZINC_MULTIPROJECT=PASS' \
    'SBT_NON_CLEAN_SEQUENCE=PASS' \
    'ZINC_NOOP_OBSERVED=YES' \
    'INCREMENTAL_PROVIDER_BECOMES_EXPERIMENTAL_INVALIDATES_ALLOWED=PASS' \
    'INCREMENTAL_PERMISSION_ADD_RECOVERS=PASS' \
    'INCREMENTAL_PERMISSION_REMOVE_FAILS_CLOSED=PASS' \
    'INCREMENTAL_SECOND_REPAIR_RECOVERS=PASS' \
    'INCREMENTAL_SIBLING_NEGATIVE=PASS' \
    'INCREMENTAL_SIBLING_REPAIR=PASS' \
    'INCREMENTAL_PROVIDER_TOGGLE=PASS' \
    'DOWNSTREAM_WITHOUT_ALLOW_PLUGIN=PASS' \
    'DOWNSTREAM_WITHOUT_ALLOW_MARKER=PASS' \
    'PLUGIN_ABSENT_PERMISSION_SAFETY=PASS' \
    "WRONG_LANE_PLUGIN_3_8_4_ON_3_9_0=$wrong_plugin" \
    "WRONG_LANE_MARKER_3_8_4_ON_3_9_0=$wrong_marker" \
    'PUBLIC_MARKER_CROSS_LANE_COMPATIBILITY_CLAIMED=NO' \
    'PERSISTENT_SBT_BSP_IDE_CLAIMED=NO' \
    'GLOBAL_EXPERIMENTAL_REQUIRED=NO'
} > "$work/zinc-summary.txt"

find "$evidence" -type f -print0 | sort -z | xargs -0 sha256sum > "$work/zinc-evidence.sha256"
printf 'M5A ZINC PASS: non-clean matrix complete; wrong-plugin=%s; wrong-marker=%s\n' "$wrong_plugin" "$wrong_marker"
