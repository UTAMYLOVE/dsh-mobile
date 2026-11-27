package app.dshmobile.ui.agency.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.agency.core.engine.MoveIcon

/** A stick figure: the head's centre and the limbs as segments, in a unit square (0..1, y down). */
internal data class Pose(val head: Offset, val limbs: List<Pair<Offset, Offset>>, val prop: Pair<Offset, Offset>? = null)

private fun o(x: Float, y: Float) = Offset(x, y)

/** Chained segments through the points (two to five). */
private fun chain(a: Offset, b: Offset, c: Offset? = null, d: Offset? = null, e: Offset? = null): List<Pair<Offset, Offset>> =
    listOfNotNull(a, b, c, d, e).zipWithNext()

/**
 * The built-in pictures of the moves (T18): simple stick figures drawn by code, so nothing is ever fetched from the network.
 * Each is only a hint of the pose; the words under it say how.
 */
internal object Poses {
    private val floor = o(0.05f, 0.9f) to o(0.95f, 0.9f)

    fun of(icon: MoveIcon): Pose = when (icon) {
        MoveIcon.HALF_BOAT -> Pose(o(.25f, .3f), chain(o(.3f, .4f), o(.45f, .72f), o(.6f, .45f), o(.8f, .55f)) + chain(o(.3f, .4f), o(.6f, .38f)), floor)
        MoveIcon.HALF_SITUP -> Pose(o(.22f, .56f), chain(o(.3f, .63f), o(.55f, .82f), o(.7f, .58f), o(.86f, .84f)) + chain(o(.3f, .63f), o(.46f, .56f)), floor)
        MoveIcon.KNEE_HUG -> Pose(o(.32f, .3f), chain(o(.35f, .42f), o(.45f, .8f), o(.57f, .48f), o(.66f, .75f)) + chain(o(.35f, .42f), o(.57f, .5f)), floor)
        MoveIcon.BICYCLE -> Pose(o(.2f, .62f), chain(o(.3f, .68f), o(.55f, .8f), o(.62f, .55f), o(.74f, .66f)) + chain(o(.55f, .8f), o(.92f, .7f)) + chain(o(.3f, .68f), o(.42f, .56f)), floor)
        MoveIcon.LEG_RAISE -> Pose(o(.14f, .78f), chain(o(.24f, .82f), o(.5f, .84f), o(.76f, .46f)) + chain(o(.24f, .82f), o(.42f, .86f)), floor)
        MoveIcon.SIDE_PLANK_LEFT -> Pose(o(.22f, .44f), chain(o(.3f, .52f), o(.86f, .86f)) + chain(o(.3f, .52f), o(.3f, .84f), o(.4f, .86f)), floor)
        MoveIcon.SIDE_PLANK_RIGHT -> Pose(o(.78f, .44f), chain(o(.7f, .52f), o(.14f, .86f)) + chain(o(.7f, .52f), o(.7f, .84f), o(.6f, .86f)), floor)
        MoveIcon.PUSH_UP -> Pose(o(.2f, .46f), chain(o(.3f, .52f), o(.56f, .62f), o(.88f, .84f)) + chain(o(.3f, .52f), o(.3f, .86f)), floor)
        MoveIcon.SQUAT -> Pose(o(.36f, .2f), chain(o(.38f, .32f), o(.42f, .6f), o(.62f, .62f), o(.6f, .88f)) + chain(o(.38f, .34f), o(.68f, .36f)), floor)
        MoveIcon.PLANK -> Pose(o(.2f, .58f), chain(o(.3f, .62f), o(.88f, .84f)) + chain(o(.3f, .62f), o(.3f, .86f), o(.44f, .86f)), floor)
        MoveIcon.ROW -> Pose(o(.2f, .42f), chain(o(.3f, .46f), o(.56f, .55f), o(.52f, .72f), o(.56f, .88f)) + chain(o(.3f, .48f), o(.38f, .64f)), o(.32f, .66f) to o(.46f, .7f))
        MoveIcon.BRIDGE -> Pose(o(.12f, .8f), chain(o(.2f, .84f), o(.55f, .58f), o(.72f, .52f), o(.78f, .86f)) + chain(o(.2f, .84f), o(.36f, .88f)), floor)
        MoveIcon.REST -> Pose(o(.5f, .2f), chain(o(.5f, .32f), o(.5f, .6f), o(.42f, .88f)) + chain(o(.5f, .6f), o(.58f, .88f)) + chain(o(.5f, .36f), o(.38f, .56f)) + chain(o(.5f, .36f), o(.62f, .56f)), floor)
        MoveIcon.HIP -> Pose(o(.5f, .2f), chain(o(.5f, .32f), o(.5f, .6f), o(.72f, .62f), o(.76f, .88f)) + chain(o(.5f, .6f), o(.32f, .86f), o(.14f, .88f)), floor)
        MoveIcon.LEG -> Pose(o(.7f, .54f), chain(o(.6f, .6f), o(.34f, .84f), o(.88f, .86f)) + chain(o(.6f, .62f), o(.82f, .74f)), floor)
        MoveIcon.CHEST -> Pose(o(.55f, .2f), chain(o(.55f, .32f), o(.55f, .6f), o(.48f, .88f)) + chain(o(.55f, .6f), o(.62f, .88f)) + chain(o(.55f, .36f), o(.2f, .34f)), o(.18f, .1f) to o(.18f, .9f))
        MoveIcon.BACK -> Pose(o(.28f, .78f), chain(o(.38f, .74f), o(.7f, .66f), o(.62f, .86f), o(.86f, .88f)) + chain(o(.38f, .76f), o(.12f, .86f)), floor)
        MoveIcon.JUMPING_JACK -> Pose(o(.5f, .16f), chain(o(.5f, .28f), o(.5f, .58f), o(.3f, .88f)) + chain(o(.5f, .58f), o(.7f, .88f)) + chain(o(.5f, .32f), o(.28f, .12f)) + chain(o(.5f, .32f), o(.72f, .12f)), floor)
        MoveIcon.BURPEE -> Pose(o(.2f, .5f), chain(o(.3f, .56f), o(.58f, .64f), o(.88f, .84f)) + chain(o(.3f, .56f), o(.3f, .86f)), floor)
        MoveIcon.SQUAT_JUMP -> Pose(o(.5f, .14f), chain(o(.5f, .26f), o(.5f, .52f), o(.4f, .66f), o(.46f, .8f)) + chain(o(.5f, .52f), o(.62f, .66f), o(.56f, .8f)) + chain(o(.5f, .3f), o(.34f, .14f)) + chain(o(.5f, .3f), o(.66f, .14f)), floor)
    }
}

/** The picture of [icon] in [color] (a hint of the pose, drawn by code). */
@Composable
fun MovePictogram(icon: MoveIcon, color: Color, modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val pose = Poses.of(icon)
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * .045f
        fun at(p: Offset) = Offset(p.x * w, p.y * h)
        pose.prop?.let { (a, b) -> drawLine(color.copy(alpha = .35f), at(a), at(b), stroke * .7f, StrokeCap.Round) }
        pose.limbs.forEach { (a, b) -> drawLine(color, at(a), at(b), stroke, StrokeCap.Round) }
        drawCircle(color, radius = w * .065f, center = at(pose.head))
    }
}
