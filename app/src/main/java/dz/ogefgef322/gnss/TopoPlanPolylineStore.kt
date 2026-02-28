package dz.ogefgef322.gnss
import android.content.ContentResolver
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

data class StoredPolylinePoint(
    val x: Double,
    val y: Double
)

data class StoredPolyline(
    val id: String,
    val leveId: String?,
    val type: String,
    val points: List<StoredPolylinePoint>,
    val closed: Boolean = false,
    val holes: List<List<StoredPolylinePoint>>? = null
)

class TopoPlanPolylineStore(private val contentResolver: ContentResolver) {

    fun load(chantierDir: DocumentFile): List<StoredPolyline> {
        val file = chantierDir.findFile(FILE_NAME) ?: return emptyList()
        return runCatching {
            contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                val text = reader.readText()
                val array = JSONArray(text)
                buildList {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: continue
                        val type = obj.optString("type", "").takeIf { it.isNotBlank() } ?: continue
                        val leveId = obj.opt("leveId")?.takeIf { it != JSONObject.NULL }?.toString()
                        val pointsArray = obj.optJSONArray("points") ?: JSONArray()
                        val points = buildList {
                            for (j in 0 until pointsArray.length()) {
                                val pt = pointsArray.optJSONObject(j) ?: continue
                                val x = pt.optDouble("x", Double.NaN)
                                val y = pt.optDouble("y", Double.NaN)
                                if (!x.isNaN() && !y.isNaN()) {
                                    add(StoredPolylinePoint(x = x, y = y))
                                }
                            }
                        }
                        val closed = obj.optBoolean("closed", false)
                        val holes = obj.optJSONArray("holes")?.let { holesArray ->
                            buildList {
                                for (h in 0 until holesArray.length()) {
                                    val holeArray = holesArray.optJSONArray(h) ?: continue
                                    val holePoints = buildList {
                                        for (k in 0 until holeArray.length()) {
                                            val pt = holeArray.optJSONObject(k) ?: continue
                                            val x = pt.optDouble("x", Double.NaN)
                                            val y = pt.optDouble("y", Double.NaN)
                                            if (!x.isNaN() && !y.isNaN()) {
                                                add(StoredPolylinePoint(x = x, y = y))
                                            }
                                        }
                                    }
                                    if (holePoints.isNotEmpty()) {
                                        add(holePoints)
                                    }
                                }
                            }.takeIf { it.isNotEmpty() }
                        }
                        add(
                            StoredPolyline(
                                id = id,
                                leveId = leveId,
                                type = type,
                                points = points,
                                closed = closed,
                                holes = holes
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Impossible de lire $FILE_NAME", err)
            emptyList()
        }
    }

    fun save(chantierDir: DocumentFile, polylines: List<StoredPolyline>) {
        val file = chantierDir.findFile(FILE_NAME)
            ?: chantierDir.createFile("application/json", FILE_NAME)
            ?: return
        runCatching {
            val array = JSONArray()
            polylines.forEach { poly ->
                val obj = JSONObject()
                obj.put("id", poly.id)
                obj.put("leveId", poly.leveId ?: JSONObject.NULL)
                obj.put("type", poly.type)
                val pointsArray = JSONArray()
                poly.points.forEach { pt ->
                    val ptObj = JSONObject()
                    ptObj.put("x", pt.x)
                    ptObj.put("y", pt.y)
                    pointsArray.put(ptObj)
                }
                obj.put("points", pointsArray)
                obj.put("closed", poly.closed)
                poly.holes?.takeIf { it.isNotEmpty() }?.let { holes ->
                    val holesArray = JSONArray()
                    holes.forEach { hole ->
                        val holeArray = JSONArray()
                        hole.forEach { pt ->
                            val ptObj = JSONObject()
                            ptObj.put("x", pt.x)
                            ptObj.put("y", pt.y)
                            holeArray.put(ptObj)
                        }
                        holesArray.put(holeArray)
                    }
                    obj.put("holes", holesArray)
                }
                array.put(obj)
            }
            contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(array.toString())
            }
        }.onFailure { err ->
            Log.w(TAG, "Impossible d'écrire $FILE_NAME", err)
        }
    }

    companion object {
        private const val FILE_NAME = "topoplan_polylines.json"
        private const val TAG = "TopoPlanPolylineStore"
    }
}
