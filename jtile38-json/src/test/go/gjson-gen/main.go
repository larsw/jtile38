// Generates differential test cases for the Kotlin gjson/sjson ports.
package main

import (
	"encoding/hex"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"math"
	"os"
	"sort"
	"strconv"
	"strings"

	"github.com/tidwall/gjson"
	"github.com/tidwall/sjson"
)

func h(s string) string { return hex.EncodeToString([]byte(s)) + "." }

func ints(a []int) string {
	if a == nil {
		return "nil"
	}
	var s []string
	for _, v := range a {
		s = append(s, strconv.Itoa(v))
	}
	return "[" + strings.Join(s, ",") + "]"
}

func res(r gjson.Result) string {
	return fmt.Sprintf("%d\t%s\t%s\t%x\t%d\t%s", r.Type, h(r.Raw), h(r.Str), math.Float64bits(r.Num), r.Index, ints(r.Indexes))
}

// literal string args of calls to the named functions
func literals(file string, names map[string]int) [][]string {
	fs := token.NewFileSet()
	f, err := parser.ParseFile(fs, file, nil, 0)
	if err != nil {
		panic(err)
	}
	var out [][]string
	ast.Inspect(f, func(n ast.Node) bool {
		call, ok := n.(*ast.CallExpr)
		if !ok {
			return true
		}
		var name string
		switch fn := call.Fun.(type) {
		case *ast.Ident:
			name = fn.Name
		case *ast.SelectorExpr:
			name = fn.Sel.Name
		}
		nargs, ok := names[name]
		if !ok || len(call.Args) < nargs {
			return true
		}
		args := []string{name}
		for i := 0; i < nargs; i++ {
			lit, ok := call.Args[i].(*ast.BasicLit)
			if !ok || lit.Kind != token.STRING {
				args = append(args, "\x00nonlit")
				continue
			}
			s, err := strconv.Unquote(lit.Value)
			if err != nil {
				return true
			}
			args = append(args, s)
		}
		out = append(out, args)
		return true
	})
	return out
}

func main() {
	gdir := os.Args[1]
	sdir := os.Args[2]
	docs := map[string]bool{}
	paths := map[string]bool{}
	for _, c := range literals(gdir+"/gjson_test.go", map[string]int{"Get": 2, "GetBytes": 2}) {
		if c[1] != "\x00nonlit" {
			docs[c[1]] = true
		}
		if c[2] != "\x00nonlit" {
			paths[c[2]] = true
		}
	}
	extraDocs, _ := os.ReadFile("docs.txt")
	for _, d := range strings.Split(string(extraDocs), "\n===\n") {
		if strings.TrimSpace(d) != "" {
			docs[d] = true
		}
	}
	extraPaths, _ := os.ReadFile("paths.txt")
	for _, p := range strings.Split(string(extraPaths), "\n") {
		if p != "" {
			paths[p] = true
		}
	}
	var dl, pl []string
	for d := range docs {
		dl = append(dl, d)
	}
	for p := range paths {
		pl = append(pl, p)
	}
	sort.Strings(dl)
	sort.Strings(pl)
	w := os.Stdout
	for _, d := range dl {
		fmt.Fprintf(w, "parse\t%s\t%s\n", h(d), res(gjson.Parse(d)))
		fmt.Fprintf(w, "valid\t%s\t%v\n", h(d), gjson.Valid(d))
		for _, p := range pl {
			fmt.Fprintf(w, "get\t%s\t%s\t%s\n", h(d), h(p), res(gjson.Get(d, p)))
		}
	}
	// sjson: literal cases from the sjson tests plus a cross product
	type sc struct{ op, json, path, val string }
	var scs []sc
	for _, c := range literals(sdir+"/sjson_test.go", map[string]int{"Set": 3, "SetRaw": 3, "Delete": 2}) {
		ok := true
		for _, a := range c[1:] {
			if a == "\x00nonlit" {
				ok = false
			}
		}
		if !ok {
			continue
		}
		if c[0] == "Delete" {
			scs = append(scs, sc{"delete", c[1], c[2], ""})
		} else if c[0] == "SetRaw" {
			scs = append(scs, sc{"setraw", c[1], c[2], c[3]})
		} else {
			scs = append(scs, sc{"set", c[1], c[2], c[3]})
		}
	}
	sdocs, _ := os.ReadFile("sdocs.txt")
	spaths, _ := os.ReadFile("spaths.txt")
	for _, d := range strings.Split(string(sdocs), "\n===\n") {
		for _, p := range strings.Split(string(spaths), "\n") {
			if p == "" {
				continue
			}
			scs = append(scs, sc{"set", d, p, "v\"é<"}, sc{"setraw", d, p, `{"x":[1,2]}`}, sc{"delete", d, p, ""})
		}
	}
	for _, c := range scs {
		var out string
		var err error
		switch c.op {
		case "set":
			out, err = sjson.Set(c.json, c.path, c.val)
		case "setraw":
			out, err = sjson.SetRaw(c.json, c.path, c.val)
		case "delete":
			out, err = sjson.Delete(c.json, c.path)
		}
		es := ""
		if err != nil {
			es = err.Error()
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\n", c.op, h(c.json), h(c.path), h(c.val), h(out), h(es))
	}
}
