"""Refresh the checked-in oracle only when intentionally updating the desktop wire protocol.

Extracts pure protocol functions via AST, so PyQt/bleak and Bluetooth hardware are not needed.
Run from any directory: python android/scripts/generate_protocol_vectors.py
"""
import ast
from pathlib import Path

root = Path(__file__).resolve().parents[2]
tree = ast.parse((root / "ble_tool.py").read_text(encoding="utf-8"))
nodes = []
for node in tree.body:
    if isinstance(node, ast.Assign):
        names = [target.id for target in node.targets if isinstance(target, ast.Name)]
        if any(name.startswith(("_CRC8", "_PROTO_", "_PB_")) or name == "_proto_seq" for name in names):
            nodes.append(node)
    elif isinstance(node, ast.FunctionDef):
        if node.name.startswith(("pb_", "_encode_", "_decode_", "build_", "parse_")) or node.name in ("_crc8", "_pb_walk"):
            nodes.append(node)
namespace = {}
exec(compile(ast.Module(body=nodes, type_ignores=[]), "desktop_protocol", "exec"), namespace)


def call(name, *args, **kwargs):
    return namespace[name](*args, **kwargs)


fixtures = [
    ("ping", call("pb_encode_ping", "Hello from Android"), 60206),
    ("ping_utf8", call("pb_encode_ping", "你好 BLE"), 60206),
    ("file_first", call("pb_encode_file_write", call("pb_encode_file", "vol0:test.bin", 0, 220400, bytes(range(256))), True, False), 60805),
    ("file_later", call("pb_encode_file_write", call("pb_encode_file", "vol0:测试.bin", 1800, 5242880, b"\x00\xff\x5a"), False, False), 60805),
]
lines = []
for name, body, kind in fixtures:
    namespace["_proto_seq"] = 0
    lines.append(name + "\t" + call("build_pb_frame", kind, body, router=1).hex())
destination = root / "android/app/src/test/resources/desktop-vectors.tsv"
destination.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"Generated {len(lines)} desktop protocol vectors: {destination}")
