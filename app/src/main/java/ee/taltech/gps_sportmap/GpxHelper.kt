import android.content.Context
import ee.taltech.gps_sportmap.dto.GpsLocationDTO
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GpxHelper {

    fun generateGpxFile(
        context: Context,
        sessionName: String,
        trackPoints: List<GpsLocationDTO>,
        checkpoints: List<GpsLocationDTO>
    ): File? {
        val gpxHeader = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="GPS Sport Map" xmlns="http://www.topografix.com/GPX/1/1">
    """.trimIndent()

        val gpxFooter = "</gpx>"
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

        val trackSegments = trackPoints.joinToString("\n") { location ->
            """
            <trkpt lat="${location.latitude}" lon="${location.longitude}">
                <time>${location.recordedAt}</time>
                <ele>${location.altitude}</ele>
                <fix>3d</fix>
                <sat>8</sat>
                <hdop>${location.accuracy}</hdop>
            </trkpt>
        """.trimIndent()
        }

        val waypointEntries = checkpoints.joinToString("\n") { checkpoint ->
            """
            <wpt lat="${checkpoint.latitude}" lon="${checkpoint.longitude}">
                <time>${checkpoint.recordedAt}</time>
                <ele>${checkpoint.altitude}</ele>
                <fix>3d</fix>
                <sat>8</sat>
                <hdop>${checkpoint.accuracy}</hdop>
            </wpt>
        """.trimIndent()
        }

        val gpxContent = """
        $gpxHeader
        <metadata>
            <name>${sessionName}</name>
            <time>${sdf.format(Date())}</time>
        </metadata>
        <trk>
            <name>${sessionName}</name>
            <trkseg>
                $trackSegments
            </trkseg>
        </trk>
        $waypointEntries
        $gpxFooter
    """.trimIndent()

        return try {
            val file = File(context.cacheDir, "$sessionName.gpx")
            FileOutputStream(file).use { it.write(gpxContent.toByteArray()) }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
