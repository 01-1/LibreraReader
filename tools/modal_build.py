from __future__ import annotations

import datetime
import hashlib
import json
import os
import secrets
import shutil
import subprocess
from pathlib import Path

import modal


ANDROID_HOME = "/opt/android-sdk"
SOURCE_DIR = Path("/source")
WORK_DIR = Path("/tmp/librera")

app = modal.App("librera-fork-build")
artifacts = modal.Volume.from_name("librera-build-artifacts", create_if_missing=True)
build_cache = modal.Volume.from_name("librera-build-cache", create_if_missing=True)
signing = modal.Volume.from_name("librera-build-signing", create_if_missing=True)

SIGNING_DIR = Path("/signing")
SIGNING_KEYSTORE = SIGNING_DIR / "librera-fork.keystore"
SIGNING_PASSWORD = SIGNING_DIR / "keystore-password"
SIGNING_ALIAS = "librera-fork"

image = (
    modal.Image.from_registry("eclipse-temurin:21-jdk-jammy", add_python="3.11")
    .apt_install(
        "build-essential",
        "curl",
        "git",
        "libgl-dev",
        "libglu1-mesa-dev",
        "libxcursor-dev",
        "libxi-dev",
        "libxinerama-dev",
        "libxrandr-dev",
        "mesa-common-dev",
        "ninja-build",
        "pkg-config",
        "unzip",
    )
    .env(
        {
            "ANDROID_HOME": ANDROID_HOME,
            "ANDROID_SDK_ROOT": ANDROID_HOME,
            "PATH": f"{ANDROID_HOME}/cmdline-tools/latest/bin:{ANDROID_HOME}/platform-tools:"
            "/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        }
    )
    .run_commands(
        "mkdir -p /opt/android-sdk/cmdline-tools "
        "&& curl -fsSL -o /tmp/commandlinetools.zip "
        "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip "
        "&& echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  "
        "/tmp/commandlinetools.zip' | sha256sum -c - "
        "&& unzip -q /tmp/commandlinetools.zip -d /opt/android-sdk/cmdline-tools "
        "&& mv /opt/android-sdk/cmdline-tools/cmdline-tools "
        "/opt/android-sdk/cmdline-tools/latest",
        "yes | sdkmanager --licenses >/dev/null || true",
        "sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0' "
        "'build-tools;36.0.0' "
        "'ndk;21.4.7075529'",
    )
    .add_local_dir(
        ".",
        remote_path=str(SOURCE_DIR),
        ignore=[
            ".git/**",
            ".gradle/**",
            "**/__pycache__/**",
            "**/build/**",
            "app/src/main/jniLibs/**",
            "artifacts/**",
        ],
    )
)


def run(
    command: list[str],
    *,
    cwd: Path = WORK_DIR,
    env: dict[str, str] | None = None,
    log_command: bool = True,
) -> None:
    if log_command:
        print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def write_gradle_properties(signing_password: str) -> None:
    properties = {
        "RELEASE_STORE_FILE": str(SIGNING_KEYSTORE),
        "RELEASE_STORE_PASSWORD": signing_password,
        "RELEASE_KEY_PASSWORD": signing_password,
        "RELEASE_KEY_ALIAS": SIGNING_ALIAS,
        "librera_appGdriveKey": "",
        "librera_admobAppId": "",
        "librera_admobBannerId": "",
        "librera_admobFullId": "",
        "librera_admobRewardId": "",
        "pro_appGdriveKey": "",
        "pdf_classic_appGdriveKey": "",
        "pdf_classic_admobAppId": "",
        "pdf_classic_admobBannerId": "",
        "pdf_classic_admobFullId": "",
        "pdf_classic_admobRewardId": "",
        "ebooka_appGdriveKey": "",
        "ebooka_admobAppId": "",
        "ebooka_admobBannerId": "",
        "ebooka_admobFullId": "",
        "ebooka_admobRewardId": "",
        "pdf_v2_appGdriveKey": "",
        "pdf_v2_admobAppId": "",
        "pdf_v2_admobBannerId": "",
        "pdf_v2_admobFullId": "",
        "pdf_v2_admobRewardId": "",
        "tts_reader_appGdriveKey": "",
        "tts_reader_admobAppId": "",
        "tts_reader_admobBannerId": "",
        "tts_reader_admobFullId": "",
        "tts_reader_admobRewardId": "",
        "epub_reader_appGdriveKey": "",
        "epub_reader_admobAppId": "",
        "epub_reader_admobBannerId": "",
        "epub_reader_admobFullId": "",
        "epub_reader_admobRewardId": "",
    }
    destination = WORK_DIR / "gradle.properties"
    with destination.open("a", encoding="utf-8") as output:
        output.write("\n# Ephemeral Modal build properties\n")
        for key, value in properties.items():
            output.write(f"{key}={value}\n")


def ensure_signing_keystore(env: dict[str, str]) -> str:
    has_keystore = SIGNING_KEYSTORE.is_file()
    has_password = SIGNING_PASSWORD.is_file()
    if has_keystore != has_password:
        raise RuntimeError("Persistent signing volume is incomplete")
    if has_keystore:
        return SIGNING_PASSWORD.read_text(encoding="utf-8").strip()

    signing_password = secrets.token_urlsafe(32)
    SIGNING_DIR.mkdir(parents=True, exist_ok=True)
    SIGNING_PASSWORD.write_text(signing_password + "\n", encoding="utf-8")
    SIGNING_PASSWORD.chmod(0o600)
    run(
        [
            "keytool",
            "-genkeypair",
            "-noprompt",
            "-keystore",
            str(SIGNING_KEYSTORE),
            "-storepass",
            signing_password,
            "-keypass",
            signing_password,
            "-alias",
            SIGNING_ALIAS,
            "-keyalg",
            "RSA",
            "-keysize",
            "3072",
            "-validity",
            "10000",
            "-dname",
            "CN=Librera Fork,OU=Release,O=Librera Fork,L=Local,ST=None,C=XX",
        ],
        env=env,
        log_command=False,
    )
    SIGNING_KEYSTORE.chmod(0o600)
    signing.commit()
    return signing_password


def apk_signer_sha256(apk_path: Path, env: dict[str, str]) -> str:
    result = subprocess.run(
        [
            f"{ANDROID_HOME}/build-tools/36.0.0/apksigner",
            "verify",
            "--print-certs",
            str(apk_path),
        ],
        cwd=WORK_DIR,
        env=env,
        check=True,
        capture_output=True,
        text=True,
    )
    prefix = "Signer #1 certificate SHA-256 digest:"
    for line in result.stdout.splitlines():
        if line.startswith(prefix):
            return line[len(prefix):].strip()
    raise RuntimeError(f"Signer SHA-256 digest missing for {apk_path.name}")


def write_google_services_stub() -> None:
    # The upstream Google Services plugin is applied to every flavor even though
    # F-Droid does not use Firebase. Give that build-only task a non-secret
    # placeholder configuration so the documented F-Droid build can proceed.
    configuration = {
        "project_info": {
            "project_number": "000000000000",
            "project_id": "librera-fork-local-build",
            "storage_bucket": "librera-fork-local-build.invalid",
        },
        "client": [
            {
                "client_info": {
                    "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
                    "android_client_info": {
                        "package_name": "com.foobnix.pro.pdf.reader",
                    },
                },
                "oauth_client": [],
                "api_key": [
                    {
                        "current_key": "not-a-real-api-key",
                    }
                ],
                "services": {
                    "appinvite_service": {
                        "other_platform_oauth_client": [],
                    }
                },
            }
        ],
        "configuration_version": "1",
    }
    destination = WORK_DIR / "app/google-services.json"
    destination.write_text(json.dumps(configuration, indent=2) + "\n", encoding="utf-8")


def build_native_libraries(env: dict[str, str]) -> int:
    android_parent = Path("/home/dev/Android")
    android_parent.mkdir(parents=True, exist_ok=True)
    sdk_link = android_parent / "Sdk"
    if not sdk_link.exists():
        sdk_link.symlink_to(ANDROID_HOME)

    script = WORK_DIR / "Builder/link_to_mupdf_1.23.7.sh"
    content = script.read_text(encoding="utf-8")
    content = content.replace(
        "git clone --recursive git://git.ghostscript.com/mupdf.git --branch $VERSION_TAG $MUPDF_FOLDER",
        "git clone --depth 1 --recursive https://github.com/ArtifexSoftware/mupdf.git "
        "--branch $VERSION_TAG $MUPDF_FOLDER",
    )
    script.write_text(content, encoding="utf-8")
    run(["bash", str(script), "fdroid"], env=env)

    native_libraries = list((WORK_DIR / "app/src/main/jniLibs").glob("*/*.so"))
    if not native_libraries:
        raise RuntimeError("MuPDF build completed without producing Android shared libraries")
    return len(native_libraries)


@app.function(
    image=image,
    cpu=8.0,
    memory=16384,
    timeout=7200,
    volumes={
        "/artifacts": artifacts,
        "/cache": build_cache,
        "/signing": signing,
    },
)
def build() -> dict[str, object]:
    shutil.copytree(SOURCE_DIR, WORK_DIR, symlinks=True)

    env = os.environ.copy()
    env["GRADLE_USER_HOME"] = "/cache/gradle"
    env["ANDROID_HOME"] = ANDROID_HOME
    env["ANDROID_SDK_ROOT"] = ANDROID_HOME

    signing_password = ensure_signing_keystore(env)
    write_gradle_properties(signing_password)
    write_google_services_stub()

    run(
        [
            "./gradlew",
            ":app:testFdroidDebugUnitTest",
            "--tests",
            "com.foobnix.pdf.info.*HelperTest",
            "--no-daemon",
            "--stacktrace",
        ],
        env=env,
    )
    native_library_count = build_native_libraries(env)
    run(["./gradlew", ":app:assembleFdroidRelease", "--no-daemon", "--stacktrace"], env=env)

    apk_paths = sorted((WORK_DIR / "app/build/outputs/apk/fdroid/release").glob("*.apk"))
    if not apk_paths:
        raise RuntimeError("Gradle completed without producing F-Droid release APKs")
    signer_digests = {apk_signer_sha256(path, env) for path in apk_paths}
    if len(signer_digests) != 1:
        raise RuntimeError("APK outputs do not share one signing certificate")

    run_id = datetime.datetime.now(datetime.UTC).strftime("%Y%m%dT%H%M%SZ")
    artifact_dir = Path("/artifacts") / run_id
    artifact_dir.mkdir(parents=True, exist_ok=False)

    checksums: list[str] = []
    for apk_path in apk_paths:
        destination = artifact_dir / apk_path.name
        shutil.copy2(apk_path, destination)
        digest = hashlib.sha256(destination.read_bytes()).hexdigest()
        checksums.append(f"{digest}  {destination.name}")

    (artifact_dir / "SHA256SUMS").write_text("\n".join(checksums) + "\n", encoding="utf-8")
    result = {
        "artifact_dir": str(artifact_dir),
        "apks": [path.name for path in apk_paths],
        "native_library_count": native_library_count,
        "signer_sha256": signer_digests.pop(),
        "sha256": checksums,
    }
    (artifact_dir / "build-result.json").write_text(
        json.dumps(result, indent=2) + "\n",
        encoding="utf-8",
    )
    artifacts.commit()
    build_cache.commit()
    return result


@app.local_entrypoint()
def main() -> None:
    print(json.dumps(build.remote(), indent=2))
