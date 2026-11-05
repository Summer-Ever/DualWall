package com.dualwall.data

import android.content.Context
import org.json.JSONObject

class RecipeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("dualwall", Context.MODE_PRIVATE)

    var recipe: Recipe
        get() = runCatching { decode(prefs.getString(KEY, null)) }.getOrDefault(Recipe())
        set(value) {
            prefs.edit().putString(KEY, encode(value)).apply()
            RecipeBus.notifyChanged()
        }

    fun update(profile: Profile, layer: Layer) {
        recipe = recipe.with(profile, layer)
    }

    private fun encode(r: Recipe): String = JSONObject().apply {
        put("v", 1)
        put("portrait", encodeLayer(r.portrait))
        put("landscape", encodeLayer(r.landscape))
        put("lockIndependent", r.lockIndependent)
    }.toString()

    private fun encodeLayer(l: Layer): JSONObject = JSONObject().apply {
        put("path", l.imagePath ?: JSONObject.NULL)
        put("w", l.srcW)
        put("h", l.srcH)
        put("ax", l.anchorX.toDouble())
        put("ay", l.anchorY.toDouble())
        put("z", l.zoom.toDouble())
    }

    private fun decode(raw: String?): Recipe {
        if (raw.isNullOrEmpty()) return Recipe()
        val o = JSONObject(raw)
        return Recipe(
            portrait = decodeLayer(o.optJSONObject("portrait")),
            landscape = decodeLayer(o.optJSONObject("landscape")),
            lockIndependent = o.optBoolean("lockIndependent", false),
        )
    }

    private fun decodeLayer(o: JSONObject?): Layer {
        if (o == null) return Layer()
        val path = if (o.isNull("path")) null else o.optString("path").takeIf { it.isNotEmpty() }
        return Layer(
            imagePath = path,
            srcW = o.optInt("w"),
            srcH = o.optInt("h"),
            anchorX = o.optDouble("ax", 0.5).toFloat(),
            anchorY = o.optDouble("ay", 0.5).toFloat(),
            zoom = o.optDouble("z", 1.0).toFloat(),
        ).sanitized()
    }

    private companion object {
        const val KEY = "recipe_v1"
    }
}
