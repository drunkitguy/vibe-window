# SPDX-License-Identifier: GPL-3.0-or-later
"""Tests for rsp_watch.py against a fake Eden gdbstub (acks every packet, replies like
src/core/debugger/gdbstub.cpp). python3 -I -m unittest discover -s tools/totk
"""
import contextlib
import io
import socket
import threading
import time
import unittest
from unittest import mock

import rsp_watch

MAIN = 0x80004000
# Eden's monitor info text [src/core/debugger/gdbstub.cpp:676-695]
INFO = ("Process:     0x51 (Game)\nProgram Id:  0x0100f2c0115b6000\nLayout:\n"
        "  Alias: 0x0100000000 - 0x10ffffffff\n  Heap:  0x1100000000 - 0x117fffffff\n"
        "  Aslr:  0x0008000000 - 0x7fffffffff\n  Stack: 0x1180000000 - 0x11bfffffff\nModules:\n"
        "  0x0080000000 - 0x0080003fff rtld\n  0x0080004000 - 0x0085ffffff main\n"
        "  0x0086000000 - 0x0086ffffff sdk\n")
ADDR, HASH = 0x9A100160, 0xB80E3DBB


def le(v):
    return v.to_bytes(8, "little").hex()


def g_reply(x, pc, lr, fp):
    r = [0] * 33
    r[:len(x)] = x
    r[29], r[30], r[32] = fp, lr, pc
    return "".join(le(v) for v in r) + "00000000"


class FakeStub(threading.Thread):
    """Listens (after `delay` s), takes one client, acks every packet (`ack`; anything else
    closes after it) and answers it from `script`, a list of (expected packet, reply or None[,
    seconds to wait before the reply]). Records every packet in `got`, then whether the client
    closed (`closed`)."""

    def __init__(self, script, delay=0.0, port=0, ack=b"+"):
        super().__init__(daemon=True)
        self.script, self.delay, self.got, self.closed = list(script), delay, [], False
        self.ack = ack
        if not port:
            with socket.socket() as s:
                s.bind(("127.0.0.1", 0))
                port = s.getsockname()[1]
        self.port = port
        self.start()

    def run(self):
        time.sleep(self.delay)
        with socket.create_server(("127.0.0.1", self.port)) as srv:
            conn, _ = srv.accept()
        with conn:
            buf = b""
            while True:
                while b"#" not in buf or len(buf) < buf.index(b"#") + 3:
                    data = conn.recv(4096)
                    if not data:
                        self.closed = True
                        return
                    buf += data
                start, end = buf.index(b"$"), buf.index(b"#")
                packet, buf = buf[start + 1:end].decode(), buf[end + 3:]
                self.got.append(packet)
                conn.sendall(self.ack)
                want, reply, wait = (self.script.pop(0) + (0,))[:3] if self.script else (0, 0, 0)
                if packet != want or self.ack != b"+":  # the close fails the client
                    return
                time.sleep(wait)
                if reply is not None:
                    body = reply.encode()
                    conn.sendall(b"$%s#%02x" % (body, sum(body) & 0xFF))


def run(argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
        code = rsp_watch.main(argv)
    return code, out.getvalue()


class RspWatch(unittest.TestCase):
    @mock.patch.object(rsp_watch, "ACK_TIMEOUT", 0.5)
    def test_watch_skips_then_matches(self):
        z = "%x,4" % ADDR
        stop = "T0520:%s;1f:%s;1e:%s;thread:5a;watch:%x;" % (le(0), le(0), le(0), ADDR)
        frames = [(0x10F000, MAIN + 0x3E59A4), (0x110000, 0x7100001234)]  # then outside main
        script = [
            ("qRcmd," + b"info".hex(), INFO.encode().hex()),
            ("Z2," + z, "OK"),
            ("c", stop),  # stop 1: the flush, the hash only in x16; lr: main's last byte
            ("g", g_reply([1, 2, 3] + [0] * 13 + [HASH], MAIN + 0x74240, 0x85FFFFFF, 0)),
            ("z2," + z, "OK"),
            ("s", "T05thread:5a;"),
            ("Z2," + z, "OK"),
            # stop 2, past ACK_TIMEOUT: the push, the hash in x12's low 32 bits and in x15 (the
            # first is printed)
            ("c", stop, 1.0),
            ("g", g_reply([7] + [0] * 11 + [0xFFFF0000_00000000 | HASH, 0, 0, HASH], MAIN + 0x100,
                          MAIN + 0x200, 0x10E000)),
            ("m10e000,10", le(frames[0][0]) + le(frames[0][1])),
            ("m10f000,10", le(frames[1][0]) + le(frames[1][1])),
            ("m110000,10", "E01"),  # unreadable: the walk stops
            ("z2," + z, "OK"),
            ("c", None),
        ]
        stub = FakeStub(script)
        code, out = run(["127.0.0.1:%d" % stub.port, hex(ADDR), "4", hex(HASH)])
        stub.join(5)
        self.assertEqual(code, 0)
        self.assertEqual(stub.got, [p[0] for p in script])
        self.assertTrue(stub.closed)
        self.assertEqual(out.splitlines(), [
            "main base 0x80004000 (main)",
            "skip pc=main+74240 lr=main+5FFBFFF",
            "match stop 2: pc=main+100 lr=main+200 hash in x12",
            "x0=0x7 x1=0x0 x2=0x0 x3=0x0 x4=0x0 x5=0x0 x6=0x0 x7=0x0",
            "frame 0: main+3E59A4",
            "frame 1: 0x7100001234",
        ])

    def test_gives_up_after_max_stops(self):
        z = "%x,4" % ADDR
        g = ("g", g_reply([HASH + 1], MAIN + 0x10, MAIN + 0x20, 0))
        script = [("qRcmd," + b"info".hex(), INFO.encode().hex()), ("Z2," + z, "OK"),
                  ("c", "T05thread:5a;"), g, ("z2," + z, "OK"), ("s", "T05thread:5a;"),
                  ("Z2," + z, "OK"), ("c", "T05thread:5a;"), g, ("z2," + z, "OK"), ("c", None)]
        stub = FakeStub(script)
        saved = rsp_watch.MAX_STOPS
        rsp_watch.MAX_STOPS = 2
        try:
            code, out = run(["127.0.0.1:%d" % stub.port, hex(ADDR), "4", hex(HASH)])
        finally:
            rsp_watch.MAX_STOPS = saved
        stub.join(5)
        self.assertEqual((code, stub.got, stub.closed), (1, [p[0] for p in script], True))
        self.assertEqual(out.splitlines()[1:], ["skip pc=main+10 lr=main+20"] * 2 +
                         ["no match after 2 stops"])

    def test_errors(self):
        z = "%x,4" % ADDR
        info = ("qRcmd," + b"info".hex(), INFO.encode().hex())
        g = ("g", g_reply([HASH], MAIN + 0x10, MAIN + 0x20, 0x10E000))
        match = "match stop 1: pc=main+10 lr=main+20 hash in x"
        for script, want in (
            # one module: no main (Eden's qOffsets would take it as main; TotK never has one)
            ([("qRcmd," + b"info".hex(),
               b"Modules:\n  0x0080000000 - 0x0080003fff rtld\n".hex())], []),
            ([info, ("Z2," + z, "E01")], []),  # Eden refuses the range: nothing armed
            # an error past the Z2 (a refused z2): the best-effort z2 and c still go out; the
            # walk stops at a misaligned fp without reading it
            ([info, ("Z2," + z, "OK"), ("c", "T05thread:5a;"), g,
              ("m10e000,10", le(0x10E004) + le(MAIN + 0x30)), ("z2," + z, "E01"),
              ("z2," + z, "OK"), ("c", None)], [match + "0", "frame 0: main+30"]),
            # the hash only in x15, fp 0 (a root frame): no frame read; a refused z2 ends it
            ([info, ("Z2," + z, "OK"), ("c", "T05thread:5a;"),
              ("g", g_reply([0] * 15 + [HASH], MAIN + 0x10, MAIN + 0x20, 0)), ("z2," + z, "E01"),
              ("z2," + z, "OK"), ("c", None)], [match + "15"]),
        ):
            stub = FakeStub(script)
            code, out = run(["127.0.0.1:%d" % stub.port, hex(ADDR), "4", hex(HASH)])
            stub.join(5)
            self.assertEqual((code, stub.got, stub.closed), (1, [p[0] for p in script], True))
            self.assertIn("; if the game stays frozen, run the go line;", out.splitlines()[-1])
            lines = [s for s in out.splitlines() if s.startswith(("match", "frame"))]
            self.assertEqual(lines, want)

    def test_nack_and_dead_stub(self):
        # a nack (Eden's answer to a bad checksum) fails the packet
        stub = FakeStub([], ack=b"-")
        code, out = run(["127.0.0.1:%d" % stub.port, hex(ADDR), "4", hex(HASH)])
        stub.join(5)
        self.assertEqual((code, stub.got), (1, ["qRcmd," + b"info".hex()]))
        self.assertIn("error: gdbstub refused qRcmd,", out)
        # a bad g reply, and the stub gone at the best-effort z2: the c fails silently, the
        # first error is the one printed
        z = "%x,4" % ADDR
        script = [("qRcmd," + b"info".hex(), INFO.encode().hex()), ("Z2," + z, "OK"),
                  ("c", "T05thread:5a;"), ("g", "E01")]
        stub = FakeStub(script)
        code, out = run(["127.0.0.1:%d" % stub.port, hex(ADDR), "4", hex(HASH)])
        stub.join(5)
        self.assertEqual((code, stub.got), (1, [p[0] for p in script] + ["z2," + z]))
        self.assertIn("fromhex", out.splitlines()[-1])

    def test_go_sends_one_c_then_closes(self):
        for delay in (0.0, 2.0):  # also when the stub starts listening 2 s late
            stub = FakeStub([("c", None)], delay=delay)
            code, out = run(["127.0.0.1:%d" % stub.port, "go"])
            stub.join(5)
            self.assertEqual((code, out), (0, ""))
            self.assertEqual(stub.got, ["c"])
            self.assertTrue(stub.closed)

    def test_no_stub_times_out(self):
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        saved = rsp_watch.CONNECT_TIMEOUT
        rsp_watch.CONNECT_TIMEOUT = 1.0
        try:
            code, out = run(["127.0.0.1:%d" % port, "go"])
        finally:
            rsp_watch.CONNECT_TIMEOUT = saved
        self.assertEqual(code, 1)
        self.assertEqual(out, "no gdbstub at 127.0.0.1:%d after 1 s; is --debug set?\n" % port)

    def test_bad_args(self):  # exit 2 before any connect
        for argv in (["127.0.0.1:1", "goo"], ["127.0.0.1:1", "zz", "4", "1"], ["127.0.0.1:1"]):
            self.assertEqual(run(argv), (2, ""))


if __name__ == "__main__":
    unittest.main()
