import java.io.File
import sbt._
import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}

/** Real compiler fixtures, kept here so each negative can share the exact
  * provider with a permitted body in the same invocation. No mocked compiler.
  */
object M1Verifier {
  private val prelude = """package m1
import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental
@experimental def provider(): Int = 1
@experimental def provider2(): Int = 2
@experimental val stable: String = "value"
@experimental class Box
object Terms:
  @experimental def selected(): Int = 3
"""
  private val permitted = """
@allowExperimental def allowedIdent(): Int = provider()
@allowExperimental def allowedSelect(): Int = Terms.selected()
@allowExperimental def allowedStable(): Any = stable
@allowExperimental def allowedNested(): Int =
  def local(): Int = provider2()
  local()
@allowExperimental private def implementation(): Int = provider()
def publicViaPrivate(): Int = implementation()
object PrivateUse:
  @allowExperimental private def hidden(): Int = provider2()
  def facade(): Int = hidden()
@allowExperimental def allowedImport(): Int =
  import Terms.selected
  selected()
"""
  private val scalaDiagnostic = "marked @experimental"
  private case class Negative(name: String, source: String, diagnostic: String = scalaDiagnostic,
      extraUnit: Boolean = false, early: Boolean = false)
  private val negatives = Seq(
    Negative("direct", "def bad(): Int = provider()"),
    Negative("stable-sibling", "def bad(): Any = stable"),
    Negative("select-sibling", "def bad(): Int = Terms.selected()"),
    Negative("nested-sibling", "def bad(): Int = { def local(): Int = provider2(); local() }"),
    Negative("later-unit", "def bad(): Int = provider2()", extraUnit = true),
    Negative("new", "@allowExperimental def bad(): Any = new Box"),
    Negative("constructor-only", """class ConstructorOnly @experimental() ()
@allowExperimental def bad(): Any = new ConstructorOnly()"""),
    Negative("local-val-type", "@allowExperimental def bad(): Any = { val x: Box = null; x }"),
    Negative("ascription", "@allowExperimental def bad(): Any = (null: Box)"),
    Negative("type-argument", "@allowExperimental def bad(): Any = List.empty[Box]"),
    Negative("experimental-owner-import", """@experimental object Imports { def value: Int = 1 }
@allowExperimental def bad(): Int = { import Imports.*; value }"""),
    Negative("parameter-type", "@allowExperimental def bad(x: Box): Any = provider()"),
    Negative("return-type", "@allowExperimental def bad(): Box = { provider(); null }"),
    Negative("inferred-return-type", """@experimental def makeBox(): Box = new Box
@allowExperimental def bad() = makeBox()"""),
    Negative("nested-signature", """@allowExperimental def bad(): Int =
  def local(x: Box): Int = provider()
  local(null)"""),
    // The same stable provider is permitted as a body term and rejected as a signature type.
    Negative("same-provider-parameter", "@allowExperimental def bad(x: stable.type): Any = stable"),
    Negative("same-provider-return", "@allowExperimental def bad(): stable.type = stable"),
    Negative("inline-provider", """@experimental inline def earlyProvider: Int = 1
@allowExperimental def bad(): Int = earlyProvider""", early = true),
    Negative("transparent-inline-provider", """@experimental transparent inline def earlyProvider: Int = 1
@allowExperimental def bad(): Int = earlyProvider""", early = true),
    Negative("inline-owner", "@allowExperimental inline def bad(): Int = provider()",
      "supports only non-inline def owners"),
    Negative("independent-local-owner", """def bad(): Int =
  @allowExperimental def local(): Int = provider()
  local()""", "does not support independently annotated local defs"),
    Negative("nested-inline", """@allowExperimental def bad(): Int =
  inline def local(): Int = provider()
  local()""", "does not support nested inline definitions"),
    Negative("nested-class", """@allowExperimental def bad(): Int =
  class Local { def value(): Int = provider() }
  new Local().value()"""),
    Negative("public-default", "@allowExperimental def bad(x: Int = provider()): Int = x"),
    Negative("nested-default", """@allowExperimental def bad(): Int =
  def local(x: Int = provider()): Int = x
  local()"""),
    Negative("annotation-argument", """class Tag(x: Int) extends scala.annotation.StaticAnnotation
@Tag(provider()) @allowExperimental def bad(): Int = provider()""",
      "does not support experimental annotation arguments"),
    Negative("parameter-annotation", """class Tag(x: Int) extends scala.annotation.StaticAnnotation
@allowExperimental def bad(@Tag(provider()) x: Int): Int = provider()""",
      "does not support experimental annotation arguments"),
    Negative("nested-annotation", """class Tag(x: Int) extends scala.annotation.StaticAnnotation
@allowExperimental def bad(): Int =
  @Tag(provider()) def local(): Int = provider()
  local()""", "does not support experimental annotation arguments"),
    Negative("return-type-annotation", """class Tag(x: Int) extends scala.annotation.StaticAnnotation
@allowExperimental def bad(): Int @Tag(provider()) = provider()"""),
    Negative("parameter-type-annotation", """class Tag(x: Int) extends scala.annotation.StaticAnnotation
@allowExperimental def bad(x: Int @Tag(provider())): Int = provider()"""),
    Negative("ascription-type-annotation", """class Tag(x: Int) extends scala.annotation.StaticAnnotation
@allowExperimental def bad(): Int = (provider(): Int @Tag(provider()))"""),
    Negative("nested-type-annotation", """class Tag(x: Int) extends scala.annotation.StaticAnnotation
@allowExperimental def bad(x: List[Int @Tag(provider())]): Int = provider()"""),
    Negative("expression-annotation", """class Tag(x: Int) extends scala.annotation.StaticAnnotation
@allowExperimental def bad(): Int = (provider(): @Tag(provider()))""",
      "does not support experimental annotation arguments"),
    Negative("nested-expression-annotation", """class Tag(x: Int) extends scala.annotation.StaticAnnotation
@allowExperimental def bad(): Int = (provider(): @Tag((0: @Tag(provider()))))""",
      "does not support experimental annotation arguments"),
    Negative("import-sibling", "def bad(): Int = { import Terms.selected; selected() }"),
    Negative("import-later-unit", "def bad(): Int = { import Terms.selected; selected() }", extraUnit = true),
    Negative("late-refchecks-error", """trait Required { def missing: Int }
class Broken extends Required""", "needs to be abstract")
  )

  def verify(root: File, annotationJar: File, pluginJar: File,
      compilerClasspath: Seq[File], log: Logger): Unit = {
    val work = root / "target" / "m1-verification"
    IO.delete(work)
    IO.createDirectory(work)
    val compilerCp = compilerClasspath.map(_.getAbsolutePath)
    val libraries = compilerClasspath.filter { f =>
      f.getName.startsWith("scala3-library_3-") || f.getName.startsWith("scala-library-")
    }.map(_.getAbsolutePath)
    require(libraries.size == 2, "expected two Scala runtime libraries")
    val sourceCp = libraries :+ annotationJar.getAbsolutePath
    val failures = ArrayBuffer.empty[String]
    def check(label: String)(body: => Unit): Unit = {
      try { body; log.info(s"M1 PASS $label") }
      catch { case e: IllegalArgumentException =>
        failures += s"$label: ${e.getMessage}"; log.error(s"M1 FAIL $label: ${e.getMessage}")
      }
    }
    val trace = Seq("-color:never", "-Vprint:allowExperimentalCaptureOwners,allowExperimentalCheckReferences,allowExperimentalRestoreProviders")
    def compile(label: String, sources: Seq[(String, String)], cp: Seq[String],
        usePlugin: Boolean = true, flags: Seq[String] = trace): (Int, String, File) = {
      val dir = work / label
      val out = dir / "classes"
      IO.createDirectory(out)
      val files = sources.map { case (name, source) =>
        val file = dir / name; IO.write(file, source); file.getAbsolutePath
      }
      val args = Seq("-classpath", cp.mkString(File.pathSeparator), "-d", out.getAbsolutePath) ++
        (if (usePlugin) Seq(s"-Xplugin:${pluginJar.getAbsolutePath}") else Nil) ++ flags ++ files
      val result = run(root, compilerCp, "dotty.tools.dotc.Main", args)
      IO.write(dir / "compiler.log", result._2)
      (result._1, result._2, out)
    }
    val positive = compile("positive", Seq("Library.scala" -> (prelude + permitted),
      "Later.scala" -> "package m1\n@io.github.dmytromitin.allowexperimental.allowExperimental def laterAllowed(): Int = provider2()"), sourceCp)
    check("ordinary/private/term/stable/nested/import/multiple-unit positive") {
      require(positive._1 == 0, positive._2)
      providerState(positive._2, "allowExperimentalCaptureOwners", experimental = true)
      providerState(positive._2, "allowExperimentalCheckReferences", experimental = false)
      providerState(positive._2, "allowExperimentalRestoreProviders", experimental = true)
    }
    negatives.foreach { n =>
      val sources = if (n.extraUnit) Seq("Library.scala" -> (prelude + permitted),
        "ZForbidden.scala" -> ("package m1\n" + n.source))
      else Seq("Library.scala" -> (prelude + permitted + "\n" + n.source))
      val result = compile(n.name, sources, sourceCp)
      check(n.name) {
        require(result._1 != 0, "unexpectedly compiled")
        require(result._2.contains(n.diagnostic), s"missing ${n.diagnostic}: ${result._2}")
        if (n.early) require(!result._2.contains("[[syntax trees at end of allowExperimentalCheckReferences]]"),
          "expected experimental inline provider rejection before the late selective checker")
        if (Set("direct", "later-unit", "parameter-type", "same-provider-return", "late-refchecks-error",
            "return-type-annotation", "parameter-type-annotation", "ascription-type-annotation", "nested-type-annotation",
            "expression-annotation")(n.name)) {
          providerState(result._2, "allowExperimentalCheckReferences", experimental = n.name != "late-refchecks-error")
          providerState(result._2, "allowExperimentalRestoreProviders", experimental = true)
        }
        if (n.name == "inline-provider") require(result._2.contains("[[syntax trees at end of allowExperimentalCaptureOwners]]"),
          "inline-provider fixture no longer characterizes the post-capture path")
        if (n.name == "transparent-inline-provider") require(!result._2.contains("[[syntax trees at end of allowExperimentalCaptureOwners]]"),
          "transparent-inline fixture no longer characterizes the pre-capture path")
      }
    }
    val importOnly = """
@allowExperimental def importOnlyAllowed(): Int = { import Terms.selected; 0 }
def importOnlyOrdinary(): Int = { import Terms.selected; 0 }
"""
    Seq(true, false).foreach { plugin =>
      val result = compile(s"import-only-$plugin", Seq("Library.scala" -> (prelude + importOnly)), sourceCp, usePlugin = plugin)
      check(s"ordinary-qualifier import alone needs no permission (plugin=$plugin)") {
        require(result._1 == 0, result._2)
      }
    }
    val absent = compile("plugin-absent", Seq("Library.scala" -> (prelude + permitted)), sourceCp, usePlugin = false)
    check("plugin absent never grants M1 permission") {
      require(absent._1 != 0 && absent._2.contains(scalaDiagnostic), absent._2)
    }
    val stockMetadata = compile("stock-annotation-argument", Seq("Library.scala" -> (prelude + """
class Tag(x: Int) extends scala.annotation.StaticAnnotation
@Tag(provider()) def tagged(): Int = 0
""")), sourceCp, usePlugin = false)
    check("stock Scala annotation-argument characterization") { require(stockMetadata._1 == 0, stockMetadata._2) }
    val metadataControls = compile("annotation-controls", Seq("Library.scala" -> (prelude + """
class Tag(x: Int) extends scala.annotation.StaticAnnotation
@Tag(0) @allowExperimental def ordinaryMetadata(): Int = (provider(): @Tag(0))
@experimental def experimentalMetadata(): Int = (provider(): @Tag(provider()))
""")), sourceCp)
    check("ordinary metadata and genuine experimental scope stay valid") {
      require(metadataControls._1 == 0, metadataControls._2)
    }
    val repeated = compile("repeated-annotations", Seq("Library.scala" -> """package m1
import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental
class Meta(value: String) extends scala.annotation.StaticAnnotation
@Meta("before") @experimental("first-marker") @experimental("second-marker") @Meta("after")
def repeated(): Int = 1
@allowExperimental def usesRepeated(): Int = repeated()
"""), sourceCp)
    check("exact repeated/mixed provider annotation restoration") {
      require(repeated._1 == 0, repeated._2)
      val captured = phaseBody(repeated._2, "allowExperimentalCaptureOwners")
      val checked = phaseBody(repeated._2, "allowExperimentalCheckReferences")
      val restored = phaseBody(repeated._2, "allowExperimentalRestoreProviders")
      val markers = Seq("\"before\"", "\"first-marker\"", "\"second-marker\"", "\"after\"")
      require(markers.forall(captured.contains), s"missing original annotation fixture: $captured")
      require(markers.forall(restored.contains), s"provider annotation multiplicity not restored: $restored")
      require(markers.sortBy(captured.indexOf) == markers.sortBy(restored.indexOf),
        s"provider annotation order not restored: $restored")
      require(!checked.contains("first-marker") && !checked.contains("second-marker"),
        "repeated provider was not neutralized for the check window")
      require(checked.contains("\"before\"") && checked.contains("\"after\""), "unrelated provider metadata lost")
    }
    if (positive._1 == 0) {
      val externalCp = libraries :+ positive._3.getAbsolutePath
      val consumer = compile("external-allowed", Seq("Consumer.scala" -> """package external
def use(): Any = (m1.allowedIdent(), m1.allowedSelect(), m1.allowedStable(),
  m1.allowedNested(), m1.allowedImport(), m1.publicViaPrivate(), m1.laterAllowed(), m1.PrivateUse.facade())
"""), externalCp, usePlugin = false, flags = Nil)
      check("external consumer without marker/plugin") { require(consumer._1 == 0, consumer._2) }
      Seq("provider()", "provider2()", "stable", "Terms.selected()").zipWithIndex.foreach { case (call, i) =>
        val result = compile(s"external-provider-$i", Seq("Consumer.scala" -> s"package external\ndef bad(): Any = m1.$call"),
          externalCp, usePlugin = false, flags = Nil)
        check(s"external provider $call remains experimental") {
          require(result._1 != 0 && result._2.contains(scalaDiagnostic), result._2)
        }
      }
      val tasty = positive._3 / "m1" / "Library$package.tasty"
      val decompiled = run(root, compilerCp, "dotty.tools.dotc.decompiler.Main",
        Seq("-classpath", externalCp.mkString(File.pathSeparator), "-color:never", tasty.getAbsolutePath))
      IO.write(work / "positive" / "decompiled.log", decompiled._2)
      check("TASTy provider and consumer annotation boundaries") {
        require(decompiled._1 == 0, decompiled._2)
        Seq("provider", "provider2").foreach { name =>
          require(definition(decompiled._2, name).contains("@scala.annotation.experimental"), s"lost provider annotation: $name")
        }
        Seq("allowedIdent", "allowedSelect", "allowedStable", "allowedNested", "allowedImport", "implementation", "publicViaPrivate").foreach { name =>
          val line = definition(decompiled._2, name)
          require(!line.contains("@scala.annotation.experimental") && !line.contains("@io.github.dmytromitin.allowexperimental.allowExperimental"), s"annotation leak: $line")
        }
      }
      val privateTasty = run(root, compilerCp, "dotty.tools.dotc.decompiler.Main",
        Seq("-classpath", externalCp.mkString(File.pathSeparator), "-color:never",
          (positive._3 / "m1" / "PrivateUse.tasty").getAbsolutePath))
      IO.write(work / "positive" / "private-decompiled.log", privateTasty._2)
      check("private member marker stripped and ordinary facade") {
        require(privateTasty._1 == 0, privateTasty._2)
        Seq("hidden", "facade").foreach { name =>
          val line = definition(privateTasty._2, name)
          require(!line.contains("@scala.annotation.experimental") && !line.contains("allowExperimental"), s"private member annotation leak: $line")
        }
      }
    }
    require(failures.isEmpty, failures.mkString("\n"))
  }

  private def definition(output: String, name: String): String =
    output.split("\\R").find(_.contains(s"def $name(")).getOrElse(
      throw new IllegalArgumentException(s"missing definition $name: $output"))

  private def phaseBody(output: String, phase: String): String = {
    val marker = s"[[syntax trees at end of $phase]]"
    val start = output.indexOf(marker)
    require(start >= 0, s"missing phase $phase: $output")
    val next = output.indexOf("[[syntax trees at end of ", start + marker.length)
    val section = output.substring(start, if (next < 0) output.length else next)
    "unchanged since ([A-Za-z]+)".r.findFirstMatchIn(section) match {
      case Some(m) => phaseBody(output, m.group(1))
      case None => section
    }
  }

  private def providerState(output: String, phase: String, experimental: Boolean): Unit = {
    val body = phaseBody(output, phase)
    Seq("provider", "provider2", "selected").foreach { name =>
      require(definition(body, name).contains("@experimental") == experimental,
        s"wrong provider state for $name at $phase: $body")
    }
    val stable = body.split("\\R").find(_.contains("val stable:")).getOrElse(
      throw new IllegalArgumentException(s"missing stable provider at $phase"))
    require(stable.contains("@experimental") == experimental, s"wrong stable provider state at $phase: $stable")
  }

  private def run(root: File, cp: Seq[String], main: String, args: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val java = new File(sys.props("java.home"), "bin/java").getAbsolutePath
    val exit = Process(Seq(java, "-cp", cp.mkString(File.pathSeparator), main) ++ args, root).!(
      ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n')))
    (exit, output.result())
  }
}
