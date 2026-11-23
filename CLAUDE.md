# jtile38

JVM (Kotlin) port of [Tile38](https://github.com/tidwall/tile38). MIT licensed.

## Build

- JDK 25, Kotlin 2.4.x, Maven 3.9+. Multi-module build from the repo root: `mvn install`.
- Modules: `jtile38-json`, `jtile38-geojson`, `jtile38-rtree`, `jtile38-resp`, `jtile38-server`,
  `jtile38-client` (client library), `jtile38-cli` (port of `tile38-cli`).

## Code style: ktlint is mandatory

- All Kotlin code (main and test) must pass ktlint. The `ktlint-maven-plugin` is configured in the
  root `pom.xml` and runs `ktlint:check` in the `verify` phase, so `mvn verify` / `mvn install`
  fail on lint errors. This applies to every module, including new ones.
- Style rules live in `.editorconfig` (`ktlint_code_style = ktlint_official`, max line length 140).
- After writing or changing Kotlin code, run `mvn ktlint:format` (optionally `-pl <module>`), then
  `mvn ktlint:check`, and fix anything the formatter could not fix by hand. Do not suppress rules
  without a comment explaining why.

## Porting conventions

- Ports should be faithful to the Go sources (same semantics and byte-identical output), with the
  upstream copyright/MIT notice in the file header.
- Strings coming off the wire are "byte strings" (ISO-8859-1, one char per byte) to mirror Go's
  byte-oriented `string`.
