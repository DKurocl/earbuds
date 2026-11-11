package ua.olex.sonyanc

/**
 * Who needs the control channel open: foreground screens, short user-triggered actions, and
 * the earbuds' presence while Earbuds is their companion app (LiveLink). Independent leases.
 */
internal class ConnectionUse {
    private var screens=0
    private var actions=0
    private var present=false
    @get:Synchronized val foreground get()=screens>0
    @get:Synchronized val active get()=screens>0 || actions>0 || present
    @get:Synchronized val staying get()=present
    @Synchronized fun acquire(foreground:Boolean) {if(foreground) screens++ else actions++}
    @Synchronized fun release(foreground:Boolean) {
        if(foreground) screens=(screens-1).coerceAtLeast(0) else actions=(actions-1).coerceAtLeast(0)
    }
    @Synchronized fun stay(on:Boolean) { present=on }
}
