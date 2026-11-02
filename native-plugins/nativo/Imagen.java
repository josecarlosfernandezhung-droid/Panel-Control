package com.tototools.catalogo.plugins;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Reducir y comprimir fotos a WebP directo desde archivos (sin pasar por JavaScript). */
public class Imagen {

    public static class Medida { public int ancho, alto; }

    public static Medida medir(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        Medida m = new Medida(); m.ancho = o.outWidth; m.alto = o.outHeight;
        return m;
    }

    static int rotacion(File f) {
        try {
            int ori = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (ori == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (ori == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Throwable e) { }
        return 0;
    }

    /** Lee la foto, la deja con el lado mayor <= lado (sin agrandar) y la guarda en WebP con calidad 0-100. Devuelve el tamaño final. */
    public static long reducir(File origen, File destino, int lado, int calidad) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(origen.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("Imagen no válida");
        int mayor = Math.max(o.outWidth, o.outHeight), muestra = 1;
        while (mayor / (muestra * 2) >= lado) muestra *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = muestra;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(origen.getPath(), d);
        if (b == null) throw new IOException("No se pudo leer la imagen");
        int rot = rotacion(origen);
        float k = Math.min(1f, (float) lado / Math.max(b.getWidth(), b.getHeight()));
        if (k < 1f || rot != 0) {
            Matrix m = new Matrix();
            if (k < 1f) m.postScale(k, k);
            if (rot != 0) m.postRotate(rot);
            Bitmap n = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (n != b) { b.recycle(); b = n; }
        }
        if (b.hasAlpha()) { // fondo blanco, como en el panel
            Bitmap w = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(w); c.drawColor(Color.WHITE); c.drawBitmap(b, 0, 0, null);
            b.recycle(); b = w;
        }
        File tmp = new File(destino.getPath() + ".tmp");
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
            if (!b.compress(fmt, calidad, os)) throw new IOException("No se pudo comprimir");
        } finally { b.recycle(); }
        if (destino.exists()) destino.delete();
        if (!tmp.renameTo(destino)) throw new IOException("No se pudo guardar la imagen");
        return destino.length();
    }
}
