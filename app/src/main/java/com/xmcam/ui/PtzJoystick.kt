package com.xmcam.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.xmcam.protocol.PtzCmd
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Sectores de 45°, contados en sentido antihorario desde la derecha. */
private val SECTORS = listOf(
    PtzCmd.RIGHT, PtzCmd.RIGHT_UP, PtzCmd.UP, PtzCmd.LEFT_UP,
    PtzCmd.LEFT, PtzCmd.LEFT_DOWN, PtzCmd.DOWN, PtzCmd.RIGHT_DOWN
)
private const val TRAVEL = 0.64f   // recorrido del mando respecto al radio de la base
private const val DEAD = 0.16f     // zona muerta: dentro de ella la cámara está parada

private fun angDiff(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

/**
 * Joystick analógico para PTZ.
 *  - Dirección: 8 sectores con histéresis (no parpadea en los bordes).
 *  - Velocidad: proporcional a lo que se aleja el mando del centro (1..8, curva suave).
 *  - Al soltar: el mando vuelve al centro con un muelle y se avisa con `onMove(null, 0)` (la cámara se para).
 *  - Se dibuja todo en la fase de DIBUJO (sin recomponer la pantalla mientras se arrastra), así el vídeo no sufre.
 * [onMove] solo se llama cuando cambia la dirección o la velocidad.
 */
@Composable
fun PtzJoystick(modifier: Modifier = Modifier, onMove: (cmd: String?, step: Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val accent = cs.primary
    val baseHi = cs.surfaceVariant
    val baseLo = cs.surface
    val line = cs.outline
    val knobHi = cs.primaryContainer
    val knobLo = cs.primary

    var knob by remember { mutableStateOf(Offset.Zero) }      // posición normalizada del mando (-1..1)
    var sector by remember { mutableIntStateOf(-1) }
    var intensity by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val onMoveNow by rememberUpdatedState(onMove)
    val returnJob = remember { arrayOfNulls<Job>(1) }

    Canvas(
        modifier = modifier.aspectRatio(1f).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val c = Offset(size.width / 2f, size.height / 2f)
                val limit = min(size.width, size.height) / 2f * TRAVEL
                var lastCmd: String? = null
                var lastStep = 0
                var cur = -1

                returnJob[0]?.cancel()
                pressed = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                fun update(p: Offset) {
                    val d = p - c
                    val len = d.getDistance()
                    val n = min(len, limit) / limit
                    knob = if (len > 0f) d / len * n else Offset.Zero
                    intensity = if (n < DEAD) 0f else n
                    if (n < DEAD) {
                        cur = -1
                    } else {
                        var ang = Math.toDegrees(atan2((-d.y).toDouble(), d.x.toDouble()))
                        if (ang < 0) ang += 360.0
                        val ideal = (ang / 45.0).roundToInt() % 8
                        cur = if (cur >= 0 && angDiff(ang, cur * 45.0) < 28.0) cur else ideal
                    }
                    val cmd = if (cur >= 0) SECTORS[cur] else null
                    val t = ((n - DEAD) / (1f - DEAD)).coerceIn(0f, 1f)
                    val step = if (cmd == null) 0 else 1 + (t.pow(1.3f) * 7f).roundToInt()
                    if (cur != sector) {
                        if (cur >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        sector = cur
                    }
                    if (cmd != lastCmd || step != lastStep) {
                        lastCmd = cmd; lastStep = step
                        onMoveNow(cmd, step)
                    }
                }

                try {
                    update(down.position)
                    down.consume()
                    drag(down.id) { ch -> update(ch.position); ch.consume() }
                } finally {
                    // Soltar (o gesto cancelado): parar la cámara y devolver el mando al centro con un muelle.
                    pressed = false
                    sector = -1
                    intensity = 0f
                    onMoveNow(null, 0)
                    val from = knob
                    returnJob[0] = scope.launch {
                        animate(
                            typeConverter = Offset.VectorConverter, initialValue = from, targetValue = Offset.Zero,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                        ) { v, _ -> knob = v }
                    }
                }
            }
        }
    ) {
        val c = center
        val r = size.minDimension / 2f
        val kr = r * 0.27f
        val limit = r * TRAVEL
        val kp = c + knob * limit
        val act = sector
        val inten = intensity
        val pr = press

        // Base con degradado y borde
        drawCircle(brush = Brush.radialGradient(listOf(baseHi, baseLo), center = c, radius = r), radius = r, center = c)
        drawCircle(color = line.copy(alpha = 0.6f), radius = r - 1.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))

        // Sector activo (cuña) y arco de velocidad alrededor del borde
        if (act >= 0) {
            drawArc(
                color = accent.copy(alpha = 0.16f + 0.34f * inten), startAngle = -(act * 45f) - 22.5f, sweepAngle = 45f,
                useCenter = true, topLeft = c - Offset(r, r), size = Size(2 * r, 2 * r)
            )
        }
        if (inten > 0f) {
            val rr = r - 6.dp.toPx()
            drawArc(
                color = accent, startAngle = -90f, sweepAngle = 360f * inten, useCenter = false,
                topLeft = c - Offset(rr, rr), size = Size(2 * rr, 2 * rr),
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Anillo guía discontinuo y 8 flechas direccionales
        drawCircle(
            color = line.copy(alpha = 0.35f), radius = limit, center = c,
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 12f)))
        )
        for (i in 0 until 8) {
            val active = i == act
            val s = r * (if (active) 0.085f else 0.06f)
            rotate(degrees = -(i * 45f), pivot = c) {
                val x = c.x + r * 0.84f
                val tri = Path().apply {
                    moveTo(x + s, c.y); lineTo(x - s * 0.7f, c.y - s * 0.8f); lineTo(x - s * 0.7f, c.y + s * 0.8f); close()
                }
                drawPath(tri, if (active) accent else line.copy(alpha = 0.55f))
            }
        }
        drawCircle(color = line.copy(alpha = 0.5f), radius = 3.dp.toPx(), center = c)

        // Estela del mando, sombra y mando con degradado
        if (knob != Offset.Zero) {
            drawLine(
                color = accent.copy(alpha = 0.25f + 0.3f * inten), start = c, end = kp,
                strokeWidth = kr * 0.55f, cap = StrokeCap.Round
            )
        }
        val kR = kr * (1f + 0.10f * pr)
        drawCircle(color = Color.Black.copy(alpha = 0.22f), radius = kR, center = kp + Offset(0f, 4.dp.toPx()))
        drawCircle(
            brush = Brush.radialGradient(listOf(knobHi, knobLo), center = kp - Offset(kr * 0.3f, kr * 0.3f), radius = kr * 1.5f),
            radius = kR, center = kp
        )
        drawCircle(color = Color.White.copy(alpha = 0.5f), radius = kR * 0.55f, center = kp, style = Stroke(2.dp.toPx()))
    }
}
