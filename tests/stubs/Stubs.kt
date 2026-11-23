package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope

interface LifecycleObserver
abstract class Lifecycle { abstract fun addObserver(o: LifecycleObserver); enum class Event { ON_CREATE, ON_RESUME, ON_PAUSE, ON_DESTROY } }
interface LifecycleOwner { val lifecycle: Lifecycle }
val LifecycleOwner.lifecycleScope: CoroutineScope get() = TODO()
