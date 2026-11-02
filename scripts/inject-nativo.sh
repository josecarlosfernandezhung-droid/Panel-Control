#!/usr/bin/env bash
# Añade el plugin "Nativo" (restaurar copias, armar catálogo compartido) junto al de copias.
# Si algo falla aquí, el workflow compila igual sin él (el panel usa su camino de siempre).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
SRC="$ROOT/native-plugins/nativo"
JAVA_ROOT="$ANDROID/app/src/main/java"
MAIN=$(find "$JAVA_ROOT" -name "MainActivity.java" | head -1)
[ -n "$MAIN" ] || { echo "MainActivity.java no encontrado"; exit 1; }
REL=$(dirname "${MAIN#$JAVA_ROOT/}")
PKG=$(echo "$REL/plugins" | tr '/' '.')
DEST="$JAVA_ROOT/$REL/plugins"
mkdir -p "$DEST"
for f in Nucleo Imagen NativoPlugin; do
  sed "s/^package .*;/package $PKG;/" "$SRC/$f.java" > "$DEST/$f.java"
done
python3 - "$MAIN" "$PKG" <<'PY'
import sys, re
from pathlib import Path
p = Path(sys.argv[1]); pkg = sys.argv[2]; t = p.read_text()
if "NativoPlugin" in t:
    print("Ya registrado"); sys.exit(0)
imp = "import %s.NativoPlugin;" % pkg
t = re.sub(r"^(package [^;]+;)", r"\1\n" + imp, t, count=1, flags=re.M)
if "registerPlugin(BackupPlugin.class);" in t:
    t = t.replace("registerPlugin(BackupPlugin.class);", "registerPlugin(BackupPlugin.class);\n        registerPlugin(NativoPlugin.class);", 1)
elif "super.onCreate(savedInstanceState);" in t:
    t = t.replace("super.onCreate(savedInstanceState);", "registerPlugin(NativoPlugin.class);\n        super.onCreate(savedInstanceState);", 1)
else:
    t = re.sub(r"public class MainActivity extends BridgeActivity\s*\{\s*\}",
      "public class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(android.os.Bundle savedInstanceState) {\n        registerPlugin(NativoPlugin.class);\n        super.onCreate(savedInstanceState);\n    }\n}", t, count=1)
p.write_text(t)
print(t)
PY
echo "inject-nativo: listo"
