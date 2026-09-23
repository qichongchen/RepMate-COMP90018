package com.repmate.safety

/** The single emergency contact safety check-in can alert. Set once, from Profile. */
data class SafetyContact(
    val name: String,
    val phoneNumber: String,
)

/** A last known position, decoupled from `android.location.Location` so the escalation logic
 * ([CheckInEscalationAction]) and its tests never need a real Android runtime. */
data class LastLocation(
    val latitude: Double,
    val longitude: Double,
)
