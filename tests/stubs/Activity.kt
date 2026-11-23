package androidx.activity

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

open class ComponentActivity : android.app.Activity(), LifecycleOwner {
    override val lifecycle: Lifecycle get() = TODO()
}
