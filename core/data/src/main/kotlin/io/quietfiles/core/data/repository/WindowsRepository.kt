package io.quietfiles.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.quietfiles.core.model.BrowseWindow
import io.quietfiles.core.model.OpenWindows
import io.quietfiles.core.model.WindowPlace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Открытые окна обзора (F1.5) переживают перезапуск: хранятся рядом с настройками. */
@Singleton
class WindowsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    private val key = stringPreferencesKey("browse_windows")

    val windows: Flow<OpenWindows> = store.data.map { decode(it[key]) }.distinctUntilChanged()

    suspend fun update(transform: (OpenWindows) -> OpenWindows) {
        store.edit { p -> p[key] = encode(transform(decode(p[key]))) }
    }

    internal companion object {
        fun encode(w: OpenWindows): String = JSONObject()
            .put("current", w.currentId)
            .put("windows", JSONArray(w.windows.map { win ->
                JSONObject().put("id", win.id).put("kind", win.place.kind).put("path", win.place.path)
                    .put("inner", win.place.inner).put("title", win.place.title)
            }))
            .toString()

        fun decode(raw: String?): OpenWindows {
            raw ?: return OpenWindows.Initial
            return try {
                val json = JSONObject(raw)
                val array = json.getJSONArray("windows")
                val list = (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    BrowseWindow(
                        o.getLong("id"),
                        WindowPlace(o.getString("kind"), o.optString("path"), o.optString("inner"), o.optString("title")),
                    )
                }
                if (list.isEmpty()) OpenWindows.Initial else OpenWindows(list, json.optLong("current", list.first().id))
            } catch (e: JSONException) {
                OpenWindows.Initial
            }
        }
    }
}
