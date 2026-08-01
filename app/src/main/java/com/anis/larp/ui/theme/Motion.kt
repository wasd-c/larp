package com.anis.larp.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object LarpMotion {
    fun <T> navigationSlideSpec() = tween<T>(
        durationMillis = 300,
        easing = FastOutSlowInEasing
    )

    fun <T> expressiveSpatialSpec() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> expressiveEffectsSpec() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
}
