import java.io.File
import sbt._
import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}

/** Exact-lane real Quotes Symbol.info macro boundary.
  * Producer and downstream consumer are always separate compiler invocations.
  */
object M3Verifier {
  private val scalaDiagnostic = "method info is marked @experimental"
  private val productInlineDiagnostic = "supports only non-inline def owners"
  private val derivedLiteral = "symbol-info-nonempty"

  private case class CompileResult(exit: Int, output: String, classes: File, args: Seq[String])

  def verify(root: File, scalaVersion: String, annotationJar: File, pluginJar: File,
      compilerClasspath: Seq[File], log: Logger): Unit = {
    VerificationLane.validateInputs(scalaVersion, annotationJar, pluginJar, compilerClasspath)
    val work = VerificationLane.workDirectory(root, scalaVersion, "m3")
    IO.delete(work)
    IO.createDirectory(work)
    VerificationLane.recordInputs(work, scalaVersion, annotationJar, pluginJar, compilerClasspath)
    log.info(s"M3 Scala $scalaVersion: ${work.getAbsolutePath}")

    val compilerCp = compilerClasspath.map(_.getAbsolutePath)
    val libraries = compilerClasspath.filter { file =>
      file.getName.startsWith("scala3-library_3-") || file.getName.startsWith("scala-library-")
    }.map(_.getAbsolutePath)
    require(libraries.size == 2, "expected two exact Scala runtime libraries")
    val producerCp = libraries :+ annotationJar.getAbsolutePath
    val failures = ArrayBuffer.empty[String]

    def check(label: String)(body: => Unit): Unit =
      try {
        body
        log.info(s"M3 PASS $label")
      } catch {
        case error: IllegalArgumentException =>
          failures += s"$label: ${error.getMessage}"
          log.error(s"M3 FAIL $label: ${error.getMessage}")
      }

    val trace = Seq(
      "-color:never",
      "-Vprint:allowExperimentalCaptureOwners,allowExperimentalCheckReferences,allowExperimentalRestoreProviders"
    )

    def compile(label: String, sources: Seq[(String, String)], cp: Seq[String],
        usePlugin: Boolean, flags: Seq[String]): CompileResult = {
      val dir = work / label
      val classes = dir / "classes"
      IO.createDirectory(classes)
      val files = sources.map { case (name, source) =>
        val file = dir / name
        IO.write(file, source)
        file.getAbsolutePath
      }
      val args =
        Seq("-classpath", cp.mkString(File.pathSeparator), "-d", classes.getAbsolutePath) ++
          (if (usePlugin) Seq(s"-Xplugin:${pluginJar.getAbsolutePath}") else Nil) ++
          flags ++ files
      val result = runJava(root, compilerCp, "dotty.tools.dotc.Main", args)
      IO.write(dir / "compiler-arguments.txt", args.mkString("\n") + "\n")
      IO.write(dir / "compiler.log", result._2)
      CompileResult(result._1, result._2, classes, args)
    }

    val macroWithoutPermission = """package m3
import scala.quoted.*

object MacroApi:
  inline def symbolInfoSummary[T]: String =
    ${ symbolInfoSummaryImpl[T] }

  private def symbolInfoSummaryImpl[T: Type](using Quotes): Expr[String] =
    import quotes.reflect.*
    val info = TypeRepr.of[T].typeSymbol.info
    Expr(if info.show.nonEmpty then "symbol-info-nonempty" else "symbol-info-empty")
"""
    val unpermitted = compile("producer-without-permission",
      Seq("MacroApi.scala" -> macroWithoutPermission), producerCp, usePlugin = true, flags = Seq("-color:never"))
    check("real Symbol.info producer without permission rejected") {
      require(unpermitted.exit != 0, "unpermitted real Symbol.info producer unexpectedly compiled")
      require(unpermitted.output.contains(scalaDiagnostic), s"missing exact experimental diagnostic: ${unpermitted.output}")
      require(!unpermitted.args.contains("-experimental"), "unpermitted control used global -experimental")
    }

    val macroWithPermission = """package m3
import scala.quoted.*
import io.github.dmytromitin.allowexperimental.allowExperimental

object MacroApi:
  inline def symbolInfoSummary[T]: String =
    ${ symbolInfoSummaryImpl[T] }

  @allowExperimental
  private def symbolInfoSummaryImpl[T: Type](using Quotes): Expr[String] =
    import quotes.reflect.*
    val info = TypeRepr.of[T].typeSymbol.info
    Expr(if info.show.nonEmpty then "symbol-info-nonempty" else "symbol-info-empty")
"""
    val positive = compile("producer-with-permission", Seq("MacroApi.scala" -> macroWithPermission),
      producerCp, usePlugin = true, flags = trace)
    check("real Symbol.info producer with private implementation permission") {
      require(positive.exit == 0, positive.output)
      require(positive.output.contains("SymbolMethods.info"), "phase evidence lacks real Symbol.info reference")
      require(!positive.args.contains("-experimental"), "positive producer used global -experimental")
    }

    if (positive.exit == 0) {
      val producerTasty = positive.classes / "m3" / "MacroApi.tasty"
      val producerDecompiled = runJava(root, compilerCp, "dotty.tools.dotc.decompiler.Main",
        Seq("-classpath", producerCp.mkString(File.pathSeparator), "-color:never", producerTasty.getAbsolutePath))
      IO.write(work / "producer-with-permission" / "decompiled.log", producerDecompiled._2)
      val javap = new File(sys.props("java.home"), "bin/javap").getAbsolutePath
      val producerBytecode = runExecutable(root,
        Seq(javap, "-classpath", positive.classes.getAbsolutePath, "-p", "m3.MacroApi$"))
      IO.write(work / "producer-with-permission" / "javap.log", producerBytecode._2)
      check("producer TASTy confines Symbol.info to ordinary private implementation") {
        require(producerDecompiled._1 == 0, producerDecompiled._2)
        require(producerBytecode._1 == 0, producerBytecode._2)
        val frontend = definition(producerDecompiled._2, "symbolInfoSummary")
        val implementation = definition(producerDecompiled._2, "symbolInfoSummaryImpl")
        val implementationBytecode = producerBytecode._2.split("\\R").find(_.contains(" symbolInfoSummaryImpl("))
          .getOrElse(throw new IllegalArgumentException(s"classfile lacks macro implementation: ${producerBytecode._2}"))
        require(implementationBytecode.trim.startsWith("private "),
          s"macro implementation is not private in the classfile API: $implementationBytecode")
        val implementationStart = producerDecompiled._2.indexOf(implementation)
        val implementationEnd = producerDecompiled._2.indexOf("final def inline$symbolInfoSummaryImpl", implementationStart)
        require(implementationEnd > implementationStart, "unable to delimit private macro implementation body")
        val implementationBody = producerDecompiled._2.substring(implementationStart, implementationEnd)
        require(implementationBody.contains("SymbolMethods.info"),
          "private macro implementation TASTy lacks Symbol.info")
        require(!frontend.contains("SymbolMethods.info"),
          s"public inline frontend directly contains Symbol.info: $frontend")
        Seq(frontend, implementation).foreach { header =>
          require(!header.contains("@scala.annotation.experimental"), s"unexpected experimental API annotation: $header")
          require(!header.contains("@io.github.dmytromitin.allowexperimental.allowExperimental"),
            s"permission marker persisted on definition: $header")
        }
        require(!producerDecompiled._2.contains("@io.github.dmytromitin.allowexperimental.allowExperimental"),
          "permission marker annotation persisted in producer decompilation")
      }

      val downstreamCp = libraries :+ positive.classes.getAbsolutePath
      IO.write(work / "downstream-classpath.txt", downstreamCp.mkString("\n") + "\n")
      val downstreamSource = """package downstream
import m3.MacroApi

val observed: String = MacroApi.symbolInfoSummary[List[Int]]
"""
      val downstream = compile("downstream", Seq("Consumer.scala" -> downstreamSource),
        downstreamCp, usePlugin = false, flags = Seq("-color:never"))
      check("separate downstream compiles without Allow Experimental or global flag") {
        require(downstream.exit == 0, downstream.output)
        require(!downstreamCp.contains(annotationJar.getAbsolutePath), "downstream classpath contains marker artifact")
        require(!downstreamCp.contains(pluginJar.getAbsolutePath), "downstream classpath contains plugin artifact")
        require(!downstream.args.exists(_.startsWith("-Xplugin:")), "downstream used a compiler plugin")
        require(!downstream.args.contains("-experimental"), "downstream used global -experimental")
        require(!downstreamSource.contains("@experimental") && !downstreamSource.contains("@allowExperimental"),
          "downstream source contains an authority annotation")
      }

      if (downstream.exit == 0) {
        val bytecode = runExecutable(root,
          Seq(javap, "-classpath", downstream.classes.getAbsolutePath, "-c", "-p", "downstream.Consumer$package$"))
        IO.write(work / "downstream" / "javap.log", bytecode._2)
        check("Symbol.info-dependent result executed during downstream expansion") {
          require(bytecode._1 == 0, bytecode._2)
          require(bytecode._2.contains(s"String $derivedLiteral"), s"missing derived expansion literal: ${bytecode._2}")
        }
      }
    }

    val inlineOwnerSource = """package m3
import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental

@experimental def provider(): Int = 1
@allowExperimental inline def unsafe(): Int = provider()
"""
    val inlineOwner = compile("inline-allow-owner-negative",
      Seq("InlineOwner.scala" -> inlineOwnerSource), producerCp, usePlugin = true, flags = Seq("-color:never"))
    check("Allow Experimental inline owner remains unsupported") {
      require(inlineOwner.exit != 0, "inline permission owner unexpectedly compiled")
      require(inlineOwner.output.contains(productInlineDiagnostic), s"missing inline-owner diagnostic: ${inlineOwner.output}")
      require(!inlineOwner.args.contains("-experimental"), "inline-owner control used global -experimental")
    }

    val serializedInlineAuthority =
      if (scalaVersion == "3.3.8") "@experimental " else ""
    val serializedProducerSource = s"""package serialized
import scala.annotation.experimental

@experimental def provider(): Int = 1
${serializedInlineAuthority}inline def unsafe(): Int = provider()
"""
    val serializedUsesGlobalFlag = scalaVersion != "3.3.8"
    val serializedFlags = Seq("-color:never") ++ (if (serializedUsesGlobalFlag) Seq("-experimental") else Nil)
    IO.write(work / "serialized-inline-authority.txt",
      if (serializedUsesGlobalFlag) "global-experimental-flag=yes; reason=negative-only producer authority\n"
      else "global-experimental-flag=no; reason=Scala 3.3.8 has no -experimental flag; unsafe is explicitly @experimental\n")
    val serializedProducer = compile("serialized-inline-producer",
      Seq("Serialized.scala" -> serializedProducerSource), libraries, usePlugin = false,
      flags = serializedFlags)
    check("negative-only serialized inline producer manufactured with Scala authority") {
      require(serializedProducer.exit == 0, serializedProducer.output)
      require(serializedProducer.args.contains("-experimental") == serializedUsesGlobalFlag,
        s"wrong global-flag state for negative serialized producer: ${serializedProducer.args}")
      require(!serializedProducer.args.exists(_.startsWith("-Xplugin:")), "negative serialized producer used Allow Experimental")
    }
    if (serializedProducer.exit == 0) {
      val serializedConsumerSource = """package serializedconsumer
val observed: Int = serialized.unsafe()
"""
      val serializedConsumer = compile("serialized-inline-downstream",
        Seq("Consumer.scala" -> serializedConsumerSource), libraries :+ serializedProducer.classes.getAbsolutePath,
        usePlugin = false, flags = Seq("-color:never"))
      check("direct serialized experimental inline-body reference rejected downstream") {
        require(serializedConsumer.exit != 0, "serialized inline experimental reference unexpectedly compiled downstream")
        val serializedDiagnostic =
          if (scalaVersion == "3.3.8")
            "method unsafe is marked @experimental and therefore may only be used in an experimental scope."
          else "method unsafe is marked @experimental: Added by -experimental"
        require(serializedConsumer.output.contains(serializedDiagnostic),
          s"missing exact serialized-inline downstream diagnostic: ${serializedConsumer.output}")
        require(!serializedConsumer.args.contains("-experimental"), "serialized downstream used global -experimental")
        require(!serializedConsumer.args.exists(_.startsWith("-Xplugin:")), "serialized downstream used Allow Experimental")
      }
    }

    require(failures.isEmpty, failures.mkString("\n"))
  }

  private def definition(output: String, name: String): String =
    output.split("\\R").find(_.contains(s"def $name[")).getOrElse(
      throw new IllegalArgumentException(s"missing definition $name: $output"))

  private def runJava(root: File, cp: Seq[String], main: String, args: Seq[String]): (Int, String) = {
    val java = new File(sys.props("java.home"), "bin/java").getAbsolutePath
    runExecutable(root, Seq(java, "-cp", cp.mkString(File.pathSeparator), main) ++ args)
  }

  private def runExecutable(root: File, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val exit = Process(command, root).!(
      ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n')))
    (exit, output.result())
  }
}
