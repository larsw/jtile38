# A5 case generator

Generates `src/test/resources/a5-cases.txt.gz`, the expected results that `A5DiffTest` compares
the Kotlin A5 port against bit for bit. It runs the a5-go version Tile38 uses
(`github.com/corewood-tech/a5go`, imported as `github.com/a5geo/a5-go`) on:

- a grid of points, a few special places and some random points at every resolution (-1 to 30):
  the cell ID, its center, its vertices (Cartesian and as lon/lat), its parent and, for low
  resolutions, its children and their compaction;
- 2000 random points at random resolutions (cell IDs only);
- the constants that Go folds with exact arithmetic.

Floats are written as the hex of their IEEE 754 bits.

```sh
cd jtile38-a5/src/test/go/a5-gen
go run . | gzip -9 > ../../resources/a5-cases.txt.gz
```
