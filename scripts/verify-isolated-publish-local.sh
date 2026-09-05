#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo 'Usage: bash scripts/verify-isolated-publish-local.sh' >&2
  exit 2
fi

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
VERSION=0.1.0-SNAPSHOT
OUTER="$ROOT/target/isolated-publish-local"
WORK="$OUTER/consumers"
STATE="$OUTER/tool-state"
EVIDENCE="$OUTER/evidence"
IVY_LOCAL="$STATE/ivy/local"
REPOSITORIES="$STATE/repositories"
ORGANIZATION=com.github.dmytromitin
ANNOTATION_MODULE=allow-experimental-annotation_3

rm -rf -- "$WORK" "$EVIDENCE" "$IVY_LOCAL"
mkdir -p -- "$WORK" "$STATE" "$EVIDENCE"

cat > "$REPOSITORIES" <<'EOF'
[repositories]
  local
  maven-central
EOF

export COURSIER_CACHE="$STATE/coursier-cache"
SBT=(sbt -batch -Dsbt.supershell=false -Dsbt.server.autostart=false
  "-Dsbt.global.base=$STATE/global"
  "-Dsbt.boot.directory=$STATE/boot"
  "-Dsbt.ivy.home=$STATE/ivy"
  -Dsbt.override.build.repos=true
  "-Dsbt.repository.config=$REPOSITORIES")
isolated_sbt_opts="${SBT_OPTS:-} -Dsbt.global.base=$STATE/global -Dsbt.boot.directory=$STATE/boot -Dsbt.ivy.home=$STATE/ivy -Dsbt.override.build.repos=true -Dsbt.repository.config=$REPOSITORIES"

run_sbt() {
  SBT_OPTS="$isolated_sbt_opts" "${SBT[@]}" "$@"
}

(
  cd "$ROOT"
  run_sbt \
    '++3.3.8!' 'annotation/publishLocal' 'plugin/publishLocal' \
    '++3.8.4!' 'plugin/publishLocal' \
    '++3.9.0!' 'plugin/publishLocal'
) 2>&1 | tee "$EVIDENCE/publish.log"

annotation_dir="$IVY_LOCAL/$ORGANIZATION/$ANNOTATION_MODULE/$VERSION"
annotation_base="$ANNOTATION_MODULE"
for artifact in \
    "$annotation_dir/jars/$annotation_base.jar" \
    "$annotation_dir/srcs/$annotation_base-sources.jar" \
    "$annotation_dir/docs/$annotation_base-javadoc.jar" \
    "$annotation_dir/poms/$annotation_base.pom" \
    "$annotation_dir/ivys/ivy.xml"; do
  test -s "$artifact"
done

for lane in 3.3.8 3.8.4 3.9.0; do
  module="allow-experimental-plugin_$lane"
  dir="$IVY_LOCAL/$ORGANIZATION/$module/$VERSION"
  for artifact in \
      "$dir/jars/$module.jar" \
      "$dir/srcs/$module-sources.jar" \
      "$dir/docs/$module-javadoc.jar" \
      "$dir/poms/$module.pom" \
      "$dir/ivys/ivy.xml"; do
    test -s "$artifact"
  done
done

if find "$IVY_LOCAL" -type f -name '*.asc' -print -quit | grep -q .; then
  echo 'ISOLATED PUBLISHLOCAL FAIL: signatures must not be fabricated during the local smoke test' >&2
  exit 1
fi

annotation_jar="$annotation_dir/jars/$annotation_base.jar"
unzip -p "$annotation_jar" META-INF/MANIFEST.MF | tr -d '\r' > "$EVIDENCE/annotation-manifest.txt"
grep -Fxq 'Allow-Experimental-Scala-Version: 3.3.8' "$EVIDENCE/annotation-manifest.txt"
jar tf "$annotation_jar" > "$EVIDENCE/annotation-jar.txt"
grep -Fxq 'io/github/dmytromitin/allowexperimental/allowExperimental.class' "$EVIDENCE/annotation-jar.txt"
grep -Fxq 'io/github/dmytromitin/allowexperimental/allowExperimental.tasty' "$EVIDENCE/annotation-jar.txt"
if rg -n 'dotty/tools/dotc|m[0-9]-fixtures|Verifier|plugin\.properties' "$EVIDENCE/annotation-jar.txt"; then
  echo 'ISOLATED PUBLISHLOCAL FAIL: annotation jar contains non-annotation implementation' >&2
  exit 1
fi
grep -Fxq 'META-INF/LICENSE' "$EVIDENCE/annotation-jar.txt"

for archive in \
    "$annotation_dir/srcs/$annotation_base-sources.jar" \
    "$annotation_dir/docs/$annotation_base-javadoc.jar"; do
  jar tf "$archive" | grep -Fxq 'META-INF/LICENSE'
done

for lane in 3.3.8 3.8.4 3.9.0; do
  module="allow-experimental-plugin_$lane"
  dir="$IVY_LOCAL/$ORGANIZATION/$module/$VERSION"
  jar_file="$dir/jars/$module.jar"
  pom_file="$dir/poms/$module.pom"
  jar tf "$jar_file" > "$EVIDENCE/plugin-$lane-jar.txt"
  unzip -p "$jar_file" META-INF/MANIFEST.MF | tr -d '\r' > "$EVIDENCE/plugin-$lane-manifest.txt"
  grep -Fxq 'plugin.properties' "$EVIDENCE/plugin-$lane-jar.txt"
  grep -Fxq 'META-INF/LICENSE' "$EVIDENCE/plugin-$lane-jar.txt"
  grep -Fq 'io/github/dmytromitin/allowexperimental/plugin/ExactCompilerVersion.class' "$EVIDENCE/plugin-$lane-jar.txt"
  grep -Fxq "Allow-Experimental-Scala-Version: $lane" "$EVIDENCE/plugin-$lane-manifest.txt"
  if rg -n 'dotty/tools/dotc|m[0-9]-fixtures|Verifier|macroparadise|quasiquotes' "$EVIDENCE/plugin-$lane-jar.txt"; then
    echo "ISOLATED PUBLISHLOCAL FAIL: plugin $lane jar contains compiler, verifier, fixture, or peer content" >&2
    exit 1
  fi
  for archive in "$dir/srcs/$module-sources.jar" "$dir/docs/$module-javadoc.jar"; do
    jar tf "$archive" | grep -Fxq 'META-INF/LICENSE'
  done
  javap -classpath "$jar_file" -c -p \
    io.github.dmytromitin.allowexperimental.plugin.ExactCompilerVersion\$ \
    > "$EVIDENCE/plugin-$lane-exact-identity.txt"
  grep -Fq "$lane" "$EVIDENCE/plugin-$lane-exact-identity.txt"
  grep -Fq '<scope>provided</scope>' "$pom_file"
done

for pom in "$annotation_dir/poms/$annotation_base.pom" \
    "$IVY_LOCAL/$ORGANIZATION"/allow-experimental-plugin_*/"$VERSION"/poms/*.pom; do
  grep -Fq '<name>' "$pom"
  grep -Fq '<description>' "$pom"
  grep -Fq '<url>https://github.com/DmytroMitin/allow-experimental</url>' "$pom"
  grep -Fq '<scm>' "$pom"
  grep -Fq '<name>Apache-2.0</name>' "$pom"
  grep -Fq '<url>https://www.apache.org/licenses/LICENSE-2.0</url>' "$pom"
  grep -Fq '<developer>' "$pom"
  if grep -Fq '<repositories>' "$pom"; then
    echo "ISOLATED PUBLISHLOCAL FAIL: repository metadata present in $pom" >&2
    exit 1
  fi
done

create_consumer() {
  local lane=$1
  local wrong_lane=$2
  local fixture="$WORK/scala-$lane"
  local evidence="$fixture/evidence"
  mkdir -p -- \
    "$fixture/project" \
    "$fixture/provider/src/main/scala/installfixture" \
    "$fixture/allowed/src/main/scala/installfixture" \
    "$fixture/downstream/src/main/scala/installfixture" \
    "$fixture/direct-negative/src/main/scala/installfixture" \
    "$fixture/wrong-plugin/src/main/scala/installfixture" \
    "$evidence"

  printf '%s\n' 'sbt.version=1.11.7' > "$fixture/project/build.properties"
  cat > "$fixture/build.sbt" <<EOF
import sbt._
import Keys._

ThisBuild / scalaVersion := "$lane"
ThisBuild / organization := "install.fixture"
ThisBuild / version := "0.0.0-task-local"
ThisBuild / publish / skip := true

lazy val recordIsolatedResolution = taskKey[Unit]("Record isolated publishLocal coordinate resolution")

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

lazy val wrongPlugin = project.in(file("wrong-plugin"))
  .dependsOn(provider)
  .settings(
    libraryDependencies += "$ORGANIZATION" %% "allow-experimental-annotation" % "$VERSION" % Provided,
    libraryDependencies += compilerPlugin("$ORGANIZATION" % "allow-experimental-plugin_$wrong_lane" % "$VERSION")
  )

lazy val root = project.in(file("."))
  .aggregate(provider, allowed, downstream)
  .settings(
    recordIsolatedResolution := {
      val allowedCp = (allowed / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      val allowedOptions = (allowed / Compile / scalacOptions).value
      val downstreamCp = (downstream / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      require(allowedCp.contains("$annotation_dir/jars/$annotation_base.jar"), allowedCp.mkString("\\n"))
      require(allowedOptions.exists(_.contains("$IVY_LOCAL/$ORGANIZATION/allow-experimental-plugin_$lane/$VERSION/jars/allow-experimental-plugin_$lane.jar")), allowedOptions.mkString("\\n"))
      require(!downstreamCp.exists(_.contains("allow-experimental-annotation")), downstreamCp.mkString("\\n"))
      require(!downstreamCp.exists(_.contains("allow-experimental-plugin")), downstreamCp.mkString("\\n"))
      IO.writeLines(file("$evidence/resolution.txt"),
        Seq("scalaVersion=$lane", "annotation=$ANNOTATION_MODULE", "plugin=allow-experimental-plugin_$lane") ++
          allowedCp.map("allowedClasspath=" + _) ++ allowedOptions.map("allowedScalacOption=" + _) ++
          downstreamCp.map("downstreamClasspath=" + _))
    }
  )
EOF

  cat > "$fixture/provider/src/main/scala/installfixture/Provider.scala" <<'EOF'
package installfixture

import scala.annotation.experimental

object Provider:
  @experimental def value(): Int = 1
EOF

  cat > "$fixture/allowed/src/main/scala/installfixture/Allowed.scala" <<'EOF'
package installfixture

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

  cat > "$fixture/downstream/src/main/scala/installfixture/Downstream.scala" <<'EOF'
package installfixture

val ordinaryResult: Int = Allowed.value()
val macroResult: String = MacroApi.symbolInfoSummary[List[Int]]
EOF

  cat > "$fixture/direct-negative/src/main/scala/installfixture/DirectNegative.scala" <<'EOF'
package installfixture

def forbidden: Int = Provider.value()
EOF

  cat > "$fixture/wrong-plugin/src/main/scala/installfixture/WrongPlugin.scala" <<'EOF'
package installfixture

import io.github.dmytromitin.allowexperimental.allowExperimental

@allowExperimental def mustNotCompile: Int = Provider.value()
EOF

  (
    cd "$fixture"
    run_sbt clean allowed/compile downstream/compile recordIsolatedResolution
  ) 2>&1 | tee "$evidence/positive.log"

  javap -classpath "$fixture/downstream/target/scala-$lane/classes" -c -p \
    installfixture.Downstream\$package\$ > "$evidence/downstream-javap.txt"
  grep -Fq 'symbol-info-nonempty' "$evidence/downstream-javap.txt"

  if (
    cd "$fixture"
    run_sbt directNegative/compile
  ) > "$evidence/direct-negative.log" 2>&1; then
    echo "ISOLATED PUBLISHLOCAL FAIL [$lane]: direct experimental reference compiled" >&2
    exit 1
  fi
  grep -Fq 'marked @experimental' "$evidence/direct-negative.log"

  if (
    cd "$fixture"
    run_sbt wrongPlugin/compile
  ) > "$evidence/wrong-plugin.log" 2>&1; then
    echo "ISOLATED PUBLISHLOCAL FAIL [$lane]: mismatched exact plugin granted permission" >&2
    exit 1
  fi
}

create_consumer 3.3.8 3.8.4
create_consumer 3.8.4 3.9.0
create_consumer 3.9.0 3.8.4

find "$IVY_LOCAL" -type f -print | LC_ALL=C sort > "$EVIDENCE/repository-manifest.txt"
find "$IVY_LOCAL" -type f ! -name '*.md5' ! -name '*.sha1' -print0 | LC_ALL=C sort -z | \
  xargs -0 sha256sum > "$EVIDENCE/repository.sha256"
find "$WORK" -path '*/evidence/*' -type f -print0 | LC_ALL=C sort -z | \
  xargs -0 sha256sum > "$EVIDENCE/consumers.sha256"

printf '%s\n' \
  'PUBLISHLOCAL_ISOLATED_SMOKE=PASS' \
  'ANNOTATION_PUBLICATION_POLICY=BINARY_CROSS_SCALA3_OLDEST_BUILD' \
  'PLUGIN_PUBLICATION_POLICY=FULL_CROSS_EXACT' \
  'SOURCE_JARS=PASS' \
  'DOC_JARS=PASS' \
  'PLUGIN_DESCRIPTOR_ALL_EXACT_LANES=PASS' \
  'COMPILER_DEPENDENCY_SCOPE=PASS' \
  'ARTIFACT_HYGIENE=PASS' \
  'COORDINATE_CONSUMER_3_3_8=PASS' \
  'COORDINATE_CONSUMER_3_8_4=PASS' \
  'COORDINATE_CONSUMER_3_9_0=PASS' \
  'MACRO_COORDINATE_CONSUMER_ALL=PASS' \
  'DOWNSTREAM_WITHOUT_ALLOW_ARTIFACTS=PASS' \
  'LICENSE_ARTIFACT_POLICY=PASS' \
  'REMOTE_PUBLISH_CONFIGURED=NO' \
  'REMOTE_PUBLICATION_PERFORMED=NO' \
  'CENTRAL_TOKEN_READ_OR_USED=NO' \
  > "$EVIDENCE/summary.txt"

printf '%s\n' 'Isolated publishLocal and coordinate consumers: PASS'
