package com.example.gpstracker.utils

import android.content.Context
import android.net.Uri
import com.example.gpstracker.data.AppDatabase
import com.example.gpstracker.data.LocationPoint
import com.example.gpstracker.data.Session
import org.json.JSONArray
import org.json.JSONObject

object SessionExporter {

    private const val PREFS_NAME = "gps_tracker_prefs"
    private const val KEY_MOVEMENT_THRESHOLD = "movement_threshold"
    private const val KEY_DWELL_TIME = "dwell_time_seconds"
    private const val KEY_TRACKING_INTERVAL = "tracking_interval_seconds"
    private const val KEY_DWELLING_INTERVAL = "dwelling_interval_seconds"

    private data class Settings(
        val movementThresholdM: Double,
        val dwellTimeS: Int,
        val trackingIntervalS: Int,
        val dwellingIntervalS: Int
    )

    private fun loadSettings(context: Context): Settings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Settings(
            movementThresholdM = prefs.getFloat(KEY_MOVEMENT_THRESHOLD, 20.0f).toDouble(),
            dwellTimeS = prefs.getInt(KEY_DWELL_TIME, 15),
            trackingIntervalS = prefs.getInt(KEY_TRACKING_INTERVAL, 5),
            dwellingIntervalS = prefs.getInt(KEY_DWELLING_INTERVAL, 30)
        )
    }

    private data class SessionJson(
        val startTime: Long,
        val endTime: Long?,
        val totalPoints: Int,
        val points: List<LocationPoint>,
        val settings: Settings
    )

    suspend fun export(context: Context, uri: Uri): Result<Unit> {
        return try {
            val db = AppDatabase.getDatabase(context)
            val sessions = db.sessionDao().getAllSessionsSync()
            val settings = loadSettings(context)
            val exportData = sessions.map { session ->
                val points = db.locationPointDao().getPointsBySessionSync(session.id)
                SessionJson(session.startTime, session.endTime, session.totalPoints, points, settings)
            }
            val json = buildJson(exportData)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            } ?: return Result.failure(Exception("Could not open output stream"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun import(context: Context, uri: Uri): Result<Int> {
        return try {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return Result.failure(Exception("Could not open input stream"))

            val data = parseJson(json)
            val db = AppDatabase.getDatabase(context)
            var imported = 0

            for (sessionData in data) {
                val existing = db.sessionDao().getSessionByStartTime(sessionData.startTime)
                if (existing != null) continue

                val session = Session(
                    startTime = sessionData.startTime,
                    endTime = sessionData.endTime,
                    totalPoints = sessionData.points.size
                )
                val newId = db.sessionDao().insertSession(session)
                val dbPoints = sessionData.points.map { p ->
                    LocationPoint(
                        sessionId = newId,
                        latitude = p.latitude,
                        longitude = p.longitude,
                        altitude = p.altitude,
                        timestamp = p.timestamp
                    )
                }
                db.locationPointDao().insertPoints(dbPoints)
                imported++
            }

            Result.success(imported)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildJson(sessions: List<SessionJson>): String {
        val root = JSONObject()
        root.put("version", 1)
        val sessionsArr = JSONArray()
        for (s in sessions) {
            val sObj = JSONObject()
            sObj.put("startTime", s.startTime)
            sObj.put("endTime", s.endTime ?: JSONObject.NULL)
            sObj.put("totalPoints", s.totalPoints)
            val settingsObj = JSONObject()
            settingsObj.put("movementThresholdM", s.settings.movementThresholdM)
            settingsObj.put("dwellTimeS", s.settings.dwellTimeS)
            settingsObj.put("trackingIntervalS", s.settings.trackingIntervalS)
            settingsObj.put("dwellingIntervalS", s.settings.dwellingIntervalS)
            sObj.put("settings", settingsObj)
            val pointsArr = JSONArray()
            for (p in s.points) {
                val pObj = JSONObject()
                pObj.put("latitude", p.latitude)
                pObj.put("longitude", p.longitude)
                pObj.put("altitude", p.altitude)
                pObj.put("timestamp", p.timestamp)
                pointsArr.put(pObj)
            }
            sObj.put("points", pointsArr)
            sessionsArr.put(sObj)
        }
        root.put("sessions", sessionsArr)
        return root.toString(2)
    }

    private data class SessionData(
        val startTime: Long,
        val endTime: Long?,
        val points: List<PointData>
    )

    private data class PointData(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val timestamp: Long
    )

    private fun parseJson(json: String): List<SessionData> {
        val root = JSONObject(json)
        val sessionsArr = root.getJSONArray("sessions")
        val result = mutableListOf<SessionData>()
        for (i in 0 until sessionsArr.length()) {
            val sObj = sessionsArr.getJSONObject(i)
            val startTime = sObj.getLong("startTime")
            val endTime = if (sObj.isNull("endTime")) null else sObj.getLong("endTime")
            val pointsArr = sObj.getJSONArray("points")
            val points = mutableListOf<PointData>()
            for (j in 0 until pointsArr.length()) {
                val pObj = pointsArr.getJSONObject(j)
                points.add(
                    PointData(
                        latitude = pObj.getDouble("latitude"),
                        longitude = pObj.getDouble("longitude"),
                        altitude = pObj.getDouble("altitude"),
                        timestamp = pObj.getLong("timestamp")
                    )
                )
            }
            result.add(SessionData(startTime, endTime, points))
        }
        return result
    }
}
