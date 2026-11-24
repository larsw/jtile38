// Generates the expected results that A5DiffTest compares the Kotlin A5 port against.
//
// Every float is written as the hex of its IEEE 754 bits so the comparison is bit exact.
package main

import (
	"fmt"
	"math"
	"math/rand"
	"sort"
	"strconv"
	"strings"

	a5 "github.com/a5geo/a5-go"
)

func b(f float64) string { return strconv.FormatUint(math.Float64bits(f), 16) }

func h(id uint64) string { return strconv.FormatUint(id, 16) }

func main() {
	// Go folds these constant expressions with exact arithmetic.
	fmt.Printf("const\tPI_OVER_180\t%s\n", b(math.Pi/180))
	fmt.Printf("const\t180_OVER_PI\t%s\n", b(180/math.Pi))
	fmt.Printf("const\tTWO_PI_OVER_5\t%s\n", b(a5.TwoPiOver5))
	fmt.Printf("const\tPI_OVER_5\t%s\n", b(a5.PiOver5))
	fmt.Printf("const\tPI_OVER_10\t%s\n", b(a5.PiOver10))
	fmt.Printf("const\tPHI\t%s\n", b(a5.Phi))
	fmt.Printf("const\tDIHEDRAL_ANGLE\t%s\n", b(a5.DihedralAngle))
	fmt.Printf("const\tINTERHEDRAL_ANGLE\t%s\n", b(a5.InterhedralAngle))
	fmt.Printf("const\tFACE_EDGE_ANGLE\t%s\n", b(a5.FaceEdgeAngle))
	fmt.Printf("const\tR_CIRCUMSCRIBED\t%s\n", b(a5.Rcircumscribed))

	// Points with every resolution: a grid, special places and random points.
	var full []a5.LonLat
	for lon := -180.0; lon <= 180; lon += 60 {
		for lat := -90.0; lat <= 90; lat += 45 {
			full = append(full, a5.LonLat{lon, lat})
		}
	}
	full = append(full,
		a5.LonLat{13, 52}, a5.LonLat{0, 0}, a5.LonLat{-73.9857, 40.7484},
		a5.LonLat{-93, 89.9999}, a5.LonLat{179.99999, -0.00001}, a5.LonLat{-112.2693, 33.5123},
		a5.LonLat{151.2093, -33.8688}, a5.LonLat{-0.1276, 51.5072}, a5.LonLat{200, 100},
	)
	rnd := rand.New(rand.NewSource(1))
	for i := 0; i < 6; i++ {
		full = append(full, a5.LonLat{rnd.Float64()*360 - 180, rnd.Float64()*180 - 90})
	}
	ids := map[uint64]bool{}
	for _, ll := range full {
		for res := -1; res <= 30; res++ {
			id, err := a5.LonLatToCell(ll, res)
			if err != nil {
				fmt.Printf("cell\t%s\t%s\t%d\terr:%v\n", b(ll[0]), b(ll[1]), res, err)
				continue
			}
			fmt.Printf("cell\t%s\t%s\t%d\t%s\n", b(ll[0]), b(ll[1]), res, h(id))
			ids[id] = true
		}
	}
	// Many more random points at random resolutions (cell ids only).
	for i := 0; i < 2000; i++ {
		ll := a5.LonLat{rnd.Float64()*360 - 180, rnd.Float64()*180 - 90}
		res := rnd.Intn(31)
		id, _ := a5.LonLatToCell(ll, res)
		fmt.Printf("cell\t%s\t%s\t%d\t%s\n", b(ll[0]), b(ll[1]), res, h(id))
	}

	var sorted []uint64
	for id := range ids {
		sorted = append(sorted, id)
	}
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	for _, id := range sorted {
		if panics(func() { a5.CellToLonLat(id) }) {
			fmt.Printf("panics\t%s\n", h(id))
			continue
		}
		ll := a5.CellToLonLat(id)
		fmt.Printf("center\t%s\t%d\t%s\t%s\n", h(id), a5.GetResolution(id), b(ll[0]), b(ll[1]))
		var vs []string
		for _, c := range a5.CellVertices(id) {
			v := a5.ToLonLat(a5.ToSpherical(c))
			vs = append(vs, b(c[0])+","+b(c[1])+","+b(c[2])+","+b(v[0])+","+b(v[1]))
		}
		fmt.Printf("verts\t%s\t%s\n", h(id), strings.Join(vs, ";"))
		res := a5.GetResolution(id)
		p, err := a5.CellToParent(id, res-1)
		fmt.Printf("parent\t%s\t%s\t%v\n", h(id), h(p), err != nil)
		if res < 4 {
			ch, _ := a5.CellToChildren(id, res+1)
			var cs []string
			for _, c := range ch {
				cs = append(cs, h(c))
			}
			fmt.Printf("children\t%s\t%s\n", h(id), strings.Join(cs, ","))
			cp := a5.Compact(ch)
			var cps []string
			for _, c := range cp {
				cps = append(cps, h(c))
			}
			fmt.Printf("compact\t%s\t%s\n", h(id), strings.Join(cps, ","))
		}
	}
}

func panics(f func()) (p bool) {
	defer func() {
		if recover() != nil {
			p = true
		}
	}()
	f()
	return false
}
