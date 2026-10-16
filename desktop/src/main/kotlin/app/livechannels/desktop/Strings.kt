package app.livechannels.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The app's texts in Hebrew or English: the TV app's string files, plus the few the PC needs of
 * its own. The language is the viewer's choice in Settings (Hebrew unless changed).
 */
object Strings {
    private val pcOnly = mapOf(
        "he" to mapOf(
            "engine_missing_text" to "הערוצים מתנגנים דרך המנוע של Ace Stream. התקינו את Ace Stream למחשב (Windows), ואחרי זה התוכנה מפעילה אותו לבד.",
            "install_engine" to "להורדת Ace Stream",
            "language" to "שפה",
            "fullscreen" to "מסך מלא",
            "subtitles" to "כתוביות",
            "volume" to "עוצמה",
            "player_keys" to "↑↓ החלפת ערוץ · S כתוביות · F מסך מלא · M השתקה · Esc חזרה",
            "subs_hint" to "S  כתוביות",
            "vlc_missing" to "רכיב הנגן (VLC) לא נמצא. התקינו את התוכנה מחדש.",
            "field_empty" to "",
            "favorite_hint" to "קליק ימני: הוספה או הסרה ממועדפים",
            "setting_playlist_desc" to "אופציונלי: כתובת של רשימת M3U משלכם. היא נקראת במחשב הזה ומוצגת יחד עם הקטלוג.",
        ),
        "en" to mapOf(
            "engine_missing_text" to "Channels play through the Ace Stream engine. Install Ace Stream for Windows; the app then starts it by itself.",
            "install_engine" to "Download Ace Stream",
            "language" to "Language",
            "fullscreen" to "Full screen",
            "subtitles" to "Subtitles",
            "volume" to "Volume",
            "player_keys" to "↑↓ change channel · S subtitles · F full screen · M mute · Esc back",
            "subs_hint" to "S  subtitles",
            "vlc_missing" to "The player component (VLC) was not found. Please reinstall the app.",
            "field_empty" to "",
            "favorite_hint" to "Right click: add to or remove from favourites",
            "setting_playlist_desc" to "Optional: the address of an M3U list of your own. It is read on this computer and listed with the catalogue.",
        ),
    )

    private val cache = HashMap<String, Map<String, String>>()

    /** Read by every text on screen, so changing it redraws them in the other language. */
    var language: String by mutableStateOf("he")

    val isHebrew: Boolean get() = language == "he"

    private fun table(lang: String): Map<String, String> = cache.getOrPut(lang) {
        val stream = Strings::class.java.getResourceAsStream("/strings/$lang.xml") ?: return@getOrPut emptyMap()
        val document = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val nodes = document.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val element = nodes.item(i) as Element
            element.getAttribute("name") to unescape(element.textContent)
        }
    }

    private fun unescape(text: String): String =
        text.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")

    operator fun get(name: String, vararg args: Any): String {
        val text = pcOnly[language]?.get(name) ?: table(language)[name] ?: table("en")[name] ?: name
        return if (args.isEmpty()) text.replace("%%", "%") else runCatching { String.format(text, *args) }.getOrDefault(text)
    }

    /** A catalogue category by its name, in the viewer's language where the app knows it. */
    fun category(category: String): String = when {
        category.isBlank() -> get("uncategorized")
        else -> (pcOnly[language]?.get("cat_$category") ?: table(language)["cat_$category"])
            ?: category.replaceFirstChar { it.uppercase() }
    }
}
