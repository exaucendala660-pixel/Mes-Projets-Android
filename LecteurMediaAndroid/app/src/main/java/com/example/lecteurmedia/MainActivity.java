package com.example.lecteurmedia;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.media.AudioAttributes;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Xml;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends AppCompatActivity {

    private static final String OR1 = "#F59E0B";
    private static final String OR2 = "#EA580C";
    private static final String GRIS = "#7FB2CC";

    // ====== Données ======
    private final List<Media> musiques = new ArrayList<>();
    private final List<Media> videos = new ArrayList<>();
    private final List<Media> photos = new ArrayList<>(); // NOUVEAU
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random hasard = new Random();

    // ====== Lecteur audio ======
    private MediaPlayer lecteur;
    private int indexCourant = -1;
    private boolean preparation = false;
    private boolean glisse = false;
    private boolean shuffle = false;
    private int repeat = 0; // 0 = non, 1 = tout, 2 = une seule

    // ====== Écran ======
    private DisqueView disque;
    private ObjectAnimator rotation;
    private TextView tabMusique, tabVideo, textTitre, textArtiste, textTemps, textDuree;
    private TextView btnShuffle, btnRepeat, videMusique, videVideo;
    private ImageView btnPlay, btnPrev, btnNext;
    private SeekBar seekBar;
    private View panelMusique, panelVideo;
    private ListView listeMusique, listeVideo;
    private ListeAdapter adapterMusique, adapterVideo;

    // ====== NOUVEAU : Photo / PDF / Office ======
    private TextView tabPhoto, tabPdf, tabOffice, videPhoto, btnChoisirPdf, btnChoisirOffice;
    private View panelPhoto, panelPdf, panelOffice;
    private GridView gridPhoto;
    private PhotoAdapter adapterPhoto;
    private int tailleVignette;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final LruCache<Long, Bitmap> cacheVignettes = new LruCache<>(120);

    // ====== Autorisations ======
    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (permissionOk()) {
                            chargerMedias();
                        } else {
                            String msg = "Autorisation refusée.\nVa dans Paramètres → Applications → "
                                    + "LecteurMedia → Autorisations, puis autorise la musique, la vidéo et les photos.";
                            videMusique.setText(msg);
                            videVideo.setText(msg);
                            videPhoto.setText(msg);
                        }
                    });

    // ====== NOUVEAU : sélecteur de fichiers (PDF et Office) ======
    // Le sélecteur du système ne demande aucune autorisation spéciale.
    private final ActivityResultLauncher<String[]> pdfLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) ouvrirPdf(uri);
            });

    private final ActivityResultLauncher<String[]> officeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) ouvrirOffice(uri);
            });

    // ====== Une chanson ou une vidéo ======
    static class Media {
        long id;
        String titre;
        String artiste;
        long duree;
        Uri uri;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);

        // Fond dégradé bleu pétrole
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0B5373"), Color.parseColor("#052B42"),
                        Color.parseColor("#020F1A")}));

        // Récupération des vues
        tabMusique = findViewById(R.id.tabMusique);
        tabVideo = findViewById(R.id.tabVideo);
        textTitre = findViewById(R.id.textTitre);
        textArtiste = findViewById(R.id.textArtiste);
        textTemps = findViewById(R.id.textTemps);
        textDuree = findViewById(R.id.textDuree);
        btnShuffle = findViewById(R.id.btnShuffle);
        btnRepeat = findViewById(R.id.btnRepeat);
        btnPlay = findViewById(R.id.btnPlay);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        seekBar = findViewById(R.id.seekBar);
        panelMusique = findViewById(R.id.panelMusique);
        panelVideo = findViewById(R.id.panelVideo);
        listeMusique = findViewById(R.id.listeMusique);
        listeVideo = findViewById(R.id.listeVideo);
        videMusique = findViewById(R.id.videMusique);
        videVideo = findViewById(R.id.videVideo);

        // NOUVEAU : vues Photo / PDF / Office
        tabPhoto = findViewById(R.id.tabPhoto);
        tabPdf = findViewById(R.id.tabPdf);
        tabOffice = findViewById(R.id.tabOffice);
        panelPhoto = findViewById(R.id.panelPhoto);
        panelPdf = findViewById(R.id.panelPdf);
        panelOffice = findViewById(R.id.panelOffice);
        gridPhoto = findViewById(R.id.gridPhoto);
        videPhoto = findViewById(R.id.videPhoto);
        btnChoisirPdf = findViewById(R.id.btnChoisirPdf);
        btnChoisirOffice = findViewById(R.id.btnChoisirOffice);

        // Disque qui tourne
        FrameLayout disqueContainer = findViewById(R.id.disqueContainer);
        disque = new DisqueView(this);
        disqueContainer.addView(disque, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rotation = ObjectAnimator.ofFloat(disque, "rotation", 0f, 360f);
        rotation.setDuration(12000);
        rotation.setInterpolator(new LinearInterpolator());
        rotation.setRepeatCount(ValueAnimator.INFINITE);

        // Design
        findViewById(R.id.playerCard).setBackground(verre(28, "#33021522", "#2200E5FF"));
        btnPlay.setBackground(ovaleOrange());
        btnPlay.setElevation(dp(10));
        btnPlay.setColorFilter(Color.WHITE);
        btnPrev.setBackground(rond("#26FFFFFF"));
        btnPrev.setColorFilter(Color.WHITE);
        btnNext.setBackground(rond("#26FFFFFF"));
        btnNext.setColorFilter(Color.WHITE);
        int orange = Color.parseColor(OR1);
        seekBar.setProgressTintList(ColorStateList.valueOf(orange));
        seekBar.setThumbTintList(ColorStateList.valueOf(orange));
        seekBar.setProgressBackgroundTintList(
                ColorStateList.valueOf(Color.parseColor("#33FFFFFF")));
        for (View b : new View[]{btnPlay, btnPrev, btnNext, btnShuffle, btnRepeat,
                tabMusique, tabVideo}) {
            effetAppui(b);
        }
        // NOUVEAU : design des nouveaux boutons
        for (View b : new View[]{tabPhoto, tabPdf, tabOffice, btnChoisirPdf, btnChoisirOffice}) {
            effetAppui(b);
        }
        btnChoisirPdf.setBackground(degrade(26, OR1, OR2));
        btnChoisirOffice.setBackground(degrade(26, OR1, OR2));
        majModes();
        choisirOnglet(false);

        // Listes
        adapterMusique = new ListeAdapter(musiques, false);
        adapterVideo = new ListeAdapter(videos, true);
        for (ListView lv : new ListView[]{listeMusique, listeVideo}) {
            lv.setDivider(new ColorDrawable(Color.TRANSPARENT));
            lv.setDividerHeight(dp(8));
            lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
        listeMusique.setAdapter(adapterMusique);
        listeVideo.setAdapter(adapterVideo);
        listeMusique.setEmptyView(videMusique);
        listeVideo.setEmptyView(videVideo);
        listeMusique.setOnItemClickListener((p, v, pos, id) -> jouer(pos));
        listeVideo.setOnItemClickListener((p, v, pos, id) -> ouvrirVideo(videos.get(pos)));

        // NOUVEAU : grille de photos
        int ecran = getResources().getDisplayMetrics().widthPixels;
        tailleVignette = (ecran - dp(36) - dp(12)) / 3 - dp(2);
        adapterPhoto = new PhotoAdapter();
        gridPhoto.setAdapter(adapterPhoto);
        gridPhoto.setEmptyView(videPhoto);
        gridPhoto.setOnItemClickListener((p, v, pos, id) -> ouvrirPhoto(pos));

        // Boutons
        tabMusique.setOnClickListener(v -> choisirOnglet(false));
        tabVideo.setOnClickListener(v -> choisirOnglet(true));
        // NOUVEAU
        tabPhoto.setOnClickListener(v -> choisirOnglet(2));
        tabPdf.setOnClickListener(v -> choisirOnglet(3));
        tabOffice.setOnClickListener(v -> choisirOnglet(4));
        // "*/*" = on voit tous les fichiers (ex. ceux reçus par WhatsApp)
        btnChoisirPdf.setOnClickListener(v -> pdfLauncher.launch(new String[]{"*/*"}));
        btnChoisirOffice.setOnClickListener(v -> officeLauncher.launch(new String[]{"*/*"}));

        btnPlay.setOnClickListener(v -> basculer());
        btnNext.setOnClickListener(v -> suivant());
        btnPrev.setOnClickListener(v -> precedent());
        btnShuffle.setOnClickListener(v -> {
            shuffle = !shuffle;
            majModes();
        });
        btnRepeat.setOnClickListener(v -> {
            repeat = (repeat + 1) % 3;
            majModes();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) textTemps.setText(format(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                glisse = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                glisse = false;
                try {
                    if (lecteur != null && indexCourant >= 0 && !preparation) {
                        lecteur.seekTo(s.getProgress());
                    }
                } catch (Exception ignored) {
                }
            }
        });

        // Animation d'entrée
        View contenu = ((ViewGroup) root).getChildAt(0);
        contenu.setAlpha(0f);
        contenu.animate().alpha(1f).setDuration(900).start();

        // Autorisations puis chargement des fichiers
        if (permissionOk()) {
            chargerMedias();
        } else {
            permLauncher.launch(permissions());
        }

        // NOUVEAU : si un fichier a été envoyé à l'application (ex. depuis WhatsApp)
        root.post(() -> traiterIntent(getIntent()));
    }

    // =====================================================
    //  AUTORISATIONS
    // =====================================================
    private String[] permissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES}; // NOUVEAU : photos
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean permissionOk() {
        for (String p : permissions()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // =====================================================
    //  LECTURE DES FICHIERS DU TÉLÉPHONE
    // =====================================================
    private void chargerMedias() {
        new Thread(() -> {
            List<Media> a = lireListeAudio();
            List<Media> v = lireListeVideos();
            List<Media> ph = lireListePhotos(); // NOUVEAU
            runOnUiThread(() -> {
                musiques.clear();
                musiques.addAll(a);
                videos.clear();
                videos.addAll(v);
                photos.clear(); // NOUVEAU
                photos.addAll(ph); // NOUVEAU
                adapterMusique.notifyDataSetChanged();
                adapterVideo.notifyDataSetChanged();
                adapterPhoto.notifyDataSetChanged(); // NOUVEAU
                videMusique.setText("Aucune musique trouvée sur ce téléphone");
                videVideo.setText("Aucune vidéo trouvée sur ce téléphone");
                videPhoto.setText("Aucune photo trouvée sur ce téléphone"); // NOUVEAU
            });
        }).start();
    }

    private List<Media> lireListeAudio() {
        List<Media> liste = new ArrayList<>();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] colonnes = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION};
        String filtre = MediaStore.Audio.Media.IS_RINGTONE + "=0 AND "
                + MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND "
                + MediaStore.Audio.Media.IS_ALARM + "=0";
        try (Cursor c = getContentResolver().query(collection, colonnes, filtre, null,
                MediaStore.Audio.Media.TITLE + " ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    Media m = new Media();
                    m.id = c.getLong(0);
                    m.titre = c.getString(1);
                    m.artiste = c.getString(2);
                    m.duree = c.getLong(3);
                    if (m.artiste == null || m.artiste.equals("<unknown>")) {
                        m.artiste = "Artiste inconnu";
                    }
                    m.uri = ContentUris.withAppendedId(collection, m.id);
                    liste.add(m);
                }
            }
        } catch (Exception ignored) {
        }
        return liste;
    }

    private List<Media> lireListeVideos() {
        List<Media> liste = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] colonnes = {MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION};
        try (Cursor c = getContentResolver().query(collection, colonnes, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    Media m = new Media();
                    m.id = c.getLong(0);
                    m.titre = c.getString(1);
                    m.duree = c.getLong(2);
                    m.artiste = "Vidéo";
                    m.uri = ContentUris.withAppendedId(collection, m.id);
                    liste.add(m);
                }
            }
        } catch (Exception ignored) {
        }
        return liste;
    }

    // NOUVEAU : liste des photos
    private List<Media> lireListePhotos() {
        List<Media> liste = new ArrayList<>();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] colonnes = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME};
        try (Cursor c = getContentResolver().query(collection, colonnes, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    Media m = new Media();
                    m.id = c.getLong(0);
                    m.titre = c.getString(1);
                    m.artiste = "Photo";
                    m.uri = ContentUris.withAppendedId(collection, m.id);
                    liste.add(m);
                }
            }
        } catch (Exception ignored) {
        }
        return liste;
    }

    // =====================================================
    //  LECTEUR AUDIO
    // =====================================================
    private void jouer(int index) {
        if (index < 0 || index >= musiques.size()) return;
        indexCourant = index;
        Media m = musiques.get(index);

        textTitre.setText(m.titre);
        textArtiste.setText(m.artiste);
        textDuree.setText(format(m.duree));
        textTemps.setText("0:00");
        seekBar.setMax((int) m.duree);
        seekBar.setProgress(0);
        chargerPochette(m);

        try {
            if (lecteur == null) {
                lecteur = new MediaPlayer();
                lecteur.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                lecteur.setOnCompletionListener(mp -> finDeChanson());
                lecteur.setOnErrorListener((mp, what, extra) -> {
                    preparation = false;
                    Toast.makeText(this, "Impossible de lire ce fichier", Toast.LENGTH_SHORT).show();
                    majBoutonPlay();
                    return true;
                });
            }
            preparation = true;
            lecteur.reset();
            lecteur.setDataSource(this, m.uri);
            lecteur.setOnPreparedListener(mp -> {
                preparation = false;
                seekBar.setMax(mp.getDuration());
                textDuree.setText(format(mp.getDuration()));
                mp.start();
                majBoutonPlay();
                handler.removeCallbacks(progression);
                handler.post(progression);
            });
            lecteur.prepareAsync();
        } catch (Exception e) {
            preparation = false;
            Toast.makeText(this, "Impossible de lire ce fichier", Toast.LENGTH_SHORT).show();
        }
        majBoutonPlay();
        adapterMusique.notifyDataSetChanged();
    }

    private void basculer() {
        if (lecteur == null || indexCourant < 0) {
            if (!musiques.isEmpty()) jouer(0);
            return;
        }
        if (preparation) return;
        try {
            if (lecteur.isPlaying()) {
                lecteur.pause();
            } else {
                lecteur.start();
                handler.removeCallbacks(progression);
                handler.post(progression);
            }
        } catch (Exception ignored) {
        }
        majBoutonPlay();
    }

    private void suivant() {
        if (musiques.isEmpty()) return;
        int i = shuffle ? hasard.nextInt(musiques.size()) : (indexCourant + 1) % musiques.size();
        jouer(i);
    }

    private void precedent() {
        if (musiques.isEmpty()) return;
        try {
            if (lecteur != null && indexCourant >= 0 && !preparation
                    && lecteur.getCurrentPosition() > 3000) {
                lecteur.seekTo(0);
                return;
            }
        } catch (Exception ignored) {
        }
        int i = indexCourant - 1;
        if (i < 0) i = musiques.size() - 1;
        jouer(i);
    }

    private void finDeChanson() {
        if (repeat == 2) {
            jouer(indexCourant);
        } else if (shuffle) {
            suivant();
        } else if (indexCourant + 1 >= musiques.size()) {
            if (repeat == 1) {
                jouer(0);
            } else {
                try {
                    lecteur.seekTo(0);
                } catch (Exception ignored) {
                }
                seekBar.setProgress(0);
                textTemps.setText("0:00");
                majBoutonPlay();
            }
        } else {
            jouer(indexCourant + 1);
        }
    }

    private boolean enLecture() {
        try {
            return lecteur != null && !preparation && lecteur.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    // Met à jour la barre de progression toutes les 0,4 seconde
    private final Runnable progression = new Runnable() {
        @Override
        public void run() {
            if (enLecture()) {
                try {
                    int pos = lecteur.getCurrentPosition();
                    if (!glisse) {
                        seekBar.setProgress(pos);
                        textTemps.setText(format(pos));
                    }
                } catch (Exception ignored) {
                }
                handler.postDelayed(this, 400);
            }
        }
    };

    private void majBoutonPlay() {
        boolean lit = enLecture();
        btnPlay.setImageResource(lit ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
        if (lit) {
            if (!rotation.isStarted()) rotation.start();
            else if (rotation.isPaused()) rotation.resume();
        } else if (rotation.isRunning()) {
            rotation.pause();
        }
    }

    private void majModes() {
        btnShuffle.setTextColor(Color.parseColor(shuffle ? OR1 : GRIS));
        btnRepeat.setTextColor(Color.parseColor(repeat != 0 ? OR1 : GRIS));
        btnRepeat.setText(repeat == 2 ? "↻¹" : "↻");
    }

    // Cherche l'image de la chanson (pochette) dans le fichier
    private void chargerPochette(Media m) {
        disque.setCover(null);
        final int idx = indexCourant;
        new Thread(() -> {
            Bitmap b = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(this, m.uri);
                byte[] art = r.getEmbeddedPicture();
                if (art != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 2;
                    b = BitmapFactory.decodeByteArray(art, 0, art.length, o);
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
            final Bitmap fin = b;
            runOnUiThread(() -> {
                if (idx == indexCourant) disque.setCover(fin);
            });
        }).start();
    }

    // =====================================================
    //  LECTEUR VIDÉO
    // =====================================================
    private void ouvrirVideo(Media m) {
        if (enLecture()) {
            try {
                lecteur.pause();
            } catch (Exception ignored) {
            }
            majBoutonPlay();
        }
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(Color.BLACK);

        final VideoView vv = new VideoView(this);
        vv.setVideoURI(m.uri);
        MediaController mc = new MediaController(this);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);
        vv.setOnPreparedListener(mp -> vv.start());
        vv.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Impossible de lire cette vidéo", Toast.LENGTH_SHORT).show();
            return true;
        });
        fl.addView(vv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

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

        d.setOnDismissListener(di -> vv.stopPlayback());
        d.setContentView(fl);
        d.show();
    }

    // =====================================================
    //  NOUVEAU : LECTEUR PHOTO
    // =====================================================
    private void ouvrirPhoto(int index) {
        if (index < 0 || index >= photos.size()) return;
        final int[] cur = {index};

        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(Color.BLACK);

        final ZoomImageView zv = new ZoomImageView(this);
        fl.addView(zv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView compteur = new TextView(this);
        compteur.setTextColor(Color.WHITE);
        compteur.setTextSize(14);
        compteur.setGravity(Gravity.CENTER);
        compteur.setBackground(verre(16, "#99000000", "#33FFFFFF"));
        compteur.setPadding(dp(14), dp(6), dp(14), dp(6));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cp.setMargins(0, 0, 0, dp(32));
        fl.addView(compteur, cp);

        final Runnable[] afficher = new Runnable[1];
        afficher[0] = () -> {
            final int k = cur[0];
            Media m = photos.get(k);
            compteur.setText((k + 1) + " / " + photos.size());
            pool.execute(() -> {
                Bitmap b = decoder(m.uri, 1800);
                runOnUiThread(() -> {
                    if (k == cur[0] && d.isShowing()) {
                        if (b != null) {
                            zv.setImageBitmap(b);
                        } else {
                            Toast.makeText(this, "Impossible d'afficher cette photo",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            });
        };
        zv.surGauche = () -> {
            if (cur[0] < photos.size() - 1) {
                cur[0]++;
                afficher[0].run();
            }
        };
        zv.surDroite = () -> {
            if (cur[0] > 0) {
                cur[0]--;
                afficher[0].run();
            }
        };

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

        d.setContentView(fl);
        d.show();
        afficher[0].run();
    }

    // Charge une image réduite pour ne pas saturer la mémoire (et la remet à l'endroit)
    private Bitmap decoder(Uri uri, int cible) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (o.outWidth / (s * 2) >= cible && o.outHeight / (s * 2) >= cible) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            Bitmap b;
            try (InputStream in2 = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in2, null, o);
            }
            if (b == null) return null;

            int deg = 0;
            try (InputStream in3 = getContentResolver().openInputStream(uri)) {
                ExifInterface exif = new ExifInterface(in3);
                int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            } catch (Exception ignored) {
            }
            if (deg != 0) {
                Matrix mx = new Matrix();
                mx.postRotate(deg);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
            }
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    // =====================================================
    //  NOUVEAU : LECTEUR PDF
    // =====================================================
    private void ouvrirPdf(Uri uri) {
        final ParcelFileDescriptor pfd;
        final PdfRenderer renderer;
        try {
            pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) throw new Exception("vide");
            renderer = new PdfRenderer(pfd);
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir ce PDF (protégé ou abîmé)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final int total = renderer.getPageCount();
        if (total == 0) {
            Toast.makeText(this, "Ce PDF est vide", Toast.LENGTH_SHORT).show();
            try {
                renderer.close();
                pfd.close();
            } catch (Exception ignored) {
            }
            return;
        }
        final int[] cur = {0};
        final Bitmap[] ancienne = new Bitmap[1];

        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout racine = new LinearLayout(this);
        racine.setOrientation(LinearLayout.VERTICAL);
        racine.setBackgroundColor(Color.parseColor("#1A1A1A"));

        // Barre du haut
        LinearLayout haut = new LinearLayout(this);
        haut.setOrientation(LinearLayout.HORIZONTAL);
        haut.setGravity(Gravity.CENTER_VERTICAL);
        haut.setPadding(dp(16), dp(36), dp(16), dp(8));
        TextView nom = new TextView(this);
        nom.setText(nomFichier(uri));
        nom.setTextColor(Color.WHITE);
        nom.setTextSize(15);
        nom.setTypeface(null, Typeface.BOLD);
        nom.setSingleLine(true);
        nom.setEllipsize(TextUtils.TruncateAt.END);
        haut.addView(nom, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView fermer = new TextView(this);
        fermer.setText("✕");
        fermer.setTextColor(Color.WHITE);
        fermer.setTextSize(22);
        fermer.setGravity(Gravity.CENTER);
        fermer.setBackground(verre(24, "#33FFFFFF", "#55FFFFFF"));
        haut.addView(fermer, new LinearLayout.LayoutParams(dp(48), dp(48)));
        racine.addView(haut);

        // Page
        final ZoomImageView zv = new ZoomImageView(this);
        racine.addView(zv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f));

        // Barre du bas
        LinearLayout bas = new LinearLayout(this);
        bas.setOrientation(LinearLayout.HORIZONTAL);
        bas.setGravity(Gravity.CENTER);
        bas.setPadding(dp(16), dp(10), dp(16), dp(24));
        TextView btnG = new TextView(this);
        btnG.setText("◀");
        TextView page = new TextView(this);
        TextView btnD = new TextView(this);
        btnD.setText("▶");
        for (TextView t : new TextView[]{btnG, btnD}) {
            t.setTextColor(Color.WHITE);
            t.setTextSize(20);
            t.setGravity(Gravity.CENTER);
            t.setBackground(degrade(22, OR1, OR2));
        }
        page.setTextColor(Color.WHITE);
        page.setTextSize(15);
        page.setGravity(Gravity.CENTER);
        bas.addView(btnG, new LinearLayout.LayoutParams(dp(56), dp(44)));
        bas.addView(page, new LinearLayout.LayoutParams(dp(120),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        bas.addView(btnD, new LinearLayout.LayoutParams(dp(56), dp(44)));
        racine.addView(bas);

        final Runnable[] afficher = new Runnable[1];
        afficher[0] = () -> {
            try {
                PdfRenderer.Page p = renderer.openPage(cur[0]);
                int w = getResources().getDisplayMetrics().widthPixels * 2;
                int h = (int) ((float) w * p.getHeight() / p.getWidth());
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                p.close();
                zv.setImageBitmap(b);
                if (ancienne[0] != null) ancienne[0].recycle();
                ancienne[0] = b;
                page.setText("Page " + (cur[0] + 1) + " / " + total);
            } catch (Throwable e) {
                Toast.makeText(this, "Impossible d'afficher cette page",
                        Toast.LENGTH_SHORT).show();
            }
        };
        Runnable suiv = () -> {
            if (cur[0] < total - 1) {
                cur[0]++;
                afficher[0].run();
            }
        };
        Runnable prec = () -> {
            if (cur[0] > 0) {
                cur[0]--;
                afficher[0].run();
            }
        };
        zv.surGauche = suiv;
        zv.surDroite = prec;
        btnD.setOnClickListener(v -> suiv.run());
        btnG.setOnClickListener(v -> prec.run());
        fermer.setOnClickListener(v -> d.dismiss());

        d.setOnDismissListener(di -> {
            try {
                renderer.close();
            } catch (Exception ignored) {
            }
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
        });
        d.setContentView(racine);
        d.show();
        afficher[0].run();
    }

    // =====================================================
    //  NOUVEAU : LECTEUR MICROSOFT OFFICE
    // =====================================================
    private void ouvrirOffice(Uri uri) {
        String nom = nomFichier(uri);
        String bas = nom.toLowerCase(Locale.ROOT);
        String mime = getContentResolver().getType(uri);
        if (mime == null) mime = "";
        final int type; // 1 = Word, 2 = Excel, 3 = PowerPoint
        if (bas.endsWith(".docx") || mime.contains("wordprocessingml")) type = 1;
        else if (bas.endsWith(".xlsx") || mime.contains("spreadsheetml")) type = 2;
        else if (bas.endsWith(".pptx") || mime.contains("presentationml")) type = 3;
        else {
            // Anciens formats (.doc, .xls, .ppt) ou autre fichier : on reste dans l'application
            Toast.makeText(this, "Format non pris en charge. Utilise un fichier "
                            + ".docx, .xlsx ou .pptx (enregistre-le sous ce format dans Office).",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout racine = new LinearLayout(this);
        racine.setOrientation(LinearLayout.VERTICAL);
        racine.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0B5373"), Color.parseColor("#020F1A")}));

        LinearLayout haut = new LinearLayout(this);
        haut.setOrientation(LinearLayout.HORIZONTAL);
        haut.setGravity(Gravity.CENTER_VERTICAL);
        haut.setPadding(dp(16), dp(36), dp(16), dp(8));
        TextView titre = new TextView(this);
        titre.setText((type == 1 ? "▤ " : type == 2 ? "▦ " : "▶ ") + nom);
        titre.setTextColor(Color.WHITE);
        titre.setTextSize(15);
        titre.setTypeface(null, Typeface.BOLD);
        titre.setSingleLine(true);
        titre.setEllipsize(TextUtils.TruncateAt.END);
        haut.addView(titre, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView fermer = new TextView(this);
        fermer.setText("✕");
        fermer.setTextColor(Color.WHITE);
        fermer.setTextSize(22);
        fermer.setGravity(Gravity.CENTER);
        fermer.setBackground(verre(24, "#33FFFFFF", "#55FFFFFF"));
        haut.addView(fermer, new LinearLayout.LayoutParams(dp(48), dp(48)));
        racine.addView(haut);

        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final TextView contenu = new TextView(this);
        contenu.setTextColor(Color.WHITE);
        contenu.setTextSize(14);
        contenu.setLineSpacing(0f, 1.2f);
        contenu.setTextIsSelectable(true);
        contenu.setPadding(dp(18), dp(10), dp(18), dp(10));
        contenu.setText("Chargement...");
        sv.addView(contenu, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        racine.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f));

        fermer.setOnClickListener(v -> d.dismiss());

        d.setContentView(racine);
        d.show();

        pool.execute(() -> {
            String texte;
            try {
                texte = lireOffice(uri, type);
                if (texte.trim().isEmpty()) texte = "(Aucun texte trouvé dans ce document)";
            } catch (Throwable e) {
                texte = "Impossible de lire ce document.\n"
                        + "Le fichier est peut-être abîmé ou protégé par un mot de passe.";
            }
            final String fin = texte;
            runOnUiThread(() -> contenu.setText(fin));
        });
    }

    // Un fichier .docx / .xlsx / .pptx est un ZIP qui contient des fichiers XML
    private String lireOffice(Uri uri, int type) throws Exception {
        Map<String, byte[]> fichiers = new HashMap<>();
        try (InputStream in = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName();
                boolean utile = n.equals("word/document.xml")
                        || n.equals("xl/sharedStrings.xml")
                        || (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml"))
                        || (n.startsWith("ppt/slides/slide") && n.endsWith(".xml"));
                if (utile) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = zip.read(buf)) > 0) out.write(buf, 0, r);
                    fichiers.put(n, out.toByteArray());
                }
            }
        }
        StringBuilder sb = new StringBuilder();

        if (type == 1) { // ---------- WORD ----------
            byte[] doc = fichiers.get("word/document.xml");
            if (doc != null) {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new ByteArrayInputStream(doc), "UTF-8");
                int ev = p.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        String t = p.getName();
                        if (t.equals("w:t")) sb.append(p.nextText());
                        else if (t.equals("w:tab")) sb.append('\t');
                        else if (t.equals("w:br")) sb.append('\n');
                    } else if (ev == XmlPullParser.END_TAG && p.getName().equals("w:p")) {
                        sb.append("\n\n");
                    }
                    ev = p.next();
                }
            }
        } else if (type == 3) { // ---------- POWERPOINT ----------
            TreeMap<Integer, byte[]> slides = new TreeMap<>();
            for (Map.Entry<String, byte[]> en : fichiers.entrySet()) {
                String n = en.getKey();
                if (n.startsWith("ppt/slides/slide")) {
                    String num = n.substring("ppt/slides/slide".length(), n.length() - 4);
                    try {
                        slides.put(Integer.parseInt(num), en.getValue());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            for (Map.Entry<Integer, byte[]> en : slides.entrySet()) {
                sb.append("━━━ Diapositive ").append(en.getKey()).append(" ━━━\n");
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new ByteArrayInputStream(en.getValue()), "UTF-8");
                int ev = p.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.getName().equals("a:t")) {
                        sb.append(p.nextText());
                    } else if (ev == XmlPullParser.END_TAG && p.getName().equals("a:p")) {
                        sb.append('\n');
                    }
                    ev = p.next();
                }
                sb.append('\n');
            }
        } else { // ---------- EXCEL ----------
            List<String> partages = new ArrayList<>();
            byte[] ss = fichiers.get("xl/sharedStrings.xml");
            if (ss != null) {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new ByteArrayInputStream(ss), "UTF-8");
                StringBuilder cur = null;
                int ev = p.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        if (p.getName().equals("si")) cur = new StringBuilder();
                        else if (p.getName().equals("t") && cur != null) cur.append(p.nextText());
                    } else if (ev == XmlPullParser.END_TAG && p.getName().equals("si")) {
                        partages.add(cur == null ? "" : cur.toString());
                        cur = null;
                    }
                    ev = p.next();
                }
            }
            TreeMap<Integer, byte[]> feuilles = new TreeMap<>();
            for (Map.Entry<String, byte[]> en : fichiers.entrySet()) {
                String n = en.getKey();
                if (n.startsWith("xl/worksheets/sheet")) {
                    String num = n.substring("xl/worksheets/sheet".length(), n.length() - 4);
                    try {
                        feuilles.put(Integer.parseInt(num), en.getValue());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            for (Map.Entry<Integer, byte[]> en : feuilles.entrySet()) {
                sb.append("━━━ Feuille ").append(en.getKey()).append(" ━━━\n");
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new ByteArrayInputStream(en.getValue()), "UTF-8");
                String typeCellule = "";
                boolean debutLigne = true;
                int ev = p.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        String t = p.getName();
                        if (t.equals("row")) {
                            debutLigne = true;
                        } else if (t.equals("c")) {
                            String a = p.getAttributeValue(null, "t");
                            typeCellule = a == null ? "" : a;
                        } else if (t.equals("v") || (t.equals("t") && typeCellule.equals("inlineStr"))) {
                            String val = p.nextText();
                            if (t.equals("v") && typeCellule.equals("s")) {
                                try {
                                    val = partages.get(Integer.parseInt(val.trim()));
                                } catch (Exception ignored) {
                                }
                            }
                            if (!debutLigne) sb.append('\t');
                            sb.append(val);
                            debutLigne = false;
                        }
                    } else if (ev == XmlPullParser.END_TAG && p.getName().equals("row")) {
                        sb.append('\n');
                    }
                    ev = p.next();
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // NOUVEAU : ouverture d'un fichier reçu depuis une autre application (WhatsApp, etc.)
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        traiterIntent(intent);
    }

    private void traiterIntent(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return;
        Uri uri = i.getData();
        String type = i.getType() != null ? i.getType() : getContentResolver().getType(uri);
        String nom = nomFichier(uri).toLowerCase(Locale.ROOT);
        if (nom.endsWith(".pdf") || "application/pdf".equals(type)) {
            choisirOnglet(3);
            ouvrirPdf(uri);
        } else {
            choisirOnglet(4);
            ouvrirOffice(uri);
        }
        setIntent(new Intent());
    }

    private void ouvrirAvecAutreApp(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, getContentResolver().getType(uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Ouvrir avec"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Aucune application ne peut ouvrir ce fichier",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir ce fichier", Toast.LENGTH_SHORT).show();
        }
    }

    private String nomFichier(Uri uri) {
        String nom = "document";
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) nom = c.getString(0);
        } catch (Exception ignored) {
        }
        return nom;
    }

    // =====================================================
    //  ONGLETS
    // =====================================================
    // (ancienne version gardée : false = musique, true = vidéo)
    private void choisirOnglet(boolean video) {
        choisirOnglet(video ? 1 : 0);
    }

    // NOUVEAU : 0 = musique, 1 = vidéo, 2 = photo, 3 = pdf, 4 = office
    private void choisirOnglet(int n) {
        panelMusique.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        panelVideo.setVisibility(n == 1 ? View.VISIBLE : View.GONE);
        panelPhoto.setVisibility(n == 2 ? View.VISIBLE : View.GONE);
        panelPdf.setVisibility(n == 3 ? View.VISIBLE : View.GONE);
        panelOffice.setVisibility(n == 4 ? View.VISIBLE : View.GONE);
        styleOnglet(tabMusique, n == 0);
        styleOnglet(tabVideo, n == 1);
        styleOnglet(tabPhoto, n == 2);
        styleOnglet(tabPdf, n == 3);
        styleOnglet(tabOffice, n == 4);
    }

    private void styleOnglet(TextView t, boolean actif) {
        if (actif) {
            t.setBackground(degrade(21, OR1, OR2));
            t.setTextColor(Color.WHITE);
        } else {
            t.setBackground(verre(21, "#1AFFFFFF", "#33FFFFFF"));
            t.setTextColor(Color.parseColor(GRIS));
        }
    }

    // =====================================================
    //  LISTE (musiques et vidéos)
    // =====================================================
    private class ListeAdapter extends BaseAdapter {
        private final List<Media> data;
        private final boolean video;

        ListeAdapter(List<Media> d, boolean v) {
            data = d;
            video = v;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int i) {
            return data.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout ligne;
            if (convertView == null) {
                ligne = new LinearLayout(MainActivity.this);
                ligne.setOrientation(LinearLayout.HORIZONTAL);
                ligne.setGravity(Gravity.CENTER_VERTICAL);
                ligne.setPadding(dp(10), dp(10), dp(14), dp(10));

                TextView icone = new TextView(MainActivity.this);
                icone.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
                icone.setGravity(Gravity.CENTER);
                icone.setTextSize(18);

                LinearLayout textes = new LinearLayout(MainActivity.this);
                textes.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tp.setMargins(dp(12), 0, dp(8), 0);
                textes.setLayoutParams(tp);

                TextView t1 = new TextView(MainActivity.this);
                t1.setTextSize(15);
                t1.setTypeface(null, Typeface.BOLD);
                t1.setSingleLine(true);
                t1.setEllipsize(TextUtils.TruncateAt.END);

                TextView t2 = new TextView(MainActivity.this);
                t2.setTextSize(12);
                t2.setTextColor(Color.parseColor(GRIS));
                t2.setSingleLine(true);
                t2.setEllipsize(TextUtils.TruncateAt.END);

                textes.addView(t1);
                textes.addView(t2);

                TextView duree = new TextView(MainActivity.this);
                duree.setTextSize(12);
                duree.setTextColor(Color.parseColor(GRIS));

                ligne.addView(icone);
                ligne.addView(textes);
                ligne.addView(duree);
                ligne.setTag(new TextView[]{icone, t1, t2, duree});
            } else {
                ligne = (LinearLayout) convertView;
            }

            TextView[] v = (TextView[]) ligne.getTag();
            Media m = data.get(position);
            boolean actif = !video && position == indexCourant;

            v[0].setText(video ? "▶" : (actif ? "♫" : "♪"));
            v[0].setTextColor(Color.WHITE);
            v[0].setBackground(actif ? degrade(14, OR1, OR2) : verre(14, "#26FFFFFF", "#00000000"));
            v[1].setText(m.titre);
            v[1].setTextColor(Color.parseColor(actif ? "#FBBF24" : "#FFFFFF"));
            v[2].setText(m.artiste);
            v[3].setText(format(m.duree));
            ligne.setBackground(verre(18, actif ? "#26F59E0B" : "#14FFFFFF",
                    actif ? "#66F59E0B" : "#1AFFFFFF"));
            return ligne;
        }
    }

    // =====================================================
    //  NOUVEAU : GRILLE DES PHOTOS
    // =====================================================
    private class PhotoAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return photos.size();
        }

        @Override
        public Object getItem(int i) {
            return photos.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView iv;
            if (convertView == null) {
                iv = new ImageView(MainActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(verre(10, "#14FFFFFF", "#1AFFFFFF"));
            } else {
                iv = (ImageView) convertView;
            }
            iv.setLayoutParams(new AbsListView.LayoutParams(tailleVignette, tailleVignette));

            final Media m = photos.get(position);
            iv.setTag(m.id);
            Bitmap c = cacheVignettes.get(m.id);
            if (c != null) {
                iv.setImageBitmap(c);
            } else {
                iv.setImageDrawable(null);
                final ImageView cible = iv;
                pool.execute(() -> {
                    Bitmap b = decoder(m.uri, 240);
                    if (b != null) {
                        cacheVignettes.put(m.id, b);
                        runOnUiThread(() -> {
                            Object tag = cible.getTag();
                            if (tag instanceof Long && ((Long) tag) == m.id) {
                                cible.setImageBitmap(b);
                            }
                        });
                    }
                });
            }
            return iv;
        }
    }

    // =====================================================
    //  OUTILS
    // =====================================================
    private String format(long ms) {
        long s = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
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

    private GradientDrawable ovaleOrange() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor(OR1), Color.parseColor(OR2)});
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    private GradientDrawable rond(String couleur) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.parseColor(couleur));
        return g;
    }

    private void effetAppui(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        rotation.cancel();
        pool.shutdownNow(); // NOUVEAU
        if (lecteur != null) {
            lecteur.release();
            lecteur = null;
        }
    }

    // =====================================================
    //  LE DISQUE (dessiné par le code)
    // =====================================================
    static class DisqueView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap cover;

        DisqueView(Context c) {
            super(c);
        }

        void setCover(Bitmap b) {
            cover = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy);

            // Rayons de couleur
            int[] couleurs = {0xFF00E5FF, 0xFF0B3C5D, 0xFF7C4DFF, 0xFF0B3C5D,
                    0xFFFF9800, 0xFF0B3C5D, 0xFF00E5FF};
            p.setStyle(Paint.Style.FILL);
            p.setShader(new SweepGradient(cx, cy, couleurs, null));
            canvas.drawCircle(cx, cy, r, p);
            p.setShader(null);

            // Anneau sombre
            p.setColor(0xFF03111C);
            canvas.drawCircle(cx, cy, r * 0.62f, p);

            // Centre : pochette ou note de musique
            float rc = r * 0.58f;
            if (cover != null) {
                Path path = new Path();
                path.addCircle(cx, cy, rc, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(path);
                canvas.drawBitmap(cover, null, new RectF(cx - rc, cy - rc, cx + rc, cy + rc), p);
                canvas.restore();
            } else {
                p.setShader(new RadialGradient(cx, cy, rc, 0xFF0E5A7E, 0xFF031B2B,
                        Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, rc, p);
                p.setShader(null);
                p.setColor(0xFFF59E0B);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(rc * 0.9f);
                canvas.drawText("♪", cx, cy + rc * 0.3f, p);
            }

            // Petit trou au centre
            p.setColor(0xFF020E18);
            canvas.drawCircle(cx, cy, r * 0.08f, p);

            // Contour brillant
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.02f);
            p.setColor(0x66FFFFFF);
            canvas.drawCircle(cx, cy, r - 1f, p);
        }
    }

    // =====================================================
    //  NOUVEAU : IMAGE ZOOMABLE (pincer pour zoomer, double tap, glisser)
    //  Utilisée pour les photos et les pages PDF
    // =====================================================
    static class ZoomImageView extends ImageView {
        Runnable surGauche; // glisser vers la gauche  -> suivant
        Runnable surDroite; // glisser vers la droite -> précédent

        private final Matrix m = new Matrix();
        private final float[] vals = new float[9];
        private float echelleMin = 1f;
        private float dernierX, dernierY;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestes;

        ZoomImageView(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);

            scaleDetector = new ScaleGestureDetector(c,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector d) {
                            float actuel = echelle();
                            float voulu = Math.max(echelleMin,
                                    Math.min(actuel * d.getScaleFactor(), echelleMin * 6f));
                            float f = voulu / actuel;
                            m.postScale(f, f, d.getFocusX(), d.getFocusY());
                            corriger();
                            setImageMatrix(m);
                            return true;
                        }
                    });

            gestes = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (echelle() > echelleMin * 1.05f) {
                        reinit();
                    } else {
                        m.postScale(2.5f, 2.5f, e.getX(), e.getY());
                        corriger();
                        setImageMatrix(m);
                    }
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    if (echelle() <= echelleMin * 1.05f
                            && Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 800) {
                        if (vx < 0 && surGauche != null) surGauche.run();
                        else if (vx > 0 && surDroite != null) surDroite.run();
                        return true;
                    }
                    return false;
                }
            });
        }

        @Override
        public void setImageBitmap(Bitmap bm) {
            super.setImageBitmap(bm);
            reinit();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            reinit();
        }

        private float echelle() {
            m.getValues(vals);
            return vals[Matrix.MSCALE_X];
        }

        private void reinit() {
            if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
            float dw = getDrawable().getIntrinsicWidth();
            float dh = getDrawable().getIntrinsicHeight();
            float s = Math.min(getWidth() / dw, getHeight() / dh);
            m.reset();
            m.postScale(s, s);
            m.postTranslate((getWidth() - dw * s) / 2f, (getHeight() - dh * s) / 2f);
            echelleMin = s;
            setImageMatrix(m);
        }

        private void corriger() {
            if (getDrawable() == null) return;
            RectF r = new RectF(0, 0, getDrawable().getIntrinsicWidth(),
                    getDrawable().getIntrinsicHeight());
            m.mapRect(r);
            float vw = getWidth();
            float vh = getHeight();
            float dx = 0, dy = 0;
            if (r.width() <= vw) dx = (vw - r.width()) / 2f - r.left;
            else if (r.left > 0) dx = -r.left;
            else if (r.right < vw) dx = vw - r.right;
            if (r.height() <= vh) dy = (vh - r.height()) / 2f - r.top;
            else if (r.top > 0) dy = -r.top;
            else if (r.bottom < vh) dy = vh - r.bottom;
            m.postTranslate(dx, dy);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaleDetector.onTouchEvent(e);
            gestes.onTouchEvent(e);
            if (!scaleDetector.isInProgress() && e.getPointerCount() == 1) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    dernierX = e.getX();
                    dernierY = e.getY();
                } else if (e.getAction() == MotionEvent.ACTION_MOVE
                        && echelle() > echelleMin * 1.05f) {
                    m.postTranslate(e.getX() - dernierX, e.getY() - dernierY);
                    dernierX = e.getX();
                    dernierY = e.getY();
                    corriger();
                    setImageMatrix(m);
                }
            } else if (e.getPointerCount() > 1) {
                dernierX = e.getX();
                dernierY = e.getY();
            }
            return true;
        }
    }
}