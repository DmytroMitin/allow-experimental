import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

import sbt._

import scala.sys.process.{Process, ProcessLogger}

object M5CVerifier {
  private val Lanes = Seq("3.3.8", "3.8.4", "3.9.0")

  private final case class Compilation(exit: Int, output: String)

  def verify(root: File, scalaVersion: String, log: Logger): Unit = {
    require(scalaVersion == "3.9.0", s"the aggregate M5C gate runs only from exact Scala 3.9.0, not $scalaVersion")

    Lanes.foreach { lane =>
      val laneKey = lane.replace('.', '_')
      val work = VerificationLane.workDirectory(root, lane, "m5c")
      val persistent = requiredProperties(work / "persistent-sbt-summary.txt")
      val bsp = requiredProperties(work / "bsp-summary.txt")

      Seq(
        "PERSISTENT_SBT_SESSION",
        "PERSISTENT_SBT_FAILURE_RECOVERY",
        "PERSISTENT_SBT_PROVIDER_BECOMES_EXPERIMENTAL_INVALIDATES_ALLOWED",
        "PERSISTENT_SBT_PERMISSION_REPAIR",
        "PERSISTENT_SBT_SIBLING_ISOLATION",
        "PERSISTENT_SBT_DOWNSTREAM_WITHOUT_ALLOW_ARTIFACTS"
      ).foreach(prefix => requireValue(persistent, s"${prefix}_$laneKey", "PASS"))
      requireValue(persistent, s"PERSISTENT_SBT_SINGLE_PROCESS_$laneKey", "YES")
      requireValue(persistent, s"PERSISTENT_SBT_NOOP_$laneKey", "YES")

      Seq(
        "BSP_SESSION",
        "BSP_FAILURE_RECOVERY",
        "BSP_PROVIDER_BECOMES_EXPERIMENTAL_INVALIDATES_ALLOWED",
        "BSP_PERMISSION_REPAIR",
        "BSP_SIBLING_ISOLATION",
        "BSP_STABLE_NO_CHANGE",
        "BSP_DOWNSTREAM_WITHOUT_ALLOW_ARTIFACTS",
        "BSP_BUILD_SEMANTICS"
      ).foreach(prefix => requireValue(bsp, s"${prefix}_$laneKey", "PASS"))
      requireValue(bsp, s"BSP_REAL_SBT_SERVER_$laneKey", "YES")
      requireValue(bsp, s"BSP_SINGLE_SERVER_PROCESS_$laneKey", "YES")
      requireValue(bsp, s"BSP_INITIALIZE_ONCE_$laneKey", "YES")

      verifyChecksums(work / "m5c-evidence.sha256")
      verifyChecksums(work / "artifact-inputs.sha256")
      verifySessionIdentity(work / "persistent-sbt" / "evidence", "process")
      verifySessionIdentity(work / "bsp" / "evidence", "server-process")
      verifyJvmIdentity(work / "persistent-sbt" / "fixture" / "m5c-identities.txt", lane)
      verifyJvmIdentity(work / "bsp" / "fixture" / "m5c-identities.txt", lane)
      verifyBspTraffic(work / "bsp" / "evidence" / "jsonrpc.jsonl")
      verifyNoAllowClasspath(work)
      verifySourceAudit(work / "source-audit.txt", lane)
      Seq("persistent-sbt", "bsp").foreach(surface => verifyFinalOutputs(root, lane, work, surface))
    }

    val retained = requiredProperties(VerificationLane.workDirectory(root, "3.9.0", "m5b") / "summary.txt")
    requireValue(retained, "PROMPT_013_M5B", "PASS")
    requireValue(retained, "WRONG_LANE_PLUGIN_PERMISSION_GRANTED", "NO")
    requireValue(retained, "M0_M1_M3_ALL_LANES_REGRESSION", "PASS")
    requireValue(retained, "M4_RETAINED_REGRESSION", "PASS")
    requireValue(retained, "LANE_ISOLATION_AND_PRESERVATION", "PASS")

    val regressions = requiredProperties(root / "target" / "verification-logs" / "m5c-regression-summary.txt")
    Seq("M5A_M5B_RETAINED", "M0_M1_M3_ALL_LANES_REGRESSION", "M4_RETAINED_REGRESSION",
      "LANE_ISOLATION_AND_PRESERVATION").foreach(requireValue(regressions, _, "PASS"))
    requireValue(regressions, "WRONG_LANE_PLUGIN_PERMISSION_GRANTED", "NO")
    requireValue(regressions, "PEER_REPOSITORIES_BUILT_FOR_PROMPT_014", "NO")

    val summary = VerificationLane.workDirectory(root, "3.9.0", "m5c") / "summary.txt"
    IO.write(summary, Seq(
      "PROMPT_014_M5C=PASS",
      "PERSISTENT_SBT_SESSION_3_3_8=PASS",
      "PERSISTENT_SBT_SESSION_3_8_4=PASS",
      "PERSISTENT_SBT_SESSION_3_9_0=PASS",
      "PERSISTENT_SBT_SINGLE_PROCESS_ALL=YES",
      "PERSISTENT_SBT_FAILURE_RECOVERY_ALL=PASS",
      "PERSISTENT_SBT_PROVIDER_BECOMES_EXPERIMENTAL_INVALIDATES_ALLOWED_ALL=PASS",
      "PERSISTENT_SBT_PERMISSION_REPAIR_ALL=PASS",
      "PERSISTENT_SBT_SIBLING_ISOLATION_ALL=PASS",
      "PERSISTENT_SBT_NOOP_ALL=YES",
      "PERSISTENT_SBT_DOWNSTREAM_WITHOUT_ALLOW_ARTIFACTS_ALL=PASS",
      "BSP_SESSION_3_3_8=PASS",
      "BSP_SESSION_3_8_4=PASS",
      "BSP_SESSION_3_9_0=PASS",
      "BSP_REAL_SBT_SERVER_ALL=YES",
      "BSP_SINGLE_SERVER_PROCESS_ALL=YES",
      "BSP_INITIALIZE_ONCE_ALL=YES",
      "BSP_FAILURE_RECOVERY_ALL=PASS",
      "BSP_PROVIDER_BECOMES_EXPERIMENTAL_INVALIDATES_ALLOWED_ALL=PASS",
      "BSP_PERMISSION_REPAIR_ALL=PASS",
      "BSP_SIBLING_ISOLATION_ALL=PASS",
      "BSP_STABLE_NO_CHANGE_ALL=PASS",
      "BSP_DOWNSTREAM_WITHOUT_ALLOW_ARTIFACTS_ALL=PASS",
      "BSP_BUILD_SEMANTICS_3_3_8=PASS",
      "BSP_BUILD_SEMANTICS_3_8_4=PASS",
      "BSP_BUILD_SEMANTICS_3_9_0=PASS",
      "BSP_BUILD_SEMANTICS_ALL=PASS",
      "INTELLIJ_EDITOR_TYPECHECKING=UNQUALIFIED",
      "INTELLIJ_INSPECTIONS=UNQUALIFIED",
      "PRESENTATION_COMPILER_EDITOR_SEMANTICS=OUT_OF_SCOPE",
      "M5A_M5B_RETAINED=PASS",
      "WRONG_LANE_PLUGIN_PERMISSION_GRANTED=NO",
      "M0_M1_M3_ALL_LANES_REGRESSION=PASS",
      "M4_RETAINED_REGRESSION=PASS",
      "LANE_ISOLATION_AND_PRESERVATION=PASS",
      "M5C_CONTROLLER_RECOMMENDATION=ACCEPT_WITH_QUALIFICATIONS",
      "M5_CONTROLLER_RECOMMENDATION=ACCEPT_WITH_QUALIFICATIONS",
      "M5_COMPLETE_IMPLEMENTATION_EVIDENCE=YES",
      "M6_STARTED=NO",
      "PEER_REPOSITORIES_MODIFIED=NO",
      "PEER_REPOSITORIES_BUILT_FOR_PROMPT_014=NO",
      "RELEASE_AUTHORIZED=NO"
    ).mkString("", "\n", "\n"))
    log.info("M5C PASS: persistent sbt and real BSP lifecycle on exact Scala 3.3.8, 3.8.4, and 3.9.0")
  }

  private def verifySessionIdentity(evidence: File, prefix: String): Unit = {
    val start = evidence / s"$prefix-start.json"
    val end = evidence / s"$prefix-end.json"
    require(start.isFile && end.isFile, s"missing process identity evidence under $evidence")
    require(IO.read(start) == IO.read(end), s"process identity changed under $evidence")
  }

  private def verifyJvmIdentity(file: File, lane: String): Unit = {
    val identities = IO.readLines(file)
    require(identities.size == 2 && identities.head == identities.last, s"JVM identity changed: $file")
    require(identities.head.contains(s"scalaVersion=$lane"), s"wrong JVM lane identity: ${identities.head}")
    require(identities.head.contains("sbtVersion=1.11.7"), s"wrong sbt identity: ${identities.head}")
  }

  private def verifyBspTraffic(file: File): Unit = {
    val traffic = IO.read(file)
    require(count(traffic, "\"method\": \"build/initialize\"") == 1, s"BSP initialize count was not one: $file")
    require(count(traffic, "\"method\": \"buildTarget/compile\"") == 9, s"BSP compile request count was not nine: $file")
    require(traffic.contains("build/publishDiagnostics") && traffic.contains("marked @experimental"),
      s"missing BSP rejection diagnostics: $file")
    require(traffic.contains("\"method\": \"build/shutdown\"") &&
      traffic.contains("\"method\": \"build/exit\""), s"missing BSP shutdown/exit: $file")
  }

  private def count(text: String, needle: String): Int =
    text.sliding(needle.length).count(_ == needle)

  private def verifyNoAllowClasspath(work: File): Unit = {
    val inputs = IO.read(work / "persistent-sbt" / "fixture" / "m5c-inputs.txt")
    inputs.linesIterator.filter(line => line.startsWith("consumerClasspath=") ||
      line.startsWith("freshDownstreamClasspath=")).foreach(line =>
      require(!line.contains("allow-experimental-annotation") && !line.contains("allow-experimental-plugin"), line))
    val options = IO.read(work / "bsp" / "evidence" / "scalac-options.json")
    require(!options.contains("allow-experimental-annotation") && !options.contains("allow-experimental-plugin"),
      s"BSP downstream classpath leaked Allow artifacts: $options")
  }

  private def verifySourceAudit(file: File, lane: String): Unit = {
    val audit = IO.read(file)
    Seq("sbtVersion=1.11.7", "zincVersion=1.11.0", s"scalaVersion=$lane", "bspVersion=2.1.0-M1",
      "persistentSbtProcess=one JVM for all lane transitions",
      "scala3CompilerLifetime=fresh CompilerBridgeDriver per compile invocation",
      "bspTransport=stdio proxy to the task-local sbt Unix socket server",
      "bspCompile=ordinary sbt compileIncremental task with diagnostics",
      "bspShutdown=build/shutdown then build/exit then client stdin close").foreach(fragment =>
      require(audit.contains(fragment), s"source audit missing '$fragment': $file"))
    val hashes = audit.linesIterator.filter(_.startsWith("sha256=")).toSeq
    require(hashes.size >= 5, s"source audit lacks hashes: $file")
    hashes.foreach { line =>
      val separator = line.indexOf(" path=")
      require(separator > "sha256=".length, s"malformed source hash: $line")
      val expected = line.substring("sha256=".length, separator)
      val input = new File(line.substring(separator + " path=".length))
      require(input.isFile && sha256(input) == expected, s"source hash mismatch or missing input: $input")
    }
  }

  private def verifyFinalOutputs(root: File, lane: String, work: File, surface: String): Unit = {
    val fixture = work / surface / "fixture"
    def classes(project: String): File = fixture / project / "target" / s"scala-$lane" / "classes"
    val outputs = Seq("provider", "allowed", "consumer", "fresh-downstream").map(classes)
    outputs.foreach { output =>
      val analysis = output.getParentFile / "zinc" / "inc_compile_3.zip"
      require(analysis.isFile && analysis.length() > 0, s"missing M5C Zinc analysis: $analysis")
    }

    val priorGate = if (lane == "3.9.0") "m5a" else "m5b"
    val inputs = IO.readLines(VerificationLane.workDirectory(root, lane, priorGate) / "lane-inputs.txt")
    val compilerCp = inputs.collect {
      case line if line.startsWith("compiler-classpath=") => new File(line.substring("compiler-classpath=".length))
    }
    val annotation = root / "annotation" / "target" / s"scala-$lane" /
      "allow-experimental-annotation_3-0.1.0-SNAPSHOT.jar"
    val libraries = compilerCp.filter(file => file.getName.startsWith("scala3-library_3-") ||
      file.getName.startsWith("scala-library-"))
    val decompilerCp = (libraries ++ Seq(annotation) ++ outputs).map(_.getCanonicalPath).distinct
    val targets = Seq(
      (outputs(0) / "m5cfixture" / "Provider.tasty", "provider", true),
      (outputs(1) / "m5cfixture" / "Allowed.tasty", "allowed", false),
      (outputs(2) / "m5cfixture" / "Consumer.tasty", "use", false),
      (outputs(3) / "m5cfixture" / "FreshDownstream.tasty", "freshUse", false)
    )
    targets.foreach { case (tasty, name, experimental) =>
      require(tasty.isFile, s"missing final M5C TASTy: $tasty")
      val result = runJava(root, compilerCp.map(_.getCanonicalPath), "dotty.tools.dotc.decompiler.Main",
        Seq("-classpath", decompilerCp.mkString(File.pathSeparator), "-color:never", tasty.getCanonicalPath))
      IO.write(work / surface / s"final-$name-decompiled.log", result.output)
      require(result.exit == 0, result.output)
      val line = result.output.linesIterator.find(_.contains(s"def $name(")).getOrElse(
        throw new IllegalArgumentException(s"missing definition $name: ${result.output}"))
      if (experimental) require(line.contains("@scala.annotation.experimental"), line)
      else require(!line.contains("experimental") && !line.contains("allowExperimental"), line)
    }
  }

  private def requiredProperties(file: File): Map[String, String] = {
    require(file.isFile, s"missing required M5C evidence: $file")
    val entries = IO.readLines(file).filter(_.contains("=")).map { line =>
      val split = line.indexOf('=')
      line.substring(0, split) -> line.substring(split + 1)
    }
    val duplicates = entries.groupBy(_._1).collect { case (key, values) if values.size > 1 => key }.toSeq
    require(duplicates.isEmpty, s"duplicate keys in $file: ${duplicates.mkString(", ")}")
    entries.toMap
  }

  private def requireValue(properties: Map[String, String], key: String, expected: String): Unit =
    require(properties.get(key).contains(expected),
      s"expected $key=$expected, observed ${properties.get(key).getOrElse("MISSING")}")

  private def verifyChecksums(manifest: File): Unit = {
    require(manifest.isFile, s"missing checksum manifest: $manifest")
    IO.readLines(manifest).filter(_.nonEmpty).foreach { line =>
      val separator = line.indexOf("  ")
      require(separator > 0, s"malformed checksum entry: $line")
      val expected = line.substring(0, separator)
      val file = new File(line.substring(separator + 2))
      require(file.isFile && sha256(file) == expected, s"checksum mismatch or missing evidence: $file")
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
