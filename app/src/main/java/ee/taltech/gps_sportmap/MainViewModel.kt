package ee.taltech.gps_sportmap

import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {

    var isTracking = false
    var isTrackingUserRotation: Boolean = true
    var isCompassVisible: Boolean = false
}