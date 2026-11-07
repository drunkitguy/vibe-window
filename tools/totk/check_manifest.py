#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check the TotK manifest against the Eden Duo runtime and the module's key list (PLAN.md T4).

python3 -I tools/totk/check_manifest.py --eden <eden-duo checkout>
Fails (exit 1) when
- an object key (not starting with "_") is not a "key" string literal in $E/src/core/mods/*.cpp;
- an action kind is not page, module or view_reset; a module action is not in kActionNames, or
  its argument is not an integer in kActionArgs' range or "$payload"; a button action is not
  wheel_<a> for an <a> in gen_manifest.WHEEL, its enabled_bind is not ui.wheel.<a>, that derived
  expr is not exactly "totk.ability.<a>.owned && !totk.loading", its chord is not
  L+RStick(x,y), it has a field beyond kind/button/frames/enabled_bind (repeat, counter...
  re-press without the gate) or frames is not WHEEL_FRAMES, or enforce names it (PLAN.md CA7b);
- an on_tap/on_hold/on_swipe_*/drop_action/enforce names an undefined action, a page action or
  page bind an undefined page, a widget scroll an undefined region; a page id or derived name
  is defined twice;
- a rect is not 4 numbers or leaves the canvas; centre- or right-aligned literal text, measured
  from its anchor (rect x) with the built-in font's 4 px x text_scale advance, leaves the canvas;
- hide_eq is not an integer, a numeric field is not a number, a colour does not parse, a
  text_map key is not an integer, a widget type is missing or unknown, a haptic strength,
  transition, easing or cmp operator is not one the runtime knows (it would warn and fall back);
- a bind or gate (bind, bind_text, text_bind, *_bind, need_bind without "!", page_binds point,
  derived a/b and expr names) is not a totk_keys.h key, a derived name or a runtime key; a
  "totk." name anywhere is not in totk_keys.h; a derived expr does not parse;
- the file is over 4 MiB.
Warns when a key listed in totk_keys.h is bound nowhere. Std-lib only.
"""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # -I leaves the script's folder out
from gen_manifest import WHEEL, WHEEL_FRAMES  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "packages/TearsOfTheKingdom/dualscreen/manifest.json"
KEYS_HEADER = ROOT / "native/modules/totk_keys.h"
MAX_BYTES = 4 << 20
GLYPH_ADVANCE = 4  # built-in font: 3 px glyph + 1 px gap per character, times text_scale
KINDS = {"page", "module", "view_reset", "button"}  # button: only CA7b's wheel actions
NUMBER = r"-?\d+(?:\.\d+)?"
WHEEL_CHORD = re.compile(rf"L\+RStick\({NUMBER},{NUMBER}\)")
WHEEL_FIELDS = {"kind", "button", "frames", "enabled_bind"}  # repeat, gap, counter... re-press ungated
ACTION_REFS = ("on_tap", "on_hold", "on_swipe_left", "on_swipe_right", "on_swipe_up",
               "on_swipe_down", "drop_action")
# Objects whose keys are names (actions, text_map values, ...), not runtime keys.
NAME_MAPS = {"actions", "text_map", "tables", "sprite_map", "flags", "areas"}
TOKEN = re.compile(r"(?<![A-Za-z0-9_.])totk\.[A-Za-z0-9_.{}]*[A-Za-z0-9_}]")
# What the runtime parses (mod_manifest.cpp ParseWidgetType, ParseEasing, ParsePageBindTarget,
# ParseDerived; mod_runtime_shared.h HapticStrengthNames / HapticKindNames).
WIDGET_TYPES = {"rect", "label", "value", "bar", "button", "pips", "image", "map", "chart"}
STRENGTHS = {"off", "light", "click", "confirm", "heavy", "reject", "none", "tick", "strong"}
HAPTIC_KINDS = {"tap", "write", "select", "drag", "drop", "marker", "refused", "hold", "swipe"}
TRANSITIONS = {"slide_up", "slide_down", "grow", "shrink", "fade", "none"}
EASINGS = {"linear", "ease_in", "ease_out", "ease_in_out"}
CMP_OPS = {"eq", "ne", "ge", "gt", "le", "lt"}
FUNCTIONS = {"min", "max", "abs", "clamp", "floor", "ceil", "round"}
# Values the runtime publishes itself (mod_module_host.cpp RunGameModule).
RUNTIME_KEYS = {"module_ready", "module_error", "module_error_message", "build_match"}
# Fields read with json value<int>/get<number>: a string or bool there throws or misreads.
NUMERIC = {"format", "min_runtime", "canvas_w", "canvas_h", "text_scale", "wrap_width",
           "max_lines", "line_gap", "text_min_scale", "text_center_h", "hold_ms", "swipe_px",
           "duration_ms", "equals", "frame", "border", "text_inset", "outline_px", "pad", "div",
           "mul", "add", "repeat", "repeat_dx", "repeat_dy", "repeat_cols", "repeat_row_dy",
           "row_h", "cols", "every_ms", "detail_threshold", "frames"}
COLOR_KEYS = {"color", "bg", "background", "bar", "bar_track", "tint", "highlight_color",
              "outline", "drag_under_color", "drag_under_tint"}
COLOR = re.compile(r"#?[0-9A-Fa-f]{1,8}")


def runtime_literals(eden):
    mods = Path(eden) / "src/core/mods"
    sources = sorted(mods.glob("*.cpp"))
    if not sources:
        raise SystemExit(f"check_manifest: no runtime sources in {mods}")
    literals = set()
    for path in sources:
        literals.update(re.findall(r'"((?:[^"\\\n]|\\.)*)"', path.read_text(errors="replace")))
    return literals


def parse_keys_header(text):
    """(key patterns as {family: regex}, {module action name: argument range n, 0 = any})."""
    text = re.sub(r"//[^\n]*", "", text)  # comments quote "totk." too

    def array(name, rest):
        found = re.search(name + r"\[[^\]]*\]\s*=\s*\{(.*?)\};", rest, re.S)
        if not found:
            raise SystemExit(f"check_manifest: {name} not found in totk_keys.h")
        return found
    block = array("kActionNames", text)
    actions = re.findall(r'"([^"]*)"', block.group(1))
    args = [int(n) for n in re.findall(r"\d+", array("kActionArgs", text).group(1))]
    if len(args) != len(actions):
        raise SystemExit("check_manifest: kActionArgs and kActionNames differ in length")
    rest = text[:block.start()] + text[block.end():]

    def names(name):
        return re.findall(r'"([^"]*)"', array(name, rest).group(1))
    holders = {"<s>": "(?:" + "|".join(names("kGearSlotNames")) + ")",
               "<a>": "(?:" + "|".join(names("kAbilityNames")) + ")",
               "{i}": r"(?:\{i\}|0|[1-9][0-9]*)", "{k}": "[0-7]"}
    patterns = {}
    for key in re.findall(r'"(totk\.[^"]*)"', rest):
        regex = re.escape(key)
        for holder, alternative in holders.items():
            regex = regex.replace(re.escape(holder), alternative)
        patterns[key] = re.compile(regex)
    return patterns, dict(zip(actions, args))


def is_number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def strength_ok(v):
    return isinstance(v, bool) or isinstance(v, str) and v in STRENGTHS


def is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


EXPR_TOKEN = re.compile(r"\s*(?:(0[xX][0-9A-Fa-f]+|(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)"
                        r"|([A-Za-z_][A-Za-z0-9_.]*|@[A-Za-z0-9_.:]*)|'([^']*)'"
                        r"|(\|\||&&|==|!=|<=|>=|[<>+\-*/%!?:(),]))")
BINARY = [{"||"}, {"&&"}, {"==", "!="}, {"<", "<=", ">", ">="}, {"+", "-"}, {"*", "/", "%"}]


def expr_names(text):
    """The names a derived expr reads (runtime 17 grammar, mod_expr.cpp); ValueError if it does
    not parse."""
    tokens, at = [], 0
    while at < len(text.rstrip()):
        m = EXPR_TOKEN.match(text, at)
        if not m:
            raise ValueError(f"bad character at column {at + 1}")
        number, name, quoted, op = m.groups()
        tokens.append(("num", number) if number else ("name", name) if name else
                      ("name", quoted) if quoted is not None else ("op", op))
        at = m.end()
    names, pos = [], [0]

    def peek():
        return tokens[pos[0]] if pos[0] < len(tokens) else ("end", "")

    def take(value):
        if peek() != ("op", value):
            raise ValueError(f"expected '{value}' at token {pos[0] + 1}")
        pos[0] += 1

    def ternary():
        cond = binary(0)
        if peek() == ("op", "?"):
            pos[0] += 1
            ternary()
            take(":")
            ternary()
        return cond

    def binary(level):
        if level == len(BINARY):
            return unary()
        binary(level + 1)
        while peek()[0] == "op" and peek()[1] in BINARY[level]:
            pos[0] += 1
            binary(level + 1)

    def unary():
        if peek() in (("op", "!"), ("op", "-"), ("op", "+")):
            pos[0] += 1
            return unary()
        kind, value = peek()
        pos[0] += 1
        if kind == "num":
            return
        if kind == "name" and peek() == ("op", "(") and value in FUNCTIONS:
            pos[0] += 1
            ternary()
            while peek() == ("op", ","):
                pos[0] += 1
                ternary()
            take(")")
            return
        if kind == "name":
            names.append(value)
            return
        if (kind, value) == ("op", "("):
            ternary()
            take(")")
            return
        raise ValueError(f"unexpected '{value}' at token {pos[0]}")

    ternary()
    if peek()[0] != "end":
        raise ValueError(f"unexpected '{peek()[1]}' at token {pos[0] + 1}")
    return names


def check(manifest, literals, patterns, module_actions, size=0, data=None):
    """Returns (errors, warnings). data: the parsed dualscreen/data.json."""
    errors, warnings = [], []
    # data.json's points and derived merge into the same snapshot (mod_manifest.cpp ParsePoints,
    # ParseDerived), past every rule below.
    # shortcut: data.json must stay {}, run these rules over it when a packet commits data there.
    if data is not None and data != {}:
        errors.append(f"data.json is {json.dumps(data)[:60]}, not {{}}")
    if size > MAX_BYTES:
        errors.append(f"manifest is {size} bytes, over {MAX_BYTES}")
    canvas_w, canvas_h = (manifest.get(k) if is_number(manifest.get(k)) else 0
                          for k in ("canvas_w", "canvas_h"))

    def walk_keys(value, where, names=False):
        if isinstance(value, dict):
            for key, child in value.items():
                if not names and not key.startswith("_") and key not in literals:
                    errors.append(f"{where}: key '{key}' is not a runtime key literal")
                if not names and key in NUMERIC and not is_number(child):
                    errors.append(f"{where}: {key} {child!r} is not a number")
                if not names and key in COLOR_KEYS and not (
                        is_int(child) or isinstance(child, str) and COLOR.fullmatch(child)):
                    errors.append(f"{where}: {key} {child!r} is not a colour")
                if not names and key == "haptic" and not strength_ok(child):
                    errors.append(f"{where}: haptic {child!r} is not a strength")
                if not names and key == "transition" and child not in TRANSITIONS:
                    errors.append(f"{where}: transition {child!r} is not one of the runtime's")
                if not names and key == "easing" and child not in EASINGS:
                    errors.append(f"{where}: easing {child!r} is not one of the runtime's")
                walk_keys(child, f"{where}.{key}", key in NAME_MAPS and not names)
        elif isinstance(value, list):
            for i, child in enumerate(value):
                walk_keys(child, f"{where}[{i}]")
    walk_keys(manifest, "manifest")

    haptics = manifest.get("haptics")
    if isinstance(haptics, dict):
        for kind in HAPTIC_KINDS & haptics.keys():
            if not strength_ok(haptics[kind]):
                errors.append(f"haptics.{kind}: {haptics[kind]!r} is not a strength")

    actions = manifest.get("actions", {})
    pages = manifest.get("pages", [])
    page_ids = [p.get("id") for p in pages]
    for page_id in sorted({p for p in page_ids if page_ids.count(p) > 1}):
        errors.append(f"page id '{page_id}' is defined twice")
    for name, action in actions.items():
        kind = action.get("kind")
        if kind not in KINDS:
            errors.append(f"action '{name}': kind '{kind}' is not one of {sorted(KINDS)}")
        if kind == "module":
            module_action, arg = action.get("action"), action.get("argument", 0)
            if module_action not in module_actions:
                errors.append(f"action '{name}': module action '{module_action}' is not in "
                              "kActionNames (totk_keys.h)")
            elif arg != "$payload" and not (
                    is_int(arg) and (module_actions[module_action] == 0 or
                                     0 <= arg < module_actions[module_action])):
                errors.append(f"action '{name}': argument {arg!r} is not \"$payload\" or an "
                              f"integer in {module_action}'s range (kActionArgs)")
        if kind == "button":
            a = name[len("wheel_"):] if name.startswith("wheel_") else None
            gate = f"ui.wheel.{a}"
            exprs = [d.get("expr") for d in manifest.get("derived", []) if d.get("name") == gate]
            if a not in WHEEL:
                errors.append(f"action '{name}': kind 'button' is only for wheel_<a>, <a> in "
                              f"WHEEL {sorted(WHEEL)}")
            elif action.get("enabled_bind") != gate:
                errors.append(f"action '{name}': enabled_bind {action.get('enabled_bind')!r} is "
                              f"not '{gate}'")
            elif exprs != [f"totk.ability.{a}.owned && !totk.loading"]:
                errors.append(f"action '{name}': derived '{gate}' expr {exprs} is not "
                              f"'totk.ability.{a}.owned && !totk.loading'")
            if set(action) != WHEEL_FIELDS:
                errors.append(f"action '{name}': fields {sorted(action)} are not "
                              f"{sorted(WHEEL_FIELDS)}")
            if action.get("frames") != WHEEL_FRAMES:
                errors.append(f"action '{name}': frames {action.get('frames')!r} is not "
                              f"{WHEEL_FRAMES}")
            if not (isinstance(action.get("button"), str) and
                    WHEEL_CHORD.fullmatch(action["button"])):
                errors.append(f"action '{name}': chord {action.get('button')!r} is not "
                              "L+RStick(x,y)")
        if kind == "page" and action.get("page") not in page_ids:
            errors.append(f"action '{name}': page '{action.get('page')}' is not defined")
    for i, entry in enumerate(manifest.get("enforce", [])):
        if entry.get("action") not in actions:
            errors.append(f"enforce[{i}]: action '{entry.get('action')}' is not defined")
        elif actions[entry["action"]].get("kind") == "button":
            # enforce presses the chord every every_ms with no tap whenever the gate is open
            # (RunAction re-checks enabled_bind, so the gate holds, but the input is unrequested)
            errors.append(f"enforce[{i}]: action '{entry['action']}' is a wheel button")
    for i, bind in enumerate(manifest.get("page_binds", [])):
        for branch in ("when_equal", "when_not_equal"):
            target = bind.get(branch)
            if target is not None and target.get("page") not in page_ids:
                errors.append(f"page_binds[{i}].{branch}: page '{target.get('page')}' is not "
                              "defined")

    def check_rect(rect, where):
        if not (isinstance(rect, list) and len(rect) == 4 and all(map(is_number, rect))):
            errors.append(f"{where}: rect {rect!r} is not 4 numbers")
            return False
        if (rect[0] < 0 or rect[1] < 0 or rect[2] < 0 or rect[3] < 0 or
                rect[0] + rect[2] > canvas_w or rect[1] + rect[3] > canvas_h):
            errors.append(f"{where}: rect {rect} leaves the {canvas_w}x{canvas_h} canvas")
        return True

    for page in pages:
        scrolls = page.get("scrolls", [])
        regions = {s.get("id") for s in scrolls}
        for s in scrolls:
            check_rect(s.get("rect"), f"page '{page.get('id')}' scroll '{s.get('id')}'")
        for i, w in enumerate(page.get("widgets", [])):
            where = f"page '{page.get('id')}' widget {i}"
            if w.get("type") not in WIDGET_TYPES:
                errors.append(f"{where}: type {w.get('type')!r} is not a widget type (an "
                              "unknown or missing type silently becomes a label)")
            rect_ok = check_rect(w.get("rect"), where)
            # Labels and values centre on (or end at) rect x, not the rect's middle (buttons
            # are the exception: they centre in their rect).
            align, text = w.get("align", "left"), w.get("text", "")
            if (rect_ok and w.get("type") in ("label", "value") and align in ("center", "right")
                    and isinstance(text, str) and is_number(w.get("text_scale", 3))):
                width = GLYPH_ADVANCE * w.get("text_scale", 3) * len(text)
                left = w["rect"][0] - (width / 2 if align == "center" else width)
                if left < 0 or left + width > canvas_w:
                    errors.append(f"{where}: {align}-aligned text {text!r} spans x {left:g} to "
                                  f"{left + width:g} from its anchor x {w['rect'][0]}, off the "
                                  "canvas")
            if "hide_eq" in w and not is_int(w["hide_eq"]):
                errors.append(f"{where}: hide_eq {w['hide_eq']!r} is not an integer")
            for key in w.get("text_map", {}):
                if not re.fullmatch(r"-?(0x[0-9A-Fa-f]+|\d+)", key):
                    errors.append(f"{where}: text_map key {key!r} is not an integer")
            for ref in ACTION_REFS:
                if ref in w and w[ref] not in actions:
                    errors.append(f"{where}: {ref} '{w[ref]}' is not a defined action")
            if isinstance(w.get("scroll"), str) and w["scroll"] not in regions:
                errors.append(f"{where}: scroll '{w['scroll']}' is not a region of its page")

    # Every name a bind, gate or derived value reads must be something that gets published.
    derived = manifest.get("derived", [])
    derived_names = [d.get("name") for d in derived]
    for name in sorted({n for n in derived_names if derived_names.count(n) > 1}):
        errors.append(f"derived '{name}' is defined twice")

    def resolves(name):
        return (name in derived_names or name in RUNTIME_KEYS or
                any(r.fullmatch(name) for r in patterns.values()))

    def check_name(name, where):
        if isinstance(name, str) and not resolves(name):
            errors.append(f"{where}: '{name}' is not a totk_keys.h key, a derived name or a "
                          "runtime key")

    def walk_gates(value, where):
        if isinstance(value, dict):
            for k, child in value.items():
                if isinstance(child, str) and (k in ("bind", "bind_text", "point") or
                                               k.endswith("_bind")):
                    check_name(child[1:] if child.startswith("!") else child, f"{where}.{k}")
                walk_gates(child, f"{where}.{k}")
        elif isinstance(value, list):
            for i, child in enumerate(value):
                walk_gates(child, f"{where}[{i}]")
    walk_gates({k: v for k, v in manifest.items() if k != "derived"}, "manifest")
    for d in derived:
        where = f"derived '{d.get('name')}'"
        # EvaluateDerived runs after the module publishes and overwrites by name, so a derived
        # totk.loading or build_match would replace the gate inputs it shadows.
        name = d.get("name")
        if isinstance(name, str) and (name.startswith("totk.") or name in RUNTIME_KEYS):
            errors.append(f"{where}: name shadows a module or runtime key")
        if "cmp" in d:
            if d["cmp"] not in CMP_OPS:
                errors.append(f"{where}: cmp {d['cmp']!r} is not one of {sorted(CMP_OPS)}")
            for side in ("a", "b"):
                if not is_number(d.get(side)):
                    check_name(d.get(side), f"{where}.{side}")
        if "expr" in d:
            try:
                for name in expr_names(d["expr"]):
                    check_name(name, f"{where}.expr")
            except ValueError as e:
                errors.append(f"{where}: expr {d['expr']!r} does not parse: {e}")

    used = set()

    def walk_tokens(value, where):
        if isinstance(value, dict):
            for k, child in value.items():
                # a module action's "action" is an action name (checked above), not a key
                if not (k == "action" and value.get("kind") == "module"):
                    walk_tokens(child, f"{where}.{k}")
        elif isinstance(value, list):
            for i, child in enumerate(value):
                walk_tokens(child, f"{where}[{i}]")
        elif isinstance(value, str):
            for token in TOKEN.findall(value):
                hits = [f for f, regex in patterns.items() if regex.fullmatch(token)]
                if not hits:
                    errors.append(f"{where}: '{token}' is not a key in totk_keys.h")
                used.update(hits)
    walk_tokens(manifest, "manifest")
    for family in patterns:
        if family not in used:
            warnings.append(f"'{family}' (totk_keys.h) is bound by no page")
    return errors, warnings


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--eden", required=True, help="Eden Duo checkout (src/core/mods)")
    parser.add_argument("--manifest", default=str(MANIFEST))
    parser.add_argument("--keys", default=str(KEYS_HEADER))
    args = parser.parse_args(argv)
    raw = Path(args.manifest).read_bytes()
    patterns, module_actions = parse_keys_header(Path(args.keys).read_text())
    data = json.loads(Path(args.manifest).with_name("data.json").read_bytes())
    errors, warnings = check(json.loads(raw), runtime_literals(args.eden), patterns,
                             module_actions, len(raw), data)
    for w in warnings:
        print(f"check_manifest: warning: {w}", file=sys.stderr)
    for e in errors:
        print(f"check_manifest: error: {e}", file=sys.stderr)
    print(f"check_manifest: {len(errors)} errors, {len(warnings)} warnings")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
