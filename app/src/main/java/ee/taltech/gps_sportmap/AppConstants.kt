package ee.taltech.gps_sportmap

import ee.taltech.gps_sportmap.domain.ActivityType
import ee.taltech.gps_sportmap.domain.LocationType

object AppConstants {
    val activityTypes = listOf(
        ActivityType(
            name = "Running - easy",
            description = "Easy normal running-jogging",
            paceMin = 360,
            paceMax = 600,
            id = "00000000-0000-0000-0000-000000000001"
        ),
        ActivityType(
            name = "Running",
            description = "Running",
            paceMin = 300,
            paceMax = 420,
            id = "00000000-0000-0000-0000-000000000002"
        ),
        ActivityType(
            name = "Orienteering - easy",
            description = "Orienteering easy mode - training",
            paceMin = 360,
            paceMax = 720,
            id = "00000000-0000-0000-0000-000000000003"
        ),
        ActivityType(
            name = "Orienteering - competition",
            description = "Orienteering competition",
            paceMin = 300,
            paceMax = 540,
            id = "00000000-0000-0000-0000-000000000004"
        ),
        ActivityType(
            name = "Bicycle - easy",
            description = "Bicycle easy mode - training",
            paceMin = 180,
            paceMax = 360,
            id = "00000000-0000-0000-0000-000000000005"
        ),
        ActivityType(
            name = "Bicycle - competition",
            description = "Bicycle competition",
            paceMin = 120,
            paceMax = 300,
            id = "00000000-0000-0000-0000-000000000006"
        )
    )

    val locationTypes = listOf(
        LocationType(
            name = "LOC",
            description = "Regular periodic location update",
            id = "00000000-0000-0000-0000-000000000001"
        ),
        LocationType(
            name = "WP",
            description = "Waypoint - temporary location, used as navigation aid",
            id = "00000000-0000-0000-0000-000000000002"
        ),
        LocationType(
            name = "CP",
            description = "Checkpoint - found on terrain the location marked on the paper map",
            id = "00000000-0000-0000-0000-000000000003"
        )
    )
}