#!/usr/bin/env bash
set -euo pipefail

if (( $# != 1 )); then
  echo "Usage: bash scripts/verify-persistent-build-lifecycle-lane.sh <3.3.8|3.8.4|3.9.0>" >&2
  exit 2
fi

lane=$1
case "$lane" in
  3.3.8|3.8.4|3.9.0) ;;
  *) echo "M5C FAIL: unsupported exact lane $lane" >&2; exit 2 ;;
esac

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
work="$root/target/scala-$lane/m5c-verification"
artifact_version=0.1.0-SNAPSHOT
annotation="$root/annotation/target/scala-$lane/allow-experimental-annotation_3-$artifact_version.jar"
plugin="$root/plugin/target/scala-$lane/allow-experimental-plugin_$lane-$artifact_version.jar"

for input in "$annotation" "$plugin"; do
  [[ -f "$input" ]] || { echo "M5C FAIL [$lane]: missing exact-lane artifact $input" >&2; exit 1; }
done

mkdir -p -- "$work"

create_fixture() {
  local fixture=$1
  local evidence=$2
  rm -rf -- "$fixture" "$evidence"
  mkdir -p -- \
    "$fixture/project" \
    "$fixture/provider/src/main/scala/m5cfixture" \
    "$fixture/allowed/src/main/scala/m5cfixture" \
    "$fixture/consumer/src/main/scala/m5cfixture" \
    "$fixture/fresh-downstream/src/main/scala/m5cfixture" \
    "$evidence"
  printf '%s\n' 'sbt.version=1.11.7' > "$fixture/project/build.properties"
  cat > "$fixture/build.sbt" <<EOF
import sbt._
import Keys._

ThisBuild / scalaVersion := "$lane"
ThisBuild / organization := "m5c.fixture"
ThisBuild / version := "0.0.0-task-local"
ThisBuild / publish / skip := true
Global / shellPrompt := { _ => "M5C> " }

lazy val recordM5CIdentity = taskKey[Unit]("Record the persistent sbt JVM identity")
lazy val recordM5CInputs = taskKey[Unit]("Record exact M5C fixture inputs")

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
    Compile / externalDependencyClasspath ~= (_.filterNot { attributed =>
      val path = attributed.data.getCanonicalPath
      path == "$annotation" || path == "$plugin"
    }),
    Compile / dependencyClasspath ~= (_.filterNot { attributed =>
      val path = attributed.data.getCanonicalPath
      path == "$annotation" || path == "$plugin"
    })
  )

lazy val freshDownstream = project.in(file("fresh-downstream"))
  .dependsOn(allowed)
  .settings(
    Compile / externalDependencyClasspath ~= (_.filterNot { attributed =>
      val path = attributed.data.getCanonicalPath
      path == "$annotation" || path == "$plugin"
    }),
    Compile / dependencyClasspath ~= (_.filterNot { attributed =>
      val path = attributed.data.getCanonicalPath
      path == "$annotation" || path == "$plugin"
    })
  )

lazy val root = project.in(file("."))
  .aggregate(provider, allowed, consumer)
  .settings(
    recordM5CIdentity := {
      val runtime = java.lang.management.ManagementFactory.getRuntimeMXBean
      val line = Seq(
        s"pid=\${ProcessHandle.current.pid}",
        s"runtimeName=\${runtime.getName}",
        s"jvmStartMillis=\${runtime.getStartTime}",
        s"javaVersion=\${System.getProperty("java.version")}",
        s"sbtVersion=\${sbtVersion.value}",
        s"scalaVersion=\${scalaVersion.value}"
      ).mkString("|")
      IO.append(baseDirectory.value / "m5c-identities.txt", line + "\n")
    },
    recordM5CInputs := {
      val consumerCp = (consumer / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      val downstreamCp = (freshDownstream / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      require(!consumerCp.exists(path => path.contains("allow-experimental-annotation") || path.contains("allow-experimental-plugin")),
        s"consumer leaked Allow artifact: \${consumerCp.mkString(",")}")
      require(!downstreamCp.exists(path => path.contains("allow-experimental-annotation") || path.contains("allow-experimental-plugin")),
        s"fresh downstream leaked Allow artifact: \${downstreamCp.mkString(",")}")
      IO.writeLines(baseDirectory.value / "m5c-inputs.txt", Seq(
        s"sbtVersion=\${sbtVersion.value}",
        s"scalaVersion=\${scalaVersion.value}",
        s"javaVersion=\${System.getProperty("java.version")}",
        "annotation=$annotation",
        "plugin=$plugin"
      ) ++ consumerCp.map("consumerClasspath=" + _) ++ downstreamCp.map("freshDownstreamClasspath=" + _))
    }
  )
EOF

  cat > "$fixture/consumer/src/main/scala/m5cfixture/Consumer.scala" <<'EOF'
package m5cfixture

object Consumer:
  def use(): Int = Allowed.allowed()
EOF
  cat > "$fixture/fresh-downstream/src/main/scala/m5cfixture/FreshDownstream.scala" <<'EOF'
package m5cfixture

object FreshDownstream:
  def freshUse(): Int = Allowed.allowed()
EOF
}

persistent_fixture="$work/persistent-sbt/fixture"
persistent_evidence="$work/persistent-sbt/evidence"
bsp_fixture="$work/bsp/fixture"
bsp_evidence="$work/bsp/evidence"
runtime=$(mktemp -d "/tmp/allow-m5c-$lane.XXXXXX")
trap 'rm -rf -- "$runtime"' EXIT

create_fixture "$persistent_fixture" "$persistent_evidence"
python3 "$root/scripts/build_lifecycle_driver.py" persistent-sbt \
  --lane "$lane" --fixture "$persistent_fixture" --evidence "$persistent_evidence" \
  --work "$work" --runtime "$runtime/persistent-sbt"

create_fixture "$bsp_fixture" "$bsp_evidence"
python3 "$root/scripts/build_lifecycle_driver.py" bsp \
  --lane "$lane" --fixture "$bsp_fixture" --evidence "$bsp_evidence" \
  --work "$work" --runtime "$runtime/bsp"

find_source() {
  local coordinate=$1
  local basename_pattern=$2
  local matches=()
  mapfile -t matches < <(cs fetch --classifier sources "$coordinate" | while IFS= read -r path; do
    [[ $(basename -- "$path") == "$basename_pattern" ]] && printf '%s\n' "$path"
  done)
  (( ${#matches[@]} == 1 )) || {
    echo "M5C FAIL [$lane]: expected one source jar $basename_pattern, found ${#matches[@]}" >&2
    return 1
  }
  printf '%s\n' "${matches[0]}"
}

find_boot() {
  local basename_pattern=$1
  local matches=()
  mapfile -t matches < <(find "$work/.sbt-boot" -type f -name "$basename_pattern" -print)
  (( ${#matches[@]} == 1 )) || {
    echo "M5C FAIL [$lane]: expected one resolved boot jar $basename_pattern, found ${#matches[@]}" >&2
    return 1
  }
  printf '%s\n' "${matches[0]}"
}

main_source=$(find_source org.scala-sbt:main_2.12:1.11.7 main_2.12-1.11.7-sources.jar)
protocol_source=$(find_source org.scala-sbt:protocol_2.12:1.11.7 protocol_2.12-1.11.7-sources.jar)
command_source=$(find_source org.scala-sbt:command_2.12:1.11.7 command_2.12-1.11.7-sources.jar)
zinc_source=$(find_source org.scala-sbt:zinc_2.12:1.11.0 zinc_2.12-1.11.0-sources.jar)
bridge_source=$(find_source "org.scala-lang:scala3-sbt-bridge:$lane" "scala3-sbt-bridge-$lane-sources.jar")
main_binary=$(find_boot main_2.12-1.11.7.jar)
protocol_binary=$(find_boot protocol_2.12-1.11.7.jar)
zinc_binary=$(find_boot zinc_2.12-1.11.0.jar)
compiler_interface_binary=$(find_boot compiler-interface-1.11.0.jar)

{
  printf '%s\n' \
    "scalaVersion=$lane" \
    'sbtVersion=1.11.7' \
    'zincVersion=1.11.0' \
    'bspVersion=2.1.0-M1' \
    'persistentSbtProcess=one JVM for all lane transitions' \
    'zincState=analysis and outputs retained by one live sbt process between non-clean compile requests' \
    'scala3CompilerLifetime=fresh CompilerBridgeDriver per compile invocation' \
    'pluginCacheBoundary=the long-lived sbt process may retain bridge and artifact classloader caches but every actual Scala compile creates a fresh bridge driver/context/compiler' \
    'processIdentityEvidence=Linux pid plus proc start ticks plus cmdline and JVM runtime start milliseconds' \
    'bspLaunch=interactive sbt 1.11.7 server plus one sbt -bsp stdio proxy' \
    'bspTransport=stdio proxy to the task-local sbt Unix socket server' \
    'bspHandshake=one build/initialize followed by build/initialized and workspace/buildTargets' \
    'bspCompile=ordinary sbt compileIncremental task with diagnostics' \
    'sourceVisibility=atomic source replacement before the next buildTarget/compile request' \
    'bspShutdown=build/shutdown then build/exit then client stdin close'
  for audited in "$main_source" "$protocol_source" "$command_source" "$zinc_source" "$bridge_source" \
      "$main_binary" "$protocol_binary" "$zinc_binary" "$compiler_interface_binary"; do
    read -r digest _ < <(sha256sum "$audited")
    printf 'sha256=%s path=%s\n' "$digest" "$audited"
  done
} > "$work/source-audit.txt"

find "$persistent_evidence" "$bsp_evidence" -type f -print0 | sort -z | xargs -0 sha256sum \
  > "$work/m5c-evidence.sha256"
sha256sum "$annotation" "$plugin" > "$work/artifact-inputs.sha256"

printf 'M5C LANE PASS [%s]: persistent sbt and real BSP lifecycle sessions\n' "$lane"
