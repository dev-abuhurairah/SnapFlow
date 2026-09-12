package com.deniscerri.ytdl

import android.app.ActionBar.LayoutParams
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.afollestad.materialdialogs.utils.MDUtil.getStringArray
import com.afollestad.materialdialogs.utils.MDUtil.textChanged
import com.anggrayudi.storage.file.getAbsolutePath
import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.repository.DownloadRepository
import com.deniscerri.ytdl.database.viewmodel.CookieViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.database.viewmodel.SettingsViewModel
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.ui.HomeFragment
import com.deniscerri.ytdl.ui.downloads.DownloadQueueMainFragment
import com.deniscerri.ytdl.ui.downloads.HistoryFragment
import com.deniscerri.ytdl.ui.more.settings.SettingsActivity
import com.deniscerri.ytdl.util.ApkInstallUtil
import com.deniscerri.ytdl.util.CrashListener
import com.deniscerri.ytdl.util.NavbarUtil
import com.deniscerri.ytdl.util.NavbarUtil.applyNavBarStyle
import com.deniscerri.ytdl.util.ThemeUtil
import com.deniscerri.ytdl.util.UiUtil
import com.deniscerri.ytdl.util.UpdateUtil
import com.deniscerri.ytdl.work.background.UpdateCheckWorker
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.sequences.forEach
import kotlin.system.exitProcess


class MainActivity : BaseActivity() {

    lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var cookieViewModel: CookieViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private var navigationView: NavigationView? = null
    private var navigationBarView: NavigationBarView? = null
    private lateinit var navHostFragment : NavHostFragment
    private lateinit var navController : NavController
    private var loadingRuntimeDialog: androidx.appcompat.app.AlertDialog? = null

    private lateinit var installLauncher: ActivityResultLauncher<Intent>
    private var activeDownloadsCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashListener(this).registerExceptionHandler()
        ThemeUtil.updateTheme(this)
        window.navigationBarColor = SurfaceColors.SURFACE_2.getColor(this)
        setContentView(R.layout.activity_main)
        runCatching {
            findViewById<View>(R.id.frame_layout)?.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.splash_fade_in)
            )
        }
        context = baseContext

        resultViewModel = ViewModelProvider(this)[ResultViewModel::class.java]
        cookieViewModel = ViewModelProvider(this)[CookieViewModel::class.java]
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(this)[DownloadCardViewModel::class.java]
        preferences = PreferenceManager.getDefaultSharedPreferences(context)

        if (preferences.getBoolean("incognito", false)) {
            lifecycleScope.launch(Dispatchers.IO){
                resultViewModel.deleteAll()
            }
        }

        askPermissions()

        navHostFragment = supportFragmentManager.findFragmentById(R.id.frame_layout) as NavHostFragment
        navController = navHostFragment.findNavController()
        kotlin.runCatching {
            navigationView = findViewById(R.id.navigationView)
        }
        kotlin.runCatching {
            navigationBarView = findViewById(R.id.bottomNavigationView)
        }

        window.decorView.setOnApplyWindowInsetsListener { view: View, windowInsets: WindowInsets? ->
            val windowInsetsCompat = WindowInsetsCompat.toWindowInsetsCompat(
                windowInsets!!, view
            )
            val isImeVisible = windowInsetsCompat.isVisible(WindowInsetsCompat.Type.ime())
            findViewById<View>(R.id.snapflow_custom_bottom_nav)?.visibility = if (isImeVisible) View.GONE else View.VISIBLE
            navigationBarView?.visibility = View.GONE
            view.onApplyWindowInsets(windowInsets)
        }

        NavbarUtil.init(this)
        setupCustomBottomNavigation()

        navigationBarView?.apply {
            if (savedInstanceState == null){
                val graph = navController.navInflater.inflate(R.navigation.nav_graph)
                graph.setStartDestination(NavbarUtil.getStartFragmentId(this@MainActivity))
                navController.graph = graph
            }
            applyNavBarStyle()

            val showingDownloadQueue = NavbarUtil.getNavBarItems(this@MainActivity).any { n -> n.itemId == R.id.downloadQueueMainFragment && n.isVisible }

            setupWithNavController(navController)
            setOnItemReselectedListener {
                when (it.itemId) {
                    R.id.homeFragment -> {
                        kotlin.runCatching {
                            (navHostFragment.childFragmentManager.primaryNavigationFragment!! as HomeFragment).scrollToTop()
                        }
                    }
                    R.id.historyFragment -> {
                        if(!showingDownloadQueue) {
                            navController.navigate(R.id.downloadQueueMainFragment)
                        }else{
                            kotlin.runCatching {
                                (navHostFragment.childFragmentManager.primaryNavigationFragment!! as HistoryFragment).openSearchView()
                            }
                        }
                    }
                    R.id.downloadQueueMainFragment -> {
                        kotlin.runCatching {
                            (navHostFragment.childFragmentManager.primaryNavigationFragment!! as DownloadQueueMainFragment).scrollToActive()
                        }
                    }
                    R.id.moreFragment -> {
                        val intent = Intent(context, SettingsActivity::class.java)
                        startActivity(intent)
                    }
                }
            }

            val activeDownloadsBadge = if (showingDownloadQueue) {
                getOrCreateBadge(R.id.downloadQueueMainFragment)
            }else{
                getOrCreateBadge(R.id.historyFragment)
            }
            lifecycleScope.launch {
                downloadViewModel.activePausedDownloadsCount.collectLatest {
                    activeDownloadsCount = it
                    if (it == 0) {
                        activeDownloadsBadge.isVisible = false
                        activeDownloadsBadge.clearNumber()
                    }
                    else {
                        activeDownloadsBadge.isVisible = true
                        activeDownloadsBadge.number = it
                    }
                }
            }

            val showingNavbarItems = NavbarUtil.getNavBarItems(this@MainActivity).filter { it.isVisible }.map { it.itemId }
            val bnv = this as? BottomNavigationView
            bnv?.let { styleBottomNavItems(it) }
            navController.addOnDestinationChangedListener { _, destination, _ ->
                Handler(Looper.getMainLooper()).post {
                    if (showingNavbarItems.contains(destination.id)) {
                        showBottomNavigation()
                    }else{
                        hideBottomNavigation()
                    }
                    bnv?.let { styleBottomNavItems(it) }
                }

            }

            visibilityChanged {
                if (it.isVisible){
                    val curr = navController.currentDestination?.id
                    if (!showingNavbarItems.contains(curr)) hideBottomNavigation()
                }
            }
        }

        navigationView?.apply {
            setupWithNavController(navController)
            //terminate button
            menu.getItem(8).setOnMenuItemClickListener {
                if (preferences.getBoolean("ask_terminate_app", true)){
                    var doNotShowAgain = false
                    val terminateDialog = MaterialAlertDialogBuilder(this@MainActivity)
                    terminateDialog.setTitle(getString(R.string.confirm_delete_history))
                    val dialogView = layoutInflater.inflate(R.layout.dialog_terminate_app, null)
                    val checkbox = dialogView.findViewById<CheckBox>(R.id.doNotShowAgain)
                    terminateDialog.setView(dialogView)
                    checkbox.setOnCheckedChangeListener { compoundButton, _ ->
                        doNotShowAgain = compoundButton.isChecked
                    }

                    terminateDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
                    terminateDialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                        runBlocking {
                            val job : Job = lifecycleScope.launch(Dispatchers.IO) {
                                val activeDownloads = downloadViewModel.getActiveDownloads().toMutableList()
                                activeDownloads.map { it.status = DownloadRepository.Status.Queued.toString() }
                                activeDownloads.forEach {
                                    downloadViewModel.updateDownload(it)
                                }
                            }
                            runBlocking {
                                job.join()
                                if (doNotShowAgain){
                                    preferences.edit().putBoolean("ask_terminate_app", false).apply()
                                }
                                finishAndRemoveTask()
                                finishAffinity()
                                exitProcess(0)
                            }
                        }
                    }
                    terminateDialog.show()
                }else{
                    finishAndRemoveTask()
                    exitProcess(0)
                }
                true
            }
            //settings button
            menu.getItem(9).setOnMenuItemClickListener {
                val intent = Intent(context, SettingsActivity::class.java)
                startActivity(intent)
                true
            }

            getHeaderView(0).findViewById<TextView>(R.id.title).text = ThemeUtil.getStyledAppName(this@MainActivity)
        }

        cookieViewModel.updateCookiesFile()
        installLauncher = ApkInstallUtil.registerInstallLauncher(this)
        val intent = intent
        handleIntents(intent)

        askAutoUpdatePreferences()
    }
    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putBundle("nav_state", navController.saveState())
    }
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        navController.restoreState(savedInstanceState.getBundle("nav_state"))
    }

    private fun View.visibilityChanged(action: (View) -> Unit) {
        this.viewTreeObserver.addOnGlobalLayoutListener {
            val newVis: Int = this.visibility
            if (this.tag as Int? != newVis) {
                this.tag = this.visibility
                // visibility has changed
                action(this)
            }
        }
    }


    fun hideBottomNavigation(){
        findViewById<View>(R.id.snapflow_custom_bottom_nav)?.apply {
            animate()?.translationY(this.height.toFloat() + 40f)?.setDuration(250)?.withEndAction {
                this.visibility = View.GONE
            }?.start()
        }
        navigationBarView?.apply {
            if (this is BottomNavigationView){
                this.visibility = View.GONE
            }else if (this is NavigationRailView){
                this@MainActivity.findViewById<FragmentContainerView>(R.id.frame_layout).updateLayoutParams {
                    this.width = LayoutParams.MATCH_PARENT
                }

                if (resources.getBoolean(R.bool.is_right_to_left)){
                    this.animate()?.translationX(this.width.toFloat())?.setDuration(300)?.withEndAction {
                        this.visibility = View.GONE
                    }?.start()
                }else{
                    this.animate()?.translationX(-this.width.toFloat())?.setDuration(300)?.withEndAction {
                        this.visibility = View.GONE
                    }?.start()
                }
            }
        }
    }

    fun showBottomNavigation(){
        findViewById<View>(R.id.snapflow_custom_bottom_nav)?.apply {
            this.visibility = View.VISIBLE
            animate()?.translationY(0F)?.setDuration(250)?.start()
        }
        navigationBarView?.apply {
            if (this is BottomNavigationView){
                this.visibility = View.GONE
            }else if (this is NavigationRailView){
                this@MainActivity.findViewById<FragmentContainerView>(R.id.frame_layout).updateLayoutParams {
                    this.width = 0
                }
                this.animate()?.translationX(0F)?.setDuration(300)?.withEndAction {
                    this.visibility = View.VISIBLE
                }?.start()
            }
        }

    }

    fun disableBottomNavigation(){
        navigationBarView?.menu?.forEach { it.isEnabled = false }
        navigationView?.menu?.forEach { it.isEnabled = false }
    }

    fun enableBottomNavigation(){
        navigationBarView?.menu?.forEach { it.isEnabled = true }
        navigationView?.menu?.forEach { it.isEnabled = true }
    }

    override fun onResume() {
        super.onResume()
        //incognito header
        val incognitoHeader = findViewById<TextView>(R.id.incognito_header)
        if (preferences.getBoolean("incognito", false)){
            incognitoHeader.visibility = View.VISIBLE
            window.statusBarColor = (incognitoHeader.background as ColorDrawable).color
        }else{
            window.statusBarColor = getColor(android.R.color.transparent)
            incognitoHeader.visibility = View.GONE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntents(intent)
    }

    private fun handleIntents(intent: Intent) {
        val action = intent.action
        val type = intent.type
        if (Intent.ACTION_SEND == action && type != null) {
            Log.e(TAG, action)
            try {
                val uri = if (Build.VERSION.SDK_INT >= 33){
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                }else{
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }

                var downloadType = DownloadType.valueOf(preferences.getString("preferred_download_type", "video")!!)
                if (preferences.getBoolean("quick_download", false) || downloadType == DownloadType.command) {
                    val docFile = DocumentFile.fromSingleUri(this, uri!!)
                    if (docFile?.exists() == true){
                        val bundle = Bundle()
                        val path = docFile.getAbsolutePath(this)
                        if (downloadType == DownloadType.auto) {
                            downloadType = downloadViewModel.getDownloadType(null, path)
                        }

                        downloadCardViewModel.setResultItem(downloadViewModel.createEmptyResultItem(path))
                        downloadCardViewModel.setDownloadItem(null)
                        bundle.putSerializable("type", downloadType)
                        navController.navigate(R.id.downloadBottomSheetDialog, bundle)
                        return
                    }
                }

                val `is` = contentResolver.openInputStream(uri!!)
                val textBuilder = StringBuilder()
                val reader: Reader = BufferedReader(
                    InputStreamReader(
                        `is`, Charset.forName(
                            StandardCharsets.UTF_8.name()
                        )
                    )
                )
                var c: Int
                while (reader.read().also { c = it } != -1) {
                    textBuilder.append(c.toChar())
                }
                val bundle = Bundle()
                bundle.putString("url", textBuilder.toString())
                navController.popBackStack(R.id.homeFragment, true)
                navController.navigate(
                    R.id.homeFragment,
                    bundle
                )
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't read file", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }else if (action == Intent.ACTION_VIEW){

            val navbarItems = NavbarUtil.getNavBarItems(this)
            when(intent.getStringExtra("destination")){
                "Downloads" -> {
                    if (navbarItems.any { n -> n.itemId == R.id.historyFragment && n.isVisible }) {
                        navController.popBackStack(navController.graph.startDestinationId, true)
                    }
                    navController.navigate(R.id.historyFragment)
                }
                "Queue" -> {
                    if (navbarItems.any { n -> n.itemId == R.id.downloadQueueMainFragment && n.isVisible }) {
                        navController.popBackStack(navController.graph.startDestinationId, true)
                    }

                    val bundle = Bundle()
                    intent.getStringExtra("tab")?.apply {
                        bundle.putString("tab", this)
                    }
                    intent.getLongExtra("reconfigure", 0L).apply {
                        if (this != 0L){
                            bundle.putLong("reconfigure", this)
                        }
                    }
                    navController.navigate(R.id.downloadQueueMainFragment, bundle)
                }
                "Search" -> {
                    val bundle = Bundle()
                    bundle.putBoolean("search", true)
                    navController.popBackStack(R.id.homeFragment, true)
                    navController.navigate(
                        R.id.homeFragment,
                        bundle
                    )
                }
            }
        }
    }

    private fun askAutoUpdatePreferences() {
        if (preferences.getBoolean("asked_auto_update_preferences", false)) {
            callAutoUpdates()
            return
        }

        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(context.getString(R.string.update))
        builder.setIcon(R.drawable.ic_info)
        val view = layoutInflater.inflate(R.layout.dialog_ask_update_preferences, null)

        val updateAppLayout = view.findViewById<View>(R.id.update_app)
        val updateAppSwitch = updateAppLayout.findViewById<MaterialSwitch>(R.id.preference_switch)
        updateAppLayout.findViewById<MaterialButton>(R.id.preference_icon).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_update_app)
            isVisible = true
        }
        updateAppLayout.findViewById<TextView>(R.id.preference_title).text = getString(R.string.update_app)
        updateAppLayout.findViewById<TextView>(R.id.preference_summary).apply {
            text = getString(R.string.update_app_summary)
            isVisible = true
        }
        updateAppSwitch.isChecked = true
        updateAppLayout.setOnClickListener {
            updateAppSwitch.isChecked = !updateAppSwitch.isChecked
        }
        updateAppLayout.isVisible = BuildConfig.FLAVOR == "github"

        val updateYTDLLayout = view.findViewById<View>(R.id.update_ytdl)
        val updateYTDLSwitch = updateYTDLLayout.findViewById<MaterialSwitch>(R.id.preference_switch)
        updateYTDLLayout.findViewById<MaterialButton>(R.id.preference_icon).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_update)
            isVisible = true
        }
        updateYTDLLayout.findViewById<TextView>(R.id.preference_title).text = getString(R.string.auto_update_ytdlp)
        updateYTDLLayout.findViewById<TextView>(R.id.preference_summary).apply {
            text = getString(R.string.auto_update_ytdlp_summary)
            isVisible = true
        }
        updateYTDLSwitch.isChecked = true
        updateYTDLLayout.setOnClickListener {
            updateYTDLSwitch.isChecked = !updateYTDLSwitch.isChecked
        }

        builder.setView(view)
        builder.setCancelable(false)
        builder.setPositiveButton(
            context.getString(R.string.ok)
        ) { _: DialogInterface?, _: Int ->
            preferences.edit(commit = true) {
                putBoolean("update_app", updateAppSwitch.isChecked)
                putBoolean("auto_update_ytdlp", updateYTDLSwitch.isChecked)
                putBoolean("asked_auto_update_preferences", true)
            }

            if (updateAppSwitch.isChecked) {
                UpdateCheckWorker.schedule(context)
            }

            callAutoUpdates(firstRun = true)
        }

        val dialog = builder.create()
        dialog.show()
    }


    private fun callAutoUpdates(firstRun : Boolean = false) {
        if (BuildConfig.FLAVOR == "github" && preferences.getBoolean("update_app", false)) {
            val updateUtil = UpdateUtil(this)
            CoroutineScope(Dispatchers.IO).launch {
                val res = updateUtil.tryGetNewVersion()
                if (res.isSuccess) {
                    if (preferences.getBoolean("automatic_backup", false)) {
                        settingsViewModel.backup()
                    }
                    withContext(Dispatchers.Main) {
                        UiUtil.showNewAppUpdateSnackBar(
                            res.getOrNull()!!,
                            this@MainActivity,
                            findViewById<LinearLayout>(R.id.notification_container),
                            findViewById(R.id.frame_layout),
                            navigationBarView,
                            layoutInflater,
                            updateUtil,
                            this@MainActivity,
                            preferences,
                            installLauncher
                        )
                    }
                }

                val skipRemindingPackageUpdate = preferences.getStringSet("skip_reminding_package_update", setOf())!!.toMutableSet()
                RuntimeManager.getInstance().assertInit()
                RuntimeManager.packages.forEach { pkg ->
                    val instance = pkg.plugin.getInstance()
                    if (instance.bundledVersion.isNullOrBlank() && instance.downloadedVersion.isNullOrBlank()) return@forEach

                    instance.getReleases().apply {
                        val releases = this.getOrElse { listOf() }
                        if (releases.isEmpty()) return@apply

                        val latestRelease = releases.first()
                        if (latestRelease.isBundled || latestRelease.isInstalled) return@apply
                        if (latestRelease.oldVersion) return@apply
                        if (skipRemindingPackageUpdate.contains(latestRelease.tag_name)) return@apply

                        skipRemindingPackageUpdate.add(latestRelease.tag_name)
                        preferences.edit().putStringSet("skip_reminding_package_update", skipRemindingPackageUpdate).apply()
                        withContext(Dispatchers.Main) {
                            UiUtil.showNewPackageUpdateSnackBar(
                                latestRelease,
                                pkg,
                                this@MainActivity,
                                findViewById<LinearLayout>(R.id.notification_container),
                                findViewById(R.id.frame_layout),
                                navigationBarView,
                                layoutInflater,
                                this@MainActivity,
                                installLauncher
                            ) { result ->
                                result.onSuccess {
                                    RuntimeManager.reInit(this@MainActivity)
                                }.onFailure { f ->
                                    Snackbar.make(findViewById(R.id.frame_layout), f.message ?: "", Snackbar.LENGTH_LONG).apply {
                                        anchorView = findViewById(R.id.snapflow_custom_bottom_nav) ?: navigationBarView
                                        show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (preferences.getBoolean("auto_update_ytdlp", false)){
            CoroutineScope(SupervisorJob()).launch {
                try {
                    val hasActiveQueuedDownloads = DBManager.getInstance(this@MainActivity).downloadDao.getDownloadsCountByStatus(listOf("Active", "Queued")) > 0
                    if (hasActiveQueuedDownloads) return@launch

                    if (firstRun) {
                        Snackbar.make(findViewById(R.id.frame_layout), context.getString(R.string.ytdl_updating_started), Snackbar.LENGTH_LONG).apply {
                            anchorView = findViewById(R.id.snapflow_custom_bottom_nav) ?: navigationBarView
                            show()
                        }
                    }

                    val updateRes = withContext(Dispatchers.IO) {
                        UpdateUtil(this@MainActivity).updateYTDL()
                    }

                    if (updateRes.status == UpdateUtil.YTDLPUpdateStatus.DONE) {
                        val version = RuntimeManager.getInstance().version(context)
                        val message = this@MainActivity.getString(R.string.ytld_update_success) + " [${version}]"
                        Snackbar.make(findViewById(R.id.frame_layout), message, Snackbar.LENGTH_LONG).apply {
                            anchorView = findViewById(R.id.snapflow_custom_bottom_nav) ?: navigationBarView
                            show()
                        }
                    }
                } catch (err: Exception) {}
            }
        }
    }

    private fun setupCustomBottomNavigation() {
        val customNav = findViewById<View>(R.id.snapflow_custom_bottom_nav) ?: return

        val tabDownload = findViewById<View>(R.id.snapflow_nav_tab_download)
        val tabPlay = findViewById<View>(R.id.snapflow_nav_tab_play)
        val tabSettings = findViewById<View>(R.id.snapflow_nav_tab_settings)

        val pillDownload = findViewById<View>(R.id.snapflow_nav_pill_download)
        val pillPlay = findViewById<View>(R.id.snapflow_nav_pill_play)
        val pillSettings = findViewById<View>(R.id.snapflow_nav_pill_settings)

        val iconDownload = findViewById<ImageView>(R.id.snapflow_nav_icon_download)
        val iconPlay = findViewById<ImageView>(R.id.snapflow_nav_icon_play)
        val iconSettings = findViewById<ImageView>(R.id.snapflow_nav_icon_settings)

        val labelDownload = findViewById<TextView>(R.id.snapflow_nav_label_download)
        val labelPlay = findViewById<TextView>(R.id.snapflow_nav_label_play)
        val labelSettings = findViewById<TextView>(R.id.snapflow_nav_label_settings)

        fun updateNavState(destinationId: Int) {
            val isHome = (destinationId == R.id.homeFragment)
            val isPlay = (destinationId == R.id.historyFragment || destinationId == R.id.downloadQueueMainFragment)
            val isSettings = (destinationId == R.id.moreFragment)

            val activeColor = ContextCompat.getColor(this, R.color.snapflow_accent_gold)
            val inactiveColor = Color.parseColor("#8E8E98")

            // Download tab
            pillDownload?.setBackgroundResource(if (isHome) R.drawable.snapflow_nav_pill_active else 0)
            iconDownload?.imageTintList = ColorStateList.valueOf(if (isHome) activeColor else inactiveColor)
            labelDownload?.visibility = if (isHome) View.VISIBLE else View.GONE

            // Play tab
            pillPlay?.setBackgroundResource(if (isPlay) R.drawable.snapflow_nav_pill_active else 0)
            iconPlay?.imageTintList = ColorStateList.valueOf(if (isPlay) activeColor else inactiveColor)
            labelPlay?.visibility = if (isPlay) View.VISIBLE else View.GONE

            // Settings tab
            pillSettings?.setBackgroundResource(if (isSettings) R.drawable.snapflow_nav_pill_active else 0)
            iconSettings?.imageTintList = ColorStateList.valueOf(if (isSettings) activeColor else inactiveColor)
            labelSettings?.visibility = if (isSettings) View.VISIBLE else View.GONE
        }

        tabDownload?.setOnClickListener {
            if (navController.currentDestination?.id == R.id.homeFragment) {
                runCatching {
                    (navHostFragment.childFragmentManager.primaryNavigationFragment as? HomeFragment)?.scrollToTop()
                }
            } else {
                navController.navigate(R.id.homeFragment)
            }
        }

        tabPlay?.setOnClickListener {
            val curr = navController.currentDestination?.id
            if (curr == R.id.historyFragment) {
                navController.navigate(R.id.downloadQueueMainFragment)
            } else if (curr == R.id.downloadQueueMainFragment) {
                navController.navigate(R.id.historyFragment)
            } else {
                if (activeDownloadsCount > 0) {
                    navController.navigate(R.id.downloadQueueMainFragment)
                } else {
                    navController.navigate(R.id.historyFragment)
                }
            }
        }

        tabSettings?.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateNavState(destination.id)
        }

        // Initialize state
        navController.currentDestination?.id?.let { updateNavState(it) } ?: updateNavState(R.id.homeFragment)
    }

    private fun styleBottomNavItems(navView: BottomNavigationView) {
        navView.visibility = View.GONE
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}