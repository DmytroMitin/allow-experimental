# Allow `@experimental`

This repository contains a bounded Scala 3.9.0 compiler-plugin implementation
and its M0/M1 verification matrices. It is not a production-ready plugin or a
published API. Other exact Scala versions are not qualified.

At the tested boundary, a provisional `@allowExperimental` marker permits
ordinary/private non-inline `def` implementation bodies to use supported
experimental term references without making those methods experimental to
callers:

```scala
import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental

@experimental
def foo(): Int = 1

@allowExperimental
def bar(): Int = foo()

def baz(): Int = bar()
```

The implementation uses three uniquely named Scala 3.9.0 compiler phases:

1. capture the exact annotated owner after `posttyper` and remove the marker
   before `pickler`;
2. select supported RHS term nodes structurally and check all compilation units
   after `postInlining`, then
   temporarily remove only the experimental provider annotations needed to
   avoid the built-in duplicate rejection in `crossVersionChecks`;
3. restore each provider's complete saved annotation list, preserving object
   identity, multiplicity and order, after the transform group that contains
   `crossVersionChecks`. Unexpected annotation changes in that window report
   an invariant error.

The annotation and plugin artifacts are separate and their package, artifact,
and version coordinates are provisional. A separately compiled ordinary
consumer of `bar` needs neither artifact and does not use `-experimental`.

Run the retained regression and implementation-body matrices with:

```text
sbt -batch verifyM0
sbt -batch verifyM1
```

The M1 gate generates readable fixtures and compiler/phase/decompiler logs in
`target/m1-verification`. Both gates assert diagnostics for expected failures.

| Reference/placement | Scala 3.9.0 boundary |
|---|---|
| Ordinary/private method-body term `Ident`/`Select` | Supported for the tested direct method and stable-value providers |
| Nested local non-inline method implementation | Inherits permission from the allowed enclosing implementation |
| Ordinary qualifier member import, then supported call | Call is permitted; the import alone needs no permission, even without the plugin |
| Experimental class construction, constructor-only experimental provider | Rejected |
| Experimental body-local type annotation, ascription, type argument | Rejected |
| Experimental parameter/return type, including inferred return | Rejected; a marker never authorizes a public signature |
| Experimental owner import | Rejected |
| Experimental inline provider | Rejected by earlier compiler checking; not enabled by this late-check mechanism |
| Inline owner/nested inline, independently marked local method | Explicitly unsupported |
| Nested class body, experimental default argument, experimental annotation argument | Not inherited as implementation permission |

Class-carried experimental providers and provider override edges retain their
fail-closed guards. Permission owners such as vals, classes, constructors and
arbitrary blocks remain unsupported. This is not full `CrossVersionChecks`
parity, exception-proof restoration, multi-plugin coexistence, incremental or
repeated-run qualification, IDE/BSP support, or a macro/Quasiquotes integration.

Without the plugin, an experimental reference remains rejected by Scala's
ordinary diagnostic: permission safety is fail-closed. A marker on otherwise
ordinary code is accepted and retained in TASTy without the plugin. This inert
marker is an accepted usability contract, not permission leakage. The marker
remains a parameterless final `StaticAnnotation`; the prior `@compileTimeOnly`
probe did not enforce a stronger diagnostic on this compiler and is not used.
