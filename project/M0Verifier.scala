import java.io.File

import sbt._

import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}

object M0Verifier {
  private final case class Compilation(exitCode: Int, output: String)

  def verify(
      root: File,
      scalaVersion: String,
      annotationJar: File,
      pluginJar: File,
      compilerClasspath: Seq[File],
      log: Logger
  ): Unit = {
    VerificationLane.validateInputs(scalaVersion, annotationJar, pluginJar, compilerClasspath)
    val work = VerificationLane.workDirectory(root, scalaVersion, "m0")
    IO.delete(work)
    IO.createDirectory(work)
    VerificationLane.recordInputs(work, scalaVersion, annotationJar, pluginJar, compilerClasspath)
    log.info(s"M0 Scala $scalaVersion: ${work.getAbsolutePath}")

    val compilerCp = compilerClasspath.map(_.getAbsolutePath)
    val libraryCp = compilerCp.filter { path =>
      val name = new File(path).getName
      name.startsWith("scala3-library_3-") || name.startsWith("scala-library-")
    }
    require(libraryCp.size == 2, s"expected exact Scala 3 library classpath, found: ${libraryCp.mkString(", ")}")
    val annotationCp = libraryCp :+ annotationJar.getAbsolutePath
    val providerOut = work / "provider"

    val provider = compile(
      root,
      fixtureSources(root, "provider"),
      providerOut,
      compilerCp,
      annotationCp,
      Some(pluginJar)
    )
    expectSuccess("annotated ordinary definition", provider)
    log.info("M0 PASS annotated ordinary definition and ordinary wrapper")

    val direct = compile(
      root,
      fixtureSources(root, "direct-negative"),
      work / "direct-negative",
      compilerCp,
      annotationCp,
      Some(pluginJar)
    )
    expectExperimentalFailure("direct provider negative", direct)
    log.info("M0 PASS direct provider negative")

    val sibling = compile(
      root,
      fixtureSources(root, "sibling-negative"),
      work / "sibling-negative",
      compilerCp,
      annotationCp,
      Some(pluginJar)
    )
    expectExperimentalFailure("sibling isolation", sibling)
    log.info("M0 PASS sibling isolation")

    val laterUnit = compile(
      root,
      fixtureSources(root, "later-unit-negative"),
      work / "later-unit-negative",
      compilerCp,
      annotationCp,
      Some(pluginJar)
    )
    expectExperimentalFailure("later-unit isolation", laterUnit)
    log.info("M0 PASS later-unit isolation")

    val externalClasspath = libraryCp :+ providerOut.getAbsolutePath
    val externalAllowed = compile(
      root,
      fixtureSources(root, "external-allowed"),
      work / "external-allowed",
      compilerCp,
      externalClasspath,
      None
    )
    expectSuccess("external allowed consumer without plugin or marker", externalAllowed)
    log.info("M0 PASS external allowed consumer without plugin or marker")

    val externalProvider = compile(
      root,
      fixtureSources(root, "external-provider-negative"),
      work / "external-provider-negative",
      compilerCp,
      externalClasspath,
      None
    )
    expectExperimentalFailure("external provider negative", externalProvider)
    log.info("M0 PASS external provider remains experimental")

    val pluginAbsent = compile(
      root,
      fixtureSources(root, "plugin-absent"),
      work / "plugin-absent",
      compilerCp,
      annotationCp,
      None
    )
    expectExperimentalFailure("plugin-absent experimental use", pluginAbsent)
    log.info("M0 PASS plugin-absent experimental use fails closed")

    val meaninglessMarker = compile(
      root,
      fixtureSources(root, "plugin-absent-meaningless"),
      work / "plugin-absent-meaningless",
      compilerCp,
      annotationCp,
      None
    )
    expectSuccess("plugin-absent meaningless marker characterization", meaninglessMarker)
    val meaninglessTasty = work / "plugin-absent-meaningless" / "m0" /
      "pluginabsentmeaningless" / "MeaninglessMarker$package.tasty"
    val meaninglessDecompilation = decompile(
      root,
      meaninglessTasty,
      compilerCp,
      annotationCp :+ (work / "plugin-absent-meaningless").getAbsolutePath
    )
    expectSuccess("plugin-absent marker decompilation", meaninglessDecompilation)
    IO.write(work / "meaningless-marker-decompiled.log", meaninglessDecompilation.output)
    val meaninglessSource = stripAnsi(meaninglessDecompilation.output)
    require(
      definitionLine(meaninglessSource, "ordinary").contains("allowExperimental"),
      "plugin-absent marker characterization did not retain the annotation on ordinary"
    )
    log.info("M0 PASS plugin-absent meaningless marker is accepted and retained")

    val inline = compile(
      root,
      fixtureSources(root, "inline-negative"),
      work / "inline-negative",
      compilerCp,
      annotationCp,
      Some(pluginJar)
    )
    expectFailureContaining(
      "inline owner rejection",
      inline,
      "supports only non-inline def owners"
    )
    log.info("M0 PASS inline owner fails closed")

    val classOwnerProvider = compile(
      root,
      fixtureSources(root, "class-owner-provider-negative"),
      work / "class-owner-provider-negative",
      compilerCp,
      annotationCp,
      Some(pluginJar)
    )
    expectFailureContaining(
      "experimental class-owner provider rejection",
      classOwnerProvider,
      "does not support experimental providers inherited from class owners"
    )
    log.info("M0 PASS experimental class-owner provider fails closed")

    val overridingProvider = compile(
      root,
      fixtureSources(root, "override-provider-negative"),
      work / "override-provider-negative",
      compilerCp,
      annotationCp,
      Some(pluginJar)
    )
    expectFailureContaining(
      "experimental overriding provider rejection",
      overridingProvider,
      "does not support experimental providers that participate in overrides"
    )
    log.info("M0 PASS experimental overriding provider fails closed")

    val overriddenProvider = compile(
      root,
      fixtureSources(root, "overridden-provider-negative"),
      work / "overridden-provider-negative",
      compilerCp,
      annotationCp,
      Some(pluginJar)
    )
    expectFailureContaining(
      "experimental overridden provider rejection",
      overriddenProvider,
      "does not support experimental providers that participate in overrides"
    )
    log.info("M0 PASS experimental overridden provider fails closed")

    val tasty = providerOut / "m0" / "Library$package.tasty"
    require(tasty.isFile, s"missing provider TASTy: ${tasty.getAbsolutePath}")
    val decompilation = decompile(root, tasty, compilerCp, externalClasspath)
    expectSuccess("provider TASTy decompilation", decompilation)
    IO.write(work / "provider-decompiled.log", decompilation.output)
    val decompiledSource = stripAnsi(decompilation.output)
    val fooLine = definitionLine(decompiledSource, "foo")
    val barLine = definitionLine(decompiledSource, "bar")
    val bazLine = definitionLine(decompiledSource, "baz")
    require(fooLine.contains("@scala.annotation.experimental"),
      s"provider TASTy does not retain @experimental on foo: $fooLine")
    require(!barLine.contains("experimental") && !barLine.contains("allowExperimental"),
      s"provider TASTy leaks an annotation on bar: $barLine")
    require(!bazLine.contains("experimental") && !bazLine.contains("allowExperimental"),
      s"provider TASTy leaks an annotation on baz: $bazLine")
    log.info("M0 PASS TASTy decompiler inspection: foo experimental; bar/baz marker-free and non-experimental")
  }

  private def fixtureSources(root: File, name: String): Seq[File] = {
    val directory = root / "verification" / "permission" / "fixtures" / name
    val sources = (directory ** "*.scala").get.sorted
    require(sources.nonEmpty, s"fixture has no Scala sources: ${directory.getAbsolutePath}")
    sources
  }

  private def compile(
      root: File,
      sources: Seq[File],
      output: File,
      compilerClasspath: Seq[String],
      sourceClasspath: Seq[String],
      plugin: Option[File]
  ): Compilation = {
    IO.createDirectory(output)
    val java = new File(sys.props("java.home"), "bin/java").getAbsolutePath
    val separator = File.pathSeparator
    val arguments = ArrayBuffer(
      java,
      "-cp",
      compilerClasspath.mkString(separator),
      "dotty.tools.dotc.Main",
      "-classpath",
      sourceClasspath.mkString(separator),
      "-d",
      output.getAbsolutePath
    )
    plugin.foreach(jar => arguments += s"-Xplugin:${jar.getAbsolutePath}")
    arguments ++= sources.map(_.getAbsolutePath)

    val outputBuffer = new StringBuilder
    val logger = ProcessLogger(
      line => outputBuffer.append(line).append('\n'),
      line => outputBuffer.append(line).append('\n')
    )
    val exit = Process(arguments.toSeq, root).!(logger)
    IO.write(output / "compiler-command.txt", arguments.mkString("\n") + "\n")
    IO.write(output / "compiler.log", outputBuffer.result())
    Compilation(exit, outputBuffer.result())
  }

  private def decompile(
      root: File,
      tasty: File,
      compilerClasspath: Seq[String],
      sourceClasspath: Seq[String]
  ): Compilation = {
    val java = new File(sys.props("java.home"), "bin/java").getAbsolutePath
    val separator = File.pathSeparator
    val arguments = Seq(
      java,
      "-cp",
      compilerClasspath.mkString(separator),
      "dotty.tools.dotc.decompiler.Main",
      "-classpath",
      sourceClasspath.mkString(separator),
      tasty.getAbsolutePath
    )
    val outputBuffer = new StringBuilder
    val logger = ProcessLogger(
      line => outputBuffer.append(line).append('\n'),
      line => outputBuffer.append(line).append('\n')
    )
    val exit = Process(arguments, root).!(logger)
    Compilation(exit, outputBuffer.result())
  }

  private def stripAnsi(value: String): String =
    value.replaceAll("\\u001B\\[[;\\d]*m", "")

  private def definitionLine(source: String, name: String): String =
    source.split("\\R").iterator.find(_.contains(s"def $name")).getOrElse {
      throw new IllegalArgumentException(s"decompiled TASTy has no definition `$name`:\n$source")
    }

  private def expectSuccess(label: String, result: Compilation): Unit =
    require(result.exitCode == 0, s"$label unexpectedly failed:\n${result.output}")

  private def expectExperimentalFailure(label: String, result: Compilation): Unit =
    expectFailureContaining(label, result, "marked @experimental")

  private def expectFailureContaining(label: String, result: Compilation, fragment: String): Unit = {
    require(result.exitCode != 0, s"$label unexpectedly compiled")
    require(
      result.output.contains(fragment),
      s"$label failed without expected diagnostic `$fragment`:\n${result.output}"
    )
  }
}
