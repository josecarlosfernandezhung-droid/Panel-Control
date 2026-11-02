package com.tototools.catalogo.plugins;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Trabajo pesado sin dependencias de Android (se prueba solo con Java normal). */
public class Nucleo {
    public interface Avance { void paso(int hecho, int total); }

    static final Pattern ENTRADA = Pattern.compile("^fotos/([A-Za-z0-9_-]{1,80})-([gt])\\.bin$");
    static final Pattern ID_OK = Pattern.compile("^[A-Za-z0-9_-]{1,80}$");

    static boolean idValido(String id) { return id != null && ID_OK.matcher(id).matches(); }

    /** Copia de seguridad: zip sin comprimir (las fotos ya vienen comprimidas) con los mismos nombres que usa el panel. */
    public static long crearZip(File dirFotos, File salida, String datos, List<String> ids, boolean completa, Avance av) throws IOException {
        File tmp = new File(salida.getPath() + ".tmp");
        if (salida.getParentFile() != null) salida.getParentFile().mkdirs();
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            z.setLevel(Deflater.NO_COMPRESSION);
            z.putNextEntry(new ZipEntry("datos.json"));
            z.write(datos.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            byte[] buf = new byte[1 << 16];
            int n = ids.size();
            for (int i = 0; i < n; i++) {
                String id = ids.get(i);
                if (idValido(id)) {
                    File g = new File(dirFotos, id + ".g"), t = new File(dirFotos, id + ".t");
                    if (completa) {
                        if (g.isFile()) agregar(z, "fotos/" + id + "-g.bin", g, buf);
                        else if (t.isFile()) agregar(z, "fotos/" + id + "-g.bin", t, buf);
                        if (g.isFile() && t.isFile()) agregar(z, "fotos/" + id + "-t.bin", t, buf);
                    } else {
                        File f = t.isFile() ? t : g;
                        if (f.isFile()) agregar(z, "fotos/" + id + "-g.bin", f, buf);
                    }
                }
                if (av != null && (i % 10 == 0 || i == n - 1)) av.paso(i + 1, n);
            }
        }
        if (salida.exists()) salida.delete();
        if (!tmp.renameTo(salida)) throw new IOException("No se pudo guardar la copia");
        return salida.length();
    }

    static void agregar(ZipOutputStream z, String nombre, File f, byte[] buf) throws IOException {
        z.putNextEntry(new ZipEntry(nombre));
        try (InputStream in = new FileInputStream(f)) { int r; while ((r = in.read(buf)) > 0) z.write(buf, 0, r); }
        z.closeEntry();
    }

    /** Restaurar: deja las fotos como archivos y devuelve el texto de datos.json (o null si no es una copia). */
    public static String descomprimir(InputStream origen, File dirFotos, int[] cuenta, Avance av) throws IOException {
        return descomprimir(origen, dirFotos, cuenta, av, "todo", false, null);
    }

    /** modo: "todo" | "datos" (no toca fotos) | "fotos". conservarMejor: si ya hay una foto igual o más pesada, se queda. mant[0] cuenta las conservadas. */
    public static String descomprimir(InputStream origen, File dirFotos, int[] cuenta, Avance av, String modo, boolean conservarMejor, int[] mant) throws IOException {
        boolean sinFotos = "datos".equals(modo);
        dirFotos.mkdirs();
        String datos = null;
        Set<String> ids = new HashSet<>();
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(origen, 1 << 16))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String nombre = e.getName();
                if (e.isDirectory()) continue;
                if (nombre.equals("datos.json")) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    int r; while ((r = z.read(buf)) > 0) b.write(buf, 0, r);
                    datos = b.toString("UTF-8");
                    continue;
                }
                Matcher m = ENTRADA.matcher(nombre);
                if (sinFotos || !m.matches() || m.group(1).contains("..")) continue;
                File dest = new File(dirFotos, m.group(1) + "." + m.group(2)), tmp = new File(dirFotos, m.group(1) + "." + m.group(2) + ".tmp");
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) { int r; while ((r = z.read(buf)) > 0) o.write(buf, 0, r); }
                if (conservarMejor && dest.exists() && dest.length() >= tmp.length()) { tmp.delete(); if ("g".equals(m.group(2)) && mant != null && mant.length > 0) mant[0]++; ids.add(m.group(1)); continue; }
                if (dest.exists()) dest.delete();
                if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("No se pudo guardar una foto"); }
                if (ids.add(m.group(1)) && av != null && ids.size() % 10 == 0) av.paso(ids.size(), 0);
            }
        }
        if (cuenta != null && cuenta.length > 0) cuenta[0] = ids.size();
        return datos;
    }

    /** Segmento del catálogo: texto, o una foto (id + tamaño g/t) que se escribe como data:URI. */
    public static class Seg {
        public final String texto, foto, tam;
        public Seg(String texto, String foto, String tam) { this.texto = texto; this.foto = foto; this.tam = tam; }
    }

    static final String GIF_VACIO = "data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==";

    /** Arma el HTML del catálogo leyendo las fotos directo del disco y escribiéndolas en base64 sin pasar por memoria grande. */
    public static long armarHtml(File dirFotos, File salida, List<Seg> segs, Avance av) throws IOException {
        File tmp = new File(salida.getPath() + ".tmp");
        if (salida.getParentFile() != null) salida.getParentFile().mkdirs();
        int fotos = 0, hechas = 0;
        for (Seg s : segs) if (s.foto != null) fotos++;
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            byte[] buf = new byte[3 * 16384];
            for (Seg s : segs) {
                if (s.texto != null) { o.write(s.texto.getBytes(StandardCharsets.UTF_8)); continue; }
                File f = null;
                if (idValido(s.foto)) {
                    File c = new File(dirFotos, s.foto + "." + s.tam), pref = new File(dirFotos, s.foto + "." + ("t".equals(s.tam) ? "t" : "g")), otra = new File(dirFotos, s.foto + "." + ("t".equals(s.tam) ? "g" : "t"));
                    /* tamaños «c» (buena) y «d» (alta): versión ya optimizada para el catálogo compartido (si existe); si no, la foto grande */
                    if (s.tam != null && s.tam.matches("[cdef]") && c.isFile() && c.length() > 0) pref = c;
                    f = pref.isFile() && pref.length() > 0 ? pref : (otra.isFile() && otra.length() > 0 ? otra : null);
                }
                if (f == null) { o.write(GIF_VACIO.getBytes(StandardCharsets.UTF_8)); }
                else {
                    try (InputStream in = new FileInputStream(f)) {
                        int r = leer(in, buf);
                        o.write(("data:" + tipo(buf, r) + ";base64,").getBytes(StandardCharsets.UTF_8));
                        while (r > 0) { base64(o, buf, r); r = leer(in, buf); }
                    }
                }
                hechas++;
                if (av != null && (hechas % 10 == 0 || hechas == fotos)) av.paso(hechas, fotos);
            }
        }
        if (salida.exists()) salida.delete();
        if (!tmp.renameTo(salida)) throw new IOException("No se pudo guardar el archivo");
        return salida.length();
    }

    /** Lee hasta llenar el buffer (múltiplo de 3) o hasta el final, para que el base64 no meta relleno a mitad. */
    static int leer(InputStream in, byte[] buf) throws IOException {
        int total = 0, r;
        while (total < buf.length && (r = in.read(buf, total, buf.length - total)) > 0) total += r;
        return total;
    }

    static String tipo(byte[] b, int n) {
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (n >= 2 && (b[0] & 0xFF) == 0x89 && b[1] == 0x50) return "image/png";
        return "image/webp";
    }

    static final byte[] ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".getBytes(StandardCharsets.US_ASCII);

    /** Base64 de n bytes (el bloque completo es múltiplo de 3; solo el último puede llevar '='). */
    static void base64(OutputStream o, byte[] b, int n) throws IOException {
        byte[] out = new byte[((n + 2) / 3) * 4];
        int j = 0, i = 0;
        for (; i + 2 < n; i += 3) {
            int v = ((b[i] & 0xFF) << 16) | ((b[i + 1] & 0xFF) << 8) | (b[i + 2] & 0xFF);
            out[j++] = ABC[v >> 18 & 63]; out[j++] = ABC[v >> 12 & 63]; out[j++] = ABC[v >> 6 & 63]; out[j++] = ABC[v & 63];
        }
        if (n - i == 1) {
            int v = (b[i] & 0xFF) << 16;
            out[j++] = ABC[v >> 18 & 63]; out[j++] = ABC[v >> 12 & 63]; out[j++] = '='; out[j++] = '=';
        } else if (n - i == 2) {
            int v = ((b[i] & 0xFF) << 16) | ((b[i + 1] & 0xFF) << 8);
            out[j++] = ABC[v >> 18 & 63]; out[j++] = ABC[v >> 12 & 63]; out[j++] = ABC[v >> 6 & 63]; out[j++] = '=';
        }
        o.write(out, 0, j);
    }

    /** Comprime un archivo en un zip de una sola entrada (nivel bajo: rápido y suficiente). */
    public static long zipArchivo(File origen, File salida, String entrada) throws IOException {
        File tmp = new File(salida.getPath() + ".tmp");
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            z.setLevel(1);
            z.putNextEntry(new ZipEntry(entrada));
            byte[] buf = new byte[1 << 16];
            try (InputStream in = new FileInputStream(origen)) { int r; while ((r = in.read(buf)) > 0) z.write(buf, 0, r); }
            z.closeEntry();
        }
        if (salida.exists()) salida.delete();
        if (!tmp.renameTo(salida)) throw new IOException("No se pudo guardar el zip");
        return salida.length();
    }

    /** Ids de la lista cuyo archivo (grande o miniatura) no existe. */
    public static List<String> faltan(File dirFotos, List<String> ids) {
        List<String> f = new ArrayList<>();
        for (String id : ids) {
            if (!idValido(id) || !(new File(dirFotos, id + ".g").isFile() || new File(dirFotos, id + ".t").isFile())) f.add(id);
        }
        return f;
    }
}
