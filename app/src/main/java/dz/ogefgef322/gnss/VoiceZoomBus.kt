package dz.ogefgef322.gnss
object VoiceZoomBus {
    data class Event(val meters: Double, val reset: Boolean)

    private val listeners = mutableSetOf<(Event) -> Unit>()

    @Volatile
    var lastEvent: Event? = null
        private set

    fun post(meters: Double, reset: Boolean) {
        val event = Event(meters, reset)
        lastEvent = event
        listeners.toList().forEach { it(event) }
    }

    fun register(listener: (Event) -> Unit) {
        listeners.add(listener)
        lastEvent?.let { listener(it) }
    }

    fun unregister(listener: (Event) -> Unit) {
        listeners.remove(listener)
    }
}
