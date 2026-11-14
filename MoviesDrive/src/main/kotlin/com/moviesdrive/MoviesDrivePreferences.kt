package com.moviesdrive

import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.mainPageOf

object MoviesDrivePreferences {
    private const val PREFS_NAME = "moviesdrive_plugin_prefs"
    private const val KEY_CATEGORIES = "catalogue_categories"
    private val mapper = jacksonObjectMapper()

    val DEFAULT_CATEGORIES = listOf(
        CategoryItem("Latest Releases", "home", isEnabled = true),
        CategoryItem("4K Ultra HD", "2160p-4k", isEnabled = true),
        CategoryItem("Bollywood Movies", "bollywood", isEnabled = true),
        CategoryItem("Hollywood", "hollywood", isEnabled = true),
        CategoryItem("Hindi Dubbed", "hindi-dubbed", isEnabled = true),
        CategoryItem("Dual Audio", "dual-audio", isEnabled = true),
        CategoryItem("South Hindi Dubbed", "south", isEnabled = true),
        CategoryItem("Web Series", "web", isEnabled = true),
        CategoryItem("Netflix", "netflix", isEnabled = true),
        CategoryItem("Amazon Prime Video", "amzn-prime-video", isEnabled = true),
        CategoryItem("Korean (K-Drama)", "k-drama", isEnabled = true),
        CategoryItem("Anime", "anime", isEnabled = true),
        CategoryItem("Action", "action", isEnabled = true),
        CategoryItem("Comedy", "comedy", isEnabled = true),
        CategoryItem("Drama", "drama", isEnabled = true),
        CategoryItem("Horror", "horror", isEnabled = true),
        CategoryItem("Thriller & Crime", "triller", isEnabled = true),
        CategoryItem("Romance", "romance", isEnabled = true),
        CategoryItem("Animation", "animation", isEnabled = true),
        CategoryItem("Sci-Fi", "sifi", isEnabled = true),
        CategoryItem("Adventure", "adventure", isEnabled = true)
    )

    fun getCategories(context: Context?): List<CategoryItem> {
        if (context == null) return DEFAULT_CATEGORIES
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CATEGORIES, null) ?: return DEFAULT_CATEGORIES
        return try {
            mapper.readValue<List<CategoryItem>>(json)
        } catch (e: Exception) {
            DEFAULT_CATEGORIES
        }
    }

    fun saveCategories(context: Context?, categories: List<CategoryItem>) {
        if (context == null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val json = mapper.writeValueAsString(categories)
            prefs.edit().putString(KEY_CATEGORIES, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleCategory(context: Context?, slug: String, isEnabled: Boolean) {
        val list = getCategories(context).toMutableList()
        val index = list.indexOfFirst { it.slug == slug }
        if (index != -1) {
            list[index] = list[index].copy(isEnabled = isEnabled)
            saveCategories(context, list)
        }
    }

    fun addCustomCategory(context: Context?, name: String, slug: String): Boolean {
        val cleanName = name.trim()
        val cleanSlug = slug.trim().lowercase().removePrefix("/category/").removePrefix("/").removeSuffix("/")
        if (cleanName.isBlank() || cleanSlug.isBlank()) return false

        val list = getCategories(context).toMutableList()
        if (list.any { it.slug == cleanSlug }) return false

        list.add(CategoryItem(cleanName, cleanSlug, isEnabled = true, isCustom = true))
        saveCategories(context, list)
        return true
    }

    fun removeCategory(context: Context?, slug: String) {
        val list = getCategories(context).toMutableList()
        list.removeAll { it.slug == slug }
        saveCategories(context, list)
    }

    fun resetToDefaults(context: Context?) {
        saveCategories(context, DEFAULT_CATEGORIES)
    }

    fun getActiveCategories(context: Context?, baseUrl: String = "https://new3.moviesdrive.cyou"): List<MainPageData> {
        val categories = getCategories(context).filter { it.isEnabled }
        val list = if (categories.isEmpty()) DEFAULT_CATEGORIES else categories

        val cleanBase = baseUrl.removeSuffix("/")
        val pairs = list.map { item ->
            val data = if (item.slug.equals("home", ignoreCase = true)) {
                cleanBase
            } else if (item.slug.startsWith("http://") || item.slug.startsWith("https://")) {
                item.slug
            } else {
                "$cleanBase/category/${item.slug}"
            }
            data to item.name
        }

        return mainPageOf(*pairs.toTypedArray())
    }
}
