package app.dshmobile.system

import android.content.SharedPreferences

/** Which first-launch steps the user has finished or skipped (SharedPreferences `dshm_onboarding`). */
class OnboardingState(private val prefs: SharedPreferences) {
    enum class Step(val skippable: Boolean) {
        NOTIFICATIONS(true),
        BATTERY(true),
        PHANTOM(true),
        DEEPSEEK_KEY(false),
        ROOTFS(false),
    }

    fun isDone(step: Step): Boolean = prefs.getString(key(step), null) != null

    fun markDone(step: Step) {
        prefs.edit().putString(key(step), DONE).apply()
    }

    /** Only the optional steps can be skipped; the DeepSeek key and the rootfs are required. */
    fun markSkipped(step: Step) {
        require(step.skippable) { "$step cannot be skipped" }
        prefs.edit().putString(key(step), SKIPPED).apply()
    }

    /** Forgets a step's result, e.g. when the rootfs it unpacked is gone or the stored key was lost. */
    fun clear(step: Step) {
        prefs.edit().remove(key(step)).apply()
    }

    /** First step, in order, that is neither done nor skipped; null when none is left. */
    fun nextStep(): Step? = Step.values().firstOrNull { !isDone(it) }

    /** Every step finished or skipped, and (as the caller verified just now) the rootfs is installed and a key is stored. */
    fun isComplete(rootfsInstalled: Boolean, keyStored: Boolean): Boolean =
        nextStep() == null && rootfsInstalled && keyStored

    private fun key(step: Step) = "step_${step.name}"

    companion object {
        const val PREFS = "dshm_onboarding"
        private const val DONE = "done"
        private const val SKIPPED = "skipped"
    }
}
