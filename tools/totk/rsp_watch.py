#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Eden gdbstub client for the A3 watch sessions (docs/PLAN.md §A3 step 2). Std-lib only.

py -3 rsp_watch.py <host:port> go
    Connect (retrying for 60 s: the stub may not listen yet right after the launch), send c and
    close while the game runs: ends the boot suspension of a --debug launch.
py -3 rsp_watch.py <host:port> <addr> <len> <hash>
    Read the main base (monitor info), set one write watchpoint on <addr>/<len> and continue.
    At each stop, if one of x0-x15 holds <hash> in its low 32 bits, print pc, lr, x0-x7 and 16
    frames of the x29 chain as main+X and finish; else print a skip line, step past the store
    with the watchpoint removed, set it again and continue. Finishes after the match or 200
    stops: removes the watchpoint, continues and closes while the game runs. After an error past
    the Z2 it still tries to remove the watchpoint and continue: Eden keeps a watchpoint after
    the client is gone, and its next hit would freeze the game with nobody to continue it.
shortcut: the hash filter assumes the push still holds the hash in a register (UNVERIFIED),
print every stop's frames if no row matches.
Exit 0 after a match or go, 2 for bad arguments, 1 otherwise.
"""
import contextlib
import socket
import sys
import time

CONNECT_TIMEOUT = 60.0  # seconds; the tests patch it
RETRY = 0.5
ACK_TIMEOUT = 10.0  # the stub acks every packet at once
MAX_STOPS = 200
FRAMES = 16


class Stub:
    def __init__(self, sock):
        self.sock = sock
        self.buf = b""

    def _byte(self):
        if not self.buf:
            self.buf = self.sock.recv(4096)
            if not self.buf:
                raise ConnectionError("gdbstub closed the connection")
        b, self.buf = self.buf[:1], self.buf[1:]
        return b

    def send(self, body):
        """One packet; returns once the stub acked it (so a close after it loses nothing)."""
        data = body.encode()
        self.sock.sendall(b"$%s#%02x" % (data, sum(data) & 0xFF))
        if self._byte() != b"+":
            raise ConnectionError("gdbstub refused " + body)

    def recv(self):
        """The next reply packet's body. Eden escapes no hex digit and never run-length encodes."""
        while self._byte() != b"$":
            pass
        body = b""
        while (b := self._byte()) != b"#":
            body += b
        self._byte(), self._byte()  # checksum
        return body.decode("latin-1")

    def ask(self, body):
        self.send(body)
        return self.recv()

    def ok(self, body):
        reply = self.ask(body)
        if reply != "OK":
            raise ConnectionError("%s -> %r" % (body, reply))


def connect(hostport):
    host, port = hostport.rsplit(":", 1)
    end = time.monotonic() + CONNECT_TIMEOUT
    while True:
        try:
            sock = socket.create_connection((host, int(port)), timeout=ACK_TIMEOUT)
            return sock
        except OSError:
            if time.monotonic() >= end:
                return None
            time.sleep(RETRY)


def main_module(stub):
    """(base, end, name) of main from `monitor info`: the second module when there are two or
    more, else the first, as Eden's own qOffsets picks it [S1 src/core/arm/debug.cpp:318-328]."""
    text = bytes.fromhex(stub.ask("qRcmd," + b"info".hex())).decode("latin-1")
    mods = []
    for line in text.split("Modules:", 1)[-1].splitlines():
        parts = line.split()
        if len(parts) >= 4 and parts[1] == "-":
            mods.append((int(parts[0], 16), int(parts[2], 16) + 1, " ".join(parts[3:])))
    if not mods:
        raise ConnectionError("monitor info lists no module")
    return mods[1] if len(mods) >= 2 else mods[0]


def regs(stub):
    """x0-x30, sp, pc from the g packet (8 little-endian bytes each)."""
    g = stub.ask("g")
    return [int.from_bytes(bytes.fromhex(g[i * 16:i * 16 + 16]), "little") for i in range(33)]


def watch(stub, addr, length, want):
    base, end, name = main_module(stub)
    print("main base 0x%x (%s)" % (base, name), flush=True)

    def rel(v):
        return "main+%X" % (v - base) if base <= v < end else "0x%x" % v

    z = "%x,%x" % (addr, length)
    stub.ok("Z2," + z)
    done = False
    try:
        code = stops(stub, z, want, rel)
        stub.ok("z2," + z)
        stub.send("c")
        done = True
        return code
    finally:
        if not done:  # best effort; z2 answers E01 when a step had already removed it
            with contextlib.suppress(OSError, ValueError):
                stub.ask("z2," + z)
            with contextlib.suppress(OSError, ValueError):
                stub.send("c")


def stops(stub, z, want, rel):
    """Continues from the armed watchpoint until the match (0) or MAX_STOPS stops (1); returns
    with the game stopped and the watchpoint armed."""
    stub.send("c")
    for n in range(1, MAX_STOPS + 1):
        stub.recv()  # the stop reply
        r = regs(stub)
        hit = [i for i in range(16) if r[i] & 0xFFFFFFFF == want]
        if hit:
            print("match stop %d: pc=%s lr=%s hash in x%d" % (n, rel(r[32]), rel(r[30]), hit[0]))
            print(" ".join("x%d=0x%x" % (i, r[i]) for i in range(8)))
            fp = r[29]
            for i in range(FRAMES):
                mem = stub.ask("m%x,10" % fp) if fp and fp % 8 == 0 else ""
                if len(mem) != 32:
                    break
                fp, ret = (int.from_bytes(bytes.fromhex(mem[j:j + 16]), "little") for j in (0, 16))
                print("frame %d: %s" % (i, rel(ret)))
            sys.stdout.flush()
            return 0
        print("skip pc=%s lr=%s" % (rel(r[32]), rel(r[30])), flush=True)
        if n == MAX_STOPS:
            break
        stub.ok("z2," + z)
        stub.send("s")
        stub.recv()  # the step's stop reply
        stub.ok("Z2," + z)
        stub.send("c")
    print("no match after %d stops" % MAX_STOPS)
    return 1


def main(argv):
    if len(argv) not in (2, 4) or (len(argv) == 2 and argv[1] != "go"):
        print(__doc__, file=sys.stderr)
        return 2
    try:
        args = [int(a, 0) for a in argv[1:]] if len(argv) == 4 else None
    except ValueError:
        print("addr, len and hash must be numbers (0x... for hex)", file=sys.stderr)
        return 2
    sock = connect(argv[0])
    if sock is None:
        print("no gdbstub at %s after %g s; is --debug set?" % (argv[0], CONNECT_TIMEOUT))
        return 1
    with sock:
        stub = Stub(sock)
        if args is None:
            stub.send("c")
            return 0
        sock.settimeout(None)  # a stop comes when the player acts
        try:
            return watch(stub, *args)
        except (ConnectionError, ValueError) as e:
            print("error: %s; if the game stays frozen, run the go line; if it freezes again, quit "
                  "eden-cli and relaunch" % e)
            return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
