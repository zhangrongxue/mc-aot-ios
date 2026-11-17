"""Build a resource-only jar from every mod jar in the pack.

The static runtime has the mod classes but none of their data: assets, lang files, default
configs, structure templates. Minecraft and the mods load those through the class loader, so a
single jar holding every non-class entry gives the runtime the same view a real launcher has.
"""
import sys
import zipfile
from pathlib import Path

MODS = Path(r"C:\Users\zrxma\Desktop\我的世界\.minecraft\versions\Better MC [NEOFORGE] BMC5\mods")
OUT = Path(r"D:\harness\work\b1-local\resources.jar")
SKIP_SUFFIX = (".class", ".jar", ".SF", ".RSA", ".DSA", ".EC")
SKIP_PREFIX = ("META-INF/versions/",)

seen = set()
count = 0
with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as target:
    for jar in sorted(MODS.glob("*.jar")):
        try:
            with zipfile.ZipFile(jar) as source:
                for info in source.infolist():
                    name = info.filename
                    if name.endswith("/") or name in seen:
                        continue
                    if name.endswith(SKIP_SUFFIX) or name.startswith(SKIP_PREFIX):
                        continue
                    if name.startswith("META-INF/") and name.count("/") == 1 and name.endswith((".MF", ".LIST")):
                        continue
                    seen.add(name)
                    target.writestr(name, source.read(info))
                    count += 1
        except Exception as error:  # noqa: BLE001
            print("skip", jar.name, error)

print("entries:", count, "size:", f"{OUT.stat().st_size / 1024 / 1024:.1f} MB")
