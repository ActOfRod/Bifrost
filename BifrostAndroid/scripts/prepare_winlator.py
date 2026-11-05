#!/usr/bin/env python3
import copy
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path


OLD_PATH = b"/data/data/com.winlator"
NEW_PATH = b"/data/data/com.actofrod"
OLD_PROVIDER = b"com.winlator.FileProvider"
NEW_PROVIDER = b"com.actofrod.FileProvider"
OLD_ACTION = b"com.winlator.action.ENSURE_FOREGROUND"
NEW_ACTION = b"com.actofrod.action.ENSURE_FOREGROUND"
PATH_REPLACEMENTS = ((OLD_PATH, NEW_PATH, "archive data"),)
JAVA_REPLACEMENTS = (
    (OLD_PATH, NEW_PATH, "Java"),
    (OLD_PROVIDER, NEW_PROVIDER, "FileProvider"),
    (OLD_ACTION, NEW_ACTION, "foreground action"),
)
COUNTS = {
    "Java": 0,
    "C++": 0,
    "archive metadata": 0,
    "archive data": 0,
    "FileProvider": 0,
    "foreground action": 0,
}
OTHER_STRINGS = set()


def replace_bytes(data, replacements):
    for old, new, label in replacements:
        if len(old) != len(new):
            raise ValueError(f"Replacement is not equal-length: {old!r} -> {new!r}")
        count = data.count(old)
        if count:
            COUNTS[label] += count
            data = data.replace(old, new)
    return data


def replace_metadata(value):
    if OLD_PATH.decode() not in value:
        return value
    count = value.count(OLD_PATH.decode())
    COUNTS["archive metadata"] += count
    return value.replace(OLD_PATH.decode(), NEW_PATH.decode())


class ReplacingReader:
    def __init__(self, source, size):
        self.source = source
        self.remaining = size
        self.keep = max(len(old) for old, _, _ in PATH_REPLACEMENTS) - 1
        self.tail = b""
        self.ready = b""

    def read(self, size=-1):
        if size is None or size < 0:
            size = self.remaining + len(self.tail) + len(self.ready)
        while len(self.ready) < size and self.remaining:
            chunk = self.source.read(min(1024 * 1024, self.remaining))
            if not chunk:
                raise EOFError("Truncated archive member while rewriting")
            self.remaining -= len(chunk)
            data = self.tail + chunk
            if self.remaining:
                stable_length = max(0, len(data) - self.keep)
                stable, self.tail = data[:stable_length], data[stable_length:]
            else:
                stable, self.tail = data, b""
            self.ready += replace_bytes(stable, PATH_REPLACEMENTS)
        if not self.remaining and self.tail:
            self.ready += replace_bytes(self.tail, PATH_REPLACEMENTS)
            self.tail = b""
        result = self.ready[:size]
        self.ready = self.ready[len(result):]
        return result


def rewrite_archive(source_path, output_path):
    decompressor = subprocess.Popen(
        ["zstd", "-dc", str(source_path)],
        stdout=subprocess.PIPE,
    )
    with output_path.open("wb") as output_file:
        compressor = subprocess.Popen(
            ["zstd", "-T0", "-12", "-c"],
            stdin=subprocess.PIPE,
            stdout=output_file,
        )
        try:
            with tarfile.open(fileobj=decompressor.stdout, mode="r|") as source_tar:
                with tarfile.open(
                    fileobj=compressor.stdin,
                    mode="w|",
                    format=tarfile.PAX_FORMAT,
                ) as output_tar:
                    for member in source_tar:
                        rewritten = copy.copy(member)
                        rewritten.pax_headers = dict(member.pax_headers)
                        name = replace_metadata(rewritten.name)
                        linkname = replace_metadata(rewritten.linkname)
                        rewritten.name = name
                        rewritten.linkname = linkname
                        for key in ("path", "linkpath"):
                            if key in rewritten.pax_headers:
                                rewritten.pax_headers[key] = replace_metadata(
                                    rewritten.pax_headers[key],
                                )
                        data = source_tar.extractfile(member) if member.isfile() else None
                        if data is not None and member.size:
                            data = ReplacingReader(data, member.size)
                        else:
                            data = None
                        output_tar.addfile(rewritten, data)
            if compressor.stdin and not compressor.stdin.closed:
                compressor.stdin.close()
            if decompressor.stdout:
                decompressor.stdout.close()
            compressor_status = compressor.wait()
            decompressor_status = decompressor.wait()
            if compressor_status or decompressor_status:
                raise RuntimeError(
                    f"zstd failed while rewriting {source_path} "
                    f"(decompress={decompressor_status}, compress={compressor_status})",
                )
        except BaseException:
            compressor.kill()
            decompressor.kill()
            compressor.wait()
            decompressor.wait()
            raise


def copy_and_rewrite_text_tree(source, destination, suffixes, replacements, label):
    shutil.copytree(source, destination, symlinks=True)
    for path in destination.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        if suffixes is not None and path.suffix.lower() not in suffixes:
            continue
        raw = path.read_bytes()
        if label == "C++" and b"\0" in raw:
            continue
        path.write_bytes(replace_bytes(raw, replacements))


def scan_stream_for_old_path(stream, location):
    overlap = b""
    keep = len(OLD_PATH) - 1
    while True:
        chunk = stream.read(1024 * 1024)
        if not chunk:
            return
        data = overlap + chunk
        if OLD_PATH in data:
            raise RuntimeError(f"Unrewritten runtime path remains in {location}")
        overlap = data[-keep:]


def note_other_string_literals(stream, member_name):
    overlap = b""
    keep = 512
    needle = b"com.winlator"
    while True:
        chunk = stream.read(1024 * 1024)
        if not chunk:
            return
        data = overlap + chunk
        cursor = 0
        while True:
            index = data.find(needle, cursor)
            if index < 0:
                break
            start = max(0, index - 120)
            end = min(len(data), index + len(needle) + 120)
            context = data[start:end]
            if (b'"' in context or b"'" in context) and not re.search(
                rb"\b(?:package|import)\s+com\.winlator(?:\.|\s*;)",
                context,
            ):
                OTHER_STRINGS.add(f"{member_name}: {context!r}")
            cursor = index + len(needle)
        overlap = data[-keep:]


def inspect_archive(path):
    process = subprocess.Popen(["zstd", "-dc", str(path)], stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
            for member in archive:
                values = [member.name, member.linkname]
                values.extend(member.pax_headers.values())
                if any(OLD_PATH.decode() in value for value in values if isinstance(value, str)):
                    raise RuntimeError(f"Unrewritten runtime path in archive metadata: {path}:{member.name}")
                if member.isfile():
                    data = archive.extractfile(member)
                    if data is not None:
                        overlap = b""
                        keep = max(len(OLD_PATH), 512) - 1
                        while True:
                            chunk = data.read(1024 * 1024)
                            if not chunk:
                                break
                            combined = overlap + chunk
                            if OLD_PATH in combined:
                                raise RuntimeError(
                                    f"Unrewritten runtime path remains in {path}:{member.name}",
                                )
                            note_other_string_literals(
                                io.BytesIO(combined),
                                f"{path.name}:{member.name}",
                            )
                            overlap = combined[-keep:]
        if process.stdout:
            process.stdout.close()
        status = process.wait()
        if status:
            raise RuntimeError(f"zstd failed while inspecting {path} (status {status})")
    except BaseException:
        process.kill()
        process.wait()
        raise


def check_output(output):
    for path in output.rglob("*"):
        if path.is_symlink():
            if OLD_PATH in os.readlink(path).encode():
                raise RuntimeError(f"Unrewritten runtime path remains in symlink {path}")
            continue
        if not path.is_file():
            continue
        if path.suffix == ".tzst":
            inspect_archive(path)
        else:
            with path.open("rb") as stream:
                scan_stream_for_old_path(stream, str(path))


def main():
    script_dir = Path(__file__).resolve().parent
    android_dir = script_dir.parent
    submodule = android_dir / "third_party" / "winlator-app"
    upstream = submodule / "app" / "src" / "main"
    patches_dir = android_dir / "patches" / "winlator"
    output = android_dir / "app" / "build" / "winlator"

    for old, new, _ in JAVA_REPLACEMENTS:
        if len(old) != len(new):
            raise ValueError(f"Replacement is not equal-length: {old!r} -> {new!r}")
    if shutil.which("zstd") is None:
        raise RuntimeError("zstd CLI is required to prepare Winlator assets")

    revision = subprocess.run(
        ["git", "-C", str(submodule), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()

    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    copy_and_rewrite_text_tree(
        upstream / "java",
        output / "java",
        {".java"},
        JAVA_REPLACEMENTS,
        "Java",
    )
    copy_and_rewrite_text_tree(
        upstream / "cpp",
        output / "cpp",
        None,
        ((OLD_PATH, NEW_PATH, "C++"),),
        "C++",
    )
    for name in ("res", "assets", "jniLibs"):
        shutil.copytree(upstream / name, output / name, symlinks=True)

    patch_files = sorted(patches_dir.glob("*.patch"))
    patch_digest = hashlib.sha256()
    for patch_file in patch_files:
        patch_digest.update(patch_file.read_bytes())
        subprocess.run(
            ["patch", "-p1", "--forward", "--batch", "-d", str(output)],
            input=patch_file.read_bytes(),
            check=True,
        )

    for archive in sorted((output / "assets").rglob("*.tzst")):
        rewrite_archive(archive, archive.with_suffix(archive.suffix + ".rewritten"))
        archive.with_suffix(archive.suffix + ".rewritten").replace(archive)

    check_output(output)
    path_count = COUNTS["Java"] + COUNTS["C++"] + COUNTS["archive metadata"] + COUNTS["archive data"]
    for label, count in (
        ("runtime data path", path_count),
        ("FileProvider authority", COUNTS["FileProvider"]),
        ("foreground service action", COUNTS["foreground action"]),
    ):
        if count < 1:
            raise RuntimeError(f"Expected replacement was not found: {label}")

    stamp = output / ".source-sha"
    stamp.write_text(f"{revision}+{patch_digest.hexdigest()}\n", encoding="utf-8")
    print(f"Winlator source revision: {revision}")
    print(f"  patches: {len(patch_files)} ({patch_digest.hexdigest()})")
    print(f"  {OLD_PATH.decode()} -> {NEW_PATH.decode()}: {path_count}")
    print(f"    Java: {COUNTS['Java']}")
    print(f"    C++: {COUNTS['C++']}")
    print(f"    archive member names/links: {COUNTS['archive metadata']}")
    print(f"    archive file contents: {COUNTS['archive data']}")
    print(f'  "com.winlator.FileProvider" -> "com.actofrod.FileProvider": {COUNTS["FileProvider"]}')
    print(
        '  "com.winlator.action.ENSURE_FOREGROUND" -> '
        f'"com.actofrod.action.ENSURE_FOREGROUND": {COUNTS["foreground action"]}',
    )
    if OTHER_STRINGS:
        print("Other remaining com.winlator string literals in tar contents:")
        for item in sorted(OTHER_STRINGS):
            print(f"  {item}")
    else:
        print("Other remaining com.winlator string literals in tar contents: none")
    print(f"Prepared sources at {output}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"prepare_winlator.py: {error}", file=sys.stderr)
        sys.exit(1)
