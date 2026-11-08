package ua.olex.sonyanc

/** Foreground screens and short user-triggered actions own independent connection leases. */
internal class ConnectionUse {
    private var screens=0
    private var actions=0
    @get:Synchronized val foreground get()=screens>0
    @get:Synchronized val active get()=screens>0 || actions>0
    @Synchronized fun acquire(foreground:Boolean) {if(foreground) screens++ else actions++}
    @Synchronized fun release(foreground:Boolean) {
        if(foreground) screens=(screens-1).coerceAtLeast(0) else actions=(actions-1).coerceAtLeast(0)
    }
}
