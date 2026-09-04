package io.github.dmytromitin.allowexperimental.plugin

import dotty.tools.dotc.config.Feature
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}
import dotty.tools.dotc.report

final class AllowExperimentalPlugin extends StandardPlugin:
  override val name: String = AllowExperimentalPluginEntrypoint.Name
  override val description: String = AllowExperimentalPluginEntrypoint.Description

  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    if options.nonEmpty then report.error(AllowExperimentalPluginEntrypoint.UnsupportedOptionsMessage)
    AllowExperimentalPluginEntrypoint.phases()

private[plugin] object CompilerAdapter:
  def isExperimentalEnabledByImport(using Context): Boolean = Feature.isExperimentalEnabledByImport
  def restoreIsRunnable(using Context): Boolean = !ctx.usedBestEffortTasty
  def enforceM4APhaseContract: Boolean = false
