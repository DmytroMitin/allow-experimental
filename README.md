# Allow Experimental

## What it does

Allow Experimental is a bounded Scala 3 compiler plugin. It lets a supported
non-inline method implementation call selected APIs marked
`scala.annotation.experimental` without making the method experimental for its
callers.

The compiler plugin is built separately for each exact supported compiler.
Support for three named versions is a finite tested set, not a compatibility
interval.

## Stability warning

`allow-experimental` deliberately relaxes Scala 3's transitive `@experimental` guardrail. It does **not** make an experimental API stable, and it does not provide compatibility guarantees when that API changes.

Treat the plugin as an escape hatch, not as the default way to consume experimental APIs. Prefer a stable API or a refactoring that avoids the experimental dependency when that is practical.

It is intended for cases where the experimental reference is a bounded implementation detail and the library author explicitly accepts responsibility for tracking, testing, and adapting that implementation as the experimental API evolves.

Do not use it to hide experimentality that is part of a library's effective public contract. In particular, an experimental dependency should remain visible to consumers when it leaks through public signatures or types, public inline code or serialized TASTy, inheritance or overrides, generated public API, or some other downstream requirement.

A useful rule of thumb is:

- if a change to the experimental dependency can be absorbed by updating and republishing the library implementation while preserving the public API, `@allowExperimental` may be appropriate;
- if consumers may themselves have to change because the experimental dependency changes, keep the affected API experimental.

The plugin's intentionally restricted supported scope is part of this policy. Unsupported placements are rejected rather than treated as generally safe.

### Real-world use

`allow-experimental` is used by [Quasiquotes for Scala 3](https://github.com/DmytroMitin/quasiquotes-scala3) in the implementation of type quasiquotes such as `tqr"..."`.

This is an example of the intended use case: an experimental Scala compiler/reflection API is needed in a bounded implementation detail, while that implementation choice should not require ordinary users of the resulting API to opt into Scala's global experimental mode.

The library author takes responsibility for tracking changes to the experimental compiler API and adapting the implementation for the supported Scala versions.

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

Version `0.1.0` is available from Maven Central. See the
[GitHub release](https://github.com/DmytroMitin/allow-experimental/releases/tag/v0.1.0)
for release notes and source archives.

Configure a consumer with one Scala-3 binary-cross annotation dependency and
the matching full-cross compiler plugin:

```scala
ThisBuild / scalaVersion := "3.9.0" // or exact 3.3.8 / 3.8.4

val allowExperimentalVersion = "0.1.0"

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
- The project and `0.1.0` release are experimental.

## Development and verification

### Local checkout and publishLocal

For development or building from source, publish artifacts from a local
checkout. Normal installation uses Maven Central as described above.

Publish the annotation once from the oldest supported lane, then publish one
exact compiler-plugin artifact per lane:

```sh
sbt -batch '++3.3.8!' 'annotation/publishLocal'
sbt -batch '++3.3.8!' 'plugin/publishLocal'
sbt -batch '++3.8.4!' 'plugin/publishLocal'
sbt -batch '++3.9.0!' 'plugin/publishLocal'
```

Use the same consumer configuration shown in Installation for the local build.

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

### Local release preparation rehearsal

The release-preparation rehearsal builds the exact four `0.1.0` coordinates
into a task-local Maven repository, checks the normalized repository, signs
its 16 primary files with a newly generated ephemeral test key, and compiles
focused external consumers on all supported compiler lanes:

```sh
bash scripts/rehearse-local-release.sh <exact-source-commit>
```

Its generated bundle is classified
`EPHEMERAL_TEST_ONLY_NOT_FOR_UPLOAD`. The rehearsal neither reads a Central
credential nor performs a Central upload, tag, GitHub Release, or remote
artifact publication.

## Related projects

- [Macro-Paradise](https://github.com/DmytroMitin/macroparadise-scala3)
- [Quasiquotes](https://github.com/DmytroMitin/quasiquotes-scala3)
- [AUXify](https://github.com/DmytroMitin/AUXify-scala3)
- [Scala Semantic Harness](https://github.com/DmytroMitin/scala-semantic-harness)

## License

Allow Experimental is licensed under the
[Apache License 2.0](LICENSE).
