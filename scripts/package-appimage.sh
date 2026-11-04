#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
source_app="$project_dir/build/compose/binaries/main/app/EpsonReset"
output_dir="$project_dir/build/appimage"
desktop_file="$project_dir/installer/linux/epsonreset.desktop"
icon_file="$project_dir/src/main/composeResources/drawable/icon.png"
app_run="$project_dir/installer/linux/AppRun"

architecture="${1:-}"
raw_version="${2:-${APP_VERSION:-1.0.0}}"
version="${raw_version#v}"

tool_version="1.9.1"

case "$architecture" in
    x86_64)
        tool_sha256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
        ;;
    aarch64)
        tool_sha256="f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158"
        ;;
    *)
        echo "usage: $0 <x86_64|aarch64> [version]" >&2
        exit 2
        ;;
esac

if [[ ! "$version" =~ ^[0-9A-Za-z][0-9A-Za-z.+-]*$ ]]; then
    echo "Invalid AppImage version: $version" >&2
    exit 2
fi

for required in "$source_app/bin/EpsonReset" "$desktop_file" "$icon_file" "$app_run"; do
    if [[ ! -e "$required" ]]; then
        echo "Required AppImage input not found: $required" >&2
        echo "Run ./gradlew createDistributable on Linux first." >&2
        exit 1
    fi
done

for tool in curl sha256sum; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Missing required tool: $tool" >&2
        exit 1
    fi
done

mkdir -p "$output_dir"
work_dir="$(mktemp -d "$output_dir/.build.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT
app_dir="$work_dir/EpsonReset.AppDir"
tool_file="$work_dir/appimagetool-$architecture.AppImage"
output_file="$output_dir/EpsonReset-$version-$architecture.AppImage"

mkdir -p \
    "$app_dir/usr" \
    "$app_dir/usr/share/applications" \
    "$app_dir/usr/share/icons/hicolor/512x512/apps"
cp -R "$source_app/." "$app_dir/usr/"
install -m 755 "$app_run" "$app_dir/AppRun"
install -m 644 "$desktop_file" "$app_dir/usr/share/applications/epsonreset.desktop"
printf 'X-AppImage-Version=%s\nX-AppImage-Arch=%s\n' "$version" "$architecture" \
    >> "$app_dir/usr/share/applications/epsonreset.desktop"
install -m 644 "$icon_file" "$app_dir/usr/share/icons/hicolor/512x512/apps/epsonreset.png"
ln -s usr/share/applications/epsonreset.desktop "$app_dir/epsonreset.desktop"
ln -s usr/share/icons/hicolor/512x512/apps/epsonreset.png "$app_dir/epsonreset.png"
ln -s epsonreset.png "$app_dir/.DirIcon"

tool_url="https://github.com/AppImage/appimagetool/releases/download/$tool_version/appimagetool-$architecture.AppImage"
curl --fail --location --retry 3 --silent --show-error "$tool_url" --output "$tool_file"
printf '%s  %s\n' "$tool_sha256" "$tool_file" | sha256sum --check
chmod +x "$tool_file"

ARCH="$architecture" VERSION="$version" \
    "$tool_file" --appimage-extract-and-run "$app_dir" "$output_file"

if [[ ! -x "$output_file" ]]; then
    echo "appimagetool did not produce an executable: $output_file" >&2
    exit 1
fi

echo "AppImage written to $output_file"
