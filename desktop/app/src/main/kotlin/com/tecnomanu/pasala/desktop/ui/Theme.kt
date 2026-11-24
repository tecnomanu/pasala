package com.tecnomanu.pasala.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tecnomanu.pasala.desktop.res.Res
import com.tecnomanu.pasala.desktop.res.jakarta_bold
import com.tecnomanu.pasala.desktop.res.jakarta_extra_bold
import com.tecnomanu.pasala.desktop.res.jakarta_medium
import com.tecnomanu.pasala.desktop.res.jakarta_regular
import com.tecnomanu.pasala.desktop.res.jakarta_semi_bold
import com.tecnomanu.pasala.desktop.res.pasala_mark
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Brand palette shared with the Mac and Android apps (AGENTS.md rule 6). */
object P {
    val ink = Color(0xFF0E0F1A)
    val surface = Color(0xFF181A2A)
    val surfaceHigh = Color(0xFF23263A)
    val line = Color(0x22FFFFFF)
    val text = Color(0xFFF4F3FF)
    val dim = Color(0xFF9A9CB8)
    val orange = Color(0xFFFF6B3D)
    val violet = Color(0xFF7C5CFF)
    val mint = Color(0xFF3DFFB0)
    val brand = Brush.linearGradient(listOf(orange, violet))
}

@Composable
fun jakarta(): FontFamily = FontFamily(
    Font(Res.font.jakarta_regular, FontWeight.Normal),
    Font(Res.font.jakarta_medium, FontWeight.Medium),
    Font(Res.font.jakarta_semi_bold, FontWeight.SemiBold),
    Font(Res.font.jakarta_bold, FontWeight.Bold),
    Font(Res.font.jakarta_extra_bold, FontWeight.ExtraBold),
)

@Composable
fun T(
    text: String,
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = P.text,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) = BasicText(
    text, modifier,
    style = TextStyle(fontFamily = jakarta(), fontSize = size, fontWeight = weight, color = color),
    maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

@Composable
fun Mark(size: Dp = 20.dp, modifier: Modifier = Modifier) =
    Image(painterResource(Res.drawable.pasala_mark), null, modifier.size(size))

/** The prominent action: brand gradient. Secondary ones are flat. */
@Composable
fun GlowButton(title: String, modifier: Modifier = Modifier, prominent: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .alpha(if (enabled) (if (hovered) 1f else 0.94f) else 0.4f)
            .clip(shape)
            .then(if (prominent) Modifier.background(P.brand) else Modifier.background(if (hovered) P.surfaceHigh else P.surface).border(1.dp, P.line, shape))
            .hoverable(src)
            .clickable(enabled = enabled, interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { T(title, 13.sp, FontWeight.Bold, maxLines = 1) }
}

@Composable
fun Chip(title: String, selected: Boolean, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(if (selected) P.surfaceHigh else P.surface)
            .border(1.dp, if (selected) P.violet else P.line, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        T(title, 12.5.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

/** Round badge for a device: first letter of its platform, mint ring when reachable. */
@Composable
fun DeviceGlyph(platform: String, online: Boolean, size: Dp = 30.dp) {
    val letter = when (platform) { "mac" -> "M"; "ios" -> "i"; "android" -> "A"; "windows" -> "W"; "linux" -> "L"; else -> "?" }
    Box(
        Modifier.size(size).clip(CircleShape).background(P.surfaceHigh)
            .border(2.dp, if (online) P.mint else P.line, CircleShape),
        contentAlignment = Alignment.Center,
    ) { T(letter, (size.value * 0.42f).sp, FontWeight.ExtraBold, if (online) P.text else P.dim) }
}

/** Small decoded previews for images, cached by path. */
object Thumbs {
    private val cache = ConcurrentHashMap<String, ImageBitmap?>()
    fun get(path: String): ImageBitmap? = cache.getOrPut(path) {
        runCatching {
            val f = File(path)
            if (!f.isFile || f.length() > 40L shl 20) return@runCatching null
            val img = SkiaImage.makeFromEncoded(f.readBytes())
            val scale = 320f / maxOf(img.width, img.height)
            if (scale >= 1f) img.toComposeImageBitmap() else {
                val bmp = org.jetbrains.skia.Bitmap()
                bmp.allocN32Pixels((img.width * scale).toInt().coerceAtLeast(1), (img.height * scale).toInt().coerceAtLeast(1))
                img.scalePixels(bmp.peekPixels()!!, org.jetbrains.skia.SamplingMode.LINEAR, false)
                org.jetbrains.skia.Image.makeFromBitmap(bmp).toComposeImageBitmap()
            }
        }.getOrNull()
    }
}

/** Preview of a file: the picture for images, a label with its extension otherwise. */
@Composable
fun FileThumb(path: String, mime: String, name: String, size: Dp, corner: Dp = 12.dp) {
    val shape = RoundedCornerShape(corner)
    Box(Modifier.size(size).clip(shape).background(P.surfaceHigh), contentAlignment = Alignment.Center) {
        val bmp = if (mime.startsWith("image/")) remember(path) { Thumbs.get(path) } else null
        if (bmp != null) Image(bmp, null, Modifier.size(size), contentScale = ContentScale.Crop)
        else if (mime == com.tecnomanu.pasala.data.TEXT_MIME) T("“”", (size.value * 0.4f).sp, FontWeight.ExtraBold, P.violet)
        else T(name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "•" }, (size.value * 0.22f).sp, FontWeight.ExtraBold, P.dim)
    }
}
