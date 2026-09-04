# Allow `@experimental`

This repository contains a bounded compiler-plugin implementation and M0/M1/M3
verification matrices, tested on exact Scala **3.3.8**, **3.8.4**, and **3.9.0**.
It is not a production-ready plugin or a
published API. Other exact versions are not qualified here; these three
versions are a finite tested set, not a version interval.
The tested lanes have separately compiled plugin and annotation binaries;
this does not imply compiler-plugin binary compatibility between them.

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

The implementation uses three uniquely named compiler phases on each exact lane:

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

Run all three exact lanes from clean, separate sbt sessions with:

```text
bash scripts/verify-lanes.sh
```

Scala 3.9.0 remains the default. To run one lane explicitly:

```text
sbt -batch '++3.3.8' clean verifyLane verifyM0 verifyM1 verifyM3
sbt -batch '++3.8.4' clean verifyLane verifyM0 verifyM1 verifyM3
sbt -batch '++3.9.0' clean verifyLane verifyM0 verifyM1 verifyM3 verifyM4A
```

`verifyM0`, `verifyM1`, and `verifyM3` use the active exact lane. `verifyLane` checks compiler
identity, lane-built jar metadata, separate output roots, and rejection of
wrong-lane inputs. Each lane runs the same 13 M0, 51 M1, and eight M3 checks
plus five build-structure checks; expected failures assert the relevant
diagnostics. `verifyM4A` is an exact-3.9.0-only gate.

Each project's build state and artifacts live under `target/scala-<version>`;
cleaning a lane leaves the other lane's outputs intact. Root fixture sources,
commands and compiler/phase/decompiler logs live in
`target/scala-<version>/m0-verification`, `m1-verification`, and
`m3-verification`, with M4A evidence under the exact 3.9.0 root. The script
retains per-lane summaries and preservation ledgers in
`target/verification-logs`.

Jar names and coordinates remain provisional. The current `_3` names do not
make a plugin jar portable between compilers; verification uses the artifact
built for that exact lane. The 3.3.8 compiler has a small private source adapter
for its legacy plugin entrypoint and unavailable import/best-effort APIs; the
modern lanes also use exact-version adapters so the M4A guard is enabled only
on 3.9.0. The semantic phase source remains shared. A future plugin release should use an exact-version
distinction (such as full-cross coordinates), but publication remains skipped
and the annotation artifact's eventual compatibility policy is undecided.

| Reference/placement | Exact Scala 3.3.8 / 3.8.4 / 3.9.0 tested boundary |
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

## Exact three-lane macro implementation evidence

The M3 verifier compiles a distinct macro producer whose public inline
frontend is ordinary and whose private, non-inline implementation alone carries
`@allowExperimental`. That implementation evaluates the genuinely experimental
`quotes.reflect.Symbol.info` API without global `-experimental`.

A second compiler invocation expands the public macro from the compiled
producer using only the exact Scala runtime libraries and producer classes: no
Allow Experimental plugin, marker artifact, authority annotation, or global
flag is present. The macro emits a small literal selected from whether the
`Symbol.info` representation is non-empty, and downstream bytecode inspection
confirms the `symbol-info-nonempty` result.

The same real implementation without the marker remains rejected. An
`@allowExperimental inline def` remains unsupported, and a negative-only
producer demonstrates that a directly serialized experimental inline reference
is rejected by its ordinary downstream caller. Scala 3.8.4 and 3.9.0 use
`-experimental` only to manufacture that negative producer. Scala 3.3.8 has no
such option, so the negative producer's inline definition is explicitly
`@experimental`. Global `-experimental` is never used in a positive proof or in
any downstream invocation.

This is exact 3.3.8, 3.8.4, and 3.9.0 implementation evidence pending
controller review, not a version interval, Quasiquotes integration, or a public
macro API added to this product.

## Exact Scala 3.9.0 generic second-plugin contract

The retained `verifyM4A` gate builds a separate real standard compiler-plugin
jar and probes both plugin loading orders. It demonstrates that the Scala 3.9.0
scheduler can place a peer plugin phase before the selective checker, between
that checker and `crossVersionChecks`, after the built-in transform group but
before restoration, or after restoration.

Allow Experimental therefore uses an explicit fail-closed phase contract on
this exact lane; this is not transparent coexistence. When a supported provider
would otherwise be temporarily neutralized, any foreign standard-plugin phase
scheduled after `allowExperimentalCheckReferences` and before
`allowExperimentalRestoreProviders` causes a truthful incompatibility error
before provider mutation. Foreign phases before the checker or after restoration
are not rejected by this sensitive-window guard; the retained read-only observer
fixture coexists at both positions. The verifier proves both sensitive placements
are blocked, a harmless after-restoration observer runs and inspects an ordinary
definition, provider restoration survives the exercised reported-error path,
and the ordinary downstream consumer still needs neither plugin nor marker
artifact.

Run the exact M4A gate with:

```text
sbt -batch '++3.9.0' verifyM4A
```

This qualification is limited to ordinary Scala 3.9.0 standard plugins and the
tested phase-plan API. It is not a Scala 3.8.4/3.3.8 coexistence claim, a claim
about research-plugin phase-plan replacement, or exception/cancellation safety.
The separate M4B gate below tests one pinned real Macro-Paradise boundary. M4 is
not complete and publication remains unauthorized.

## Exact Scala 3.9.0 real Macro-Paradise coexistence

The retained `verifyM4B` gate uses an independent no-hardlink disposable clone
of Macro-Paradise commit
`d773332c29efce90b3af343d34ae5450a93f6d93`. It source-builds only that
commit's exact-3.9.0 plugin and experimental handler API with JDK 25 and sbt
1.12.15, without `publishLocal`, vendoring, a submodule, or modification of the
peer checkout.

The pinned plugin is a normal `StandardPlugin` named `macroparadise`. Its one
phase, `paradiseGen`, is actually installed after `parser` and before `typer` in
both plugin loading orders. It is therefore well before
`allowExperimentalCheckReferences` and the protected provider-neutralization
interval. A task-owned external marker and precompiled handler use the pinned
public `paradise3.api.expander` and `ParadiseAnnotationExpander` contracts to
generate `GenUser.generatedHello`; the generated member and a real
`@allowExperimental` implementation are typechecked in the same compiler
invocation with both plugins installed.

Both loading orders have the same supported result. Provider TASTy remains
genuinely experimental, the allowed and ordinary APIs remain non-experimental,
the Allow marker is absent, and an ordinary separate downstream compilation
needs neither compiler plugin, either handler/marker artifact, the
Macro-Paradise API, nor the Allow marker artifact. Negative controls prove that
Macro-Paradise grants no experimental permission, an inert Allow marker grants
none without its plugin, and permission does not leak to an unannotated
same-unit sibling or a separately compiled later unit in either loading order.
Retained P1/P2 observers still trigger M4A's fail-closed guard before provider
mutation even when Macro-Paradise is present.

Run the fresh pinned-peer build plus exact-3.9.0 M0/M1/M3/M4A/M4B gate with:

```text
bash scripts/verify-m4b-macroparadise.sh
```

This retained M4B result is bounded to one unreleased pinned Macro-Paradise
commit on exact Scala 3.9.0. The M4C gate below extends the same real fixture
across the two older exact lanes; neither result is a compatibility interval,
general multi-plugin transparency, or release authorization. Routine
three-lane core verification intentionally does not build the peer.

## Exact cross-lane real Macro-Paradise evidence

The `verifyM4C` gate reuses the retained real M4B fixture and assertions on
exact Scala **3.3.8** and **3.8.4**. Each lane independently materializes and
source-builds pinned Macro-Paradise commit
`d773332c29efce90b3af343d34ae5450a93f6d93` in its own no-hardlink disposable
root, using JDK 25 and sbt 1.12.15. No compiler-plugin binary is reused across
exact Scala lanes and no peer artifact is published locally.

On both older exact lanes, the actual installed plan in both plugin loading
orders places the pinned plugin's single normal-path `paradiseGen` phase after
`parser` and before `typer`. The same real external marker and precompiled
handler generate `GenUser.generatedHello` while Allow Experimental authorizes
the bounded provider reference in the same compiler invocation. The generated
member and ordinary Allow API remain usable by a separate downstream compiler
with no compiler plugins or marker/API artifacts. The retained permission,
plugin-absence, sibling, and later-unit negatives remain fail closed.

Run the two fresh older-lane peer builds, complete older-lane core/M4C gates,
and retained exact-3.9.0 core/M4A/M4B regression with:

```text
bash scripts/verify-m4c-macroparadise.sh
```

Before either peer build starts, the script fails closed unless the active JVM
reports Java specification feature 25 and each pinned disposable peer's
`project/build.properties` selects sbt 1.12.15. Run the focused guard check with
`bash scripts/test-verify-m4c-toolchain.sh`.

This is a finite three-exact-line result for the one pinned peer commit, not a
version interval or general plugin compatibility claim. Generic adversarial
sensitive-window protection remains **unqualified** on 3.3.8 and 3.8.4 and
retains the exact-3.9.0 fail-closed result. M4C and M4 remain subject to
controller review; publication remains unauthorized.

## Exact Scala 3.9.0 repeated-run and Zinc lifecycle evidence

The retained M5A gate separates two lifecycle boundaries. Its one-process
harness uses one Scala 3.9.0 `ContextBase`, one `Compiler`, and one cached Allow
Experimental plugin instance for eight ordered `Compiler.newRun` compilations.
Every logical run receives a fresh reporter, `Run`, plugin phase trio, and
`CompilationState`. Alternating allowed, rejected, repaired, sibling-negative,
reported-error, recovery, and final direct-negative cases reuse the same
package and symbol names. No permission, selected body reference, provider
mutation, or reporter error leaks into a later run, and successful TASTy keeps
the provider experimental while the ordinary APIs remain marker-free.

The distinct generated sbt 1.11.7 / Zinc 1.11.0 fixture uses provider,
allowed, and ordinary consumer subprojects on exact Scala 3.9.0. After its one
clean baseline, every provider/permission/sibling transition runs without
`clean` over persisted Zinc analysis. An ordinary provider becoming
`@experimental` invalidates and rejects the unchanged unmarked dependent;
adding permission recovers, removing it rejects, a second repair recovers,
sibling isolation rejects and recovers, and provider toggles in both directions
are observed. A no-source-change compile is a real no-op. The final consumer
boundary is proved by a distinct downstream compiled for the first time after
the final supported provider/allowed state; it needs neither Allow compiler
plugin nor marker artifact.

Plugin absence remains fail closed. A 3.8.4 plugin artifact presented to the
3.9.0 compiler is rejected by a generated exact-build-version guard before it
can grant permission. A 3.8.4 marker artifact loaded with the exact 3.9.0
plugin worked in this bounded fixture, but that observation does not establish
or advertise marker binary compatibility and does not lock publication policy.

Run the complete M5A gate, including all three core lanes and retained M4
coexistence regressions without rebuilding read-only peers, with:

```text
bash scripts/verify-m5a.sh
```

This is the controller-accepted bounded exact-3.9.0 lifecycle result. It does
not prove persistent sbt server, BSP, IDE, cancellation, unhandled-exception,
or best-effort-TASTy behavior and does not authorize publication.

## Exact Scala 3.3.8 and 3.8.4 lifecycle hardening evidence

The M5B verifier independently repeats both lifecycle mechanisms on exact Scala
3.3.8 and 3.8.4. Exact compiler source audits support the same strongest
same-JVM topology on both lanes: one `ContextBase`, one `Compiler`, and one
cached Allow plugin instance across eight fresh `Compiler.newRun` values, with
fresh reporters, phase trios, `CompilationState` values, and checker-local body
maps. Scala 3.3.8 uses only a verifier-private observer adaptation for its
legacy `StandardPlugin.init` entrypoint. The shared semantic plugin source and
all three exact product adapters are unchanged.

Separate sbt 1.11.7 / Zinc 1.11.0 fixtures repeat the full non-clean transition
sequence on each older lane in isolated roots. Each bridge source shows that a
compile invocation constructs a fresh bridge driver, context base, and
compiler; these are persisted-analysis and output proofs, not compiler-instance
reuse proofs. Both lanes invalidate the unchanged unmarked dependent when only
the provider becomes `@experimental`, recover and reject truthfully across
permission/sibling/provider edits, observe a real no-op, and first compile a
plugin-free and marker-free downstream after the final supported state.

Wrong-lane plugins never grant permission. The 3.8.4 compiler reaches the
generated exact-version guard for both 3.9.0 and 3.3.8 plugin artifacts. The
3.3.8 compiler rejects the 3.8.4 plugin earlier with a plugin-load linkage
failure. These fail-closed mechanisms do not imply compiler-plugin binary
compatibility. The retained 3.8.4-marker/3.9.0-plugin observation still does
not establish marker compatibility.

Run the complete cross-lane lifecycle and retained regression gate, without
building read-only peers, with:

```text
bash scripts/verify-m5b.sh
```

This M5B implementation evidence is pending controller review. It does not
prove a persistent sbt server, BSP, IDE, cancellation, unhandled exceptions,
or best-effort TASTy; it does not start M5C and does not authorize publication.

Class-carried experimental providers and provider override edges retain their
fail-closed guards. Permission owners such as vals, classes, constructors and
arbitrary blocks remain unsupported. This is not full `CrossVersionChecks`
parity, exception-proof restoration, general multi-plugin coexistence,
persistent IDE/BSP support, or Quasiquotes integration.

Without the plugin, an experimental reference remains rejected by Scala's
ordinary diagnostic: permission safety is fail-closed. A marker on otherwise
ordinary code is accepted and retained in TASTy without the plugin. This inert
marker is an accepted usability contract, not permission leakage. The marker
remains a parameterless final `StaticAnnotation`; the prior `@compileTimeOnly`
probe did not enforce a stronger diagnostic on Scala 3.9.0 and is not used.
