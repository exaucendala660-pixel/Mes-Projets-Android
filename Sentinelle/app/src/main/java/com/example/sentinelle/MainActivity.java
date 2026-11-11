package com.example.sentinelle;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // ====== Réglages de la détection (tu peux les changer) ======
    private static final int SEUIL_PIXEL = 30;       // différence de luminosité (0-255)
    private static final float SEUIL_ZONE = 0.05f;   // 5% de l'image doit changer
    private static final long PAUSE_ENTRE_ALERTES = 8000; // ms
    private static final long DUREE_VIDEO = 5000;    // ms (vidéo auto)
    private static final long DUREE_ALERTE = 4000;   // ms
    private static final String CANAL = "alertes";

    // ====== Éléments de l'écran ======
    private PreviewView previewView;
    private TextView textEtat, textCount, textLast, placeholder, alertOverlay, badgeRec;
    private TextView btnDemarrer, btnStop, btnPhoto, btnVideo, btnHistorique;
    private View dashboard, historique;
    private LinearLayout listeHistorique;

    // ====== Caméra ======
    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    // ====== État ======
    private volatile boolean surveillance = false;
    private int detections = 0;
    private long startTime = 0;
    private long lastDetection = 0;
    private byte[] previous = null;

    // ====== Alertes ======
    private Vibrator vibrator;
    private Ringtone ringtone;
    private ValueAnimator alertAnim;

    private final Runnable arreterAlerteRunnable = this::arreterAlerte;
    private final Runnable arreterVideoRunnable = this::arreterVideo;

    // ====== Demande des autorisations ======
    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (cameraAutorisee()) {
                            demarrer();
                        } else {
                            Toast.makeText(this, "Autorisation caméra refusée", Toast.LENGTH_LONG).show();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);

        // Fond dégradé
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0B1026"), Color.parseColor("#1B1B4B"),
                        Color.parseColor("#2E1A5E")}));

        // Récupération des vues
        previewView = findViewById(R.id.previewView);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        textEtat = findViewById(R.id.textEtat);
        textCount = findViewById(R.id.textCount);
        textLast = findViewById(R.id.textLast);
        placeholder = findViewById(R.id.placeholder);
        alertOverlay = findViewById(R.id.alertOverlay);
        badgeRec = findViewById(R.id.badgeRec);
        btnDemarrer = findViewById(R.id.btnDemarrer);
        btnStop = findViewById(R.id.btnStop);
        btnPhoto = findViewById(R.id.btnPhoto);
        btnVideo = findViewById(R.id.btnVideo);
        btnHistorique = findViewById(R.id.btnHistorique);
        dashboard = findViewById(R.id.dashboard);
        historique = findViewById(R.id.historique);
        listeHistorique = findViewById(R.id.listeHistorique);
        TextView btnRetour = findViewById(R.id.btnRetour);

        // Design
        View cameraCard = findViewById(R.id.cameraCard);
        cameraCard.setBackground(verre(28, "#14FFFFFF", "#33FFFFFF"));
        cameraCard.setClipToOutline(true);
        findViewById(R.id.cardCount).setBackground(verre(20, "#14FFFFFF", "#26FFFFFF"));
        findViewById(R.id.cardLast).setBackground(verre(20, "#14FFFFFF", "#26FFFFFF"));
        badgeRec.setBackground(verre(20, "#EEEF4444", "#00000000"));
        btnDemarrer.setBackground(degrade(29, "#10B981", "#3B82F6"));
        btnStop.setBackground(verre(29, "#26EF4444", "#80EF4444"));
        for (TextView b : new TextView[]{btnPhoto, btnVideo, btnHistorique, btnRetour}) {
            b.setBackground(verre(26, "#1AFFFFFF", "#33FFFFFF"));
            effetAppui(b);
        }
        effetAppui(btnDemarrer);
        effetAppui(btnStop);

        // Animation d'entrée
        dashboard.setAlpha(0f);
        dashboard.animate().alpha(1f).setDuration(900).start();

        // Vibreur
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        creerCanalNotification();
        majEtat(false);

        // Boutons
        btnDemarrer.setOnClickListener(v -> demarrer());
        btnStop.setOnClickListener(v -> arreter());
        btnPhoto.setOnClickListener(v -> prendrePhoto());
        btnVideo.setOnClickListener(v -> {
            if (!surveillance) {
                Toast.makeText(this, "Appuie d'abord sur DÉMARRER", Toast.LENGTH_SHORT).show();
            } else if (recording != null) {
                arreterVideo();
            } else {
                demarrerVideo(0);
            }
        });
        btnHistorique.setOnClickListener(v -> ouvrirHistorique());
        btnRetour.setOnClickListener(v -> fermerHistorique());

        // Bouton retour du téléphone
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (historique.getVisibility() == View.VISIBLE) {
                    fermerHistorique();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    // =====================================================
    //  AUTORISATIONS
    // =====================================================
    private boolean cameraAutorisee() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String[] autorisationsNecessaires() {
        List<String> l = new ArrayList<>();
        l.add(Manifest.permission.CAMERA);
        l.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return l.toArray(new String[0]);
    }

    // =====================================================
    //  DÉMARRER / ARRÊTER LA SURVEILLANCE
    // =====================================================
    private void demarrer() {
        if (surveillance) return;
        if (!cameraAutorisee()) {
            permLauncher.launch(autorisationsNecessaires());
            return;
        }
        ListenableFuture<ProcessCameraProvider> futur = ProcessCameraProvider.getInstance(this);
        futur.addListener(() -> {
            try {
                cameraProvider = futur.get();
                brancherCamera();
            } catch (Exception e) {
                Toast.makeText(this, "Impossible d'ouvrir la caméra", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void brancherCamera() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build();
        videoCapture = VideoCapture.withOutput(recorder);

        ImageAnalysis analyse = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analyse.setAnalyzer(cameraExecutor, this::analyserImage);

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, videoCapture, analyse);
        } catch (Exception e) {
            Toast.makeText(this, "Erreur caméra : " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        previous = null;
        startTime = SystemClock.elapsedRealtime();
        surveillance = true;
        majEtat(true);
    }

    private void arreter() {
        surveillance = false;
        main.removeCallbacksAndMessages(null);
        arreterAlerte();
        majEtat(false);
        if (recording != null) {
            recording.stop();
            main.postDelayed(this::debrancherCamera, 800); // laisse finir la vidéo
        } else {
            debrancherCamera();
        }
    }

    private void debrancherCamera() {
        if (!surveillance && cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (surveillance) arreter(); // la caméra se ferme en arrière-plan
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        ioExecutor.shutdown();
    }

    // =====================================================
    //  DÉTECTION DE MOUVEMENT (traitement d'image)
    // =====================================================
    private void analyserImage(@NonNull ImageProxy image) {
        try {
            ImageProxy.PlaneProxy plan = image.getPlanes()[0]; // plan Y = luminosité
            ByteBuffer buf = plan.getBuffer();
            int w = image.getWidth();
            int h = image.getHeight();
            int rs = plan.getRowStride();

            // On échantillonne une grille de 32 x 24 points
            int cols = 32, rows = 24;
            byte[] actuel = new byte[cols * rows];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int x = (c * w) / cols + w / (cols * 2);
                    int y = (r * h) / rows + h / (rows * 2);
                    actuel[r * cols + c] = buf.get(y * rs + x);
                }
            }

            byte[] avant = previous;
            previous = actuel;

            long now = SystemClock.elapsedRealtime();
            // On ignore les 2,5 premières secondes (la caméra règle sa luminosité)
            if (avant != null && surveillance && now - startTime > 2500) {
                int changes = 0;
                for (int i = 0; i < actuel.length; i++) {
                    int d = Math.abs((actuel[i] & 0xFF) - (avant[i] & 0xFF));
                    if (d > SEUIL_PIXEL) changes++;
                }
                if (changes > actuel.length * SEUIL_ZONE
                        && now - lastDetection > PAUSE_ENTRE_ALERTES) {
                    lastDetection = now;
                    main.post(this::presenceDetectee);
                }
            }
        } finally {
            image.close();
        }
    }

    // =====================================================
    //  QUAND UNE PRÉSENCE EST DÉTECTÉE
    // =====================================================
    private void presenceDetectee() {
        if (!surveillance) return;
        detections++;
        String heure = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        textCount.setText(String.valueOf(detections));
        textLast.setText(heure);

        prendrePhoto();                 // 4. capture automatique
        demarrerVideo(DUREE_VIDEO);     // 5. vidéo automatique
        declencherAlerte();             // 6. sonnerie + vibration + lumière rouge
        notifier(heure);                // 7. notification
    }

    // =====================================================
    //  PHOTO
    // =====================================================
    private void prendrePhoto() {
        if (!surveillance) {
            Toast.makeText(this, "Appuie d'abord sur DÉMARRER", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bmp = previewView.getBitmap();
        if (bmp == null) return;
        final String dateTexte = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date());
        final File fichier = new File(dossier(), "photo_" + horodatage() + ".jpg");

        ioExecutor.execute(() -> {
            try {
                Bitmap copie = bmp.copy(Bitmap.Config.ARGB_8888, true);
                Canvas canvas = new Canvas(copie);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.WHITE);
                p.setTextSize(copie.getWidth() * 0.045f);
                p.setShadowLayer(6f, 0f, 0f, Color.BLACK);
                canvas.drawText(dateTexte, 20f, copie.getHeight() - 24f, p);
                try (FileOutputStream out = new FileOutputStream(fichier)) {
                    copie.compress(Bitmap.CompressFormat.JPEG, 90, out);
                }
            } catch (Exception ignored) {
            }
        });
        Toast.makeText(this, "📷 Photo enregistrée", Toast.LENGTH_SHORT).show();
    }

    // =====================================================
    //  VIDÉO
    // =====================================================
    @SuppressLint("MissingPermission")
    private void demarrerVideo(long dureeMs) {
        if (videoCapture == null || recording != null) return;
        File fichier = new File(dossier(), "video_" + horodatage() + ".mp4");
        FileOutputOptions options = new FileOutputOptions.Builder(fichier).build();
        PendingRecording pr = videoCapture.getOutput().prepareRecording(this, options);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            pr = pr.withAudioEnabled();
        }
        recording = pr.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Finalize) {
                recording = null;
                majBoutonVideo(false);
            }
        });
        majBoutonVideo(true);
        if (dureeMs > 0) {
            main.postDelayed(arreterVideoRunnable, dureeMs);
        }
    }

    private void arreterVideo() {
        if (recording != null) recording.stop();
    }

    private void majBoutonVideo(boolean enCours) {
        btnVideo.setText(enCours ? "⏹ ARRÊTER" : "🎥 VIDÉO");
        badgeRec.setVisibility(enCours ? View.VISIBLE : View.GONE);
    }

    // =====================================================
    //  ALERTE : sonnerie + vibration + lumière rouge
    // =====================================================
    private void declencherAlerte() {
        // Sonnerie
        try {
            if (ringtone == null) {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                ringtone = RingtoneManager.getRingtone(this, uri);
                ringtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                if (Build.VERSION.SDK_INT >= 28) ringtone.setLooping(true);
            }
            ringtone.play();
        } catch (Exception ignored) {
        }

        // Vibration
        long[] rythme = {0, 400, 200, 400, 200, 400};
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(rythme, -1));
        } else {
            vibrator.vibrate(rythme, -1);
        }

        // Lumière rouge clignotante
        alertOverlay.setVisibility(View.VISIBLE);
        if (alertAnim != null) alertAnim.cancel();
        alertAnim = ValueAnimator.ofFloat(0.2f, 0.75f);
        alertAnim.setDuration(300);
        alertAnim.setRepeatMode(ValueAnimator.REVERSE);
        alertAnim.setRepeatCount(ValueAnimator.INFINITE);
        alertAnim.addUpdateListener(a -> alertOverlay.setAlpha((float) a.getAnimatedValue()));
        alertAnim.start();

        textEtat.setText("⚠ PRÉSENCE DÉTECTÉE");
        textEtat.setTextColor(Color.parseColor("#FCA5A5"));
        textEtat.setBackground(verre(30, "#33EF4444", "#99EF4444"));

        main.removeCallbacks(arreterAlerteRunnable);
        main.postDelayed(arreterAlerteRunnable, DUREE_ALERTE);
    }

    private void arreterAlerte() {
        if (ringtone != null && ringtone.isPlaying()) ringtone.stop();
        vibrator.cancel();
        if (alertAnim != null) alertAnim.cancel();
        alertOverlay.setVisibility(View.GONE);
        majEtat(surveillance);
    }

    // =====================================================
    //  NOTIFICATION
    // =====================================================
    private void creerCanalNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel canal = new NotificationChannel(CANAL, "Alertes de présence",
                    NotificationManager.IMPORTANCE_HIGH);
            getSystemService(NotificationManager.class).createNotificationChannel(canal);
        }
    }

    @SuppressLint("MissingPermission")
    private void notifier(String heure) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠ Présence détectée")
                .setContentText("Mouvement détecté à " + heure)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        NotificationManagerCompat.from(this).notify((int) (System.currentTimeMillis() % 100000), n);
    }

    // =====================================================
    //  HISTORIQUE (galerie photos + vidéos)
    // =====================================================
    private void ouvrirHistorique() {
        dashboard.setVisibility(View.GONE);
        historique.setVisibility(View.VISIBLE);
        historique.setAlpha(0f);
        historique.animate().alpha(1f).setDuration(300).start();
        listeHistorique.removeAllViews();

        File[] fichiers = dossier().listFiles((d, nom) -> nom.endsWith(".jpg") || nom.endsWith(".mp4"));
        if (fichiers == null || fichiers.length == 0) {
            TextView vide = new TextView(this);
            vide.setText("Aucune photo ni vidéo pour le moment.");
            vide.setTextColor(Color.parseColor("#93A3D9"));
            vide.setGravity(Gravity.CENTER);
            vide.setPadding(0, dp(60), 0, 0);
            listeHistorique.addView(vide);
            return;
        }
        Arrays.sort(fichiers, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : fichiers) ajouterLigne(f);
    }

    private void fermerHistorique() {
        historique.setVisibility(View.GONE);
        dashboard.setVisibility(View.VISIBLE);
    }

    private void ajouterLigne(File f) {
        final boolean video = f.getName().endsWith(".mp4");

        LinearLayout ligne = new LinearLayout(this);
        ligne.setOrientation(LinearLayout.HORIZONTAL);
        ligne.setGravity(Gravity.CENTER_VERTICAL);
        ligne.setPadding(dp(12), dp(12), dp(12), dp(12));
        ligne.setBackground(verre(20, "#14FFFFFF", "#26FFFFFF"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        ligne.setLayoutParams(lp);

        // Miniature
        ImageView mini = new ImageView(this);
        mini.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(72)));
        mini.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mini.setBackground(verre(14, "#33000000", "#00000000"));
        mini.setClipToOutline(true);
        ligne.addView(mini);

        ioExecutor.execute(() -> {
            Bitmap b = null;
            try {
                if (video) {
                    MediaMetadataRetriever m = new MediaMetadataRetriever();
                    try {
                        m.setDataSource(f.getAbsolutePath());
                        b = m.getFrameAtTime(0);
                    } finally {
                        m.release();
                    }
                } else {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 4;
                    b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                }
            } catch (Exception ignored) {
            }
            final Bitmap fin = b;
            if (fin != null) runOnUiThread(() -> mini.setImageBitmap(fin));
        });

        // Textes
        LinearLayout textes = new LinearLayout(this);
        textes.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dp(14));
        textes.setLayoutParams(tp);

        TextView t1 = new TextView(this);
        t1.setText(video ? "🎥 Vidéo" : "📷 Photo");
        t1.setTextColor(Color.WHITE);
        t1.setTextSize(16);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView t2 = new TextView(this);
        t2.setText(new SimpleDateFormat("dd/MM/yyyy  •  HH:mm:ss", Locale.getDefault())
                .format(new Date(f.lastModified())));
        t2.setTextColor(Color.parseColor("#93A3D9"));
        t2.setTextSize(13);

        TextView t3 = new TextView(this);
        t3.setText((f.length() / 1024) + " Ko");
        t3.setTextColor(Color.parseColor("#64748B"));
        t3.setTextSize(12);

        textes.addView(t1);
        textes.addView(t2);
        textes.addView(t3);
        ligne.addView(textes);

        TextView fleche = new TextView(this);
        fleche.setText("›");
        fleche.setTextColor(Color.parseColor("#93A3D9"));
        fleche.setTextSize(28);
        ligne.addView(fleche);

        ligne.setOnClickListener(v -> ouvrirMedia(f, video));
        listeHistorique.addView(ligne);
    }

    private void ouvrirMedia(File f, boolean video) {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(Color.BLACK);

        final VideoView vv;
        if (video) {
            vv = new VideoView(this);
            vv.setVideoPath(f.getAbsolutePath());
            MediaController mc = new MediaController(this);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            vv.setOnPreparedListener(mp -> vv.start());
            fl.addView(vv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        } else {
            vv = null;
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            iv.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath(), o));
            fl.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        TextView fermer = new TextView(this);
        fermer.setText("✕");
        fermer.setTextColor(Color.WHITE);
        fermer.setTextSize(22);
        fermer.setGravity(Gravity.CENTER);
        fermer.setBackground(verre(24, "#99000000", "#55FFFFFF"));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(48), dp(48),
                Gravity.TOP | Gravity.END);
        fp.setMargins(0, dp(40), dp(16), 0);
        fl.addView(fermer, fp);
        fermer.setOnClickListener(v -> d.dismiss());

        d.setOnDismissListener(di -> {
            if (vv != null) vv.stopPlayback();
        });
        d.setContentView(fl);
        d.show();
    }

    // =====================================================
    //  OUTILS
    // =====================================================
    private File dossier() {
        File d = getExternalFilesDir("Surveillance");
        if (d == null) d = new File(getFilesDir(), "Surveillance");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private String horodatage() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable verre(int rayonDp, String fond, String contour) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(rayonDp));
        g.setColor(Color.parseColor(fond));
        g.setStroke(dp(1), Color.parseColor(contour));
        return g;
    }

    private GradientDrawable degrade(int rayonDp, String c1, String c2) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor(c1), Color.parseColor(c2)});
        g.setCornerRadius(dp(rayonDp));
        return g;
    }

    private void effetAppui(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });
    }

    private void majEtat(boolean actif) {
        if (actif) {
            textEtat.setText("● SURVEILLANCE ACTIVE");
            textEtat.setTextColor(Color.parseColor("#4ADE80"));
            textEtat.setBackground(verre(30, "#334ADE80", "#994ADE80"));
            placeholder.setVisibility(View.GONE);
            btnDemarrer.setAlpha(0.5f);
            btnStop.setAlpha(1f);
        } else {
            textEtat.setText("● EN VEILLE");
            textEtat.setTextColor(Color.parseColor("#94A3B8"));
            textEtat.setBackground(verre(30, "#14FFFFFF", "#26FFFFFF"));
            placeholder.setVisibility(View.VISIBLE);
            badgeRec.setVisibility(View.GONE);
            btnDemarrer.setAlpha(1f);
            btnStop.setAlpha(0.5f);
        }
    }
}