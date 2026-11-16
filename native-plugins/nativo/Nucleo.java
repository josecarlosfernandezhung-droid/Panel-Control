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

    /** Cómo abrir (otra vez) la copia elegida: se usa una segunda vez si hace falta leerla de otra forma. */
    public interface Abridor { InputStream abrir() throws IOException; }

    /** «datos.json» y «fotos/ID-g.bin» se reconocen aunque el zip tenga una carpeta delante (ej. «copia/datos.json»). */
    static String nombreLimpio(String n) {
        n = n.replace('\\', '/');
        if (n.equals("datos.json") || n.endsWith("/datos.json")) return "datos.json";
        int k = n.lastIndexOf("fotos/");
        return k >= 0 ? n.substring(k) : n;
    }

    /** Guarda una foto de la copia. Si llega vacía no se cuenta ni pisa la que ya hay. */
    /** Calidad al restaurar: "talcual" (por defecto) o "media". La fija el plugin antes de restaurar. */
    public static volatile String calRest = "talcual";

    static void guardarFoto(InputStream in, String id, String tipo, File dirFotos, boolean conservarMejor, Set<String> ids, int[] mant, byte[] buf, Avance av) throws IOException {
        File dest = new File(dirFotos, id + "." + tipo), tmp = new File(dirFotos, id + "." + tipo + ".tmp");
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) { int r; while ((r = in.read(buf)) > 0) o.write(buf, 0, r); }
        if (tmp.length() == 0) { tmp.delete(); return; }
        if (conservarMejor && dest.exists() && dest.length() >= tmp.length()) { tmp.delete(); if ("g".equals(tipo) && mant != null && mant.length > 0) mant[0]++; ids.add(id); return; }
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("No se pudo guardar una foto"); }
        /* La foto cambió: se borran las versiones livianas (.c .d .e .f) hechas de la anterior para que el catálogo las rehaga con la foto restaurada */
        for (String z : new String[]{"c", "d", "e", "f"}) { try { new File(dirFotos, id + "." + z).delete(); } catch (Throwable e) { } }
        /* Calidad elegida al restaurar: «media» baja la foto grande a ~1000 px (solo si así pesa menos) */
        if ("media".equals(calRest) && "g".equals(tipo)) {
            try {
                File rd = new File(dirFotos, id + ".g.red");
                Imagen.reducir(dest, rd, 1000, 78);
                if (rd.length() > 0 && rd.length() < dest.length()) { dest.delete(); rd.renameTo(dest); } else rd.delete();
            } catch (Throwable e) { }
        }
        if (ids.add(id) && av != null && ids.size() % 10 == 0) av.paso(ids.size(), 0);
    }

    /** Lectura en un solo paso (rápida). No sirve con entradas sin comprimir que guardan su tamaño al final. */
    static String leerFlujo(InputStream origen, File dirFotos, Set<String> ids, Avance av, boolean sinFotos, boolean conservarMejor, int[] mant, List<String> vistos) throws IOException {
        String datos = null;
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(origen, 1 << 16))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String nombre = nombreLimpio(e.getName());
                if (vistos != null && vistos.size() < 6) vistos.add(e.getName());
                if (nombre.equals("datos.json")) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    int r; while ((r = z.read(buf)) > 0) b.write(buf, 0, r);
                    datos = b.toString("UTF-8");
                    continue;
                }
                Matcher m = ENTRADA.matcher(nombre);
                if (sinFotos || !m.matches() || m.group(1).contains("..")) continue;
                guardarFoto(z, m.group(1), m.group(2), dirFotos, conservarMejor, ids, mant, buf, av);
            }
        }
        return datos;
    }

    /** Lectura con el índice del final del archivo: sirve para cualquier zip (también el que arma el navegador con tamaños al final). */
    static String leerArchivo(File archivo, File dirFotos, Set<String> ids, Avance av, boolean sinFotos, boolean conservarMejor, int[] mant, List<String> vistos) throws IOException {
        String datos = null;
        byte[] buf = new byte[1 << 16];
        try (ZipFile zf = new ZipFile(archivo)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String nombre = nombreLimpio(e.getName());
                if (vistos != null && vistos.size() < 6) vistos.add(e.getName());
                if (nombre.equals("datos.json")) {
                    try (InputStream in = zf.getInputStream(e)) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        int r; while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
                        datos = b.toString("UTF-8");
                    }
                    continue;
                }
                Matcher m = ENTRADA.matcher(nombre);
                if (sinFotos || !m.matches() || m.group(1).contains("..")) continue;
                try (InputStream in = zf.getInputStream(e)) { guardarFoto(in, m.group(1), m.group(2), dirFotos, conservarMejor, ids, mant, buf, av); }
            }
        }
        return datos;
    }

    /** modo: "todo" | "datos" (no toca fotos) | "fotos". conservarMejor: si ya hay una foto igual o más pesada, se queda. mant[0] cuenta las conservadas. */
    public static String descomprimir(InputStream origen, File dirFotos, int[] cuenta, Avance av, String modo, boolean conservarMejor, int[] mant) throws IOException {
        dirFotos.mkdirs();
        Set<String> ids = new HashSet<>();
        String datos = leerFlujo(origen, dirFotos, ids, av, "datos".equals(modo), conservarMejor, mant, null);
        if (cuenta != null && cuenta.length > 0) cuenta[0] = ids.size();
        return datos;
    }


    static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    static long u32(byte[] b, int o) { return (u16(b, o) | ((long) u16(b, o + 2) << 16)) & 0xFFFFFFFFL; }
    static long u64(byte[] b, int o) { return u32(b, o) | (u32(b, o + 4) << 32); }

    /** Flujo acotado: lee como mucho «n» bytes del archivo desde la posición dada. */
    static class Acotado extends InputStream {
        final FileInputStream f; long resta;
        Acotado(File archivo, long desde, long n) throws IOException { f = new FileInputStream(archivo); f.getChannel().position(desde); resta = n; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int r = read(b, 0, 1); return r < 0 ? -1 : (b[0] & 0xFF); }
        @Override public int read(byte[] b, int o, int l) throws IOException { if (resta <= 0) return -1; int r = f.read(b, o, (int) Math.min(l, resta)); if (r > 0) resta -= r; return r; }
        @Override public void close() throws IOException { f.close(); }
    }

    /** Datos del final del archivo: tamaño, cómo empieza y qué índice trae. Sirve para explicar por qué no se pudo leer. */
    public static String diagnostico(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length(); byte[] h = new byte[(int) Math.min(8, len)]; r.readFully(h);
            StringBuilder sb = new StringBuilder(); sb.append(len / 1048576).append(" MB, empieza con ");
            for (byte x : h) sb.append(String.format("%02X", x & 0xFF));
            int tail = (int) Math.min(len, 70000); byte[] t = new byte[tail]; r.seek(len - tail); r.readFully(t);
            int p = -1; for (int i = tail - 22; i >= 0; i--) if (t[i] == 0x50 && t[i + 1] == 0x4b && t[i + 2] == 5 && t[i + 3] == 6) { p = i; break; }
            if (p < 0) sb.append(", sin índice final"); else sb.append(", índice final con ").append(u16(t, p + 10)).append(" entradas");
            return sb.toString();
        } catch (Throwable e) { return "no se pudo examinar: " + e.getMessage(); }
    }

    /** Lector propio con el índice del final (también zip64): no depende del lector de Android. */
    static String leerManual(File f, File dirFotos, Set<String> ids, Avance av, boolean sinFotos, boolean conservarMejor, int[] mant, List<String> vistos) throws IOException {
        String datos = null; byte[] buf = new byte[1 << 16];
        long n, tam, off;
        byte[] cd;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length(); int tail = (int) Math.min(len, 70000); byte[] t = new byte[tail]; raf.seek(len - tail); raf.readFully(t);
            int p = -1; for (int i = tail - 22; i >= 0; i--) if (t[i] == 0x50 && t[i + 1] == 0x4b && t[i + 2] == 5 && t[i + 3] == 6) { p = i; break; }
            if (p < 0) return null;
            n = u16(t, p + 10); tam = u32(t, p + 12); off = u32(t, p + 16);
            if ((n == 0xFFFF || tam == 0xFFFFFFFFL || off == 0xFFFFFFFFL) && p >= 20 && len - tail + p - 20 >= 0) {
                byte[] l = new byte[20]; raf.seek(len - tail + p - 20); raf.readFully(l);
                if (u32(l, 0) == 0x07064b50L) {
                    byte[] z = new byte[56]; raf.seek(u64(l, 8)); raf.readFully(z);
                    if (u32(z, 0) == 0x06064b50L) { n = u64(z, 32); tam = u64(z, 40); off = u64(z, 48); }
                }
            }
            if (tam <= 0 || tam > (1 << 28)) return null;
            cd = new byte[(int) tam]; raf.seek(off); raf.readFully(cd);
        }
        int q = 0;
        for (long k = 0; k < n && q + 46 <= cd.length; k++) {
            if (u32(cd, q) != 0x02014b50L) break;
            int metodo = u16(cd, q + 10), nl = u16(cd, q + 28), el = u16(cd, q + 30), cl = u16(cd, q + 32);
            long csize = u32(cd, q + 20), usize = u32(cd, q + 24), lho = u32(cd, q + 42);
            String nombre = new String(cd, q + 46, nl, StandardCharsets.UTF_8);
            int ex = q + 46 + nl, fin = ex + el;
            while (ex + 4 <= fin) {
                int id = u16(cd, ex), sz = u16(cd, ex + 2), d = ex + 4;
                if (id == 1) {
                    if (usize == 0xFFFFFFFFL && d + 8 <= fin) { usize = u64(cd, d); d += 8; }
                    if (csize == 0xFFFFFFFFL && d + 8 <= fin) { csize = u64(cd, d); d += 8; }
                    if (lho == 0xFFFFFFFFL && d + 8 <= fin) { lho = u64(cd, d); d += 8; }
                }
                ex += 4 + sz;
            }
            q += 46 + nl + el + cl;
            if (nombre.endsWith("/")) continue;
            nombre = nombreLimpio(nombre);
            if (vistos != null && vistos.size() < 6) vistos.add(nombre);
            boolean esDatos = nombre.equals("datos.json"); Matcher m = ENTRADA.matcher(nombre);
            if (!esDatos && (sinFotos || !m.matches() || m.group(1).contains(".."))) continue;
            byte[] lh = new byte[30];
            try (RandomAccessFile r2 = new RandomAccessFile(f, "r")) { r2.seek(lho); r2.readFully(lh); }
            if (u32(lh, 0) != 0x04034b50L) continue;
            long ini = lho + 30 + u16(lh, 26) + u16(lh, 28);
            try (InputStream raw = new Acotado(f, ini, csize); InputStream in = metodo == 8 ? new java.util.zip.InflaterInputStream(raw, new Inflater(true), 1 << 16) : raw) {
                if (esDatos) { ByteArrayOutputStream b = new ByteArrayOutputStream(); int r; while ((r = in.read(buf)) > 0) b.write(buf, 0, r); datos = b.toString("UTF-8"); }
                else guardarFoto(in, m.group(1), m.group(2), dirFotos, conservarMejor, ids, mant, buf, av);
            }
        }
        return datos;
    }

    /** Lee la copia directamente de un archivo con acceso aleatorio (sirve para cualquier zip y no hace falta copiarla). */
    public static String restaurarArchivo(File archivo, File dirFotos, int[] cuenta, Avance av, String modo, boolean conservarMejor, int[] mant, List<String> vistos) throws IOException {
        dirFotos.mkdirs();
        Set<String> ids = new HashSet<>();
        String datos = null;
        try { datos = leerArchivo(archivo, dirFotos, ids, av, "datos".equals(modo), conservarMejor, mant, vistos); } catch (IOException ex) { datos = null; }
        if (datos == null || datos.trim().isEmpty()) {
            ids.clear(); if (mant != null && mant.length > 0) mant[0] = 0; if (vistos != null) vistos.clear();
            datos = leerManual(archivo, dirFotos, ids, av, "datos".equals(modo), conservarMejor, mant, vistos);
        }
        if (cuenta != null && cuenta.length > 0) cuenta[0] = ids.size();
        return datos;
    }

    /** Restaurar una copia grande o de cualquier origen: primero se intenta en un solo paso; si no sale (o datos.json llega vacío) se lee con el índice del final. vistos: primeros nombres del zip, para explicar el error. */
    public static String restaurar(Abridor abridor, File cacheDir, File dirFotos, int[] cuenta, Avance av, String modo, boolean conservarMejor, int[] mant, List<String> vistos) throws IOException {
        boolean sinFotos = "datos".equals(modo);
        dirFotos.mkdirs();
        Set<String> ids = new HashSet<>();
        String datos = null;
        try (InputStream in = abridor.abrir()) { datos = leerFlujo(in, dirFotos, ids, av, sinFotos, conservarMejor, mant, vistos); }
        catch (IOException ex) { datos = null; }
        if (datos == null || datos.trim().isEmpty()) {
            File tmp = new File(cacheDir, "restaurar-tmp.zip");
            try {
                try (InputStream in = abridor.abrir(); OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
                    byte[] b = new byte[1 << 16]; int r; while ((r = in.read(b)) > 0) o.write(b, 0, r);
                }
                ids.clear(); if (mant != null && mant.length > 0) mant[0] = 0; if (vistos != null) vistos.clear();
                try { datos = leerArchivo(tmp, dirFotos, ids, av, sinFotos, conservarMejor, mant, vistos); } catch (IOException ex2) { datos = null; }
                if (datos == null || datos.trim().isEmpty()) { ids.clear(); if (mant != null && mant.length > 0) mant[0] = 0; if (vistos != null) vistos.clear(); datos = leerManual(tmp, dirFotos, ids, av, sinFotos, conservarMejor, mant, vistos); }
            } finally { tmp.delete(); }
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
