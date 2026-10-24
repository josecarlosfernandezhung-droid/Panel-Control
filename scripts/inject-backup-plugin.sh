#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
PLUGIN_SRC="$ROOT/native-plugins/backup/BackupPlugin.java"

if [ ! -d "$ANDROID" ]; then
  echo "No existe android/"
  exit 1
fi

MAIN=$(find "$ANDROID/app/src/main/java" \( -name "MainActivity.java" -o -name "MainActivity.kt" \) 2>/dev/null | head -1)
if [ -z "$MAIN" ]; then
  echo "MainActivity no encontrado"
  find "$ANDROID" -name "MainActivity*" 2>/dev/null || true
  exit 1
fi

# Paquete real de la app (carpeta del MainActivity)
MAIN_DIR=$(dirname "$MAIN")
PKG=$(echo "$MAIN_DIR" | sed 's|.*/java/||; s|/|.|g')
echo "MainActivity: $MAIN"
echo "Package: $PKG"
cat "$MAIN"
echo "----"

# Copiar plugin junto al MainActivity (mismo árbol de paquetes)
# Usamos com.tototools.catalogo.plugins como subpaquete
PLUGIN_DIR="$MAIN_DIR/plugins"
# Si MainActivity está en com/tototools/catalogo, plugins queda en .../catalogo/plugins
# Ajuste: crear .../plugins bajo el package root
PKG_ROOT=$(echo "$MAIN_DIR" | sed 's|/[^/]*$||')  # parent of last segment if activity is in leaf
# Mejor: package del app = path relativo a java/
JAVA_ROOT="$ANDROID/app/src/main/java"
REL=$(dirname "${MAIN#$JAVA_ROOT/}")
PLUGIN_DIR="$JAVA_ROOT/$REL/plugins"
mkdir -p "$PLUGIN_DIR"

# Ajustar package del plugin al real
PLUGIN_PKG=$(echo "$REL/plugins" | tr '/' '.')
sed "s/package com.tototools.catalogo.plugins;/package $PLUGIN_PKG;/" "$PLUGIN_SRC" > "$PLUGIN_DIR/BackupPlugin.java"
rm -f "$PLUGIN_DIR/BackupPlugin.kt"
echo "Plugin en $PLUGIN_DIR (package $PLUGIN_PKG)"

# Registrar en MainActivity
if grep -q "BackupPlugin" "$MAIN"; then
  echo "Ya registrado"
else
  if [[ "$MAIN" == *.java ]]; then
    # import
    if ! grep -q "BackupPlugin" "$MAIN"; then
      sed -i "/^package /a\\
import ${PLUGIN_PKG}.BackupPlugin;" "$MAIN"
    fi
    python3 - <<PY
from pathlib import Path
p = Path("$MAIN")
t = p.read_text()
imp = "import ${PLUGIN_PKG}.BackupPlugin;"
if imp not in t:
    lines = t.splitlines()
    out = []
    done = False
    for line in lines:
        out.append(line)
        if not done and line.startswith("package "):
            out.append(imp)
            done = True
    t = "\n".join(out) + ("\n" if t.endswith("\n") else "")

# Añadir onCreate si es BridgeActivity vacío
if "registerPlugin" not in t:
    if "extends BridgeActivity" in t and "onCreate" not in t:
        t = t.replace(
            "public class MainActivity extends BridgeActivity {}",
            """public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(BackupPlugin.class);
        super.onCreate(savedInstanceState);
    }
}"""
        )
        t = t.replace(
            "public class MainActivity extends BridgeActivity {\n}",
            """public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(BackupPlugin.class);
        super.onCreate(savedInstanceState);
    }
}"""
        )
    elif "super.onCreate" in t:
        t = t.replace(
            "super.onCreate(savedInstanceState);",
            "registerPlugin(BackupPlugin.class);\n        super.onCreate(savedInstanceState);",
            1
        )
p.write_text(t)
print("MainActivity actualizado")
print(p.read_text())
PY
  fi
fi

echo "inject-backup-plugin: listo"
