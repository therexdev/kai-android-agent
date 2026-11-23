package com.kai.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class KaiGlyph { TALK, TASKS, MEMORY, CONNECTIONS, SETTINGS, MIC, SEND, PLUS, CLOSE, CHECK, ARROW, STOP, PLAY, DELETE, EDIT, SEARCH, SHIELD, SUN, MOON, CHEVRON, REFRESH, LINK, MORE, VOLUME }

@Composable
fun KaiIcon(glyph: KaiGlyph, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface, label: String? = null) {
    Canvas(modifier.size(22.dp).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)) {
        val scale = size.width / 24f
        withTransform({ scale(scale, scale, Offset.Zero) }) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x, y), Offset(x2, y2), 1.7f, StrokeCap.Round)
            fun path(vararg points: Float) {
                val p = Path().apply { moveTo(points[0], points[1]); var i=2; while(i < points.size) { lineTo(points[i], points[i+1]); i+=2 } }
                drawPath(p, tint, style = stroke)
            }
            when (glyph) {
                KaiGlyph.TALK -> { drawRoundRect(tint, Offset(3f, 3f), Size(18f, 15f), CornerRadius(5f, 5f), style=stroke); path(5f,18f,5f,22f,10f,18f); drawCircle(tint,1f,Offset(8f,10f)); drawCircle(tint,1f,Offset(12f,10f)); drawCircle(tint,1f,Offset(16f,10f)) }
                KaiGlyph.TASKS -> { drawRoundRect(tint, Offset(4f,3f),Size(16f,18f),CornerRadius(4f,4f),style=stroke); path(7f,9f,9f,11f,12f,7f); line(14f,9f,17f,9f); line(8f,16f,16f,16f) }
                KaiGlyph.MEMORY -> { drawRoundRect(tint,Offset(4f,3f),Size(16f,18f),CornerRadius(3f,3f),style=stroke); line(9f,3f,9f,21f); line(13f,8f,17f,8f); line(13f,12f,17f,12f) }
                KaiGlyph.CONNECTIONS -> { drawCircle(tint,3f,Offset(12f,5f),style=stroke); drawCircle(tint,3f,Offset(5f,18f),style=stroke); drawCircle(tint,3f,Offset(19f,18f),style=stroke); line(10f,8f,6f,15f);line(14f,8f,18f,15f);line(8f,18f,16f,18f) }
                KaiGlyph.SETTINGS -> { drawCircle(tint,7.5f,Offset(12f,12f),style=stroke);drawCircle(tint,2.8f,Offset(12f,12f),style=stroke); repeat(8){ rotate(it*45f,Offset(12f,12f)){line(12f,2f,12f,4f)} } }
                KaiGlyph.MIC -> { drawRoundRect(tint,Offset(9f,3f),Size(6f,12f),CornerRadius(3f,3f),style=stroke); drawArc(tint,0f,180f,false,Offset(5f,7f),Size(14f,12f),style=stroke); line(12f,19f,12f,22f);line(9f,22f,15f,22f) }
                KaiGlyph.SEND -> { path(12f,19f,12f,5f);path(6f,11f,12f,5f,18f,11f) }
                KaiGlyph.PLUS -> {line(5f,12f,19f,12f);line(12f,5f,12f,19f)}
                KaiGlyph.CLOSE -> {line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
                KaiGlyph.CHECK -> path(5f,12f,10f,17f,19f,7f)
                KaiGlyph.ARROW -> {line(4f,12f,20f,12f);path(14f,6f,20f,12f,14f,18f)}
                KaiGlyph.STOP -> drawRoundRect(tint,Offset(6f,6f),Size(12f,12f),CornerRadius(3f,3f))
                KaiGlyph.PLAY -> drawPath(Path().apply {moveTo(8f,4f);lineTo(20f,12f);lineTo(8f,20f);close()},tint)
                KaiGlyph.DELETE -> {path(6f,7f,7f,21f,17f,21f,18f,7f);line(4f,7f,20f,7f);path(9f,7f,9f,3f,15f,3f,15f,7f);line(10f,11f,10f,17f);line(14f,11f,14f,17f)}
                KaiGlyph.EDIT -> {path(4f,20f,5f,15f,17f,3f,21f,7f,9f,19f,4f,20f);line(14f,6f,18f,10f)}
                KaiGlyph.SEARCH -> {drawCircle(tint,6.5f,Offset(10f,10f),style=stroke);line(15f,15f,21f,21f)}
                KaiGlyph.SHIELD -> {drawPath(Path().apply {moveTo(12f,2f);lineTo(20f,5f);lineTo(20f,12f);quadraticBezierTo(20f,18f,12f,22f);quadraticBezierTo(4f,18f,4f,12f);lineTo(4f,5f);close()},tint,style=stroke);path(8f,12f,11f,15f,16f,9f)}
                KaiGlyph.SUN -> {drawCircle(tint,4f,Offset(12f,12f),style=stroke);repeat(8){rotate(it*45f,Offset(12f,12f)){line(12f,2f,12f,4f)}}}
                KaiGlyph.MOON -> drawPath(Path().apply {moveTo(16f,3f);cubicTo(1f,-1f,1f,22f,15f,21f);cubicTo(20f,20f,22f,16f,21f,13f);cubicTo(13f,17f,9f,7f,16f,3f)},tint,style=stroke)
                KaiGlyph.CHEVRON -> path(8f,5f,15f,12f,8f,19f)
                KaiGlyph.REFRESH -> {drawArc(tint,30f,290f,false,Offset(4f,4f),Size(16f,16f),style=stroke);path(16f,3f,20f,5f,18f,9f)}
                KaiGlyph.LINK -> {drawRoundRect(tint,Offset(3f,6f),Size(11f,7f),CornerRadius(4f,4f),style=stroke);drawRoundRect(tint,Offset(10f,11f),Size(11f,7f),CornerRadius(4f,4f),style=stroke);line(9f,11f,15f,13f)}
                KaiGlyph.MORE -> {repeat(3){drawCircle(tint,1.5f,Offset(5f+7f*it,12f))}}
                KaiGlyph.VOLUME -> {path(3f,9f,7f,9f,12f,5f,12f,19f,7f,15f,3f,15f,3f,9f);drawArc(tint,-60f,120f,false,Offset(7f,3f),Size(14f,18f),style=stroke)}
            }
        }
    }
}

/** Bundled cartoon rig; static thumbnails do not start an animation or a web renderer. */
@Composable
fun CharacterAvatar(
    character: KaiCharacter,
    voiceState: KaiVoiceState,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    activity: KaiCharacterActivity = voiceState.characterActivity(),
    speechAudible: Boolean = false,
    reducedMotion: Boolean = false,
    hapticsEnabled: Boolean = false,
    interactive: Boolean = true,
) {
    CharacterRig(character, activity, modifier, animated, speechAudible, reducedMotion, hapticsEnabled, interactive && animated)
}
