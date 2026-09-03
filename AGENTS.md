# Allow Experimental product instructions

- This repository owns the Allow Experimental implementation only. The sibling `allow-experimental-control` repository is the development architecture, roadmap, and system of record.
- Macro-Paradise, Quasiquotes, AUXify, and their control repositories are read-only peers.
- Treat exact Scala compiler versions as distinct support lanes. Do not imply compiler-plugin binary compatibility across them.
- Never satisfy a verification gate with global `-experimental`, permanently remove a provider's `@experimental`, or weaken the saved-state restoration invariant.
- Unsupported placements and reference forms must fail truthfully; a neighboring success is not general support.
- Run the verification gate relevant to every touched semantic boundary. Substantial tasks also require a durable handoff in the control repository.
- Do not release, tag, publish artifacts, or lock public coordinates unless the user explicitly authorizes that separate gate.
