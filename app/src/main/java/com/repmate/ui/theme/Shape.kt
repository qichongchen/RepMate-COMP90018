package com.repmate.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * RepMate's shared corner-radius tokens.
 *
 * NOTE: components should reach for `MaterialTheme.shapes.medium` (16dp -- the radius used for
 * buttons and cards) rather than writing their own `RoundedCornerShape(...)`, so every rounded
 * surface in the app shares one radius and a future design tweak is a one-line change here
 * instead of a find-and-replace across every component file.
 */
val RepMateShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
