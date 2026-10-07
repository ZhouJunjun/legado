package io.legado.app.utils

import android.content.Context
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.annotation.AnimRes

fun loadAnimation(context: Context, @AnimRes id: Int): Animation {
    return AnimationUtils.loadAnimation(context, id)
}