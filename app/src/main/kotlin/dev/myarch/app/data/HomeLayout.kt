package dev.myarch.app.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Something placed on a home screen page. */
public sealed interface HomeItem {
    public val key: String

    /** An Android app. */
    public data class AppShortcut(
        val packageName: String,
        val activityName: String,
        val label: String,
    ) : HomeItem {
        override val key: String get() = "app:$packageName/$activityName"
    }

    /** A Linux application from the container's desktop entries. */
    public data class LinuxShortcut(
        val desktopId: String,
        val label: String,
        val exec: String,
    ) : HomeItem {
        override val key: String get() = "linux:$desktopId"
    }

    /** An Android app widget, hosted by [dev.myarch.app.launcher.WidgetHostController]. */
    public data class Widget(
        val appWidgetId: Int,
        val widthDp: Int,
        val heightDp: Int,
    ) : HomeItem {
        override val key: String get() = "widget:$appWidgetId"
    }
}

/** One swipeable page of the home screen. */
public data class HomePage(
    val id: String,
    val title: String,
    val items: List<HomeItem>,
)

/** The whole home screen layout. */
public data class HomeLayout(val pages: List<HomePage>) {

    public fun withItem(pageId: String, item: HomeItem): HomeLayout = HomeLayout(
        pages.map { page ->
            if (page.id == pageId && page.items.none { it.key == item.key }) {
                page.copy(items = page.items + item)
            } else {
                page
            }
        },
    )

    public fun withoutItem(pageId: String, key: String): HomeLayout = HomeLayout(
        pages.map { page ->
            if (page.id == pageId) page.copy(items = page.items.filterNot { it.key == key }) else page
        },
    )

    public fun withNewPage(title: String): HomeLayout =
        HomeLayout(pages + HomePage(id = "page-${pages.size + 1}-${System.currentTimeMillis()}", title, emptyList()))

    public fun withoutPage(pageId: String): HomeLayout =
        if (pages.size <= 1) this else HomeLayout(pages.filterNot { it.id == pageId })

    public companion object {
        /** The layout a fresh install starts with. */
        public val DEFAULT: HomeLayout = HomeLayout(
            listOf(
                HomePage("page-1", "Home", emptyList()),
                HomePage("page-2", "Widgets", emptyList()),
            ),
        )
    }
}

/**
 * Persists the home screen layout as JSON in settings.
 *
 * JSON by hand rather than a serialisation library: the schema is five fields
 * wide, and a launcher losing its layout to a migration bug is a bad day for
 * the user. Unknown item types are skipped rather than failing the whole load,
 * so a layout written by a newer version still mostly works.
 */
public class HomeLayoutStore(private val settings: MyarchSettings) {

    public fun load(): HomeLayout {
        val raw = settings.homeLayout ?: return HomeLayout.DEFAULT
        return try {
            parse(raw)
        } catch (json: JSONException) {
            Log.w(TAG, "could not read the saved home layout; starting fresh", json)
            HomeLayout.DEFAULT
        }
    }

    public fun save(layout: HomeLayout) {
        settings.homeLayout = render(layout)
    }

    internal fun parse(raw: String): HomeLayout {
        val root = JSONObject(raw)
        val pagesJson = root.optJSONArray("pages") ?: return HomeLayout.DEFAULT
        val pages = ArrayList<HomePage>(pagesJson.length())
        for (pageIndex in 0 until pagesJson.length()) {
            val pageJson = pagesJson.getJSONObject(pageIndex)
            val itemsJson = pageJson.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<HomeItem>(itemsJson.length())
            for (itemIndex in 0 until itemsJson.length()) {
                parseItem(itemsJson.getJSONObject(itemIndex))?.let(items::add)
            }
            pages.add(
                HomePage(
                    id = pageJson.optString("id", "page-${pageIndex + 1}"),
                    title = pageJson.optString("title", "Page ${pageIndex + 1}"),
                    items = items,
                ),
            )
        }
        return if (pages.isEmpty()) HomeLayout.DEFAULT else HomeLayout(pages)
    }

    private fun parseItem(json: JSONObject): HomeItem? = when (json.optString("type")) {
        TYPE_APP -> HomeItem.AppShortcut(
            packageName = json.optString("package"),
            activityName = json.optString("activity"),
            label = json.optString("label"),
        ).takeIf { it.packageName.isNotEmpty() && it.activityName.isNotEmpty() }
        TYPE_LINUX -> HomeItem.LinuxShortcut(
            desktopId = json.optString("desktopId"),
            label = json.optString("label"),
            exec = json.optString("exec"),
        ).takeIf { it.desktopId.isNotEmpty() }
        TYPE_WIDGET -> HomeItem.Widget(
            appWidgetId = json.optInt("appWidgetId", -1),
            widthDp = json.optInt("widthDp", DEFAULT_WIDGET_DP),
            heightDp = json.optInt("heightDp", DEFAULT_WIDGET_DP),
        ).takeIf { it.appWidgetId >= 0 }
        else -> null
    }

    internal fun render(layout: HomeLayout): String {
        val pages = JSONArray()
        for (page in layout.pages) {
            val items = JSONArray()
            for (item in page.items) {
                items.put(renderItem(item))
            }
            pages.put(
                JSONObject()
                    .put("id", page.id)
                    .put("title", page.title)
                    .put("items", items),
            )
        }
        return JSONObject().put("version", SCHEMA_VERSION).put("pages", pages).toString()
    }

    private fun renderItem(item: HomeItem): JSONObject = when (item) {
        is HomeItem.AppShortcut -> JSONObject()
            .put("type", TYPE_APP)
            .put("package", item.packageName)
            .put("activity", item.activityName)
            .put("label", item.label)
        is HomeItem.LinuxShortcut -> JSONObject()
            .put("type", TYPE_LINUX)
            .put("desktopId", item.desktopId)
            .put("label", item.label)
            .put("exec", item.exec)
        is HomeItem.Widget -> JSONObject()
            .put("type", TYPE_WIDGET)
            .put("appWidgetId", item.appWidgetId)
            .put("widthDp", item.widthDp)
            .put("heightDp", item.heightDp)
    }

    private companion object {
        const val TAG = "MyarchLayout"
        const val SCHEMA_VERSION = 1
        const val TYPE_APP = "app"
        const val TYPE_LINUX = "linux"
        const val TYPE_WIDGET = "widget"
        const val DEFAULT_WIDGET_DP = 180
    }
}
