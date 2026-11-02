package com.tototools.catalogo.plugins;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

/** Puente entre el panel (JavaScript) y el trabajo pesado en Java: copias de seguridad y catálogo compartido. */
@CapacitorPlugin(name = "Nativo")
public class NativoPlugin extends Plugin {

    private File dirFotos() { return new File(getContext().getFilesDir(), "fotos"); }

    private File salida(String nombre) {
        String limpio = nombre == null ? "archivo.bin" : nombre.replaceAll("[^A-Za-z0-9._-]", "_");
        if (limpio.isEmpty() || limpio.startsWith(".")) limpio = "archivo.bin";
        return new File(getContext().getCacheDir(), limpio);
    }

    /** Borra copias y catálogos anteriores de la caché para no acumular archivos pesados. */
    private void limpiarCache() {
        File[] fs = getContext().getCacheDir().listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith("catalogo-") && (n.endsWith(".zip") || n.endsWith(".html") || n.endsWith(".tmp"))) f.delete();
        }
    }

    private void avance(String que, int hecho, int total) {
        JSObject o = new JSObject();
        o.put("que", que); o.put("hecho", hecho); o.put("total", total);
        notifyListeners("progreso", o);
    }

    private List<String> lista(JSArray a) throws Exception {
        List<String> r = new ArrayList<>();
        if (a == null) return r;
        for (int i = 0; i < a.length(); i++) r.add(a.getString(i));
        return r;
    }

    @PluginMethod
    public void ping(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ok", true);
        r.put("dir", dirFotos().getAbsolutePath());
        call.resolve(r);
    }

    @PluginMethod
    public void existen(PluginCall call) {
        try {
            List<String> falta = Nucleo.faltan(dirFotos(), lista(call.getArray("ids")));
            JSArray a = new JSArray();
            for (String s : falta) a.put(s);
            JSObject r = new JSObject();
            r.put("faltan", a);
            call.resolve(r);
        } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void crearZip(final PluginCall call) {
        new Thread(() -> {
            try {
                limpiarCache();
                File out = salida(call.getString("nombre"));
                String datos = call.getString("datos", "{}");
                boolean completa = !"mini".equals(call.getString("nivel"));
                List<String> ids = lista(call.getArray("ids"));
                long size = Nucleo.crearZip(dirFotos(), out, datos, ids, completa, (h, t) -> avance("zip", h, t));
                JSObject r = new JSObject();
                r.put("uri", Uri.fromFile(out).toString());
                r.put("size", size);
                call.resolve(r);
            } catch (Throwable e) { call.reject(String.valueOf(e.getMessage())); }
        }).start();
    }

    @PluginMethod
    public void armarHtml(final PluginCall call) {
        new Thread(() -> {
            try {
                limpiarCache();
                File out = salida(call.getString("nombre"));
                JSArray arr = call.getArray("segmentos");
                List<Nucleo.Seg> segs = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (o.has("f")) segs.add(new Nucleo.Seg(null, o.getString("f"), o.optString("s", "g")));
                    else segs.add(new Nucleo.Seg(o.optString("t", ""), null, null));
                }
                String entrada = call.getString("entrada");
                long size;
                if (entrada != null && !entrada.isEmpty()) {
                    File html = new File(getContext().getCacheDir(), "catalogo-tmp.html");
                    Nucleo.armarHtml(dirFotos(), html, segs, (h, t) -> avance("html", h, t));
                    avance("zip", 0, 1);
                    size = Nucleo.zipArchivo(html, out, entrada);
                    html.delete();
                } else {
                    size = Nucleo.armarHtml(dirFotos(), out, segs, (h, t) -> avance("html", h, t));
                }
                JSObject r = new JSObject();
                r.put("uri", Uri.fromFile(out).toString());
                r.put("size", size);
                call.resolve(r);
            } catch (Throwable e) { call.reject(String.valueOf(e.getMessage())); }
        }).start();
    }

    /** Abre el selector de archivos del sistema, descomprime la copia elegida y devuelve su datos.json. */
    @PluginMethod
    public void elegirZip(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(call, i, "zipElegido");
    }

    @ActivityCallback
    private void zipElegido(final PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            JSObject r = new JSObject();
            r.put("cancelado", true);
            call.resolve(r);
            return;
        }
        final Uri uri = result.getData().getData();
        getBridge().saveCall(call);
        new Thread(() -> {
            try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                int[] n = new int[1], mant = new int[1];
                String modo = call.getString("modo", "todo");
                boolean mejor = Boolean.TRUE.equals(call.getBoolean("conservarMejor", false));
                String datos = Nucleo.descomprimir(in, dirFotos(), n, (h, t) -> avance("restaurar", h, t), modo, mejor, mant);
                JSObject r = new JSObject();
                if (datos == null) r.put("invalido", true);
                else { r.put("datos", datos); r.put("fotos", n[0]); r.put("mantenidas", mant[0]); }
                call.resolve(r);
            } catch (Throwable e) { call.reject(String.valueOf(e.getMessage())); }
            finally { getBridge().releaseCall(call); }
        }).start();
    }

    static boolean esImagenDirecta(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[12]; int n = in.read(b);
            if (n < 12) return false;
            boolean jpg = (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8, png = (b[0] & 0xFF) == 0x89 && b[1] == 0x50, webp = b[0] == 'R' && b[1] == 'I' && b[8] == 'W';
            return jpg || png || webp;
        } catch (Exception e) { return false; }
    }

    /** Foto nueva: el panel dejó el archivo original en fotos/ID.o; aquí se crean ID.g (grande) e ID.t (miniatura) y se borra el original. */
    @PluginMethod
    public void crearFoto(final PluginCall call) {
        new Thread(() -> {
            try {
                String id = call.getString("id", "");
                if (!Nucleo.idValido(id)) throw new Exception("id no válido");
                File dir = dirFotos(), o = new File(dir, id + ".o"), g = new File(dir, id + ".g"), t = new File(dir, id + ".t");
                if (!o.isFile() || o.length() == 0) throw new Exception("No llegó la foto");
                boolean original = Boolean.TRUE.equals(call.getBoolean("original", false));
                int lg = call.getInt("g", 960), qg = call.getInt("gq", 72), lt = call.getInt("t", 360), qt = call.getInt("tq", 66);
                File fuente = o;
                if (original && esImagenDirecta(o)) { if (g.exists()) g.delete(); if (!o.renameTo(g)) throw new Exception("No se pudo guardar"); fuente = g; }
                else Imagen.reducir(o, g, lg, qg);
                Imagen.reducir(fuente, t, lt, qt);
                if (fuente == o) o.delete();
                JSObject r = new JSObject();
                r.put("g", g.length()); r.put("t", t.length());
                call.resolve(r);
            } catch (Throwable e) { call.reject(String.valueOf(e.getMessage())); }
        }).start();
    }

    /** Catálogo compartido: crea, en paralelo, una copia WebP liviana (ID.c/.d/.e/.f) de cada foto grande. */
    @PluginMethod
    public void optimizar(final PluginCall call) {
        new Thread(() -> {
            try {
                final JSArray arr = call.getArray("items");
                final int total = arr == null ? 0 : arr.length();
                final File dir = dirFotos();
                final AtomicInteger hechas = new AtomicInteger(), creadas = new AtomicInteger(), fallos = new AtomicInteger();
                int hilos = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
                ExecutorService ex = Executors.newFixedThreadPool(hilos);
                for (int i = 0; i < total; i++) {
                    final JSONObject it = arr.getJSONObject(i);
                    ex.execute(() -> {
                        try {
                            String id = it.getString("id"), z = it.getString("z");
                            if (Nucleo.idValido(id) && z.matches("[cdef]")) {
                                File dest = new File(dir, id + "." + z), src = new File(dir, id + ".g");
                                if (!src.isFile() || src.length() == 0) src = new File(dir, id + ".t");
                                if (src.isFile() && !(dest.isFile() && dest.length() > 0)) {
                                    int lado = it.optInt("lado", 1000), q = it.optInt("q", 78); long k = it.optLong("k", 140000);
                                    Imagen.Medida m = Imagen.medir(src);
                                    if (m.ancho > 0 && Math.max(m.ancho, m.alto) <= lado && src.length() <= k && esWebp(src)) { /* ya está liviana: se usa tal cual */ }
                                    else { Imagen.reducir(src, dest, lado, q); creadas.incrementAndGet(); }
                                }
                            }
                        } catch (Throwable e) { fallos.incrementAndGet(); }
                        int h = hechas.incrementAndGet();
                        if (h % 5 == 0 || h == total) avance("optimizar", h, total);
                    });
                }
                ex.shutdown();
                ex.awaitTermination(30, TimeUnit.MINUTES);
                JSObject r = new JSObject();
                r.put("hechas", hechas.get()); r.put("creadas", creadas.get()); r.put("fallos", fallos.get());
                call.resolve(r);
            } catch (Throwable e) { call.reject(String.valueOf(e.getMessage())); }
        }).start();
    }

    static boolean esWebp(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) { byte[] b = new byte[12]; return in.read(b) == 12 && b[0] == 'R' && b[1] == 'I' && b[8] == 'W'; }
        catch (Exception e) { return false; }
    }
}
