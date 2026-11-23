# Tile38's integration tests against jtile38

`scripts/run-upstream-tests.sh` runs the integration test suite of the original Tile38
(`tests/` in [tidwall/tile38](https://github.com/tidwall/tile38)) against the jtile38 server.

How it works:

- The script fetches tidwall/tile38 at a pinned commit into `target/tile38-upstream` (or uses
  `TILE38_SRC`) and applies `external-server.patch` to its test harness.
- With the patch, the harness starts the command in `TILE38_EXTERNAL_SERVER` (by default the
  jtile38 server jar) with `-h`, `-p` and `-d` for every sub-test, instead of an in-process Go
  server. Each sub-test still gets a fresh server, port and data directory.
- A server that fails to start reports its error as an `error: <message>` line on stderr.
- A panic in a sub-test is reported as a failure of that sub-test instead of aborting the run.

`expected-failures.txt` lists the sub-tests that fail today, each with the issue that will fix it.
The script exits non-zero when any other sub-test fails, and lists tests from the file that now
pass so the entry can be removed.

```sh
scripts/run-upstream-tests.sh                              # all groups
scripts/run-upstream-tests.sh 'TestIntegration/(keys|fence)'  # some groups
TILE38_EXTERNAL_SERVER=/path/to/tile38-server scripts/run-upstream-tests.sh  # any other server
```

Requirements: Go (the version required by Tile38's `go.mod`) and network access to fetch the
upstream repository and its Go modules.

With the original Go server binary as the external server, 82 of 86 sub-tests pass. The four
failures are harness limits: the metrics test needs a metrics address passed to the server, the
two AOF loading tests expect Go's in-process startup errors, and `aof/AOFSHRINK` needs the dev
command `MASSINSERT`, which upstream only enables in dev mode.
