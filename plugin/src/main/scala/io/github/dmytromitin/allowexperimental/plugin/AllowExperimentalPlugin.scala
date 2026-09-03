package io.github.dmytromitin.allowexperimental.plugin

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.config.Feature
import dotty.tools.dotc.core.*
import Annotations.Annotation
import Contexts.*
import Flags.*
import Symbols.*
import Types.*
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}
import dotty.tools.dotc.report
import dotty.tools.dotc.transform.{Pickler, PostInlining, PostTyper}
import dotty.tools.dotc.transform.CrossVersionChecks
import dotty.tools.dotc.util.SrcPos

import scala.collection.mutable

final class AllowExperimentalPlugin extends StandardPlugin:
  override val name: String = "allow-experimental"
  override val description: String =
    "provisional Scala 3.9.0 proof of non-propagating experimental permission for ordinary defs"

  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    if options.nonEmpty then
      report.error("allow-experimental M0 accepts no plugin options")
    val state = CompilationState()
    List(CaptureAllowedOwners(state), CheckAllowedReferences(state), RestoreExperimentalProviders(state))

private object AllowExperimentalPlugin:
  val MarkerClassName = "io.github.dmytromitin.allowexperimental.allowExperimental"
  val UnsupportedOwnerMessage =
    "@allowExperimental M0 supports only non-inline def owners"
  val UnsupportedClassCarrierMessage =
    "@allowExperimental M0 does not support experimental providers inherited from class owners"
  val UnsupportedOverrideMessage =
    "@allowExperimental M0 does not support experimental providers that participate in overrides"

private final class CompilationState:
  val allowedOwners: mutable.LinkedHashSet[Symbol] = mutable.LinkedHashSet.empty
  val providerAnnotations: mutable.LinkedHashMap[Symbol, Annotation] = mutable.LinkedHashMap.empty
  val providerPositions: mutable.LinkedHashMap[Symbol, SrcPos] = mutable.LinkedHashMap.empty
  val encounteredMethods: mutable.LinkedHashSet[Symbol] = mutable.LinkedHashSet.empty
  val neutralizedProviders: mutable.LinkedHashSet[Symbol] = mutable.LinkedHashSet.empty

  def markerSymbol(using Context): Symbol =
    getClassIfDefined(AllowExperimentalPlugin.MarkerClassName)

  def isAllowedScope(owner: Symbol)(using Context): Boolean =
    allowedOwners.contains(owner) || owner.ownersIterator.exists(allowedOwners.contains)

  def experimentalCarrier(sym: Symbol)(using Context): Symbol =
    if sym.hasAnnotation(defn.ExperimentalAnnot) then sym
    else if sym.owner.hasAnnotation(defn.ExperimentalAnnot) then sym.owner
    else NoSymbol

  def rememberProvider(sym: Symbol, pos: SrcPos)(using Context): Unit =
    val carrier = experimentalCarrier(sym)
    if !carrier.exists then
      report.error(
        s"allow-experimental internal invariant failed: ${sym.showLocated} is experimental without a recoverable annotation carrier",
        pos
      )
    else if carrier != sym then
      report.error(AllowExperimentalPlugin.UnsupportedClassCarrierMessage, pos)
    else
      carrier.getAnnotation(defn.ExperimentalAnnot) match
        case Some(annotation) =>
          providerAnnotations.getOrElseUpdate(carrier, annotation)
          providerPositions.getOrElseUpdate(carrier, pos)
        case None =>
          report.error(
            s"allow-experimental internal invariant failed: ${carrier.showLocated} lost its experimental annotation",
            pos
          )

private final class CaptureAllowedOwners(state: CompilationState) extends PluginPhase:
  override val phaseName: String = "allowExperimentalCaptureOwners"
  override val runsAfter: Set[String] = Set(PostTyper.name)
  override val runsBefore: Set[String] = Set(Pickler.name)

  private def capture(symbol: Symbol, pos: SrcPos)(using Context): Unit =
    val marker = state.markerSymbol
    if marker.exists && symbol.hasAnnotation(marker) then
      val supported = symbol.isTerm && symbol.is(Method) && !symbol.isConstructor && !symbol.is(Inline)
      if supported then state.allowedOwners += symbol
      else report.error(AllowExperimentalPlugin.UnsupportedOwnerMessage, pos)
      symbol.removeAnnotation(marker)
      if symbol.hasAnnotation(marker) then
        report.error("allow-experimental internal invariant failed: permission marker removal did not take effect", pos)

  override def transformDefDef(tree: tpd.DefDef)(using Context): tpd.Tree =
    capture(tree.symbol, tree.srcPos)
    tree

  override def transformValDef(tree: tpd.ValDef)(using Context): tpd.Tree =
    capture(tree.symbol, tree.srcPos)
    tree

  override def transformTypeDef(tree: tpd.TypeDef)(using Context): tpd.Tree =
    capture(tree.symbol, tree.srcPos)
    tree

private final class CheckAllowedReferences(state: CompilationState) extends PluginPhase:
  import tpd.*

  override val phaseName: String = "allowExperimentalCheckReferences"
  override val runsAfter: Set[String] = Set(PostInlining.name)
  override val runsBefore: Set[String] = Set(CrossVersionChecks.name)

  private def checkTermReference(sym: Symbol, pos: SrcPos)(using Context): Unit =
    if sym.isExperimental then
      val compilerPermission = ctx.owner.isInExperimentalScope
      val markerPermission = state.isAllowedScope(ctx.owner)
      if markerPermission && !compilerPermission then state.rememberProvider(sym, pos)
      else if !compilerPermission then Feature.checkExperimentalDef(sym, pos)

  private def checkUnsupportedReference(sym: Symbol, pos: SrcPos)(using Context): Unit =
    if sym.isExperimental && !ctx.owner.isInExperimentalScope then
      Feature.checkExperimentalDef(sym, pos)

  override def transformIdent(tree: Ident)(using Context): Ident =
    checkTermReference(tree.symbol, tree.srcPos)
    tree

  override def transformSelect(tree: Select)(using Context): Select =
    checkTermReference(tree.symbol, tree.srcPos)
    tree

  override def transformNew(tree: New)(using Context): New =
    checkUnsupportedReference(tree.tpe.typeSymbol, tree.srcPos)
    tree

  override def transformTypeTree(tree: TypeTree)(using Context): TypeTree =
    tree.tpe.foreachPart:
      case TypeRef(_, sym: Symbol) => checkUnsupportedReference(sym, tree.srcPos)
      case TermRef(_, sym: Symbol) => checkUnsupportedReference(sym, tree.srcPos)
      case _ =>
    tree

  override def transformOther(tree: Tree)(using Context): Tree =
    val inPackage = ctx.owner.is(Package) || ctx.owner.isPackageObject
    if !(inPackage && tree.isInstanceOf[ImportOrExport] && Feature.isExperimentalEnabledByImport) then
      tree.foreachSubTree:
        case ref: Ident => checkUnsupportedReference(ref.symbol, ref.srcPos)
        case ref: Select => checkUnsupportedReference(ref.symbol, ref.srcPos)
        case ref: TypeTree => transformTypeTree(ref)
        case _ =>
    tree

  override def transformDefDef(tree: DefDef)(using Context): DefDef =
    state.encounteredMethods += tree.symbol
    tree

  override def runOn(units: List[CompilationUnit])(using runCtx: Context): List[CompilationUnit] =
    val checked = super.runOn(units)
    def guardAndNeutralize(using Context): Unit =
      state.providerAnnotations.keysIterator.foreach: provider =>
        val providerOverrides = provider.allOverriddenSymbols.nonEmpty
        val providerIsOverridden = state.encounteredMethods.exists: method =>
          method != provider && method.allOverriddenSymbols.contains(provider)
        if providerOverrides || providerIsOverridden then
          report.error(
            AllowExperimentalPlugin.UnsupportedOverrideMessage,
            state.providerPositions(provider)
          )
      if !runCtx.reporter.hasErrors then
        state.providerAnnotations.foreach: (provider, _) =>
          provider.removeAnnotation(defn.ExperimentalAnnot)
          if provider.hasAnnotation(defn.ExperimentalAnnot) then
            report.error(
              s"allow-experimental internal invariant failed: ${provider.showLocated} remained experimental after neutralization",
              provider.srcPos
            )
          else state.neutralizedProviders += provider
    guardAndNeutralize(using runCtx.fresh.setPhase(this.start))
    checked

private final class RestoreExperimentalProviders(state: CompilationState) extends PluginPhase:
  override val phaseName: String = "allowExperimentalRestoreProviders"
  override val runsAfter: Set[String] = Set(CrossVersionChecks.name)

  override def isRunnable(using Context): Boolean = !ctx.usedBestEffortTasty

  override def runOn(units: List[CompilationUnit])(using runCtx: Context): List[CompilationUnit] =
    given Context = runCtx.fresh.setPhase(this.start)
    state.neutralizedProviders.foreach: provider =>
      state.providerAnnotations.get(provider) match
        case Some(annotation) =>
          provider.addAnnotation(annotation)
          if !provider.hasAnnotation(defn.ExperimentalAnnot) then
            report.error(
              s"allow-experimental internal invariant failed: ${provider.showLocated} was not restored",
              provider.srcPos
            )
        case None =>
          report.error(
            s"allow-experimental internal invariant failed: no saved annotation for ${provider.showLocated}",
            provider.srcPos
          )
    units
