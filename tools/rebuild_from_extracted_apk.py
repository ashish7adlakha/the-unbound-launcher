#!/usr/bin/env python3
"""Rebuild The Unbound Launcher from the released 0.1.0 alpha APK.

Copies every payload of the released APK unchanged (same entries, same
STORED/DEFLATED choice) and replaces only classes4.dex (port-client) and
classes5.dex (settings-ui + embedded-adb), mirroring tools/build_embedded_adb.py.
Then zipaligns with 16KB page alignment and signs with a local debug key.
"""
import argparse
import copy
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from app_version import load_version, write_java_version  # noqa: E402

DEFAULT_BASE = ROOT / "the-unbound-launcher-0.1.2-alpha.apk"
PROBE_AUTHORITY = "dev.beampro.portprobe.bridge"
BASE_APP_VERSION = {"version_name": "2.2.2", "version_code": 2873}
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC", "MANIFEST.MF")


def find_sdk():
    local_portable = ROOT / "tools/android-sdk"
    if local_portable.exists():
        return local_portable
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if var in os.environ and Path(os.environ[var]).exists():
            return Path(os.environ[var])
    if os.name == "nt":
        local_app = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) / "Android/Sdk"
        if local_app.exists():
            return local_app
    return Path.home() / "Library/Android/sdk"


def find_tools(sdk):
    build_tools = sdk / "build-tools"
    if build_tools.exists():
        # zipalign -P (16KB pages) needs build-tools 35+; folders may be named by SDK level.
        def revision(path):
            props = path / "source.properties"
            for line in props.read_text().splitlines() if props.exists() else []:
                if line.startswith("Pkg.Revision="):
                    return tuple(int(p) for p in line.split("=")[1].split(".") if p.isdigit())
            return (0,)
        versions = sorted([v for v in build_tools.iterdir() if v.is_dir()], key=revision, reverse=True)
        if versions:
            return versions[0]
    return sdk / "build-tools/34.0.0"


def find_platform(sdk):
    for p in sorted((sdk / "platforms").glob("android-*"), reverse=True):
        if (p / "android.jar").exists():
            return p / "android.jar"
    return sdk / "platforms/android-34/android.jar"


def find_java():
    local_portable = ROOT / "tools/jdk-17"
    if (local_portable / "bin").exists():
        return local_portable
    if "JAVA_HOME" in os.environ and Path(os.environ["JAVA_HOME"]).exists():
        return Path(os.environ["JAVA_HOME"])
    which_java = shutil.which("java")
    if which_java:
        return Path(which_java).resolve().parents[1]
    return Path("C:/Program Files/Eclipse Adoptium/jdk-17")


def base_authority(base_apk):
    """The bridge provider authority declared by the base APK's manifest."""
    import re
    with zipfile.ZipFile(base_apk) as base:
        text = base.read("AndroidManifest.xml").decode("utf-16-le", "ignore")
    found = set(re.findall(r"[\w.]+\.unbound\.bridge", text))
    if len(found) != 1:
        raise SystemExit(f"ERROR: expected one bridge authority in the manifest, found {sorted(found)}")
    return found.pop()


def exe(base, name):
    if os.name == "nt":
        for ext in (".bat", ".exe", ".cmd", ""):
            cand = base / (name + ext)
            if cand.exists():
                return cand
    return base / name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=DEFAULT_BASE,
                        help="Released APK to take every other payload from")
    parser.add_argument("--output", type=Path, default=ROOT / "unbound-launcher-0.1.0-xbx.apk",
                        help="Output signed APK path")
    args = parser.parse_args()

    base_apk = args.base.resolve()
    if not base_apk.exists():
        print(f"ERROR: base APK not found at: {base_apk}")
        sys.exit(1)

    java_dir = find_java()
    sdk_dir = find_sdk()
    tools_dir = find_tools(sdk_dir)
    platform_jar = find_platform(sdk_dir)

    javac_bin = exe(java_dir / "bin", "javac")
    jar_bin = exe(java_dir / "bin", "jar")
    keytool_bin = exe(java_dir / "bin", "keytool")
    d8_bin = exe(tools_dir, "d8")
    zipalign_bin = exe(tools_dir, "zipalign")
    apksigner_bin = exe(tools_dir, "apksigner")

    print("=== The Unbound Launcher Rebuilder ===")
    print(f"Base APK:       {base_apk}")
    print(f"Java Home:      {java_dir}")
    print(f"Android SDK:    {sdk_dir}")
    print(f"Build Tools:    {tools_dir}")
    print(f"Platform:       {platform_jar}")
    print("=======================================")

    for name, path in [("javac", javac_bin), ("jar", jar_bin), ("d8", d8_bin),
                       ("zipalign", zipalign_bin), ("apksigner", apksigner_bin)]:
        if not path.exists() and not shutil.which(path.name):
            print(f"ERROR: Required tool '{name}' not found at {path}")
            sys.exit(1)
    if not platform_jar.exists():
        print(f"ERROR: android.jar not found at: {platform_jar}")
        sys.exit(1)

    build_dir = ROOT / "build_temp"
    if build_dir.exists():
        shutil.rmtree(build_dir)
    build_dir.mkdir(parents=True)

    env = dict(os.environ, JAVA_HOME=str(java_dir), PATH=f"{java_dir / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}")

    def run(*command):
        subprocess.run([str(arg) for arg in command], check=True, env=env)

    def compile_java(sources, name, libraries=()):
        classes = build_dir / f"{name}-classes"
        classes.mkdir()
        run(javac_bin, "-encoding", "UTF-8", "--release", "8", "-classpath",
            os.pathsep.join(str(p) for p in [platform_jar, *libraries]), "-d", classes, *sources)
        jar = build_dir / f"{name}.jar"
        run(jar_bin, "cf", jar, "-C", classes, ".")
        return jar

    def to_dex(jars, name):
        dest = build_dir / name
        dest.mkdir()
        run(d8_bin, "--min-api", "29", "--lib", platform_jar, "--output", dest, *jars)
        return dest / "classes.dex"

    # 1. port-client -> classes4.dex. The sources address the probe bridge
    # authority; the launcher ships its own (see build_embedded_adb.py).
    print("\n[1/5] Compiling port-client (dev.beampro.port)...")
    AUTHORITY = base_authority(base_apk)
    print(f"  -> bridge authority: {AUTHORITY}")
    client_src = build_dir / "client-src"
    client_src.mkdir()
    client_sources = []
    for source in sorted((ROOT / "port-client/src").rglob("*.java")):
        target = client_src / source.relative_to(ROOT / "port-client/src")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text(encoding="utf-8").replace(PROBE_AUTHORITY, AUTHORITY), encoding="utf-8")
        client_sources.append(target)
    client_dex = to_dex([compile_java(client_sources, "client")], "client-dex")

    # 2. settings-ui + embedded-adb -> classes5.dex
    print("\n[2/5] Compiling settings-ui (dev.beampro.settings) & embedded-adb...")
    vendor = ROOT / "embedded-adb/vendor"
    conscrypt_jar = build_dir / "conscrypt.jar"
    with zipfile.ZipFile(vendor / "deps/conscrypt-android-2.5.3.aar") as archive:
        conscrypt_jar.write_bytes(archive.read("classes.jar"))
    libraries = [vendor / "deps/annotation-jvm-1.9.1.jar",
                 vendor / "deps/bcprov-jdk15to18-1.81.jar",
                 conscrypt_jar]

    # The helper payload hash must match assets/unbound-shell.jar in the base APK.
    import hashlib
    with zipfile.ZipFile(base_apk) as base:
        helper_sha = hashlib.sha256(base.read("assets/unbound-shell.jar")).hexdigest()
    payload = build_dir / "generated/dev/beampro/adb/HelperPayload.java"
    payload.parent.mkdir(parents=True)
    payload.write_text('package dev.beampro.adb;\nfinal class HelperPayload { static final String SHA256 = "'
                       + helper_sha + '"; }\n')
    version_java = write_java_version(build_dir / "generated", load_version(), BASE_APP_VERSION)

    settings_sources = sorted((ROOT / "settings-ui/src").rglob("*.java")) + [version_java]
    settings_sources += sorted((ROOT / "embedded-adb/src").rglob("*.java")) + [payload]
    settings_sources += sorted((vendor / "libadb/java").rglob("*.java"))
    settings_sources += sorted((vendor / "spake2/java").rglob("*.java"))
    settings_jar = compile_java(settings_sources, "settings", libraries)
    settings_dex = to_dex([settings_jar, libraries[1], libraries[2]], "settings-dex")

    # 3. Copy the base APK, swapping only the two dex files.
    print(f"\n[3/5] Repackaging {base_apk.name} with new classes4.dex / classes5.dex...")
    replacements = {"classes4.dex": client_dex.read_bytes(), "classes5.dex": settings_dex.read_bytes()}
    unsigned_apk = build_dir / "unbound-unsigned.apk"
    with zipfile.ZipFile(base_apk) as source, zipfile.ZipFile(unsigned_apk, "w", allowZip64=True) as target:
        seen = set()
        for info in source.infolist():
            if info.filename.upper().startswith("META-INF/") and info.filename.upper().endswith(SIGNATURE_SUFFIXES):
                continue
            new_info = copy.copy(info)
            value = replacements.get(info.filename)
            if value is not None:
                seen.add(info.filename)
            else:
                value = source.read(info)
            target.writestr(new_info, value, compress_type=info.compress_type)
        missing = set(replacements) - seen
        if missing:
            raise SystemExit(f"ERROR: base APK lacks {sorted(missing)}; wrong base APK?")
    print(f"  -> {os.path.getsize(unsigned_apk) // (1024 * 1024)} MB unsigned APK.")

    # 4. 16KB page alignment (Snapdragon 8 Elite / Android 15)
    print("\n[4/5] Aligning APK (16KB pages)...")
    aligned_apk = build_dir / "unbound-aligned.apk"
    run(zipalign_bin, "-f", "-P", "16", "4", unsigned_apk, aligned_apk)

    # 5. Sign
    print("\n[5/5] Signing APK with apksigner...")
    keystore = ROOT / "unbound-debug.p12"
    if not keystore.exists():
        run(keytool_bin, "-genkeypair", "-noprompt", "-keystore", keystore, "-storetype", "PKCS12",
            "-storepass", "android", "-keypass", "android", "-alias", "unbound",
            "-dname", "CN=Unbound Custom Rebuild", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650")
    out_apk = args.output.resolve()
    run(apksigner_bin, "sign", "--ks", keystore, "--ks-key-alias", "unbound", "--ks-pass", "pass:android",
        "--out", out_apk, aligned_apk)
    run(apksigner_bin, "verify", "--verbose", out_apk)
    run(zipalign_bin, "-c", "-P", "16", "4", out_apk)
    print(f"\nSUCCESS! Created signed APK at:\n{out_apk} ({os.path.getsize(out_apk) // (1024 * 1024)} MB)")


if __name__ == "__main__":
    main()
