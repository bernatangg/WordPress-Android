package org.wordpress.android.viewmodel.pages

import androidx.annotation.ColorRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.Transformations
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.wordpress.android.R
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.generated.MediaActionBuilder
import org.wordpress.android.fluxc.model.LocalOrRemoteId.LocalId
import org.wordpress.android.fluxc.model.MediaModel
import org.wordpress.android.fluxc.model.page.PageModel
import org.wordpress.android.fluxc.model.page.PageStatus
import org.wordpress.android.fluxc.store.MediaStore
import org.wordpress.android.fluxc.store.MediaStore.MediaPayload
import org.wordpress.android.fluxc.store.MediaStore.OnMediaChanged
import org.wordpress.android.fluxc.store.PostStore
import org.wordpress.android.modules.BG_THREAD
import org.wordpress.android.ui.pages.PageItem
import org.wordpress.android.ui.pages.PageItem.Action
import org.wordpress.android.ui.pages.PageItem.Divider
import org.wordpress.android.ui.pages.PageItem.DraftPage
import org.wordpress.android.ui.pages.PageItem.Empty
import org.wordpress.android.ui.pages.PageItem.Page
import org.wordpress.android.ui.pages.PageItem.PublishedPage
import org.wordpress.android.ui.pages.PageItem.ScheduledPage
import org.wordpress.android.ui.pages.PageItem.TrashedPage
import org.wordpress.android.ui.utils.UiString
import org.wordpress.android.util.AppLog
import org.wordpress.android.util.LocaleManagerWrapper
import org.wordpress.android.util.SiteUtils
import org.wordpress.android.util.toFormattedDateString
import org.wordpress.android.viewmodel.ScopedViewModel
import org.wordpress.android.viewmodel.SingleLiveEvent
import org.wordpress.android.viewmodel.pages.PageListViewModel.PageListState.FETCHING
import org.wordpress.android.viewmodel.pages.PageListViewModel.PageListType.DRAFTS
import org.wordpress.android.viewmodel.pages.PageListViewModel.PageListType.PUBLISHED
import org.wordpress.android.viewmodel.pages.PageListViewModel.PageListType.SCHEDULED
import org.wordpress.android.viewmodel.pages.PageListViewModel.PageListType.TRASHED
import org.wordpress.android.viewmodel.uistate.ProgressBarUiState
import javax.inject.Inject
import javax.inject.Named

private const val MAX_TOPOLOGICAL_PAGE_COUNT = 100
private const val DEFAULT_INDENT = 0

class PageListViewModel @Inject constructor(
    private val createPageListItemLabelsUseCase: CreatePageListItemLabelsUseCase,
    private val createPageUploadUiStateUseCase: CreatePageUploadUiStateUseCase,
    private val mediaStore: MediaStore,
    private val postStore: PostStore,
    private val dispatcher: Dispatcher,
    private val localeManagerWrapper: LocaleManagerWrapper,
    @Named(BG_THREAD) private val coroutineDispatcher: CoroutineDispatcher,
    private val pageItemUiStateHelper: PageItemUiStateHelper
) : ScopedViewModel(coroutineDispatcher) {
    private val _pages: MutableLiveData<List<PageItem>> = MutableLiveData()
    val pages: LiveData<Pair<List<PageItem>, Boolean>> = Transformations.map(_pages) {
        Pair(it, isSitePhotonCapable)
    }

    private val _scrollToPosition = SingleLiveEvent<Int>()
    val scrollToPosition: LiveData<Int> = _scrollToPosition

    private var isStarted: Boolean = false
    private lateinit var listType: PageListType

    private lateinit var pagesViewModel: PagesViewModel

    private val featuredImageMap = mutableMapOf<Long, String>()

    private val isSitePhotonCapable: Boolean by lazy {
        SiteUtils.isPhotonCapable(pagesViewModel.site)
    }

    enum class PageListType(val pageStatuses: List<PageStatus>) {
        PUBLISHED(listOf(PageStatus.PUBLISHED, PageStatus.PRIVATE)),
        DRAFTS(listOf(PageStatus.DRAFT, PageStatus.PENDING)),
        SCHEDULED(listOf(PageStatus.SCHEDULED)),
        TRASHED(listOf(PageStatus.TRASHED));

        companion object {
            fun fromPageStatus(status: PageStatus): PageListType {
                return when (status) {
                    PageStatus.PUBLISHED, PageStatus.PRIVATE -> PUBLISHED
                    PageStatus.DRAFT, PageStatus.PENDING -> DRAFTS
                    PageStatus.TRASHED -> TRASHED
                    PageStatus.SCHEDULED -> SCHEDULED
                }
            }
        }

        val title: Int
            get() = when (this) {
                PUBLISHED -> R.string.pages_published
                DRAFTS -> R.string.pages_drafts
                SCHEDULED -> R.string.pages_scheduled
                TRASHED -> R.string.pages_trashed
            }
    }

    enum class PageListState {
        DONE,
        ERROR,
        REFRESHING,
        FETCHING
    }

    fun start(listType: PageListType, pagesViewModel: PagesViewModel) {
        this.listType = listType
        this.pagesViewModel = pagesViewModel

        if (!isStarted) {
            isStarted = true

            pagesViewModel.pages.observeForever(pagesObserver)
            pagesViewModel.invalidateUploadStatus.observeForever(uploadStatusObserver)

            dispatcher.register(this)
        }
    }

    override fun onCleared() {
        pagesViewModel.pages.removeObserver(pagesObserver)
        pagesViewModel.invalidateUploadStatus.removeObserver(uploadStatusObserver)

        dispatcher.unregister(this)
    }

    fun onMenuAction(action: Action, pageItem: Page): Boolean {
        return pagesViewModel.onMenuAction(action, pageItem)
    }

    fun onItemTapped(pageItem: Page) {
        if (pageItem.tapActionEnabled) {
            pagesViewModel.onItemTapped(pageItem)
        }
    }

    fun onEmptyListNewPageButtonTapped() {
        pagesViewModel.onNewPageButtonTapped()
    }

    fun onScrollToPageRequested(remotePageId: Long) {
        val position = _pages.value?.indexOfFirst { it is Page && it.remoteId == remotePageId } ?: -1
        if (position != -1) {
            _scrollToPosition.postValue(position)
        } else {
            AppLog.e(AppLog.T.PAGES, "Attempt to scroll to a missing page with ID $remotePageId")
        }
    }

    private val pagesObserver = Observer<List<PageModel>> { pages ->
        pages?.let {
            loadPagesAsync(pages)

            pagesViewModel.checkIfNewPageButtonShouldBeVisible()
        }
    }

    private val uploadStatusObserver = Observer<List<LocalId>> { ids ->
        createPageUploadUiStateUseCase.uploadStatusTracker.invalidateUploadStatus(ids.map { localId -> localId.value })
    }

    private fun loadPagesAsync(pages: List<PageModel>) = launch {
        val pageItems = pages
                .sortedBy { it.title.toLowerCase(localeManagerWrapper.getLocale()) }
                .filter { listType.pageStatuses.contains(it.status) }
                .let {
                    when (listType) {
                        PUBLISHED -> preparePublishedPages(it, pagesViewModel.arePageActionsEnabled)
                        SCHEDULED -> prepareScheduledPages(it, pagesViewModel.arePageActionsEnabled)
                        DRAFTS -> prepareDraftPages(it, pagesViewModel.arePageActionsEnabled)
                        TRASHED -> prepareTrashedPages(it, pagesViewModel.arePageActionsEnabled)
                    }
                }

        displayListItems(pageItems)
    }

    private fun displayListItems(newPages: List<PageItem>) {
        if (newPages.isEmpty()) {
            if (pagesViewModel.listState.value == FETCHING || pagesViewModel.listState.value == null) {
                _pages.postValue(
                        listOf(
                                Empty(
                                        R.string.pages_fetching,
                                        isButtonVisible = false,
                                        isImageVisible = false
                                )
                        )
                )
            } else {
                when (listType) {
                    PUBLISHED -> _pages.postValue(listOf(Empty(R.string.pages_empty_published)))
                    SCHEDULED -> _pages.postValue(listOf(Empty(R.string.pages_empty_scheduled)))
                    DRAFTS -> _pages.postValue(listOf(Empty(R.string.pages_empty_drafts)))
                    TRASHED -> _pages.postValue(listOf(Empty(R.string.pages_empty_trashed, isButtonVisible = false)))
                }
            }
        } else {
            val pagesWithBottomGap = newPages.toMutableList()
            pagesWithBottomGap.addAll(listOf(Divider(), Divider()))
            _pages.postValue(pagesWithBottomGap)
        }
    }

    private fun getFeaturedImageUrl(featuredImageId: Long): String? {
        if (featuredImageId == 0L) {
            return null
        } else if (featuredImageMap.containsKey(featuredImageId)) {
            return featuredImageMap[featuredImageId]
        }

        mediaStore.getSiteMediaWithId(pagesViewModel.site, featuredImageId)?.let { media ->
            // This should be a pretty rare case, but some media seems to be missing url
            return if (media.url != null) {
                featuredImageMap[featuredImageId] = media.url
                media.url
            } else null
        }

        // Media is not in the Store, we need to download it
        val mediaToDownload = MediaModel()
        mediaToDownload.mediaId = featuredImageId
        mediaToDownload.localSiteId = pagesViewModel.site.id

        val payload = MediaPayload(pagesViewModel.site, mediaToDownload)
        dispatcher.dispatch(MediaActionBuilder.newFetchMediaAction(payload))

        return null
    }

    private fun preparePublishedPages(pages: List<PageModel>, actionsEnabled: Boolean): List<PageItem> {
        val shouldSortTopologically = pages.size < MAX_TOPOLOGICAL_PAGE_COUNT
        val sortedPages = if (shouldSortTopologically) {
            topologicalSort(pages, listType = PUBLISHED)
        } else {
            pages.sortedByDescending { it.date }
        }
        return sortedPages
                .map {
                    val pageItemIndent = if (shouldSortTopologically) {
                        getPageItemIndent(it)
                    } else {
                        DEFAULT_INDENT
                    }
                    val itemUiStateData = createItemUiStateData(it)

                    PublishedPage(
                            it.remoteId,
                            it.pageId,
                            it.title,
                            it.date,
                            itemUiStateData.labels,
                            itemUiStateData.labelsColor,
                            pageItemIndent,
                            getFeaturedImageUrl(it.featuredImageId),
                            pageItemUiStateHelper.setupPageActions(
                                    listType,
                                    LocalId(it.pageId),
                                    pagesViewModel.site
                            ),
                            actionsEnabled,
                            itemUiStateData.progressBarUiState,
                            itemUiStateData.showOverlay
                    )
                }
    }

    private fun prepareScheduledPages(
        pages: List<PageModel>,
        actionsEnabled: Boolean
    ): List<PageItem> {
        return pages.asSequence().groupBy { it.date.toFormattedDateString() }
                .map { (date, results) ->
                    listOf(Divider(date)) +
                            results.map {
                                val itemUiStateData = createItemUiStateData(it)

                                ScheduledPage(
                                        it.remoteId, it.pageId,
                                        it.title,
                                        it.date,
                                        itemUiStateData.labels,
                                        itemUiStateData.labelsColor,
                                        getFeaturedImageUrl(it.featuredImageId),
                                        pageItemUiStateHelper.setupPageActions(
                                                listType,
                                                LocalId(it.pageId),
                                                pagesViewModel.site
                                        ),
                                        actionsEnabled,
                                        itemUiStateData.progressBarUiState,
                                        itemUiStateData.showOverlay
                                )
                            }
                }
                .fold(mutableListOf()) { acc: MutableList<PageItem>, list: List<PageItem> ->
                    acc.addAll(list)
                    return@fold acc
                }
    }

    private fun prepareDraftPages(pages: List<PageModel>, actionsEnabled: Boolean): List<PageItem> {
        return pages.map {
            val itemUiStateData = createItemUiStateData(it)
            DraftPage(
                    it.remoteId,
                    it.pageId,
                    it.title,
                    it.date,
                    itemUiStateData.labels,
                    itemUiStateData.labelsColor,
                    getFeaturedImageUrl(it.featuredImageId),
                    pageItemUiStateHelper.setupPageActions(
                            listType,
                            LocalId(it.pageId),
                            pagesViewModel.site
                    ),
                    actionsEnabled,
                    itemUiStateData.progressBarUiState,
                    itemUiStateData.showOverlay
            )
        }
    }

    private fun prepareTrashedPages(
        pages: List<PageModel>,
        actionsEnabled: Boolean
    ): List<PageItem> {
        return pages.map {
            val itemUiStateData = createItemUiStateData(it)
            TrashedPage(
                    it.remoteId,
                    it.pageId,
                    it.title,
                    it.date,
                    itemUiStateData.labels,
                    itemUiStateData.labelsColor,
                    getFeaturedImageUrl(it.featuredImageId),
                    pageItemUiStateHelper.setupPageActions(
                            listType,
                            LocalId(it.pageId),
                            pagesViewModel.site
                    ),
                    actionsEnabled,
                    itemUiStateData.progressBarUiState,
                    itemUiStateData.showOverlay
            )
        }
    }

    private fun topologicalSort(
        pages: List<PageModel>,
        listType: PageListType,
        parent: PageModel? = null
    ): List<PageModel> {
        val sortedList = mutableListOf<PageModel>()
        pages.filter {
            it.parent?.remoteId == parent?.remoteId ||
                    (parent == null && !listType.pageStatuses.contains(it.parent?.status))
        }.forEach {
            sortedList += it
            sortedList += topologicalSort(pages, listType, it)
        }
        return sortedList
    }

    private fun getPageItemIndent(page: PageModel?): Int {
        return if (page == null || !PageListType.PUBLISHED.pageStatuses.contains(page.status))
            -1
        else
            getPageItemIndent(page.parent) + 1
    }

    private fun invalidateFeaturedMedia(vararg featuredImageIds: Long) {
        featuredImageIds.forEach { featuredImageMap.remove(it) }
        pagesViewModel.onImagesChanged()
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    @SuppressWarnings("unused")
    fun onMediaChanged(event: OnMediaChanged) {
        if (!event.isError && event.mediaList != null) {
            invalidateFeaturedMedia(*event.mediaList.map { it.mediaId }.toLongArray())
        }
    }

    private fun createItemUiStateData(pageModel: PageModel): ItemUiStateData {
        // TODO don't load the post model from db during uistate creation as it can have significant performance impact
        val postModel = postStore.getPostByLocalPostId(pageModel.pageId)
        // TODO the postmodel is sometimes null, why?
        val uploadUiState = createPageUploadUiStateUseCase.createUploadUiState(
                postModel,
                pagesViewModel.site
        )
        val (labels, labelColor) = createPageListItemLabelsUseCase.createLabels(postModel, uploadUiState)

        val (progressBarUiState, showOverlay) = pageItemUiStateHelper.getProgressStateForPage(
                postModel,
                uploadUiState
        )
        return ItemUiStateData(labels, labelColor, progressBarUiState, showOverlay)
    }

    private data class ItemUiStateData(
        val labels: List<UiString>,
        @ColorRes val labelsColor: Int?,
        val progressBarUiState: ProgressBarUiState,
        val showOverlay: Boolean
    )
}
