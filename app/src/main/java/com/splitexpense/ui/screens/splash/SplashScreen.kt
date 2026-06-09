package com.splitexpense.ui.screens.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import com.splitexpense.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SplashBg     = Color(0xFF0D0D0D)
private val SplashBorder = Color(0xFF2E2E2E)
private val SplashText   = Color(0xFFF2F2F2)
private val SplashText3  = Color(0xFF5A5A5A)
// Icon cream — matches ic_launcher_background.xml
private val IconBg       = Color(0xFFF2E8DE)

// Gradient endpoints — same as vector drawable
private val GradStart = Color(0xFFFF8040)   // warm orange (top-left)
private val GradEnd   = Color(0xFFFF3070)   // hot pink   (bottom-right)

@Composable
fun SplashScreen(onComplete: () -> Unit) {

    val logoScale  = remember { Animatable(0.25f) }
    val logoAlpha  = remember { Animatable(0f) }
    val textAlpha  = remember { Animatable(0f) }
    val textOffset = remember { Animatable(32f) }
    val tagAlpha   = remember { Animatable(0f) }
    val dotsAlpha  = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(350)) }
        launch {
            logoScale.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
            )
        }
        delay(300)
        launch { textAlpha.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
        launch { textOffset.animateTo(0f, tween(450, easing = FastOutSlowInEasing)) }
        delay(200)
        launch { tagAlpha.animateTo(1f, tween(400)) }
        delay(200)
        launch { dotsAlpha.animateTo(1f, tween(300)) }
        delay(1400)
        onComplete()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(SplashBg),
        contentAlignment = Alignment.Center
    ) {
        // Subtle grid
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 32.dp.toPx()
            val gridColor = SplashBorder.copy(alpha = 0.4f)
            var x = 0f
            while (x <= size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 0.5f)
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 0.5f)
                y += step
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            // Logo tile — cream background matches launcher icon
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value)
                    .clip(RoundedCornerShape(28.dp))
                    .background(colorResource(id = R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "App Icon",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.height(36.dp))

            Text(
                text          = "FairShare",
                fontSize      = 42.sp,
                fontWeight    = FontWeight.Bold,
                color         = SplashText,
                letterSpacing = (-1).sp,
                textAlign     = TextAlign.Center,
                modifier      = Modifier
                    .alpha(textAlpha.value)
                    .padding(bottom = (textOffset.value * 0.5f).dp)
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text       = "Split bills. Not friendships.",
                fontSize   = 15.sp,
                fontWeight = FontWeight.Medium,
                color      = SplashText.copy(alpha = 0.75f),
                textAlign  = TextAlign.Center,
                modifier   = Modifier.alpha(tagAlpha.value)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp)
                .alpha(dotsAlpha.value),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FeaturePills()
        }
    }
}

@Composable
private fun FeaturePills() {
    val pills = listOf("Fair splits", "Settle up", "PDF export")
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        pills.forEach { label ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1C1C1C))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text          = label,
                    fontSize      = 10.sp,
                    color         = SplashText3,
                    fontWeight    = FontWeight.Normal,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

// ── Donut icon canvas — mirrors ic_launcher_foreground.xml exactly ────────────
// Same segment angles, same gradient (#FF8040 → #FF3070, top-left → bottom-right)
@Composable
private fun DonutIconCanvas(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val cx     = size.width  / 2f
        val cy     = size.height / 2f
        val outerR = size.minDimension * 0.43f
        val innerR = outerR * 0.555f
        val midR   = (outerR + innerR) / 2f
        val ringW  = outerR - innerR

        // Gradient brush — same proportional coordinates as the VD
        val brush = Brush.linearGradient(
            colors = listOf(GradStart, GradEnd),
            start  = Offset(0f, 0f),
            end    = Offset(size.width, size.height)
        )

        val arcStyle = Stroke(width = ringW, cap = StrokeCap.Butt)
        val arcTL    = Offset(cx - midR, cy - midR)
        val arcSize  = Size(midR * 2f, midR * 2f)

        // Seg 1: 315° → 134°  (179°, large right arc)
        drawArc(brush = brush, startAngle = 315f, sweepAngle = 179f,
            useCenter = false, topLeft = arcTL, size = arcSize, style = arcStyle)
        // Seg 2: 144° → 224°  (80°, bottom-left)
        drawArc(brush = brush, startAngle = 144f, sweepAngle = 80f,
            useCenter = false, topLeft = arcTL, size = arcSize, style = arcStyle)
        // Seg 3: 234° → 269°  (35°, left)
        drawArc(brush = brush, startAngle = 234f, sweepAngle = 35f,
            useCenter = false, topLeft = arcTL, size = arcSize, style = arcStyle)
        // Seg 4: 279° → 305°  (26°, top-left, small)
        drawArc(brush = brush, startAngle = 279f, sweepAngle = 26f,
            useCenter = false, topLeft = arcTL, size = arcSize, style = arcStyle)

        // Hole — cream matching the tile background
        drawCircle(color = IconBg, radius = innerR, center = Offset(cx, cy))

        // ₹ symbol with the same gradient via NativeCanvas LinearGradient shader
        val symPx = innerR * 1.1f
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize    = symPx
                textAlign   = android.graphics.Paint.Align.CENTER
                typeface    = android.graphics.Typeface.DEFAULT_BOLD
                shader      = android.graphics.LinearGradient(
                    cx, cy - symPx * 0.5f,
                    cx, cy + symPx * 0.5f,
                    intArrayOf(GradStart.toArgb(), GradEnd.toArgb()),
                    null,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            canvas.nativeCanvas.drawText("₹", cx, cy + symPx * 0.36f, paint)
        }
    }
}
