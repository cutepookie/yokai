package eu.kanade.tachiyomi.ui.source.globalsearch

import dev.yokai.domain.ui.UiPreferences
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import uy.kohesive.injekt.injectLazy

/**
 * Adapter that holds the manga items from search results.
 *
 * @param controller instance of [GlobalSearchController].
 */
class GlobalSearchCardAdapter(controller: GlobalSearchController) :
    FlexibleAdapter<GlobalSearchMangaItem>(null, controller, true) {

    /**
     * Listen for browse item clicks.
     */
    val mangaClickListener: OnMangaClickListener = controller
    private val uiPreferences: UiPreferences by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()
    val showOutlines = uiPreferences.outlineOnCovers().get()

    /**
     * Listener which should be called when user clicks browse.
     * Note: Should only be handled by [GlobalSearchController]
     */
    interface OnMangaClickListener {
        fun onMangaClick(manga: Manga)
        fun onMangaLongClick(position: Int, adapter: GlobalSearchCardAdapter)
    }
}
