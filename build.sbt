ThisBuild / organization := "io.github.dmytromitin"
ThisBuild / version := "0.1.0-M0-SNAPSHOT"
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / publish / skip := true

lazy val verifyM0 = taskKey[Unit]("Run the retained Scala 3.9.0 M0 compiler-plugin matrix")
lazy val verifyM1 = taskKey[Unit]("Run the Scala 3.9.0 implementation-body reference matrix")

lazy val annotation = project
  .in(file("annotation"))
  .settings(
    name := "allow-experimental-annotation"
  )

lazy val plugin = project
  .in(file("plugin"))
  .settings(
    name := "allow-experimental-plugin",
    libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Provided
  )

lazy val root = project
  .in(file("."))
  .aggregate(annotation, plugin)
  .settings(
    name := "allow-experimental-root",
    verifyM1 := M1Verifier.verify(
      baseDirectory.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    ),
    verifyM0 := M0Verifier.verify(
      baseDirectory.value,
      (annotation / Compile / packageBin).value,
      (plugin / Compile / packageBin).value,
      (plugin / Compile / dependencyClasspath).value.map(_.data),
      streams.value.log
    )
  )
