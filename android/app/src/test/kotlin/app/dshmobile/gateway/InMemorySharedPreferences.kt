package app.dshmobile.gateway

import android.content.SharedPreferences

/** Plain-JVM SharedPreferences: edits become visible on apply()/commit() like the real thing. */
class InMemorySharedPreferences : SharedPreferences {
    private val data = HashMap<String, Any?>()

    @Synchronized override fun getAll(): MutableMap<String, *> = HashMap(data)
    @Synchronized override fun getString(key: String, defValue: String?): String? = data[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        data[key] as MutableSet<String>? ?: defValues
    @Synchronized override fun getInt(key: String, defValue: Int): Int = data[key] as Int? ?: defValue
    @Synchronized override fun getLong(key: String, defValue: Long): Long = data[key] as Long? ?: defValue
    @Synchronized override fun getFloat(key: String, defValue: Float): Float = data[key] as Float? ?: defValue
    @Synchronized override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as Boolean? ?: defValue
    @Synchronized override fun contains(key: String): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            synchronized(this@InMemorySharedPreferences) {
                if (clear) data.clear()
                removed.forEach { data.remove(it) }
                data.putAll(pending)
            }
            return true
        }
        override fun apply() { commit() }
    }
}
