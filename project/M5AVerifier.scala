import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

import sbt._

import scala.sys.process.{Process, ProcessLogger}

object M5AVerifier {
  private val RequiredVersion = "3.9.0"
  private val OlderVersions = Set("3.3.8", "3.8.4")
  private val ExperimentalDiagnostic = "marked @experimental"

  private final case class Compilation(exit: Int, output: String)
  private final case class RunSpec(label: String, source: String, success: Boolean, diagnostic: String)

  private val allowedSource = """package m5a
import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental

object Fixture:
  @experimental def provider(): Int = 1
  @allowExperimental def allowed(): Int = provider()
  def ordinary(): Int = allowed()
"""

  private val unmarkedSource = """package m5a
import scala.annotation.experimental

object Fixture:
  @experimental def provider(): Int = 1
  def allowed(): Int = provider()
  def ordinary(): Int = allowed()
"""

  private val siblingSource = allowedSource +
    "\n  def forbiddenSibling(): Int = provider()\n"

  private val lateErrorSource = allowedSource + """

trait Required:
  def missing: Int

class Broken extends Required
"""

  private val runs = Seq(
    RunSpec("R1-allowed-success", allowedSource, success = true, ""),
    RunSpec("R2-permission-removed", unmarkedSource, success = false, ExperimentalDiagnostic),
    RunSpec("R3-repaired-success", allowedSource, success = true, ""),
    RunSpec("R4-sibling-negative", siblingSource, success = false, ExperimentalDiagnostic),
    RunSpec("R5-clean-recovery", allowedSource, success = true, ""),
    RunSpec("R6-reported-error-after-neutralization", lateErrorSource, success = false, "needs to be abstract"),
    RunSpec("R7-final-clean-success", allowedSource, success = true, ""),
    RunSpec("R8-direct-provider-negative", unmarkedSource, success = false, ExperimentalDiagnostic)
  )

  def verifySameJvm(
      root: File,
      scalaVersion: String,
      annotationJar: File,
      pluginJar: File,
      compilerClasspath: Seq[File],
      log: Logger
  ): Unit = {
    requireExactLane(scalaVersion, annotationJar, pluginJar, compilerClasspath)
    verifySameJvmForLane(root, scalaVersion, annotationJar, pluginJar, compilerClasspath, "m5a", log)
  }

  def verifySameJvmOlder(
      root: File,
      scalaVersion: String,
      annotationJar: File,
      pluginJar: File,
      compilerClasspath: Seq[File],
      log: Logger
  ): Unit = {
    require(OlderVersions(scalaVersion),
      s"M5B same-JVM lifecycle is qualified only on exact Scala 3.3.8 or 3.8.4, not $scalaVersion")
    VerificationLane.validateInputs(scalaVersion, annotationJar, pluginJar, compilerClasspath)
    verifySameJvmForLane(root, scalaVersion, annotationJar, pluginJar, compilerClasspath, "m5b", log)
  }

  private def verifySameJvmForLane(
      root: File,
      scalaVersion: String,
      annotationJar: File,
      pluginJar: File,
      compilerClasspath: Seq[File],
      gate: String,
      log: Logger
  ): Unit = {
    val work = VerificationLane.workDirectory(root, scalaVersion, gate)
    IO.delete(work)
    IO.createDirectory(work)
    VerificationLane.recordInputs(work, scalaVersion, annotationJar, pluginJar, compilerClasspath)

    val compilerCp = compilerClasspath.map(_.getCanonicalFile).distinct
    val libraries = compilerCp.filter { file =>
      file.getName.startsWith("scala3-library_3-") || file.getName.startsWith("scala-library-")
    }
    require(libraries.size == 2, s"expected exact Scala runtime libraries: $libraries")
    val sourceCp = (libraries :+ annotationJar.getCanonicalFile).map(_.getAbsolutePath)

    val observerJar = buildObserver(root, work, scalaVersion, compilerCp.map(_.getAbsolutePath))
    writeRunSources(work)
    val harnessClasses = compileHarness(root, work, compilerCp.map(_.getAbsolutePath))
    val harnessCp = (Seq(harnessClasses) ++ compilerCp).map(_.getAbsolutePath)
    val harnessArgs = Seq(
      pluginJar.getCanonicalPath,
      observerJar.getCanonicalPath,
      sourceCp.mkString(File.pathSeparator),
      work.getCanonicalPath
    )
    val harness = runJava(root, harnessCp, "SameJvmHarness", harnessArgs)
    IO.write(work / "same-jvm-harness.log", harness.output)
    IO.write(work / "same-jvm-harness-arguments.txt", harnessArgs.mkString("", "\n", "\n"))
    require(harness.exit == 0, s"same-JVM harness failed: ${harness.output}")
    require(harness.output.contains("SAME_JVM_HARNESS_PASS"), harness.output)

    val identity = IO.read(work / "same-jvm-identities.txt")
    val identityLines = identity.linesIterator.filter(_.startsWith("R")).toVector
    require(identityLines.size == runs.size, s"expected ${runs.size} identity records: $identity")
    val compilerIds = values(identityLines, "compiler")
    val baseIds = values(identityLines, "base")
    val driverIds = values(identityLines, "driver")
    val pluginIds = values(identityLines, "allowPlugin")
    val runIds = values(identityLines, "run")
    val periodRunIds = values(identityLines, "runId").map(_.toInt)
    val captureIds = values(identityLines, "capturePhase")
    val checkIds = values(identityLines, "checkPhase")
    val restoreIds = values(identityLines, "restorePhase")
    val reporterIds = values(identityLines, "reporter")
    require(compilerIds.distinct.size == 1, s"compiler instance changed: $identity")
    require(baseIds.distinct.size == 1, s"ContextBase changed: $identity")
    require(driverIds.distinct.size == 1, s"Driver changed: $identity")
    require(pluginIds.distinct.size == 1, s"Allow plugin instance changed: $identity")
    require(runIds.distinct.size == runs.size, s"Run instances were reused: $identity")
    require(periodRunIds == periodRunIds.sorted && periodRunIds.distinct.size == runs.size,
      s"run ids were not fresh and increasing: $identity")
    require(captureIds.distinct.size == runs.size, s"capture phase leaked across runs: $identity")
    require(checkIds.distinct.size == runs.size, s"check phase leaked across runs: $identity")
    require(restoreIds.distinct.size == runs.size, s"restore phase leaked across runs: $identity")
    require(reporterIds.distinct.size == runs.size, s"reporter leaked across runs: $identity")

    runs.zipWithIndex.foreach { case (spec, index) =>
      val dir = work / f"run-${index + 1}%02d-${spec.label}"
      val diagnostics = IO.read(dir / "diagnostics.txt")
      val observer = IO.read(dir / "observer.log")
      if (spec.success) {
        require(diagnostics.trim.isEmpty, s"${spec.label} retained diagnostics: $diagnostics")
        require(observation(observer, "reportedErrors") == "false", observer)
        require(observation(observer, "providerIsExperimental") == "true", observer)
        verifyRestoredTasty(root, dir, compilerCp.map(_.getAbsolutePath), sourceCp)
      } else {
        require(diagnostics.contains(spec.diagnostic), s"${spec.label} wrong diagnostic: $diagnostics")
        require(observation(observer, "reportedErrors") == "true", observer)
        require(observation(observer, "providerIsExperimental") == "true", observer)
      }
    }

    writeCompilerSourceAudit(work, scalaVersion, compilerCp, pluginJar)
    val versionKey = "SCALA_" + scalaVersion.replace('.', '_')
    IO.write(work / "same-jvm-summary.txt", Seq(
      s"$versionKey=PASS",
      "SAME_JVM_REPEATED_RUNS=PASS",
      "SAME_JVM_SUPPORTED_REUSE_TOPOLOGY=ONE_CONTEXT_BASE_ONE_COMPILER_ONE_PLUGIN_FRESH_RUN_PHASE_STATE_REPORTER",
      "SAME_JVM_SUCCESS_FAILURE_RECOVERY=PASS",
      "RUN_STATE_LEAK=NO",
      "BODY_REFERENCE_STATE_LEAK=NO",
      "PROVIDER_STATE_RESTORED_BETWEEN_RUNS=YES",
      "REPORTER_ERROR_LEAK_BETWEEN_RUNS=NO",
      "RUN_COUNT=8",
      "GLOBAL_EXPERIMENTAL_REQUIRED=NO"
    ).mkString("", "\n", "\n"))
    log.info(s"${gate.toUpperCase} SAME-JVM PASS [$scalaVersion] 8/8 one ContextBase/compiler/plugin with fresh runs, phases/state and reporters")
  }

  def verifyFinal(
      root: File,
      scalaVersion: String,
      annotationJar: File,
      pluginJar: File,
      compilerClasspath: Seq[File],
      log: Logger
  ): Unit = {
    requireExactLane(scalaVersion, annotationJar, pluginJar, compilerClasspath)
    val work = VerificationLane.workDirectory(root, scalaVersion, "m5a")
    val sameJvm = requiredProperties(work / "same-jvm-summary.txt")
    val zinc = requiredProperties(work / "zinc" / "zinc-summary.txt")
    val regressions = requiredProperties(root / "target" / "verification-logs" / "m5a-regression-summary.txt")

    requireValue(sameJvm, "SCALA_3_9_0", "PASS")
    requireValue(sameJvm, "SAME_JVM_REPEATED_RUNS", "PASS")
    requireValue(sameJvm, "SAME_JVM_SUPPORTED_REUSE_TOPOLOGY",
      "ONE_CONTEXT_BASE_ONE_COMPILER_ONE_PLUGIN_FRESH_RUN_PHASE_STATE_REPORTER")
    requireValue(sameJvm, "SAME_JVM_SUCCESS_FAILURE_RECOVERY", "PASS")
    requireValue(sameJvm, "RUN_STATE_LEAK", "NO")
    requireValue(sameJvm, "BODY_REFERENCE_STATE_LEAK", "NO")
    requireValue(sameJvm, "PROVIDER_STATE_RESTORED_BETWEEN_RUNS", "YES")
    requireValue(sameJvm, "REPORTER_ERROR_LEAK_BETWEEN_RUNS", "NO")
    requireValue(sameJvm, "RUN_COUNT", "8")
    requireValue(sameJvm, "GLOBAL_EXPERIMENTAL_REQUIRED", "NO")

    Seq(
      "SBT_ZINC_MULTIPROJECT",
      "SBT_NON_CLEAN_SEQUENCE",
      "INCREMENTAL_PROVIDER_BECOMES_EXPERIMENTAL_INVALIDATES_ALLOWED",
      "INCREMENTAL_PERMISSION_ADD_RECOVERS",
      "INCREMENTAL_PERMISSION_REMOVE_FAILS_CLOSED",
      "INCREMENTAL_SECOND_REPAIR_RECOVERS",
      "INCREMENTAL_SIBLING_NEGATIVE",
      "INCREMENTAL_SIBLING_REPAIR",
      "INCREMENTAL_PROVIDER_TOGGLE",
      "DOWNSTREAM_WITHOUT_ALLOW_PLUGIN",
      "DOWNSTREAM_WITHOUT_ALLOW_MARKER",
      "PLUGIN_ABSENT_PERMISSION_SAFETY"
    ).foreach(requireValue(zinc, _, "PASS"))
    requireValue(zinc, "ZINC_NOOP_OBSERVED", "YES")
    requireValue(zinc, "WRONG_LANE_PLUGIN_3_8_4_ON_3_9_0", "REJECTED_FAIL_CLOSED")
    requireValue(zinc, "WRONG_LANE_MARKER_3_8_4_ON_3_9_0", "COMPATIBLE_OBSERVED")
    requireValue(zinc, "PUBLIC_MARKER_CROSS_LANE_COMPATIBILITY_CLAIMED", "NO")
    requireValue(zinc, "PERSISTENT_SBT_BSP_IDE_CLAIMED", "NO")
    requireValue(zinc, "GLOBAL_EXPERIMENTAL_REQUIRED", "NO")

    Seq("SCALA_3_3_8_CORE", "SCALA_3_8_4_CORE", "SCALA_3_9_0_CORE",
      "M4C_SCALA_3_3_8", "M4C_SCALA_3_8_4", "M4A_SCALA_3_9_0", "M4B_SCALA_3_9_0")
      .foreach(requireValue(regressions, _, "PASS"))
    requireValue(regressions, "READ_ONLY_PEERS_BUILT_FOR_M5A", "NO")

    verifyEvidenceChecksums(work / "zinc" / "zinc-evidence.sha256")
    verifyEvidenceChecksums(work / "zinc" / "evidence" / "artifact-inputs.sha256")
    verifyZincOutputs(root, work / "zinc", annotationJar, compilerClasspath)
    writeZincSourceAudit(work / "zinc", compilerClasspath)

    val finalSummary = Seq(
      "PROMPT_012_M5A=PASS",
      "SCALA_3_9_0=PASS",
      "SAME_JVM_REPEATED_RUNS=PASS",
      "SAME_JVM_SUCCESS_FAILURE_RECOVERY=PASS",
      "RUN_STATE_LEAK=NO",
      "BODY_REFERENCE_STATE_LEAK=NO",
      "PROVIDER_STATE_RESTORED_BETWEEN_RUNS=YES",
      "REPORTER_ERROR_LEAK_BETWEEN_RUNS=NO",
      "SBT_ZINC_MULTIPROJECT=PASS",
      "SBT_NON_CLEAN_SEQUENCE=PASS",
      "ZINC_NOOP_OBSERVED=YES",
      "INCREMENTAL_INVALIDATION_AND_RECOVERY=PASS",
      "DOWNSTREAM_WITHOUT_ALLOW_AUTHORITY=PASS",
      "PLUGIN_ABSENCE=REJECTED_FAIL_CLOSED",
      "WRONG_LANE_PLUGIN=REJECTED_FAIL_CLOSED",
      "WRONG_LANE_MARKER=COMPATIBLE_OBSERVED_WITHOUT_PUBLIC_CLAIM",
      "FULL_THREE_LANE_CORE_REGRESSION=PASS",
      "RETAINED_M4_REGRESSION=PASS",
      "READ_ONLY_PEERS_BUILT_FOR_M5A=NO",
      "GLOBAL_EXPERIMENTAL_REQUIRED=NO",
      "PERSISTENT_SBT_BSP_IDE_CLAIMED=NO",
      "M5B_OR_M5C_CLAIMED=NO",
      "RELEASE_OR_COORDINATES_LOCKED=NO"
    )
    IO.write(work / "summary.txt", finalSummary.mkString("", "\n", "\n"))
    log.info("M5A PASS: same-JVM lifecycle, real non-clean Zinc transitions, mismatch safety and retained regressions")
  }

  private def requiredProperties(file: File): Map[String, String] = {
    require(file.isFile, s"missing required M5A evidence: $file")
    IO.readLines(file).filter(_.contains("=")).map { line =>
      val split = line.indexOf('=')
      line.substring(0, split) -> line.substring(split + 1)
    }.toMap
  }

  private def requireValue(properties: Map[String, String], key: String, expected: String): Unit =
    require(properties.get(key).contains(expected),
      s"expected $key=$expected, observed ${properties.get(key).getOrElse("MISSING")}")

  private def verifyEvidenceChecksums(manifest: File): Unit = {
    require(manifest.isFile, s"missing Zinc evidence checksum manifest: $manifest")
    IO.readLines(manifest).filter(_.nonEmpty).foreach { line =>
      val separator = line.indexOf("  ")
      require(separator > 0, s"malformed checksum entry: $line")
      val expected = line.substring(0, separator)
      val file = new File(line.substring(separator + 2))
      require(file.isFile, s"missing checksummed evidence: $file")
      require(sha256(file) == expected, s"evidence checksum mismatch: $file")
    }
  }

  private def verifyZincOutputs(root: File, zinc: File, annotationJar: File,
      compilerClasspath: Seq[File]): Unit = {
    val fixture = zinc / "fixture"
    val providerClasses = fixture / "provider" / "target" / "scala-3.9.0" / "classes"
    val allowedClasses = fixture / "allowed" / "target" / "scala-3.9.0" / "classes"
    val consumerClasses = fixture / "consumer" / "target" / "scala-3.9.0" / "classes"
    val freshDownstreamClasses = fixture / "fresh-downstream" / "target" / "scala-3.9.0" / "classes"
    val analysis = Seq(providerClasses, allowedClasses, consumerClasses, freshDownstreamClasses)
      .map(_.getParentFile / "zinc" / "inc_compile_3.zip")
    require(analysis.forall(file => file.isFile && file.length() > 0), s"missing persisted Zinc analysis: $analysis")

    val compilerCp = compilerClasspath.map(_.getCanonicalFile).distinct
    val libraries = compilerCp.filter(file =>
      file.getName.startsWith("scala3-library_3-") || file.getName.startsWith("scala-library-"))
    val decompilerCp = (libraries ++ Seq(annotationJar, providerClasses, allowedClasses, consumerClasses,
      freshDownstreamClasses))
      .map(_.getCanonicalPath).distinct
    val targets = Seq(
      (providerClasses / "m5azinc" / "Provider.tasty", "provider", true),
      (allowedClasses / "m5azinc" / "Allowed.tasty", "allowed", false),
      (consumerClasses / "m5azinc" / "Consumer.tasty", "use", false),
      (freshDownstreamClasses / "m5azinc" / "FreshDownstream.tasty", "freshUse", false)
    )
    targets.foreach { case (tasty, name, experimental) =>
      require(tasty.isFile, s"missing final Zinc TASTy: $tasty")
      val result = runJava(root, compilerCp.map(_.getAbsolutePath), "dotty.tools.dotc.decompiler.Main",
        Seq("-classpath", decompilerCp.mkString(File.pathSeparator), "-color:never", tasty.getAbsolutePath))
      IO.write(zinc / s"final-$name-decompiled.log", result.output)
      require(result.exit == 0, result.output)
      val line = definition(result.output, name)
      if (experimental) require(line.contains("@scala.annotation.experimental"), line)
      else require(!line.contains("experimental") && !line.contains("allowExperimental"), line)
    }

    val inputs = IO.read(zinc / "evidence" / "m5a-inputs.txt")
    inputs.linesIterator.filter(_.startsWith("consumerClasspath=")).foreach { line =>
      require(!line.contains("allow-experimental-annotation") && !line.contains("allow-experimental-plugin"), line)
    }
    inputs.linesIterator.filter(_.startsWith("freshDownstreamClasspath=")).foreach { line =>
      require(!line.contains("allow-experimental-annotation") && !line.contains("allow-experimental-plugin"), line)
    }
    val freshLog = IO.read(zinc / "evidence" / "E6-fresh-downstream.log")
    require(freshLog.matches("(?s).*compiling 1 Scala source to .*/fresh-downstream/target/scala-3\\.9\\.0/classes.*"),
      "fresh downstream was not compiled against the final supported API")
    val wrongPlugin = IO.read(zinc / "evidence" / "F2-wrong-plugin.log")
    require(wrongPlugin.contains("plugin built for exact Scala 3.8.4 cannot run on Scala 3.9.0"), wrongPlugin)
  }

  private def writeZincSourceAudit(zinc: File, compilerClasspath: Seq[File]): Unit = {
    val fixture = zinc / "fixture"
    val sbtLib = fixture / ".sbt-global" / "boot" / "scala-2.12.20" / "org.scala-sbt" / "sbt" / "1.11.7"
    val zincJar = sbtLib / "zinc_2.12-1.11.0.jar"
    val interfaceJar = sbtLib / "compiler-interface-1.11.0.jar"
    require(zincJar.isFile && interfaceJar.isFile, "missing exact task-observed Zinc/compiler-interface jars")
    val compiler = compilerClasspath.find(_.getName == "scala3-compiler_3-3.9.0.jar").getOrElse(
      throw new IllegalArgumentException("missing exact Scala 3.9.0 compiler jar"))
    val scalaLang = compiler.getParentFile.getParentFile.getParentFile
    val bridgeDir = scalaLang / "scala3-sbt-bridge" / "3.9.0"
    val bridge = bridgeDir / "scala3-sbt-bridge-3.9.0.jar"
    val bridgeSources = bridgeDir / "scala3-sbt-bridge-3.9.0-sources.jar"
    require(bridge.isFile && bridgeSources.isFile, s"missing exact Scala 3 sbt bridge inputs: $bridgeDir")
    IO.write(zinc / "zinc-source-audit.txt", Seq(
      "sbtVersion=1.11.7",
      "zincVersion=1.11.0",
      s"zincJar=${zincJar.getCanonicalPath}",
      s"zincJarSha256=${sha256(zincJar)}",
      s"compilerInterfaceJar=${interfaceJar.getCanonicalPath}",
      s"compilerInterfaceJarSha256=${sha256(interfaceJar)}",
      s"scala3SbtBridgeJar=${bridge.getCanonicalPath}",
      s"scala3SbtBridgeJarSha256=${sha256(bridge)}",
      s"scala3SbtBridgeSources=${bridgeSources.getCanonicalPath}",
      s"scala3SbtBridgeSourcesSha256=${sha256(bridgeSources)}",
      "scala3BridgeCompileLifecycle=CompilerBridge.run constructs one fresh CompilerBridgeDriver per compile invocation",
      "scala3BridgeDriverLifecycle=CompilerBridgeDriver constructs one fresh ContextBase and Compiler",
      "zincClaim=persisted analysis and class-TASTy outputs across real non-clean sbt invocations",
      "compilerInstanceReuseClaimedForZinc=NO",
      "persistentSbtBspIdeClaimed=NO"
    ).mkString("", "\n", "\n"))
  }

  private def requireExactLane(scalaVersion: String, annotationJar: File, pluginJar: File,
      compilerClasspath: Seq[File]): Unit = {
    require(scalaVersion == RequiredVersion,
      s"M5A is qualified only on exact Scala $RequiredVersion, not $scalaVersion")
    VerificationLane.validateInputs(scalaVersion, annotationJar, pluginJar, compilerClasspath)
  }

  private def writeRunSources(work: File): Unit =
    runs.zipWithIndex.foreach { case (spec, index) =>
      val dir = work / f"run-${index + 1}%02d-${spec.label}"
      IO.createDirectory(dir / "classes")
      IO.write(dir / "Fixture.scala", spec.source)
      IO.write(dir / "expectation.txt", Seq(
        s"label=${spec.label}",
        s"expected=${if (spec.success) "success" else "failure"}",
        s"diagnostic=${spec.diagnostic}",
        s"sourceSha256=${sha256(dir / "Fixture.scala")}"
      ).mkString("", "\n", "\n"))
    }

  private def compileHarness(root: File, work: File, compilerCp: Seq[String]): File = {
    val dir = work / "same-jvm-harness"
    val classes = dir / "classes"
    IO.createDirectory(classes)
    val source = dir / "SameJvmHarness.scala"
    IO.write(source, sameJvmHarnessSource)
    val args = Seq(
      "-classpath", compilerCp.mkString(File.pathSeparator),
      "-d", classes.getAbsolutePath,
      "-color:never",
      source.getAbsolutePath
    )
    val compiled = runJava(root, compilerCp, "dotty.tools.dotc.Main", args)
    IO.write(dir / "compiler-arguments.txt", args.mkString("", "\n", "\n"))
    IO.write(dir / "compiler.log", compiled.output)
    require(compiled.exit == 0, s"same-JVM harness did not compile: ${compiled.output}")
    classes
  }

  private def buildObserver(root: File, work: File, scalaVersion: String,
      compilerCp: Seq[String]): File = {
    val observerRoot = root / "m4a-observer"
    val source = observerRoot / "src" / "main" / "scala" / "io" / "github" / "dmytromitin" /
      "allowexperimental" / "m4aobserver" / "M4AObserverPlugin.scala"
    val descriptor = observerRoot / "src" / "main" / "resources" / "plugin.properties"
    require(source.isFile && descriptor.isFile, "missing retained M4A observer source or descriptor")
    val observerSource =
      if (scalaVersion != "3.3.8") source
      else {
        val generated = work / "observer-plugin" / "M5BObserverPlugin338.scala"
        val adapted = IO.read(source)
          .replace(
            "override def initialize(options: List[String])(using Context): List[PluginPhase] =",
            "override def init(options: List[String]): List[PluginPhase] ="
          )
          .replace(
            "def parse(options: List[String])(using Context): ObserverConfig =",
            "def parse(options: List[String]): ObserverConfig ="
          )
          .replace(
            "report.error(s\"m4a-observer unknown probe: $other\")\n        Nil",
            "throw new IllegalArgumentException(s\"m4a-observer unknown probe: $other\")"
          )
          .replace(
            "report.error(s\"m4a-observer malformed option: $option\")\n          \"\" -> \"\"",
            "throw new IllegalArgumentException(s\"m4a-observer malformed option: $option\")"
          )
          .replace(
            "report.error(s\"m4a-observer missing option: $key\")\n          \"\"",
            "throw new IllegalArgumentException(s\"m4a-observer missing option: $key\")"
          )
        IO.write(generated, adapted)
        generated
      }
    val classes = work / "observer-plugin" / "classes"
    IO.createDirectory(classes)
    val args = Seq(
      "-classpath", compilerCp.mkString(File.pathSeparator),
      "-d", classes.getAbsolutePath,
      "-color:never",
      observerSource.getAbsolutePath
    )
    val compiled = runJava(root, compilerCp, "dotty.tools.dotc.Main", args)
    IO.write(work / "observer-plugin" / "compiler-arguments.txt", args.mkString("", "\n", "\n"))
    IO.write(work / "observer-plugin" / "compiler.log", compiled.output)
    require(compiled.exit == 0, s"M5A observer plugin did not compile: ${compiled.output}")
    IO.copyFile(descriptor, classes / "plugin.properties")
    val jar = work / "observer-plugin" / s"m5-lifecycle-observer_3-$scalaVersion.jar"
    IO.zip(Path.allSubpaths(classes).toSeq, jar, Some(0L))
    jar
  }

  private def verifyRestoredTasty(root: File, dir: File, compilerCp: Seq[String], sourceCp: Seq[String]): Unit = {
    val tasty = dir / "classes" / "m5a" / "Fixture.tasty"
    require(tasty.isFile, s"missing successful-run TASTy: $tasty")
    val result = runJava(root, compilerCp, "dotty.tools.dotc.decompiler.Main",
      Seq("-classpath", (sourceCp :+ (dir / "classes").getAbsolutePath).mkString(File.pathSeparator),
        "-color:never", tasty.getAbsolutePath))
    IO.write(dir / "decompiled.log", result.output)
    require(result.exit == 0, result.output)
    val provider = definition(result.output, "provider")
    val allowed = definition(result.output, "allowed")
    val ordinary = definition(result.output, "ordinary")
    require(provider.contains("@scala.annotation.experimental"), provider)
    require(!allowed.contains("experimental") && !allowed.contains("allowExperimental"), allowed)
    require(!ordinary.contains("experimental") && !ordinary.contains("allowExperimental"), ordinary)
  }

  private def writeCompilerSourceAudit(work: File, scalaVersion: String,
      compilerCp: Seq[File], pluginJar: File): Unit = {
    val compilerName = s"scala3-compiler_3-$scalaVersion.jar"
    val compiler = compilerCp.find(_.getName == compilerName).getOrElse(
      throw new IllegalArgumentException(s"missing exact Scala $scalaVersion compiler jar"))
    val sources = new File(compiler.getParentFile, s"scala3-compiler_3-$scalaVersion-sources.jar")
    require(sources.isFile, s"missing exact compiler sources: $sources")
    IO.write(work / "compiler-source-audit.txt", Seq(
      s"compilerJar=${compiler.getCanonicalPath}",
      s"compilerJarSha256=${sha256(compiler)}",
      s"compilerSources=${sources.getCanonicalPath}",
      s"compilerSourcesSha256=${sha256(sources)}",
      s"allowPluginJar=${pluginJar.getCanonicalPath}",
      s"allowPluginJarSha256=${sha256(pluginJar)}",
      "driverProcessTopology=fresh ContextBase and Compiler per process call",
      "supportedHarnessTopology=one ContextBase plus one Compiler plus repeated Compiler.newRun",
      "pluginLifetime=ContextBase caches one StandardPlugin instance",
      s"phaseStateLifetime=addPluginPhases invokes ${if (scalaVersion == "3.3.8") "init" else "initialize"} per run; Allow phases() allocates one fresh CompilationState and phase trio",
      "reporterLifetime=fresh StoreReporter per logical run",
      "contextReset=Compiler.newRun calls ContextBase.reset before allocating Run",
      "phaseReset=each Run calls addPluginPhases and usePhases with newly initialized plugin phases"
    ).mkString("", "\n", "\n"))
  }

  private def values(lines: Seq[String], key: String): Seq[String] =
    lines.map { line =>
      line.split("\\|", -1).find(_.startsWith(key + "=")).map(_.substring(key.length + 1)).getOrElse(
        throw new IllegalArgumentException(s"missing $key in $line"))
    }

  private def observation(log: String, key: String): String =
    log.linesIterator.find(_.startsWith(key + "=")).map(_.substring(key.length + 1)).getOrElse(
      throw new IllegalArgumentException(s"missing $key observation: $log"))

  private def definition(output: String, name: String): String =
    output.linesIterator.find(_.contains(s"def $name(")).getOrElse(
      throw new IllegalArgumentException(s"missing definition $name: $output"))

  private def runJava(root: File, cp: Seq[String], main: String, args: Seq[String]): Compilation = {
    val java = new File(sys.props("java.home"), "bin/java").getAbsolutePath
    val output = new StringBuilder
    val exit = Process(Seq(java, "-cp", cp.mkString(File.pathSeparator), main) ++ args, root).!(
      ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n')))
    Compilation(exit, output.result())
  }

  private def sha256(file: File): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(file.toPath)
    try {
      val buffer = new Array[Byte](8192)
      var read = input.read(buffer)
      while (read >= 0) {
        if (read > 0) digest.update(buffer, 0, read)
        read = input.read(buffer)
      }
    } finally input.close()
    digest.digest().map(b => f"${b & 0xff}%02x").mkString
  }

  private val sameJvmHarnessSource = """import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import dotty.tools.dotc.{Compiler, Driver}
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Phases
import dotty.tools.dotc.reporting.StoreReporter

object SameJvmHarness:
  final class HarnessDriver extends Driver

  private def identity(value: AnyRef): String =
    Integer.toHexString(System.identityHashCode(value))

  private def phaseIdentity(phases: Array[Phases.Phase], name: String): String =
    phases.find(_.phaseName == name).map(phase => identity(phase)).getOrElse("MISSING")

  def main(args: Array[String]): Unit =
    require(args.length == 4, s"expected plugin, observer, classpath and work arguments: ${args.toList}")
    val allowPlugin = args(0)
    val observerPlugin = args(1)
    val sourceClasspath = args(2)
    val work = File(args(3))
    val driver = HarnessDriver()
    val base = ContextBase()
    val compiler = Compiler()
    val identities = Vector.newBuilder[String]

    for index <- 1 to 8 do
      val prefix = f"run-$index%02d-"
      val runDir = work.listFiles.nn.find(_.getName.startsWith(prefix)).getOrElse(
        throw IllegalArgumentException(s"missing run directory $prefix"))
      val source = File(runDir, "Fixture.scala")
      val classes = File(runDir, "classes")
      val observerLog = File(runDir, "observer.log")
      val reporter = StoreReporter(null)
      val initial = base.initialCtx.fresh.setReporter(reporter)
      val compilerArgs = Array(
        "-classpath", sourceClasspath,
        "-d", classes.getAbsolutePath,
        s"-Xplugin:$allowPlugin",
        s"-Xplugin:$observerPlugin",
        "-Xplugin-require:allow-experimental",
        "-P:m4a-observer:probe=p3",
        "-P:m4a-observer:provider=m5a.Fixture$.provider",
        "-P:m4a-observer:ordinary=m5a.Fixture$.ordinary",
        s"-P:m4a-observer:log=${observerLog.getAbsolutePath}",
        "-color:never",
        source.getAbsolutePath
      )
      Files.writeString(File(runDir, "compiler-arguments.txt").toPath,
        compilerArgs.mkString("", "\n", "\n"), StandardCharsets.UTF_8)
      val (files, compileCtx) = driver.setup(compilerArgs, initial).getOrElse(
        throw IllegalArgumentException(s"setup failed for $runDir"))
      val run = compiler.newRun(using compileCtx)
      run.compile(files)
      val runCtx = run.runContext
      val phases = Phases.unfusedPhases(using runCtx)
      val plugins = runCtx.base.plugins(using runCtx)
      val allowPluginInstance = plugins.find(_.name == "allow-experimental").getOrElse(
        throw IllegalArgumentException("Allow Experimental plugin missing"))
      val diagnostics = reporter.allErrors.map(_.message)
      Files.writeString(File(runDir, "diagnostics.txt").toPath,
        diagnostics.mkString("", "\n", if diagnostics.isEmpty then "" else "\n"), StandardCharsets.UTF_8)
      identities += List(
        s"R$index",
        s"driver=${identity(driver)}",
        s"base=${identity(base)}",
        s"compiler=${identity(compiler)}",
        s"allowPlugin=${identity(allowPluginInstance)}",
        s"run=${identity(run)}",
        s"runId=${runCtx.runId}",
        s"reporter=${identity(reporter)}",
        s"capturePhase=${phaseIdentity(phases, "allowExperimentalCaptureOwners")}",
        s"checkPhase=${phaseIdentity(phases, "allowExperimentalCheckReferences")}",
        s"restorePhase=${phaseIdentity(phases, "allowExperimentalRestoreProviders")}"
      ).mkString("|")

    Files.writeString(File(work, "same-jvm-identities.txt").toPath,
      identities.result().mkString("", "\n", "\n"), StandardCharsets.UTF_8)
    println("SAME_JVM_HARNESS_PASS")
"""
}
