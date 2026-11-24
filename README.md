# jtile38

A JVM port of [Tile38](https://github.com/tidwall/tile38), the in-memory geospatial database and
geofencing server, written in Kotlin. jtile38 speaks the same protocols as Tile38 (RESP, the
Tile38 native protocol, telnet and HTTP, all on one port), so existing Tile38 and Redis client
libraries work against it.

It also includes a Kotlin/Java client library and `jtile38-cli`, a port of `tile38-cli`.

Status: **MVP**. Core CRUD, search and geofencing commands work. Replication and scripting are not
ported yet. See the [roadmap](#roadmap).

## Requirements

- JDK 25
- Maven 3.9+

## Build and run

```sh
mvn install                       # build, test and lint all modules
java -jar jtile38-server/target/jtile38-server.jar -p 9851
```

Server options: `-h host` (default `127.0.0.1`), `-p port` (default `9851`), `-d dir` (data
directory, default `data`), `--appendonly yes|no`, `-o resp|json` (default output for all clients).

Try it with `jtile38-cli`, the original `tile38-cli`, `redis-cli` or plain HTTP:

```sh
java -jar jtile38-cli/target/jtile38-cli.jar            # interactive, with history and tab completion
java -jar jtile38-cli/target/jtile38-cli.jar set fleet truck1 point 33.5123 -112.2693
java -jar jtile38-cli/target/jtile38-cli.jar --resp nearby fleet point 33.5 -112.2 10000
redis-cli -p 9851 GET fleet truck1
curl 'localhost:9851/get+fleet+truck1'
```

`jtile38-cli` takes the same options and environment variables as `tile38-cli`: `-h`, `-p`,
`--resp`, `--json`, `--raw`, `--noprompt`, `-x` (read the last argument from stdin),
`TILE38_HOSTNAME`, `TILE38_PORT`, `TILE38_OUTPUT` and `TILE38_PASSWORD`. Its output is identical to
`tile38-cli`'s. History is kept in `~/.jtile38-cli_history`.

## Client library

`jtile38-client` works with both jtile38 and the original Tile38. It is thread-safe, pools
connections and has typed methods for the supported commands:

```kotlin
Tile38Client("localhost", 9851).use { t38 ->
    t38.set("fleet", "truck1").field("speed", 90).point(33.5123, -112.2693)
    val truck = t38.getPoint("fleet", "truck1")                  // Point(lat=33.5123, lon=-112.2693)
    val near = t38.nearby("fleet").distance().point(33.5, -112.2, 10_000.0).asObjects()
    near.hits.forEach { println("${it.id} ${it.distance} ${it.fields}") }
    val count = t38.within("fleet").bounds(33.0, -113.0, 34.0, -112.0).count()
    val raw = t38.execute("SCAN", "fleet", "LIMIT", 10, "IDS")   // any command, raw RESP reply
}
```

From Java:

```java
try (var t38 = new Tile38Client("localhost", 9851)) {
    t38.set("fleet", "truck1").field("speed", 90).point(33.5123, -112.2693);
    SearchPage page = t38.within("fleet").circle(33.5, -112.2, 5000).limit(50).asIds();
}
```

Searches are built as options, then an area, then an output (`asIds`, `asObjects`, `asPoints`,
`asBounds`, `asHashes(precision)` or `count`). Results come in pages; pass `page.cursor` to
`cursor(...)` to fetch the next page. Server errors are thrown as `Tile38Exception`.

## Modules

| Module | Ports | Contents |
|---|---|---|
| `jtile38-json` | `tidwall/gjson`, `tidwall/sjson`, `tidwall/pretty`, `tidwall/match` | JSON validation, path queries (full gjson syntax), JSON editing, formatting, Go-compatible number and string formatting |
| `jtile38-geojson` | `tidwall/geojson` | GeoJSON objects, planar geometry, geodesic helpers |
| `jtile38-rtree` | `tidwall/rtree` | 2D R-tree with float32 coordinates and kNN search |
| `jtile38-resp` | `tidwall/redcon`, `tidwall/resp` | RESP, native and telnet command reader; RESP values |
| `jtile38-server` | `tile38/internal/{server,collection,field,object,glob,bing}`, `mmcloughlin/geohash` | The server |
| `jtile38-client` | | Client library (Kotlin and Java) |
| `jtile38-cli` | `tile38/cmd/tile38-cli`, `core/commands.json` | Command line interface (JLine for line editing) |

Go dependencies with a good JVM equivalent are not ported: `tidwall/btree` → `java.util.TreeMap`
and `TreeSet`, goroutines → virtual threads, `sync.RWMutex` → `ReentrantReadWriteLock`.

## Commands

`PING`, `ECHO`, `QUIT`, `OUTPUT`, `HEALTHZ`, `SERVER` (and `SERVER EXT`), `INFO`, `ROLE`, `STATS`,
`CONFIG GET|SET|REWRITE`, `CLIENT LIST|GETNAME|SETNAME|KILL`, `TIMEOUT`, `KEYS`, `SET` (`POINT`, `BOUNDS`,
`HASH`, `OBJECT`, `STRING`, `FIELD`, `EX`, `NX`/`XX`, `RETURN`), `GET` (`WITHFIELDS`,
`OBJECT`/`POINT`/`BOUNDS`/`HASH`), `DEL`, `PDEL`, `DROP`, `RENAME`, `RENAMENX`, `FLUSHDB`, `FSET`,
`FGET`, `FEXISTS`, `JGET`, `JSET`, `JDEL`, `EXISTS`, `EXPIRE`, `PERSIST`, `TTL`, `TYPE`, `BOUNDS`, `SCAN`, `SEARCH`,
`NEARBY`, `WITHIN`, `INTERSECTS`, `TEST`.

Search areas: `POINT`, `CIRCLE`, `BOUNDS`, `HASH`, `QUADKEY`, `TILE`, `OBJECT`, `GET`, `SECTOR`.
`TEST` also combines areas with `AND`, `OR`, `NOT` and parentheses. Search
options: `CURSOR`, `LIMIT`, `MATCH`, `WHERE` (range and comparison form), `WHEREIN`, `NOFIELDS`,
`DISTANCE`, `SPARSE`, `ASC`/`DESC`. Outputs: `COUNT`, `IDS`, `OBJECTS`, `POINTS`, `BOUNDS`,
`HASHES`.

`JGET`, `JSET` and `JDEL` edit an object's JSON document by path, with the full
[gjson](https://github.com/tidwall/gjson/blob/master/SYNTAX.md) path syntax for `JGET`
(queries, modifiers, multipaths). One deliberate difference from Tile38: Tile38 runs `JDEL` as a
read command, so its changes are not written to the AOF and are lost on restart; jtile38 treats
`JDEL` as a write.

Geofencing: live fences (`NEARBY`/`WITHIN`/`INTERSECTS ... FENCE` with `DETECT`, `COMMANDS`,
`NODWELL` and `ROAM key pattern meters [SCAN pattern]`), channels (`SETCHAN`, `DELCHAN`,
`PDELCHAN`, `CHANS`), pub/sub (`SUBSCRIBE`, `PSUBSCRIBE`, `PUBLISH`) and webhooks (`SETHOOK`,
`DELHOOK`, `PDELHOOK`, `HOOKS`, with `META` and `EX`). Webhooks deliver to `http://` and
`https://` endpoints; other endpoint types (Kafka, MQTT, gRPC, ...) are planned. Undelivered webhook
messages are retried for 30 seconds and survive restarts: like Tile38, they are kept in
`<dir>/queue.db`, which uses the same file format, so a data directory can be moved between Tile38
and jtile38.

Data persists in an append-only file (`<dir>/appendonly.aof`) in the same format as Tile38.

## Roadmap

The MVP (protocols, CRUD, search, AOF, expiry, client library and CLI) is done. The remaining work
is tracked as [GitHub issues](https://github.com/larsw/jtile38/issues?q=is%3Aissue+label%3Aroadmap),
grouped into milestones:

1. [Compatibility](https://github.com/larsw/jtile38/milestone/1): run Tile38's own integration
   tests against jtile38 and fill the command gaps
2. [Geofencing](https://github.com/larsw/jtile38/milestone/2): live fences, webhooks, channels,
   pub/sub and roaming
3. [Endpoints](https://github.com/larsw/jtile38/milestone/3): hook delivery to HTTP, gRPC, Kafka,
   MQTT and others
4. [Replication](https://github.com/larsw/jtile38/milestone/4): leader/follower replication
5. [Scripting](https://github.com/larsw/jtile38/milestone/5): Lua scripting
6. [Operations](https://github.com/larsw/jtile38/milestone/6): metrics, vector tiles,
   benchmarking, Docker and client-library follow-ups

## Development

All Kotlin code is checked with ktlint during `mvn verify`. Run `mvn ktlint:format` to fix style
issues. See [CLAUDE.md](CLAUDE.md) for conventions.

`scripts/run-upstream-tests.sh` runs Tile38's own integration test suite against jtile38 and fails
on regressions; see [compat/upstream-tests](compat/upstream-tests/README.md). Currently 63 of its 86
sub-tests pass, and each remaining failure is tracked in an issue.

More compatibility checks against the original Tile38 (build `tile38-server` and `tile38-cli`
from upstream first):

- `TILE38_EXTERNAL_PORT=9851 mvn -pl jtile38-server test -Dtest=ServerTest` runs the Jedis
  integration suite against an already running server, which confirms that the test expectations
  match upstream behavior.
- `scripts/compare-with-tile38.sh <tile38-cli> <tile38-port> <jtile38-port>` runs the same
  `tile38-cli` session against both servers and diffs the output, ignoring timings.
- `scripts/compare-cli.sh <tile38-cli> <jtile38-cli.jar> <port>` runs the same sessions through
  both CLIs against one server and diffs the output.
- `scripts/compare-fences.py <tile38-port> <jtile38-port>` runs a geofencing scenario (live fences,
  roaming, channels and subscribers) against both servers and diffs everything the clients receive.
- The client tests (`jtile38-client`) also honor `TILE38_EXTERNAL_PORT`.

## License

MIT. See [LICENSE](LICENSE). jtile38 is derived from Tile38 and several of its dependencies; their
copyright notices are in [NOTICE](NOTICE).
