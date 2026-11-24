# gjson/sjson case generator

Generates `src/test/resources/gjson-sjson-cases.txt.gz`, the expected results that
`GjsonDiffTest` compares the Kotlin ports against. It runs the real `tidwall/gjson` v1.18.0 and
`tidwall/sjson` v1.2.5 on:

- every literal document and path passed to `Get` in gjson's own `gjson_test.go`, plus the
  documents in `docs.txt` and paths in `paths.txt` (all documents × all paths);
- every literal `Set`/`SetRaw`/`Delete` call in sjson's `sjson_test.go`, plus `sdocs.txt` ×
  `spaths.txt`.

Strings are hex encoded (with a trailing `.`) so the file is byte exact.

```sh
cd jtile38-json/src/test/go/gjson-gen
go run . "$(go env GOMODCACHE)/github.com/tidwall/gjson@v1.18.0" \
         "$(go env GOMODCACHE)/github.com/tidwall/sjson@v1.2.5" \
  | gzip -9 > ../../resources/gjson-sjson-cases.txt.gz
```

(`go mod download` the two modules first if they are not in the module cache.)
