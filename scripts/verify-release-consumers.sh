#!/usr/bin/env bash
set -euo pipefail

if (( $# != 2 )); then
  echo 'Usage: bash scripts/verify-release-consumers.sh <candidate-repository> <evidence-root>' >&2
  exit 2
fi

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
CANDIDATE_REPOSITORY=$(cd "$1" && pwd -P)
EVIDENCE_ROOT=$(realpath -m "$2")
VERSION=0.1.0
ORGANIZATION=com.github.dmytromitin
WORK="$EVIDENCE_ROOT/work"
TOOL_STATE="$EVIDENCE_ROOT/tool-state"
REPOSITORIES="$TOOL_STATE/repositories"

rm -rf -- "$EVIDENCE_ROOT"
mkdir -p -- "$WORK" "$TOOL_STATE"
cat > "$REPOSITORIES" <<EOF
[repositories]
  release-candidate-task-local: file://$CANDIDATE_REPOSITORY/
  maven-central
EOF

export COURSIER_CACHE="$TOOL_STATE/coursier-cache"
SBT=(sbt -batch -Dsbt.supershell=false -Dsbt.server.autostart=false
  "-Dsbt.global.base=$TOOL_STATE/global"
  "-Dsbt.boot.directory=$TOOL_STATE/boot"
  "-Dsbt.ivy.home=$TOOL_STATE/ivy"
  -Dsbt.override.build.repos=true
  "-Dsbt.repository.config=$REPOSITORIES")
isolated_sbt_opts="${SBT_OPTS:-} -Dsbt.global.base=$TOOL_STATE/global -Dsbt.boot.directory=$TOOL_STATE/boot -Dsbt.ivy.home=$TOOL_STATE/ivy -Dsbt.override.build.repos=true -Dsbt.repository.config=$REPOSITORIES"

run_sbt() {
  SBT_OPTS="$isolated_sbt_opts" "${SBT[@]}" "$@"
}

create_consumer() {
  local lane=$1
  local wrong_lane=$2
  local fixture="$WORK/scala-$lane"
  local evidence="$EVIDENCE_ROOT/scala-$lane"
  mkdir -p -- \
    "$fixture/project" \
    "$fixture/provider/src/main/scala/releasefixture" \
    "$fixture/allowed/src/main/scala/releasefixture" \
    "$fixture/downstream/src/main/scala/releasefixture" \
    "$fixture/direct-negative/src/main/scala/releasefixture" \
    "$fixture/marker-without-plugin/src/main/scala/releasefixture" \
    "$fixture/wrong-plugin/src/main/scala/releasefixture" \
    "$evidence"

  printf '%s\n' 'sbt.version=1.12.15' > "$fixture/project/build.properties"
  cat > "$fixture/build.sbt" <<EOF
import sbt._
import Keys._

ThisBuild / scalaVersion := "$lane"
ThisBuild / organization := "release.fixture"
ThisBuild / version := "0.0.0-task-local"
ThisBuild / publish / skip := true
ThisBuild / resolvers := Seq(Resolver.file("release-candidate-task-local", file("$CANDIDATE_REPOSITORY"))(Resolver.mavenStylePatterns))

lazy val recordReleaseResolution = taskKey[Unit]("Record release coordinate resolution and downstream isolation")

lazy val provider = project.in(file("provider"))

lazy val allowed = project.in(file("allowed"))
  .dependsOn(provider)
  .settings(
    libraryDependencies += "$ORGANIZATION" %% "allow-experimental-annotation" % "$VERSION" % Provided,
    libraryDependencies += compilerPlugin(
      ("$ORGANIZATION" % "allow-experimental-plugin" % "$VERSION").cross(CrossVersion.full)
    )
  )

lazy val downstream = project.in(file("downstream"))
  .dependsOn(allowed)

lazy val directNegative = project.in(file("direct-negative"))
  .dependsOn(provider)

lazy val markerWithoutPlugin = project.in(file("marker-without-plugin"))
  .dependsOn(provider)
  .settings(
    libraryDependencies += "$ORGANIZATION" %% "allow-experimental-annotation" % "$VERSION" % Provided
  )

lazy val wrongPlugin = project.in(file("wrong-plugin"))
  .dependsOn(provider)
  .settings(
    libraryDependencies += "$ORGANIZATION" %% "allow-experimental-annotation" % "$VERSION" % Provided,
    libraryDependencies += compilerPlugin("$ORGANIZATION" % "allow-experimental-plugin_$wrong_lane" % "$VERSION")
  )

lazy val root = project.in(file("."))
  .aggregate(provider, allowed, downstream)
  .settings(
    recordReleaseResolution := {
      val allowedCp = (allowed / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      val allowedOptions = (allowed / Compile / scalacOptions).value
      val downstreamCp = (downstream / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      require(allowedCp.exists(path => path.startsWith("$CANDIDATE_REPOSITORY/") && path.contains("allow-experimental-annotation_3")), allowedCp.mkString("\n"))
      require(allowedOptions.exists(option => option.contains("$CANDIDATE_REPOSITORY/") && option.contains("allow-experimental-plugin_$lane")), allowedOptions.mkString("\n"))
      require(!allowedOptions.exists(_.split("[ ,]").contains("-experimental")), allowedOptions.mkString("\n"))
      require(!downstreamCp.exists(_.contains("allow-experimental-annotation")), downstreamCp.mkString("\n"))
      require(!downstreamCp.exists(_.contains("allow-experimental-plugin")), downstreamCp.mkString("\n"))
      IO.writeLines(file("$evidence/resolution.txt"),
        Seq("scalaVersion=$lane", "allowRepository=$CANDIDATE_REPOSITORY", "globalExperimentalUsed=false") ++
          allowedCp.map("allowedClasspath=" + _) ++
          allowedOptions.map("allowedScalacOption=" + _) ++
          downstreamCp.map("downstreamClasspath=" + _))
    }
  )
EOF

  cat > "$fixture/provider/src/main/scala/releasefixture/Provider.scala" <<'EOF'
package releasefixture

import scala.annotation.experimental

object Provider:
  @experimental def value(): Int = 1
EOF

  cat > "$fixture/allowed/src/main/scala/releasefixture/Allowed.scala" <<'EOF'
package releasefixture

import io.github.dmytromitin.allowexperimental.allowExperimental
import scala.quoted.*

object Allowed:
  @allowExperimental def value(): Int = Provider.value()

object MacroApi:
  inline def symbolInfoSummary[T]: String = ${ symbolInfoSummaryImpl[T] }

  @allowExperimental
  private def symbolInfoSummaryImpl[T: Type](using Quotes): Expr[String] =
    import quotes.reflect.*
    val info = TypeRepr.of[T].typeSymbol.info
    Expr(if info.show.nonEmpty then "symbol-info-nonempty" else "symbol-info-empty")
EOF

  cat > "$fixture/downstream/src/main/scala/releasefixture/Downstream.scala" <<'EOF'
package releasefixture

val ordinaryResult: Int = Allowed.value()
val macroResult: String = MacroApi.symbolInfoSummary[List[Int]]
EOF

  cat > "$fixture/direct-negative/src/main/scala/releasefixture/DirectNegative.scala" <<'EOF'
package releasefixture

def forbidden: Int = Provider.value()
EOF

  cat > "$fixture/marker-without-plugin/src/main/scala/releasefixture/MarkerWithoutPlugin.scala" <<'EOF'
package releasefixture

import io.github.dmytromitin.allowexperimental.allowExperimental

@allowExperimental def mustNotCompile: Int = Provider.value()
EOF

  cat > "$fixture/wrong-plugin/src/main/scala/releasefixture/WrongPlugin.scala" <<'EOF'
package releasefixture

import io.github.dmytromitin.allowexperimental.allowExperimental

@allowExperimental def mustNotCompile: Int = Provider.value()
EOF

  (
    cd "$fixture"
    run_sbt clean allowed/compile downstream/compile recordReleaseResolution
  ) 2>&1 | tee "$evidence/positive.log"

  javap -classpath "$fixture/downstream/target/scala-$lane/classes" -c -p \
    releasefixture.Downstream\$package\$ > "$evidence/downstream-javap.txt"
  grep -Fq 'symbol-info-nonempty' "$evidence/downstream-javap.txt"

  if (cd "$fixture" && run_sbt directNegative/compile) > "$evidence/direct-negative.log" 2>&1; then
    echo "RELEASE CONSUMER FAIL [$lane]: direct experimental reference compiled" >&2
    exit 1
  fi
  grep -Fq 'marked @experimental' "$evidence/direct-negative.log"

  if (cd "$fixture" && run_sbt markerWithoutPlugin/compile) > "$evidence/marker-without-plugin.log" 2>&1; then
    echo "RELEASE CONSUMER FAIL [$lane]: marker without plugin granted authority" >&2
    exit 1
  fi
  grep -Fq 'marked @experimental' "$evidence/marker-without-plugin.log"

  if (cd "$fixture" && run_sbt wrongPlugin/compile) > "$evidence/wrong-plugin.log" 2>&1; then
    echo "RELEASE CONSUMER FAIL [$lane]: wrong exact-lane plugin granted authority" >&2
    exit 1
  fi

  printf '%s\n' \
    "RELEASE_CONSUMER_$lane=PASS" \
    'ORDINARY_ALLOW_POSITIVE=PASS' \
    'PRIVATE_SYMBOL_INFO_MACRO_POSITIVE=PASS' \
    'UNMARKED_DIRECT_EXPERIMENTAL_USE_REJECTED=YES' \
    'MARKER_WITHOUT_PLUGIN_PERMISSION_GRANTED=NO' \
    'WRONG_LANE_PLUGIN_PERMISSION_GRANTED=NO' \
    'DOWNSTREAM_ALLOW_ARTIFACTS_USED=NO' \
    'GLOBAL_EXPERIMENTAL_USED=NO' \
    > "$evidence/summary.txt"
}

create_consumer 3.3.8 3.8.4
create_consumer 3.8.4 3.9.0
create_consumer 3.9.0 3.8.4

find "$EVIDENCE_ROOT" -path '*/work' -prune -o -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > "$EVIDENCE_ROOT/evidence.sha256"
echo 'ALLOW_EXPERIMENTAL_RELEASE_CONSUMERS_ALL_EXACT_LANES=PASS'
