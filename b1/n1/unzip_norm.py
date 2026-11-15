#!/usr/bin/env python3
"""Extract a zip whose entries may use Windows path separators.

The client graph is zipped on Windows, so its entries carry backslashes; `unzip` refuses them.
This rewrites every entry name to forward slashes before extracting.
"""
import os
import sys
import zipfile


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: unzip_norm.py ARCHIVE DESTINATION")
        return 2
    source, destination = sys.argv[1], sys.argv[2]
    count = 0
    with zipfile.ZipFile(source) as archive:
        for info in archive.infolist():
            name = info.filename.replace("\\", "/")
            target = os.path.join(destination, name)
            if info.is_dir() or name.endswith("/"):
                os.makedirs(target, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with archive.open(info) as reader, open(target, "wb") as writer:
                writer.write(reader.read())
            count += 1
    print("extracted", count, "files into", destination)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
