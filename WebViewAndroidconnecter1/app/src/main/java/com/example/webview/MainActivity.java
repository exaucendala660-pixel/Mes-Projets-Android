package com.example.webview;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String URL_SITE = "http://192.168.2.225";
    private static final String URL_OFFLINE = "file:///android_asset/not_connect.html";
    private static final long DELAI_NOUVEL_ESSAI = 5000; // 5 secondes

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ValueCallback<Uri[]> filePathCallback;

    // Contrôle de la connexion
    private boolean offlineAffiche = false;
    private boolean verificationEnCours = false;
    private String derniereUrl = URL_SITE;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Tant que la page "Not connect" est affichée, on revérifie le serveur toutes les 5 s
    private final Runnable nouvelEssai = new Runnable() {
        @Override
        public void run() {
            if (offlineAffiche) {
                verifierServeur();
                handler.postDelayed(this, DELAI_NOUVEL_ESSAI);
            }
        }
    };

    // Résultat du sélecteur de fichiers (upload photo / vidéo / fichier)
    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Uri[] results = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    ClipData clip = data.getClipData();
                    if (clip != null) {
                        results = new Uri[clip.getItemCount()];
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            results[i] = clip.getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(results);
                    filePathCallback = null;
                }
            });

    // Demande des permissions de stockage / médias
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                // Rien de spécial : le sélecteur de fichiers fonctionne dans tous les cas
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Icônes blanches (heure, batterie...) sur fond bleu foncé
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);

        // Évite le voile gris ajouté par Android sur la barre du bas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        // Laisse l'espace en haut et en bas pour les barres système
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        demanderPermissions();

        webView = findViewById(R.id.webview);
        swipeRefresh = findViewById(R.id.swipe_refresh);

        configurerActualisation();
        configurerWebView();
        gererBoutonRetour();
        surveillerConnexion();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(URL_SITE);
        }
    }

    // ---------------------------------------------------------------
    // CONTRÔLE DE LA CONNEXION
    // ---------------------------------------------------------------

    // Écoute les coupures / retours du réseau
    private void surveillerConnexion() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // Le réseau est revenu : on vérifie que le serveur répond
                runOnUiThread(() -> {
                    if (offlineAffiche) {
                        verifierServeur();
                    }
                });
            }

            @Override
            public void onLost(Network network) {
                // Le réseau est coupé : on affiche la page Not connect
                runOnUiThread(() -> {
                    if (!offlineAffiche) {
                        String urlActuelle = webView.getUrl();
                        if (urlActuelle != null && urlActuelle.startsWith("http")) {
                            derniereUrl = urlActuelle;
                        }
                        afficherHorsLigne();
                    }
                });
            }
        };
        connectivityManager.registerDefaultNetworkCallback(networkCallback);
    }

    // Affiche la page locale stockée dans l'application
    private void afficherHorsLigne() {
        offlineAffiche = true;
        swipeRefresh.setRefreshing(false);
        webView.loadUrl(URL_OFFLINE);
        handler.removeCallbacks(nouvelEssai);
        handler.postDelayed(nouvelEssai, DELAI_NOUVEL_ESSAI);
    }

    // Teste si le serveur répond (en arrière-plan)
    private void verifierServeur() {
        if (verificationEnCours) return;
        verificationEnCours = true;

        executor.execute(() -> {
            boolean serveurJoignable = false;
            try {
                HttpURLConnection connexion = (HttpURLConnection) new URL(URL_SITE).openConnection();
                connexion.setConnectTimeout(3000);
                connexion.setReadTimeout(3000);
                connexion.getResponseCode(); // toute réponse = le serveur est joignable
                connexion.disconnect();
                serveurJoignable = true;
            } catch (Exception ignored) {
            }

            final boolean joignable = serveurJoignable;
            runOnUiThread(() -> {
                verificationEnCours = false;
                if (joignable && offlineAffiche) {
                    reconnecter();
                }
            });
        });
    }

    // Retour automatique sur le site, à la dernière page visitée
    private void reconnecter() {
        offlineAffiche = false;
        handler.removeCallbacks(nouvelEssai);
        Toast.makeText(this, "Connexion rétablie", Toast.LENGTH_SHORT).show();
        webView.loadUrl(derniereUrl);
    }

    // ---------------------------------------------------------------
    // ACTUALISATION (tirer vers le bas)
    // ---------------------------------------------------------------
    private void configurerActualisation() {
        swipeRefresh.setColorSchemeColors(
                Color.parseColor("#1E6BFF"),
                Color.parseColor("#FFC400"),
                Color.parseColor("#E5252A"));
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#16233D"));

        swipeRefresh.setOnRefreshListener(() -> {
            if (offlineAffiche) {
                // Hors ligne : on retente de joindre le serveur
                verifierServeur();
                swipeRefresh.setRefreshing(false);
            } else {
                webView.reload();
            }
        });

        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> webView.getScrollY() > 0);
    }

    private void demanderPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            });
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            });
        }
    }

    private void configurerWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true); // nécessaire pour lire not_connect.html dans assets
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Cookies (pour garder la session de connexion)
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    String urlEnErreur = request.getUrl().toString();
                    // On ignore une éventuelle erreur de la page locale elle-même
                    if (!urlEnErreur.equals(URL_OFFLINE)) {
                        derniereUrl = urlEnErreur;
                        afficherHorsLigne();
                    }
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                swipeRefresh.setRefreshing(false);
                CookieManager.getInstance().flush();
                // Retient la dernière vraie page du site (pas la page Not connect)
                if (url != null && url.startsWith("http")) {
                    derniereUrl = url;
                }
            }
        });

        // Gère le bouton "Choisir un fichier" du site (upload)
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    fileChooserLauncher.launch(intent);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this,
                            "Impossible d'ouvrir le sélecteur de fichiers",
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        // Téléchargement de fichiers depuis le site
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                String cookies = CookieManager.getInstance().getCookie(url);
                request.addRequestHeader("Cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                request.setMimeType(mimeType);
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                request.setTitle(fileName);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.enqueue(request);
                Toast.makeText(this, "Téléchargement : " + fileName, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Échec du téléchargement", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Bouton Retour
    private void gererBoutonRetour() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (offlineAffiche) {
                    // Sur la page Not connect : on quitte l'application
                    finish();
                } else if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
    }
}