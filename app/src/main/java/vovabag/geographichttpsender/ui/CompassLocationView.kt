package vovabag.geographichttpsender.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vovabag.geographichttpsender.ui.theme.GeographicHttpSenderTheme

@Composable
fun CompassLocationView(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFA6CBE2)),
        contentAlignment = Alignment.Center
    ) {
        CompassGraphic(
            modifier = Modifier
                .fillMaxSize(0.9f)
                .aspectRatio(1f)
        )
        LocationPin(
            modifier = Modifier
                .fillMaxSize(0.32f)
                .aspectRatio(1f)
        )
    }
}

@Composable
private fun CompassGraphic(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2
        val strokeWidth = 2.dp.toPx()
        val color = Color.White.copy(alpha = 0.9f)

        // 3 Concentric Circles
        drawCircle(color, radius * 0.42f, center, style = Stroke(strokeWidth))
        drawCircle(color, radius * 0.72f, center, style = Stroke(strokeWidth))
        drawCircle(color, radius * 1.0f, center, style = Stroke(strokeWidth))

        // 8 Main directional lines from center
        val angles = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
        angles.forEach { angle ->
            val rad = Math.toRadians(angle.toDouble()).toFloat()
            val length = if (angle % 90 == 0f) radius else radius * 0.72f
            val end = Offset(
                center.x + length * Math.cos(rad.toDouble()).toFloat(),
                center.y + length * Math.sin(rad.toDouble()).toFloat()
            )
            drawLine(color, center, end, strokeWidth)
        }

        // Spikes
        val baseRadius = radius * 0.42f
        
        // Large Spikes (N, E, S, W) - 0, 90, 180, 270 degrees
        val largeAngles = listOf(0f, 90f, 180f, 270f)
        largeAngles.forEach { angle ->
            drawCompassSpike(center, radius, baseRadius, angle, 20f, color, strokeWidth)
        }

        // Small Spikes (NE, SE, SW, NW) - 45, 135, 225, 315 degrees
        val smallAngles = listOf(45f, 135f, 225f, 315f)
        smallAngles.forEach { angle ->
            drawCompassSpike(center, radius * 0.72f, baseRadius, angle, 15f, color, strokeWidth)
        }
    }
}

private fun DrawScope.drawCompassSpike(
    center: Offset,
    tipRadius: Float,
    baseRadius: Float,
    angle: Float,
    spread: Float,
    color: Color,
    strokeWidth: Float
) {
    val tipRad = Math.toRadians(angle.toDouble()).toFloat()
    val tip = Offset(
        center.x + tipRadius * Math.cos(tipRad.toDouble()).toFloat(),
        center.y + tipRadius * Math.sin(tipRad.toDouble()).toFloat()
    )

    val leftRad = Math.toRadians((angle - spread).toDouble()).toFloat()
    val leftBase = Offset(
        center.x + baseRadius * Math.cos(leftRad.toDouble()).toFloat(),
        center.y + baseRadius * Math.sin(leftRad.toDouble()).toFloat()
    )

    val rightRad = Math.toRadians((angle + spread).toDouble()).toFloat()
    val rightBase = Offset(
        center.x + baseRadius * Math.cos(rightRad.toDouble()).toFloat(),
        center.y + baseRadius * Math.sin(rightRad.toDouble()).toFloat()
    )

    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(leftBase.x, leftBase.y)
        moveTo(tip.x, tip.y)
        lineTo(rightBase.x, rightBase.y)
    }
    drawPath(path, color, style = Stroke(strokeWidth))
}

@Composable
private fun LocationPin(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerX = w / 2
        val centerY = h * 0.38f
        val radius = w * 0.35f
        
        // Shadow (Oval)
        drawOval(
            color = Color.Black.copy(alpha = 0.12f),
            topLeft = Offset(centerX - radius * 0.4f, h * 0.85f),
            size = androidx.compose.ui.geometry.Size(radius * 0.8f, radius * 0.2f)
        )

        // Main Pin Shape
        val fullPath = Path().apply {
            moveTo(centerX, h * 0.95f) // The bottom tip
            cubicTo(
                centerX - radius * 1.1f, h * 0.7f,
                centerX - radius, centerY + radius * 0.5f,
                centerX - radius, centerY
            )
            arcTo(
                rect = Rect(centerX - radius, centerY - radius, centerX + radius, centerY + radius),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
            cubicTo(
                centerX + radius, centerY + radius * 0.5f,
                centerX + radius * 1.1f, h * 0.7f,
                centerX, h * 0.95f
            )
            close()
        }

        // Left half (white)
        val leftHalf = Path().apply {
            moveTo(centerX, h * 0.95f)
            cubicTo(
                centerX - radius * 1.1f, h * 0.7f,
                centerX - radius, centerY + radius * 0.5f,
                centerX - radius, centerY
            )
            arcTo(
                rect = Rect(centerX - radius, centerY - radius, centerX + radius, centerY + radius),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            lineTo(centerX, centerY)
            close()
        }

        // Right half (shaded)
        val rightHalf = Path().apply {
            moveTo(centerX, h * 0.95f)
            cubicTo(
                centerX + radius, centerY + radius * 0.5f,
                centerX + radius * 1.1f, h * 0.7f,
                centerX, h * 0.95f
            )
            lineTo(centerX, centerY - radius)
            arcTo(
                rect = Rect(centerX - radius, centerY - radius, centerX + radius, centerY + radius),
                startAngleDegrees = 270f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            close()
        }

        drawPath(leftHalf, Color.White)
        drawPath(rightHalf, Color(0xFFF0F0F0))
        
        // Refined shadow/depth effect: vertical line in the middle
        drawLine(
            color = Color.Black.copy(alpha = 0.05f),
            start = Offset(centerX, centerY - radius),
            end = Offset(centerX, h * 0.95f),
            strokeWidth = 1.dp.toPx()
        )

        // Hole in the middle
        drawCircle(
            color = Color(0xFFA6CBE2),
            radius = radius * 0.42f,
            center = Offset(centerX, centerY)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun CompassLocationViewPreview() {
    GeographicHttpSenderTheme {
        Box(modifier = Modifier.size(400.dp)) {
            CompassLocationView()
        }
    }
}
