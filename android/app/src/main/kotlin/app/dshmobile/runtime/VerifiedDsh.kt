package app.dshmobile.runtime

/**
 * The dsh versions this app release has verified the protocol against. A Kotlin constant on purpose (not `rootfs/versions.env`:
 * changing that file changes the rootfs hash and forces every install to unpack the rootfs again). Adding a version means
 * verifying the protocol first, then changing this list and releasing the app.
 */
object VerifiedDsh {
    val VERSIONS: List<String> = listOf("0.2.1-rc.5")

    fun isVerified(v: String?): Boolean = v != null && v in VERSIONS

    fun newest(): String = VERSIONS.last()
}

/** The pure rules shared by Settings' update and rollback and by the crash notification's rollback. */
object VerifiedUpdatePolicy {
    /** The version an update installs; null when [current] is already the newest verified one. An unverified (or unknown) current is switched back to it. */
    fun target(current: String?): String? = VerifiedDsh.newest().takeIf { it != current }

    /** The version a rollback may go back to: [previous] only when it is verified. */
    fun previous(previous: String?): String? = previous?.takeIf(VerifiedDsh::isVerified)
}
