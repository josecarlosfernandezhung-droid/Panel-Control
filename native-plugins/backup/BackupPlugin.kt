package com.tototools.catalogo.plugins

import android.util.Base64
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Copia de seguridad nativa (Kotlin):
 * - Escribe datos.json
 * - Empaqueta fotos desde el directorio DATA/fotos de Capacitor
 * - Devuelve la URI del ZIP para compartir/guardar
 *
 * Llamada desde JS:
 *   Capacitor.Plugins.CatalogoBackup.crear({
 *     datosJson: "...",
 *     fotoIds: ["id1","id2"],
 *     calidad: "completa" | "mini",
 *     nombre: "catalogo-copia.zip"
 *   })
 */
@CapacitorPlugin(name = "CatalogoBackup")
class BackupPlugin : Plugin() {

    @PluginMethod
    fun crear(call: PluginCall) {
        try {
            val datosJson = call.getString("datosJson")
                ?: return call.reject("Falta datosJson")
            val nombre = call.getString("nombre") ?: "catalogo-copia.zip"
            val calidad = call.getString("calidad") ?: "mini"
            val fotoIds = mutableListOf<String>()
            call.getArray("fotoIds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.getString(i)?.takeIf { it.isNotBlank() }?.let { fotoIds.add(it) }
                }
            }

            val ctx = context
            val filesDir = ctx.filesDir
            // Capacitor Filesystem Directory.DATA → files/
            val fotosDir = File(filesDir, "fotos")

            val outFile = File(ctx.cacheDir, nombre)
            if (outFile.exists()) outFile.delete()

            var fotosOk = 0
            var fotosFail = 0
            var fotosBytes = 0L

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zos ->
                // datos.json
                zos.putNextEntry(ZipEntry("datos.json"))
                val jsonBytes = datosJson.toByteArray(Charsets.UTF_8)
                zos.write(jsonBytes)
                zos.closeEntry()

                // fotos
                for (id in fotoIds) {
                    try {
                        val gFile = File(fotosDir, "$id.g")
                        val tFile = File(fotosDir, "$id.t")
                        // También probar extensión .bin por si acaso
                        val g = when {
                            gFile.exists() && gFile.length() > 0 -> gFile
                            File(fotosDir, "$id-g.bin").exists() -> File(fotosDir, "$id-g.bin")
                            else -> null
                        }
                        val t = when {
                            tFile.exists() && tFile.length() > 0 -> tFile
                            File(fotosDir, "$id-t.bin").exists() -> File(fotosDir, "$id-t.bin")
                            else -> null
                        }

                        if (g == null && t == null) {
                            fotosFail++
                            continue
                        }

                        if (calidad == "completa") {
                            if (g != null) {
                                putFile(zos, "fotos/$id-g.bin", g)
                                fotosBytes += g.length()
                            }
                            if (t != null && t != g) {
                                putFile(zos, "fotos/$id-t.bin", t)
                                fotosBytes += t.length()
                            }
                        } else {
                            // mini: preferir miniatura
                            val src = t ?: g!!
                            putFile(zos, "fotos/$id-g.bin", src)
                            fotosBytes += src.length()
                        }
                        fotosOk++
                    } catch (e: Exception) {
                        fotosFail++
                        Log.w(TAG, "foto $id: ${e.message}")
                    }
                }
            }

            if (fotoIds.isNotEmpty() && fotosOk == 0) {
                outFile.delete()
                return call.reject(
                    "No se pudo leer ninguna foto del teléfono (${fotoIds.size} referenciadas). " +
                        "Comprueba que se vean en el catálogo."
                )
            }

            val uri = "file://${outFile.absolutePath}"
            val ret = JSObject()
            ret.put("uri", uri)
            ret.put("path", outFile.absolutePath)
            ret.put("size", outFile.length())
            ret.put("fotosOk", fotosOk)
            ret.put("fotosFail", fotosFail)
            ret.put("fotosBytes", fotosBytes)
            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "crear backup", e)
            call.reject(e.message ?: "Error al crear la copia")
        }
    }

    /**
     * Lista cuántas fotos existen realmente en disco para los IDs dados.
     */
    @PluginMethod
    fun contarFotos(call: PluginCall) {
        try {
            val fotoIds = mutableListOf<String>()
            call.getArray("fotoIds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.getString(i)?.let { fotoIds.add(it) }
                }
            }
            val fotosDir = File(context.filesDir, "fotos")
            var ok = 0
            for (id in fotoIds) {
                val g = File(fotosDir, "$id.g")
                val t = File(fotosDir, "$id.t")
                if ((g.exists() && g.length() > 0) || (t.exists() && t.length() > 0)) ok++
            }
            val ret = JSObject()
            ret.put("enDisco", ok)
            ret.put("pedidas", fotoIds.size)
            ret.put("dir", fotosDir.absolutePath)
            ret.put("dirExiste", fotosDir.exists())
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject(e.message ?: "Error")
        }
    }

    private fun putFile(zos: ZipOutputStream, entryName: String, file: File) {
        zos.putNextEntry(ZipEntry(entryName))
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                zos.write(buf, 0, n)
            }
        }
        zos.closeEntry()
    }

    companion object {
        private const val TAG = "CatalogoBackup"
    }
}
