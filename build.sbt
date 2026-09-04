ThisBuild / organization := "io.github.dmytromitin"
ThisBuild / version := "0.1.0-M0-SNAPSHOT"
ThisBuild / scalaVersion := VerificationLane.defaultVersion
ThisBuild / crossScalaVersions := VerificationLane.versions
ThisBuild / publish / skip := true

lazy val verifyM0 = taskKey[Unit]("Run the retained M0 matrix on the active exact Scala lane")
lazy val verifyM1 = taskKey[Unit]("Run the M1 implementation-body matrix on the active exact Scala lane")
lazy val verifyM3 = taskKey[Unit]("Run the real Symbol.info macro boundary matrix on the active exact Scala lane")
lazy val verifyM4A = taskKey[Unit]("Run the generic second-plugin coexistence gate on exact Scala 3.9.0")
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
    verifyM0 := M0Verifier.verify(
      baseDirectory.value,
      scalaVersion.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    )
  )
