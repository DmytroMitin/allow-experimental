import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

import sbt._

import scala.sys.process.{Process, ProcessLogger}

object M5BVerifier {
  private val OlderLanes = Seq("3.3.8", "3.8.4")
  private val Topology =
    "ONE_CONTEXT_BASE_ONE_COMPILER_ONE_PLUGIN_FRESH_RUN_PHASE_STATE_REPORTER"
  private val FailClosedClassifications = Set(
    "EXACT_VERSION_GUARD_REJECTION",
    "PLUGIN_LOAD_REJECTION",
    "OTHER_FAIL_CLOSED_REJECTION"
  )

  private final case class Compilation(exit: Int, output: String)

  def verify(root: File, scalaVersion: String, log: Logger): Unit = {
    require(scalaVersion == "3.9.0", s"the aggregate M5B gate runs only from exact Scala 3.9.0, not $scalaVersion")

    OlderLanes.foreach { lane =>
      val laneKey = lane.replace('.', '_')
      val work = VerificationLane.workDirectory(root, lane, "m5b")
      val sameJvm = requiredProperties(work / "same-jvm-summary.txt")
      val zinc = requiredProperties(work / "zinc" / "zinc-summary.txt")

      requireValue(sameJvm, s"SCALA_$laneKey", "PASS")
      requireValue(sameJvm, "SAME_JVM_REPEATED_RUNS", "PASS")
      requireValue(sameJvm, "SAME_JVM_SUPPORTED_REUSE_TOPOLOGY", Topology)
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
        "INCREMENTAL_PERMISSION_ADD_REMOVE_REPAIR",
        "INCREMENTAL_SIBLING_NEGATIVE_REPAIR",
        "INCREMENTAL_PROVIDER_TOGGLE",
        "DOWNSTREAM_WITHOUT_ALLOW_PLUGIN",
        "DOWNSTREAM_WITHOUT_ALLOW_MARKER",
        "PLUGIN_ABSENT_PERMISSION_SAFETY"
      ).foreach(prefix => requireValue(zinc, s"${prefix}_$laneKey", "PASS"))
      requireValue(zinc, s"ZINC_NOOP_OBSERVED_$laneKey", "YES")
      requireValue(zinc, "WRONG_LANE_PLUGIN_PERMISSION_GRANTED", "NO")
      requireValue(zinc, "PUBLIC_PLUGIN_BINARY_COMPATIBILITY_CLAIMED", "NO")
      requireValue(zinc, "PUBLIC_MARKER_CROSS_LANE_COMPATIBILITY_CLAIMED", "NO")
      requireValue(zinc, "PERSISTENT_SBT_SERVER_CLAIMED", "NO")
      requireValue(zinc, "GLOBAL_EXPERIMENTAL_REQUIRED", "NO")

      val mismatchEntries = zinc.filter { case (key, _) => key.startsWith("WRONG_LANE_PLUGIN_") && key != "WRONG_LANE_PLUGIN_PERMISSION_GRANTED" }
      require(mismatchEntries.nonEmpty, s"missing mismatch classifications for Scala $lane")
      mismatchEntries.foreach { case (key, value) =>
        require(FailClosedClassifications(value), s"$key did not fail closed: $value")
      }

      verifyChecksums(work / "zinc" / "zinc-evidence.sha256")
      verifyChecksums(work / "zinc" / "evidence" / "artifact-inputs.sha256")
      verifyFinalZincOutputs(root, lane, work)
      verifySourceAudits(lane, work)
    }

    val mismatch38 = requiredProperties(
      VerificationLane.workDirectory(root, "3.8.4", "m5b") / "zinc" / "zinc-summary.txt")
    val plugin39On38 = requireFailClosed(mismatch38, "WRONG_LANE_PLUGIN_3_9_0_ON_3_8_4")
    val plugin33On38 = requireFailClosed(mismatch38, "WRONG_LANE_PLUGIN_3_3_8_ON_3_8_4")
    val mismatch33 = requiredProperties(
      VerificationLane.workDirectory(root, "3.3.8", "m5b") / "zinc" / "zinc-summary.txt")
    val plugin38On33 = requireFailClosed(mismatch33, "WRONG_LANE_PLUGIN_3_8_4_ON_3_3_8")

    val retained = requiredProperties(
      VerificationLane.workDirectory(root, "3.9.0", "m5a") / "summary.txt")
    requireValue(retained, "PROMPT_012_M5A", "PASS")
    requireValue(retained, "SCALA_3_9_0", "PASS")
    requireValue(retained, "SAME_JVM_REPEATED_RUNS", "PASS")
    requireValue(retained, "SBT_ZINC_MULTIPROJECT", "PASS")
    requireValue(retained, "WRONG_LANE_PLUGIN", "REJECTED_FAIL_CLOSED")

    val regressions = requiredProperties(root / "target" / "verification-logs" / "m5b-regression-summary.txt")
    Seq(
      "SCALA_3_3_8_CORE", "SCALA_3_8_4_CORE", "SCALA_3_9_0_CORE",
      "M4C_SCALA_3_3_8", "M4C_SCALA_3_8_4", "M4A_SCALA_3_9_0", "M4B_SCALA_3_9_0",
      "M5A_SCALA_3_9_0", "LANE_ISOLATION_AND_PRESERVATION"
    ).foreach(requireValue(regressions, _, "PASS"))
    requireValue(regressions, "READ_ONLY_PEERS_BUILT_FOR_PROMPT_013", "NO")

    val summary = VerificationLane.workDirectory(root, "3.9.0", "m5b") / "summary.txt"
    IO.createDirectory(summary.getParentFile)
    IO.write(summary, Seq(
      "PROMPT_013_M5B=PASS",
      "SCALA_3_3_8_LIFECYCLE=PASS",
      "SCALA_3_8_4_LIFECYCLE=PASS",
      "SCALA_3_9_0_M5A_RETAINED=PASS",
      "SAME_JVM_REPEATED_RUNS_3_3_8=PASS",
      "SAME_JVM_REPEATED_RUNS_3_8_4=PASS",
      "SAME_JVM_SUCCESS_FAILURE_RECOVERY_OLDER_LANES=PASS",
      "RUN_STATE_LEAK_OLDER_LANES=NO",
      "BODY_REFERENCE_STATE_LEAK_OLDER_LANES=NO",
      "PROVIDER_STATE_RESTORED_BETWEEN_RUNS_OLDER_LANES=YES",
      "REPORTER_ERROR_LEAK_BETWEEN_RUNS_OLDER_LANES=NO",
      "SBT_ZINC_MULTIPROJECT_3_3_8=PASS",
      "SBT_ZINC_MULTIPROJECT_3_8_4=PASS",
      "SBT_NON_CLEAN_SEQUENCE_OLDER_LANES=PASS",
      "ZINC_NOOP_OBSERVED_OLDER_LANES=YES",
      "INCREMENTAL_PROVIDER_BECOMES_EXPERIMENTAL_INVALIDATES_ALLOWED_OLDER_LANES=PASS",
      "INCREMENTAL_PERMISSION_ADD_REMOVE_REPAIR_OLDER_LANES=PASS",
      "INCREMENTAL_SIBLING_NEGATIVE_REPAIR_OLDER_LANES=PASS",
      "INCREMENTAL_PROVIDER_TOGGLE_OLDER_LANES=PASS",
      "DOWNSTREAM_WITHOUT_ALLOW_PLUGIN_OLDER_LANES=PASS",
      "DOWNSTREAM_WITHOUT_ALLOW_MARKER_OLDER_LANES=PASS",
      "PLUGIN_ABSENT_PERMISSION_SAFETY_OLDER_LANES=PASS",
      "WRONG_LANE_PLUGIN_PERMISSION_GRANTED=NO",
      s"WRONG_LANE_PLUGIN_REJECTION_MATRIX=3_8_4_ON_3_3_8=$plugin38On33;3_9_0_ON_3_8_4=$plugin39On38;3_3_8_ON_3_8_4=$plugin33On38;3_8_4_ON_3_9_0=EXACT_VERSION_GUARD_REJECTION_RETAINED",
      "PUBLIC_PLUGIN_BINARY_COMPATIBILITY_CLAIMED=NO",
      "PUBLIC_MARKER_CROSS_LANE_COMPATIBILITY_CLAIMED=NO",
      "M0_M1_M3_ALL_LANES_REGRESSION=PASS",
      "M4_RETAINED_REGRESSION=PASS",
      "M5A_3_9_0_REGRESSION=PASS",
      "LANE_ISOLATION_AND_PRESERVATION=PASS",
      "PERSISTENT_SBT_SERVER=NOT_TESTED",
      "BSP=NOT_TESTED",
      "IDE=NOT_TESTED",
      "M5B_CONTROLLER_RECOMMENDATION=ACCEPT_WITH_QUALIFICATIONS",
      "M5_COMPLETE=NO",
      "M5C_STARTED=NO",
      "PEER_REPOSITORIES_MODIFIED=NO",
      "PEER_REPOSITORIES_BUILT_FOR_PROMPT_013=NO",
      "RELEASE_AUTHORIZED=NO"
    ).mkString("", "\n", "\n"))
    log.info("M5B PASS: exact 3.3.8/3.8.4 same-JVM and batch Zinc lifecycle, mismatch safety, and retained 3.9.0 M5A")
  }

  private def verifyFinalZincOutputs(root: File, lane: String, work: File): Unit = {
    val fixture = work / "zinc" / "fixture"
    def classes(project: String): File = fixture / project / "target" / s"scala-$lane" / "classes"
    val provider = classes("provider")
    val allowed = classes("allowed")
    val consumer = classes("consumer")
    val downstream = classes("fresh-downstream")
    Seq(provider, allowed, consumer, downstream).foreach { output =>
      val analysis = output.getParentFile / "zinc" / "inc_compile_3.zip"
      require(analysis.isFile && analysis.length() > 0, s"missing persisted Zinc analysis: $analysis")
    }

    val inputs = IO.readLines(work / "lane-inputs.txt")
    val compilerCp = inputs.collect {
      case line if line.startsWith("compiler-classpath=") => new File(line.substring("compiler-classpath=".length))
    }
    val annotation = inputs.collectFirst {
      case line if line.startsWith("annotation=") => new File(line.substring("annotation=".length))
    }.getOrElse(throw new IllegalArgumentException(s"missing annotation input for $lane"))
    val libraries = compilerCp.filter(file =>
      file.getName.startsWith("scala3-library_3-") || file.getName.startsWith("scala-library-"))
    val decompilerCp = (libraries ++ Seq(annotation, provider, allowed, consumer, downstream))
      .map(_.getCanonicalPath).distinct
    val targets = Seq(
      (provider / "m5bzinc" / "Provider.tasty", "provider", true),
      (allowed / "m5bzinc" / "Allowed.tasty", "allowed", false),
      (consumer / "m5bzinc" / "Consumer.tasty", "use", false),
      (downstream / "m5bzinc" / "FreshDownstream.tasty", "freshUse", false)
    )
    targets.foreach { case (tasty, name, experimental) =>
      require(tasty.isFile, s"missing final Zinc TASTy: $tasty")
      val result = runJava(root, compilerCp.map(_.getCanonicalPath), "dotty.tools.dotc.decompiler.Main",
        Seq("-classpath", decompilerCp.mkString(File.pathSeparator), "-color:never", tasty.getCanonicalPath))
      IO.write(work / "zinc" / s"final-$name-decompiled.log", result.output)
      require(result.exit == 0, result.output)
      val line = result.output.linesIterator.find(_.contains(s"def $name(")).getOrElse(
        throw new IllegalArgumentException(s"missing definition $name: ${result.output}"))
      if (experimental) require(line.contains("@scala.annotation.experimental"), line)
      else require(!line.contains("experimental") && !line.contains("allowExperimental"), line)
    }

    val fixtureInputs = IO.read(work / "zinc" / "evidence" / "m5b-inputs.txt")
    fixtureInputs.linesIterator.filter(line =>
      line.startsWith("consumerClasspath=") || line.startsWith("freshDownstreamClasspath="))
      .foreach(line => require(!line.contains("allow-experimental-annotation") &&
        !line.contains("allow-experimental-plugin"), line))
  }

  private def verifySourceAudits(lane: String, work: File): Unit = {
    val compilerAudit = IO.read(work / "compiler-source-audit.txt")
    require(compilerAudit.contains(s"scala3-compiler_3-$lane-sources.jar"), compilerAudit)
    require(compilerAudit.contains("supportedHarnessTopology=one ContextBase plus one Compiler plus repeated Compiler.newRun"), compilerAudit)
    require(compilerAudit.contains("phaseStateLifetime=addPluginPhases invokes initialize per run") ||
      compilerAudit.contains("phaseStateLifetime=addPluginPhases invokes init per run"), compilerAudit)
    val zincAudit = IO.read(work / "zinc" / "zinc-source-audit.txt")
    require(zincAudit.contains(s"scalaVersion=$lane"), zincAudit)
    require(zincAudit.contains(s"scala3-sbt-bridge-$lane-sources.jar"), zincAudit)
    require(zincAudit.contains("CompilerBridge.run constructs one fresh CompilerBridgeDriver per compile invocation"), zincAudit)
  }

  private def requiredProperties(file: File): Map[String, String] = {
    require(file.isFile, s"missing required M5B evidence: $file")
    val entries = IO.readLines(file).filter(_.contains("=")).map { line =>
      val split = line.indexOf('=')
      line.substring(0, split) -> line.substring(split + 1)
    }
    val duplicateKeys = entries.groupBy(_._1).collect { case (key, values) if values.size > 1 => key }.toSeq.sorted
    require(duplicateKeys.isEmpty, s"duplicate classification keys in $file: ${duplicateKeys.mkString(", ")}")
    entries.toMap
  }

  private def requireValue(properties: Map[String, String], key: String, expected: String): Unit =
    require(properties.get(key).contains(expected),
      s"expected $key=$expected, observed ${properties.get(key).getOrElse("MISSING")}")

  private def requireFailClosed(properties: Map[String, String], key: String): String = {
    val observed = properties.getOrElse(key, "MISSING")
    require(FailClosedClassifications(observed),
      s"expected fail-closed classification for $key, observed $observed")
    observed
  }

  private def verifyChecksums(manifest: File): Unit = {
    require(manifest.isFile, s"missing checksum manifest: $manifest")
    IO.readLines(manifest).filter(_.nonEmpty).foreach { line =>
      val separator = line.indexOf("  ")
      require(separator > 0, s"malformed checksum entry: $line")
      val expected = line.substring(0, separator)
      val file = new File(line.substring(separator + 2))
      require(file.isFile, s"missing checksummed evidence: $file")
      require(sha256(file) == expected, s"checksum mismatch: $file")
    }
  }

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
}
