package io.github.dmytromitin.allowexperimental.plugin

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.config.Feature
import dotty.tools.dotc.core.*
import Annotations.Annotation
import Contexts.*
import Flags.*
import NameKinds.DefaultGetterName
import Symbols.*
import Types.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report
import dotty.tools.dotc.transform.{Pickler, PostInlining, PostTyper}
import dotty.tools.dotc.transform.CrossVersionChecks
import dotty.tools.dotc.util.SrcPos

import scala.collection.mutable

private[plugin] object AllowExperimentalPluginEntrypoint:
  val Name: String = "allow-experimental"
  val Description: String =
    "provisional exact-Scala-lane implementation permission for ordinary defs"
  val UnsupportedOptionsMessage: String = "allow-experimental M0 accepts no plugin options"

  def phases(): List[PluginPhase] =
    ExactCompilerVersion.validate()
    val state = CompilationState()
    List(CaptureAllowedOwners(state), CheckAllowedReferences(state), RestoreExperimentalProviders(state))

private object AllowExperimentalSemantics:
  val MarkerClassName = "io.github.dmytromitin.allowexperimental.allowExperimental"
  val UnsupportedOwnerMessage =
    "@allowExperimental M0 supports only non-inline def owners"
  val UnsupportedClassCarrierMessage =
    "@allowExperimental M0 does not support experimental providers inherited from class owners"
  val UnsupportedOverrideMessage =
    "@allowExperimental M0 does not support experimental providers that participate in overrides"
  val UnsupportedLocalOwnerMessage =
    "@allowExperimental M1 does not support independently annotated local defs"
  val UnsupportedNestedInlineMessage =
    "@allowExperimental M1 does not support nested inline definitions"
  val UnsupportedMetadataMessage =
    "@allowExperimental M1 does not support experimental annotation arguments"
  val IncompatibleSensitiveWindowPhaseMessage =
    "allow-experimental incompatible compiler-plugin phase inside the provider neutralization window"

private trait AllowExperimentalOwnedPhase extends PluginPhase

private final class CompilationState:
  val allowedOwners: mutable.LinkedHashSet[Symbol] = mutable.LinkedHashSet.empty
  val providerAnnotations: mutable.LinkedHashMap[Symbol, List[Annotation]] = mutable.LinkedHashMap.empty
  val neutralizedAnnotations: mutable.LinkedHashMap[Symbol, List[Annotation]] = mutable.LinkedHashMap.empty
  val providerPositions: mutable.LinkedHashMap[Symbol, SrcPos] = mutable.LinkedHashMap.empty
  val encounteredMethods: mutable.LinkedHashSet[Symbol] = mutable.LinkedHashSet.empty
  val neutralizedProviders: mutable.LinkedHashSet[Symbol] = mutable.LinkedHashSet.empty

  def markerSymbol(using Context): Symbol =
    getClassIfDefined(AllowExperimentalSemantics.MarkerClassName)

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
      report.error(AllowExperimentalSemantics.UnsupportedClassCarrierMessage, pos)
    else
      carrier.getAnnotation(defn.ExperimentalAnnot) match
        case Some(_) =>
          providerAnnotations.getOrElseUpdate(carrier, carrier.annotations)
          providerPositions.getOrElseUpdate(carrier, pos)
        case None =>
          report.error(
            s"allow-experimental internal invariant failed: ${carrier.showLocated} lost its experimental annotation",
            pos
          )

private final class CaptureAllowedOwners(state: CompilationState) extends AllowExperimentalOwnedPhase:
  override val phaseName: String = "allowExperimentalCaptureOwners"
  override val runsAfter: Set[String] = Set(PostTyper.name)
  override val runsBefore: Set[String] = Set(Pickler.name)

  private def capture(symbol: Symbol, pos: SrcPos)(using Context): Unit =
    val marker = state.markerSymbol
    if marker.exists && symbol.hasAnnotation(marker) then
      val supported = symbol.isTerm && symbol.is(Method) && !symbol.isConstructor && !symbol.is(Inline)
      if supported && symbol.isLocal then report.error(AllowExperimentalSemantics.UnsupportedLocalOwnerMessage, pos)
      else if supported then state.allowedOwners += symbol
      else report.error(AllowExperimentalSemantics.UnsupportedOwnerMessage, pos)
      symbol.removeAnnotation(marker)
      if symbol.hasAnnotation(marker) then
        report.error("allow-experimental internal invariant failed: permission marker removal did not take effect", pos)

  override def prepareForDefDef(tree: tpd.DefDef)(using Context): Context =
    capture(tree.symbol, tree.srcPos)
    // Run before children/inlining: an inline local body must not acquire
    // permission merely because its expansion later appears in an allowed RHS.
    if tree.symbol.is(Inline) && state.isAllowedScope(tree.symbol.owner) then
      report.error(AllowExperimentalSemantics.UnsupportedNestedInlineMessage, tree.srcPos)
    ctx

  override def transformValDef(tree: tpd.ValDef)(using Context): tpd.Tree =
    capture(tree.symbol, tree.srcPos)
    tree

  override def transformTypeDef(tree: tpd.TypeDef)(using Context): tpd.Tree =
    capture(tree.symbol, tree.srcPos)
    tree

private final class CheckAllowedReferences(state: CompilationState) extends AllowExperimentalOwnedPhase:
  import tpd.*

  override val phaseName: String = "allowExperimentalCheckReferences"
  override val runsAfter: Set[String] = Set(PostInlining.name)
  override val runsBefore: Set[String] = Set(CrossVersionChecks.name)

  private val bodyReferences = new java.util.IdentityHashMap[Tree, java.lang.Boolean]()

  override def prepareForDefDef(tree: DefDef)(using Context): Context =
    if state.allowedOwners.contains(tree.symbol) then
      // Match typed-tree roles, not positions or source spelling. Only RHS
      // executable terms (including non-inline local-def RHSs) are eligible.
      // Signatures, class bodies, type trees and imports remain restricted.
      // Symbol annotation arguments are checked separately, not by MegaPhase.
      val collect = new TreeTraverser:
        def traverse(part: Tree)(using Context): Unit = part match
          case nested: DefDef =>
            if !nested.symbol.is(Inline) && !nested.symbol.isConstructor
                && !nested.name.is(DefaultGetterName) then traverse(nested.rhs)
          case value: ValDef => traverse(value.rhs)
          case _: TypeDef | _: Template | _: TypeTree | _: ImportOrExport => ()
          case Typed(expr, _) => traverse(expr)
          case Annotated(arg, _) => traverse(arg)
          case ref: RefTree if ref.isTerm && !ref.symbol.isConstructor =>
            bodyReferences.put(ref, java.lang.Boolean.TRUE)
            traverseChildren(ref)
          case other if other.isType => ()
          case other => traverseChildren(other)
      collect.traverse(tree.rhs)
    ctx

  private def checkTermReference(tree: Tree)(using Context): Unit =
    val sym = tree.symbol
    val pos = tree.srcPos
    if sym.isExperimental then
      val compilerPermission = ctx.owner.isInExperimentalScope
      val markerPermission = bodyReferences.containsKey(tree) && state.isAllowedScope(ctx.owner)
      if markerPermission && !compilerPermission then state.rememberProvider(sym, pos)
      else if !compilerPermission then Feature.checkExperimentalDef(sym, pos)

  private def checkUnsupportedReference(sym: Symbol, pos: SrcPos)(using Context): Unit =
    if sym.isExperimental && !ctx.owner.isInExperimentalScope then
      Feature.checkExperimentalDef(sym, pos)

  override def transformIdent(tree: Ident)(using Context): Ident =
    if tree.isTerm then checkTermReference(tree)
    else checkUnsupportedReference(tree.symbol, tree.srcPos)
    tree

  override def transformSelect(tree: Select)(using Context): Select =
    if tree.isTerm then checkTermReference(tree)
    else checkUnsupportedReference(tree.symbol, tree.srcPos)
    tree

  override def transformNew(tree: New)(using Context): New =
    checkUnsupportedReference(tree.tpe.typeSymbol, tree.srcPos)
    tree

  override def transformTypeTree(tree: TypeTree)(using Context): TypeTree =
    tree.tpe.foreachPart:
      case TypeRef(_, sym: Symbol) => checkUnsupportedReference(sym, tree.srcPos)
      case TermRef(_, sym: Symbol) => checkUnsupportedReference(sym, tree.srcPos)
      // Expression annotations are lowered to AnnotatedType; foreachPart
      // visits that type but intentionally does not enter its annotation tree.
      case AnnotatedType(_, annotation) if !ctx.owner.isInExperimentalScope =>
        checkAnnotationArguments(annotation)
      case _ =>
    tree

  override def transformOther(tree: Tree)(using Context): Tree =
    val inPackage = ctx.owner.is(Package) || ctx.owner.isPackageObject
    if !(inPackage && tree.isInstanceOf[ImportOrExport] && CompilerAdapter.isExperimentalEnabledByImport) then
      tree.foreachSubTree:
        case ref: Ident => checkUnsupportedReference(ref.symbol, ref.srcPos)
        case ref: Select => checkUnsupportedReference(ref.symbol, ref.srcPos)
        case ref: TypeTree => transformTypeTree(ref)
        case _ =>
    tree

  override def transformDefDef(tree: DefDef)(using Context): DefDef =
    state.encounteredMethods += tree.symbol
    checkMetadata(tree.symbol)
    tree

  override def transformValDef(tree: ValDef)(using Context): ValDef =
    checkMetadata(tree.symbol)
    tree

  override def transformTypeDef(tree: TypeDef)(using Context): TypeDef =
    checkMetadata(tree.symbol)
    tree

  private def checkMetadata(symbol: Symbol)(using Context): Unit =
    if symbol.exists && !symbol.isInExperimentalScope then
      // CrossVersionChecks checks annotation classes but not all argument
      // references. Classify these explicitly before touching provider state.
      symbol.annotations.foreach(checkAnnotationArguments)

  private def checkAnnotationArguments(annotation: Annotation)(using Context): Unit =
    def rejectExperimental(sym: Symbol, pos: SrcPos): Unit =
      if sym.isExperimental then report.error(AllowExperimentalSemantics.UnsupportedMetadataMessage, pos)
    annotation.tree.foreachSubTree:
      case ref: RefTree => rejectExperimental(ref.symbol, ref.srcPos)
      case tpt: TypeTree =>
        tpt.tpe.foreachPart:
          case TypeRef(_, sym: Symbol) => rejectExperimental(sym, tpt.srcPos)
          case TermRef(_, sym: Symbol) => rejectExperimental(sym, tpt.srcPos)
          case AnnotatedType(_, nested) => checkAnnotationArguments(nested)
          case _ =>
      case _ =>

  override def runOn(units: List[CompilationUnit])(using runCtx: Context): List[CompilationUnit] =
    val checked = super.runOn(units)
    def guardAndNeutralize(using Context): Unit =
      state.providerAnnotations.keysIterator.foreach: provider =>
        val providerOverrides = provider.allOverriddenSymbols.nonEmpty
        val providerIsOverridden = state.encounteredMethods.exists: method =>
          method != provider && method.allOverriddenSymbols.contains(provider)
        if providerOverrides || providerIsOverridden then
          report.error(
            AllowExperimentalSemantics.UnsupportedOverrideMessage,
            state.providerPositions(provider)
          )
      if !runCtx.reporter.hasErrors && state.providerAnnotations.nonEmpty then
        if CompilerAdapter.enforceM4APhaseContract then
          val plan = Phases.unfusedPhases.toList
          val checkIndex = plan.indexWhere(_ eq this)
          val restoreIndex = plan.indexWhere(_.isInstanceOf[RestoreExperimentalProviders])
          val invalidBoundary = checkIndex < 0 || restoreIndex <= checkIndex
          if invalidBoundary then
            report.error(
              "allow-experimental internal invariant failed: unable to locate the installed neutralization-window phase boundaries",
              state.providerPositions.valuesIterator.next()
            )
          else
            val incompatible = plan.slice(checkIndex + 1, restoreIndex).collect:
              case phase: PluginPhase if !phase.isInstanceOf[AllowExperimentalOwnedPhase] => phase
            if incompatible.nonEmpty then
              val installed = plan.iterator.filter(_.exists).map: phase =>
                val kind = if phase.isInstanceOf[PluginPhase] then "plugin" else "builtin"
                s"${phase.phaseName}:$kind"
              report.error(
                s"${AllowExperimentalSemantics.IncompatibleSensitiveWindowPhaseMessage}: " +
                  s"${incompatible.map(_.phaseName).mkString(", ")}; provider mutation has not started; " +
                  s"installed phase plan=${installed.mkString(",")}",
                state.providerPositions.valuesIterator.next()
              )
        if !runCtx.reporter.hasErrors then
          state.providerAnnotations.foreach: (provider, _) =>
            provider.removeAnnotation(defn.ExperimentalAnnot)
            if provider.hasAnnotation(defn.ExperimentalAnnot) then
              report.error(
                s"allow-experimental internal invariant failed: ${provider.showLocated} remained experimental after neutralization",
                provider.srcPos
              )
            else
              state.neutralizedProviders += provider
              state.neutralizedAnnotations(provider) = provider.annotations
    guardAndNeutralize(using runCtx.fresh.setPhase(this.start))
    checked

private final class RestoreExperimentalProviders(state: CompilationState) extends AllowExperimentalOwnedPhase:
  override val phaseName: String = "allowExperimentalRestoreProviders"
  override val runsAfter: Set[String] = Set(CrossVersionChecks.name)

  override def isRunnable(using Context): Boolean = CompilerAdapter.restoreIsRunnable

  override def runOn(units: List[CompilationUnit])(using runCtx: Context): List[CompilationUnit] =
    given Context = runCtx.fresh.setPhase(this.start)
    state.neutralizedProviders.foreach: provider =>
      state.providerAnnotations.get(provider) match
        case Some(annotations) =>
          val expected = state.neutralizedAnnotations(provider)
          val current = provider.annotations
          if current.size != expected.size || !current.lazyZip(expected).forall((a, b) => a eq b) then
            report.error("allow-experimental internal invariant failed: provider annotations changed inside the neutralization window", provider.srcPos)
          // Preserve every original annotation object, multiplicity and order.
          provider.denot.annotations = annotations
          if (provider.annotations ne annotations) || !provider.hasAnnotation(defn.ExperimentalAnnot) then
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
