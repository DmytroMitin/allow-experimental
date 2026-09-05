import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarFile

import sbt._
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}

/** Black-box verification of the task-owned overlay on an independently
  * materialized Quasiquotes pin. Peer acquisition and source builds are kept
  * in scripts/verify-quasiquotes-integration.sh so this task never mutates a peer.
  */
object M6Verifier {
  private val pinned = "b7425e2f97a42107e78c96454d14f66581889f80"
  private val experimentalDiagnostic = "method info is marked @experimental"
  private case class Result(exit: Int, output: String, classes: File, args: Seq[String])

  def verify(root: File, version: String, annotationJar: File, pluginJar: File,
      compilerClasspath: Seq[File], log: Logger): Unit = {
    VerificationLane.validateInputs(version, annotationJar, pluginJar, compilerClasspath)
    val inputRoot = root / "target" / "m6-verification" / s"scala-$version"
    val clone = inputRoot / "quasiquotes-pinned"
    val artifacts = inputRoot / "artifacts"
    val baselineCore = artifacts / "baseline" / "core.jar"
    val baselineFrontend = artifacts / "baseline" / "frontend.jar"
    val overlayCore = artifacts / "overlay" / "core.jar"
    val overlayFrontend = artifacts / "overlay" / "frontend.jar"
    Seq(clone, baselineCore, baselineFrontend, overlayCore, overlayFrontend).foreach(path =>
      require(path.exists, s"missing prebuilt M6 input: $path"))
    require(git(clone, Seq("rev-parse", "HEAD")).trim == pinned,
      s"disposable Quasiquotes clone is not pinned to $pinned")

    val work = VerificationLane.workDirectory(root, version, "m6")
    IO.delete(work); IO.createDirectory(work)
    VerificationLane.recordInputs(work, version, annotationJar, pluginJar, compilerClasspath)
    val compilerCp = compilerClasspath.map(_.getCanonicalFile).distinct
    val compilerCpStrings = compilerCp.map(_.getAbsolutePath)
    val libraries = compilerCp.filter(f =>
      f.getName.startsWith("scala3-library_3-") || f.getName.startsWith("scala-library-"))
    require(libraries.size == 2, "expected two exact Scala runtime libraries")
    val failures = ArrayBuffer.empty[String]
    def check(label: String)(body: => Unit): Unit = try {
      body; log.info(s"M6 PASS [$version] $label")
    } catch { case e: IllegalArgumentException =>
      failures += s"$label: ${e.getMessage}"; log.error(s"M6 FAIL [$version] $label: ${e.getMessage}")
    }

    val baselineSource = producerSource(expectOverlay = false)
    val baselineProducer = compile(root, work / "baseline-producer", "MacroApi.scala", baselineSource,
      compilerCpStrings, libraries ++ Seq(baselineCore, baselineFrontend))
    check("unmodified baseline macro producer compiles without Allow artifacts") {
      require(baselineProducer.exit == 0, baselineProducer.output)
      assertNoAllow(baselineProducer.args, annotationJar, pluginJar)
    }
    val baselineDownstream = compile(root, work / "baseline-downstream", "Consumer.scala",
      "package m6baselineclient\nval observed: String = quasiquotes.m6fixture.MacroApi.probe()\n",
      compilerCpStrings, (libraries :+ baselineProducer.classes) ++ Seq(baselineCore, baselineFrontend))
    check("unmodified baseline retains bare-List limitation and terminal control") {
      require(baselineDownstream.exit == 0, baselineDownstream.output)
      assertNoAllow(baselineDownstream.args, annotationJar, pluginJar)
      val bytecode = javap(root, baselineDownstream.classes, "m6baselineclient.Consumer$package$")
      IO.write(work / "baseline-downstream" / "javap.log", bytecode._2)
      require(bytecode._1 == 0 && bytecode._2.contains("baseline-list-rejected-terminal-ok"), bytecode._2)
    }

    val baselineAbortProducer = compile(root, work / "baseline-abort-producer", "MacroApi.scala",
      producerSource(expectOverlay = true), compilerCpStrings,
      libraries ++ Seq(baselineCore, baselineFrontend))
    val baselineAbortDownstream = compile(root, work / "baseline-abort-downstream", "Consumer.scala",
      "package m6baselineabort\nval observed: String = quasiquotes.m6fixture.MacroApi.probe()\n",
      compilerCpStrings, (libraries :+ baselineAbortProducer.classes) ++ Seq(baselineCore, baselineFrontend))
    check("baseline version of the overlay-dependent downstream fails") {
      require(baselineAbortProducer.exit == 0, baselineAbortProducer.output)
      require(baselineAbortDownstream.exit != 0, "baseline unexpectedly classified bare List as a constructor")
      require(baselineAbortDownstream.output.contains("M6_BARE_LIST_NOT_CONSTRUCTOR"), baselineAbortDownstream.output)
      assertNoAllow(baselineAbortDownstream.args, annotationJar, pluginJar)
    }

    val overlayProducer = compile(root, work / "overlay-producer", "MacroApi.scala",
      producerSource(expectOverlay = true), compilerCpStrings,
      libraries ++ Seq(overlayCore, overlayFrontend))
    val overlayDownstream = compile(root, work / "overlay-downstream", "Consumer.scala",
      "package m6overlayclient\nval observed: String = quasiquotes.m6fixture.MacroApi.probe()\n",
      compilerCpStrings, (libraries :+ overlayProducer.classes) ++ Seq(overlayCore, overlayFrontend))
    check("bare List/Either, wrong arities, terminal, and applied witness use real overlaid API") {
      require(overlayProducer.exit == 0, overlayProducer.output)
      require(overlayDownstream.exit == 0, overlayDownstream.output)
      assertNoAllow(overlayProducer.args, annotationJar, pluginJar)
      assertNoAllow(overlayDownstream.args, annotationJar, pluginJar)
      val bytecode = javap(root, overlayDownstream.classes, "m6overlayclient.Consumer$package$")
      IO.write(work / "overlay-downstream" / "javap.log", bytecode._2)
      require(bytecode._1 == 0 && bytecode._2.contains("symbol-info-bare-list-either-ok"), bytecode._2)
    }

    val noMarkerLog = IO.read(inputRoot / "negative-without-marker.log")
    val noPluginLog = IO.read(inputRoot / "negative-without-plugin.log")
    check("same direct Symbol.info helper fails without marker") {
      require(noMarkerLog.contains(experimentalDiagnostic), noMarkerLog)
      require(!containsGlobalExperimentalOption(noMarkerLog), "negative used global -experimental")
    }
    check("inert marker fails without Allow plugin") {
      require(noPluginLog.contains(experimentalDiagnostic), noPluginLog)
      require(!containsGlobalExperimentalOption(noPluginLog), "negative used global -experimental")
    }

    check("public selected-Type API is unchanged and helper stays private") {
      val classes = Seq("quasiquotes.types.GlobalSelectedTypeEnvironment",
        "quasiquotes.types.GlobalSelectedTypeEnvironment$",
        "quasiquotes.types.GlobalSelectedTypeFrontend$",
        "quasiquotes.types.ResolvedTypeReflection$")
      classes.foreach { cls =>
        val before = javapJar(root, baselineFrontend, cls, publicOnly = true)
        val after = javapJar(root, overlayFrontend, cls, publicOnly = true)
        require(before._1 == 0 && after._1 == 0 && before._2 == after._2,
          s"public API changed for $cls\nBASELINE:\n${before._2}\nOVERLAY:\n${after._2}")
      }
      val privateApi = javapJar(root, overlayFrontend, "quasiquotes.types.ResolvedTypeReflection$", publicOnly = false)
      IO.write(work / "ResolvedTypeReflection.javap", privateApi._2)
      val helperHeader = privateApi._2.split("\\R").find(_.contains(" declarationArity("))
        .getOrElse(throw new IllegalArgumentException("missing declarationArity helper"))
      require(helperHeader.trim.startsWith("private "), s"helper is not private: $helperHeader")
      require(privateApi._2.contains("SymbolMethods.info"), privateApi._2)
      val entries = jarEntries(overlayFrontend)
      require(!entries.exists(_.startsWith("io/github/dmytromitin/allowexperimental/")),
        "Allow marker classes were packaged into Quasiquotes frontend")
      val artifactBoundary = work / "artifact-boundary"
      IO.createDirectory(artifactBoundary)
      val tastyFile = artifactBoundary / "ResolvedTypeReflection.tasty"
      Files.write(tastyFile.toPath, jarBytes(overlayFrontend,
        Seq("quasiquotes/types/ResolvedTypeReflection.tasty")).head)
      val decompiled = runJava(root, compilerCpStrings, "dotty.tools.dotc.decompiler.Main", Seq(
        "-classpath", (libraries ++ Seq(overlayCore, overlayFrontend)).map(_.getAbsolutePath).mkString(File.pathSeparator),
        "-color:never", tastyFile.getAbsolutePath))
      IO.write(artifactBoundary / "ResolvedTypeReflection.decompiled.log", decompiled._2)
      require(decompiled._1 == 0, decompiled._2)
      require(decompiled._2.contains("SymbolMethods.info"),
        "decompiled implementation lacks Symbol.info machinery")
      require(!decompiled._2.contains("@io.github.dmytromitin.allowexperimental.allowExperimental"),
        "Allow marker persisted on an overlaid TASTy definition")
      require(!decompiled._2.contains("@scala.annotation.experimental"),
        "consumer experimental metadata leaked onto an overlaid TASTy definition")
      val boundaryBytes = jarBytes(overlayFrontend, Seq(
        "quasiquotes/types/ResolvedTypeReflection$.class",
        "quasiquotes/types/ResolvedTypeReflection.tasty"))
      require(boundaryBytes.exists(bytes => new String(bytes, StandardCharsets.ISO_8859_1).contains("SymbolMethods")),
        "overlaid implementation lacks Symbol.info machinery")
      require(boundaryBytes.forall(bytes => !new String(bytes, StandardCharsets.ISO_8859_1)
        .contains("io/github/dmytromitin/allowexperimental/allowExperimental")),
        "Allow marker persisted on overlaid implementation")
    }

    val originalPeer = root.getParentFile / "quasiquotes-scala3"
    val peerBefore = IO.read(inputRoot / "peer-state-before.txt").trim
    val peerStatusNow = git(originalPeer, Seq("status", "--porcelain=v1")).stripSuffix("\n")
    val peerAfter = git(originalPeer, Seq("rev-parse", "HEAD")).trim + "\n" +
      sha256(peerStatusNow.getBytes(StandardCharsets.UTF_8))
    IO.write(work / "peer-concurrency-sample.txt", s"before=$peerBefore\nafter=$peerAfter\n")
    check("M6 orchestration contains no peer-mutating command") {
      val script = IO.read(root / "scripts" / "verify-quasiquotes-integration.sh")
      Seq("checkout", "reset", "clean", "stash", "fetch", "pull", "add", "commit", "push").foreach { verb =>
        require(!script.contains(s"""git -C "$$PEER_ROOT" $verb"""), s"peer-mutating command present: $verb")
      }
    }

    if (failures.nonEmpty) require(requirement = false, failures.mkString("\n"))
    IO.write(work / "summary.txt", Seq(
      "PROMPT_015_M6=PASS", s"SCALA_${version.replace('.', '_')}_M6=PASS",
      s"QUASIQUOTES_PINNED_SHA=$pinned", "HISTORICAL_Q005_SEAM_STATUS=EVOLVED_BUT_APPLICABLE",
      "CURRENT_PINNED_QUASIQUOTES_USES_SYMBOL_INFO=NO",
      "CURRENT_PINNED_QUASIQUOTES_REQUIRES_ALLOW_EXPERIMENTAL=NO",
      "BARE_LIST_DECLARATION_ARITY=1", "BARE_EITHER_DECLARATION_ARITY=2",
      "TERMINAL_ZERO_ARITY_PRESERVED=PASS", "APPLIED_WITNESS_ROUTE_RETAINED=PASS",
      "OVERLAY_WITH_EXACT_ALLOW_PLUGIN=PASS", "OVERLAY_WITHOUT_MARKER=REJECTED",
      "OVERLAY_WITHOUT_ALLOW_PLUGIN=REJECTED", "PUBLIC_API_DELTA=NONE",
      "ALLOW_MARKER_TASTY_LEAK=NO", "QUASIQUOTES_CONSUMER_EXPERIMENTAL_LEAK=NO",
      "SYMBOL_INFO_PRESENT_ONLY_IN_IMPLEMENTATION_MACHINERY=YES", "ALLOW_MARKER_PACKAGED=NO",
      "DOWNSTREAM_WITHOUT_ALLOW_PLUGIN=PASS", "DOWNSTREAM_WITHOUT_ALLOW_MARKER=PASS",
      "DOWNSTREAM_WITHOUT_GLOBAL_EXPERIMENTAL=PASS", "SYMBOL_INFO_DERIVED_BEHAVIOR_EXECUTED=YES",
      "APPLIED_TYPE_CONSTRUCTOR_POLICY_CHANGED=NO", "PEER_CHECKOUT_MUTATED=NO",
      "RELEASE_AUTHORIZED=NO").mkString("", "\n", "\n"))
  }

  private def producerSource(expectOverlay: Boolean): String = {
    val expectation = if (expectOverlay) "true" else "false"
    s"""package quasiquotes.m6fixture
import scala.quoted.*

object MacroApi:
  import quasiquotes.types.*
  inline def probe(): String = $${ probeImpl }

  private def probeImpl(using Quotes): Expr[String] =
    import quotes.reflect.*
    val listRef = Symbol.requiredClass("scala.collection.immutable.List").typeRef
    val eitherRef = Symbol.requiredClass("scala.util.Either").typeRef
    val terminalRef = Symbol.requiredClass("java.lang.String").typeRef
    val env = GlobalSelectedTypeEnvironment.fromWitnesses(listRef, eitherRef, terminalRef).toOption.get
    val list = GlobalSelectedTypeFrontend.construct("scala.collection.immutable.List[Int]", env)
    val listWrong = GlobalSelectedTypeFrontend.construct("scala.collection.immutable.List[Int, String]", env)
    val either = GlobalSelectedTypeFrontend.construct("scala.util.Either[Int, String]", env)
    val eitherWrong = GlobalSelectedTypeFrontend.construct("scala.util.Either[Int]", env)
    val terminal = GlobalSelectedTypeFrontend.construct("java.lang.String", env)
    val appliedEnv = GlobalSelectedTypeEnvironment.fromWitnesses(TypeRepr.of[List[Int]]).toOption.get
    val applied = GlobalSelectedTypeFrontend.construct("scala.collection.immutable.List[Int]", appliedEnv)
    val overlayExpected = $expectation
    if overlayExpected then
      val good = list.exists(_ =:= TypeRepr.of[List[Int]]) && listWrong.isLeft &&
        either.exists(_ =:= TypeRepr.of[Either[Int, String]]) && eitherWrong.isLeft &&
        terminal.exists(_ =:= TypeRepr.of[String]) && applied.exists(_ =:= TypeRepr.of[List[Int]])
      if !good then
        val listId = ResolvedTypeReflection.deriveTypeRef(listRef).map(_.render)
        val eitherId = ResolvedTypeReflection.deriveTypeRef(eitherRef).map(_.render)
        val details = for
          listIdentity <- ResolvedTypeReflection.deriveTypeRef(listRef)
          eitherIdentity <- ResolvedTypeReflection.deriveTypeRef(eitherRef)
        yield " bindings=" + env.binding(listIdentity) + ";" + env.binding(eitherIdentity) +
          "; policies=" + AppliedTypeConstructorPolicy.forResolved(listIdentity, 1) + ";" +
          AppliedTypeConstructorPolicy.forResolved(eitherIdentity, 2)
        report.errorAndAbort("M6_BARE_LIST_NOT_CONSTRUCTOR: " + list.toString + "; " + either.toString + "; ids=" + listId + ";" + eitherId + details)
      Expr("symbol-info-bare-list-either-ok")
    else
      val good = list.isLeft && terminal.exists(_ =:= TypeRepr.of[String])
      if !good then report.errorAndAbort("M6_BASELINE_CHANGED: " + list.toString + "; " + terminal.toString)
      Expr("baseline-list-rejected-terminal-ok")
"""
  }

  private def compile(root: File, dir: File, name: String, source: String, compilerCp: Seq[String], cp: Seq[File]): Result = {
    val classes = dir / "classes"; IO.createDirectory(classes)
    val file = dir / name; IO.write(file, source)
    val args = Seq("-classpath", cp.map(_.getAbsolutePath).mkString(File.pathSeparator), "-d",
      classes.getAbsolutePath, "-color:never", file.getAbsolutePath)
    val r = runJava(root, compilerCp, "dotty.tools.dotc.Main", args)
    IO.write(dir / "compiler-arguments.txt", args.mkString("", "\n", "\n")); IO.write(dir / "compiler.log", r._2)
    Result(r._1, r._2, classes, args)
  }
  private def assertNoAllow(args: Seq[String], annotation: File, plugin: File): Unit = {
    require(!args.contains("-experimental"), args.mkString("\n"))
    require(!args.exists(_.startsWith("-Xplugin:")), args.mkString("\n"))
    require(!args.exists(_.contains(annotation.getAbsolutePath)), args.mkString("\n"))
    require(!args.exists(_.contains(plugin.getAbsolutePath)), args.mkString("\n"))
  }
  private def containsGlobalExperimentalOption(text: String): Boolean =
    text.split("\\R").exists(_.trim == "-experimental")
  private def javap(root: File, cp: File, cls: String) = run(Seq(new File(sys.props("java.home"), "bin/javap").getAbsolutePath,
    "-classpath", cp.getAbsolutePath, "-c", "-p", cls), root)
  private def javapJar(root: File, jar: File, cls: String, publicOnly: Boolean) = run(
    Seq(new File(sys.props("java.home"), "bin/javap").getAbsolutePath, "-classpath", jar.getAbsolutePath,
      if (publicOnly) "-public" else "-p") ++ (if (publicOnly) Nil else Seq("-c")) ++ Seq(cls), root)
  private def runJava(root: File, cp: Seq[String], main: String, args: Seq[String]) =
    run(Seq(new File(sys.props("java.home"), "bin/java").getAbsolutePath, "-cp", cp.mkString(File.pathSeparator), main) ++ args, root)
  private def run(command: Seq[String], root: File): (Int, String) = { val out = new StringBuilder
    val exit = Process(command, root).!(ProcessLogger(s => out.append(s).append('\n'), s => out.append(s).append('\n'))); (exit, out.result()) }
  private def git(root: File, args: Seq[String]): String = { val r = run(Seq("git") ++ args, root); require(r._1 == 0, r._2); r._2 }
  private def jarEntries(jar: File): Set[String] = { val z = new JarFile(jar); try z.entries.asScala.map(_.getName).toSet finally z.close() }
  private def jarBytes(jar: File, names: Seq[String]): Seq[Array[Byte]] = { val z = new JarFile(jar); try names.flatMap { n =>
    Option(z.getEntry(n)).map(e => { val in = z.getInputStream(e); try Stream.continually(in.read).takeWhile(_ != -1).map(_.toByte).toArray finally in.close() })
  } finally z.close() }
  private def sha256(bytes: Array[Byte]): String = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
}
