#!/usr/bin/env bash
# Inyecta el plugin Kotlin CatalogoBackup en el proyecto Android de Capacitor.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
PLUGIN_SRC="$ROOT/native-plugins/backup/BackupPlugin.kt"

if [ ! -d "$ANDROID" ]; then
  echo "No existe android/ — ejecuta antes: npx cap add android"
  exit 1
fi

PKG_DIR="$ANDROID/app/src/main/java/com/tototools/catalogo/plugins"
mkdir -p "$PKG_DIR"
cp "$PLUGIN_SRC" "$PKG_DIR/BackupPlugin.kt"
echo "Plugin copiado a $PKG_DIR/BackupPlugin.kt"

# Registrar en MainActivity (Capacitor 6)
MAIN=$(find "$ANDROID/app/src/main/java" -name "MainActivity.java" -o -name "MainActivity.kt" | head -1)
if [ -z "$MAIN" ]; then
  echo "MainActivity no encontrado"
  exit 1
fi

if grep -q "CatalogoBackup\|BackupPlugin" "$MAIN"; then
  echo "Plugin ya registrado en MainActivity"
else
  if [[ "$MAIN" == *.java ]]; then
    # Java MainActivity
    if ! grep -q "import com.tototools.catalogo.plugins.BackupPlugin" "$MAIN"; then
      sed -i '/^package /a import com.tototools.catalogo.plugins.BackupPlugin;' "$MAIN"
    fi
    # Capacitor 5/6: registerPlugin en onCreate
    if grep -q "registerPlugin" "$MAIN"; then
      sed -i '/registerPlugin/a\        registerPlugin(BackupPlugin.class);' "$MAIN"
    elif grep -q "super.onCreate" "$MAIN"; then
      sed -i '/super.onCreate/a\        registerPlugin(BackupPlugin.class);' "$MAIN"
    else
      echo "WARN: no se pudo insertar registerPlugin automáticamente"
    fi
  else
    # Kotlin MainActivity
    if ! grep -q "import com.tototools.catalogo.plugins.BackupPlugin" "$MAIN"; then
      sed -i '/^package /a import com.tototools.catalogo.plugins.BackupPlugin' "$MAIN"
    fi
    if grep -q "registerPlugin" "$MAIN"; then
      sed -i '/registerPlugin/a\        registerPlugin(BackupPlugin::class.java)' "$MAIN"
    elif grep -q "super.onCreate" "$MAIN"; then
      sed -i '/super.onCreate/a\        registerPlugin(BackupPlugin::class.java)' "$MAIN"
    fi
  fi
  echo "Registro en MainActivity: $MAIN"
  cat "$MAIN"
fi

# Asegurar cleartext y permisos (por si el workflow no lo hizo)
MANIFEST="$ANDROID/app/src/main/AndroidManifest.xml"
if [ -f "$MANIFEST" ] && ! grep -q 'usesCleartextTraffic' "$MANIFEST"; then
  sed -i 's/<application/<application android:usesCleartextTraffic="true"/' "$MANIFEST" || true
fi

echo "inject-backup-plugin: listo"
