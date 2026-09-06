ThisBuild / organization := "com.github.dmytromitin"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := VerificationLane.defaultVersion
ThisBuild / crossScalaVersions := VerificationLane.versions
ThisBuild / publish / skip := true
ThisBuild / organizationName := "Dmytro Mitin"
ThisBuild / organizationHomepage := Some(url("https://github.com/DmytroMitin"))
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / publishMavenStyle := true
ThisBuild / Compile / packageSrc / publishArtifact := true
ThisBuild / Compile / packageDoc / publishArtifact := true
ThisBuild / Test / publishArtifact := false
ThisBuild / description := "Implementation-scoped access to selected experimental Scala 3 APIs."
ThisBuild / homepage := Some(url("https://github.com/DmytroMitin/allow-experimental"))
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/DmytroMitin/allow-experimental"),
    "scm:git:https://github.com/DmytroMitin/allow-experimental.git",
    Some("scm:git:ssh://git@github.com:DmytroMitin/allow-experimental.git")
  )
)
ThisBuild / developers := List(
  Developer(
    "dmytromitin",
    "Dmytro Mitin",
    "",
    url("https://github.com/DmytroMitin")
  )
)
ThisBuild / pomIncludeRepository := (_ => false)

lazy val verifyPermissionFixtures = taskKey[Unit]("Run the permission fixture matrix on the active exact Scala lane")
lazy val verifyPermissionScope = taskKey[Unit]("Run the implementation-body permission matrix on the active exact Scala lane")
lazy val verifyMacroImplementation = taskKey[Unit]("Run the real Symbol.info macro boundary matrix on the active exact Scala lane")
lazy val verifyPhaseObserverCoexistence = taskKey[Unit]("Run the generic second-plugin coexistence gate on exact Scala 3.9.0")
lazy val verifyMacroParadiseCoexistence39 = taskKey[Unit]("Run real Macro-Paradise coexistence on exact Scala 3.9.0")
lazy val verifyMacroParadiseCoexistence = taskKey[Unit]("Run real Macro-Paradise coexistence on exact Scala 3.3.8 or 3.8.4")
lazy val verifySameJvmLifecycle39 = taskKey[Unit]("Run the exact Scala 3.9.0 same-JVM repeated-run lifecycle gate")
lazy val verifyZincLifecycle39 = taskKey[Unit]("Verify the complete exact Scala 3.9.0 Zinc lifecycle evidence")
lazy val verifySameJvmLifecycleOlder = taskKey[Unit]("Run the same-JVM repeated-run lifecycle gate on exact Scala 3.3.8 or 3.8.4")
lazy val verifyZincLifecycle = taskKey[Unit]("Verify the complete exact Scala 3.3.8/3.8.4 Zinc lifecycle evidence")
lazy val verifyPersistentBuildLifecycle = taskKey[Unit]("Verify persistent sbt and real BSP lifecycle evidence on all exact Scala lanes")
lazy val verifyQuasiquotesIntegration = taskKey[Unit]("Verify the disposable pinned Quasiquotes Symbol.info integration")
lazy val verifyLane = taskKey[Unit]("Check exact compiler, artifact and output-lane identities")

lazy val rootLicense = file("LICENSE")

lazy val publishableSettings = Seq(
  publish / skip := false,
  Compile / packageBin / mappings += rootLicense -> "META-INF/LICENSE",
  Compile / packageSrc / mappings += rootLicense -> "META-INF/LICENSE",
  Compile / packageDoc / mappings += rootLicense -> "META-INF/LICENSE"
)

// Separate classes, Zinc analysis, streams, jars and fixtures by exact version.
// Cleaning one lane must not delete the other lane's products or evidence.
lazy val laneSettings = Seq(
  target := baseDirectory.value / "target" / s"scala-${scalaVersion.value}",
  crossTarget := target.value,
  Compile / packageOptions += Package.ManifestAttributes(
    VerificationLane.manifestKey -> scalaVersion.value
  )
)

lazy val annotation = project
  .in(file("annotation"))
  .settings(laneSettings)
  .settings(publishableSettings)
  .settings(
    name := "allow-experimental-annotation",
    crossVersion := CrossVersion.binary,
  )

lazy val plugin = project
  .in(file("plugin"))
  .settings(laneSettings)
  .settings(publishableSettings)
  .settings(
    name := "allow-experimental-plugin",
    crossVersion := CrossVersion.full,
    Compile / sourceGenerators += Def.task {
      val output = (Compile / sourceManaged).value / "io" / "github" / "dmytromitin" /
        "allowexperimental" / "plugin" / "ExactCompilerVersion.scala"
      val expected = scalaVersion.value
      IO.write(output,
        s"""package io.github.dmytromitin.allowexperimental.plugin

import dotty.tools.dotc.config.Properties

private[plugin] object ExactCompilerVersion:
  private val Expected = "$expected"

  def validate(): Unit =
    require(
      Properties.versionNumberString == Expected,
      s"allow-experimental plugin built for exact Scala $$Expected cannot run on Scala $${Properties.versionNumberString}"
    )
""")
      Seq(output)
    }.taskValue,
    Compile / unmanagedSourceDirectories += {
      val adapter = scalaVersion.value match {
        case "3.3.8" => "scala-3.3.8"
        case "3.8.4" => "scala-3.8.4"
        case "3.9.0" => "scala-3.9.0"
        case other => sys.error(s"no compiler adapter for Scala $other")
      }
      baseDirectory.value / "src" / "main" / adapter
    },
    libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Provided
  )

lazy val root = project
  .in(file("."))
  .aggregate(annotation, plugin)
  .settings(laneSettings)
  .settings(
    name := "allow-experimental-root",
    publish / skip := true,
    verifyLane := VerificationLane.verifyStructure(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      Seq(target.value, (annotation / target).value, (plugin / target).value),
      streams.value.log
    ),
    verifyPermissionScope := M1Verifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyMacroImplementation := M3Verifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyPhaseObserverCoexistence := M4AVerifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyMacroParadiseCoexistence39 := M4BVerifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyMacroParadiseCoexistence := M4CVerifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifySameJvmLifecycle39 := M5AVerifier.verifySameJvm(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyZincLifecycle39 := M5AVerifier.verifyFinal(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifySameJvmLifecycleOlder := M5AVerifier.verifySameJvmOlder(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyZincLifecycle := M5BVerifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      streams.value.log
    ),
    verifyPersistentBuildLifecycle := M5CVerifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      streams.value.log
    ),
    verifyQuasiquotesIntegration := M6Verifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyPermissionFixtures := M0Verifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    )
  )
