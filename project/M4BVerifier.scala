import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarFile

import sbt._

import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}

/** Exact Scala 3.9.0 black-box coexistence proof against the pinned
  * Macro-Paradise source build. Generated peer/source artifacts stay below
  * ignored target roots; only this orchestration is product source.
  */
object M4BVerifier {
  private val requiredVersion = "3.9.0"
  private val pinnedPeer = "d773332c29efce90b3af343d34ae5450a93f6d93"
  private val scalaDiagnostic = "marked @experimental"
  private val guardDiagnostic =
    "incompatible compiler-plugin phase inside the provider neutralization window"
  private val providerName = "m4b.Fixture$.provider"
  private val ordinaryName = "m4b.Fixture$.ordinary"
  private val handlerName = "m4b.handler.GenHandler"

  private case class CompileResult(
      exit: Int,
      output: String,
      classes: File,
      args: Seq[String],
      trace: String,
      observer: String
  )

  private val markerSource = """package m4b.marker
import paradise3.api.expander
import scala.annotation.StaticAnnotation

@expander("m4b.handler.GenHandler")
final class gen extends StaticAnnotation
"""

  private val handlerSource = """package m4b.handler
import dotty.tools.dotc.core.Contexts.Context
import paradise3.api.{ExpansionInput, ExpansionOutcome, ParadiseAnnotationExpander}
import paradise3.api.helpers.ExpansionHelpers

final class GenHandler extends ParadiseAnnotationExpander:
  override def annotationName: String = "m4b.marker.gen"

  override def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionHelpers.addStringMethodToClass(
      input,
      methodName = "generatedHello",
      value = s"hello ${input.className}"
    )
"""

  private val positiveSource = """package m4b
import m4b.marker.gen
import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental

@gen final class GenUser

object Fixture:
  @experimental def provider(): Int = 1
  @allowExperimental def allowed(): Int = provider()
  def ordinary(): Int = allowed()
  val generatedEvidence: String = new GenUser().generatedHello
"""

  def verify(
      root: File,
      scalaVersion: String,
      annotationJar: File,
      allowPluginJar: File,
      compilerClasspath: Seq[File],
      log: Logger
  ): Unit = {
    require(
      scalaVersion == requiredVersion,
      s"M4B is qualified only on exact Scala $requiredVersion, not $scalaVersion"
    )
    VerificationLane.validateInputs(
      scalaVersion,
      annotationJar,
      allowPluginJar,
      compilerClasspath
    )

    val peerRoot = root / "target" / "m4b-verification" / "macroparadise-disposable"
    val macroApiJar = peerRoot / "plugin-api" / "target" / "scala-3.9.0" /
      "macroparadise-scala3-plugin-api_3.9.0-0.1.1-SNAPSHOT.jar"
    val macroPluginJar = peerRoot / "plugin" / "target" / "scala-3.9.0" /
      "macroparadise-scala3-plugin_3.9.0-0.1.1-SNAPSHOT.jar"
    require(peerRoot.isDirectory, s"missing disposable Macro-Paradise clone: $peerRoot")
    require(git(peerRoot, Seq("rev-parse", "HEAD")).trim == pinnedPeer,
      s"disposable Macro-Paradise clone is not pinned to $pinnedPeer")
    require(git(peerRoot, Seq("status", "--porcelain=v1")).trim.isEmpty,
      "disposable Macro-Paradise source checkout is dirty before M4B")
    require(macroApiJar.isFile, s"missing exact Macro-Paradise API jar: $macroApiJar")
    require(macroPluginJar.isFile, s"missing exact Macro-Paradise plugin jar: $macroPluginJar")

    val originalPeer = root.getParentFile / "macroparadise-scala3"
    val independentObjectComparisons = requireIndependentGitObjects(originalPeer, peerRoot)
    val originalPeerHead = git(originalPeer, Seq("rev-parse", "HEAD")).trim
    val originalPeerStatus = git(originalPeer, Seq("status", "--porcelain=v1"))
    val originalPeerDirty = originalPeerStatus.trim.nonEmpty

    val work = VerificationLane.workDirectory(root, scalaVersion, "m4b")
    IO.delete(work)
    IO.createDirectory(work)
    VerificationLane.recordInputs(
      work,
      scalaVersion,
      annotationJar,
      allowPluginJar,
      compilerClasspath
    )
    log.info(s"M4B Scala $scalaVersion: ${work.getAbsolutePath}")

    val compilerCp = compilerClasspath.map(_.getCanonicalFile).distinct
    val compilerCpStrings = compilerCp.map(_.getAbsolutePath)
    val libraries = compilerCp.filter { file =>
      file.getName.startsWith("scala3-library_3-") ||
      file.getName.startsWith("scala-library-")
    }
    require(libraries.size == 2, "expected two exact Scala runtime libraries")
    val failures = ArrayBuffer.empty[String]

    def check(label: String)(body: => Unit): Unit =
      try {
        body
        log.info(s"M4B PASS $label")
      } catch {
        case error: IllegalArgumentException =>
          failures += s"$label: ${error.getMessage}"
          log.error(s"M4B FAIL $label: ${error.getMessage}")
      }

    val markerClasses = compileSimple(
      root,
      work / "marker",
      "Marker.scala",
      markerSource,
      compilerCpStrings,
      (libraries :+ macroApiJar).map(_.getAbsolutePath)
    )
    val markerJar = packageClasses(markerClasses, work / "marker" / "m4b-marker.jar")
    val handlerClasses = compileSimple(
      root,
      work / "handler",
      "GenHandler.scala",
      handlerSource,
      compilerCpStrings,
      (compilerCp :+ macroApiJar).map(_.getAbsolutePath).distinct
    )
    val handlerJar = packageClasses(handlerClasses, work / "handler" / "m4b-handler.jar")
    val handlerClasspath = (Seq(handlerJar, macroApiJar) ++ compilerCp).map(_.getCanonicalFile).distinct
    val externalIdentity = artifactIdentity(markerJar, handlerClasspath)
    val sourceCp = (libraries ++ Seq(annotationJar, markerJar)).map(_.getAbsolutePath)
    val observerJar = buildObserver(root, work, compilerCpStrings)

    IO.write(work / "peer-build.txt", Seq(
      s"peerCommit=$pinnedPeer",
      s"peerOriginalHead=$originalPeerHead",
      s"localPeerWorktreeDirty=${yesNo(originalPeerDirty)}",
      s"peerOriginalStatusSha256=${sha256(originalPeerStatus.getBytes(StandardCharsets.UTF_8))}",
      "pinnedPeerBytesFromDirtyWorktree=NO",
      "pinnedCommitMaterializedIndependently=YES",
      s"independentGitObjectComparisons=$independentObjectComparisons",
      s"javaVersion=${System.getProperty("java.version")}",
      s"javaVendor=${System.getProperty("java.vendor")}",
      s"sbtVersion=${IO.read(peerRoot / "project" / "build.properties").trim}",
      s"scalaVersion=$scalaVersion",
      s"macroApiJar=${macroApiJar.getAbsolutePath}",
      s"macroApiSha256=${sha256(macroApiJar)}",
      s"macroApiManifest=${manifestVersion(macroApiJar)}",
      s"macroPluginJar=${macroPluginJar.getAbsolutePath}",
      s"macroPluginSha256=${sha256(macroPluginJar)}",
      s"macroPluginManifest=${manifestVersion(macroPluginJar)}",
      s"markerJar=${markerJar.getAbsolutePath}",
      s"markerSha256=${sha256(markerJar)}",
      s"handlerJar=${handlerJar.getAbsolutePath}",
      s"handlerSha256=${sha256(handlerJar)}",
      s"handlerClasspath=${handlerClasspath.map(_.getAbsolutePath).mkString(File.pathSeparator)}",
      s"externalArtifactIdentity=sha256:$externalIdentity"
    ).mkString("", "\n", "\n"))

    def macroOptions(traceFile: File): Seq[String] = Seq(
      "-Xplugin-require:macroparadise",
      s"-P:macroparadise:handlerClasspath=${handlerClasspath.map(_.getAbsolutePath).mkString(File.pathSeparator)}",
      s"-P:macroparadise:externalArtifactIdentity=sha256:$externalIdentity",
      s"-P:macroparadise:externalHandlerInvocationTrace=${traceFile.getAbsolutePath}"
    )

    def compileFixture(
        label: String,
        source: String,
        allowFirst: Boolean,
        observerProbe: Option[String] = None,
        includeAllow: Boolean = true,
        extraSources: Seq[(String, String)] = Nil
    ): CompileResult = {
      val dir = work / label
      val classes = dir / "classes"
      IO.createDirectory(classes)
      val sourceFile = dir / "Fixture.scala"
      val traceFile = dir / "macroparadise-handler-trace.log"
      val observerFile = dir / "observer.log"
      IO.write(sourceFile, source)
      val sourceFiles = sourceFile +: extraSources.map { case (name, contents) =>
        val file = dir / name
        IO.write(file, contents)
        file
      }
      val realPlugins =
        if (!includeAllow) Seq(macroPluginJar)
        else if (allowFirst) Seq(allowPluginJar, macroPluginJar)
        else Seq(macroPluginJar, allowPluginJar)
      val pluginFiles = observerProbe.fold(realPlugins)(_ => realPlugins :+ observerJar)
      val pluginArgs = pluginFiles.map(file => s"-Xplugin:${file.getAbsolutePath}")
      val allowRequire = if (includeAllow) Seq("-Xplugin-require:allow-experimental") else Nil
      val observerOptions = observerProbe.toSeq.flatMap { probe =>
        Seq(
          s"-P:m4a-observer:probe=$probe",
          s"-P:m4a-observer:provider=$providerName",
          s"-P:m4a-observer:ordinary=$ordinaryName",
          s"-P:m4a-observer:log=${observerFile.getAbsolutePath}"
        )
      }
      val args = Seq(
        "-classpath", sourceCp.mkString(File.pathSeparator),
        "-d", classes.getAbsolutePath
      ) ++ pluginArgs ++ allowRequire ++ macroOptions(traceFile) ++ observerOptions ++
        Seq("-color:never") ++ sourceFiles.map(_.getAbsolutePath)
      val result = runJava(root, compilerCpStrings, "dotty.tools.dotc.Main", args)
      IO.write(dir / "compiler-arguments.txt", args.mkString("", "\n", "\n"))
      IO.write(dir / "compiler.log", result._2)
      CompileResult(
        result._1,
        result._2,
        classes,
        args,
        if (traceFile.isFile) IO.read(traceFile) else "",
        if (observerFile.isFile) IO.read(observerFile) else ""
      )
    }

    val positives = Seq(true, false).map { allowFirst =>
      val order = if (allowFirst) "allow-first" else "macroparadise-first"
      val result = compileFixture(s"positive-$order", positiveSource, allowFirst)
      val javap = runJavap(root, result.classes, "m4b.GenUser")
      IO.write(work / s"positive-$order" / "GenUser.javap", javap._2)
      val decompiled = decompile(
        root,
        compilerCpStrings,
        sourceCp :+ result.classes.getAbsolutePath,
        result.classes / "m4b" / "Fixture.tasty"
      )
      IO.write(work / s"positive-$order" / "Fixture.decompiled.log", decompiled._2)
      check(s"$order real transformation and Allow permission") {
        require(result.exit == 0, result.output)
        require(result.trace.contains(s"handler=$handlerName"), result.trace)
        require(result.trace.contains("annotation=m4b.marker.gen"), result.trace)
        require(result.trace.contains("class=GenUser"), result.trace)
        require(javap._1 == 0 && javap._2.contains("generatedHello"), javap._2)
        require(decompiled._1 == 0, decompiled._2)
        assertAllowBoundary(decompiled._2)
        require(!result.output.contains(guardDiagnostic), result.output)
        require(!result.args.contains("-experimental"), "positive used global -experimental")
      }
      (order, result, javap._2, decompiled._2)
    }

    check("both loading orders have the same supported semantic outcome") {
      require(positives.forall(_._2.exit == 0), positives.map(_._2.output).mkString("\n"))
      require(
        semanticSignature(positives.head._3, positives.head._4) ==
          semanticSignature(positives.last._3, positives.last._4),
        "loading orders produced different supported API/TASTy evidence"
      )
    }

    Seq(true, false).foreach { allowFirst =>
      val order = if (allowFirst) "allow-first" else "macroparadise-first"
      val audit = compileFixture(s"phase-plan-$order", positiveSource, allowFirst, Some("p3"))
      check(s"$order actual installed phase plan keeps Macro-Paradise outside the sensitive window") {
        require(audit.exit == 0, audit.output)
        require(observation(audit.observer, "P3", "providerHasExperimentalAnnotation") == "true", audit.observer)
        require(observation(audit.observer, "P3", "providerIsExperimental") == "true", audit.observer)
        assertPlan(audit.observer, Seq(
          "parser:builtin",
          "paradiseGen:plugin",
          "typer:builtin",
          "posttyper:builtin",
          "allowExperimentalCaptureOwners:plugin",
          "postInlining:builtin",
          "allowExperimentalCheckReferences:plugin",
          "crossVersionChecks:builtin",
          "allowExperimentalRestoreProviders:plugin",
          "m4aProbeP3:plugin"
        ))
      }
    }

    var downstreamRequirement = "OTHER"
    val downstreamSource = """package downstream
val allowValue: Int = m4b.Fixture.ordinary()
val generatedValue: String = new m4b.GenUser().generatedHello
"""
    val downstreamCandidates = Seq(
      "NONE" -> Seq.empty[File],
      "MARKER_ONLY" -> Seq(markerJar),
      "MARKER_AND_API" -> Seq(markerJar, macroApiJar)
    )
    val downstreamResults = downstreamCandidates.map { case (name, extras) =>
      name -> compileWithoutPlugins(
        root,
        work / s"downstream-${name.toLowerCase.replace('_', '-')}",
        compilerCpStrings,
        (libraries ++ Seq(positives.head._2.classes) ++ extras).map(_.getAbsolutePath),
        downstreamSource
      )
    }
    downstreamResults.find(_._2.exit == 0).foreach { case (name, _) => downstreamRequirement = name }
    check("separate downstream needs neither plugin, handler, nor Allow marker artifact") {
      val firstPass = downstreamResults.find(_._2.exit == 0).getOrElse(
        throw new IllegalArgumentException(downstreamResults.map(_._2.output).mkString("\n")))
      require(firstPass._2.args.forall(!_.startsWith("-Xplugin:")), firstPass._2.args.mkString("\n"))
      require(!firstPass._2.args.contains("-experimental"), firstPass._2.args.mkString("\n"))
      require(!firstPass._2.args.contains(annotationJar.getAbsolutePath), firstPass._2.args.mkString("\n"))
      require(!firstPass._2.args.exists(_.contains(handlerJar.getAbsolutePath)), firstPass._2.args.mkString("\n"))
      require(!firstPass._2.args.exists(_.contains(macroPluginJar.getAbsolutePath)), firstPass._2.args.mkString("\n"))
    }

    val noPermissionSource = positiveSource.replace(
      "@allowExperimental def allowed(): Int = provider()",
      "def allowed(): Int = provider()"
    )
    val noPermission = compileFixture("negative-macro-does-not-grant", noPermissionSource, allowFirst = true)
    check("Macro-Paradise does not grant experimental permission") {
      require(noPermission.exit != 0, "missing permission unexpectedly compiled")
      require(noPermission.output.contains(scalaDiagnostic), noPermission.output)
      require(noPermission.trace.contains(s"handler=$handlerName"), noPermission.trace)
    }

    Seq(true, false).foreach { allowFirst =>
      val order = if (allowFirst) "allow-first" else "macroparadise-first"
      val siblingLeak = compileFixture(
        s"negative-sibling-leak-$order",
        positiveSource + "\nobject SiblingLeak { def forbidden(): Int = Fixture.provider() }\n",
        allowFirst
      )
      check(s"$order does not leak Allow permission to a same-unit sibling") {
        require(siblingLeak.exit != 0, "same-unit sibling experimental use unexpectedly compiled")
        require(siblingLeak.output.contains(scalaDiagnostic), siblingLeak.output)
        require(siblingLeak.output.contains("provider"), siblingLeak.output)
        require(siblingLeak.trace.contains(s"handler=$handlerName"), siblingLeak.trace)
      }

      val laterUnitLeak = compileFixture(
        s"negative-later-unit-leak-$order",
        positiveSource,
        allowFirst,
        extraSources = Seq(
          "ZLaterLeak.scala" ->
            "package m4b\nobject LaterUnitLeak { def forbidden(): Int = Fixture.provider() }\n"
        )
      )
      check(s"$order does not leak Allow permission to a later unit") {
        require(laterUnitLeak.exit != 0, "later-unit experimental use unexpectedly compiled")
        require(laterUnitLeak.output.contains(scalaDiagnostic), laterUnitLeak.output)
        require(laterUnitLeak.output.contains("provider"), laterUnitLeak.output)
        require(laterUnitLeak.trace.contains(s"handler=$handlerName"), laterUnitLeak.trace)
      }
    }

    val allowAbsent = compileFixture(
      "negative-allow-absent",
      positiveSource,
      allowFirst = false,
      includeAllow = false
    )
    check("inert Allow marker fails closed when Allow plugin is absent") {
      require(allowAbsent.exit != 0, "Allow-absent fixture unexpectedly compiled")
      require(allowAbsent.output.contains(scalaDiagnostic), allowAbsent.output)
      require(allowAbsent.trace.contains(s"handler=$handlerName"), allowAbsent.trace)
    }

    Seq("p1", "p2").zipWithIndex.foreach { case (probe, index) =>
      val guard = compileFixture(
        s"negative-m4a-guard-$probe",
        positiveSource,
        allowFirst = index == 0,
        observerProbe = Some(probe)
      )
      check(s"M4A $probe guard remains load-bearing with Macro-Paradise present") {
        require(guard.exit != 0, s"sensitive $probe fixture unexpectedly compiled")
        require(guard.output.contains(guardDiagnostic), guard.output)
        require(!guard.observer.contains(s"probe=${probe.toUpperCase}\n"), guard.observer)
        require(observation(guard.observer, "P3_AUDIT", "providerHasExperimentalAnnotation") == "true", guard.observer)
        require(observation(guard.observer, "P3_AUDIT", "providerIsExperimental") == "true", guard.observer)
        require(guard.trace.contains(s"handler=$handlerName"), guard.trace)
        assertPlan(guard.observer, Seq(
          "paradiseGen:plugin",
          "allowExperimentalCheckReferences:plugin",
          s"m4aProbe${probe.toUpperCase}:plugin",
          "allowExperimentalRestoreProviders:plugin",
          "m4aProbeP3Audit:plugin"
        ))
      }
    }

    val finalPeerHead = git(originalPeer, Seq("rev-parse", "HEAD")).trim
    val finalPeerStatus = git(originalPeer, Seq("status", "--porcelain=v1"))
    check("original Macro-Paradise peer checkout stayed untouched") {
      require(finalPeerHead == originalPeerHead,
        s"peer HEAD changed: before=$originalPeerHead after=$finalPeerHead")
      require(finalPeerStatus == originalPeerStatus,
        s"peer status changed: before=$originalPeerStatus after=$finalPeerStatus")
    }

    IO.write(work / "source-hashes.txt", Seq(
      s"MacroParadisePlugin390.scala=${sha256(peerRoot / "plugin" / "src" / "main" / "scala-3.9.0" / "macroparadise" / "MacroParadisePlugin390.scala")}",
      s"ExactCompilerLine.scala=${sha256(peerRoot / "plugin" / "src" / "main" / "scala" / "macroparadise" / "ExactCompilerLine.scala")}",
      s"MacroParadisePlugin.scala=${sha256(peerRoot / "plugin" / "src" / "main" / "scala" / "macroparadise" / "MacroParadisePlugin.scala")}",
      s"ParadiseAnnotationExpander.scala=${sha256(peerRoot / "plugin-api" / "src" / "main" / "scala" / "paradise3" / "api" / "ParadiseAnnotationExpander.scala")}",
      s"expander.java=${sha256(peerRoot / "plugin-api" / "src" / "main" / "java" / "paradise3" / "api" / "expander.java")}",
      s"M4BVerifier.scala=${sha256(root / "project" / "M4BVerifier.scala")}"
    ).mkString("", "\n", "\n"))

    if (failures.nonEmpty) {
      IO.write(work / "summary.txt", (Seq(
        "PROMPT_009_M4B=FAIL",
        "SCALA_3_9_0=FAIL",
        s"MACROPARADISE_PIN=$pinnedPeer",
        s"FAILURE_COUNT=${failures.size}"
      ) ++ failures.zipWithIndex.map { case (failure, index) =>
        s"FAILURE_${index + 1}=${failure.replace('\n', ' ')}"
      }).mkString("", "\n", "\n"))
      require(requirement = false, failures.mkString("\n"))
    } else {
      IO.write(work / "summary.txt", Seq(
        "PROMPT_009_M4B=PASS",
        "PROMPT_010_RECOVERY=PASS",
        "SCALA_3_9_0=PASS",
        s"MACROPARADISE_PIN=$pinnedPeer",
        s"MACROPARADISE_PINNED_SHA=$pinnedPeer",
        s"LOCAL_PEER_WORKTREE_DIRTY=${yesNo(originalPeerDirty)}",
        "PINNED_PEER_BYTES_FROM_DIRTY_WORKTREE=NO",
        "PINNED_COMMIT_MATERIALIZED_INDEPENDENTLY=YES",
        "PEER_CHECKOUT_MUTATED=NO",
        "MACROPARADISE_PEER_CHECKOUT_MODIFIED=NO",
        "MACROPARADISE_DISPOSABLE_BUILD=PASS",
        "MACROPARADISE_STANDARD_PLUGIN=YES",
        "MACROPARADISE_REAL_TRANSFORMATION=PASS",
        "MACROPARADISE_PHASE_PLAN_RECORDED=YES",
        "MACROPARADISE_PHASES_IN_ALLOW_SENSITIVE_WINDOW=NO",
        "MACROPARADISE_PHASES_OUTSIDE_SENSITIVE_WINDOW=YES",
        "PLUGIN_LOAD_ORDER_ALLOW_FIRST=PASS",
        "PLUGIN_LOAD_ORDER_MACROPARADISE_FIRST=PASS",
        "PLUGIN_LOAD_ORDER_PARADISE_FIRST=PASS",
        "ALLOW_PERMISSION_WITH_MACROPARADISE=PASS",
        "MACROPARADISE_GENERATED_MEMBER_WITH_ALLOW=PASS",
        "PROVIDER_STILL_EXPERIMENTAL=YES",
        "ALLOW_CONSUMER_ORDINARY=YES",
        "ALLOW_MARKER_TASTY_LEAK=NO",
        "CONSUMER_EXPERIMENTAL_LEAK=NO",
        "DOWNSTREAM_WITHOUT_ALLOW_PLUGIN=PASS",
        "DOWNSTREAM_WITHOUT_MACROPARADISE_PLUGIN=PASS",
        "DOWNSTREAM_WITHOUT_MACROPARADISE_HANDLER=PASS",
        "DOWNSTREAM_WITHOUT_COMPILER_PLUGINS=PASS",
        s"DOWNSTREAM_MACRO_MARKER_API_REQUIREMENT=$downstreamRequirement",
        "MACROPARADISE_DOES_NOT_GRANT_EXPERIMENTAL=PASS",
        "ALLOW_ABSENT_FAILS_CLOSED_WITH_MACROPARADISE=PASS",
        "M4A_GUARD_WITH_MACROPARADISE_PRESENT=PASS",
        "M4A_GUARD_WITH_MACROPARADISE=PASS",
        "GLOBAL_EXPERIMENTAL_USED_IN_POSITIVE=NO",
        "GLOBAL_EXPERIMENTAL_REQUIRED=NO",
        "REAL_MACROPARADISE_COEXISTENCE_3_9_0=COMPATIBLE_PASS",
        "M4B_CONTROLLER_RECOMMENDATION=ACCEPT_WITH_QUALIFICATIONS",
        "M4_COMPLETE=NO",
        "PEER_REPOSITORIES_MODIFIED=NO",
        "RELEASE_AUTHORIZED=NO"
      ).mkString("", "\n", "\n"))
    }
  }

  private case class PlainCompile(exit: Int, output: String, classes: File, args: Seq[String])

  private def compileSimple(
      root: File,
      dir: File,
      fileName: String,
      source: String,
      compilerCp: Seq[String],
      sourceCp: Seq[String]
  ): File = {
    val classes = dir / "classes"
    IO.createDirectory(classes)
    val sourceFile = dir / fileName
    IO.write(sourceFile, source)
    val args = Seq(
      "-classpath", sourceCp.mkString(File.pathSeparator),
      "-d", classes.getAbsolutePath,
      "-color:never",
      sourceFile.getAbsolutePath
    )
    val result = runJava(root, compilerCp, "dotty.tools.dotc.Main", args)
    IO.write(dir / "compiler-arguments.txt", args.mkString("", "\n", "\n"))
    IO.write(dir / "compiler.log", result._2)
    require(result._1 == 0, s"fixture $fileName did not compile: ${result._2}")
    classes
  }

  private def compileWithoutPlugins(
      root: File,
      dir: File,
      compilerCp: Seq[String],
      cp: Seq[String],
      source: String
  ): PlainCompile = {
    val classes = dir / "classes"
    IO.createDirectory(classes)
    val sourceFile = dir / "Downstream.scala"
    IO.write(sourceFile, source)
    val args = Seq(
      "-classpath", cp.mkString(File.pathSeparator),
      "-d", classes.getAbsolutePath,
      "-color:never",
      sourceFile.getAbsolutePath
    )
    val result = runJava(root, compilerCp, "dotty.tools.dotc.Main", args)
    IO.write(dir / "compiler-arguments.txt", args.mkString("", "\n", "\n"))
    IO.write(dir / "compiler.log", result._2)
    PlainCompile(result._1, result._2, classes, args)
  }

  private def packageClasses(classes: File, jar: File): File = {
    IO.zip(Path.allSubpaths(classes).toSeq, jar, Some(0L))
    require(jar.isFile, s"fixture jar missing: $jar")
    jar
  }

  private def buildObserver(root: File, work: File, compilerCp: Seq[String]): File = {
    val observerRoot = root / "m4a-observer"
    val source = observerRoot / "src" / "main" / "scala" / "io" / "github" /
      "dmytromitin" / "allowexperimental" / "m4aobserver" / "M4AObserverPlugin.scala"
    val descriptor = observerRoot / "src" / "main" / "resources" / "plugin.properties"
    val classes = work / "observer-plugin" / "classes"
    IO.createDirectory(classes)
    val args = Seq(
      "-classpath", compilerCp.mkString(File.pathSeparator),
      "-d", classes.getAbsolutePath,
      "-color:never",
      source.getAbsolutePath
    )
    val result = runJava(root, compilerCp, "dotty.tools.dotc.Main", args)
    IO.write(work / "observer-plugin" / "compiler-arguments.txt", args.mkString("", "\n", "\n"))
    IO.write(work / "observer-plugin" / "compiler.log", result._2)
    require(result._1 == 0, s"retained M4A observer did not compile: ${result._2}")
    IO.copyFile(descriptor, classes / "plugin.properties")
    packageClasses(classes, work / "observer-plugin" / "m4a-observer_3-3.9.0.jar")
  }

  private def artifactIdentity(marker: File, handlerClasspath: Seq[File]): String = {
    val markerLine = s"marker\tmarker\t${sha256(marker)}\n"
    val handlerLines = handlerClasspath.zipWithIndex.map { case (file, index) =>
      f"handler\t$index%04d:handler-$index%04d\t${sha256(file)}\n"
    }.mkString
    sha256((markerLine + handlerLines).getBytes(StandardCharsets.UTF_8))
  }

  private def manifestVersion(jar: File): String = {
    val opened = new JarFile(jar)
    try Option(opened.getManifest.getMainAttributes.getValue("Implementation-Version"))
      .getOrElse("MISSING")
    finally opened.close()
  }

  private def decompile(
      root: File,
      compilerCp: Seq[String],
      cp: Seq[String],
      tasty: File
  ): (Int, String) =
    runJava(
      root,
      compilerCp,
      "dotty.tools.dotc.decompiler.Main",
      Seq("-classpath", cp.mkString(File.pathSeparator), "-color:never", tasty.getAbsolutePath)
    )

  private def runJavap(root: File, classes: File, className: String): (Int, String) = {
    val javap = new File(sys.props("java.home"), "bin/javap").getAbsolutePath
    run(Seq(javap, "-classpath", classes.getAbsolutePath, "-p", className), root)
  }

  private def assertAllowBoundary(decompiled: String): Unit = {
    val provider = definition(decompiled, "provider")
    val allowed = definition(decompiled, "allowed")
    val ordinary = definition(decompiled, "ordinary")
    require(provider.contains("@scala.annotation.experimental"), provider)
    require(!allowed.contains("experimental") && !allowed.contains("allowExperimental"), allowed)
    require(!ordinary.contains("experimental") && !ordinary.contains("allowExperimental"), ordinary)
    require(!decompiled.contains("@io.github.dmytromitin.allowexperimental.allowExperimental"), decompiled)
  }

  private def semanticSignature(javap: String, decompiled: String): String = Seq(
    javap.split("\\R").filter(_.contains("generatedHello")).mkString,
    definition(decompiled, "provider"),
    definition(decompiled, "allowed"),
    definition(decompiled, "ordinary")
  ).mkString("\n")

  private def definition(output: String, name: String): String =
    output.split("\\R").find(_.contains(s"def $name(")).getOrElse(
      throw new IllegalArgumentException(s"missing definition $name: $output"))

  private def assertPlan(observer: String, expected: Seq[String]): Unit = {
    val plan = observationValue(observer, "phasePlan")
    var previous = -1
    expected.foreach { phase =>
      val next = plan.indexOf(phase, previous + 1)
      require(next >= 0, s"phase $phase absent from plan: $plan")
      require(next > previous, s"phase $phase out of order: $plan")
      previous = next
    }
  }

  private def observation(log: String, probe: String, key: String): String = {
    val block = log.split("---", -1).find(_.contains(s"probe=$probe\n")).getOrElse(
      throw new IllegalArgumentException(s"missing $probe observation: $log"))
    observationValue(block, key)
  }

  private def observationValue(block: String, key: String): String =
    block.split("\\R").find(_.startsWith(key + "=")).map(_.stripPrefix(key + "=")).getOrElse(
      throw new IllegalArgumentException(s"missing $key observation: $block"))

  private def git(root: File, args: Seq[String]): String = {
    val result = run(Seq("git") ++ args, root)
    require(result._1 == 0, result._2)
    result._2
  }

  private def requireIndependentGitObjects(original: File, disposable: File): Int = {
    val originalObjects = original / ".git" / "objects"
    val disposableObjects = disposable / ".git" / "objects"
    require(!(disposableObjects / "info" / "alternates").exists,
      "disposable Git object store uses alternates")
    val comparable = (disposableObjects ** "*").get.filter(_.isFile).flatMap { copied =>
      val relative = IO.relativize(disposableObjects, copied)
      relative.map(path => (originalObjects / path, copied)).filter(_._1.isFile)
    }
    require(comparable.nonEmpty, "no comparable Git object files found for no-hardlink proof")
    comparable.foreach { case (source, copied) =>
      require(!Files.isSameFile(source.toPath, copied.toPath),
        s"disposable Git object is hard-linked to read-only peer: ${copied.getAbsolutePath}")
    }
    comparable.size
  }

  private def runJava(
      root: File,
      cp: Seq[String],
      main: String,
      args: Seq[String]
  ): (Int, String) = {
    val java = new File(sys.props("java.home"), "bin/java").getAbsolutePath
    run(Seq(java, "-cp", cp.mkString(File.pathSeparator), main) ++ args, root)
  }

  private def run(command: Seq[String], root: File): (Int, String) = {
    val output = new StringBuilder
    val exit = Process(command, root).!(ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')
    ))
    (exit, output.result())
  }

  private def sha256(file: File): String = {
    val input = Files.newInputStream(file.toPath)
    val digest = MessageDigest.getInstance("SHA-256")
    try {
      val buffer = new Array[Byte](8192)
      var count = input.read(buffer)
      while (count >= 0) {
        if (count > 0) digest.update(buffer, 0, count)
        count = input.read(buffer)
      }
    } finally input.close()
    hex(digest.digest())
  }

  private def sha256(bytes: Array[Byte]): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def hex(bytes: Array[Byte]): String =
    bytes.map(value => f"${value & 0xff}%02x").mkString

  private def yesNo(value: Boolean): String = if (value) "YES" else "NO"
}
