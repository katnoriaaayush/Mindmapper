package com.mindmapper.explorer

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.mindmapper.explorer.databinding.ActivityMainBinding

/**
 * Single-activity WebView shell for the MindMapper board UI.
 *
 * Loads the FastAPI-served single-page app with the `?display=board` flag so the
 * web UI switches into its large-format, touch-optimised skin. The app holds no
 * credentials — all AI work happens server-side.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var loadFailed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        goImmersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        configureWebView()

        binding.swipe.setOnRefreshListener {
            loadFailed = false
            binding.webview.reload()
        }
        binding.retry.setOnClickListener { loadApp() }

        // Back navigates WebView history, then falls through to default (exit).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webview.canGoBack()) {
                    binding.webview.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadApp()
    }

    private fun loadApp() {
        loadFailed = false
        showError(false)
        binding.webview.loadUrl(BuildConfig.APP_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(binding.webview.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = false
            // Honour the page's own viewport; do not let the WebView add its own zoom.
            setSupportZoom(false)
            builtInZoomControls = false
        }

        binding.webview.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                binding.progress.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.isVisible = false
                binding.swipe.isRefreshing = false
                if (!loadFailed) showError(false)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Only surface failures for the main document, not sub-resources.
                if (request?.isForMainFrame == true) {
                    loadFailed = true
                    binding.progress.isVisible = false
                    binding.swipe.isRefreshing = false
                    showError(true)
                }
            }
        }
    }

    private fun showError(show: Boolean) {
        binding.errorView.isVisible = show
        binding.swipe.isVisible = !show
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }
}
