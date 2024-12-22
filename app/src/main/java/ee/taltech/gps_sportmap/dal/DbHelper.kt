package ee.taltech.gps_sportmap.dal
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.database.Cursor
import ee.taltech.gps_sportmap.domain.Track

class DbHelper (context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME,null, DATABASE_VERSION){
    companion object{
        const val DATABASE_NAME = "app.db"
        const val DATABASE_VERSION = 2

        const val TRACK_TABLE_NAME = "TRACKS"

        const val TRACK_ID = "_id"
        const val TRACK_DT = "dt"
        const val TRACK_STATE = "state"
        const val TRACK_NAME = "name"

        const val SQL_CREATE_TABLE =
            "create table $TRACK_TABLE_NAME(" +
                    "$TRACK_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$TRACK_DT INTEGER NOT NULL, " +
                    "$TRACK_STATE TEXT NOT NULL, " +
                    "$TRACK_NAME TEXT NOT NULL DEFAULT 'Unnamed Track');"


        const val SQL_DELETE_TABLES = "DROP TABLE IF EXISTS " +
                "$TRACK_TABLE_NAME";
    }

    override fun onCreate(db: SQLiteDatabase?) {
        db?.execSQL(SQL_CREATE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db?.execSQL("ALTER TABLE " +
                    "$TRACK_TABLE_NAME ADD COLUMN " +
                    "$TRACK_NAME TEXT NOT NULL DEFAULT 'Unnamed Track'")
        }
    }

    fun saveTrackState(jsonState: String) {
        val db = writableDatabase
        val contentValues = ContentValues().apply {
            put(TRACK_ID, 1) // Use a fixed ID since there's only one track state
            put(TRACK_STATE, jsonState)
        }
        db.replace(TRACK_TABLE_NAME, null, contentValues)
        db.close()
    }

    fun deleteTrack(trackId: Int): Int {
        val db = writableDatabase
        val rowsDeleted = db.delete(
            TRACK_TABLE_NAME,
            "$TRACK_ID = ?",
            arrayOf(trackId.toString())
        )
        db.close()
        return rowsDeleted
    }


    fun loadTrackState(): String? {
        val db = readableDatabase
        val cursor: Cursor = db.query(
            TRACK_TABLE_NAME,
            arrayOf(TRACK_STATE),
            "$TRACK_ID = ?",
            arrayOf("1"),
            null,
            null,
            null
        )

        val trackStateJson: String? = if (cursor.moveToFirst()) {
            cursor.getString(cursor.getColumnIndexOrThrow(TRACK_STATE))
        } else null

        cursor.close()
        db.close()
        return trackStateJson
    }

    fun getNextTrackId(): Int {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT MAX($TRACK_ID) FROM $TRACK_TABLE_NAME", null)
        var nextId = 1
        if (cursor.moveToFirst()) {
            val maxId = cursor.getInt(0)
            nextId = maxId + 1
        }
        cursor.close()
        return nextId
    }

    fun saveTrack(track: Track): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(TRACK_ID, track.id)
            put(TRACK_DT, track.dt)
            put(TRACK_STATE, track.state)
            put(TRACK_NAME, track.name) // Add the name
        }
        return db.insert(TRACK_TABLE_NAME, null, values)
    }


    fun getAllTracks(): List<Track> {
        val db = readableDatabase
        val cursor = db.query(
            TRACK_TABLE_NAME,
            arrayOf(TRACK_ID, TRACK_DT, TRACK_STATE, TRACK_NAME), // Include the name
            null, null, null, null,
            "$TRACK_DT DESC"
        )
        val tracks = mutableListOf<Track>()
        while (cursor.moveToNext()) {
            val id = cursor.getInt(cursor.getColumnIndexOrThrow(TRACK_ID))
            val dt = cursor.getLong(cursor.getColumnIndexOrThrow(TRACK_DT))
            val state = cursor.getString(cursor.getColumnIndexOrThrow(TRACK_STATE))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(TRACK_NAME)) // Get the name
            tracks.add(Track(id, dt, state, name))
        }
        cursor.close()
        db.close()
        return tracks
    }

    fun loadTrack(trackId: Int): Track? {
        val db = readableDatabase
        val cursor = db.query(
            TRACK_TABLE_NAME,
            arrayOf(TRACK_ID, TRACK_DT, TRACK_STATE, TRACK_NAME), // Include the name
            "$TRACK_ID = ?",
            arrayOf(trackId.toString()),
            null,
            null,
            null
        )

        val track: Track? = if (cursor.moveToFirst()) {
            val id = cursor.getInt(cursor.getColumnIndexOrThrow(TRACK_ID))
            val dt = cursor.getLong(cursor.getColumnIndexOrThrow(TRACK_DT))
            val state = cursor.getString(cursor.getColumnIndexOrThrow(TRACK_STATE))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(TRACK_NAME)) // Get the name
            Track(id, dt, state, name)
        } else null

        cursor.close()
        db.close()
        return track
    }



    fun renameTrack(trackId: Int, newName: String): Int {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(TRACK_NAME, newName)
        }
        return db.update(TRACK_TABLE_NAME, values, "$TRACK_ID = ?", arrayOf(trackId.toString()))
    }

}