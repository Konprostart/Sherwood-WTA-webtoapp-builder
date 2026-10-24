package com.sherwood.wta.core.wordpress

import android.content.Context
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.sample.SampleProjectExtractor
import com.sherwood.wta.core.sample.TypedSampleProject

object WordPressSampleManager {

    fun getSampleProjects(): List<TypedSampleProject> {
        val suffix = SampleProjectExtractor.getLanguageSuffix()
        return listOf(
            TypedSampleProject(
                id = "wp-blog$suffix",
                name = Strings.sampleWpBlogName,
                description = Strings.sampleWpBlogDesc,
                frameworkName = "WordPress",
                icon = "📝",
                tags = listOf("WP 6.4", Strings.sampleTagBlog, Strings.sampleTagSqlite),
                brandColor = 0xFF21759B
            ),
            TypedSampleProject(
                id = "wp-woocommerce$suffix",
                name = Strings.sampleWpWooName,
                description = Strings.sampleWpWooDesc,
                frameworkName = "WooCommerce",
                icon = "🛒",
                tags = listOf("WooCommerce", Strings.sampleTagEcommerce, Strings.sampleTagSqlite),
                brandColor = 0xFF96588A
            ),
            TypedSampleProject(
                id = "wp-portfolio$suffix",
                name = Strings.sampleWpPortfolioName,
                description = Strings.sampleWpPortfolioDesc,
                frameworkName = "WordPress",
                icon = "🎨",
                tags = listOf(Strings.sampleTagPortfolio, Strings.sampleTagResponsive, Strings.sampleTagSqlite),
                brandColor = 0xFF0073AA
            )
        )
    }

    suspend fun extractSampleProject(
        context: Context,
        projectId: String
    ): Result<String> = SampleProjectExtractor.extractSampleProject(context, projectId)
}
