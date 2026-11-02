#!/usr/bin/env bash
# Deshace inject-nativo.sh (se usa si la compilación con el plugin falla).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_ROOT="$ROOT/android/app/src/main/java"
find "$JAVA_ROOT" \( -name "NativoPlugin.java" -o -name "Nucleo.java" -o -name "Imagen.java" \) -delete
MAIN=$(find "$JAVA_ROOT" -name "MainActivity.java" | head -1)
[ -n "$MAIN" ] && sed -i '/NativoPlugin/d' "$MAIN"
echo "plugin Nativo quitado"
