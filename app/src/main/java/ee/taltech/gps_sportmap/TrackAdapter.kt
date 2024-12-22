package ee.taltech.gps_sportmap

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ee.taltech.gps_sportmap.domain.Track

class TrackAdapter(
    private var tracks: List<Track>,
    private val onTrackSelected: (Track) -> Unit,
    private val onTrackDeleted: (Track) -> Unit,
    private val onTrackRenamed: (Track) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val trackName: TextView = itemView.findViewById(R.id.trackName)
        val trackDate: TextView = itemView.findViewById(R.id.trackDate)
        val deleteButton: ImageButton = itemView.findViewById(R.id.deleteTrackButton)
        val renameButton: ImageButton = itemView.findViewById(R.id.renameTrackButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        holder.trackName.text = "${track.name} #${track.id}" // You can customize this
        holder.trackDate.text = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", track.dt)

        // select
        holder.itemView.setOnClickListener { onTrackSelected(track) }

        // rename
        holder.renameButton.setOnClickListener {
            onTrackRenamed(track)
        }

        // delete
        holder.deleteButton.setOnClickListener { onTrackDeleted(track) }

    }

    override fun getItemCount(): Int = tracks.size

    // Update the adapter's data
    fun updateTracks(newTracks: List<Track>) {
        Log.d("TrackAdapter", "Updating tracks: ${newTracks.size}")
        tracks = newTracks
        notifyDataSetChanged()
    }


}