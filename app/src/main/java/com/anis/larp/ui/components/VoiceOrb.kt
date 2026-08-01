package com.anis.larp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.anis.larp.ui.theme.LarpMotion

@Composable
fun VoiceOrb(
    isActive: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    animateIdle: Boolean = true,
    accessibilityDescription: String = "Orbe vocal"
) {
    val idleScale = if (animateIdle) {
        val idleTransition = rememberInfiniteTransition(label = "idle orb")
        val scale by idleTransition.animateFloat(
            initialValue = 0.985f,
            targetValue = 1.015f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "idle orb scale"
        )
        scale
    } else {
        1f
    }
    val containerColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = LarpMotion.expressiveEffectsSpec(),
        label = "orb container"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = LarpMotion.expressiveEffectsSpec(),
        label = "orb content"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(if (isActive || !animateIdle) 1f else idleScale)
            .background(containerColor, CircleShape)
            .semantics {
                contentDescription = accessibilityDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.58f)
                .background(
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(size * 0.3f),
                tint = contentColor
            )
        }
    }
}
