#!/usr/bin/env bash
# Ajustes de rendimiento del WebView y de la app Android
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
[ -d "$ANDROID" ] || exit 0

MANIFEST="$ANDROID/app/src/main/AndroidManifest.xml"
if [ -f "$MANIFEST" ]; then
  # largeHeap ayuda con muchas fotos en memoria
  if ! grep -q 'android:largeHeap' "$MANIFEST"; then
    sed -i 's/<application /<application android:largeHeap="true" android:hardwareAccelerated="true" /' "$MANIFEST" || true
  fi
  if ! grep -q 'usesCleartextTraffic' "$MANIFEST"; then
    sed -i 's/<application /<application android:usesCleartextTraffic="true" /' "$MANIFEST" || true
  fi
  echo "Manifest: largeHeap + hardwareAccelerated"
fi

MAIN=$(find "$ANDROID/app/src/main/java" -name "MainActivity.java" | head -1)
if [ -n "$MAIN" ]; then
  python3 - "$MAIN" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
t = p.read_text()
if "setOffscreenPreRaster" in t or "PERF_WEBVIEW" in t:
    print("WebView ya optimizado")
    raise SystemExit(0)

# Ensure imports
for imp in [
    "import android.webkit.WebSettings;",
    "import android.webkit.WebView;",
]:
    if imp not in t:
        t = t.replace("package ", imp + "\npackage ", 1) if False else t
        # after package line
        lines = t.splitlines()
        out = []
        done = False
        for line in lines:
            out.append(line)
            if not done and line.startswith("package "):
                out.append("import android.webkit.WebSettings;")
                out.append("import android.webkit.WebView;")
                done = True
        t = "\n".join(out) + "\n"

snippet = '''
    /* PERF_WEBVIEW: más fluido con listas largas y fotos */
    @Override
    public void onStart() {
        super.onStart();
        try {
            WebView web = this.bridge != null ? this.bridge.getWebView() : null;
            if (web != null) {
                WebSettings s = web.getSettings();
                s.setDomStorageEnabled(true);
                s.setCacheMode(WebSettings.LOAD_DEFAULT);
                s.setLoadWithOverviewMode(true);
                s.setUseWideViewPort(true);
                s.setMediaPlaybackRequiresUserGesture(false);
                try { s.setOffscreenPreRaster(true); } catch (Throwable ignored) {}
                web.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);
            }
        } catch (Throwable ignored) {}
    }
'''

if "onStart()" in t and "PERF_WEBVIEW" not in t:
    # insert before last closing of class - fragile; append before final }
    idx = t.rfind("}")
    t = t[:idx] + snippet + "\n}\n"
elif "onStart()" not in t:
    idx = t.rfind("}")
    t = t[:idx] + snippet + "\n}\n"

p.write_text(t)
print("MainActivity WebView perf OK")
print(p.read_text()[-800:])
PY
fi
echo "inject-perf: listo"
