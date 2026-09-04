package io.github.dmytromitin.allowexperimental.m4aobserver

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.TreeOps
import dotty.tools.dotc.core.*
import Contexts.*
import Phases.unfusedPhases
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}
import dotty.tools.dotc.report
import dotty.tools.dotc.transform.PostInlining
import dotty.tools.dotc.transform.CrossVersionChecks

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable

final class M4AObserverPlugin extends StandardPlugin:
  override val name: String = "m4a-observer"
  override val description: String =
    "exact-Scala-3.9.0 adversarial observer for Allow Experimental M4A"

  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    val config = ObserverConfig.parse(options)
    config.probe match
      case "p0" => List(ProbeP0(config))
      case "p1" => List(ProbeP1(config), ProbeP3Audit(config))
      case "p2" => List(ProbeP2(config), ProbeP3Audit(config))
      case "p3" => List(ProbeP3(config))
      case "harmless" => List(HarmlessProbe(config))
      case other =>
        report.error(s"m4a-observer unknown probe: $other")
        Nil

private final case class ObserverConfig(
    probe: String,
    providerFullName: String,
    ordinaryFullName: String,
    log: Path
)

private object ObserverConfig:
  def parse(options: List[String])(using Context): ObserverConfig =
    val parsed = options.map: option =>
      option.split("=", 2).toList match
        case key :: value :: Nil => key -> value
        case _ =>
          report.error(s"m4a-observer malformed option: $option")
          "" -> ""
    .toMap
    def required(key: String): String =
      parsed.get(key) match
        case Some(value) => value
        case None =>
          report.error(s"m4a-observer missing option: $key")
          ""
    ObserverConfig(
      required("probe"),
      required("provider"),
      required("ordinary"),
      Path.of(required("log"))
    )

private abstract class ObserverPhase(config: ObserverConfig) extends PluginPhase:
  protected def probeLabel: String

  protected def shouldInspectOrdinary: Boolean = false

  override def runOn(units: List[CompilationUnit])(using runCtx: Context): List[CompilationUnit] =
    given Context = runCtx.fresh.setPhase(this.start)
    val symbols = mutable.LinkedHashMap.empty[String, Symbols.Symbol]
    val candidateNames = mutable.LinkedHashSet.empty[String]
    units.foreach: unit =>
      unit.tpdTree.foreachSubTree: tree =>
        val symbol = tree.symbol
        if symbol.exists then
          val fullName = symbol.fullName.toString
          if symbol.name.toString == "provider" || symbol.name.toString == "harmlessSentinel" then
            candidateNames += fullName
          if fullName == config.providerFullName || fullName == config.ordinaryFullName then
            symbols.getOrElseUpdate(fullName, symbol)

    symbols.get(config.providerFullName) match
      case Some(provider) =>
        val ordinary = symbols.get(config.ordinaryFullName)
        val phasePlan = unfusedPhases.iterator
          .filter(_.exists)
          .map: phase =>
            val kind = if phase.isInstanceOf[PluginPhase] then "plugin" else "builtin"
            s"${phase.phaseName}:$kind"
          .mkString(",")
        val lines = List(
          s"probe=$probeLabel",
          s"phase=$phaseName",
          s"phasePlan=$phasePlan",
          s"provider=${provider.fullName}",
          s"providerOwner=${provider.owner.fullName}",
          s"providerHasExperimentalAnnotation=${provider.hasAnnotation(runCtx.definitions.ExperimentalAnnot)}",
          s"providerIsExperimental=${provider.isExperimental}",
          s"reportedErrors=${runCtx.reporter.hasErrors}",
          s"ordinary=${ordinary.map(_.fullName.toString).getOrElse("NOT_INSPECTED")}",
          s"ordinaryIsExperimental=${ordinary.exists(_.isExperimental)}"
        )
        Files.writeString(
          config.log,
          lines.mkString("", "\n", "\n---\n"),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        )
        if shouldInspectOrdinary && ordinary.isEmpty then
          report.error(s"m4a-observer did not resolve ordinary symbol ${config.ordinaryFullName}")
      case None =>
        report.error(s"m4a-observer did not resolve provider symbol ${config.providerFullName}; candidates=${candidateNames.mkString(",")}")
    units

private final class ProbeP0(config: ObserverConfig) extends ObserverPhase(config):
  override val phaseName: String = "m4aProbeP0"
  override val runsAfter: Set[String] = Set(PostInlining.name)
  override val runsBefore: Set[String] = Set("allowExperimentalCheckReferences")
  protected val probeLabel: String = "P0"

private final class ProbeP1(config: ObserverConfig) extends ObserverPhase(config):
  override val phaseName: String = "m4aProbeP1"
  override val runsAfter: Set[String] = Set("allowExperimentalCheckReferences")
  override val runsBefore: Set[String] = Set(CrossVersionChecks.name)
  protected val probeLabel: String = "P1"

private final class ProbeP2(config: ObserverConfig) extends ObserverPhase(config):
  override val phaseName: String = "m4aProbeP2"
  override val runsAfter: Set[String] = Set(CrossVersionChecks.name)
  override val runsBefore: Set[String] = Set("allowExperimentalRestoreProviders")
  protected val probeLabel: String = "P2"

private final class ProbeP3Audit(config: ObserverConfig) extends ObserverPhase(config):
  override val phaseName: String = "m4aProbeP3Audit"
  override val runsAfter: Set[String] = Set("allowExperimentalRestoreProviders")
  override def isRunnable(using Context): Boolean = true
  protected val probeLabel: String = "P3_AUDIT"

private final class ProbeP3(config: ObserverConfig) extends ObserverPhase(config):
  override val phaseName: String = "m4aProbeP3"
  override val runsAfter: Set[String] = Set("allowExperimentalRestoreProviders")
  override def isRunnable(using Context): Boolean = true
  protected val probeLabel: String = "P3"

private final class HarmlessProbe(config: ObserverConfig) extends ObserverPhase(config):
  override val phaseName: String = "m4aHarmlessObserver"
  override val runsAfter: Set[String] = Set("allowExperimentalRestoreProviders")
  protected val probeLabel: String = "HARMLESS"
  override protected val shouldInspectOrdinary: Boolean = true
