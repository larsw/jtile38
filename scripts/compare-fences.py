#!/usr/bin/env python3
"""Runs the same geofencing scenario against two servers (e.g. the original Tile38 and jtile38)
and diffs everything the live/subscriber connections receive.

Usage: scripts/compare-fences.py <reference-port> <jtile38-port>

Times, group ids, elapsed values and the bulk lengths that depend on them are normalized.
Only the Python standard library is used.
"""
import difflib
import re
import socket
import sys
import time


def resp(*args):
    out = b"*%d\r\n" % len(args)
    for a in args:
        b = str(a).encode()
        out += b"$%d\r\n%s\r\n" % (len(b), b)
    return out


class Conn:
    def __init__(self, port):
        self.s = socket.create_connection(("127.0.0.1", port))
        self.s.settimeout(0.3)
        self.buf = b""

    def send(self, data):
        self.s.sendall(data)

    def cmd(self, *args):
        self.send(resp(*args))
        return self.drain(0.2)

    def native(self, line):
        self.send(b"$%d %s\r\n" % (len(line.encode()), line.encode()))
        return self.drain(0.2)

    def drain(self, quiet=0.6):
        data = b""
        deadline = time.time() + quiet
        while time.time() < deadline:
            try:
                chunk = self.s.recv(65536)
                if not chunk:
                    break
                data += chunk
                deadline = time.time() + quiet
            except socket.timeout:
                pass
        return data


def scenario(port):
    out = []
    c = Conn(port)
    c.cmd("FLUSHDB")
    c.cmd("SET", "areas", "a", "OBJECT",
          '{"type":"Polygon","coordinates":[[[-116,32],[-114,32],[-114,34],[-116,34],[-116,32]]]}')

    lives = {}
    lives["resp-nearby-distance"] = Conn(port)
    out.append(("handshake resp", lives["resp-nearby-distance"].cmd("NEARBY", "fleet", "FENCE", "DISTANCE", "POINT", 33, -115, 5000)))
    lives["json-within-nofields"] = Conn(port)
    lives["json-within-nofields"].cmd("OUTPUT", "json")
    out.append(("handshake json", lives["json-within-nofields"].cmd("WITHIN", "fleet", "FENCE", "NOFIELDS", "BOUNDS", 32, -116, 34, -114)))
    lives["native-intersects-ids"] = Conn(port)
    out.append(("handshake native", lives["native-intersects-ids"].native("INTERSECTS fleet FENCE DETECT enter,exit IDS GET areas a")))
    lives["roam-scan"] = Conn(port)
    out.append(("handshake roam", lives["roam-scan"].cmd("NEARBY", "fleet", "FENCE", "ROAM", "fleet", "*", 3000, "SCAN", ":*")))
    lives["points-where"] = Conn(port)
    out.append(("handshake points", lives["points-where"].cmd("NEARBY", "fleet", "FENCE", "WHERE", "speed", 15, 100, "POINTS", "POINT", 33, -115, 5000)))

    out.append(("setchan", c.cmd("SETCHAN", "ch1", "META", "x", "y", "WITHIN", "fleet", "FENCE", "BOUNDS", 32, -116, 34, -114)))
    out.append(("setchan2", c.cmd("SETCHAN", "ch2", "NEARBY", "fleet", "FENCE", "DETECT", "inside", "COMMANDS", "set", "HASHES", 6, "POINT", 33, -115, 5000)))
    subs = {"resp-sub": Conn(port), "json-psub": Conn(port)}
    out.append(("subscribe", subs["resp-sub"].cmd("SUBSCRIBE", "ch1", "ch2")))
    subs["json-psub"].cmd("OUTPUT", "json")
    out.append(("psubscribe", subs["json-psub"].cmd("PSUBSCRIBE", "ch*")))

    writes = [
        ("SET", "fleet", "t1", "FIELD", "speed", 10, "POINT", 33, -115),
        ("SET", "fleet", "t2", "POINT", 33.01, -115.01),
        ("SET", "fleet", "t1:x", "POINT", 33.02, -115.02),
        ("FSET", "fleet", "t1", "speed", 20),
        ("SET", "fleet", "t1", "POINT", 40, -115),
        ("SET", "fleet", "t1", "POINT", 33, -115, 5),
        ("EXPIRE", "fleet", "t2", 100),
        ("PERSIST", "fleet", "t2"),
        ("SET", "fleet", "t3", "STRING", "not spatial"),
        ("DEL", "fleet", "t2"),
        ("PDEL", "fleet", "t*"),
        ("SET", "fleet", "t9", "POINT", 33, -115),
        ("DROP", "fleet"),
        ("PUBLISH", "ch1", "plain text"),
        ("PUBLISH", "ch2", '{"json":true}'),
    ]
    for w in writes:
        out.append((" ".join(map(str, w)), c.cmd(*w)))
        time.sleep(0.05)
    for name, conn in list(lives.items()) + list(subs.items()):
        out.append((name, conn.drain()))
    out.append(("chans json", (c.cmd("OUTPUT", "json"), c.cmd("CHANS", "*"))[1]))
    out.append(("chans resp", (c.cmd("OUTPUT", "resp"), c.cmd("CHANS", "*"))[1]))
    c.cmd("PDELCHAN", "*")
    return out


def normalize(data):
    s = data.decode("utf-8", "replace")
    s = re.sub(r'"time":"[^"]*"', '"time":"T"', s)
    s = re.sub(r'"group":"[^"]*"', '"group":"G"', s)
    s = re.sub(r'"elapsed":"[^"]*"', '"elapsed":"E"', s)
    s = re.sub(r"\$\d+(\r\n| )", r"$N\1", s)
    return s


def main():
    ref_port, jvm_port = int(sys.argv[1]), int(sys.argv[2])
    ref = scenario(ref_port)
    jvm = scenario(jvm_port)
    diffs = 0
    for (name, a), (_, b) in zip(ref, jvm):
        na, nb = normalize(a), normalize(b)
        if na != nb:
            diffs += 1
            print("--- %s" % name)
            for line in difflib.unified_diff(na.splitlines(), nb.splitlines(), "reference", "jtile38", lineterm=""):
                print(line)
    total = sum(len(normalize(a)) for _, a in ref)
    print("IDENTICAL (%d sections, %d bytes)" % (len(ref), total) if diffs == 0 else "%d section(s) differ" % diffs)
    return 1 if diffs else 0


if __name__ == "__main__":
    sys.exit(main())
