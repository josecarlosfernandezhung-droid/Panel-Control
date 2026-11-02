#!/usr/bin/env bash
# Copia el keystore del repo y configura signingConfigs.debug en Gradle
set -euo pipefail

if [ ! -f debug.keystore ]; then
  echo "ERROR: falta debug.keystore en la raíz"
  exit 1
fi

mkdir -p ~/.android android/app
cp -f debug.keystore ~/.android/debug.keystore
cp -f debug.keystore android/app/debug.keystore

GRADLE="android/app/build.gradle"
if [ ! -f "$GRADLE" ]; then
  echo "No hay build.gradle aún (se creará después de cap add)"
  exit 0
fi

if grep -q "signingConfigs" "$GRADLE"; then
  echo "signingConfigs ya existe"
  ls -la ~/.android/debug.keystore android/app/debug.keystore
  exit 0
fi

python3 -c '
from pathlib import Path
p = Path("android/app/build.gradle")
t = p.read_text()
block = """
    signingConfigs {
        debug {
            storeFile file(\"debug.keystore\")
            storePassword \"android\"
            keyAlias \"androiddebugkey\"
            keyPassword \"android\"
        }
    }
"""
if "buildTypes {" in t:
    t = t.replace("buildTypes {", block + "\n    buildTypes {", 1)
if "signingConfig signingConfigs.debug" not in t:
    t = t.replace("debug {", "debug {\n            signingConfig signingConfigs.debug", 1)
p.write_text(t)
print("signingConfigs inyectado")
'

ls -la ~/.android/debug.keystore android/app/debug.keystore
