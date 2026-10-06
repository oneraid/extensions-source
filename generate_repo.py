import gzip
import json
import os
import shutil
import sys
from pathlib import Path

# Add scripts directory for protobuf index definition
sys.path.append(str(Path(__file__).resolve().parent / ".github" / "scripts"))
import index_pb2
from google.protobuf import json_format

GITHUB_USER = "oneraid"
GITHUB_REPO = "extensions"
GITHUB_BRANCH = "repo"
RAW_BASE_URL = f"https://github.com/{GITHUB_USER}/{GITHUB_REPO}/raw/{GITHUB_BRANCH}"
SIGNING_KEY_SHA256 = "7ff7b14449b366cc5e91810774790bb5f8f7932a1d63cf29f66ffc3f490699d7"

def find_icon(module_path: str, root: Path) -> Path | None:
    # 1. Check in module's res folder
    module_icon = root / "src" / module_path.replace(".", "/") / "res" / "mipmap-xhdpi" / "ic_launcher.png"
    if module_icon.exists():
        return module_icon
    # 2. Check default fallback icon in core
    core_icon = root / "core" / "src" / "main" / "res" / "mipmap-xhdpi" / "ic_launcher.png"
    if core_icon.exists():
        return core_icon
    return None

def main():
    root = Path(__file__).resolve().parent
    repo_dir = root / "repo"
    apk_dir = repo_dir / "apk"
    icon_dir = repo_dir / "icon"
    apk_dir.mkdir(parents=True, exist_ok=True)
    icon_dir.mkdir(parents=True, exist_ok=True)

    final_extensions = []

    # Find all source info json files generated during build
    for info_file in root.glob("src/**/build/keiyoushi-source-info.json"):
        with open(info_file, "r", encoding="utf-8") as f:
            info = json.load(f)

        module_dir = info_file.parent
        # Look for release apk first, then debug apk
        apk_candidates = list((module_dir / "outputs/apk/release").glob("*.apk"))
        if not apk_candidates:
            apk_candidates = list((module_dir / "outputs/apk/debug").glob("*.apk"))

        if not apk_candidates:
            print(f"Skipping {info['name']}: no APK found.")
            continue

        apk_file = apk_candidates[0]
        dest_apk = apk_dir / apk_file.name
        shutil.copy2(apk_file, dest_apk)
        print(f"[+] Added APK: {apk_file.name} ({dest_apk.stat().st_size} bytes)")

        # Copy icon
        pkg_name = info["packageName"]
        icon_path = find_icon(info.get("module", ""), root)
        icon_filename = f"{pkg_name}.png"
        dest_icon = icon_dir / icon_filename
        if icon_path and icon_path.exists():
            shutil.copy2(icon_path, dest_icon)
            print(f"[+] Added Icon: {icon_filename}")
        else:
            dest_icon.touch()

        icon_url = f"{RAW_BASE_URL}/icon/{icon_filename}"
        apk_url = f"{RAW_BASE_URL}/apk/{apk_file.name}"

        proto_sources = [
            index_pb2.Source(
                id=int(source["id"]),
                name=source["name"],
                language=source["lang"],
                homeUrl=source["baseUrl"],
                mirrorUrls=source.get("mirrorUrls", []),
            )
            for source in info.get("sources", [])
        ]

        ext = index_pb2.Extension(
            name=info["name"],
            packageName=pkg_name,
            resources=index_pb2.Resources(
                apkUrl=apk_url,
                iconUrl=icon_url,
            ),
            extensionLib=info.get("extensionLib", "1.6"),
            versionCode=int(info.get("versionCode", 1)),
            versionName=info.get("versionName", "1.0.0"),
            contentWarning=info.get("contentWarning", 0),
            sources=proto_sources,
        )
        final_extensions.append(ext)

    proto_index = index_pb2.Index(
        name="Oneraid Extensions",
        badgeLabel="ONE",
        signingKey=SIGNING_KEY_SHA256,
        contact=index_pb2.Contact(
            website=f"https://github.com/{GITHUB_USER}/{GITHUB_REPO}",
            discord=f"https://github.com/{GITHUB_USER}",
        ),
        extensionList=index_pb2.ExtensionList(extensions=final_extensions),
    )

    # 1. Write index.json (standard json)
    index_json_path = repo_dir / "index.json"
    with open(index_json_path, "w", encoding="utf-8") as f:
        f.write(
            json_format.MessageToJson(
                proto_index,
                always_print_fields_with_no_presence=False,
                preserving_proto_field_name=False,
            )
        )

    # 2. Write index.min.json (compact minified json)
    index_min_path = repo_dir / "index.min.json"
    with open(index_min_path, "w", encoding="utf-8") as f:
        json.dump(
            json.loads(
                json_format.MessageToJson(
                    proto_index,
                    always_print_fields_with_no_presence=False,
                    preserving_proto_field_name=False,
                )
            ),
            f,
            separators=(",", ":"),
        )

    # 3. Write index.pb (GZIP compressed protobuf binary, exactly as Mihon expects)
    index_pb_path = repo_dir / "index.pb"
    with open(index_pb_path, "wb") as f:
        f.write(gzip.compress(proto_index.SerializeToString(deterministic=True), mtime=0))

    print(f"\n[OK] Repository generated successfully at: {repo_dir}")
    print(f"[OK] Total extensions: {len(final_extensions)}")
    print(f"[OK] Generated index.json     ({index_json_path.stat().st_size} bytes)")
    print(f"[OK] Generated index.min.json ({index_min_path.stat().st_size} bytes)")
    print(f"[OK] Generated index.pb (GZIP)({index_pb_path.stat().st_size} bytes)")

if __name__ == "__main__":
    main()
