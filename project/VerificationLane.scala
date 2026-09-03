import java.io.File
import java.util.jar.JarFile
import sbt._
import scala.sys.process.{Process, ProcessLogger}

/** Build/test boundary only: never used to branch compiler-plugin semantics. */
object VerificationLane {
  val versions = Seq("3.8.4", "3.9.0")
  val defaultVersion = "3.9.0"
  val manifestKey = "Allow-Experimental-Scala-Version"

  private def requireLane(version: String): Unit =
    require(versions.contains(version), s"unqualified Scala lane: $version")

  private def checkCompiler(version: String, cp: Seq[File]): Unit = {
    requireLane(version)
    Seq("scala3-compiler_3", "scala3-library_3", "scala-library").foreach { artifact =>
      val found = cp.filter(_.getName.startsWith(artifact + "-"))
      require(found.size == 1 && found.head.getName == s"$artifact-$version.jar" && found.head.isFile,
        s"wrong $artifact for Scala $version: ${found.mkString(", ")}")
    }
  }

  private def checkArtifact(file: File, version: String): Unit = {
    require(file.isFile, s"missing lane artifact: $file")
    val jar = new JarFile(file)
    try {
      val builtWith = Option(jar.getManifest).flatMap(m => Option(m.getMainAttributes.getValue(manifestKey)))
      require(builtWith.contains(version), s"artifact lane mismatch: $file built with $builtWith, expected $version")
    } finally jar.close()
  }

  def validateInputs(version: String, annotationJar: File, pluginJar: File, cp: Seq[File]): Unit = {
    checkCompiler(version, cp)
    checkArtifact(annotationJar, version)
    checkArtifact(pluginJar, version)
  }

  def workDirectory(root: File, version: String, gate: String): File = {
    requireLane(version)
    require(Set("m0", "m1")(gate), s"unknown verification gate: $gate")
    root / "target" / s"scala-$version" / s"$gate-verification"
  }

  def recordInputs(work: File, version: String, annotationJar: File, pluginJar: File, cp: Seq[File]): Unit =
    IO.write(work / "lane-inputs.txt",
      s"scala=$version\nannotation=${annotationJar.getCanonicalPath}\nplugin=${pluginJar.getCanonicalPath}\n" +
        cp.map(f => s"compiler-classpath=${f.getCanonicalPath}\n").mkString)

  def verifyStructure(root: File, version: String, annotationJar: File, pluginJar: File,
      cp: Seq[File], targets: Seq[File], log: Logger): Unit = {
    checkCompiler(version, cp)
    val output = new StringBuilder
    val java = new File(sys.props("java.home"), "bin/java").getAbsolutePath
    val exit = Process(Seq(java, "-cp", cp.map(_.getAbsolutePath).mkString(File.pathSeparator),
      "dotty.tools.dotc.Main", "-version"), root).!(ProcessLogger(
      line => output.append(line).append('\n'), line => output.append(line).append('\n')))
    require(exit == 0 && output.toString.contains(s"version $version "), s"wrong compiler runtime: $output")
    log.info(s"LANE PASS [$version] exact compiler and runtime libraries")

    checkArtifact(annotationJar, version)
    require(annotationJar.getCanonicalFile.getParentFile ==
      (root / "annotation" / "target" / s"scala-$version").getCanonicalFile, "annotation output is not lane-separated")
    log.info(s"LANE PASS [$version] lane-built annotation artifact")
    checkArtifact(pluginJar, version)
    require(pluginJar.getCanonicalFile.getParentFile ==
      (root / "plugin" / "target" / s"scala-$version").getCanonicalFile, "plugin output is not lane-separated")
    log.info(s"LANE PASS [$version] lane-built plugin artifact")

    val expectedTargets = Seq(root, root / "annotation", root / "plugin").map(_ / "target" / s"scala-$version")
    require(targets.map(_.getCanonicalFile) == expectedTargets.map(_.getCanonicalFile),
      s"shared build-state targets: $targets")
    require(workDirectory(root, "3.8.4", "m0") != workDirectory(root, "3.9.0", "m0"), "shared M0 output")
    require(workDirectory(root, "3.8.4", "m1") != workDirectory(root, "3.9.0", "m1"), "shared M1 output")
    log.info(s"LANE PASS [$version] exact-lane build and verification roots")

    def rejects(label: String)(body: => Unit): Unit = {
      val rejected = try { body; false } catch { case _: IllegalArgumentException => true }
      require(rejected, s"lane guard accepted $label")
    }
    val other = versions.find(_ != version).get
    rejects("unqualified lane") { validateInputs("unqualified", annotationJar, pluginJar, cp) }
    rejects("mismatched compiler") { checkCompiler(other, cp) }
    rejects("mismatched annotation") { checkArtifact(annotationJar, other) }
    rejects("mismatched plugin") { checkArtifact(pluginJar, other) }
    log.info(s"LANE PASS [$version] wrong-lane inputs rejected without launching fixtures")
  }
}
