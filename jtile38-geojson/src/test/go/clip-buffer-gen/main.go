package main

import (
	"fmt"

	"github.com/tidwall/geojson"
	"github.com/tidwall/tile38/internal/buffer"
	"github.com/tidwall/tile38/internal/clip"
)

func main() {
	objs := []string{
		`{"type":"Point","coordinates":[-112.5,33.5]}`,
		`{"type":"Point","coordinates":[179.9,89.9]}`,
		`{"type":"LineString","coordinates":[[-113.5,33.1],[-112.7,33.6],[-112.2,33.3]]}`,
		`{"type":"Polygon","coordinates":[[[-113,33],[-112,33],[-112,34],[-113,34],[-113,33]],[[-112.8,33.2],[-112.6,33.2],[-112.6,33.4],[-112.8,33.4],[-112.8,33.2]]]}`,
		`{"type":"MultiPoint","coordinates":[[-112.5,33.5],[10,10]]}`,
		`{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"properties":{"a":1}}`,
		`{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]},"properties":{}}]}`,
		`{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[1,2]}]}`,
	}
	for _, s := range objs {
		o, err := geojson.Parse(s, nil)
		if err != nil {
			panic(err)
		}
		for _, m := range []float64{1, 1000, 123456.789} {
			b, err := buffer.Simple(o, m)
			if err != nil {
				fmt.Printf("ERR %v\n", err)
				continue
			}
			fmt.Println(b.JSON())
		}
		r, _ := geojson.Parse(`{"type":"Polygon","coordinates":[[[-112.9,33.3],[-112.3,33.3],[-112.3,33.7],[-112.9,33.7],[-112.9,33.3]]]}`, nil)
		fmt.Println(clip.Clip(o, r, nil).JSON())
	}
}
