#!/usr/bin/env bash
set -euo pipefail

if (( $# != 2 )); then
  echo "Usage: bash scripts/verify-m5b-zinc-lane.sh <3.3.8|3.8.4> <comma-separated-wrong-plugin-versions>" >&2
  exit 2
fi

lane=$1
wrong_csv=$2
case "$lane" in
  3.3.8|3.8.4) ;;
  *) echo "M5B ZINC FAIL: unsupported target lane $lane" >&2; exit 2 ;;
esac

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
work="$root/target/scala-$lane/m5b-verification/zinc"
fixture="$work/fixture"
evidence="$work/evidence"
socket_runtime=$(mktemp -d "/tmp/allow-m5b-$lane-sbt.XXXXXX")
trap 'rm -rf -- "$socket_runtime"' EXIT
artifact_name=0.1.0-M0-SNAPSHOT
annotation="$root/annotation/target/scala-$lane/allow-experimental-annotation_3-$artifact_name.jar"
plugin="$root/plugin/target/scala-$lane/allow-experimental-plugin_3-$artifact_name.jar"

IFS=',' read -r -a wrong_versions <<< "$wrong_csv"
if (( ${#wrong_versions[@]} == 0 || ${#wrong_versions[@]} > 2 )); then
  echo "M5B ZINC FAIL: expected one or two wrong plugin versions" >&2
  exit 2
fi
wrong1=${wrong_versions[0]}
wrong2=${wrong_versions[1]:-${wrong_versions[0]}}
wrong_count=${#wrong_versions[@]}
wrong_plugin1="$root/plugin/target/scala-$wrong1/allow-experimental-plugin_3-$artifact_name.jar"
wrong_plugin2="$root/plugin/target/scala-$wrong2/allow-experimental-plugin_3-$artifact_name.jar"

for input in "$annotation" "$plugin" "$wrong_plugin1" "$wrong_plugin2"; do
  if [[ ! -f "$input" ]]; then
    echo "M5B ZINC FAIL: missing exact-lane input $input" >&2
    exit 1
  fi
done

rm -rf -- "$work"
mkdir -p -- \
  "$fixture/project" \
  "$fixture/provider/src/main/scala/m5bzinc" \
  "$fixture/allowed/src/main/scala/m5bzinc" \
  "$fixture/consumer/src/main/scala/m5bzinc" \
  "$fixture/fresh-downstream/src/main/scala/m5bzinc" \
  "$fixture/plugin-absent/src/main/scala/m5bzinc" \
  "$fixture/wrong-plugin-1/src/main/scala/m5bzinc" \
  "$fixture/wrong-plugin-2/src/main/scala/m5bzinc" \
  "$fixture/.sbt-global" \
  "$evidence"

printf '%s\n' 'sbt.version=1.11.7' > "$fixture/project/build.properties"

cat > "$fixture/build.sbt" <<EOF
import sbt._
import Keys._

ThisBuild / scalaVersion := "$lane"
ThisBuild / organization := "m5b.fixture"
ThisBuild / version := "0.0.0-task-local"
ThisBuild / publish / skip := true

lazy val recordM5BInputs = taskKey[Unit]("Record exact task-local M5B fixture inputs")

lazy val provider = project.in(file("provider"))

lazy val allowed = project.in(file("allowed"))
  .dependsOn(provider)
  .settings(
    Compile / unmanagedJars += file("$annotation"),
    Compile / scalacOptions ++= Seq(
      "-Xplugin:$plugin",
      "-Xplugin-require:allow-experimental",
      "-color:never"
    )
  )

lazy val consumer = project.in(file("consumer"))
  .dependsOn(allowed)
  .settings(
    Compile / dependencyClasspath ~= (_.filterNot { attributed =>
      val path = attributed.data.getCanonicalPath
      path == "$annotation" || path == "$plugin"
    })
  )

lazy val freshDownstream = project.in(file("fresh-downstream"))
  .dependsOn(allowed)
  .settings(
    Compile / dependencyClasspath ~= (_.filterNot { attributed =>
      val path = attributed.data.getCanonicalPath
      path == "$annotation" || path == "$plugin"
    })
  )

lazy val pluginAbsent = project.in(file("plugin-absent"))
  .dependsOn(provider)
  .settings(Compile / unmanagedJars += file("$annotation"))

lazy val wrongPlugin1 = project.in(file("wrong-plugin-1"))
  .dependsOn(provider)
  .settings(
    Compile / unmanagedJars += file("$annotation"),
    Compile / scalacOptions ++= Seq(
      "-Xplugin:$wrong_plugin1",
      "-Xplugin-require:allow-experimental",
      "-color:never"
    )
  )

lazy val wrongPlugin2 = project.in(file("wrong-plugin-2"))
  .dependsOn(provider)
  .settings(
    Compile / unmanagedJars += file("$annotation"),
    Compile / scalacOptions ++= Seq(
      "-Xplugin:$wrong_plugin2",
      "-Xplugin-require:allow-experimental",
      "-color:never"
    )
  )

lazy val root = project.in(file("."))
  .aggregate(provider, allowed, consumer)
  .settings(
    recordM5BInputs := {
      val out = baseDirectory.value / "m5b-inputs.txt"
      val consumerCp = (consumer / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      val freshDownstreamCp = (freshDownstream / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      val allowedCp = (allowed / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      val lines = Seq(
        s"sbtVersion=\${sbtVersion.value}",
        s"scalaVersion=\${scalaVersion.value}",
        s"javaVersion=\${System.getProperty("java.version")}",
        s"javaVendor=\${System.getProperty("java.vendor")}",
        "annotation=$annotation",
        "plugin=$plugin",
        "wrongPlugin1=$wrong_plugin1",
        "wrongPlugin2=$wrong_plugin2"
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
  local mode=$1
  if [[ "$mode" == ordinary ]]; then
    cat > "$fixture/provider/src/main/scala/m5bzinc/Provider.scala" <<'EOF'
package m5bzinc

object Provider:
  def provider(): Int = 1
EOF
  elif [[ "$mode" == experimental ]]; then
    cat > "$fixture/provider/src/main/scala/m5bzinc/Provider.scala" <<'EOF'
package m5bzinc

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
  local mode=$1
  if [[ "$mode" == unmarked ]]; then
    cat > "$fixture/allowed/src/main/scala/m5bzinc/Allowed.scala" <<'EOF'
package m5bzinc

object Allowed:
  def allowed(): Int = Provider.provider()
EOF
  elif [[ "$mode" == marked ]]; then
    cat > "$fixture/allowed/src/main/scala/m5bzinc/Allowed.scala" <<'EOF'
package m5bzinc

import io.github.dmytromitin.allowexperimental.allowExperimental

object Allowed:
  @allowExperimental def allowed(): Int = Provider.provider()
EOF
  elif [[ "$mode" == sibling ]]; then
    cat > "$fixture/allowed/src/main/scala/m5bzinc/Allowed.scala" <<'EOF'
package m5bzinc

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

cat > "$fixture/consumer/src/main/scala/m5bzinc/Consumer.scala" <<'EOF'
package m5bzinc

object Consumer:
  def use(): Int = Allowed.allowed()
EOF

cat > "$fixture/fresh-downstream/src/main/scala/m5bzinc/FreshDownstream.scala" <<'EOF'
package m5bzinc

object FreshDownstream:
  def freshUse(): Int = Allowed.allowed()
EOF

for project in plugin-absent wrong-plugin-1 wrong-plugin-2; do
  cat > "$fixture/$project/src/main/scala/m5bzinc/Use.scala" <<'EOF'
package m5bzinc

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
  local label=$1
  sha256sum \
    "$fixture/provider/src/main/scala/m5bzinc/Provider.scala" \
    "$fixture/allowed/src/main/scala/m5bzinc/Allowed.scala" \
    "$fixture/consumer/src/main/scala/m5bzinc/Consumer.scala" \
    "$fixture/fresh-downstream/src/main/scala/m5bzinc/FreshDownstream.scala" \
    > "$evidence/$label-sources.sha256"
}

record_outputs() {
  local label=$1
  find "$fixture" -path "*/target/scala-$lane/classes/*" -type f -print0 \
    | sort -z | xargs -0 -r sha256sum > "$evidence/$label-outputs.sha256"
  find "$fixture" -path "*/target/scala-$lane/classes/*" -type f \
    -printf '%p|%s|%T@\n' | sort > "$evidence/$label-output-metadata.txt"
}

run_success() {
  local label=$1
  shift
  record_sources "$label-before"
  if ! run_fixture_sbt "$@" > "$evidence/$label.log" 2>&1; then
    sed -n '1,240p' "$evidence/$label.log" >&2
    echo "M5B ZINC FAIL [$lane]: $label expected success" >&2
    exit 1
  fi
  record_outputs "$label-after"
}

run_failure() {
  local label=$1
  shift
  record_sources "$label-before"
  if run_fixture_sbt "$@" > "$evidence/$label.log" 2>&1; then
    echo "M5B ZINC FAIL [$lane]: $label unexpectedly succeeded" >&2
    exit 1
  fi
  if ! grep -Fq 'marked @experimental' "$evidence/$label.log"; then
    sed -n '1,240p' "$evidence/$label.log" >&2
    echo "M5B ZINC FAIL [$lane]: $label lacked the stock experimental diagnostic" >&2
    exit 1
  fi
  record_outputs "$label-after"
}

classify_wrong_plugin() {
  local label=$1
  local project=$2
  local expected=$3
  record_sources "$label-before"
  if run_fixture_sbt "$project/compile" > "$evidence/$label.log" 2>&1; then
    printf '%s' UNSAFE_PERMISSION_GRANTED
  elif grep -Fq "plugin built for exact Scala $expected cannot run on Scala $lane" "$evidence/$label.log"; then
    printf '%s' EXACT_VERSION_GUARD_REJECTION
  elif grep -Eq 'NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|AbstractMethodError|IncompatibleClassChangeError|could not load|Error while loading|Failed to load|not a valid compiler plugin' "$evidence/$label.log" &&
      grep -Eq 'io\.github\.dmytromitin\.allowexperimental\.plugin|AllowExperimentalPlugin|dotty\.tools\.dotc\.plugins\.Plugins|compiler plugin' "$evidence/$label.log"; then
    printf '%s' PLUGIN_LOAD_REJECTION
  elif grep -Fq 'marked @experimental' "$evidence/$label.log"; then
    printf '%s' OTHER_FAIL_CLOSED_REJECTION
  else
    printf '%s' UNCLASSIFIED_FAILURE
  fi
}

write_provider ordinary
write_allowed unmarked
run_success E0-baseline clean compile recordM5BInputs

write_provider experimental
run_failure E1-provider-becomes-experimental allowed/compile consumer/compile

write_allowed marked
run_success E2-permission-add allowed/compile consumer/compile recordM5BInputs

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

run_success E6-fresh-downstream freshDownstream/compile recordM5BInputs
if ! grep -Eq "compiling 1 Scala source to .*/fresh-downstream/target/scala-$lane/classes" \
    "$evidence/E6-fresh-downstream.log"; then
  echo "M5B ZINC FAIL [$lane]: fresh downstream was not first compiled after the final supported state" >&2
  exit 1
fi

record_outputs E7-noop-before
run_success E7-noop compile recordM5BInputs
record_outputs E7-noop-after
if ! cmp -s "$evidence/E7-noop-before-outputs.sha256" "$evidence/E7-noop-after-outputs.sha256"; then
  echo "M5B ZINC FAIL [$lane]: no-op compile changed class/TASTy hashes" >&2
  exit 1
fi
if grep -Eq '\[info\] compiling [0-9]+ Scala source' "$evidence/E7-noop.log"; then
  echo "M5B ZINC FAIL [$lane]: no-op compile recompiled Scala sources" >&2
  exit 1
fi

run_failure F1-plugin-absent pluginAbsent/compile
wrong_result1=$(classify_wrong_plugin F2-wrong-plugin-1 wrongPlugin1 "$wrong1")
record_outputs F2-wrong-plugin-1-after
wrong_result2=NOT_RUN
if (( wrong_count == 2 )); then
  wrong_result2=$(classify_wrong_plugin F3-wrong-plugin-2 wrongPlugin2 "$wrong2")
  record_outputs F3-wrong-plugin-2-after
fi

if [[ "$wrong_result1" == UNSAFE_PERMISSION_GRANTED || "$wrong_result2" == UNSAFE_PERMISSION_GRANTED ||
      "$wrong_result1" == UNCLASSIFIED_FAILURE || "$wrong_result2" == UNCLASSIFIED_FAILURE ]]; then
  echo "M5B ZINC FAIL [$lane]: a wrong-lane result was unsafe or unrelated to fail-closed permission handling" >&2
  sed -n '1,160p' "$evidence/F2-wrong-plugin-1.log" >&2
  if (( wrong_count == 2 )); then
    sed -n '1,160p' "$evidence/F3-wrong-plugin-2.log" >&2
  fi
  exit 1
fi

cp "$fixture/m5b-inputs.txt" "$evidence/m5b-inputs.txt"
if (( wrong_count == 2 )); then
  sha256sum "$annotation" "$plugin" "$wrong_plugin1" "$wrong_plugin2" > "$evidence/artifact-inputs.sha256"
else
  sha256sum "$annotation" "$plugin" "$wrong_plugin1" > "$evidence/artifact-inputs.sha256"
fi
lane_key=${lane//./_}
{
  printf '%s\n' \
    "SBT_ZINC_MULTIPROJECT_$lane_key=PASS" \
    "SBT_NON_CLEAN_SEQUENCE_$lane_key=PASS" \
    "ZINC_NOOP_OBSERVED_$lane_key=YES" \
    "INCREMENTAL_PROVIDER_BECOMES_EXPERIMENTAL_INVALIDATES_ALLOWED_$lane_key=PASS" \
    "INCREMENTAL_PERMISSION_ADD_REMOVE_REPAIR_$lane_key=PASS" \
    "INCREMENTAL_SIBLING_NEGATIVE_REPAIR_$lane_key=PASS" \
    "INCREMENTAL_PROVIDER_TOGGLE_$lane_key=PASS" \
    "DOWNSTREAM_WITHOUT_ALLOW_PLUGIN_$lane_key=PASS" \
    "DOWNSTREAM_WITHOUT_ALLOW_MARKER_$lane_key=PASS" \
    "PLUGIN_ABSENT_PERMISSION_SAFETY_$lane_key=PASS" \
    "WRONG_LANE_PLUGIN_${wrong1//./_}_ON_$lane_key=$wrong_result1"
  if (( wrong_count == 2 )); then
    printf '%s\n' "WRONG_LANE_PLUGIN_${wrong2//./_}_ON_$lane_key=$wrong_result2"
  fi
  printf '%s\n' \
    'WRONG_LANE_PLUGIN_PERMISSION_GRANTED=NO' \
    'PUBLIC_PLUGIN_BINARY_COMPATIBILITY_CLAIMED=NO' \
    'PUBLIC_MARKER_CROSS_LANE_COMPATIBILITY_CLAIMED=NO' \
    'PERSISTENT_SBT_SERVER_CLAIMED=NO' \
    'GLOBAL_EXPERIMENTAL_REQUIRED=NO'
} > "$work/zinc-summary.txt"

compiler_sources=$(find "$HOME/.cache/coursier" \
  -path "*/scala3-compiler_3/$lane/scala3-compiler_3-$lane-sources.jar" -print -quit)
bridge_sources=$(find "$HOME/.cache/coursier" \
  -path "*/scala3-sbt-bridge/$lane/scala3-sbt-bridge-$lane-sources.jar" -print -quit)
compiler_jar="$(dirname -- "$compiler_sources")/scala3-compiler_3-$lane.jar"
bridge_jar="$(dirname -- "$bridge_sources")/scala3-sbt-bridge-$lane.jar"
sbt_lib="$fixture/.sbt-global/boot/scala-2.12.20/org.scala-sbt/sbt/1.11.7"
for input in "$compiler_jar" "$compiler_sources" "$bridge_jar" "$bridge_sources" \
    "$sbt_lib/zinc_2.12-1.11.0.jar" "$sbt_lib/compiler-interface-1.11.0.jar"; do
  [[ -f "$input" ]] || { echo "M5B ZINC FAIL [$lane]: missing source-audit input $input" >&2; exit 1; }
done
{
  printf '%s\n' \
    'sbtVersion=1.11.7' \
    'zincVersion=1.11.0' \
    "scalaVersion=$lane" \
    'scala3BridgeCompileLifecycle=CompilerBridge.run constructs one fresh CompilerBridgeDriver per compile invocation' \
    'scala3BridgeDriverLifecycle=CompilerBridgeDriver constructs one fresh ContextBase and Compiler' \
    'zincClaim=persisted analysis and class-TASTy outputs across real non-clean batch sbt invocations' \
    'compilerInstanceReuseClaimedForZinc=NO' \
    'persistentSbtBspIdeClaimed=NO'
  sha256sum "$compiler_jar" "$compiler_sources" "$bridge_jar" "$bridge_sources" \
    "$sbt_lib/zinc_2.12-1.11.0.jar" "$sbt_lib/compiler-interface-1.11.0.jar"
} > "$work/zinc-source-audit.txt"

find "$evidence" -type f -print0 | sort -z | xargs -0 sha256sum > "$work/zinc-evidence.sha256"
if (( wrong_count == 2 )); then
  printf 'M5B ZINC PASS [%s]: non-clean lifecycle and mismatch safety; %s=%s; %s=%s\n' \
    "$lane" "$wrong1" "$wrong_result1" "$wrong2" "$wrong_result2"
else
  printf 'M5B ZINC PASS [%s]: non-clean lifecycle and mismatch safety; %s=%s\n' \
    "$lane" "$wrong1" "$wrong_result1"
fi
