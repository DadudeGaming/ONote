package com.nicholas.onote.data

import android.content.Context
import com.nicholas.onote.drawing.CompletedStroke
import com.nicholas.onote.drawing.PageBackground
import com.nicholas.onote.drawing.StrokePoint
import com.nicholas.onote.drawing.Tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    init {
        if (!dir.exists()) dir.mkdirs()
        if (!trashDir.exists()) trashDir.mkdirs()
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
            applyCamera(json.optJSONObject("camera"), doc)

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
                        .getOrDefault(doc.pageBackground)
                )
                applyCamera(pj.optJSONObject("camera"), page)
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
        json.put("pageMode", doc.pageMode.name)
        json.put("camera", cameraJson(doc))

        json.put("strokes", strokesJson(doc.strokes))

        val pages = JSONArray()
        for (p in doc.pages) {
            val pj = JSONObject()
            pj.put("id", p.id)
            pj.put("bg", p.pageBackground.name)
            pj.put("camera", cameraJson(p))
            pj.put("strokes", strokesJson(p.strokes))
            pages.put(pj)
        }
        json.put("pages", pages)
        return json.toString()
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