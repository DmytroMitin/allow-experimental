#!/usr/bin/env bash
set -euo pipefail

if (( $# != 0 )); then
  echo 'Usage: bash scripts/verify-m7a-isolated-publication.sh' >&2
  exit 2
fi

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
VERSION=0.1.0-M7A-LOCAL
REPOSITORY="$ROOT/target/m7a-local-repository"
WORK="$ROOT/target/m7a-consumers"
STATE="$ROOT/target/m7a-tool-state"
EVIDENCE="$ROOT/target/m7a-evidence"
REPOSITORIES="$STATE/repositories"
ORGANIZATION=io.github.dmytromitin
ANNOTATION_MODULE=allow-experimental-annotation_3

rm -rf -- "$REPOSITORY" "$WORK" "$EVIDENCE"
mkdir -p -- "$REPOSITORY" "$WORK" "$STATE" "$EVIDENCE"

cat > "$REPOSITORIES" <<EOF
[repositories]
  m7a-isolated: file:$REPOSITORY/
  maven-central
EOF

export COURSIER_CACHE="$STATE/coursier-cache"
SBT=(sbt -batch -Dsbt.supershell=false -Dsbt.server.autostart=false
  "-Dsbt.global.base=$STATE/global"
  "-Dsbt.boot.directory=$STATE/boot"
  "-Dsbt.ivy.home=$STATE/ivy"
  -Dsbt.override.build.repos=true
  "-Dsbt.repository.config=$REPOSITORIES")
m7a_sbt_opts="${SBT_OPTS:-} -Dsbt.global.base=$STATE/global -Dsbt.boot.directory=$STATE/boot -Dsbt.ivy.home=$STATE/ivy -Dsbt.override.build.repos=true -Dsbt.repository.config=$REPOSITORIES"

run_sbt() {
  SBT_OPTS="$m7a_sbt_opts" "${SBT[@]}" "$@"
}

publish_settings=(
  "set ThisBuild / version := \"$VERSION\""
  'set ThisBuild / versionScheme := Some("semver-spec")'
  'set ThisBuild / publishMavenStyle := true'
  'set ThisBuild / pomIncludeRepository := { _ => false }'
  'set ThisBuild / homepage := Some(url("https://github.com/DmytroMitin/allow-experimental"))'
  'set ThisBuild / organizationName := "io.github.dmytromitin"'
  'set ThisBuild / organizationHomepage := Some(url("https://github.com/DmytroMitin"))'
  'set ThisBuild / description := "A bounded Scala 3 compiler plugin for implementation-scoped access to selected experimental APIs."'
  'set ThisBuild / scmInfo := Some(ScmInfo(url("https://github.com/DmytroMitin/allow-experimental"), "scm:git:git@github.com:DmytroMitin/allow-experimental.git"))'
  'set ThisBuild / publishTo := Some(Resolver.file("m7a-output", file("'"$REPOSITORY"'"))(Resolver.mavenStylePatterns))'
  'set annotation / crossVersion := CrossVersion.binary'
  'set plugin / crossVersion := CrossVersion.full'
  'set annotation / publish / skip := false'
  'set plugin / publish / skip := false'
  'set root / publish / skip := true'
)

(
  cd "$ROOT"
  run_sbt "${publish_settings[@]}" \
    '++3.3.8' 'annotation/publish' 'plugin/publish' \
    '++3.8.4' 'plugin/publish' \
    '++3.9.0' 'plugin/publish'
) 2>&1 | tee "$EVIDENCE/publish.log"

annotation_dir="$REPOSITORY/io/github/dmytromitin/$ANNOTATION_MODULE/$VERSION"
annotation_base="$ANNOTATION_MODULE-$VERSION"
for suffix in .jar -sources.jar -javadoc.jar .pom; do
  test -s "$annotation_dir/$annotation_base$suffix"
done

for lane in 3.3.8 3.8.4 3.9.0; do
  module="allow-experimental-plugin_$lane"
  dir="$REPOSITORY/io/github/dmytromitin/$module/$VERSION"
  base="$module-$VERSION"
  for suffix in .jar -sources.jar -javadoc.jar .pom; do
    test -s "$dir/$base$suffix"
  done
done

if find "$REPOSITORY" -type f -name '*.asc' -print -quit | grep -q .; then
  echo 'M7A FAIL: signatures must not be fabricated during the isolated dry run' >&2
  exit 1
fi

annotation_jar="$annotation_dir/$annotation_base.jar"
unzip -p "$annotation_jar" META-INF/MANIFEST.MF | tr -d '\r' > "$EVIDENCE/annotation-manifest.txt"
grep -Fxq 'Allow-Experimental-Scala-Version: 3.3.8' "$EVIDENCE/annotation-manifest.txt"
jar tf "$annotation_jar" > "$EVIDENCE/annotation-jar.txt"
grep -Fxq 'io/github/dmytromitin/allowexperimental/allowExperimental.class' "$EVIDENCE/annotation-jar.txt"
grep -Fxq 'io/github/dmytromitin/allowexperimental/allowExperimental.tasty' "$EVIDENCE/annotation-jar.txt"
if rg -n 'dotty/tools/dotc|m[0-9]-fixtures|Verifier|plugin\.properties' "$EVIDENCE/annotation-jar.txt"; then
  echo 'M7A FAIL: annotation jar contains non-annotation implementation' >&2
  exit 1
fi

for lane in 3.3.8 3.8.4 3.9.0; do
  module="allow-experimental-plugin_$lane"
  dir="$REPOSITORY/io/github/dmytromitin/$module/$VERSION"
  base="$module-$VERSION"
  jar_file="$dir/$base.jar"
  pom_file="$dir/$base.pom"
  jar tf "$jar_file" > "$EVIDENCE/plugin-$lane-jar.txt"
  unzip -p "$jar_file" META-INF/MANIFEST.MF | tr -d '\r' > "$EVIDENCE/plugin-$lane-manifest.txt"
  grep -Fxq 'plugin.properties' "$EVIDENCE/plugin-$lane-jar.txt"
  grep -Fq 'io/github/dmytromitin/allowexperimental/plugin/ExactCompilerVersion.class' "$EVIDENCE/plugin-$lane-jar.txt"
  grep -Fxq "Allow-Experimental-Scala-Version: $lane" "$EVIDENCE/plugin-$lane-manifest.txt"
  if rg -n 'dotty/tools/dotc|m[0-9]-fixtures|Verifier|macroparadise|quasiquotes' "$EVIDENCE/plugin-$lane-jar.txt"; then
    echo "M7A FAIL: plugin $lane jar contains compiler, verifier, fixture, or peer content" >&2
    exit 1
  fi
  javap -classpath "$jar_file" -c -p \
    io.github.dmytromitin.allowexperimental.plugin.ExactCompilerVersion\$ \
    > "$EVIDENCE/plugin-$lane-exact-identity.txt"
  grep -Fq "$lane" "$EVIDENCE/plugin-$lane-exact-identity.txt"
  grep -Fq '<scope>provided</scope>' "$pom_file"
done

for pom in "$annotation_dir/$annotation_base.pom" \
    "$REPOSITORY"/io/github/dmytromitin/allow-experimental-plugin_*/"$VERSION"/*.pom; do
  grep -Fq '<name>' "$pom"
  grep -Fq '<description>' "$pom"
  grep -Fq '<url>https://github.com/DmytroMitin/allow-experimental</url>' "$pom"
  grep -Fq '<scm>' "$pom"
  if grep -Eq '<licenses>|<developers>|<repositories>' "$pom"; then
    echo "M7A FAIL: unapproved human metadata or repositories present in $pom" >&2
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
    "$fixture/provider/src/main/scala/m7afixture" \
    "$fixture/allowed/src/main/scala/m7afixture" \
    "$fixture/downstream/src/main/scala/m7afixture" \
    "$fixture/direct-negative/src/main/scala/m7afixture" \
    "$fixture/wrong-plugin/src/main/scala/m7afixture" \
    "$evidence"

  printf '%s\n' 'sbt.version=1.11.7' > "$fixture/project/build.properties"
  cat > "$fixture/build.sbt" <<EOF
import sbt._
import Keys._

ThisBuild / scalaVersion := "$lane"
ThisBuild / organization := "m7a.fixture"
ThisBuild / version := "0.0.0-task-local"
ThisBuild / publish / skip := true
ThisBuild / resolvers := Seq("m7a-isolated" at "file:$REPOSITORY/")

lazy val recordM7AResolution = taskKey[Unit]("Record isolated M7A coordinate resolution")

lazy val provider = project.in(file("provider"))

lazy val allowed = project.in(file("allowed"))
  .dependsOn(provider)
  .settings(
    libraryDependencies += "$ORGANIZATION" %% "allow-experimental-annotation" % "$VERSION" % Provided,
    libraryDependencies += compilerPlugin("$ORGANIZATION" %% "allow-experimental-plugin" % "$VERSION" cross CrossVersion.full)
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
    recordM7AResolution := {
      val allowedCp = (allowed / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      val allowedOptions = (allowed / Compile / scalacOptions).value
      val downstreamCp = (downstream / Compile / dependencyClasspath).value.map(_.data.getCanonicalPath)
      require(allowedCp.exists(_.contains("allow-experimental-annotation_3-$VERSION.jar")), allowedCp.mkString("\\n"))
      require(allowedOptions.exists(_.contains("allow-experimental-plugin_$lane-$VERSION.jar")), allowedOptions.mkString("\\n"))
      require(!downstreamCp.exists(_.contains("allow-experimental-annotation")), downstreamCp.mkString("\\n"))
      require(!downstreamCp.exists(_.contains("allow-experimental-plugin")), downstreamCp.mkString("\\n"))
      IO.writeLines(file("$evidence/resolution.txt"),
        Seq("scalaVersion=$lane", "annotation=$ANNOTATION_MODULE", "plugin=allow-experimental-plugin_$lane") ++
          allowedCp.map("allowedClasspath=" + _) ++ allowedOptions.map("allowedScalacOption=" + _) ++
          downstreamCp.map("downstreamClasspath=" + _))
    }
  )
EOF

  cat > "$fixture/provider/src/main/scala/m7afixture/Provider.scala" <<'EOF'
package m7afixture

import scala.annotation.experimental

object Provider:
  @experimental def value(): Int = 1
EOF

  cat > "$fixture/allowed/src/main/scala/m7afixture/Allowed.scala" <<'EOF'
package m7afixture

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

  cat > "$fixture/downstream/src/main/scala/m7afixture/Downstream.scala" <<'EOF'
package m7afixture

val ordinaryResult: Int = Allowed.value()
val macroResult: String = MacroApi.symbolInfoSummary[List[Int]]
EOF

  cat > "$fixture/direct-negative/src/main/scala/m7afixture/DirectNegative.scala" <<'EOF'
package m7afixture

def forbidden: Int = Provider.value()
EOF

  cat > "$fixture/wrong-plugin/src/main/scala/m7afixture/WrongPlugin.scala" <<'EOF'
package m7afixture

import io.github.dmytromitin.allowexperimental.allowExperimental

@allowExperimental def mustNotCompile: Int = Provider.value()
EOF

  (
    cd "$fixture"
    run_sbt clean allowed/compile downstream/compile recordM7AResolution
  ) 2>&1 | tee "$evidence/positive.log"

  javap -classpath "$fixture/downstream/target/scala-$lane/classes" -c -p \
    m7afixture.Downstream\$package\$ > "$evidence/downstream-javap.txt"
  grep -Fq 'symbol-info-nonempty' "$evidence/downstream-javap.txt"

  if (
    cd "$fixture"
    run_sbt directNegative/compile
  ) > "$evidence/direct-negative.log" 2>&1; then
    echo "M7A FAIL [$lane]: direct experimental reference compiled" >&2
    exit 1
  fi
  grep -Fq 'marked @experimental' "$evidence/direct-negative.log"

  if (
    cd "$fixture"
    run_sbt wrongPlugin/compile
  ) > "$evidence/wrong-plugin.log" 2>&1; then
    echo "M7A FAIL [$lane]: mismatched exact plugin granted permission" >&2
    exit 1
  fi
}

create_consumer 3.3.8 3.8.4
create_consumer 3.8.4 3.9.0
create_consumer 3.9.0 3.8.4

find "$REPOSITORY" -type f -print | LC_ALL=C sort > "$EVIDENCE/repository-manifest.txt"
find "$REPOSITORY" -type f ! -name '*.md5' ! -name '*.sha1' -print0 | LC_ALL=C sort -z | \
  xargs -0 sha256sum > "$EVIDENCE/repository.sha256"
find "$WORK" -path '*/evidence/*' -type f -print0 | LC_ALL=C sort -z | \
  xargs -0 sha256sum > "$EVIDENCE/consumers.sha256"

printf '%s\n' \
  'ISOLATED_FILESYSTEM_MAVEN_DRY_RUN=PASS' \
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
  'RELEASE_CREDENTIALS_TOUCHED=NO' \
  'REMOTE_PUBLISH_WORKFLOW_ADDED=NO' \
  > "$EVIDENCE/summary.txt"

printf '%s\n' 'M7A isolated filesystem Maven publication and coordinate consumers: PASS'
