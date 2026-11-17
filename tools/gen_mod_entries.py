"""List classes annotated with NeoForge's @Mod.

FML constructs one instance of each mod entry class; the static runtime never did, so code that
reaches for a mod's singleton (AudioImprovements.getInstance() and friends) hits null. These are
the classes that have to be constructed at startup for the same effect.
"""
import struct
from pathlib import Path

GRAPH = Path(r"D:\harness\work\b1-local\dump2")
OUT = Path(r"D:\harness\work\b1-local\overrides\b1-mod-entries.txt")
TARGET = "Lnet/neoforged/fml/common/Mod;"


def skip_element_value(payload: bytes, offset: int) -> int:
    tag = chr(payload[offset])
    offset += 1
    if tag in "BCDFIJSZsc":
        return offset + 2
    if tag == "e":
        return offset + 4
    if tag == "@":
        return parse_annotation(payload, offset)
    if tag == "[":
        count = struct.unpack_from(">H", payload, offset)[0]
        offset += 2
        for _ in range(count):
            offset = skip_element_value(payload, offset)
        return offset
    return offset


def parse_annotation(payload: bytes, offset: int) -> int:
    type_index = struct.unpack_from(">H", payload, offset)[0]
    offset += 2
    pairs = struct.unpack_from(">H", payload, offset)[0]
    offset += 2
    for _ in range(pairs):
        offset += 2  # element name index
        offset = skip_element_value(payload, offset)
    return offset


def annotation_types(payload: bytes):
    types = []
    count = struct.unpack_from(">H", payload, 0)[0]
    offset = 2
    for _ in range(count):
        type_index = struct.unpack_from(">H", payload, offset)[0]
        types.append(type_index)
        offset = parse_annotation(payload, offset)
    return types


def parse(path: Path):
    data = path.read_bytes()
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    offset = 8
    count = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    pool = [None] * count
    index = 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            pool[index] = data[offset:offset + length]
            offset += length
        elif tag == 7:
            pool[index] = struct.unpack_from(">H", data, offset)[0]
            offset += 2
        elif tag in (8, 16, 19, 20):
            offset += 2
        elif tag == 15:
            offset += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        else:
            return None
        index += 1

    offset += 2
    this_class = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    offset += 2
    interfaces = struct.unpack_from(">H", data, offset)[0]
    offset += 2 + interfaces * 2
    name = pool[pool[this_class]].decode("utf-8", "replace")

    fields = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    for _ in range(fields):
        offset += 6
        attributes = struct.unpack_from(">H", data, offset)[0]
        offset += 2
        for _ in range(attributes):
            offset += 2
            length = struct.unpack_from(">I", data, offset)[0]
            offset += 4 + length

    methods = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    for _ in range(methods):
        offset += 6
        attributes = struct.unpack_from(">H", data, offset)[0]
        offset += 2
        for _ in range(attributes):
            offset += 2
            length = struct.unpack_from(">I", data, offset)[0]
            offset += 4 + length

    class_attributes = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    for _ in range(class_attributes):
        name_index = struct.unpack_from(">H", data, offset)[0]
        offset += 2
        length = struct.unpack_from(">I", data, offset)[0]
        offset += 4
        payload = data[offset:offset + length]
        offset += length
        if pool[name_index] != b"RuntimeVisibleAnnotations" and pool[name_index] != b"RuntimeInvisibleAnnotations":
            continue
        for type_index in annotation_types(payload):
            entry = pool[type_index]
            if entry is not None and entry.decode("utf-8", "replace") == TARGET:
                return name
    return None


found = []
for path in GRAPH.rglob("*.class"):
    try:
        name = parse(path)
    except Exception:
        continue
    if name:
        found.append(name.replace("/", "."))

OUT.write_text("\n".join(sorted(found)) + "\n", encoding="utf-8")
print("mod entry classes:", len(found))
for entry in sorted(found)[:10]:
    print(" ", entry)
