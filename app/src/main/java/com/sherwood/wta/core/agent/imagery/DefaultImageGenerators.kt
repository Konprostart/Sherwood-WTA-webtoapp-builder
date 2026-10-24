package com.sherwood.wta.core.agent.imagery

import android.content.Context

object DefaultImageGenerators {
    fun create(context: Context): ImageGeneratorRegistry =
        ImageGeneratorRegistry(
            listOf(
                GeminiImageGenerator(context),
                OpenAiImageGenerator(context),
            )
        )
}
