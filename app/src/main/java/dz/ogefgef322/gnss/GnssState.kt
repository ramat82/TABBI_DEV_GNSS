package dz.ogefgef322.gnss

/**
 * Single source of truth for the current GNSS fix (either from Bluetooth NMEA or simulation).
 *
 * This is intentionally small for the refactor steps: we keep only what MainActivity already uses.
 * More fields (accuracy, speed, heading, etc.) can be added later as needed.
 */
data class GnssState(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val alt: Double = 0.0,
    val fixQuality: Int = 0,
    val satellites: Int = 0,
    val inct: InctResult? = null,
    val connectedDeviceName: String? = null,
    val source: Source = Source.NONE,
    val updatedAtMs: Long = 0L
) {
    enum class Source { NONE, BLUETOOTH, SIMULATION }

    val hasFix: Boolean get() = fixQuality > 0
}

/**
 * Small observable store. Keeps state immutable (copy) and notifies listeners on updates.
 */
class GnssStateStore {

    @Volatile private var _state: GnssState = GnssState()
    val state: GnssState get() = _state

    private val listeners = mutableSetOf<(GnssState) -> Unit>()

    @Synchronized
    fun addListener(listener: (GnssState) -> Unit) {
        listeners.add(listener)
        listener(_state)
    }

    @Synchronized
    fun removeListener(listener: (GnssState) -> Unit) {
        listeners.remove(listener)
    }

    private fun update(transform: (GnssState) -> GnssState) {
        val newState: GnssState
        val toNotify: List<(GnssState) -> Unit>
        synchronized(this) {
            newState = transform(_state)
            if (newState == _state) return
            _state = newState
            toNotify = listeners.toList()
        }
        // Notify outside the lock.
        for (l in toNotify) runCatching { l(newState) }
    }

    fun resetFix() {
        update { it.copy(lat = 0.0, lon = 0.0, alt = 0.0, fixQuality = 0, satellites = 0, inct = null, updatedAtMs = now()) }
    }

    fun setConnection(connected: Boolean, deviceName: String?) {
        update { it.copy(connectedDeviceName = if (connected) deviceName else null, updatedAtMs = now()) }
    }

    fun setSource(source: GnssState.Source) {
        update { it.copy(source = source, updatedAtMs = now()) }
    }

    fun updateFromGga(update: NmeaParser.GgaFix, inct: InctResult?) {
        this.update {
            it.copy(
                lat = update.lat,
                lon = update.lon,
                alt = update.alt,
                fixQuality = update.fixQuality,
                satellites = update.satellites,
                inct = inct,
                updatedAtMs = now()
            )
        }
    }

    fun updateFromRmc(update: NmeaParser.RmcFix, inct: InctResult?) {
        this.update {
            it.copy(
                lat = update.lat,
                lon = update.lon,
                // keep alt/fixQuality from previous state
                inct = inct,
                updatedAtMs = now()
            )
        }
    }

    fun setSatellites(count: Int) {
        update { it.copy(satellites = count, updatedAtMs = now()) }
    }

    fun setLat(value: Double) {
        update { it.copy(lat = value, updatedAtMs = now()) }
    }

    fun setLon(value: Double) {
        update { it.copy(lon = value, updatedAtMs = now()) }
    }

    fun setAlt(value: Double) {
        update { it.copy(alt = value, updatedAtMs = now()) }
    }

    fun setFixQuality(value: Int) {
        update { it.copy(fixQuality = value, updatedAtMs = now()) }
    }

    fun setInct(value: InctResult?) {
        update { it.copy(inct = value, updatedAtMs = now()) }
    }

    private fun now(): Long = System.currentTimeMillis()
}
