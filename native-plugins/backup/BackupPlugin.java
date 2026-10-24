package com.tototools.catalogo.plugins;

import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.JSArray;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@CapacitorPlugin(name = "CatalogoBackup")
public class BackupPlugin extends Plugin {

    private static final String TAG = "CatalogoBackup";

    @PluginMethod
    public void crear(PluginCall call) {
        try {
            String datosJson = call.getString("datosJson");
            if (datosJson == null || datosJson.isEmpty()) {
                call.reject("Falta datosJson");
                return;
            }
            String nombre = call.getString("nombre", "catalogo-copia.zip");
            String calidad = call.getString("calidad", "mini");

            JSArray arr = call.getArray("fotoIds");
            java.util.List<String> fotoIds = new java.util.ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.getString(i);
                    if (id != null && !id.isEmpty()) fotoIds.add(id);
                }
            }

            File filesDir = getContext().getFilesDir();
            File fotosDir = new File(filesDir, "fotos");
            File outFile = new File(getContext().getCacheDir(), nombre);
            if (outFile.exists()) outFile.delete();

            int fotosOk = 0;
            int fotosFail = 0;
            long fotosBytes = 0;

            ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outFile)));
            try {
                // datos.json
                zos.putNextEntry(new ZipEntry("datos.json"));
                byte[] jsonBytes = datosJson.getBytes("UTF-8");
                zos.write(jsonBytes);
                zos.closeEntry();

                for (String id : fotoIds) {
                    try {
                        File gFile = new File(fotosDir, id + ".g");
                        File tFile = new File(fotosDir, id + ".t");
                        File gAlt = new File(fotosDir, id + "-g.bin");
                        File tAlt = new File(fotosDir, id + "-t.bin");

                        File g = null;
                        File t = null;
                        if (gFile.exists() && gFile.length() > 0) g = gFile;
                        else if (gAlt.exists() && gAlt.length() > 0) g = gAlt;
                        if (tFile.exists() && tFile.length() > 0) t = tFile;
                        else if (tAlt.exists() && tAlt.length() > 0) t = tAlt;

                        if (g == null && t == null) {
                            fotosFail++;
                            continue;
                        }

                        if ("completa".equals(calidad)) {
                            if (g != null) {
                                putFile(zos, "fotos/" + id + "-g.bin", g);
                                fotosBytes += g.length();
                            }
                            if (t != null && t != g) {
                                putFile(zos, "fotos/" + id + "-t.bin", t);
                                fotosBytes += t.length();
                            }
                        } else {
                            File src = (t != null) ? t : g;
                            putFile(zos, "fotos/" + id + "-g.bin", src);
                            fotosBytes += src.length();
                        }
                        fotosOk++;
                    } catch (Exception e) {
                        fotosFail++;
                        Log.w(TAG, "foto " + id + ": " + e.getMessage());
                    }
                }
            } finally {
                zos.close();
            }

            if (!fotoIds.isEmpty() && fotosOk == 0) {
                outFile.delete();
                call.reject(
                    "No se pudo leer ninguna foto del teléfono (" + fotoIds.size()
                        + " referenciadas). Comprueba que se vean en el catálogo."
                );
                return;
            }

            JSObject ret = new JSObject();
            ret.put("uri", "file://" + outFile.getAbsolutePath());
            ret.put("path", outFile.getAbsolutePath());
            ret.put("size", outFile.length());
            ret.put("fotosOk", fotosOk);
            ret.put("fotosFail", fotosFail);
            ret.put("fotosBytes", fotosBytes);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "crear backup", e);
            call.reject(e.getMessage() != null ? e.getMessage() : "Error al crear la copia");
        }
    }

    @PluginMethod
    public void contarFotos(PluginCall call) {
        try {
            JSArray arr = call.getArray("fotoIds");
            java.util.List<String> fotoIds = new java.util.ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.getString(i);
                    if (id != null) fotoIds.add(id);
                }
            }
            File fotosDir = new File(getContext().getFilesDir(), "fotos");
            int ok = 0;
            for (String id : fotoIds) {
                File g = new File(fotosDir, id + ".g");
                File t = new File(fotosDir, id + ".t");
                if ((g.exists() && g.length() > 0) || (t.exists() && t.length() > 0)) ok++;
            }
            JSObject ret = new JSObject();
            ret.put("enDisco", ok);
            ret.put("pedidas", fotoIds.size());
            ret.put("dir", fotosDir.getAbsolutePath());
            ret.put("dirExiste", fotosDir.exists());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage() != null ? e.getMessage() : "Error");
        }
    }

    private void putFile(ZipOutputStream zos, String entryName, File file) throws Exception {
        zos.putNextEntry(new ZipEntry(entryName));
        BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = input.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
        } finally {
            input.close();
        }
        zos.closeEntry();
    }
}
