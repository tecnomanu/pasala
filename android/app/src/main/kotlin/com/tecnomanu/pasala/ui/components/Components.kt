package com.tecnomanu.pasala.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.tecnomanu.pasala.R
import com.tecnomanu.pasala.ui.theme.BrandGradient
import com.tecnomanu.pasala.ui.theme.Orange
import com.tecnomanu.pasala.ui.theme.P
import com.tecnomanu.pasala.ui.theme.Violet

/** Ink background with two soft brand glows at the top. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = P.c
    val glow = if (c.dark) 0.22f else 0.13f
    Box(
        modifier
            .fillMaxSize()
            .background(c.ink)
            .drawBehind {
                val w = size.width
                drawCircle(
                    Brush.radialGradient(listOf(Orange.copy(alpha = glow), Color.Transparent), center = Offset(w * 0.05f, -w * 0.05f), radius = w * 0.85f),
                    radius = w * 0.85f, center = Offset(w * 0.05f, -w * 0.05f),
                )
                drawCircle(
                    Brush.radialGradient(listOf(Violet.copy(alpha = glow), Color.Transparent), center = Offset(w * 1.0f, w * 0.25f), radius = w * 0.9f),
                    radius = w * 0.9f, center = Offset(w * 1.0f, w * 0.25f),
                )
            },
        content = content,
    )
}

@Composable
fun BrandMark(size: Dp = 40.dp, corner: Dp = 14.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(BrandGradient),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_mark), null, modifier = Modifier.size(size * 0.7f))
    }
}

@Composable
fun PasalaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = P.c
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .border(1.dp, c.outline.copy(alpha = if (c.dark) 0.7f else 1f), RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = P.c.muted, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "btn")
    // Alpha goes into the colours, not a graphics layer: the layer version sometimes
    // left the gradient undrawn until the next recomposition.
    Box(
        modifier
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .drawBehind { drawRect(Brush.horizontalGradient(listOf(Orange.copy(alpha = alpha), Violet.copy(alpha = alpha)))) }
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 20.dp)) {
                icon?.invoke()
                if (icon != null) Box(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.55f + 0.45f * alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = P.c
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(c.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = c.text)
    }
}

fun platformIcon(platform: String): ImageVector = when (platform) {
    "mac" -> Icons.Rounded.Laptop
    "android" -> Icons.Rounded.PhoneAndroid
    "ios" -> Icons.Rounded.PhoneIphone
    else -> Icons.Rounded.Computer
}

@Composable
fun PeerAvatar(platform: String, online: Boolean, size: Dp = 52.dp) {
    val c = P.c
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (online) BrandGradient else Brush.linearGradient(listOf(c.surfaceHigh, c.surfaceHigh))),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(size - 4.dp).clip(CircleShape).background(c.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(platformIcon(platform), null, tint = if (online) c.text else c.muted, modifier = Modifier.size(size * 0.46f))
            }
        }
        if (online) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.3f).clip(CircleShape).background(c.surface).padding(2.5.dp),
            ) { Box(Modifier.fillMaxSize().clip(CircleShape).background(c.success)) }
        }
    }
}

@Composable
fun PulseDot(color: Color, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, 2.6f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "s")
    val a by t.animateFloat(0.55f, 0f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Box(Modifier.size(size * 2.6f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).scale(s).alpha(a).clip(CircleShape).background(color))
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 4.dp, track: Color = Color.White.copy(alpha = 0.18f)) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "ring")
    Canvas(modifier) {
        val sw = stroke.toPx()
        val inset = sw / 2
        val arcSize = Size(size.width - sw, size.height - sw)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
        drawArc(
            Brush.sweepGradient(listOf(Orange, Violet, Orange)), -90f, 360f * p, false,
            Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round),
        )
    }
}

fun fileIcon(mime: String, name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime == "application/pdf" || ext == "pdf" -> Icons.Rounded.PictureAsPdf
        mime.startsWith("audio/") -> Icons.Rounded.Audiotrack
        mime.startsWith("text/") || ext in setOf("doc", "docx", "txt", "md", "rtf", "odt", "pages") -> Icons.Rounded.Description
        ext in setOf("zip", "rar", "7z", "gz", "tar", "apk") -> Icons.Rounded.FolderZip
        else -> Icons.Rounded.InsertDriveFile
    }
}

/** Square thumbnail: real image/video frame when possible, otherwise a type tile. */
@Composable
fun FileThumb(model: Any?, mime: String, name: String, modifier: Modifier = Modifier, corner: Dp = 18.dp, showLabel: Boolean = false) {
    val c = P.c
    val media = mime.startsWith("image/") || mime.startsWith("video/")
    Box(modifier.clip(RoundedCornerShape(corner)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        if (media && model != null) {
            AsyncImage(model = model, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (mime.startsWith("video/")) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Icon(fileIcon(mime, name), null, tint = Orange, modifier = Modifier.size(if (showLabel) 30.dp else 24.dp))
                if (showLabel) {
                    Text(
                        name.substringAfterLast('.', "").uppercase().take(5),
                        style = MaterialTheme.typography.labelSmall, color = c.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyNote(title: String, body: String, modifier: Modifier = Modifier) {
    val c = P.c
    Column(modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = c.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = color) }
}
