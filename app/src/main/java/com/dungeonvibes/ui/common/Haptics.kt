package com.dungeonvibes.ui.common

import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/* Haptics for lighting moments (they follow the phone's touch-feedback setting) */

/** A crisp click when a scene is applied. */
val SceneHaptic = HapticFeedbackType.Confirm

/** A heavier thump when an FX fires, so it feels different from a scene. */
val FxHaptic = HapticFeedbackType.LongPress

/** A light tick as a finger lands on a scene tile, before the Confirm of applying it. */
val PressHaptic = HapticFeedbackType.SegmentTick
