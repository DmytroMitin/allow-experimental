# Allow `@experimental`

This repository currently contains an M0 compiler-plugin reproduction for
Scala 3.9.0. It is a narrow feasibility implementation, not a production-ready
plugin or a published API.

At the proved boundary, a provisional `@allowExperimental` marker permits an
ordinary, non-inline `def` body to call an experimental definition without
making that `def` experimental to callers:

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
2. selectively check all compilation units after `postInlining`, then
   temporarily remove only the experimental provider annotations needed to
   avoid the built-in duplicate rejection in `crossVersionChecks`;
3. restore the exact saved provider annotations after the transform group that
   contains `crossVersionChecks`.

The annotation and plugin artifacts are separate and their package, artifact,
and version coordinates are provisional. A separately compiled ordinary
consumer of `bar` needs neither artifact and does not use `-experimental`.

Run the retained positive, negative, separate-compilation, plugin-absent, and
TASTy inspection matrix with:

```text
sbt -batch verifyM0
```

M0 intentionally does not claim support for Scala 3.3.8 or 3.8.4, inline
owners, vals, classes/objects/traits, constructors, signatures or types,
imports in every position, overrides, arbitrary blocks, IDE/BSP, incremental
compilation, plugin coexistence, or full parity with every reference form in
`CrossVersionChecks`.

Without the plugin, an experimental reference remains rejected by Scala's
ordinary diagnostic. A marker on a non-experimental definition is currently
accepted and retained in TASTy; testing showed that annotating the marker class
with `@compileTimeOnly` does not change that behavior on Scala 3.9.0, so M0 does
not use that misleading meta-annotation.
