package com.example.lingxi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private static final String TARGET_URL = "https://app-6xpj2jb3qozl.appmiaoda.com/";
    private boolean isShowingOfflinePage = false;
    private static final String OFFLINE_PAGE_URL = "file:///android_asset/offline.html";

    // 文件上传相关变量
    private ValueCallback<Uri[]> uploadMessage;
    private final static int FILE_CHOOSER_RESULT_CODE = 1;
    private final static int CAMERA_PERMISSION_REQUEST_CODE = 2;
    private final static int CAMERA_CAPTURE_REQUEST_CODE = 3;
    private final static int AUDIO_CAPTURE_REQUEST_CODE = 4;
    private Uri cameraImageUri;

    // 返回键处理相关变量
    private long backPressedTime = 0;
    private static final int BACK_PRESSED_INTERVAL = 2000;

    private static final String TAG = "MainActivity";
    private static final String WEBVIEW_TAG = "WebView";
    private static final String JS_TAG = "JavaScript";
    private static final String SYSTEM_NAV_TAG = "SystemNavigation";
    private static final String DOWNLOAD_TAG = "DownloadManager";
    private static final String GALLERY_TAG = "GallerySave";

    // 所需权限列表
    private static final String[] REQUIRED_PERMISSIONS = {
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS
    };

    // Android 13+ 媒体权限
    private static final String[] ANDROID_13_PERMISSIONS = {
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO
    };

    // 返回键处理回调
    private OnBackPressedCallback onBackPressedCallback;

    // 下载管理相关
    private DownloadManager downloadManager;
    private BroadcastReceiver downloadCompleteReceiver;
    private ExecutorService downloadExecutor;

    // 下载目录
    private File downloadDirectory;

    // 保存最近请求的真实MP4 URL，用于Blob URL转换
    private String recentRealMp4Url = null;
    private long lastMp4UrlTime = 0;
    private static final long MP4_URL_TIMEOUT = 30000; // 30秒超时

    // 网络监控相关变量
    private BroadcastReceiver networkReceiver;
    private boolean isNetworkMonitoringSetup = false;
    private boolean isActivityDestroyed = false;
    private boolean isNetworkAvailable = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "MainActivity onCreate");
        isActivityDestroyed = false;

        // 设置状态栏
        setupStatusBar();

        // 隐藏标题栏
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);

        // 初始化下载相关组件
        initDownloadComponents();

        // 检查并请求权限
        if (!hasAllPermissions()) {
            requestAllPermissions();
        }

        // 配置 WebView
        setupWebView();

        // 设置返回键处理（兼容新旧系统）
        setupBackPressedHandler();

        // 只有在没有保存状态时才加载URL
        if (savedInstanceState == null) {
            // 加载目标网站
            Log.d(WEBVIEW_TAG, "加载目标URL: " + TARGET_URL);
            webView.loadUrl(TARGET_URL);
        } else {
            // 恢复状态
            Log.d(WEBVIEW_TAG, "恢复WebView状态");
            isShowingOfflinePage = savedInstanceState.getBoolean("isShowingOfflinePage", false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        isActivityDestroyed = false;

        // 在onResume中设置网络监控
        setupNetworkMonitoring();

        if (webView != null) {
            webView.onResume();
        }

        // 检查当前网络状态
        checkNetworkAndReloadIfNeeded();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");

        // 在onPause中移除网络监控，避免不必要的处理
        tearDownNetworkMonitoring();

        if (webView != null) {
            webView.onPause();
        }
    }

    /**
     * 初始化下载相关组件 - 防止重复注册版本
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void initDownloadComponents() {
        Log.d(DOWNLOAD_TAG, "初始化下载组件");

        // 创建下载目录
        downloadDirectory = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "LingXiApp");
        if (!downloadDirectory.exists()) {
            boolean created = downloadDirectory.mkdirs();
            Log.d(DOWNLOAD_TAG, "下载目录创建: " + (created ? "成功" : "失败"));
        }

        // 初始化下载管理器
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

        // 初始化线程池
        if (downloadExecutor == null || downloadExecutor.isShutdown()) {
            downloadExecutor = Executors.newFixedThreadPool(3);
        }

        // 只在第一次创建时注册广播接收器
        if (downloadCompleteReceiver == null) {
            // 注册下载完成广播接收器
            downloadCompleteReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    Log.d(DOWNLOAD_TAG, "广播接收器触发，Action: " + action);

                    if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                        Log.d(DOWNLOAD_TAG, "下载完成广播接收，下载ID: " + downloadId);

                        if (downloadId != -1) {
                            handleDownloadComplete(downloadId);
                        } else {
                            Log.e(DOWNLOAD_TAG, "无效的下载ID");
                        }
                    }
                }
            };

            // 注册广播接收器
            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(downloadCompleteReceiver, filter);
                }
                Log.d(DOWNLOAD_TAG, "下载组件初始化完成，广播接收器已注册");
            } catch (Exception e) {
                Log.e(DOWNLOAD_TAG, "注册下载广播接收器失败", e);
            }
        }
    }

    /**
     * 设置网络状态监控 - 修复版本
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void setupNetworkMonitoring() {
        if (isNetworkMonitoringSetup || isActivityDestroyed) {
            return;
        }

        Log.d(TAG, "设置网络状态监控");

        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 检查Activity是否已被销毁
                if (isActivityDestroyed || isFinishing()) {
                    return;
                }

                String action = intent.getAction();
                Log.d(TAG, "网络状态变化，Action: " + action);

                if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                    // 延迟检查，避免频繁操作
                    new Handler(getMainLooper()).postDelayed(() -> {
                        if (!isActivityDestroyed && !isFinishing()) {
                            checkNetworkAndReloadIfNeeded();
                        }
                    }, 2000);
                }
            }
        };

        // 注册网络状态变化的广播接收器
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(networkReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(networkReceiver, filter);
            }
            isNetworkMonitoringSetup = true;
            Log.d(TAG, "网络状态监控已设置");
        } catch (Exception e) {
            Log.e(TAG, "设置网络监控时出错", e);
        }
    }

    /**
     * 移除网络监控
     */
    private void tearDownNetworkMonitoring() {
        if (isNetworkMonitoringSetup && networkReceiver != null) {
            try {
                unregisterReceiver(networkReceiver);
                isNetworkMonitoringSetup = false;
                Log.d(TAG, "网络状态监控已移除");
            } catch (Exception e) {
                Log.e(TAG, "移除网络监控时出错", e);
            }
        }
    }

    /**
     * 检查网络状态并在需要时重新加载 - 修复版本
     */
    private void checkNetworkAndReloadIfNeeded() {
        // 检查Activity状态
        if (isActivityDestroyed || isFinishing()) {
            Log.d(TAG, "Activity已销毁或正在结束，跳过网络检查");
            return;
        }

        boolean currentNetworkStatus = isNetworkAvailable();
        Log.d(TAG, "检查网络状态: " + (currentNetworkStatus ? "可用" : "不可用") +
                ", 之前状态: " + (isNetworkAvailable ? "可用" : "不可用"));

        // 如果网络状态没有变化，不需要处理
        if (currentNetworkStatus == isNetworkAvailable) {
            Log.d(TAG, "网络状态未变化，跳过处理");
            return;
        }

        isNetworkAvailable = currentNetworkStatus;

        runOnUiThread(() -> {
            // 再次检查Activity状态
            if (isActivityDestroyed || isFinishing()) {
                return;
            }

            if (isNetworkAvailable) {
                Log.d(TAG, "网络已恢复，当前显示离线页面: " + isShowingOfflinePage);
                if (isShowingOfflinePage) {
                    Log.d(TAG, "自动重试连接");
                    safeRetryConnection();
                }
            } else {
                Log.d(TAG, "网络不可用");
                // 如果当前不是离线页面且是主页面，检查是否需要显示离线页面
                String currentUrl = webView != null ? webView.getUrl() : "";
                if (!isShowingOfflinePage && currentUrl != null &&
                        (currentUrl.equals(TARGET_URL) || currentUrl.contains(TARGET_URL)) &&
                        !currentUrl.equals(OFFLINE_PAGE_URL)) {

                    Log.d(TAG, "网络不可用，当前在主页面，显示离线页面");
                    showOfflinePage();
                }
            }
        });
    }

    /**
     * 安全的重试连接 - 防止崩溃版本
     */
    private void safeRetryConnection() {
        Log.d(WEBVIEW_TAG, "安全重试网络连接，当前状态 - 显示离线页面: " + isShowingOfflinePage);

        // 先检查Activity状态
        if (isActivityDestroyed || isFinishing()) {
            Log.d(WEBVIEW_TAG, "Activity已销毁，取消重试");
            return;
        }

        // 检查网络状态
        if (!isNetworkAvailable) {
            Log.d(WEBVIEW_TAG, "网络不可用，取消重试");
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    Toast.makeText(MainActivity.this, "网络连接不可用，请检查网络设置", Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        runOnUiThread(() -> {
            // 再次检查Activity状态
            if (isActivityDestroyed || isFinishing()) {
                return;
            }

            try {
                if (progressBar != null) {
                    progressBar.setVisibility(View.VISIBLE);
                }
                isShowingOfflinePage = false; // 重置离线页面状态

                Log.d(WEBVIEW_TAG, "开始重新加载主页面: " + TARGET_URL);

                // 使用loadUrl而不是reload，确保加载正确的URL
                if (webView != null) {
                    webView.loadUrl(TARGET_URL);
                }

            } catch (Exception e) {
                Log.e(WEBVIEW_TAG, "重试连接时发生错误", e);
                if (!isActivityDestroyed && !isFinishing()) {
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                    // 如果重试失败，重新显示离线页面
                    showOfflinePage();
                }
            }
        });
    }

    /**
     * 显示离线页面 - 安全版本
     */
    private void showOfflinePage() {
        Log.d(WEBVIEW_TAG, "显示离线页面，当前状态: " + isShowingOfflinePage);

        runOnUiThread(() -> {
            // 检查Activity状态
            if (isActivityDestroyed || isFinishing() || webView == null) {
                return;
            }

            try {
                // 只有在真正需要显示离线页面时才设置状态
                if (!isShowingOfflinePage) {
                    isShowingOfflinePage = true;
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }

                    Log.d(WEBVIEW_TAG, "加载离线页面: " + OFFLINE_PAGE_URL);
                    // 加载离线页面
                    webView.loadUrl(OFFLINE_PAGE_URL);

                    // 显示离线提示
                    Toast.makeText(MainActivity.this, "网络连接不可用", Toast.LENGTH_SHORT).show();

                    Log.d(WEBVIEW_TAG, "离线页面加载完成");
                } else {
                    Log.d(WEBVIEW_TAG, "已经在离线页面，无需重复加载");
                }
            } catch (Exception e) {
                Log.e(WEBVIEW_TAG, "显示离线页面时发生错误", e);
            }
        });
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView() {
        Log.d(WEBVIEW_TAG, "配置WebView");
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setSupportZoom(true);
        webSettings.setDefaultTextEncodingName("utf-8");

        // 启用文件访问
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        // 启用媒体功能
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        // 启用数据库和缓存
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);

        // 添加 JavaScript 接口
        webView.addJavascriptInterface(new JavaScriptInterface(), "AndroidApp");

        // 确保WebView可以获取焦点
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        // 设置 WebViewClient - 捕获下载链接
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.d(WEBVIEW_TAG, "拦截URL加载: " + url);

                // 检查是否是下载链接
                if (isDownloadableFile(url)) {
                    Log.d(DOWNLOAD_TAG, "检测到下载链接: " + url);
                    handleDownload(url);
                    return true; // 拦截下载链接
                }

                // 返回 false 让 WebView 自己处理 URL 加载
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.d(WEBVIEW_TAG, "拦截URL加载(旧API): " + url);

                // 检查是否是下载链接
                if (isDownloadableFile(url)) {
                    Log.d(DOWNLOAD_TAG, "检测到下载链接(旧API): " + url);
                    handleDownload(url);
                    return true; // 拦截下载链接
                }

                // 兼容旧版本 API
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!isActivityDestroyed && !isFinishing() && progressBar != null) {
                    progressBar.setVisibility(View.VISIBLE);
                }
                Log.d(WEBVIEW_TAG, "页面开始加载: " + url);

                // 如果是从离线页面跳转回主页面，重置状态
                if (url.equals(TARGET_URL) && isShowingOfflinePage) {
                    isShowingOfflinePage = false;
                    Log.d(WEBVIEW_TAG, "从离线页面返回主页面，重置状态");
                }

                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!isActivityDestroyed && !isFinishing() && progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
                Log.d(WEBVIEW_TAG, "页面加载完成: " + url);
                if (webView != null) {
                    Log.d(WEBVIEW_TAG, "WebView canGoBack: " + webView.canGoBack());
                }

                // 更新离线页面状态
                if (url.equals(OFFLINE_PAGE_URL)) {
                    isShowingOfflinePage = true;
                    Log.d(WEBVIEW_TAG, "当前显示离线页面");
                } else if (url.equals(TARGET_URL) || url.contains(TARGET_URL)) {
                    isShowingOfflinePage = false;
                    // 如果是主页面加载完成，注入下载监听器
                    injectDownloadListener();
                    Log.d(WEBVIEW_TAG, "主页面加载完成，重置离线状态");
                } else {
                    isShowingOfflinePage = false;
                    Log.d(WEBVIEW_TAG, "其他页面加载完成: " + url);
                }

                super.onPageFinished(view, url);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(WEBVIEW_TAG, "网页加载错误: " + errorCode + ", " + description + ", URL: " + failingUrl);

                // 只在主页面加载失败时显示离线页面，避免重复显示
                if ((failingUrl.equals(TARGET_URL) || failingUrl.contains(TARGET_URL))
                        && !isShowingOfflinePage
                        && !failingUrl.equals(OFFLINE_PAGE_URL)) {
                    showOfflinePage();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                Log.e(WEBVIEW_TAG, "HTTP错误: " + errorResponse.getStatusCode() + ", URL: " + request.getUrl());

                String url = request.getUrl().toString();
                // 只在主页面HTTP错误且不在离线页面时显示离线页面
                if (url.equals(TARGET_URL) && !isShowingOfflinePage && !url.equals(OFFLINE_PAGE_URL)) {
                    showOfflinePage();
                }
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // 只需要保留媒体URL的记录逻辑
                if (isRealMediaUrl(url)) {
                    Log.d(DOWNLOAD_TAG, "捕获真实媒体URL: " + url);
                    saveRecentRealUrl(url);
                }

                return super.shouldInterceptRequest(view, request);
            }
        });

        // 设置下载监听器
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimetype,
                                        long contentLength) {
                Log.d(DOWNLOAD_TAG, "WebView下载监听器触发 - URL: " + url);
                Log.d(DOWNLOAD_TAG, "Content-Disposition: " + contentDisposition);
                Log.d(DOWNLOAD_TAG, "MimeType: " + mimetype);
                Log.d(DOWNLOAD_TAG, "ContentLength: " + contentLength);

                handleDownload(url);
            }
        });

        // 设置 WebChromeClient
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (!isActivityDestroyed && !isFinishing() && progressBar != null) {
                    progressBar.setProgress(newProgress);
                }
                super.onProgressChanged(view, newProgress);
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                }
                uploadMessage = filePathCallback;

                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                boolean isCamera = false;
                boolean isAudio = false;

                if (acceptTypes != null && acceptTypes.length > 0) {
                    for (String acceptType : acceptTypes) {
                        if (acceptType.contains("image") || acceptType.contains("camera")) {
                            isCamera = true;
                        }
                        if (acceptType.contains("audio")) {
                            isAudio = true;
                        }
                    }
                }

                if (isCamera && hasAllPermissions()) {
                    openCamera();
                    return true;
                } else if (isAudio && hasAllPermissions()) {
                    openAudioRecorder();
                    return true;
                } else {
                    Intent intent = fileChooserParams.createIntent();
                    try {
                        startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                    } catch (Exception e) {
                        uploadMessage = null;
                        if (!isActivityDestroyed && !isFinishing()) {
                            Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_LONG).show();
                        }
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest request) {
                Log.d("PermissionRequest", "Requested resources: " + java.util.Arrays.toString(request.getResources()));
                String[] requestedResources = request.getResources();
                boolean grantAll = true;

                for (String resource : requestedResources) {
                    if (resource.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                            !hasCameraPermission()) {
                        grantAll = false;
                        break;
                    }
                    if (resource.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                            !hasAudioPermission()) {
                        grantAll = false;
                        break;
                    }
                }

                if (grantAll) {
                    request.grant(request.getResources());
                    Log.d("PermissionRequest", "Permissions granted");
                } else {
                    request.deny();
                    Log.d("PermissionRequest", "Permissions denied - missing system permissions");
                }
            }
        });
    }

    /**
     * 设置返回键处理，兼容新旧操作系统
     */
    private void setupBackPressedHandler() {
        setupOnBackPressedDispatcher();
    }

    /**
     * 为所有版本设置 OnBackPressedDispatcher
     */
    private void setupOnBackPressedDispatcher() {
        onBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Log.d(SYSTEM_NAV_TAG, "OnBackPressedDispatcher 回调被调用");
                handleBackNavigation();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);
    }

    /**
     * 处理返回导航逻辑 - 修复版本
     */
    private void handleBackNavigation() {
        Log.d(SYSTEM_NAV_TAG, "处理返回导航，当前显示离线页面: " + isShowingOfflinePage);

        // 检查Activity状态
        if (isActivityDestroyed || isFinishing()) {
            return;
        }

        // 检查是否是离线页面
        if (isShowingOfflinePage) {
            // 如果在离线页面，直接退出应用
            Log.d(SYSTEM_NAV_TAG, "在离线页面，直接退出应用");
            finish();
            return;
        }

        // 检查WebView是否可以返回
        if (webView != null && webView.canGoBack()) {
            // 获取当前URL来判断页面状态
            String currentUrl = webView.getUrl();
            Log.d(SYSTEM_NAV_TAG, "当前URL: " + currentUrl + ", canGoBack: " + webView.canGoBack());

            // 如果当前页面是主页面，且可以返回，说明有历史记录
            if (currentUrl != null && (currentUrl.equals(TARGET_URL) || currentUrl.contains(TARGET_URL))) {
                Log.d(SYSTEM_NAV_TAG, "在主页面且有历史记录，返回上一页");
                webView.goBack();
            } else if (currentUrl != null && !currentUrl.equals(TARGET_URL)) {
                // 如果在其他页面，返回上一页
                Log.d(SYSTEM_NAV_TAG, "在其他页面，返回上一页");
                webView.goBack();
            } else {
                // 其他情况显示退出确认
                Log.d(SYSTEM_NAV_TAG, "特殊情况，执行退出检查");
                showExitConfirmation();
            }
        } else {
            // 无法返回时显示退出确认
            Log.d(SYSTEM_NAV_TAG, "WebView无历史记录，执行退出检查");
            showExitConfirmation();
        }
    }

    // 检查是否拥有所有权限
    private boolean hasAllPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (String permission : ANDROID_13_PERMISSIONS) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    // 请求所有权限
    private void requestAllPermissions() {
        Log.d(TAG, "请求所有权限");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String[] allPermissions = new String[REQUIRED_PERMISSIONS.length + ANDROID_13_PERMISSIONS.length];
            System.arraycopy(REQUIRED_PERMISSIONS, 0, allPermissions, 0, REQUIRED_PERMISSIONS.length);
            System.arraycopy(ANDROID_13_PERMISSIONS, 0, allPermissions, REQUIRED_PERMISSIONS.length, ANDROID_13_PERMISSIONS.length);
            ActivityCompat.requestPermissions(this, allPermissions, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    // 处理权限请求结果
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(TAG, "权限请求结果: requestCode=" + requestCode);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            Log.d(TAG, "所有权限是否已授予: " + allGranted);
        }
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(getResources().getColor(android.R.color.white));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }
    }

    /**
     * 检查网络是否可用
     */
    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) {
                    return false;
                }
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                return capabilities != null &&
                        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            } else {
                @SuppressWarnings("deprecation")
                NetworkInfo networkInfo = cm.getActiveNetworkInfo();
                return networkInfo != null && networkInfo.isConnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "检查网络状态时出错", e);
            return false;
        }
    }

    /**
     * 导航到指定页面
     */
    private void navigateToPage(String page) {
        Log.d(WEBVIEW_TAG, "导航到页面: " + page);

        if (!isShowingOfflinePage) {
            // 如果不在离线页面，直接在主页面中处理
            String jsCode = "window.location.href = '/" + page + "';";
            webView.evaluateJavascript(jsCode, null);
        } else {
            // 如果在离线页面，先尝试加载主页面
            safeRetryConnection();

            // 延迟执行页面跳转
            new Handler().postDelayed(() -> {
                String jsCode = "window.location.href = '/" + page + "';";
                webView.evaluateJavascript(jsCode, null);
            }, 1000);
        }
    }

    /**
     * 检查URL是否是真实的媒体URL（不是Blob URL）
     */
    private boolean isRealMediaUrl(String url) {
        if (url == null) return false;

        // 排除Blob URL
        if (url.startsWith("blob:")) return false;

        // 检查是否是媒体文件URL
        boolean isMediaFile = url.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|mp4|mov|avi|wmv|flv|mkv|webm)$");

        // 检查是否是Supabase存储URL（根据你的日志）
        boolean isSupabaseStorage = url.contains("storage/v1/object/public") &&
                (url.contains("_videos/") || url.contains("_images/"));

        return isMediaFile || isSupabaseStorage;
    }

    /**
     * 保存最近请求的真实媒体URL
     */
    private void saveRecentRealUrl(String url) {
        recentRealMp4Url = url;
        lastMp4UrlTime = System.currentTimeMillis();
        Log.d(DOWNLOAD_TAG, "保存真实媒体URL: " + url);
    }

    /**
     * 获取有效的真实媒体URL（检查超时）
     */
    private String getValidRealUrl() {
        if (recentRealMp4Url != null) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastMp4UrlTime < MP4_URL_TIMEOUT) {
                Log.d(DOWNLOAD_TAG, "使用有效的真实URL: " + recentRealMp4Url);
                return recentRealMp4Url;
            } else {
                Log.d(DOWNLOAD_TAG, "真实URL已超时，清除缓存");
                recentRealMp4Url = null;
            }
        }
        return null;
    }

    /**
     * 注入JavaScript监听器来捕获下载点击事件
     */
    private void injectDownloadListener() {
        String jsCode = "(function() {" +
                "    // 监听所有链接点击" +
                "    document.addEventListener('click', function(e) {" +
                "        var target = e.target;" +
                "        while (target && target.nodeName !== 'A') {" +
                "            target = target.parentElement;" +
                "        }" +
                "        if (target && target.href) {" +
                "            var href = target.href.toLowerCase();" +
                "            if (href.match(/\\.(jpg|jpeg|png|gif|bmp|webp|mp4|mov|avi|wmv|flv|mkv|webm)$/)) {" +
                "                e.preventDefault();" +
                "                // 调用Android接口处理下载" +
                "                AndroidApp.handleMediaDownload(target.href);" +
                "                return false;" +
                "            }" +
                "        }" +
                "    });" +
                "    " +
                "    // 监听图片长按事件" +
                "    document.addEventListener('contextmenu', function(e) {" +
                "        if (e.target.nodeName === 'IMG') {" +
                "            var imgSrc = e.target.src;" +
                "            if (imgSrc && imgSrc.match(/\\.(jpg|jpeg|png|gif|bmp|webp)$/)) {" +
                "                AndroidApp.handleMediaDownload(imgSrc);" +
                "            }" +
                "        }" +
                "    }, false);" +
                "    " +
                "    // 监听视频下载按钮" +
                "    var observer = new MutationObserver(function(mutations) {" +
                "        mutations.forEach(function(mutation) {" +
                "            mutation.addedNodes.forEach(function(node) {" +
                "                if (node.nodeType === 1) {" + // ELEMENT_NODE
                "                    if (node.tagName === 'BUTTON' || node.tagName === 'A') {" +
                "                        var text = node.textContent || node.innerText;" +
                "                        if (text && (text.includes('下载') || text.includes('保存') || text.includes('download') || text.includes('save'))) {" +
                "                            node.addEventListener('click', function(e) {" +
                "                                e.preventDefault();" +
                "                                e.stopPropagation();" +
                "                                // 尝试找到相关的视频或图片URL" +
                "                                var mediaElement = node.closest('video, img');" +
                "                                if (mediaElement) {" +
                "                                    AndroidApp.handleMediaDownload(mediaElement.src);" +
                "                                } else {" +
                "                                    // 如果没有找到媒体元素，让Android处理" +
                "                                    AndroidApp.handleDownloadButtonClick();" +
                "                                }" +
                "                            });" +
                "                        }" +
                "                    }" +
                "                }" +
                "            });" +
                "        });" +
                "    });" +
                "    " +
                "    observer.observe(document.body, { childList: true, subtree: true });" +
                "    " +
                "    console.log('下载监听器已注入');" +
                "})();";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(jsCode, null);
        } else {
            webView.loadUrl("javascript:" + jsCode);
        }
        Log.d(JS_TAG, "JavaScript下载监听器已注入");
    }

    /**
     * 检查URL是否是可下载文件
     */
    private boolean isDownloadableFile(String url) {
        if (url == null) return false;

        String lowerUrl = url.toLowerCase();
        boolean isMediaFile = lowerUrl.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|mp4|mov|avi|wmv|flv|mkv|webm)$");

        Log.d(DOWNLOAD_TAG, "检查URL是否可下载: " + url + " -> " + isMediaFile);
        return isMediaFile;
    }

    /**
     * 处理下载请求
     */
    private void handleDownload(String url) {
        Log.d(DOWNLOAD_TAG, "开始处理下载: " + url);

        // 处理Blob URL：如果有最近的真实URL，使用真实URL
        if (url.startsWith("blob:")) {
            String realUrl = getValidRealUrl();
            if (realUrl != null) {
                Log.d(DOWNLOAD_TAG, "Blob URL转换为真实URL: " + realUrl);
                url = realUrl;
            } else {
                Log.e(DOWNLOAD_TAG, "无法处理Blob URL，没有可用的真实URL");
                runOnUiThread(() -> {
                    if (!isActivityDestroyed && !isFinishing()) {
                        Toast.makeText(MainActivity.this, "无法下载此文件格式", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
        }

        // 使用线程池执行下载任务
        String finalUrl = url;
        downloadExecutor.execute(() -> {
            try {
                if (finalUrl.startsWith("http://") || finalUrl.startsWith("https://")) {
                    // 使用DownloadManager下载
                    downloadWithDownloadManager(finalUrl);
                } else if (finalUrl.startsWith("blob:")) {
                    // 如果还有Blob URL，尝试直接处理
                    handleBlobUrlDirectly(finalUrl);
                } else if (finalUrl.startsWith("data:")) {
                    // 处理base64数据
                    handleBase64Data(finalUrl);
                } else {
                    Log.e(DOWNLOAD_TAG, "不支持的URL协议: " + finalUrl);
                    runOnUiThread(() -> {
                        if (!isActivityDestroyed && !isFinishing()) {
                            Toast.makeText(MainActivity.this, "不支持的下载链接类型", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(DOWNLOAD_TAG, "下载处理失败: " + finalUrl, e);
                runOnUiThread(() -> {
                    if (!isActivityDestroyed && !isFinishing()) {
                        Toast.makeText(MainActivity.this, "下载失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /**
     * 直接处理Blob URL（备选方案）
     */
    private void handleBlobUrlDirectly(String blobUrl) {
        Log.d(DOWNLOAD_TAG, "尝试直接处理Blob URL: " + blobUrl);

        // 在UI线程中执行JavaScript来获取Blob数据
        runOnUiThread(() -> {
            if (isActivityDestroyed || isFinishing() || webView == null) {
                return;
            }

            String jsCode =
                    "(function() {" +
                            "    try {" +
                            "        fetch('" + blobUrl + "')" +
                            "            .then(response => response.blob())" +
                            "            .then(blob => {" +
                            "                const url = URL.createObjectURL(blob);" +
                            "                AndroidApp.handleBlobDownload(url, 'video.mp4');" +
                            "            })" +
                            "            .catch(error => {" +
                            "                console.error('Blob下载失败:', error);" +
                            "                AndroidApp.downloadError('下载失败: ' + error.message);" +
                            "            });" +
                            "    } catch (error) {" +
                            "        console.error('处理Blob URL时出错:', error);" +
                            "        AndroidApp.downloadError('处理下载时出错: ' + error.message);" +
                            "    }" +
                            "})();";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript(jsCode, null);
            } else {
                webView.loadUrl("javascript:" + jsCode);
            }
        });
    }

    /**
     * 使用DownloadManager下载文件
     */
    private void downloadWithDownloadManager(String fileUrl) {
        try {
            Log.d(DOWNLOAD_TAG, "使用DownloadManager下载: " + fileUrl);

            // 创建下载请求
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(fileUrl));

            // 设置下载目录 - 使用公共下载目录
            String fileName = getFileNameFromUrl(fileUrl);

            // 使用DownloadManager的公共目录，避免权限问题
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "LingXiApp/" + fileName);

            File destinationFile = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "LingXiApp/" + fileName
            );

            Log.d(DOWNLOAD_TAG, "下载文件名: " + fileName);
            Log.d(DOWNLOAD_TAG, "下载路径: " + destinationFile.getAbsolutePath());

            // 确保目录存在
            File dir = destinationFile.getParentFile();
            if (dir != null && !dir.exists()) {
                boolean created = dir.mkdirs();
                Log.d(DOWNLOAD_TAG, "创建下载目录: " + (created ? "成功" : "失败"));
            }

            // 设置通知可见性
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            // 设置标题和描述
            request.setTitle("下载文件");
            request.setDescription(fileName);

            // 允许移动网络下载
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            // 设置请求头，避免被服务器拒绝
            request.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            request.addRequestHeader("Referer", TARGET_URL);

            // 加入下载队列
            long downloadId = downloadManager.enqueue(request);
            Log.d(DOWNLOAD_TAG, "下载已加入队列，ID: " + downloadId);

            // 在UI线程启动手动检查
            runOnUiThread(() -> startManualDownloadCheck(downloadId, destinationFile));

        } catch (Exception e) {
            Log.e(DOWNLOAD_TAG, "DownloadManager下载失败", e);
            // 降级到直接下载
            downloadDirectly(fileUrl);
        }
    }

    /**
     * 启动手动下载检查（备用方案）
     */
    private void startManualDownloadCheck(long downloadId, File destinationFile) {
        Log.d(DOWNLOAD_TAG, "启动手动下载检查，ID: " + downloadId);

        // 检查Activity状态
        if (isActivityDestroyed || isFinishing()) {
            return;
        }

        // 使用主线程的Handler
        new Handler(getMainLooper()).postDelayed(new Runnable() {
            private int checkCount = 0;
            private final int maxChecks = 10;
            private final long checkInterval = 2000;

            @Override
            public void run() {
                // 检查Activity状态
                if (isActivityDestroyed || isFinishing()) {
                    return;
                }

                checkCount++;
                Log.d(DOWNLOAD_TAG, "第" + checkCount + "次手动检查下载状态");

                // 检查文件是否存在且大小大于0
                if (destinationFile.exists() && destinationFile.length() > 0) {
                    Log.d(DOWNLOAD_TAG, "手动检查发现文件已下载完成，文件大小: " + destinationFile.length());
                    saveToGallery(destinationFile);
                    return;
                }

                // 检查下载状态
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);

                try (Cursor cursor = downloadManager.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        if (statusIndex >= 0) {
                            int status = cursor.getInt(statusIndex);
                            Log.d(DOWNLOAD_TAG, "下载状态: " + status);

                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                Log.d(DOWNLOAD_TAG, "下载状态显示成功");
                                if (destinationFile.exists()) {
                                    saveToGallery(destinationFile);
                                    return;
                                }
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                Log.e(DOWNLOAD_TAG, "下载状态显示失败");
                                runOnUiThread(() -> {
                                    if (!isActivityDestroyed && !isFinishing()) {
                                        Toast.makeText(MainActivity.this, "下载失败", Toast.LENGTH_SHORT).show();
                                    }
                                });
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(DOWNLOAD_TAG, "检查下载状态时出错", e);
                }

                if (checkCount < maxChecks) {
                    // 继续检查
                    new Handler(getMainLooper()).postDelayed(this, checkInterval);
                } else {
                    Log.d(DOWNLOAD_TAG, "手动检查达到最大次数，停止检查");
                    runOnUiThread(() -> {
                        if (!isActivityDestroyed && !isFinishing()) {
                            Toast.makeText(MainActivity.this, "下载超时，请检查网络连接", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }, 1000);
    }

    /**
     * 直接下载文件（不使用DownloadManager）
     */
    private void downloadDirectly(String fileUrl) {
        Log.d(DOWNLOAD_TAG, "开始直接下载: " + fileUrl);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(fileUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);

            // 设置请求头
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            connection.setRequestProperty("Referer", TARGET_URL);

            connection.connect();

            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                String fileName = getFileNameFromUrl(fileUrl);
                File outputFile = new File(downloadDirectory, fileName);

                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream outputStream = new FileOutputStream(outputFile)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }

                    Log.d(DOWNLOAD_TAG, "直接下载完成: " + outputFile.getAbsolutePath() + ", 大小: " + totalBytes + " bytes");

                    // 保存到相册
                    saveToGallery(outputFile);

                }
            } else {
                Log.e(DOWNLOAD_TAG, "HTTP错误: " + responseCode);
                runOnUiThread(() -> {
                    if (!isActivityDestroyed && !isFinishing()) {
                        Toast.makeText(MainActivity.this, "下载失败: HTTP " + responseCode, Toast.LENGTH_SHORT).show();
                    }
                });
            }

        } catch (Exception e) {
            Log.e(DOWNLOAD_TAG, "直接下载失败", e);
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    String errorMessage = "下载失败";
                    if (e.getMessage() != null) {
                        errorMessage += ": " + e.getMessage();
                    }
                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                }
            });
        } finally {
            // 确保连接被关闭
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 处理下载完成 - 修复版本
     */
    private void handleDownloadComplete(long downloadId) {
        Log.d(DOWNLOAD_TAG, "开始处理下载完成，ID: " + downloadId);

        // 延迟检查，确保下载管理器有足够时间更新状态
        new Handler().postDelayed(() -> {
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);

            Cursor cursor = null;
            try {
                cursor = downloadManager.query(query);

                if (cursor != null && cursor.moveToFirst()) {
                    // 获取列索引（安全方式）
                    int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                    int localFilenameIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME);

                    Log.d(DOWNLOAD_TAG, "找到下载记录，开始解析状态");

                    if (statusIndex >= 0) {
                        int status = cursor.getInt(statusIndex);
                        Log.d(DOWNLOAD_TAG, "下载状态: " + status);

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            String filePath = null;

                            // 优先尝试获取本地URI
                            if (localUriIndex >= 0) {
                                String uriString = cursor.getString(localUriIndex);
                                if (uriString != null) {
                                    Uri uri = Uri.parse(uriString);
                                    filePath = uri.getPath();
                                    Log.d(DOWNLOAD_TAG, "从URI获取文件路径: " + filePath);
                                }
                            }

                            // 如果URI方式失败，尝试直接文件名方式
                            if (filePath == null && localFilenameIndex >= 0) {
                                filePath = cursor.getString(localFilenameIndex);
                                Log.d(DOWNLOAD_TAG, "从文件名获取文件路径: " + filePath);
                            }

                            if (filePath != null) {
                                File file = new File(filePath);
                                handleSuccessfulDownload(file, downloadId);
                            } else {
                                Log.e(DOWNLOAD_TAG, "无法获取下载文件路径");
                                runOnUiThread(() -> {
                                    if (!isActivityDestroyed && !isFinishing()) {
                                        Toast.makeText(MainActivity.this, "下载完成但无法找到文件", Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            int reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                            if (reasonIndex >= 0) {
                                int reason = cursor.getInt(reasonIndex);
                                Log.e(DOWNLOAD_TAG, "下载失败，原因代码: " + reason);
                            }
                            runOnUiThread(() -> {
                                if (!isActivityDestroyed && !isFinishing()) {
                                    Toast.makeText(MainActivity.this, "下载失败，请重试", Toast.LENGTH_LONG).show();
                                }
                            });
                        } else {
                            Log.d(DOWNLOAD_TAG, "下载仍在进行中，状态: " + status);
                        }
                    } else {
                        Log.e(DOWNLOAD_TAG, "状态列不存在");
                    }
                } else {
                    Log.e(DOWNLOAD_TAG, "未找到下载记录或游标为空");
                    // 重试机制
                    retryDownloadCheck(downloadId, 1);
                }
            } catch (Exception e) {
                Log.e(DOWNLOAD_TAG, "处理下载完成时出错", e);
                runOnUiThread(() -> {
                    if (!isActivityDestroyed && !isFinishing()) {
                        Toast.makeText(MainActivity.this, "处理下载时发生错误", Toast.LENGTH_SHORT).show();
                    }
                });
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }, 1000);
    }

    /**
     * 处理成功下载
     */
    private void handleSuccessfulDownload(File file, long downloadId) {
        Log.d(DOWNLOAD_TAG, "处理成功下载: " + file.getAbsolutePath());
        Log.d(DOWNLOAD_TAG, "文件存在: " + file.exists());
        Log.d(DOWNLOAD_TAG, "文件大小: " + file.length());

        if (file.exists() && file.length() > 0) {
            // 保存到相册
            saveToGallery(file);

            // 额外显示成功提示
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    new Handler().postDelayed(() -> {
                        String fileType = getFileType(file.getAbsolutePath());
                        Toast.makeText(MainActivity.this, fileType + "已成功保存到相册！", Toast.LENGTH_LONG).show();
                        Log.d(DOWNLOAD_TAG, "下载和保存流程完成");
                    }, 1000);
                }
            });
        } else {
            Log.e(DOWNLOAD_TAG, "下载文件无效: 存在=" + file.exists() + ", 大小=" + file.length());
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    Toast.makeText(MainActivity.this, "下载文件损坏，请重新下载", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /**
     * 重试下载检查
     */
    private void retryDownloadCheck(long downloadId, int retryCount) {
        if (retryCount > 3) {
            Log.e(DOWNLOAD_TAG, "达到最大重试次数，放弃检查下载ID: " + downloadId);
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    Toast.makeText(MainActivity.this, "下载可能已完成，但无法确认状态", Toast.LENGTH_LONG).show();
                }
            });
            return;
        }

        Log.d(DOWNLOAD_TAG, "第" + retryCount + "次重试检查下载ID: " + downloadId);

        new Handler().postDelayed(() -> {
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);

            try (Cursor cursor = downloadManager.query(query)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    if (statusIndex >= 0) {
                        int status = cursor.getInt(statusIndex);
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            Log.d(DOWNLOAD_TAG, "重试成功：下载已完成");
                            runOnUiThread(() -> {
                                if (!isActivityDestroyed && !isFinishing()) {
                                    Toast.makeText(MainActivity.this, "下载完成！", Toast.LENGTH_LONG).show();
                                }
                            });
                            return;
                        }
                    }
                }
                // 继续重试
                retryDownloadCheck(downloadId, retryCount + 1);
            } catch (Exception e) {
                Log.e(DOWNLOAD_TAG, "重试检查时出错", e);
                retryDownloadCheck(downloadId, retryCount + 1);
            }
        }, 2000);
    }

    /**
     * 保存文件到相册 - 增强版本
     */
    private void saveToGallery(File file) {
        Log.d(GALLERY_TAG, "开始保存到相册: " + file.getAbsolutePath());

        if (!isFileDownloadComplete(file)) {
            Log.e(GALLERY_TAG, "文件未下载完成或无效");
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    Toast.makeText(MainActivity.this, "文件未下载完成，无法保存", Toast.LENGTH_LONG).show();
                }
            });
            return;
        }

        String filePath = file.getAbsolutePath();
        String mimeType = getMimeType(filePath);
        String fileType = getFileType(filePath);

        Log.d(GALLERY_TAG, "文件MIME类型: " + mimeType);
        Log.d(GALLERY_TAG, "文件类型: " + fileType);

        // 显示保存中的提示
        runOnUiThread(() -> {
            if (!isActivityDestroyed && !isFinishing()) {
                Toast.makeText(MainActivity.this, "正在保存" + fileType + "到相册...", Toast.LENGTH_SHORT).show();
            }
        });

        // 使用MediaScanner扫描文件到相册
        MediaScannerConnection.scanFile(this,
                new String[]{filePath},
                new String[]{mimeType},
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        Log.d(GALLERY_TAG, "媒体扫描完成 - 路径: " + path + ", URI: " + uri);

                        runOnUiThread(() -> {
                            if (!isActivityDestroyed && !isFinishing()) {
                                if (uri != null) {
                                    // 显示下载成功提示
                                    showDownloadSuccess(path);
                                    Log.d(GALLERY_TAG, "文件成功保存到相册: " + path);
                                } else {
                                    String errorMessage = fileType + "保存到相册失败，请检查权限";
                                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                                    Log.e(GALLERY_TAG, "保存到相册失败: " + path);
                                }
                            }
                        });
                    }
                });
    }

    /**
     * 检查文件是否已下载完成
     */
    private boolean isFileDownloadComplete(File file) {
        if (file == null || !file.exists()) {
            return false;
        }

        // 文件存在且大小大于1KB（避免0字节文件）
        boolean isComplete = file.length() > 1024;
        Log.d(DOWNLOAD_TAG, "文件检查: " + file.getAbsolutePath() +
                ", 存在: " + file.exists() +
                ", 大小: " + file.length() +
                ", 完成: " + isComplete);

        return isComplete;
    }

    /**
     * 显示下载成功提示
     */
    private void showDownloadSuccess(String filePath) {
        runOnUiThread(() -> {
            if (!isActivityDestroyed && !isFinishing()) {
                String fileType = getFileType(filePath);

                // 创建自定义Toast或对话框显示在页面中央
                Toast toast = Toast.makeText(MainActivity.this,
                        fileType + "下载完成！已保存到相册",
                        Toast.LENGTH_LONG);

                // 设置Toast位置为居中
                toast.setGravity(android.view.Gravity.CENTER, 0, 0);
                toast.show();

                Log.d(DOWNLOAD_TAG, "显示下载成功提示: " + filePath);
            }
        });
    }

    /**
     * 处理base64数据
     */
    private void handleBase64Data(String dataUrl) {
        Log.d(DOWNLOAD_TAG, "处理base64数据");
        try {
            // 简化处理，实际需要解析data URL
            String fileName = "image_" + System.currentTimeMillis() + ".jpg";
            File outputFile = new File(downloadDirectory, fileName);

            Log.w(DOWNLOAD_TAG, "Base64数据下载暂未实现");
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    Toast.makeText(MainActivity.this, "Base64图片下载功能暂未实现", Toast.LENGTH_SHORT).show();
                }
            });

        } catch (Exception e) {
            Log.e(DOWNLOAD_TAG, "处理base64数据失败", e);
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    Toast.makeText(MainActivity.this, "Base64图片处理失败", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * 从URL获取文件名
     */
    private String getFileNameFromUrl(String url) {
        String fileName = URLUtil.guessFileName(url, null, null);
        if (fileName == null || fileName.isEmpty()) {
            fileName = "download_" + System.currentTimeMillis();

            // 根据URL添加扩展名
            if (url.contains(".jpg") || url.contains(".jpeg")) {
                fileName += ".jpg";
            } else if (url.contains(".png")) {
                fileName += ".png";
            } else if (url.contains(".gif")) {
                fileName += ".gif";
            } else if (url.contains(".mp4")) {
                fileName += ".mp4";
            } else if (url.contains(".mov")) {
                fileName += ".mov";
            } else {
                fileName += ".tmp";
            }
        }
        return fileName;
    }

    /**
     * 获取文件的MIME类型
     */
    private String getMimeType(String filePath) {
        String extension = filePath.substring(filePath.lastIndexOf(".") + 1).toLowerCase();
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "webp":
                return "image/webp";
            case "mp4":
                return "video/mp4";
            case "mov":
                return "video/quicktime";
            case "avi":
                return "video/x-msvideo";
            case "wmv":
                return "video/x-ms-wmv";
            case "flv":
                return "video/x-flv";
            case "mkv":
                return "video/x-matroska";
            case "webm":
                return "video/webm";
            default:
                return "application/octet-stream";
        }
    }

    // 检查相机权限
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // 检查录音权限
    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // 打开相机
    private void openCamera() {
        try {
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                File photoFile = createImageFile();
                if (photoFile != null) {
                    cameraImageUri = FileProvider.getUriForFile(this,
                            getApplicationContext().getPackageName() + ".provider",
                            photoFile);
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
                    startActivityForResult(takePictureIntent, CAMERA_CAPTURE_REQUEST_CODE);
                }
            }
        } catch (Exception e) {
            Log.e("Camera", "Error opening camera", e);
            if (!isActivityDestroyed && !isFinishing()) {
                Toast.makeText(this, "无法打开相机", Toast.LENGTH_SHORT).show();
            }
            if (uploadMessage != null) {
                uploadMessage.onReceiveValue(null);
                uploadMessage = null;
            }
        }
    }

    // 打开录音机
    private void openAudioRecorder() {
        try {
            Intent recordAudioIntent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
            if (recordAudioIntent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(recordAudioIntent, AUDIO_CAPTURE_REQUEST_CODE);
                return;
            }

            if (!isActivityDestroyed && !isFinishing()) {
                Toast.makeText(this, "使用文件选择器选择音频文件", Toast.LENGTH_SHORT).show();
            }
            Intent fileIntent = new Intent(Intent.ACTION_GET_CONTENT);
            fileIntent.setType("audio/*");
            fileIntent.addCategory(Intent.CATEGORY_OPENABLE);

            try {
                startActivityForResult(fileIntent, AUDIO_CAPTURE_REQUEST_CODE);
            } catch (Exception ex) {
                if (!isActivityDestroyed && !isFinishing()) {
                    Toast.makeText(this, "无法打开音频文件选择器", Toast.LENGTH_SHORT).show();
                }
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
            }

        } catch (Exception e) {
            Log.e("Audio", "Error opening audio recorder", e);
            if (!isActivityDestroyed && !isFinishing()) {
                Toast.makeText(this, "无法打开录音功能", Toast.LENGTH_SHORT).show();
            }
            if (uploadMessage != null) {
                uploadMessage.onReceiveValue(null);
                uploadMessage = null;
            }
        }
    }

    // 创建图片文件
    private File createImageFile() throws IOException {
        @SuppressLint("SimpleDateFormat")
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (storageDir != null && !storageDir.exists()) {
            storageDir.mkdirs();
        }
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    // 处理活动结果
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (uploadMessage == null) return;

        Uri[] results = null;

        if (resultCode == Activity.RESULT_OK) {
            switch (requestCode) {
                case FILE_CHOOSER_RESULT_CODE:
                    if (data != null) {
                        results = getSelectedFiles(data);
                    }
                    break;

                case CAMERA_CAPTURE_REQUEST_CODE:
                    if (cameraImageUri != null) {
                        results = new Uri[]{cameraImageUri};
                        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        mediaScanIntent.setData(cameraImageUri);
                        sendBroadcast(mediaScanIntent);
                    }
                    break;

                case AUDIO_CAPTURE_REQUEST_CODE:
                    if (data != null && data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                    break;
            }
        }

        uploadMessage.onReceiveValue(results);
        uploadMessage = null;
        cameraImageUri = null;
    }

    // 获取选择的文件
    private Uri[] getSelectedFiles(Intent data) {
        Uri[] results = null;
        String dataString = data.getDataString();
        ClipData clipData = data.getClipData();

        if (clipData != null) {
            results = new Uri[clipData.getItemCount()];
            for (int i = 0; i < clipData.getItemCount(); i++) {
                ClipData.Item item = clipData.getItemAt(i);
                results[i] = item.getUri();
            }
        } else if (dataString != null) {
            results = new Uri[]{Uri.parse(dataString)};
        } else if (data.getData() != null) {
            results = new Uri[]{data.getData()};
        }

        return results;
    }

    // 显示退出确认
    private void showExitConfirmation() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - backPressedTime < BACK_PRESSED_INTERVAL) {
            // 在2秒内按了两次返回键，退出应用
            Log.d(SYSTEM_NAV_TAG, "确认退出应用");
            finish();
        } else {
            // 第一次按返回键，提示再按一次退出
            Log.d(SYSTEM_NAV_TAG, "第一次按返回键，提示再按一次退出");
            backPressedTime = currentTime;
            if (!isActivityDestroyed && !isFinishing()) {
                Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 使用DownloadManager下载Blob URL
     */
    private void downloadBlobWithDownloadManager(String blobUrl, String fileName) {
        try {
            Log.d(DOWNLOAD_TAG, "使用DownloadManager下载Blob URL: " + blobUrl);

            // 创建下载请求
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(blobUrl));

            // 设置下载目录和文件名
            if (fileName == null || fileName.isEmpty()) {
                fileName = "video_" + System.currentTimeMillis() + ".mp4";
            }
            File destinationFile = new File(downloadDirectory, fileName);

            Log.d(DOWNLOAD_TAG, "Blob下载文件名: " + fileName);
            Log.d(DOWNLOAD_TAG, "Blob下载路径: " + destinationFile.getAbsolutePath());

            request.setDestinationUri(Uri.fromFile(destinationFile));

            // 设置通知可见性
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            // 设置标题和描述
            request.setTitle("下载视频");
            request.setDescription(fileName);

            // 允许移动网络下载
            request.setAllowedOverMetered(true);

            // 加入下载队列
            long downloadId = downloadManager.enqueue(request);
            Log.d(DOWNLOAD_TAG, "Blob下载已加入队列，ID: " + downloadId);
        } catch (Exception e) {
            Log.e(DOWNLOAD_TAG, "Blob DownloadManager下载失败", e);
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    Toast.makeText(MainActivity.this, "Blob下载失败", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * 获取文件类型（用于显示提示信息）
     */
    private String getFileType(String filePath) {
        if (filePath.toLowerCase().endsWith(".mp4") ||
                filePath.toLowerCase().endsWith(".mov") ||
                filePath.toLowerCase().endsWith(".avi")) {
            return "视频";
        } else if (filePath.toLowerCase().endsWith(".jpg") ||
                filePath.toLowerCase().endsWith(".jpeg") ||
                filePath.toLowerCase().endsWith(".png") ||
                filePath.toLowerCase().endsWith(".gif")) {
            return "图片";
        } else {
            return "文件";
        }
    }

    // JavaScript接口类，用于处理网页调用
    public class JavaScriptInterface {
        @android.webkit.JavascriptInterface
        public void onExitButtonClicked() {
            Log.d(TAG, "JavaScript接口: 网页退出按钮被点击");
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    Log.d(TAG, "处理网页内的退出按钮");
                    // 网页内的退出按钮直接退出应用，不需要确认
                    finish();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void logMessage(String message) {
            Log.d(JS_TAG, "来自JavaScript: " + message);
        }

        @android.webkit.JavascriptInterface
        public void handleMediaDownload(String url) {
            Log.d(JS_TAG, "JavaScript调用下载: " + url);
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    handleDownload(url);
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void handleBlobDownload(String blobUrl, String fileName) {
            Log.d(JS_TAG, "JavaScript调用处理Blob下载: " + blobUrl + ", 文件名: " + fileName);
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    // 使用DownloadManager下载Blob URL
                    downloadBlobWithDownloadManager(blobUrl, fileName);
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void downloadError(String errorMessage) {
            Log.e(JS_TAG, "JavaScript报告下载错误: " + errorMessage);
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    Toast.makeText(MainActivity.this, "下载失败: " + errorMessage, Toast.LENGTH_LONG).show();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void handleDownloadButtonClick() {
            Log.d(JS_TAG, "JavaScript调用下载按钮点击");
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    // 尝试使用最近捕获的真实URL
                    String realUrl = getValidRealUrl();
                    if (realUrl != null) {
                        handleDownload(realUrl);
                    } else {
                        Toast.makeText(MainActivity.this, "无法找到可下载的媒体文件", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void saveImageToGallery(String imageData) {
            Log.d(JS_TAG, "JavaScript调用保存图片到相册");
            // 这里可以处理base64图片数据
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    Toast.makeText(MainActivity.this, "保存图片功能", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void retryConnection() {
            Log.d(JS_TAG, "JavaScript调用重试连接");
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    safeRetryConnection();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void navigateTo(String page) {
            Log.d(JS_TAG, "JavaScript调用导航到: " + page);
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    navigateToPage(page);
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void networkRestored() {
            Log.d(JS_TAG, "JavaScript报告网络恢复");
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing()) {
                    if (isShowingOfflinePage) {
                        Log.d(JS_TAG, "网络恢复，自动重试连接");
                        // 直接调用重试连接，而不是通过Handler
                        safeRetryConnection();
                    } else {
                        Log.d(JS_TAG, "网络恢复，但当前不在离线页面，无需重试");
                    }
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void safeRefresh() {
            Log.d(JS_TAG, "JavaScript调用安全刷新");
            runOnUiThread(() -> {
                if (!isActivityDestroyed && !isFinishing() && webView != null) {
                    // 简单的刷新，不涉及状态重置
                    webView.reload();
                }
            });
        }
    }

    /**
     * 处理配置变化，防止Activity重建
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "配置发生变化，但Activity不会重建");
        // 这里可以处理UI适配，但Activity不会重建
    }

    /**
     * 保存WebView状态
     */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        Log.d(TAG, "保存实例状态");
        // 先保存WebView状态
        if (webView != null) {
            webView.saveState(outState);
        }
        // 保存其他重要状态
        outState.putBoolean("isShowingOfflinePage", isShowingOfflinePage);
        super.onSaveInstanceState(outState);
    }

    /**
     * 恢复WebView状态
     */
    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        Log.d(TAG, "恢复实例状态");
        super.onRestoreInstanceState(savedInstanceState);
        if (webView != null) {
            webView.restoreState(savedInstanceState);
        }
        isShowingOfflinePage = savedInstanceState.getBoolean("isShowingOfflinePage", false);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        isActivityDestroyed = true;

        // 清理网络监控相关资源
        tearDownNetworkMonitoring();

        // 清理下载相关资源
        if (downloadCompleteReceiver != null) {
            try {
                unregisterReceiver(downloadCompleteReceiver);
                downloadCompleteReceiver = null;
            } catch (Exception e) {
                Log.e(TAG, "取消注册广播接收器时出错", e);
            }
        }
        if (downloadExecutor != null) {
            downloadExecutor.shutdown();
            downloadExecutor = null;
        }
        if (onBackPressedCallback != null) {
            onBackPressedCallback.remove();
            onBackPressedCallback = null;
        }
        if (webView != null) {
            // 先将WebView从父容器中移除
            if (webView.getParent() != null) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}