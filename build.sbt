ThisBuild / organization := "io.github.dmytromitin"
ThisBuild / version := "0.1.0-M0-SNAPSHOT"
ThisBuild / scalaVersion := VerificationLane.defaultVersion
ThisBuild / crossScalaVersions := VerificationLane.versions
ThisBuild / publish / skip := true

lazy val verifyM0 = taskKey[Unit]("Run the retained M0 matrix on the active exact Scala lane")
lazy val verifyM1 = taskKey[Unit]("Run the M1 implementation-body matrix on the active exact Scala lane")
lazy val verifyM3 = taskKey[Unit]("Run the real Symbol.info macro boundary matrix on the active exact Scala lane")
lazy val verifyM4A = taskKey[Unit]("Run the generic second-plugin coexistence gate on exact Scala 3.9.0")
lazy val verifyM4B = taskKey[Unit]("Run real Macro-Paradise coexistence on exact Scala 3.9.0")
lazy val verifyM4C = taskKey[Unit]("Run real Macro-Paradise coexistence on exact Scala 3.3.8 or 3.8.4")
lazy val verifyM5ASameJvm = taskKey[Unit]("Run the exact Scala 3.9.0 same-JVM repeated-run lifecycle gate")
lazy val verifyM5A = taskKey[Unit]("Verify the complete exact Scala 3.9.0 M5A lifecycle evidence")
lazy val verifyM5BSameJvm = taskKey[Unit]("Run the same-JVM repeated-run lifecycle gate on exact Scala 3.3.8 or 3.8.4")
lazy val verifyM5B = taskKey[Unit]("Verify the complete exact Scala 3.3.8/3.8.4 M5B lifecycle evidence")
lazy val verifyLane = taskKey[Unit]("Check exact compiler, artifact and output-lane identities")

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
  .settings(
    name := "allow-experimental-annotation"
  )

lazy val plugin = project
  .in(file("plugin"))
  .settings(laneSettings)
  .settings(
    name := "allow-experimental-plugin",
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
    verifyLane := VerificationLane.verifyStructure(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      Seq(target.value, (annotation / target).value, (plugin / target).value),
      streams.value.log
    ),
    verifyM1 := M1Verifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyM3 := M3Verifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyM4A := M4AVerifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyM4B := M4BVerifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyM4C := M4CVerifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyM5ASameJvm := M5AVerifier.verifySameJvm(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyM5A := M5AVerifier.verifyFinal(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyM5BSameJvm := M5AVerifier.verifySameJvmOlder(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyM5B := M5BVerifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      streams.value.log
    ),
    verifyM0 := M0Verifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    )
  )
