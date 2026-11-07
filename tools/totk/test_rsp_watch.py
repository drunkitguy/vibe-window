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

import rsp_watch

MAIN = 0x80004000
INFO = ("Process:     0x51 (Game)\nModules:\n  0x0080000000 - 0x0080003fff rtld\n"
        "  0x0080004000 - 0x0085ffffff main\n  0x0086000000 - 0x0086ffffff sdk\n")
ADDR, HASH = 0x9A100160, 0xB80E3DBB


def le(v):
    return v.to_bytes(8, "little").hex()


def g_reply(x, pc, lr, fp):
    r = [0] * 33
    r[:len(x)] = x
    r[29], r[30], r[32] = fp, lr, pc
    return "".join(le(v) for v in r) + "00000000"


class FakeStub(threading.Thread):
    """Listens (after `delay` s), takes one client, acks every packet and answers it from
    `script`, a list of (expected packet, reply or None). Records every packet in `got`, then
    whether the client closed (`closed`)."""

    def __init__(self, script, delay=0.0, port=0):
        super().__init__(daemon=True)
        self.script, self.delay, self.got, self.closed = list(script), delay, [], False
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
                conn.sendall(b"+")
                want, reply = self.script.pop(0) if self.script else (None, None)
                if packet != want:  # the close fails the client; `got` shows the packet
                    return
                if reply is not None:
                    body = reply.encode()
                    conn.sendall(b"$%s#%02x" % (body, sum(body) & 0xFF))


def run(argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = rsp_watch.main(argv)
    return code, out.getvalue()


class RspWatch(unittest.TestCase):
    def test_watch_skips_then_matches(self):
        z = "%x,4" % ADDR
        stop = "T0520:%s;1f:%s;1e:%s;thread:5a;watch:%x;" % (le(0), le(0), le(0), ADDR)
        frames = [(0x10F000, MAIN + 0x3E59A4), (0, MAIN + 0x20A1128)]
        script = [
            ("qRcmd," + b"info".hex(), INFO.encode().hex()),
            ("Z2," + z, "OK"),
            ("c", stop),  # stop 1: the flush, no hash in x0-x15
            ("g", g_reply([1, 2, 3], MAIN + 0x74240, MAIN + 0x3E5DFC, 0)),
            ("z2," + z, "OK"),
            ("s", "T05thread:5a;"),
            ("Z2," + z, "OK"),
            ("c", stop),  # stop 2: the push, the hash in x5's low 32 bits
            ("g", g_reply([7, 0, 0, 0, 0, 0xFFFF0000_00000000 | HASH], MAIN + 0x100, MAIN + 0x200,
                          0x10E000)),
            ("m10e000,10", le(frames[0][0]) + le(frames[0][1])),
            ("m10f000,10", le(frames[1][0]) + le(frames[1][1])),
            ("z2," + z, "OK"),
            ("c", None),
        ]
        stub = FakeStub(script)
        code, out = run(["127.0.0.1:%d" % stub.port, hex(ADDR), "4", hex(HASH)])
        stub.join(5)
        self.assertEqual(code, 0)
        self.assertEqual(stub.got, [p for p, _ in script])
        self.assertTrue(stub.closed)
        self.assertEqual(out.splitlines(), [
            "main base 0x80004000 (main)",
            "skip pc=main+74240 lr=main+3E5DFC",
            "match stop 2: pc=main+100 lr=main+200 hash in x5",
            "x0=0x7 x1=0x0 x2=0x0 x3=0x0 x4=0x0 x5=0xffff0000b80e3dbb x6=0x0 x7=0x0",
            "frame 0: main+3E59A4",
            "frame 1: main+20A1128",
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
        self.assertEqual((code, stub.got, stub.closed), (1, [p for p, _ in script], True))
        self.assertEqual(out.splitlines()[1:], ["skip pc=main+10 lr=main+20"] * 2 +
                         ["no match after 2 stops"])

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


if __name__ == "__main__":
    unittest.main()
