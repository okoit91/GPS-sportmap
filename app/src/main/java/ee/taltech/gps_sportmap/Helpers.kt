package ee.taltech.gps_sportmap

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun addCP (map: GoogleMap, location: LatLng) {

    map.addMarker(
        MarkerOptions()
            .position(location)
            .title("Checkpoint")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
    )
    map.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 17f))

}

fun showConfirmationDialog(
    context: Context,
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val builder = AlertDialog.Builder(context)
    builder.setTitle(title)
    builder.setMessage(message)
    builder.setPositiveButton("Yes") { _, _ -> onConfirm() }
    builder.setNegativeButton("No") { dialog, _ ->
        dialog.dismiss()
        onCancel?.invoke()
    }
    builder.create().show()
}

fun getCurrentISO8601Timestamp(): String {
    return Instant.now()
        .atOffset(ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

fun isInternetAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = connectivityManager.activeNetwork
    val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
    return networkCapabilities != null && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}


