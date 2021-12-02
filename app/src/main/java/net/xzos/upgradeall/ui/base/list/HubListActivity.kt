package net.xzos.upgradeall.ui.base.list

import android.graphics.Color
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import net.xzos.upgradeall.R
import net.xzos.upgradeall.databinding.ActivityDiscoverBinding
import net.xzos.upgradeall.ui.base.AppBarActivity
import net.xzos.upgradeall.ui.base.recycleview.RecyclerViewHolder

abstract class HubListActivity<L : ActivityListItemView, T : RecyclerViewHolder<L, *, *>>
    : HubListPart<L, L, T>, AppBarActivity(), SearchView.OnQueryTextListener {
    private lateinit var activityBinding: ActivityDiscoverBinding
    private var isListReady = false
    private var menu: Menu? = null
    override lateinit var rvList: RecyclerView
    override var srlContainer: SwipeRefreshLayout? = null

    override fun initView() {
        viewModel.getLiveData().observe(this) {
            isListReady = true
            menu?.findItem(R.id.search)?.isVisible = true
        }
    }

    private fun initListView(rvList: RecyclerView, srlContainer: SwipeRefreshLayout? = null) {
        this.rvList = rvList
        this.srlContainer = srlContainer
        initViewData(this)
    }

    override fun getAppBar(): Toolbar = activityBinding.appbar.toolbar

    override fun initBinding(): View {
        activityBinding = ActivityDiscoverBinding.inflate(layoutInflater)
        val binding = activityBinding.fragmentHubList.listLayout
        initListView(binding.rvList, binding.srlContainer)
        return activityBinding.root
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_cloud_app_list, menu)
        this.menu = menu

        val searchView = SearchView(this@HubListActivity).apply {
            setIconifiedByDefault(false)
            setOnQueryTextListener(this@HubListActivity)
            queryHint = getText(R.string.menu_search_hint)
            isQueryRefinementEnabled = true

            findViewById<View>(androidx.appcompat.R.id.search_plate).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
        }

        menu.findItem(R.id.search).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW or MenuItem.SHOW_AS_ACTION_IF_ROOM)
            actionView = searchView

            if (!isListReady) {
                isVisible = false
            }
        }

        return true
    }

    override fun onQueryTextSubmit(query: String) = false

    override fun onQueryTextChange(newText: String): Boolean {
        viewModel.getLiveData().value?.let { triple ->
            val filter = triple.first.filter {
                it.getItemIdName().contains(newText, ignoreCase = true)
            }
            viewModel.renewList(filter)
        }
        return false
    }
}