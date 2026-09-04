package io.github.dmytromitin.allowexperimental.plugin

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}

final class AllowExperimentalPlugin extends StandardPlugin:
  override val name: String = AllowExperimentalPluginEntrypoint.Name
  override val description: String = AllowExperimentalPluginEntrypoint.Description

  override def init(options: List[String]): List[PluginPhase] =
    require(options.isEmpty, AllowExperimentalPluginEntrypoint.UnsupportedOptionsMessage)
    AllowExperimentalPluginEntrypoint.phases()

private[plugin] object CompilerAdapter:
  // 3.3.8 has neither import-enabled experimental auto-features nor
  // best-effort TASTy compilation state on Context.
  def isExperimentalEnabledByImport(using Context): Boolean = false
  def restoreIsRunnable(using Context): Boolean = true
  def enforceM4APhaseContract: Boolean = false
