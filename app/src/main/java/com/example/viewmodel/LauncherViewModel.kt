package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FavoriteApp
import com.example.data.FavoriteRepository
import com.example.data.LauncherDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val packageManager: PackageManager = context.packageManager
    private val repository: FavoriteRepository

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())
    val installedApps: StateFlow<List<AppItem>> = _installedApps.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        val database = LauncherDatabase.getDatabase(context)
        repository = FavoriteRepository(database.favoriteDao())
        loadApps()
    }

    val favoritesByLabel: StateFlow<List<FavoriteApp>> = repository.allFavorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteAppItems: StateFlow<List<AppItem>> = combine(
        _installedApps,
        favoritesByLabel
    ) { apps, favs ->
        favs.mapNotNull { fav ->
            apps.find { it.packageName == fav.packageName }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = packageManager.queryIntentActivities(launchIntent, 0)
                val apps = resolveInfos.map { info ->
                    AppItem(
                        packageName = info.activityInfo.packageName,
                        label = info.loadLabel(packageManager).toString(),
                        icon = info.loadIcon(packageManager)
                    )
                }.sortedBy { it.label.lowercase() }
                _installedApps.value = apps
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun makeAppFavorite(packageName: String, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insert(FavoriteApp(packageName = packageName, label = label))
        }
    }

    fun toggleFavorite(app: AppItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val isFav = repository.isFavorite(app.packageName)
            if (isFav) {
                repository.delete(app.packageName)
            } else {
                repository.insert(FavoriteApp(packageName = app.packageName, label = app.label))
            }
        }
    }

    fun removeFavorite(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(packageName)
        }
    }
}
