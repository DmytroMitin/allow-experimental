import java.io.File
import sbt._
import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}

/** Exact Scala 3.9.0 adversarial coexistence gate using a separately compiled
  * real standard compiler plugin. The observer resolves fixture definitions by
  * typed Symbol.fullName and records the compiler-installed flattened phase plan.
  */
object M4AVerifier {
  private val requiredVersion = "3.9.0"
  private val providerName = "m4a.Fixture$.provider"
  private val ordinaryName = "m4a.Fixture$.harmlessSentinel"
  private val scalaDiagnostic = "marked @experimental"
  private val guardDiagnostic = "incompatible compiler-plugin phase inside the provider neutralization window"

  private val fixture = """package m4a
import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental

object Fixture:
  @experimental def provider(): Int = 1
  @allowExperimental def allowed(): Int = provider()
  def harmlessSentinel(): Int = 42
"""

  private case class CompileResult(exit: Int, output: String, classes: File, args: Seq[String], observerLog: String)

  def verify(root: File, scalaVersion: String, annotationJar: File, pluginJar: File,
      compilerClasspath: Seq[File], log: Logger): Unit = {
    require(scalaVersion == requiredVersion, s"M4A is qualified only on exact Scala $requiredVersion, not $scalaVersion")
    VerificationLane.validateInputs(scalaVersion, annotationJar, pluginJar, compilerClasspath)
    val work = VerificationLane.workDirectory(root, scalaVersion, "m4a")
    IO.delete(work)
    IO.createDirectory(work)
    VerificationLane.recordInputs(work, scalaVersion, annotationJar, pluginJar, compilerClasspath)
    log.info(s"M4A Scala $scalaVersion: ${work.getAbsolutePath}")

    val compilerCp = compilerClasspath.map(_.getAbsolutePath)
    val libraries = compilerClasspath.filter { file =>
      file.getName.startsWith("scala3-library_3-") || file.getName.startsWith("scala-library-")
    }.map(_.getAbsolutePath)
    require(libraries.size == 2, "expected two exact Scala runtime libraries")
    val sourceCp = libraries :+ annotationJar.getAbsolutePath
    val observerJar = buildObserver(root, work, compilerCp)
    val failures = ArrayBuffer.empty[String]

    def check(label: String)(body: => Unit): Unit =
      try {
        body
        log.info(s"M4A PASS $label")
      } catch {
        case error: IllegalArgumentException =>
          failures += s"$label: ${error.getMessage}"
          log.error(s"M4A FAIL $label: ${error.getMessage}")
      }

    def compile(label: String, source: String, probe: String, allowFirst: Boolean,
        extraFlags: Seq[String] = Nil): CompileResult = {
      val dir = work / label
      val classes = dir / "classes"
      IO.createDirectory(classes)
      val sourceFile = dir / "Fixture.scala"
      val observerLogFile = dir / "observer.log"
      IO.write(sourceFile, source)
      val pluginArgs =
        if (allowFirst) Seq(s"-Xplugin:${pluginJar.getAbsolutePath}", s"-Xplugin:${observerJar.getAbsolutePath}")
        else Seq(s"-Xplugin:${observerJar.getAbsolutePath}", s"-Xplugin:${pluginJar.getAbsolutePath}")
      val args = Seq(
        "-classpath", sourceCp.mkString(File.pathSeparator),
        "-d", classes.getAbsolutePath
      ) ++ pluginArgs ++ Seq(
        s"-P:m4a-observer:probe=$probe",
        s"-P:m4a-observer:provider=$providerName",
        s"-P:m4a-observer:ordinary=$ordinaryName",
        s"-P:m4a-observer:log=${observerLogFile.getAbsolutePath}",
        "-color:never"
      ) ++ extraFlags ++ Seq(sourceFile.getAbsolutePath)
      val result = runJava(root, compilerCp, "dotty.tools.dotc.Main", args)
      IO.write(dir / "compiler-arguments.txt", args.mkString("\n") + "\n")
      IO.write(dir / "compiler.log", result._2)
      val observations = if (observerLogFile.isFile) IO.read(observerLogFile) else ""
      CompileResult(result._1, result._2, classes, args, observations)
    }

    def assertPlan(result: CompileResult, expected: Seq[String]): Unit = {
      val plan = observationValue(result.observerLog, "phasePlan")
      var previous = -1
      expected.foreach { phase =>
        val next = plan.indexOf(phase, previous + 1)
        require(next >= 0, s"phase $phase absent from plan: $plan")
        require(next > previous, s"phase $phase out of order: $plan")
        previous = next
      }
    }

    Seq(true, false).foreach { allowFirst =>
      val order = if (allowFirst) "allow-first" else "observer-first"

      val p0 = compile(s"$order-p0", fixture, "p0", allowFirst)
      check(s"$order P0 sees truthful provider state") {
        require(p0.exit == 0, p0.output)
        require(observation(p0.observerLog, "P0", "providerHasExperimentalAnnotation") == "true", p0.observerLog)
        require(observation(p0.observerLog, "P0", "providerIsExperimental") == "true", p0.observerLog)
        require(observation(p0.observerLog, "P0", "reportedErrors") == "false", p0.observerLog)
        assertPlan(p0, Seq("m4aProbeP0:plugin", "allowExperimentalCheckReferences:plugin",
          "crossVersionChecks:builtin", "allowExperimentalRestoreProviders:plugin"))
      }

      val p1 = compile(s"$order-p1", fixture, "p1", allowFirst)
      check(s"$order P1 is blocked fail-closed before observing neutralization") {
        require(p1.exit != 0, "sensitive P1 observer unexpectedly compiled")
        require(p1.output.contains(guardDiagnostic), p1.output)
        require(!p1.observerLog.contains("probe=P1\n"), p1.observerLog)
        require(observation(p1.observerLog, "P3_AUDIT", "providerHasExperimentalAnnotation") == "true", p1.observerLog)
        require(observation(p1.observerLog, "P3_AUDIT", "providerIsExperimental") == "true", p1.observerLog)
        require(observation(p1.observerLog, "P3_AUDIT", "reportedErrors") == "true", p1.observerLog)
        assertPlan(p1, Seq("allowExperimentalCheckReferences:plugin", "m4aProbeP1:plugin",
          "crossVersionChecks:builtin", "allowExperimentalRestoreProviders:plugin", "m4aProbeP3Audit:plugin"))
      }

      val p2 = compile(s"$order-p2", fixture, "p2", allowFirst)
      check(s"$order P2 is blocked fail-closed before observing neutralization") {
        require(p2.exit != 0, "sensitive P2 observer unexpectedly compiled")
        require(p2.output.contains(guardDiagnostic), p2.output)
        require(!p2.observerLog.contains("probe=P2\n"), p2.observerLog)
        require(observation(p2.observerLog, "P3_AUDIT", "providerHasExperimentalAnnotation") == "true", p2.observerLog)
        require(observation(p2.observerLog, "P3_AUDIT", "providerIsExperimental") == "true", p2.observerLog)
        require(observation(p2.observerLog, "P3_AUDIT", "reportedErrors") == "true", p2.observerLog)
        assertPlan(p2, Seq("allowExperimentalCheckReferences:plugin", "crossVersionChecks:builtin",
          "m4aProbeP2:plugin", "allowExperimentalRestoreProviders:plugin", "m4aProbeP3Audit:plugin"))
      }

      val p3 = compile(s"$order-p3", fixture, "p3", allowFirst)
      check(s"$order P3 sees exact restoration") {
        require(p3.exit == 0, p3.output)
        require(observation(p3.observerLog, "P3", "providerHasExperimentalAnnotation") == "true", p3.observerLog)
        require(observation(p3.observerLog, "P3", "providerIsExperimental") == "true", p3.observerLog)
        require(observation(p3.observerLog, "P3", "reportedErrors") == "false", p3.observerLog)
        assertPlan(p3, Seq("allowExperimentalCheckReferences:plugin", "crossVersionChecks:builtin",
          "allowExperimentalRestoreProviders:plugin", "m4aProbeP3:plugin"))
      }
    }

    val harmless = compile("harmless-positive", fixture, "harmless", allowFirst = true)
    check("harmless outside-window observer ran and inspected an ordinary definition") {
      require(harmless.exit == 0, harmless.output)
      require(observation(harmless.observerLog, "HARMLESS", "ordinary") == ordinaryName, harmless.observerLog)
      require(observation(harmless.observerLog, "HARMLESS", "ordinaryIsExperimental") == "false", harmless.observerLog)
      require(observation(harmless.observerLog, "HARMLESS", "providerIsExperimental") == "true", harmless.observerLog)
      require(!harmless.args.contains("-experimental"), "harmless positive used global -experimental")
    }

    if (harmless.exit == 0) {
      val downstream = compileWithoutPlugins(root, work / "downstream", compilerCp,
        libraries :+ harmless.classes.getAbsolutePath,
        "package downstream\nval result: Int = m4a.Fixture.allowed()\n")
      check("supported consumer stays ordinary without either plugin or marker artifact") {
        require(downstream._1 == 0, downstream._2)
        require(!downstream._3.exists(_.startsWith("-Xplugin:")), downstream._3.mkString("\n"))
        require(!downstream._3.contains("-experimental"), downstream._3.mkString("\n"))
        require(!downstream._3.contains(annotationJar.getAbsolutePath), downstream._3.mkString("\n"))
      }

      val tasty = harmless.classes / "m4a" / "Fixture.tasty"
      val decompiled = runJava(root, compilerCp, "dotty.tools.dotc.decompiler.Main",
        Seq("-classpath", sourceCp.mkString(File.pathSeparator), "-color:never", tasty.getAbsolutePath))
      IO.write(work / "harmless-positive" / "decompiled.log", decompiled._2)
      check("provider remains experimental and consumer has no marker leak") {
        require(decompiled._1 == 0, decompiled._2)
        val provider = definition(decompiled._2, "provider")
        val allowed = definition(decompiled._2, "allowed")
        require(provider.contains("@scala.annotation.experimental"), provider)
        require(!allowed.contains("@scala.annotation.experimental"), allowed)
        require(!allowed.contains("allowExperimental"), allowed)
        require(!decompiled._2.contains("@io.github.dmytromitin.allowexperimental.allowExperimental"), decompiled._2)
      }
    }

    val ordinaryRejection = compile("ordinary-pre-neutralization-rejection", fixture +
      "\nobject Forbidden { def direct(): Int = Fixture.provider() }\n", "p3", allowFirst = true)
    check("ordinary rejection happens before neutralization and leaves provider experimental") {
      require(ordinaryRejection.exit != 0, "ordinary forbidden reference unexpectedly compiled")
      require(ordinaryRejection.output.contains(scalaDiagnostic), ordinaryRejection.output)
      require(observation(ordinaryRejection.observerLog, "P3", "providerIsExperimental") == "true",
        ordinaryRejection.observerLog)
      require(observation(ordinaryRejection.observerLog, "P3", "reportedErrors") == "true",
        ordinaryRejection.observerLog)
    }

    val lateError = compile("reported-error-after-neutralization", fixture +
      "\ntrait Required { def missing: Int }\nclass Broken extends Required\n", "p3", allowFirst = true)
    check("reported RefChecks error still restores provider before outside-window observer") {
      require(lateError.exit != 0, "late RefChecks negative unexpectedly compiled")
      require(lateError.output.contains("needs to be abstract"), lateError.output)
      require(!lateError.output.contains("allow-experimental internal invariant failed"), lateError.output)
      require(observation(lateError.observerLog, "P3", "providerHasExperimentalAnnotation") == "true",
        lateError.observerLog)
      require(observation(lateError.observerLog, "P3", "providerIsExperimental") == "true", lateError.observerLog)
      require(observation(lateError.observerLog, "P3", "reportedErrors") == "true", lateError.observerLog)
    }

    if (failures.nonEmpty) {
      IO.write(work / "summary.txt", (Seq(
        "SCALA_3_9_0=FAIL",
        "M4A_GATE=FAIL",
        s"FAILURE_COUNT=${failures.size}"
      ) ++ failures.zipWithIndex.map { case (failure, index) =>
        s"FAILURE_${index + 1}=${failure.replace('\n', ' ')}"
      }).mkString("", "\n", "\n"))
      require(requirement = false, failures.mkString("\n"))
    } else {
      IO.write(work / "summary.txt", Seq(
        "SCALA_3_9_0=PASS",
        "M4A_GATE=PASS",
        "P0_BEFORE_ALLOW_PROVIDER_EXPERIMENTAL=YES",
        "P1_AFTER_ALLOW_BEFORE_CROSS=BLOCKED_FAIL_CLOSED",
        "P2_AFTER_CROSS_BEFORE_RESTORE=BLOCKED_FAIL_CLOSED",
        "P3_AFTER_RESTORE_PROVIDER_EXPERIMENTAL=YES",
        "FINAL_COEXISTENCE_MODEL=FAIL_CLOSED_PHASE_CONTRACT",
        "SENSITIVE_WINDOW_SILENT_INTERFERENCE_FINAL=NO",
        "HARMLESS_SECOND_PLUGIN=PASS",
        "REPORTED_ERROR_RESTORATION=PASS",
        "GLOBAL_EXPERIMENTAL_REQUIRED=NO"
      ).mkString("", "\n", "\n"))
    }
  }

  private def buildObserver(root: File, work: File, compilerCp: Seq[String]): File = {
    val observerRoot = root / "verification" / "plugins" / "phase-observer"
    val source = observerRoot / "src" / "main" / "scala" / "io" / "github" / "dmytromitin" /
      "allowexperimental" / "m4aobserver" / "M4AObserverPlugin.scala"
    val descriptor = observerRoot / "src" / "main" / "resources" / "plugin.properties"
    require(source.isFile && descriptor.isFile, "missing retained M4A observer source or descriptor")
    val classes = work / "observer-plugin" / "classes"
    IO.createDirectory(classes)
    val args = Seq(
      "-classpath", compilerCp.mkString(File.pathSeparator),
      "-d", classes.getAbsolutePath,
      "-color:never",
      source.getAbsolutePath
    )
    val compiled = runJava(root, compilerCp, "dotty.tools.dotc.Main", args)
    IO.write(work / "observer-plugin" / "compiler-arguments.txt", args.mkString("\n") + "\n")
    IO.write(work / "observer-plugin" / "compiler.log", compiled._2)
    require(compiled._1 == 0, s"M4A observer plugin did not compile: ${compiled._2}")
    IO.copyFile(descriptor, classes / "plugin.properties")
    val jar = work / "observer-plugin" / "m4a-observer_3-3.9.0.jar"
    IO.zip(Path.allSubpaths(classes).toSeq, jar, Some(0L))
    require(jar.isFile, s"M4A observer jar missing: $jar")
    jar
  }

  private def compileWithoutPlugins(root: File, dir: File, compilerCp: Seq[String], cp: Seq[String],
      source: String): (Int, String, Seq[String]) = {
    val classes = dir / "classes"
    IO.createDirectory(classes)
    val file = dir / "Consumer.scala"
    IO.write(file, source)
    val args = Seq("-classpath", cp.mkString(File.pathSeparator), "-d", classes.getAbsolutePath,
      "-color:never", file.getAbsolutePath)
    val result = runJava(root, compilerCp, "dotty.tools.dotc.Main", args)
    IO.write(dir / "compiler-arguments.txt", args.mkString("\n") + "\n")
    IO.write(dir / "compiler.log", result._2)
    (result._1, result._2, args)
  }

  private def observation(log: String, probe: String, key: String): String = {
    val block = log.split("---", -1).find(_.contains(s"probe=$probe\n")).getOrElse(
      throw new IllegalArgumentException(s"missing $probe observation: $log"))
    observationValue(block, key)
  }

  private def observationValue(block: String, key: String): String =
    block.split("\\R").find(_.startsWith(key + "=")).map(_.stripPrefix(key + "=")).getOrElse(
      throw new IllegalArgumentException(s"missing $key observation: $block"))

  private def definition(output: String, name: String): String =
    output.split("\\R").find(_.contains(s"def $name(")).getOrElse(
      throw new IllegalArgumentException(s"missing definition $name: $output"))

  private def runJava(root: File, cp: Seq[String], main: String, args: Seq[String]): (Int, String) = {
    val java = new File(sys.props("java.home"), "bin/java").getAbsolutePath
    val output = new StringBuilder
    val exit = Process(Seq(java, "-cp", cp.mkString(File.pathSeparator), main) ++ args, root).!(
      ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n')))
    (exit, output.result())
  }
}
