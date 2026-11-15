#!/usr/bin/env python3
"""Assemble a NeoForge client launch command from its version json.

NeoForge's *client* install has no run.sh (that is a server artefact): the launcher is expected
to expand versions/<id>/<id>.json. This script does the same expansion and execs java, so the B1
dump agent can run against a real client.

Usage:
    python3 launch_client.py --root <install dir> [--version-id <id>] [--dry-run]
"""
import argparse
import json
import os
import platform
import subprocess
import sys


def os_tokens():
    system = platform.system().lower()
    name = "osx" if system == "darwin" else ("windows" if system == "windows" else "linux")
    machine = platform.machine().lower()
    if machine in ("arm64", "aarch64"):
        arch = "arm64"
    elif machine in ("x86_64", "amd64"):
        arch = "x86_64"
    else:
        arch = "x86"
    return name, arch


def rules_allow(rules):
    if not rules:
        return True
    name, arch = os_tokens()
    allowed = False
    for rule in rules:
        action = rule.get("action", "allow")
        os_rule = rule.get("os") or {}
        matches = True
        if "name" in os_rule and os_rule["name"] != name:
            matches = False
        if "arch" in os_rule and os_rule["arch"] != arch:
            matches = False
        if matches:
            allowed = action == "allow"
    return allowed


def library_path(root, library):
    downloads = library.get("downloads") or {}
    artifact = downloads.get("artifact") or {}
    if artifact.get("path"):
        return os.path.join(root, "libraries", artifact["path"])
    name = library.get("name", "")
    parts = name.split(":")
    if len(parts) < 3:
        return None
    group, artifact_name, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else ""
    file_name = f"{artifact_name}-{version}" + (f"-{classifier}" if classifier else "") + ".jar"
    return os.path.join(root, "libraries", group.replace(".", "/"), artifact_name, version, file_name)


def build_classpath(root, libraries):
    entries = []
    missing = []
    for library in libraries or []:
        if not rules_allow(library.get("rules")):
            continue
        path = library_path(root, library)
        if path and os.path.isfile(path):
            entries.append(path)
        elif path:
            missing.append(path)
    return entries, missing


def placeholder_map(root, version_id, version_json):
    natives = os.path.join(root, "natives")
    os.makedirs(natives, exist_ok=True)
    entries, missing = build_classpath(root, version_json.get("libraries"))
    return {
        "library_directory": os.path.join(root, "libraries"),
        "classpath_separator": os.pathsep,
        "classpath": os.pathsep.join(entries),
        "natives_directory": natives,
        "version_name": version_id,
        "game_directory": os.path.join(root, "game"),
        "assets_root": os.path.join(root, "assets"),
        "assets_index_name": (version_json.get("assetIndex") or {}).get("id", "17"),
        "auth_player_name": "Dev",
        "auth_uuid": "00000000000000000000000000000000",
        "auth_access_token": "0",
        "auth_session": "0",
        "user_type": "legacy",
        "version_type": "release",
        "launch_target": "forgeclient",
        "launcher_name": "b1-launcher",
        "launcher_version": "1",
        "resolution_width": "-1",
        "resolution_height": "-1",
        "user_properties": "{}",
        "clientid": "",
        "auth_xuid": "",
        "quickPlayPath": "",
        "quickPlaySingleplayer": "",
        "quickPlayMultiplayer": "",
        "quickPlayRealms": "",
    }, entries, missing


def expand(value, mapping):
    for key, replacement in mapping.items():
        value = value.replace("${" + key + "}", str(replacement))
    return value


def collect_arguments(entries, mapping):
    out = []
    for entry in entries or []:
        if isinstance(entry, str):
            out.append(expand(entry, mapping))
            continue
        if not isinstance(entry, dict):
            continue
        if not rules_allow(entry.get("rules")):
            continue
        values = entry.get("value")
        if isinstance(values, str):
            values = [values]
        out.extend(expand(value, mapping) for value in values or [])
    return out


def find_version_json(root, version_id):
    versions_dir = os.path.join(root, "versions")
    if not os.path.isdir(versions_dir):
        return None
    candidates = []
    for name in sorted(os.listdir(versions_dir)):
        path = os.path.join(versions_dir, name, name + ".json")
        if os.path.isfile(path):
            candidates.append((name, path))
    if version_id:
        for name, path in candidates:
            if name == version_id:
                return path
    for name, path in candidates:
        if name.lower().startswith("neoforge"):
            return path
    return candidates[0][1] if candidates else None


def vanilla_version_json(root, version_id):
    """NeoForge's client json inheritsFrom the vanilla one; fetch it if the launcher never did."""
    path = os.path.join(root, "versions", version_id, version_id + ".json")
    if os.path.isfile(path):
        return path
    import urllib.request

    try:
        with urllib.request.urlopen("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", timeout=60) as response:
            manifest = json.load(response)
        entry = next(v for v in manifest["versions"] if v["id"] == version_id)
        with urllib.request.urlopen(entry["url"], timeout=60) as response:
            data = response.read()
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as handle:
            handle.write(data)
        print("downloaded vanilla version json for", version_id)
        return path
    except Exception as error:  # noqa: BLE001 - the caller reports a clearer message
        print("cannot fetch vanilla version json:", error)
        return None


def merge_version(parent, child):
    merged = dict(parent)
    merged.update({key: value for key, value in child.items() if key != "arguments" and key != "libraries"})
    parent_args = parent.get("arguments") or {}
    child_args = child.get("arguments") or {}
    merged["arguments"] = {
        "jvm": list(parent_args.get("jvm") or []) + list(child_args.get("jvm") or []),
        "game": list(parent_args.get("game") or []) + list(child_args.get("game") or []),
    }
    merged["libraries"] = list(parent.get("libraries") or []) + list(child.get("libraries") or [])
    merged["assetIndex"] = child.get("assetIndex") or parent.get("assetIndex")
    return merged


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, help="NeoForge install directory")
    parser.add_argument("--version-id", default="")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    version_json_path = find_version_json(args.root, args.version_id)
    if not version_json_path:
        sys.exit("no version json under " + os.path.join(args.root, "versions"))
    with open(version_json_path, encoding="utf-8") as handle:
        data = json.load(handle)
    version_id = os.path.basename(version_json_path)[: -len(".json")]

    parent_id = data.get("inheritsFrom")
    if parent_id:
        parent_path = vanilla_version_json(args.root, parent_id)
        if parent_path:
            with open(parent_path, encoding="utf-8") as handle:
                data = merge_version(json.load(handle), data)
            print("merged with parent version", parent_id)
        else:
            print("WARNING: parent version", parent_id, "is unavailable; args will be incomplete")

    mapping, classpath_entries, classpath_missing = placeholder_map(args.root, version_id, data)

    jvm_args = collect_arguments((data.get("arguments") or {}).get("jvm"), mapping)
    game_args = collect_arguments((data.get("arguments") or {}).get("game"), mapping)

    if not any(arg == "--launchTarget" for arg in game_args):
        game_args = ["--launchTarget", "forgeclient"] + game_args

    main_class = data.get("mainClass")
    if not main_class:
        sys.exit("version json has no mainClass")

    java = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java")
    if not os.path.isfile(java):
        java = "java"
    command = [java] + jvm_args + [main_class] + game_args

    leftovers = sorted({token for token in " ".join(command).split() if token.startswith("${")})
    print("version json:", version_json_path)
    print("jvm args:", len(jvm_args), "game args:", len(game_args))
    print("classpath entries:", len(classpath_entries), "missing:", len(classpath_missing))
    if classpath_missing:
        print("first missing:", classpath_missing[:3])
    print("unexpanded placeholders:", leftovers or "none")
    print("full command:")
    print(" ".join(command))
    if args.dry_run:
        return
    os.makedirs(os.path.join(args.root, "game"), exist_ok=True)
    raise SystemExit(subprocess.call(command, cwd=args.root))


if __name__ == "__main__":
    main()
