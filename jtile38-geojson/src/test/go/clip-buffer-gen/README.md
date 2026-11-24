# clip/buffer case generator

Generates `src/test/resources/clip-buffer-go.txt`, the expected output of Tile38's
`internal/buffer` and `internal/clip` that `ClipBufferTest` compares against. Those packages are
internal to the tile38 module, so the program must run from inside a tile38 checkout:

```sh
mkdir -p <tile38>/cmd/bufgen && cp main.go <tile38>/cmd/bufgen/
cd <tile38> && go run ./cmd/bufgen > clip-buffer-go.txt
```
