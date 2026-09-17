package com.nicholas.onote.data

import android.content.Context
import android.util.Base64
import com.nicholas.onote.drawing.CompletedStroke
import com.nicholas.onote.drawing.PageBackground
import com.nicholas.onote.drawing.StrokePoint
import com.nicholas.onote.drawing.Tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * File-based document persistence. Every notebook is a single JSON file under
 * filesDir/notebooks/<id>.json; deleting a notebook moves its file to
 * filesDir/trash/<id>.json (a temporary trash, emptied from the app). Storage
 * Access Framework/Room can replace this later without touching the model.
 */
class NoteStore(context: Context) {

    private val dir = File(context.filesDir, "notebooks")
    private val trashDir = File(context.filesDir, "trash")
    private val imagesDir = File(context.filesDir, "images")
    private val filesDir = context.filesDir

    init {
        if (!dir.exists()) dir.mkdirs()
        if (!trashDir.exists()) trashDir.mkdirs()
        if (!imagesDir.exists()) imagesDir.mkdirs()
    }

    /** Copies [source] into internal storage and returns a store-relative path. */
    fun storeImage(source: InputStream, ext: String): String {
        val name = "${UUID.randomUUID()}.$ext"
        val target = File(imagesDir, name)
        source.use { input -> target.outputStream().use { input.copyTo(it) } }
        return "images/$name"
    }

    /**
     * Standalone backup of a single notebook: the document JSON plus every
     * referenced image inlined as base64. Returned string is written to a
     * user-picked location (e.g. Google Drive).
     */
    fun backupDocument(doc: NoteDocument): String {
        val docJson = JSONObject(encode(doc))
        val imagePaths = LinkedHashSet<String>()
        collectImages(docJson.optJSONArray("images"), imagePaths)
        val pages = docJson.optJSONArray("pages")
        if (pages != null) {
            for (i in 0 until pages.length()) {
                collectImages(pages.getJSONObject(i).optJSONArray("images"), imagePaths)
            }
        }
        val images = JSONObject()
        for (path in imagePaths) {
            val f = resolveImage(path)
            if (f.exists() && f.isFile) {
                images.put(path, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            }
        }
        return JSONObject()
            .put("onote_doc_backup", 1)
            .put("document", docJson)
            .put("images", images)
            .toString()
    }

    /**
     * Restores a notebook produced by [backupDocument]. Writes the embedded
     * images back to internal storage and saves the notebook as a normal
     * document. Returns the restored notebook, or null on failure.
     */
    fun importDocument(json: String): NoteDocument? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("onote_doc_backup", 0) != 1) return null
        val images = root.optJSONObject("images") ?: JSONObject()
        if (!imagesDir.exists()) imagesDir.mkdirs()
        val keys = images.keys()
        while (keys.hasNext()) {
            val rel = keys.next()
            val f = resolveImage(rel)
            f.parentFile?.mkdirs()
            f.writeBytes(Base64.decode(images.getString(rel), Base64.NO_WRAP))
        }
        val docJson = root.getJSONObject("document")
        val id = docJson.getString("id")
        File(dir, "$id.json").writeText(docJson.toString())
        loadDocument(id)
    }.getOrNull()

    private fun collectImages(array: JSONArray?, into: MutableSet<String>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            into.add(array.getJSONObject(i).optString("path"))
        }
    }

    /** Resolves a store-relative image path to a readable file. */
    fun resolveImage(path: String): File = File(filesDir, path)

    fun deleteImage(path: String) {
        runCatching { resolveImage(path).delete() }
    }

    fun listDocuments(): List<NoteDocument> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .mapNotNull { loadDocument(it) }
            .sortedByDescending { it.updatedAt }
    }

    fun createDocument(title: String, pageMode: PageMode, background: PageBackground): NoteDocument {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val doc = NoteDocument(id, title, now, now, pageMode)
        doc.pageBackground = background
        doc.insertBackground = background
        if (pageMode == PageMode.PAGES) {
            doc.pages.add(NotePage(UUID.randomUUID().toString(), background))
        }
        saveDocument(doc)
        return doc
    }

    fun loadDocument(id: String): NoteDocument? =
        loadDocument(File(dir, "$id.json"))

    fun saveDocument(doc: NoteDocument) {
        file(doc).writeText(encode(doc))
    }

    fun deleteDocument(id: String) {
        File(dir, "$id.json").delete()
    }

    fun moveToTrash(id: String) {
        val src = File(dir, "$id.json")
        if (src.isFile) src.renameTo(File(trashDir, "$id.json"))
    }

    fun restoreFromTrash(id: String) {
        val src = File(trashDir, "$id.json")
        if (src.isFile) src.renameTo(File(dir, "$id.json"))
    }

    fun deleteForever(id: String) {
        File(trashDir, "$id.json").delete()
    }

    fun listTrash(): List<NoteDocument> {
        val files = trashDir.listFiles() ?: return emptyList()
        return files
            .mapNotNull { loadDocument(it) }
            .sortedBy { it.updatedAt }
    }

    /**
     * A portable backup of every notebook plus its image files (base64),
     * suitable for saving anywhere — including Google Drive via the Share
     * sheet or the SAF file picker.
     */
    fun backupJson(): String {
        val docs = JSONArray()
        val images = JSONObject()
        val seen = LinkedHashSet<String>()
        val files = dir.listFiles() ?: emptyArray()
        for (f in files) {
            val text = f.readText()
            docs.put(JSONObject(text))
            collectImagePaths(JSONObject(text), seen)
        }
        for (path in seen) {
            val file = resolveImage(path)
            if (file.isFile) {
                images.put(path, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            }
        }
        return JSONObject()
            .put("onote_backup", 1)
            .put("documents", docs)
            .put("images", images)
            .toString()
    }

    /** Replaces all local notebooks with a previously written [backupJson]. */
    fun restoreFromBackup(json: String): Boolean = runCatching {
        val root = JSONObject(json)
        val images = root.optJSONObject("images") ?: JSONObject()
        if (!imagesDir.exists()) imagesDir.mkdirs()
        val imageKeys = images.keys()
        while (imageKeys.hasNext()) {
            val path = imageKeys.next()
            val bytes = Base64.decode(images.getString(path), Base64.NO_WRAP)
            val file = resolveImage(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        val docs = root.optJSONArray("documents") ?: JSONArray()
        dir.listFiles()?.forEach { it.delete() }
        trashDir.listFiles()?.forEach { it.delete() }
        for (i in 0 until docs.length()) {
            val doc = docs.getJSONObject(i)
            File(dir, "${doc.getString("id")}.json").writeText(doc.toString())
        }
        true
    }.getOrDefault(false)

    private fun collectImagePaths(doc: JSONObject, out: MutableSet<String>) {
        collectFromImageArray(doc.optJSONArray("images"), out)
        val pages = doc.optJSONArray("pages") ?: return
        for (i in 0 until pages.length()) {
            collectFromImageArray(pages.getJSONObject(i).optJSONArray("images"), out)
        }
    }

    private fun collectFromImageArray(arr: JSONArray?, out: MutableSet<String>) {
        arr ?: return
        for (i in 0 until arr.length()) {
            out.add(arr.getJSONObject(i).getString("path"))
        }
    }

    private fun loadDocument(file: File): NoteDocument? {
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val pageMode = runCatching { PageMode.valueOf(json.optString("pageMode", "INFINITE")) }
                .getOrDefault(PageMode.INFINITE)
            val doc = NoteDocument(
                id = json.getString("id"),
                title = json.optString("title", "Untitled"),
                createdAt = json.optLong("createdAt", 0L),
                updatedAt = json.optLong("updatedAt", 0L),
                pageMode = pageMode
            )
            doc.pageBackground =
                runCatching { PageBackground.valueOf(json.getString("bg")) }
                    .getOrDefault(PageBackground.RULED)
            doc.insertBackground = runCatching {
                PageBackground.valueOf(json.optString("insertBg", doc.pageBackground.name))
            }.getOrDefault(doc.pageBackground)
            doc.insertOrientation = runCatching {
                Orientation.valueOf(json.optString("insertOrientation", "PORTRAIT"))
            }.getOrDefault(Orientation.PORTRAIT)
            applyCamera(json.optJSONObject("camera"), doc)
            decodeImages(json.optJSONArray("images"), doc.images)

            val arr = json.optJSONArray("strokes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                doc.strokes.add(decodeStroke(arr.getJSONObject(i)))
            }

            val pages = json.optJSONArray("pages") ?: JSONArray()
            for (i in 0 until pages.length()) {
                val pj = pages.getJSONObject(i)
                val page = NotePage(
                    id = pj.getString("id"),
                    pageBackground = runCatching { PageBackground.valueOf(pj.getString("bg")) }
                        .getOrDefault(doc.pageBackground),
                    orientation = runCatching {
                        Orientation.valueOf(pj.optString("orientation", "PORTRAIT"))
                    }.getOrDefault(Orientation.PORTRAIT)
                )
                page.title = pj.optString("title", "")
                applyCamera(pj.optJSONObject("camera"), page)
                decodeImages(pj.optJSONArray("images"), page.images)
                val sa = pj.optJSONArray("strokes") ?: JSONArray()
                for (j in 0 until sa.length()) {
                    page.strokes.add(decodeStroke(sa.getJSONObject(j)))
                }
                doc.pages.add(page)
            }

            if (pageMode == PageMode.PAGES && doc.pages.isEmpty()) {
                doc.pages.add(NotePage(UUID.randomUUID().toString(), doc.pageBackground))
            }
            doc
        }.getOrNull()
    }

    private fun decodeImages(arr: JSONArray?, out: ArrayList<PlacedImage>) {
        arr ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                PlacedImage(
                    id = o.getString("id"),
                    path = o.getString("path"),
                    x = o.optDouble("x", 0.0).toFloat(),
                    y = o.optDouble("y", 0.0).toFloat(),
                    w = o.optDouble("w", 320.0).toFloat(),
                    h = o.optDouble("h", 320.0).toFloat()
                )
            )
        }
    }

    private fun decodeStroke(so: JSONObject): CompletedStroke {
        val points = ArrayList<StrokePoint>()
        val pa = so.getJSONArray("points")
        for (j in 0 until pa.length()) {
            val p = pa.getJSONObject(j)
            points.add(
                StrokePoint(
                    p.getDouble("x").toFloat(),
                    p.getDouble("y").toFloat(),
                    p.optDouble("p", 1.0).toFloat(),
                    p.optLong("t", 0L)
                )
            )
        }
        return CompletedStroke(
            color = so.getInt("color"),
            points = points,
            baseWidth = so.optDouble("width", 4.0).toFloat(),
            tool = runCatching { Tool.valueOf(so.getString("tool")) }
                .getOrDefault(Tool.BALLPOINT)
        )
    }

    private fun applyCamera(json: JSONObject?, source: PageSource) {
        if (json == null) return
        source.cameraZoom = json.optDouble("zoom", 1.0).toFloat()
        source.cameraOffsetX = json.optDouble("x", 0.0).toFloat()
        source.cameraOffsetY = json.optDouble("y", 0.0).toFloat()
    }

    private fun encode(doc: NoteDocument): String {
        val json = JSONObject()
        json.put("id", doc.id)
        json.put("title", doc.title)
        json.put("createdAt", doc.createdAt)
        json.put("updatedAt", doc.updatedAt)
        json.put("bg", doc.pageBackground.name)
        json.put("insertBg", doc.insertBackground.name)
        json.put("insertOrientation", doc.insertOrientation.name)
        json.put("pageMode", doc.pageMode.name)
        json.put("camera", cameraJson(doc))
        json.put("images", imagesJson(doc.images))

        json.put("strokes", strokesJson(doc.strokes))

        val pages = JSONArray()
        for (p in doc.pages) {
            val pj = JSONObject()
            pj.put("id", p.id)
            pj.put("bg", p.pageBackground.name)
            pj.put("orientation", p.orientation.name)
            if (p.title.isNotBlank()) pj.put("title", p.title)
            pj.put("camera", cameraJson(p))
            pj.put("images", imagesJson(p.images))
            pj.put("strokes", strokesJson(p.strokes))
            pages.put(pj)
        }
        json.put("pages", pages)
        return json.toString()
    }

    private fun imagesJson(images: List<PlacedImage>): JSONArray {
        val arr = JSONArray()
        for (im in images) {
            val o = JSONObject()
            o.put("id", im.id)
            o.put("path", im.path)
            o.put("x", im.x.toDouble())
            o.put("y", im.y.toDouble())
            o.put("w", im.w.toDouble())
            o.put("h", im.h.toDouble())
            arr.put(o)
        }
        return arr
    }

    private fun cameraJson(source: PageSource): JSONObject {
        val cam = JSONObject()
        cam.put("zoom", source.cameraZoom.toDouble())
        cam.put("x", source.cameraOffsetX.toDouble())
        cam.put("y", source.cameraOffsetY.toDouble())
        return cam
    }

    private fun strokesJson(strokes: List<CompletedStroke>): JSONArray {
        val arr = JSONArray()
        for (s in strokes) {
            val so = JSONObject()
            so.put("color", s.color)
            so.put("width", s.baseWidth.toDouble())
            so.put("tool", s.tool.name)
            val pa = JSONArray()
            for (p in s.points) {
                val po = JSONObject()
                po.put("x", p.x.toDouble())
                po.put("y", p.y.toDouble())
                po.put("p", p.pressure.toDouble())
                po.put("t", p.timestamp)
                pa.put(po)
            }
            so.put("points", pa)
            arr.put(so)
        }
        return arr
    }

    private fun file(doc: NoteDocument): File = File(dir, "${doc.id}.json")
}