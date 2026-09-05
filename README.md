# Allow Experimental

## What it does

Allow Experimental is a bounded Scala 3 compiler plugin. It lets a supported
non-inline method implementation call selected APIs marked
`scala.annotation.experimental` without making the method experimental for its
callers.

The compiler plugin is built separately for each exact supported compiler.
Support for three named versions is a finite tested set, not a compatibility
interval.

## Quick example

```scala
import scala.annotation.experimental
import io.github.dmytromitin.allowexperimental.allowExperimental

@experimental
def provider(): Int = 1

@allowExperimental
def allowed(): Int = provider()

def ordinaryCaller(): Int = allowed()
```

The positive build does not use global `-experimental`. A direct unmarked call
to `provider()` still receives Scala's normal experimental-use diagnostic.

## Installation

No release has been published to Maven Central yet. Until the first release,
build and publish the artifacts from a local checkout.

### Local checkout and publishLocal

Publish the annotation once from the oldest supported lane, then publish one
exact compiler-plugin artifact per lane:

```sh
sbt -batch '++3.3.8!' 'annotation/publishLocal'
sbt -batch '++3.3.8!' 'plugin/publishLocal'
sbt -batch '++3.8.4!' 'plugin/publishLocal'
sbt -batch '++3.9.0!' 'plugin/publishLocal'
```

Configure a consumer with one Scala-3 binary-cross annotation dependency and
the matching full-cross compiler plugin:

```scala
ThisBuild / scalaVersion := "3.9.0" // or another exact supported lane

val allowExperimentalVersion = "0.1.0-SNAPSHOT"

libraryDependencies +=
  "com.github.dmytromitin" %% "allow-experimental-annotation" %
    allowExperimentalVersion % Provided

addCompilerPlugin(
  ("com.github.dmytromitin" % "allow-experimental-plugin" %
    allowExperimentalVersion).cross(CrossVersion.full)
)
```

`% Provided` is the canonical annotation scope. The marker is needed while
compiling the protected implementation, but supported compiled APIs do not
retain it in their public TASTy surface and do not require it at runtime.
Ordinary downstream callers of an already compiled allowed API need neither
Allow Experimental artifact.

No sbt plugin is required or supplied. Ordinary use needs only the annotation
dependency and the compiler plugin dependency shown above.

### After the first Maven Central release

Use the same configuration with the released version after the first Maven
Central publication:

```scala
val allowExperimentalVersion = "<released-version>"
```

This describes the future coordinate shape; it does not claim that a released
version is currently downloadable.

## Supported exact Scala versions

- Scala 3.3.8
- Scala 3.8.4
- Scala 3.9.0

The annotation coordinate is:

```text
com.github.dmytromitin:allow-experimental-annotation_3:<version>
```

The compiler-plugin coordinates are exact-version-specific:

```text
com.github.dmytromitin:allow-experimental-plugin_3.3.8:<version>
com.github.dmytromitin:allow-experimental-plugin_3.8.4:<version>
com.github.dmytromitin:allow-experimental-plugin_3.9.0:<version>
```

A common project version does not imply compiler-plugin binary compatibility.
Each plugin embeds and validates its exact compiler identity.

## Supported permission scope

The marker applies only to an ordinary or private non-inline `def`
implementation. Within that implementation, supported term `Ident` and
`Select` references may call directly experimental methods or stable values.
A nested local non-inline method may inherit permission from its allowed
enclosing implementation.

The plugin captures an allowed owner after typing, removes the marker before
pickling, checks supported references after inlining, temporarily neutralizes
only the selected provider annotations around Scala's built-in experimental
check, and restores each complete saved annotation list. Restoration preserves
annotation identity, multiplicity, and order; an unexpected change fails the
build.

The following forms remain rejected:

- experimental parameter or return types, including inferred public returns;
- experimental local type annotations, ascriptions, and type arguments;
- experimental classes, constructors, and owner imports;
- experimental inline providers and public inline permission owners;
- permission owners that are vals, classes, constructors, or arbitrary blocks;
- independently marked local definitions, nested inline definitions, default
  arguments, and experimental annotation arguments;
- providers inherited from experimental class owners or involved in overrides.

Unsupported placements and reference shapes fail truthfully. Success for a
neighboring form is not general permission.

## Macro implementation use case

A public inline macro frontend may delegate to a private, non-inline
`@allowExperimental` implementation. The exact-lane verification uses the
genuinely experimental `quotes.reflect.Symbol.info` API in that private
implementation, then expands the compiled public macro in a separate
downstream compilation with neither Allow Experimental artifact.

The same implementation without the marker is rejected. Marking the public
inline frontend itself is unsupported.

## Compiler-plugin coexistence

The product is tested with one pinned Macro-Paradise commit on each supported
exact Scala lane and in both plugin loading orders. That finite result does not
claim general plugin compatibility.

On Scala 3.9.0, a phase observer also exercises the sensitive window in which
provider annotations may be temporarily neutralized. A foreign phase in that
window fails closed before provider mutation; phases before the checker or
after restoration are admitted by the tested guard. Generic sensitive-window
protection is not qualified on Scala 3.3.8 or 3.8.4.

The Quasiquotes `Symbol.info` check is a disposable pinned integration proof.
It does not add Quasiquotes as a product dependency or claim broader
Quasiquotes support.

## Build and lifecycle behavior

Verification covers:

- repeated `Compiler.newRun` calls with one cached plugin instance;
- non-clean Zinc provider/permission transitions;
- one persistent sbt process across fail-and-recover transitions;
- one real BSP server across repeated compile requests;
- exact-lane mismatch rejection and separate lane outputs.

These are bounded build-lifecycle results. They do not establish native IDE
editor semantics, cancellation recovery, unhandled-exception recovery, or
compatibility beyond the exact tested compilers.

## Known limitations

- The plugin does not provide full parity with Scala's experimental checking.
- Public inline permission owners and direct public-inline experimental
  references remain unsafe and unsupported.
- Restoration is guarded for the tested compiler paths, not every possible
  exception or third-party phase behavior.
- A marker without the plugin is inert: it grants no permission.
- A wrong exact plugin must fail and never silently grant permission.
- The project is experimental and unreleased.

## Development and verification

Fast exact-lane semantic verification:

```sh
bash scripts/verify-lanes.sh
```

Focused capability entry points:

```sh
bash scripts/test-public-product-boundary.sh
bash scripts/test-publication-metadata.sh
bash scripts/test-readme-installation.sh
bash scripts/test-isolated-publish-local.sh
bash scripts/verify-isolated-publish-local.sh
bash scripts/verify-macroparadise-coexistence.sh
bash scripts/verify-zinc-lifecycle.sh
bash scripts/verify-persistent-build-lifecycle.sh
bash scripts/verify-quasiquotes-integration.sh
```

The isolated install smoke test redirects Ivy, sbt global/boot state, and
Coursier cache into `target/isolated-publish-local`; it does not write to the
normal user Ivy or Maven repositories. Expensive coexistence and lifecycle
checks remain separate composable gates.

## Related projects

- [Macro-Paradise](https://github.com/DmytroMitin/macroparadise-scala3)
- [Quasiquotes](https://github.com/DmytroMitin/quasiquotes-scala3)
- [AUXify](https://github.com/DmytroMitin/AUXify-scala3)
- [Scala Semantic Harness](https://github.com/DmytroMitin/scala-semantic-harness)

## License

Allow Experimental is licensed under the
[Apache License 2.0](LICENSE).
