# Repository working instructions

- Always commit completed work. Multiple commits for a single user instruction
  are fine; use coherent commits and include all changes made for that work.
- Give every commit a short, descriptive headline followed by a detailed body.
  The body must explain the code changes and their purpose, and describe testing:
  tests added or changed, commands run, results, and any verification limitations.
  Do not claim checks that were not performed.
- Always add useful tests for the work. Cover expected behavior, failure cases,
  and relevant security or policy boundaries. Refactor existing code when needed
  to make behavior testable rather than leaving it untested.
- Run the relevant tests and build checks before committing. For plugin changes,
  use `mvn verify`, including JSP compilation and plugin packaging. Check relevant
  dependency compatibility when changing integration or dependency behavior.
- Maintain the overall design in `doc/design.md`, including decisions,
  assumptions, scope, test coverage, and known limitations.
- Stop and ask questions when requirements or design choices need clarification.
  Record the resulting decisions in the design document.

## Project context

This plugin implements server security-label enforcement using XEP-0258 and
Spiffing Java. Openfire source is in `../openfire`; the Spiffing Java library is
in `../spiffing-java`. Follow `README.md` for dependency installation and building.
The minimum runtime is Java 17. The test suite uses real Spiffing policy fixtures
and XMPP message objects, with injected adapters for persistence and lifecycle
behavior that would otherwise require a running Openfire server.
