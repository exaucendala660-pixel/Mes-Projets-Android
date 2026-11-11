package com.example.herorun3d;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * HERO RUN 3D - tout le jeu dans un seul fichier.
 *
 * Contenu :
 *  1. MainActivity   : l'unique ecran (plein ecran, portrait)
 *  2. GameConfig     : toutes les constantes
 *  3. SaveData       : sauvegarde (pieces, heros achetes, heros choisi, missions, record)
 *  4. Missions       : liste des missions
 *  5. CharacterDef   : les 10 heros (nom, prix, couleurs)
 *  6. Player, Obstacle, Coin : les objets du jeu
 *  7. GameThread     : la boucle de jeu (60 images / seconde)
 *  8. Sfx            : sons et musique generes par le code
 *  9. GameView       : moteur pseudo-3D, ecran de chargement, menu, heros,
 *                      missions, boutique, jeu, pause, fin de partie, gestes
 */
public class MainActivity extends Activity {

    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        gameView = new GameView(this);
        setContentView(gameView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        gameView.resumeGame();
        gameView.startMusic();
    }

    @Override
    protected void onPause() {
        super.onPause();
        gameView.stopMusic();
        gameView.pauseGame();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        gameView.release();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    public void onBackPressed() {
        if (!gameView.handleBack()) {
            super.onBackPressed();
        }
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    void quitGame() {
        finishAffinity();
    }
}

// ======================================================================
// Constantes
// ======================================================================
final class GameConfig {

    private GameConfig() { }

    static final int LANE_COUNT = 3;
    static final float LANE_WIDTH = 1.6f;
    static final float FOCAL = 40f;
    static final float MAX_DEPTH = 240f;
    static final float PLAYER_DEPTH = 2.4f;

    static final float START_SPEED = 26f;
    static final float MAX_SPEED = 58f;
    static final float SPEED_ACCEL = 0.6f;

    static final float GRAVITY = -55f;
    static final float JUMP_VELOCITY = 14f;
    static final float SLIDE_DURATION = 0.62f;
    static final float LANE_SWITCH_SPEED = 10f;

    static final int COIN_SCORE = 25;
}

// ======================================================================
// Les 10 heros (provisoires, dessines par le code : les photos viendront apres)
// ======================================================================
final class CharacterDef {

    private CharacterDef() { }

    static final int COUNT = 10;

    /** Noms affiches. L'ordre correspond a hero_01.png ... hero_10.png. */
    static final String[] NAME = {
            "SUPERMAN", "HULK", "BATMAN", "NARUTO", "THOR",
            "CAPTAIN AMERICA", "VEGETA", "DOCTOR STRANGE", "GREEN ARROW", "BLACK PANTHER"
    };

    /** Heros qui ont une cape (ou un manteau) quand on les voit de dos en course. */
    static final boolean[] HAS_CAPE = {
            true, false, true, true, true, false, false, true, false, false
    };

    /** Prix en pieces (le premier est gratuit). */
    static final int[] PRICE = {
            0, 500, 1000, 2000, 3500, 5000, 8000, 12000, 18000, 25000
    };

    /** Couleurs : costume, cape, peau, cheveux, accent (ceinture/embleme), bottes. */
    static final int[][] COLORS = {
            // 1 Superman
            {0xFF2B6BEA, 0xFFC92A2A, 0xFFF5C9A0, 0xFF5A3A24, 0xFFE0B040, 0xFFD62828},
            // 2 Hulk
            {0xFF4CAF50, 0xFF4CAF50, 0xFF4CAF50, 0xFF5A3A2A, 0xFF6B5A78, 0xFF2E7D6B},
            // 3 Batman
            {0xFF37474F, 0xFF151515, 0xFFF0C8A0, 0xFF1A1A1A, 0xFFE5B800, 0xFF111111},
            // 4 Naruto
            {0xFFFF9800, 0xFFC62828, 0xFFF8D8B8, 0xFFFFE23A, 0xFF4A4A4A, 0xFF3E3A36},
            // 5 Thor
            {0xFF3F5E8A, 0xFFC62828, 0xFFF0C8A0, 0xFFB8C4CC, 0xFFC9A227, 0xFF5D4037},
            // 6 Captain America
            {0xFF1A3C78, 0xFF1A3C78, 0xFFF0B090, 0xFF1A3C78, 0xFFD32F2F, 0xFF6D4C41},
            // 7 Vegeta
            {0xFF1A237E, 0xFF1A237E, 0xFFF0C8A0, 0xFF111111, 0xFFEEEEEE, 0xFFEEEEEE},
            // 8 Doctor Strange
            {0xFF1E4A9C, 0xFFC62828, 0xFFF0C8A0, 0xFF4E342E, 0xFFC9A227, 0xFF5D4037},
            // 9 Green Arrow
            {0xFF2E5E34, 0xFF2E5E34, 0xFFE8B890, 0xFF2E5E34, 0xFF6D4C2F, 0xFF1B3A20},
            // 10 Black Panther
            {0xFF1B1B1F, 0xFF1B1B1F, 0xFF1B1B1F, 0xFF1B1B1F, 0xFFC0C0C8, 0xFF111111}
    };

    /** Heros qui portent un masque sur les yeux. */
    static boolean hasMask(int i) {
        return i == 1 || i == 4 || i == 5 || i == 7;
    }
}

// ======================================================================
// Missions
// ======================================================================
final class Missions {

    private Missions() { }

    static final int T_COINS = 0;
    static final int T_JUMPS = 1;
    static final int T_SLIDES = 2;
    static final int T_DIST = 3;

    static final int COUNT = 9;
    static final int ACTIVE = 3;

    static final int[] TYPE = {
            T_COINS, T_JUMPS, T_SLIDES, T_DIST, T_COINS, T_JUMPS, T_DIST, T_SLIDES, T_COINS
    };
    static final int[] TARGET = {
            30, 15, 10, 1000, 100, 50, 3000, 40, 300
    };
    static final int[] REWARD = {
            50, 60, 60, 100, 150, 200, 300, 250, 500
    };

    static String text(int i) {
        switch (TYPE[i]) {
            case T_COINS:
                return "Ramasse " + TARGET[i] + " pièces";
            case T_JUMPS:
                return "Saute " + TARGET[i] + " fois";
            case T_SLIDES:
                return "Glisse " + TARGET[i] + " fois";
            default:
                return "Cours " + TARGET[i] + " mètres";
        }
    }
}

// ======================================================================
// Sauvegarde
// ======================================================================
final class SaveData {

    private final SharedPreferences prefs;

    int coins = 0;
    int best = 0;
    int owned = 1;
    int selected = 0;
    boolean sound = true;
    final int[] missionProgress = new int[Missions.COUNT];
    final boolean[] missionClaimed = new boolean[Missions.COUNT];

    SaveData(Context context) {
        prefs = context.getSharedPreferences("hero_run_prefs", Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        coins = prefs.getInt("coins", 0);
        best = prefs.getInt("best_score", 0);
        owned = prefs.getInt("owned", 1) | 1;
        selected = prefs.getInt("selected", 0);
        if (selected < 0 || selected >= CharacterDef.COUNT || !isOwned(selected)) {
            selected = 0;
        }
        sound = prefs.getBoolean("sound", true);
        for (int i = 0; i < Missions.COUNT; i++) {
            missionProgress[i] = prefs.getInt("mp_" + i, 0);
            missionClaimed[i] = prefs.getBoolean("mc_" + i, false);
        }
    }

    synchronized void save() {
        SharedPreferences.Editor e = prefs.edit();
        e.putInt("coins", coins);
        e.putInt("best_score", best);
        e.putInt("owned", owned);
        e.putInt("selected", selected);
        e.putBoolean("sound", sound);
        for (int i = 0; i < Missions.COUNT; i++) {
            e.putInt("mp_" + i, missionProgress[i]);
            e.putBoolean("mc_" + i, missionClaimed[i]);
        }
        e.apply();
    }

    boolean isOwned(int i) {
        return (owned & (1 << i)) != 0;
    }

    synchronized boolean buy(int i) {
        if (isOwned(i) || coins < CharacterDef.PRICE[i]) {
            return false;
        }
        coins -= CharacterDef.PRICE[i];
        owned |= (1 << i);
        save();
        return true;
    }

    synchronized void select(int i) {
        if (isOwned(i)) {
            selected = i;
            save();
        }
    }

    synchronized void addCoins(int n) {
        coins += n;
    }

    /** Fait avancer les missions actives du type donne. */
    synchronized void addProgress(int type, int amount) {
        int found = 0;
        for (int i = 0; i < Missions.COUNT && found < Missions.ACTIVE; i++) {
            if (missionClaimed[i]) {
                continue;
            }
            found++;
            if (Missions.TYPE[i] == type && missionProgress[i] < Missions.TARGET[i]) {
                missionProgress[i] = Math.min(Missions.TARGET[i], missionProgress[i] + amount);
            }
        }
    }

    /** Indices des missions actives (les 3 premieres non reclamees), -1 si vide. */
    synchronized int[] activeMissions() {
        int[] out = {-1, -1, -1};
        int n = 0;
        for (int i = 0; i < Missions.COUNT && n < Missions.ACTIVE; i++) {
            if (!missionClaimed[i]) {
                out[n++] = i;
            }
        }
        return out;
    }

    synchronized boolean claim(int i) {
        if (i < 0 || missionClaimed[i] || missionProgress[i] < Missions.TARGET[i]) {
            return false;
        }
        missionClaimed[i] = true;
        coins += Missions.REWARD[i];
        save();
        return true;
    }
}

// ======================================================================
// Heros (le joueur en cours de partie)
// ======================================================================
class Player {

    int lane = 1;
    float lanePosition = 0f;
    float jumpHeight = 0f;
    float verticalSpeed = 0f;
    float slideTimer = 0f;
    float runPhase = 0f;
    float jumpBuffer = 0f;

    void reset() {
        lane = 1;
        lanePosition = 0f;
        jumpHeight = 0f;
        verticalSpeed = 0f;
        slideTimer = 0f;
        runPhase = 0f;
        jumpBuffer = 0f;
    }

    float laneOffset() {
        return lanePosition * GameConfig.LANE_WIDTH;
    }

    boolean isJumping() {
        return jumpHeight > 0.01f;
    }

    boolean isSliding() {
        return slideTimer > 0f;
    }

    void moveLeft() {
        if (lane > 0) {
            lane--;
        }
    }

    void moveRight() {
        if (lane < GameConfig.LANE_COUNT - 1) {
            lane++;
        }
    }

    /** Renvoie true si un saut vient bien de commencer. */
    boolean jump() {
        if (!isJumping() && verticalSpeed == 0f) {
            verticalSpeed = GameConfig.JUMP_VELOCITY;
            slideTimer = 0f;
            return true;
        }
        jumpBuffer = 0.15f; // memorise le saut demande juste avant l'atterrissage
        return false;
    }

    void slide() {
        if (isJumping()) {
            verticalSpeed = -GameConfig.JUMP_VELOCITY * 1.6f;
        }
        slideTimer = GameConfig.SLIDE_DURATION;
    }

    void update(float dt) {
        float target = lane - 1;
        lanePosition += (target - lanePosition) * Math.min(1f, GameConfig.LANE_SWITCH_SPEED * dt);

        if (isJumping() || verticalSpeed != 0f) {
            verticalSpeed += GameConfig.GRAVITY * dt;
            jumpHeight += verticalSpeed * dt;
            if (jumpHeight <= 0f) {
                jumpHeight = 0f;
                verticalSpeed = 0f;
                if (jumpBuffer > 0f) {
                    verticalSpeed = GameConfig.JUMP_VELOCITY;
                    jumpBuffer = 0f;
                }
            }
        }

        if (jumpBuffer > 0f) {
            jumpBuffer -= dt;
        }

        if (slideTimer > 0f) {
            slideTimer -= dt;
        }
        if (slideTimer < 0f) {
            slideTimer = 0f;
        }

        runPhase += dt * 12f;
        if (runPhase > 1000f) {
            runPhase = 0f;
        }
    }
}

// ======================================================================
// Obstacle : trois types, trois facons de l'eviter
// ======================================================================
class Obstacle {

    static final int TYPE_BARRIER = 0; // barriere basse : sauter
    static final int TYPE_BAR = 1;     // barre suspendue : glisser
    static final int TYPE_TRAIN = 2;   // train : changer de voie

    int type;
    int lane;
    float depth;
    float width;
    float height;
    boolean resolved = false;

    Obstacle(int type, int lane, float depth) {
        this.type = type;
        this.lane = lane;
        this.depth = depth;

        switch (type) {
            case TYPE_BARRIER:
                width = 1.25f;
                height = 0.78f;
                break;
            case TYPE_BAR:
                width = 1.25f;
                height = 1.55f;
                break;
            default:
                width = 1.40f;
                height = 3.00f;
                break;
        }
    }
}

// ======================================================================
// Piece
// ======================================================================
class Coin {

    int lane;
    float depth;
    float spin = 0f;

    Coin(int lane, float depth) {
        this.lane = lane;
        this.depth = depth;
    }
}

// ======================================================================
// Boucle de jeu (thread separe, ~60 FPS)
// ======================================================================
class GameThread extends Thread {

    interface Frame {
        void onFrame(float deltaTime);
    }

    private final Frame frame;
    private volatile boolean running = false;

    GameThread(Frame frame) {
        this.frame = frame;
        setName("HeroRun-GameThread");
    }

    void setRunning(boolean value) {
        this.running = value;
    }

    @Override
    public void run() {
        long previous = System.nanoTime();

        while (running) {
            long start = System.nanoTime();
            float dt = (start - previous) / 1000000000f;
            previous = start;

            if (dt > 0.05f) {
                dt = 0.05f;
            }
            if (dt < 0f) {
                dt = 0f;
            }

            frame.onFrame(dt);

            long elapsed = System.nanoTime() - start;
            long sleepMs = (16666666L - elapsed) / 1000000L;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }
}

// ======================================================================
// Sons et musique generes par le code (AudioTrack, aucun fichier .wav)
// Le chargement se fait en 5 etapes pour alimenter la barre de chargement.
// ======================================================================
class Sfx {

    static final int STEPS = 5;
    private static final int RATE = 22050;

    private AudioTrack coin;
    private AudioTrack jump;
    private AudioTrack hit;
    private AudioTrack slide;
    private AudioTrack music;
    private boolean musicWanted = false;
    private volatile boolean enabled = true;

    Sfx() {
        // le chargement des sons est fait par loadStep()
    }

    /** Charge un son (appele depuis un thread de chargement). */
    void loadStep(int step) {
        switch (step) {
            case 0:
                coin = make(sweep(880f, 1760f, 0.16f, 0.45f, true), false);
                break;
            case 1:
                jump = make(sweep(300f, 700f, 0.18f, 0.45f, false), false);
                break;
            case 2:
                slide = make(sweep(600f, 200f, 0.20f, 0.35f, false), false);
                break;
            case 3:
                hit = make(crash(0.45f, 0.8f), false);
                break;
            default:
                AudioTrack m = make(melody(), true);
                if (m != null) {
                    try {
                        m.setVolume(0.22f);
                    } catch (Exception e) {
                        // ignore
                    }
                }
                synchronized (this) {
                    music = m;
                    if (musicWanted && enabled && music != null) {
                        try {
                            music.play();
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
                break;
        }
    }

    // ---- Generation des echantillons ----

    private short[] sweep(float f0, float f1, float duration, float volume, boolean square) {
        int n = (int) (duration * RATE);
        short[] data = new short[n];
        double phase = 0.0;
        for (int i = 0; i < n; i++) {
            float k = i / (float) n;
            double f = f0 + (f1 - f0) * k;
            phase += 2.0 * Math.PI * f / RATE;
            double s = Math.sin(phase);
            if (square) {
                s = s >= 0 ? 0.7 : -0.7;
            }
            double env = (1.0 - k);
            if (i < 120) {
                env *= i / 120.0;
            }
            data[i] = (short) (s * env * volume * 32767);
        }
        return data;
    }

    private short[] crash(float duration, float volume) {
        int n = (int) (duration * RATE);
        short[] data = new short[n];
        Random r = new Random(5);
        double phase = 0.0;
        double prev = 0.0;
        for (int i = 0; i < n; i++) {
            float k = i / (float) n;
            double noise = r.nextDouble() * 2.0 - 1.0;
            prev = prev * 0.55 + noise * 0.45;
            phase += 2.0 * Math.PI * (150.0 - 100.0 * k) / RATE;
            double s = prev * 0.7 + Math.sin(phase) * 0.6;
            double env = (1.0 - k) * (1.0 - k);
            data[i] = (short) (s * env * volume * 32767);
        }
        return data;
    }

    private short[] melody() {
        float[] lead = {
                523f, 0f, 622f, 523f, 698f, 0f, 784f, 698f,
                622f, 0f, 523f, 622f, 784f, 0f, 698f, 622f
        };
        float[] bass = {131f, 104f, 87f, 98f};
        float step = 0.17f;
        int stepN = (int) (step * RATE);
        short[] data = new short[stepN * lead.length];

        for (int s = 0; s < lead.length; s++) {
            float fl = lead[s];
            float fb = bass[s / 4];
            for (int i = 0; i < stepN; i++) {
                double t = i / (double) RATE;
                double env = Math.min(1.0, i / 150.0) * Math.pow(1.0 - i / (double) stepN, 0.6);
                double v = 0.0;
                if (fl > 0f) {
                    double sq = Math.sin(2.0 * Math.PI * fl * t) >= 0 ? 1.0 : -1.0;
                    v += sq * 0.18 * env;
                }
                double bassEnv = Math.min(1.0, i / 150.0) * (1.0 - 0.3 * i / (double) stepN);
                v += Math.sin(2.0 * Math.PI * fb * t) * 0.35 * bassEnv;
                data[s * stepN + i] = (short) (v * 32767);
            }
        }
        return data;
    }

    private AudioTrack make(short[] data, boolean loop) {
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(data.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            t.write(data, 0, data.length);
            if (loop) {
                t.setLoopPoints(0, data.length, -1);
            }
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Lecture ----

    private void play(AudioTrack t, float volume) {
        if (t == null || !enabled) {
            return;
        }
        try {
            t.stop();
            t.reloadStaticData();
            t.setVolume(volume);
            t.play();
        } catch (Exception e) {
            // ignore
        }
    }

    void playCoin()  { play(coin, 0.9f); }
    void playJump()  { play(jump, 0.7f); }
    void playSlide() { play(slide, 0.6f); }
    void playHit()   { play(hit, 1.0f); }

    void setEnabled(boolean value) {
        enabled = value;
        synchronized (this) {
            if (music != null) {
                try {
                    if (value && musicWanted) {
                        music.play();
                    } else {
                        music.pause();
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    synchronized void startMusic() {
        musicWanted = true;
        if (music != null && enabled) {
            try {
                music.play();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    synchronized void stopMusic() {
        musicWanted = false;
        if (music != null) {
            try {
                music.pause();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    synchronized void release() {
        AudioTrack[] all = {coin, jump, slide, hit, music};
        for (int i = 0; i < all.length; i++) {
            if (all[i] != null) {
                try {
                    all[i].release();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        coin = null;
        jump = null;
        slide = null;
        hit = null;
        music = null;
    }
}

// ======================================================================
// Vue du jeu : moteur pseudo-3D + ecrans + logique + gestes
// ======================================================================
class GameView extends SurfaceView implements SurfaceHolder.Callback, GameThread.Frame {

    // ---- Etats (ecrans) ----
    private static final int ST_LOADING = 0;
    private static final int ST_MENU = 1;
    private static final int ST_HEROES = 2;
    private static final int ST_MISSIONS = 3;
    private static final int ST_SHOP = 4;
    private static final int ST_SETTINGS = 5;
    private static final int ST_ABOUT = 6;
    private static final int ST_RUNNING = 7;
    private static final int ST_PAUSED = 8;
    private static final int ST_OVER = 9;

    // ---- Evenements tactiles (poses par le thread UI, lus par le thread de jeu) ----
    private static final int T_TAP = 0;
    private static final int T_LEFT = 1;
    private static final int T_RIGHT = 2;
    private static final int T_UP = 3;
    private static final int T_DOWN = 4;
    private static final int T_BACK = 5;

    private static final class Touch {
        final int kind;
        final float x;
        final float y;

        Touch(int kind, float x, float y) {
            this.kind = kind;
            this.x = x;
            this.y = y;
        }
    }

    private static final String[] ABOUT_LINES = {
            "BUT DU JEU",
            "Runner sans fin en pseudo-3D. Ton héros court",
            "sur 3 voies : survis le plus longtemps en",
            "évitant les obstacles et ramasse des pièces.",
            "",
            "COMMANDES",
            "Glisser gauche / droite : changer de voie",
            "Glisser en haut ou toucher : sauter",
            "Glisser en bas : se baisser",
            "",
            "OBSTACLES",
            "Barrière orange : à sauter",
            "Barre rouge suspendue : se baisser",
            "Train bleu : changer de voie",
            "",
            "HÉROS ET MISSIONS",
            "Achète de nouveaux héros avec tes pièces",
            "et accomplis les missions pour en gagner."
    };

    private static final int[] HOUSE_COLORS = {
            0xFFD9B38C, 0xFFC8795A, 0xFFE6D5A5, 0xFFA3B8C4, 0xFFCFA7A0, 0xFFB9C79A
    };
    private static final int[] TREE_COLORS = {
            0xFF2E7D32, 0xFF388E3C, 0xFF43A047, 0xFF558B2F, 0xFF66A02C
    };

    private static final float SLOT = 10f;

    private final Context ctx;
    private final SaveData save;

    private final Player player = new Player();
    private final ArrayList<Obstacle> obstacles = new ArrayList<Obstacle>();
    private final ArrayList<Coin> coins = new ArrayList<Coin>();
    private final Random random = new Random();
    private final ConcurrentLinkedQueue<Touch> touches = new ConcurrentLinkedQueue<Touch>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    // boutons
    private final RectF btnMissions = new RectF();
    private final RectF btnHeroes = new RectF();
    private final RectF btnShop = new RectF();
    private final RectF btnGear = new RectF();
    private final RectF btnBack = new RectF();
    private final RectF btnLeft = new RectF();
    private final RectF btnRight = new RectF();
    private final RectF btnAction = new RectF();
    private final RectF btnPause = new RectF();
    private final RectF btnResume = new RectF();
    private final RectF btnPauseMenu = new RectF();
    private final RectF btnOverReplay = new RectF();
    private final RectF btnOverMenu = new RectF();
    private final RectF btnSound = new RectF();
    private final RectF btnAbout = new RectF();
    private final RectF btnQuit = new RectF();
    private final RectF btnClose = new RectF();
    private final RectF btnAboutOk = new RectF();
    private final RectF btnShopHeroes = new RectF();
    private final RectF[] cards = {new RectF(), new RectF(), new RectF()};
    private final RectF[] btnClaim = {new RectF(), new RectF(), new RectF()};

    private GameThread thread;
    private final Sfx sound;

    // ---- Etat du jeu (touche uniquement par le thread de jeu) ----
    private volatile int state = ST_LOADING;
    private volatile boolean pauseRequested = false;
    private volatile boolean layoutReady = false;

    private int score = 0;
    private int coinsRun = 0;
    private boolean newRecord = false;
    private float speed = GameConfig.START_SPEED;
    private float spawnTimer = 1.4f;
    private float coinTimer = 1.0f;
    private float shake = 0f;
    private float overDelay = 0f;
    private float distance = 0f;
    private int lastMeters = 0;

    private float scrollZ = 0f;
    private float cloudT = 0f;
    private float animT = 0f;

    private int viewHero = 0;
    private boolean lockTint = false;

    private String toastText = "";
    private float toastTimer = 0f;

    // ---- Chargement ----
    private volatile float loadReal = 0f;
    private volatile boolean loadDone = false;
    private float loadShown = 0f;
    private float loadTimer = 0f;
    private boolean loaderStarted = false;

    // ---- Dimensions ----
    private int width = 0;
    private int height = 0;
    private float horizonY = 0f;
    private float centerX = 0f;
    private float pxPerUnit = 0f;
    private float eyeHeight = 10f;
    private Shader skyShader;
    private Shader hazeShader;
    private Shader loadShader;

    // images des heros (null = on dessine le heros provisoire par le code)
    private final Bitmap[] heroBmp = new Bitmap[CharacterDef.COUNT];
    private final Paint imgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ColorMatrixColorFilter lockFilter = makeLockFilter();

    private float[] bldX = new float[0];
    private float[] bldW = new float[0];
    private float[] bldH = new float[0];

    private static final float[] CLOUD_X = {0.10f, 0.35f, 0.60f, 0.85f, 0.25f, 0.72f};
    private static final float[] CLOUD_Y = {0.40f, 0.18f, 0.34f, 0.12f, 0.26f, 0.06f};
    private static final float[] CLOUD_S = {1.0f, 0.8f, 1.2f, 0.7f, 0.9f, 0.6f};

    // ---- Gestes ----
    private float downX = 0f;
    private float downY = 0f;
    private boolean swiped = false;

    private static final Comparator<Obstacle> FAR_FIRST = new Comparator<Obstacle>() {
        @Override
        public int compare(Obstacle a, Obstacle b) {
            return Float.compare(b.depth, a.depth);
        }
    };

    private static final Comparator<Coin> COIN_FAR_FIRST = new Comparator<Coin>() {
        @Override
        public int compare(Coin a, Coin b) {
            return Float.compare(b.depth, a.depth);
        }
    };

    GameView(Context context) {
        super(context);
        ctx = context;
        save = new SaveData(context);
        getHolder().addCallback(this);
        setFocusable(true);
        sound = new Sfx();
        sound.setEnabled(save.sound);
        viewHero = save.selected;
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        resetRun();
        state = ST_LOADING;
        startLoader();
    }

    // ------------------------------------------------------------------
    // Chargement reel en arriere-plan
    // ------------------------------------------------------------------

    private void startLoader() {
        if (loaderStarted) {
            return;
        }
        loaderStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int total = CharacterDef.COUNT + Sfx.STEPS;
                int done = 0;

                // 1) les images des heros (le heros choisi en premier)
                loadHeroBitmap(save.selected);
                done++;
                loadReal = done / (float) total;
                for (int i = 0; i < CharacterDef.COUNT; i++) {
                    if (i == save.selected) {
                        continue;
                    }
                    loadHeroBitmap(i);
                    done++;
                    loadReal = done / (float) total;
                }

                // 2) les sons
                for (int i = 0; i < Sfx.STEPS; i++) {
                    sound.loadStep(i);
                    done++;
                    loadReal = done / (float) total;
                }
                loadDone = true;
            }
        });
        t.setName("HeroRun-Loader");
        t.start();
    }

    /** Charge res/drawable/hero_01.png ... hero_10.png (sans les agrandir selon la densite d'ecran). */
    private void loadHeroBitmap(int i) {
        try {
            String name = String.format(java.util.Locale.US, "hero_%02d", i + 1);
            int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
            if (id == 0) {
                return;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            heroBmp[i] = BitmapFactory.decodeResource(ctx.getResources(), id, o);
        } catch (Throwable e) {
            heroBmp[i] = null; // on garde le dessin provisoire
        }
    }

    private static ColorMatrixColorFilter makeLockFilter() {
        ColorMatrix gray = new ColorMatrix();
        gray.setSaturation(0f);
        ColorMatrix dark = new ColorMatrix(new float[] {
                0.35f, 0f, 0f, 0f, 0f,
                0f, 0.35f, 0f, 0f, 0f,
                0f, 0f, 0.35f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
        });
        gray.postConcat(dark);
        return new ColorMatrixColorFilter(gray);
    }

    private void updateLoading(float dt) {
        loadTimer += dt;
        // la barre suit le chargement reel, mais dure au moins ~2 secondes
        float target = Math.min(loadReal, loadTimer / 2.0f);
        if (loadShown < target) {
            loadShown = Math.min(target, loadShown + dt * 0.9f);
        }
        if (loadDone && loadShown >= 0.999f && loadTimer >= 2.0f) {
            state = ST_MENU;
        }
    }

    // ------------------------------------------------------------------
    // Cycle de vie
    // ------------------------------------------------------------------

    void resumeGame() {
        if (thread == null) {
            thread = new GameThread(this);
            thread.setRunning(true);
            thread.start();
        }
    }

    void pauseGame() {
        if (state == ST_RUNNING) {
            pauseRequested = true;
        }
        save.save();
        if (thread != null) {
            thread.setRunning(false);
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    void startMusic() {
        sound.startMusic();
    }

    void stopMusic() {
        sound.stopMusic();
    }

    void release() {
        sound.release();
    }

    /** Bouton retour : renvoie true si le jeu a gere l'appui. */
    boolean handleBack() {
        int s = state;
        if (s == ST_MENU || s == ST_LOADING) {
            return false;
        }
        touches.add(new Touch(T_BACK, 0f, 0f));
        return true;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        resumeGame();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        layoutReady = false;
        width = w;
        height = h;
        horizonY = height * 0.34f;
        centerX = width / 2f;
        pxPerUnit = Math.min(width * 0.135f, height * 0.105f);
        eyeHeight = (height - horizonY) / pxPerUnit;
        skyShader = new LinearGradient(0f, 0f, 0f, horizonY,
                0xFF1E88E5, 0xFF9BDCFF, Shader.TileMode.CLAMP);
        hazeShader = new LinearGradient(0f, horizonY, 0f, horizonY + height * 0.14f,
                0xDDE3F4FF, 0x00E3F4FF, Shader.TileMode.CLAMP);
        loadShader = new LinearGradient(0f, 0f, 0f, height,
                0xFF4AD6FF, 0xFF1E8EFF, Shader.TileMode.CLAMP);
        buildSkyline();
        layoutButtons();
        layoutReady = true;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        pauseGame();
    }

    private void buildSkyline() {
        Random r = new Random(11);
        int count = 18;
        bldX = new float[count];
        bldW = new float[count];
        bldH = new float[count];
        for (int i = 0; i < count; i++) {
            bldX[i] = r.nextFloat();
            bldW[i] = 0.03f + r.nextFloat() * 0.05f;
            bldH[i] = 0.05f + r.nextFloat() * 0.13f;
        }
    }

    private void layoutButtons() {
        float w = width;
        float h = height;

        // menu : boutons du bas
        float bw = w * 0.29f;
        float bh = h * 0.075f;
        float gap = w * 0.025f;
        float left = (w - (bw * 3f + gap * 2f)) / 2f;
        float top = h * 0.885f;
        btnMissions.set(left, top, left + bw, top + bh);
        btnHeroes.set(left + bw + gap, top, left + bw * 2f + gap, top + bh);
        btnShop.set(left + (bw + gap) * 2f, top, left + (bw + gap) * 2f + bw, top + bh);

        // engrenage et bouton pause
        float g = w * 0.115f;
        btnGear.set(w - w * 0.04f - g, h * 0.035f, w - w * 0.04f, h * 0.035f + g);
        btnPause.set(w * 0.04f, h * 0.035f, w * 0.04f + g, h * 0.035f + g);

        // retour
        btnBack.set(w * 0.25f, h * 0.89f, w * 0.75f, h * 0.89f + h * 0.065f);

        // heros
        float arrow = w * 0.13f;
        btnLeft.set(w * 0.03f, h * 0.40f, w * 0.03f + arrow, h * 0.40f + arrow);
        btnRight.set(w - w * 0.03f - arrow, h * 0.40f, w - w * 0.03f, h * 0.40f + arrow);
        btnAction.set(w * 0.19f, h * 0.755f, w * 0.81f, h * 0.755f + h * 0.07f);

        // missions et boutique : cartes
        float cardH = h * 0.17f;
        float cardGap = h * 0.025f;
        float cardTop = h * 0.18f;
        for (int i = 0; i < 3; i++) {
            float y = cardTop + i * (cardH + cardGap);
            cards[i].set(w * 0.06f, y, w * 0.94f, y + cardH);
            btnClaim[i].set(cards[i].right - w * 0.30f - w * 0.025f, cards[i].bottom - h * 0.065f - h * 0.02f,
                    cards[i].right - w * 0.025f, cards[i].bottom - h * 0.02f);
        }
        btnShopHeroes.set(w * 0.15f, h * 0.70f, w * 0.85f, h * 0.70f + h * 0.075f);

        // parametres
        float sw = w * 0.64f;
        float sh = h * 0.075f;
        float sl = (w - sw) / 2f;
        btnSound.set(sl, h * 0.33f, sl + sw, h * 0.33f + sh);
        btnAbout.set(sl, h * 0.43f, sl + sw, h * 0.43f + sh);
        btnQuit.set(sl, h * 0.53f, sl + sw, h * 0.53f + sh);
        btnClose.set(sl, h * 0.63f, sl + sw, h * 0.63f + sh);

        // a propos
        float ab = w * 0.40f;
        float ah = h * 0.060f;
        btnAboutOk.set(w / 2f - ab / 2f, h * 0.94f - ah - h * 0.02f, w / 2f + ab / 2f, h * 0.94f - h * 0.02f);

        // pause
        btnResume.set(sl, h * 0.42f, sl + sw, h * 0.42f + sh);
        btnPauseMenu.set(sl, h * 0.53f, sl + sw, h * 0.53f + sh);

        // fin de partie
        float ow = w * 0.34f;
        float oh = h * 0.062f;
        float oy = h * 0.70f - oh - h * 0.025f;
        btnOverReplay.set(w * 0.5f - ow - w * 0.015f, oy, w * 0.5f - w * 0.015f, oy + oh);
        btnOverMenu.set(w * 0.5f + w * 0.015f, oy, w * 0.5f + w * 0.015f + ow, oy + oh);
    }

    private void resetRun() {
        player.reset();
        obstacles.clear();
        coins.clear();
        score = 0;
        coinsRun = 0;
        speed = GameConfig.START_SPEED;
        spawnTimer = 1.4f;
        coinTimer = 1.0f;
        shake = 0f;
        newRecord = false;
        overDelay = 0f;
        distance = 0f;
        lastMeters = 0;
    }

    private void startRun() {
        resetRun();
        state = ST_RUNNING;
    }

    private void toast(String msg) {
        toastText = msg;
        toastTimer = 1.6f;
    }

    // ------------------------------------------------------------------
    // Projection
    // ------------------------------------------------------------------

    private float persp(float z) {
        if (z < 0f) {
            z = 0f;
        }
        return GameConfig.FOCAL / (GameConfig.FOCAL + z);
    }

    private float yGround(float p) {
        return horizonY + (height - horizonY) * p;
    }

    private float xAt(float worldX, float p) {
        return centerX + worldX * p * pxPerUnit;
    }

    private float yAt(float worldHeight, float p) {
        return yGround(p) - worldHeight * pxPerUnit * p;
    }

    private void quad(Canvas c, float x1, float y1, float x2, float y2,
                      float x3, float y3, float x4, float y4) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.lineTo(x4, y4);
        path.close();
        c.drawPath(path, paint);
    }

    // ------------------------------------------------------------------
    // Couleurs
    // ------------------------------------------------------------------

    private static int darken(int c, float f) {
        int r = (int) (((c >> 16) & 0xFF) * f);
        int g = (int) (((c >> 8) & 0xFF) * f);
        int b = (int) ((c & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lighten(int c, float f) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        r += (int) ((255 - r) * f);
        g += (int) ((255 - g) * f);
        b += (int) ((255 - b) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lockColor(int c) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int l = (int) ((0.3f * r + 0.59f * g + 0.11f * b) * 0.35f) + 25;
        return 0xFF000000 | (l << 16) | (l << 8) | l;
    }

    private int hc(int c) {
        return lockTint ? lockColor(c) : c;
    }

    private static int hash(long n) {
        long h = n * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        return (int) (h & 0x7FFFFFFF);
    }

    // ------------------------------------------------------------------
    // Gestes tactiles : le thread UI pose des evenements, le thread de jeu les lit
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float threshold = Math.max(24f, height * 0.035f);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                swiped = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!swiped) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > threshold || Math.abs(dy) > threshold) {
                        swiped = true;
                        if (Math.abs(dx) > Math.abs(dy)) {
                            touches.add(new Touch(dx > 0f ? T_RIGHT : T_LEFT, downX, downY));
                        } else {
                            touches.add(new Touch(dy < 0f ? T_UP : T_DOWN, downX, downY));
                        }
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (!swiped) {
                    touches.add(new Touch(T_TAP, event.getX(), event.getY()));
                }
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void processTouches() {
        Touch t;
        while ((t = touches.poll()) != null) {
            handleTouch(t);
        }
    }

    private void handleTouch(Touch t) {
        int s = state;
        if (t.kind == T_BACK) {
            switch (s) {
                case ST_HEROES:
                case ST_MISSIONS:
                case ST_SHOP:
                case ST_SETTINGS:
                case ST_OVER:
                    if (s == ST_OVER) {
                        resetRun();
                    }
                    state = ST_MENU;
                    break;
                case ST_ABOUT:
                    state = ST_SETTINGS;
                    break;
                case ST_RUNNING:
                    state = ST_PAUSED;
                    break;
                case ST_PAUSED:
                    state = ST_RUNNING;
                    break;
                default:
                    break;
            }
            return;
        }

        switch (s) {
            case ST_RUNNING:
                touchRunning(t);
                break;
            case ST_MENU:
                if (t.kind == T_TAP) {
                    tapMenu(t.x, t.y);
                }
                break;
            case ST_HEROES:
                if (t.kind == T_LEFT) {
                    viewHero = (viewHero + 1) % CharacterDef.COUNT;
                } else if (t.kind == T_RIGHT) {
                    viewHero = (viewHero + CharacterDef.COUNT - 1) % CharacterDef.COUNT;
                } else if (t.kind == T_TAP) {
                    tapHeroes(t.x, t.y);
                }
                break;
            case ST_MISSIONS:
                if (t.kind == T_TAP) {
                    tapMissions(t.x, t.y);
                }
                break;
            case ST_SHOP:
                if (t.kind == T_TAP) {
                    if (btnBack.contains(t.x, t.y)) {
                        state = ST_MENU;
                    } else if (btnShopHeroes.contains(t.x, t.y)) {
                        viewHero = save.selected;
                        state = ST_HEROES;
                    }
                }
                break;
            case ST_SETTINGS:
                if (t.kind == T_TAP) {
                    tapSettings(t.x, t.y);
                }
                break;
            case ST_ABOUT:
                if (t.kind == T_TAP) {
                    state = ST_SETTINGS;
                }
                break;
            case ST_PAUSED:
                if (t.kind == T_TAP) {
                    if (btnResume.contains(t.x, t.y)) {
                        state = ST_RUNNING;
                    } else if (btnPauseMenu.contains(t.x, t.y)) {
                        save.save();
                        resetRun();
                        state = ST_MENU;
                    }
                }
                break;
            case ST_OVER:
                if (t.kind == T_TAP && overDelay <= 0f) {
                    if (btnOverMenu.contains(t.x, t.y)) {
                        resetRun();
                        state = ST_MENU;
                    } else {
                        startRun();
                    }
                }
                break;
            default:
                break;
        }
    }

    private void touchRunning(Touch t) {
        switch (t.kind) {
            case T_LEFT:
                player.moveLeft();
                sound.playSlide();
                break;
            case T_RIGHT:
                player.moveRight();
                sound.playSlide();
                break;
            case T_UP:
                doJump();
                break;
            case T_DOWN:
                doSlide();
                break;
            case T_TAP:
                if (btnPause.contains(t.x, t.y)) {
                    state = ST_PAUSED;
                } else {
                    doJump();
                }
                break;
            default:
                break;
        }
    }

    private void doJump() {
        if (player.jump()) {
            save.addProgress(Missions.T_JUMPS, 1);
            sound.playJump();
        }
    }

    private void doSlide() {
        player.slide();
        save.addProgress(Missions.T_SLIDES, 1);
        sound.playSlide();
    }

    private void tapMenu(float x, float y) {
        if (btnGear.contains(x, y)) {
            state = ST_SETTINGS;
        } else if (btnMissions.contains(x, y)) {
            state = ST_MISSIONS;
        } else if (btnHeroes.contains(x, y)) {
            viewHero = save.selected;
            state = ST_HEROES;
        } else if (btnShop.contains(x, y)) {
            state = ST_SHOP;
        } else {
            startRun();
        }
    }

    private void tapHeroes(float x, float y) {
        if (btnBack.contains(x, y)) {
            state = ST_MENU;
        } else if (btnLeft.contains(x, y)) {
            viewHero = (viewHero + CharacterDef.COUNT - 1) % CharacterDef.COUNT;
        } else if (btnRight.contains(x, y)) {
            viewHero = (viewHero + 1) % CharacterDef.COUNT;
        } else if (btnAction.contains(x, y)) {
            if (save.isOwned(viewHero)) {
                if (save.selected != viewHero) {
                    save.select(viewHero);
                    toast("Héros choisi !");
                }
            } else if (save.coins >= CharacterDef.PRICE[viewHero]) {
                save.buy(viewHero);
                save.select(viewHero);
                sound.playCoin();
                toast("Héros acheté !");
            } else {
                toast("Pas assez de pièces");
            }
        }
    }

    private void tapMissions(float x, float y) {
        if (btnBack.contains(x, y)) {
            state = ST_MENU;
            return;
        }
        int[] active = save.activeMissions();
        for (int i = 0; i < 3; i++) {
            if (active[i] >= 0 && btnClaim[i].contains(x, y)) {
                if (save.claim(active[i])) {
                    sound.playCoin();
                    toast("+" + Missions.REWARD[active[i]] + " pièces !");
                }
                return;
            }
        }
    }

    private void tapSettings(float x, float y) {
        if (btnSound.contains(x, y)) {
            save.sound = !save.sound;
            sound.setEnabled(save.sound);
            save.save();
        } else if (btnAbout.contains(x, y)) {
            state = ST_ABOUT;
        } else if (btnQuit.contains(x, y)) {
            save.save();
            if (ctx instanceof MainActivity) {
                ((MainActivity) ctx).quitGame();
            }
        } else if (btnClose.contains(x, y)) {
            state = ST_MENU;
        }
    }

    // ------------------------------------------------------------------
    // Mise a jour
    // ------------------------------------------------------------------

    @Override
    public void onFrame(float dt) {
        processTouches();

        if (pauseRequested) {
            pauseRequested = false;
            if (state == ST_RUNNING) {
                state = ST_PAUSED;
            }
        }

        animT += dt;
        cloudT += dt;

        int s = state;
        if (s == ST_LOADING) {
            updateLoading(dt);
        } else if (s == ST_RUNNING) {
            update(dt);
        } else if (s != ST_PAUSED && s != ST_OVER) {
            scrollZ += 9f * dt; // decor qui defile doucement dans les menus
        }

        if (overDelay > 0f) {
            overDelay -= dt;
        }
        if (toastTimer > 0f) {
            toastTimer -= dt;
        }
        if (shake > 0f) {
            shake = Math.max(0f, shake - dt * 3f);
        }
        drawFrame();
    }

    private void update(float dt) {
        speed = Math.min(GameConfig.MAX_SPEED, speed + GameConfig.SPEED_ACCEL * dt);
        player.update(dt);
        score += (int) (speed * dt * 1.4f);
        scrollZ += speed * dt;

        distance += speed * dt;
        int meters = (int) (distance * 0.5f);
        if (meters > lastMeters) {
            save.addProgress(Missions.T_DIST, meters - lastMeters);
            lastMeters = meters;
        }

        for (int i = obstacles.size() - 1; i >= 0; i--) {
            Obstacle o = obstacles.get(i);
            o.depth -= speed * dt;

            if (o.depth < -4f) {
                obstacles.remove(i);
                continue;
            }

            if (!o.resolved
                    && o.depth <= GameConfig.PLAYER_DEPTH + 0.9f
                    && o.depth >= GameConfig.PLAYER_DEPTH - 1.6f) {
                o.resolved = true;
                if (o.lane == player.lane) {
                    boolean avoided;
                    if (o.type == Obstacle.TYPE_BARRIER) {
                        avoided = player.jumpHeight > 0.85f;
                    } else if (o.type == Obstacle.TYPE_BAR) {
                        avoided = player.isSliding();
                    } else {
                        avoided = false;
                    }
                    if (avoided) {
                        score += 40;
                    } else {
                        gameOver();
                        return;
                    }
                }
            }
        }

        for (int i = coins.size() - 1; i >= 0; i--) {
            Coin c = coins.get(i);
            c.depth -= speed * dt;
            c.spin += dt * 3.5f;

            if (c.depth < -3f) {
                coins.remove(i);
                continue;
            }
            if (c.lane == player.lane
                    && c.depth <= GameConfig.PLAYER_DEPTH + 1.2f
                    && c.depth >= GameConfig.PLAYER_DEPTH - 1.2f
                    && player.jumpHeight < 2.6f) {
                coins.remove(i);
                coinsRun++;
                score += GameConfig.COIN_SCORE;
                save.addCoins(1);
                save.addProgress(Missions.T_COINS, 1);
                sound.playCoin();
            }
        }

        spawnTimer -= dt;
        if (spawnTimer <= 0f) {
            spawnObstacle();
            spawnTimer = Math.max(0.62f, 1.55f - speed * 0.018f);
        }

        coinTimer -= dt;
        if (coinTimer <= 0f) {
            spawnCoinRow();
            coinTimer = 2.2f + random.nextFloat() * 1.8f;
        }
    }

    private void spawnObstacle() {
        int lane = random.nextInt(GameConfig.LANE_COUNT);
        int r = random.nextInt(100);
        int type;
        if (r < 45) {
            type = Obstacle.TYPE_BARRIER;
        } else if (r < 80) {
            type = Obstacle.TYPE_BAR;
        } else {
            type = Obstacle.TYPE_TRAIN;
        }
        obstacles.add(new Obstacle(type, lane, GameConfig.MAX_DEPTH));
    }

    private void spawnCoinRow() {
        int lane = random.nextInt(GameConfig.LANE_COUNT);
        for (int i = 0; i < 4; i++) {
            coins.add(new Coin(lane, GameConfig.MAX_DEPTH + i * 3.2f));
        }
    }

    private void gameOver() {
        state = ST_OVER;
        overDelay = 0.5f;
        sound.playHit();
        shake = 1f;

        if (score > save.best) {
            save.best = score;
            newRecord = true;
        }
        save.save();

        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(90);
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    // ------------------------------------------------------------------
    // Rendu : outils de texte et de boutons
    // ------------------------------------------------------------------

    private float fitSize(String s, float maxW, float size) {
        text.setTextSize(size);
        float w = text.measureText(s);
        if (w > maxW && w > 0f) {
            return size * maxW / w;
        }
        return size;
    }

    private void outlined(Canvas c, String s, float x, float y, float size, int fill, int outline) {
        text.setTextSize(size);
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeJoin(Paint.Join.ROUND);
        text.setStrokeWidth(size * 0.16f);
        text.setColor(outline);
        c.drawText(s, x, y, text);
        text.setStyle(Paint.Style.FILL);
        text.setStrokeWidth(0f);
        text.setColor(fill);
        c.drawText(s, x, y, text);
    }

    private void button(Canvas c, RectF r, String label, int top, int dark, boolean enabled) {
        if (!enabled) {
            top = 0xFF9AA5B1;
            dark = 0xFF5F6B77;
        }
        float rad = r.height() * 0.30f;
        float depth = r.height() * 0.10f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(dark);
        rect.set(r.left, r.top + depth, r.right, r.bottom + depth);
        c.drawRoundRect(rect, rad, rad, paint);

        paint.setColor(top);
        c.drawRoundRect(r, rad, rad, paint);

        paint.setColor(0x33FFFFFF);
        float pad = r.height() * 0.08f;
        rect.set(r.left + pad, r.top + pad * 0.7f, r.right - pad, r.top + r.height() * 0.46f);
        c.drawRoundRect(rect, rad * 0.7f, rad * 0.7f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, r.height() * 0.045f));
        paint.setColor(0xFFFFFFFF);
        c.drawRoundRect(r, rad, rad, paint);
        paint.setStyle(Paint.Style.FILL);

        text.setTextAlign(Paint.Align.CENTER);
        float size = fitSize(label, r.width() * 0.86f, r.height() * 0.42f);
        outlined(c, label, r.centerX(), r.centerY() + size * 0.35f, size, 0xFFFFFFFF, darken(dark, 0.8f));
    }

    private void panel(Canvas c, float left, float top, float right, float bottom) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xEE0B1F44);
        rect.set(left, top, right, bottom);
        c.drawRoundRect(rect, width * 0.06f, width * 0.06f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, width * 0.008f));
        paint.setColor(0xFFFFC107);
        c.drawRoundRect(rect, width * 0.06f, width * 0.06f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawStar(Canvas c, float cx, float cy, float r, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        path.reset();
        for (int i = 0; i < 10; i++) {
            double ang = -Math.PI / 2 + i * Math.PI / 5;
            float rad = (i % 2 == 0) ? r : r * 0.45f;
            float px = cx + (float) Math.cos(ang) * rad;
            float py = cy + (float) Math.sin(ang) * rad;
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.close();
        c.drawPath(path, paint);
    }

    private void drawCoinIcon(Canvas c, float cx, float cy, float r) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFB8860B);
        c.drawCircle(cx, cy, r, paint);
        paint.setColor(0xFFFFC107);
        c.drawCircle(cx, cy, r * 0.84f, paint);
        drawStar(c, cx, cy, r * 0.55f, 0xFFFFF3B0);
    }

    private void coinPill(Canvas c, RectF r, int value) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        c.drawRoundRect(r, r.height() * 0.5f, r.height() * 0.5f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, r.height() * 0.05f));
        paint.setColor(0xFFFFC107);
        c.drawRoundRect(r, r.height() * 0.5f, r.height() * 0.5f, paint);
        paint.setStyle(Paint.Style.FILL);
        drawCoinIcon(c, r.left + r.height() * 0.5f, r.centerY(), r.height() * 0.34f);
        text.setTextAlign(Paint.Align.RIGHT);
        String s = String.valueOf(value);
        float size = fitSize(s, r.width() - r.height() * 1.3f, r.height() * 0.50f);
        outlined(c, s, r.right - r.height() * 0.40f, r.centerY() + size * 0.35f, size, 0xFFFFFFFF, 0xFF4A3300);
    }

    // ------------------------------------------------------------------
    // Rendu : image complete
    // ------------------------------------------------------------------

    private void drawFrame() {
        SurfaceHolder holder = getHolder();
        Canvas canvas = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    canvas = holder.lockHardwareCanvas();
                } catch (Exception e) {
                    canvas = holder.lockCanvas();
                }
            } else {
                canvas = holder.lockCanvas();
            }
            if (canvas != null) {
                drawScene(canvas);
            }
        } catch (Exception e) {
            // surface en cours de destruction
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas);
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    private void drawScene(Canvas c) {
        if (!layoutReady || width == 0 || height == 0) {
            return;
        }

        int s = state;

        if (s == ST_LOADING) {
            drawLoading(c);
            return;
        }

        boolean inGame = (s == ST_RUNNING || s == ST_PAUSED || s == ST_OVER);

        c.save();
        if (shake > 0f) {
            c.translate((random.nextFloat() - 0.5f) * 18f * shake, 0f);
        }

        drawBackdrop(c);

        if (inGame) {
            Collections.sort(obstacles, FAR_FIRST);
            for (int i = 0; i < obstacles.size(); i++) {
                drawObstacle(c, obstacles.get(i));
            }
            Collections.sort(coins, COIN_FAR_FIRST);
            for (int i = 0; i < coins.size(); i++) {
                drawCoin(c, coins.get(i));
            }
            drawPlayer(c);
        }
        c.restore();

        switch (s) {
            case ST_MENU:
                drawMenu(c);
                break;
            case ST_HEROES:
                drawHeroes(c);
                break;
            case ST_MISSIONS:
                drawMissions(c);
                break;
            case ST_SHOP:
                drawShop(c);
                break;
            case ST_SETTINGS:
                drawSettings(c);
                break;
            case ST_ABOUT:
                drawAbout(c);
                break;
            case ST_RUNNING:
                drawHud(c);
                break;
            case ST_PAUSED:
                drawHud(c);
                drawPaused(c);
                break;
            case ST_OVER:
                drawHud(c);
                drawGameOver(c);
                break;
            default:
                break;
        }

        if (toastTimer > 0f) {
            float a = Math.min(1f, toastTimer / 0.4f);
            int alpha = (int) (a * 255f);
            text.setTextAlign(Paint.Align.CENTER);
            outlined(c, toastText, width / 2f, height * 0.70f, height * 0.026f,
                    (alpha << 24) | 0x00FFFFFF, (alpha << 24) | 0x00112244);
        }
    }

    // ------------------------------------------------------------------
    // Rendu : decor (ciel, nuages, quartier, route)
    // ------------------------------------------------------------------

    private void drawBackdrop(Canvas c) {
        drawSky(c);
        drawGround(c);
        drawSideObjects(c);

        // brume a l'horizon
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(hazeShader);
        c.drawRect(0f, horizonY, width, horizonY + height * 0.14f, paint);
        paint.setShader(null);
    }

    private void drawSky(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(skyShader);
        c.drawRect(0f, 0f, width, horizonY + 2f, paint);
        paint.setShader(null);

        // soleil
        float sx = width * 0.80f;
        float sy = horizonY * 0.40f;
        paint.setColor(0x33FFF3B0);
        c.drawCircle(sx, sy, width * 0.15f, paint);
        paint.setColor(0x55FFF3B0);
        c.drawCircle(sx, sy, width * 0.11f, paint);
        paint.setColor(0xFFFFF6C2);
        c.drawCircle(sx, sy, width * 0.075f, paint);

        // nuages qui derivent
        float margin = width * 0.35f;
        float span = width + margin * 2f;
        for (int i = 0; i < CLOUD_X.length; i++) {
            float cx = ((CLOUD_X[i] * width + cloudT * (10f + i * 3f)) % span) - margin;
            float cy = CLOUD_Y[i] * horizonY;
            float r = width * 0.055f * CLOUD_S[i];
            paint.setColor(0xF2FFFFFF);
            c.drawCircle(cx, cy, r, paint);
            c.drawCircle(cx + r * 1.1f, cy - r * 0.45f, r * 1.15f, paint);
            c.drawCircle(cx + r * 2.3f, cy, r * 0.95f, paint);
            c.drawCircle(cx - r * 1.0f, cy + r * 0.2f, r * 0.75f, paint);
            rect.set(cx - r * 1.5f, cy, cx + r * 3.0f, cy + r * 0.95f);
            c.drawRoundRect(rect, r * 0.5f, r * 0.5f, paint);
            paint.setColor(0x14507AA8);
            rect.set(cx - r * 1.2f, cy + r * 0.55f, cx + r * 2.8f, cy + r * 0.95f);
            c.drawRoundRect(rect, r * 0.3f, r * 0.3f, paint);
        }

        // quartier lointain
        for (int i = 0; i < bldX.length; i++) {
            float bx = bldX[i] * width;
            float bw = bldW[i] * width;
            float bh = bldH[i] * height;
            paint.setColor(i % 2 == 0 ? 0xFF8DB6D4 : 0xFF7CA6C8);
            c.drawRect(bx, horizonY - bh, bx + bw, horizonY, paint);
        }
    }

    private void drawGround(Canvas c) {
        float roadHalf = (GameConfig.LANE_COUNT * GameConfig.LANE_WIDTH) / 2f;
        float bottomY = yGround(1f);

        // herbe
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF5DAF4A);
        c.drawRect(0f, horizonY, width, height, paint);

        // trottoirs
        float walk = roadHalf + 1.25f;
        paint.setColor(0xFFC9CED6);
        path.reset();
        path.moveTo(centerX - walk * pxPerUnit, bottomY);
        path.lineTo(centerX + walk * pxPerUnit, bottomY);
        path.lineTo(centerX, horizonY);
        path.close();
        c.drawPath(path, paint);

        // bordures claires
        paint.setColor(0xFFEEF1F6);
        float curb = roadHalf + 0.18f;
        path.reset();
        path.moveTo(centerX - curb * pxPerUnit, bottomY);
        path.lineTo(centerX + curb * pxPerUnit, bottomY);
        path.lineTo(centerX, horizonY);
        path.close();
        c.drawPath(path, paint);

        // route
        paint.setColor(0xFF3B4149);
        path.reset();
        path.moveTo(centerX - roadHalf * pxPerUnit, bottomY);
        path.lineTo(centerX + roadHalf * pxPerUnit, bottomY);
        path.lineTo(centerX, horizonY);
        path.close();
        c.drawPath(path, paint);

        float offset = scrollZ % 6f;

        // bandes de vitesse sur la route
        paint.setColor(0x14FFFFFF);
        for (int i = 0; i < 40; i++) {
            float z1 = i * 6f - offset;
            float z2 = z1 + 3f;
            if (z1 > GameConfig.MAX_DEPTH) {
                break;
            }
            if (z2 < 0.2f) {
                continue;
            }
            float p1 = persp(z1);
            float p2 = persp(z2);
            quad(c,
                    centerX - roadHalf * pxPerUnit * p1, yGround(p1),
                    centerX + roadHalf * pxPerUnit * p1, yGround(p1),
                    centerX + roadHalf * pxPerUnit * p2, yGround(p2),
                    centerX - roadHalf * pxPerUnit * p2, yGround(p2));
        }

        // separations de voies (entre les 3 voies : en -0.8 et +0.8)
        float dashOffset = scrollZ % 8f;
        paint.setColor(0xDDEDEFF5);
        for (int side = 0; side < 2; side++) {
            float worldX = (side == 0 ? -0.5f : 0.5f) * GameConfig.LANE_WIDTH;
            for (int i = 0; i < 31; i++) {
                float z1 = i * 8f - dashOffset;
                float z2 = z1 + 3.5f;
                if (z1 > GameConfig.MAX_DEPTH) {
                    break;
                }
                if (z2 < 0.2f) {
                    continue;
                }
                float p1 = persp(z1);
                float p2 = persp(z2);
                float w1 = Math.max(1f, 5f * p1);
                float w2 = Math.max(1f, 5f * p2);
                quad(c,
                        xAt(worldX, p1) - w1, yGround(p1),
                        xAt(worldX, p1) + w1, yGround(p1),
                        xAt(worldX, p2) + w2, yGround(p2),
                        xAt(worldX, p2) - w2, yGround(p2));
            }
        }
    }

    private void drawSideObjects(Canvas c) {
        float off = scrollZ % SLOT;
        long base = (long) (scrollZ / SLOT);
        int n = (int) (GameConfig.MAX_DEPTH / SLOT) + 1;
        for (int k = n; k >= 0; k--) {
            float z = k * SLOT - off;
            long idx = base + k;
            for (int side = 0; side < 2; side++) {
                float sgn = (side == 0) ? -1f : 1f;
                int h = hash(idx * 2L + side);
                drawHouse(c, z, sgn, h);
                drawTree(c, z + 5f, sgn, hash(idx * 7L + side + 99L));
            }
        }
    }

    private void drawHouse(Canvas c, float z, float sgn, int h) {
        float depthLen = 8f;
        float zn = Math.max(z, 0.6f);
        float zf = z + depthLen;
        if (zf <= 0.6f || zn >= GameConfig.MAX_DEPTH) {
            return;
        }

        float hh = 7f + (h % 8);
        float xI = sgn * 4.3f;
        float xO = sgn * 8.0f;
        int wall = HOUSE_COLORS[(h >> 3) % HOUSE_COLORS.length];

        float pn = persp(zn);
        float pf = persp(zf);

        float xIn = xAt(xI, pn);
        float xOn = xAt(xO, pn);
        float xIf = xAt(xI, pf);
        float xOf = xAt(xO, pf);
        float topN = yAt(hh, pn);
        float topF = yAt(hh, pf);
        float botN = yGround(pn);
        float botF = yGround(pf);

        paint.setStyle(Paint.Style.FILL);

        // toit (visible si le toit est sous le niveau des yeux)
        if (hh < eyeHeight) {
            paint.setColor(darken(wall, 0.65f));
            quad(c, xIn, topN, xOn, topN, xOf, topF, xIf, topF);
        }

        // face qui regarde la route
        paint.setColor(darken(wall, 0.78f));
        quad(c, xIn, topN, xIf, topF, xIf, botF, xIn, botN);

        // facade avant
        paint.setColor(wall);
        float left = Math.min(xIn, xOn);
        float right = Math.max(xIn, xOn);
        c.drawRect(left, topN, right, botN, paint);

        // corniche
        paint.setColor(darken(wall, 0.6f));
        float cornice = (botN - topN) * 0.05f;
        c.drawRect(left, topN, right, topN + cornice, paint);

        // fenetres et porte sur la facade
        float fw = right - left;
        float fh = botN - topN;
        if (fw > 14f) {
            int rows = 3 + (h % 3);
            paint.setColor(0xFF2A4A66);
            for (int r = 0; r < rows; r++) {
                float wy = topN + fh * (0.12f + r * (0.70f / rows));
                float wh = fh * (0.42f / rows);
                for (int col = 0; col < 3; col++) {
                    float wx = left + fw * (0.12f + col * 0.30f);
                    c.drawRect(wx, wy, wx + fw * 0.18f, wy + wh, paint);
                }
            }
            paint.setColor(0x55FFFFFF);
            for (int r = 0; r < rows; r++) {
                float wy = topN + fh * (0.12f + r * (0.70f / rows));
                float wh = fh * (0.42f / rows);
                for (int col = 0; col < 3; col++) {
                    float wx = left + fw * (0.12f + col * 0.30f);
                    c.drawRect(wx, wy, wx + fw * 0.18f, wy + wh * 0.35f, paint);
                }
            }
            paint.setColor(0xFF7A3B1E);
            c.drawRect(left + fw * 0.42f, botN - fh * 0.16f, left + fw * 0.58f, botN, paint);
        }
    }

    private void drawTree(Canvas c, float z, float sgn, int h) {
        if (z <= 0.6f || z >= GameConfig.MAX_DEPTH) {
            return;
        }
        float p = persp(z);
        float x = sgn * (3.35f + (h % 3) * 0.12f);
        float cx = xAt(x, p);
        float base = yGround(p);
        float unit = pxPerUnit * p;

        float size = 0.9f + (h % 5) * 0.12f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        rect.set(cx - unit * 1.1f * size, base - unit * 0.12f, cx + unit * 1.1f * size, base + unit * 0.14f);
        c.drawOval(rect, paint);

        paint.setColor(0xFF6D4C2F);
        c.drawRect(cx - unit * 0.17f, base - unit * 1.7f * size, cx + unit * 0.17f, base, paint);

        int green = TREE_COLORS[(h >> 4) % TREE_COLORS.length];
        float cy = base - unit * 2.5f * size;
        paint.setColor(darken(green, 0.8f));
        c.drawCircle(cx - unit * 0.65f * size, cy + unit * 0.35f * size, unit * 1.0f * size, paint);
        c.drawCircle(cx + unit * 0.65f * size, cy + unit * 0.35f * size, unit * 1.0f * size, paint);
        paint.setColor(green);
        c.drawCircle(cx, cy, unit * 1.3f * size, paint);
        paint.setColor(lighten(green, 0.25f));
        c.drawCircle(cx - unit * 0.35f * size, cy - unit * 0.40f * size, unit * 0.7f * size, paint);
    }

    // ------------------------------------------------------------------
    // Rendu : obstacles et pieces
    // ------------------------------------------------------------------

    private void drawObstacle(Canvas c, Obstacle o) {
        float zNear = o.depth - 1.1f;
        float zFar = o.depth + 1.1f;
        if (zFar < 0.6f) {
            return;
        }
        if (zNear < 0.6f) {
            zNear = 0.6f;
        }

        float pn = persp(zNear);
        float pf = persp(zFar);
        float worldX = (o.lane - 1) * GameConfig.LANE_WIDTH;
        float halfW = o.width * 0.5f;

        float fL = xAt(worldX - halfW, pn);
        float fR = xAt(worldX + halfW, pn);
        float bL = xAt(worldX - halfW, pf);
        float bR = xAt(worldX + halfW, pf);

        float fB = yGround(pn);
        float bB = yGround(pf);

        paint.setStyle(Paint.Style.FILL);

        // ombre portee
        paint.setColor(0x33000000);
        rect.set(fL - (fR - fL) * 0.1f, fB - (fB - bB) * 0.1f, fR + (fR - fL) * 0.1f, fB + (fR - fL) * 0.08f);
        c.drawOval(rect, paint);

        if (o.type == Obstacle.TYPE_BARRIER) {
            float fT = yAt(o.height, pn);
            float bT = yAt(o.height, pf);

            paint.setColor(0xFFB96A00);
            if (worldX <= 0f) {
                quad(c, fR, fT, bR, bT, bR, bB, fR, fB);
            } else {
                quad(c, fL, fT, bL, bT, bL, bB, fL, fB);
            }

            paint.setColor(0xFFE8A33D);
            quad(c, fL, fT, fR, fT, bR, bT, bL, bT);

            paint.setColor(0xFFF59F00);
            rect.set(fL, fT, fR, fB);
            c.drawRect(rect, paint);

            paint.setColor(0xFF2B2B2B);
            float bandH = (fB - fT) * 0.16f;
            for (int i = 1; i <= 3; i++) {
                float yy = fT + (fB - fT) * (i * 0.24f);
                c.drawRect(fL + (fR - fL) * 0.06f, yy,
                        fR - (fR - fL) * 0.06f, yy + bandH, paint);
            }
            paint.setColor(0x33000000);
            c.drawRect(fL, fB - (fB - fT) * 0.10f, fR, fB, paint);
            return;
        }

        if (o.type == Obstacle.TYPE_BAR) {
            float postTop = yAt(o.height, pn);
            float beamBottomNear = yAt(0.92f, pn);
            float beamTopFar = yAt(o.height, pf);
            float beamBottomFar = yAt(0.92f, pf);

            paint.setColor(0xFF7A8899);
            float postW = Math.max(3f, (fR - fL) * 0.10f);
            c.drawRect(fL, postTop, fL + postW, fB, paint);
            c.drawRect(fR - postW, postTop, fR, fB, paint);

            paint.setColor(0xFF9B2226);
            quad(c, fL, postTop, fR, postTop, bR, beamTopFar, bL, beamTopFar);

            paint.setColor(0xFFD62828);
            rect.set(fL, postTop, fR, beamBottomNear);
            c.drawRect(rect, paint);

            paint.setColor(0xFFFFD166);
            float stripeH = (beamBottomNear - postTop) * 0.30f;
            c.drawRect(fL + (fR - fL) * 0.05f, postTop + stripeH,
                    fR - (fR - fL) * 0.05f, postTop + stripeH * 2f, paint);

            paint.setColor(0x44000000);
            c.drawRect(fL, beamBottomNear - stripeH * 0.35f, fR, beamBottomNear, paint);

            paint.setColor(0x22FFFFFF);
            quad(c, fL, beamBottomNear, fR, beamBottomNear,
                    bR, beamBottomFar, bL, beamBottomFar);
            return;
        }

        float fT = yAt(o.height, pn);
        float bT = yAt(o.height, pf);

        paint.setColor(0xFF123B6D);
        if (worldX <= 0f) {
            quad(c, fR, fT, bR, bT, bR, bB, fR, fB);
        } else {
            quad(c, fL, fT, bL, bT, bL, bB, fL, fB);
        }

        paint.setColor(0xFF2C6DB5);
        quad(c, fL, fT, fR, fT, bR, bT, bL, bT);

        paint.setColor(0xFF1F5C99);
        rect.set(fL, fT, fR, fB);
        c.drawRect(rect, paint);

        paint.setColor(0xFFBFD7EA);
        float winTop = fT + (fB - fT) * 0.12f;
        float winH = (fB - fT) * 0.22f;
        c.drawRect(fL + (fR - fL) * 0.12f, winTop, fR - (fR - fL) * 0.12f, winTop + winH, paint);

        paint.setColor(0xFFFFC107);
        float stripeY = fT + (fB - fT) * 0.55f;
        c.drawRect(fL, stripeY, fR, stripeY + (fB - fT) * 0.07f, paint);

        paint.setColor(0xFF0B2545);
        c.drawRect(fL, fB - (fB - fT) * 0.20f, fR, fB, paint);

        paint.setColor(0xFFFFE08A);
        float gx = fL + (fR - fL) * 0.22f;
        float gy = fT + (fB - fT) * 0.80f;
        c.drawCircle(gx, gy, Math.max(2f, (fR - fL) * 0.06f), paint);
        c.drawCircle(fR - (fR - fL) * 0.22f, gy, Math.max(2f, (fR - fL) * 0.06f), paint);
    }

    private void drawCoin(Canvas c, Coin co) {
        if (co.depth < 0.5f) {
            return;
        }
        float p = persp(co.depth);
        float worldX = (co.lane - 1) * GameConfig.LANE_WIDTH;
        float cx = xAt(worldX, p);
        float cy = yAt(0.75f, p);
        float r = 0.34f * pxPerUnit * p;

        float squash = Math.abs((float) Math.cos(co.spin));
        float rx = Math.max(r * 0.15f, r * (0.30f + 0.70f * squash));
        float ry = r;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33FFD166);
        c.drawCircle(cx, cy, ry * 1.5f, paint);

        paint.setColor(0xFFB8860B);
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(rect, paint);

        paint.setColor(0xFFFFC107);
        rect.set(cx - rx * 0.84f, cy - ry * 0.86f, cx + rx * 0.84f, cy + ry * 0.86f);
        c.drawOval(rect, paint);

        if (rx > 7f) {
            paint.setColor(0xFFFFF3B0);
            float s = ry * 0.42f;
            path.reset();
            for (int i = 0; i < 10; i++) {
                double ang = -Math.PI / 2 + i * Math.PI / 5;
                float rad = (i % 2 == 0) ? s : s * 0.45f;
                float px = cx + (float) Math.cos(ang) * rad * (rx / ry);
                float py = cy + (float) Math.sin(ang) * rad;
                if (i == 0) {
                    path.moveTo(px, py);
                } else {
                    path.lineTo(px, py);
                }
            }
            path.close();
            c.drawPath(path, paint);
        }
    }

    // ------------------------------------------------------------------
    // Rendu : le heros (de dos en course, de face dans les menus)
    // ------------------------------------------------------------------

    private void drawPlayer(Canvas c) {
        float pp = persp(GameConfig.PLAYER_DEPTH);
        float cx = centerX + player.laneOffset() * pp * pxPerUnit * 0.92f;
        float groundY = yGround(pp);
        float feet = groundY - player.jumpHeight * pxPerUnit * pp * 0.90f;

        float heroH = height * 0.175f;
        float heroW = heroH * 0.46f;

        // ombre au sol (reste au sol quand le heros saute)
        float shadowScale = 1f / (1f + player.jumpHeight * 0.25f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x44000000);
        rect.set(cx - heroW * 0.55f * shadowScale, groundY - height * 0.004f,
                cx + heroW * 0.55f * shadowScale, groundY + height * 0.014f);
        c.drawOval(rect, paint);

        if (player.isSliding()) {
            heroH *= 0.55f;
            feet -= height * 0.006f;
        }

        // le heros roule sur un velo (vu de dos) : roue et garde-boue derriere lui, guidon devant
        float heroFeet = feet - heroH * 0.20f;
        drawBikeBack(c, cx, feet, heroH, player.runPhase, false);
        drawHeroBack(c, save.selected, cx, heroFeet, heroH, player.runPhase, true);
        drawBikeBack(c, cx, feet, heroH, player.runPhase, true);
    }

    /** Velo rose vu de dos. front=false : roue arriere ; front=true : guidon par-dessus le heros. */
    private void drawBikeBack(Canvas c, float cx, float base, float heroH, float phase, boolean front) {
        float heroW = heroH * 0.46f;
        float wheelH = heroH * 0.34f;
        float wheelW = heroH * 0.045f;
        int pink = 0xFFFF4FA3;
        int pinkDark = 0xFFD42F7F;

        paint.setStyle(Paint.Style.FILL);

        if (!front) {
            // pneu
            paint.setColor(0xFF1B1B1B);
            rect.set(cx - wheelW, base - wheelH, cx + wheelW, base);
            c.drawRoundRect(rect, wheelW, wheelW, paint);

            // bande de roulement qui defile
            paint.setColor(0xFF4A4A4A);
            for (int i = 0; i < 4; i++) {
                float k = ((phase * 0.12f) + i * 0.25f) % 1f;
                float y = base - wheelH + k * wheelH;
                c.drawRect(cx - wheelW * 0.85f, y, cx + wheelW * 0.85f, y + wheelH * 0.06f, paint);
            }

            // garde-boue
            paint.setColor(pink);
            rect.set(cx - wheelW * 1.5f, base - wheelH * 1.02f, cx + wheelW * 1.5f, base - wheelH * 0.80f);
            c.drawRoundRect(rect, wheelW, wheelW, paint);

            // tige de selle et cadre
            paint.setColor(pinkDark);
            c.drawRect(cx - heroW * 0.045f, base - wheelH * 1.05f - heroH * 0.04f,
                    cx + heroW * 0.045f, base - wheelH * 0.9f, paint);
            return;
        }

        // guidon, a hauteur des mains du heros
        float heroFeet = base - heroH * 0.20f;
        float handY = heroFeet - heroH * 0.72f + heroH * 0.30f;
        paint.setColor(pink);
        c.drawRect(cx - heroW * 0.58f, handY - heroH * 0.010f, cx + heroW * 0.58f, handY + heroH * 0.014f, paint);
        paint.setColor(0xFFFFD54F);
        c.drawCircle(cx - heroW * 0.58f, handY, heroH * 0.020f, paint);
        c.drawCircle(cx + heroW * 0.58f, handY, heroH * 0.020f, paint);
    }

    private void drawHeroBack(Canvas c, int idx, float cx, float feet, float heroH, float runPhase,
                              boolean riding) {
        int[] col = CharacterDef.COLORS[idx];
        float heroW = heroH * 0.46f;

        float swing = (float) Math.sin(runPhase);
        float swing2 = (float) Math.sin(runPhase + 3.14f);

        paint.setStyle(Paint.Style.FILL);

        float bodyTop = feet - heroH * 0.72f;
        float bodyBottom = feet - heroH * 0.34f;

        // jambes
        paint.setColor(darken(col[0], 0.8f));
        float legW = heroW * 0.20f;
        float kneeY = feet - heroH * 0.22f;
        c.drawRect(cx - heroW * 0.22f + swing * heroW * 0.12f, bodyBottom,
                cx - heroW * 0.22f + legW + swing * heroW * 0.12f, kneeY, paint);
        c.drawRect(cx + heroW * 0.02f - swing2 * heroW * 0.12f, bodyBottom,
                cx + heroW * 0.02f + legW - swing2 * heroW * 0.12f, kneeY, paint);

        // bottes
        paint.setColor(col[5]);
        c.drawRect(cx - heroW * 0.24f + swing * heroW * 0.12f, kneeY,
                cx - heroW * 0.04f + swing * heroW * 0.12f, feet, paint);
        c.drawRect(cx + heroW * 0.00f - swing2 * heroW * 0.12f, kneeY,
                cx + heroW * 0.20f - swing2 * heroW * 0.12f, feet, paint);

        // torse
        paint.setColor(col[0]);
        rect.set(cx - heroW * 0.30f, bodyTop, cx + heroW * 0.30f, bodyBottom);
        c.drawRect(rect, paint);

        // ceinture
        paint.setColor(col[4]);
        c.drawRect(cx - heroW * 0.30f, bodyBottom - heroH * 0.045f,
                cx + heroW * 0.30f, bodyBottom, paint);

        // bras + poings
        paint.setColor(col[0]);
        float armW = heroW * 0.16f;
        float armSwing = riding ? 0f : swing;
        float armEndL = bodyTop + heroH * 0.30f - armSwing * heroH * 0.05f;
        float armEndR = bodyTop + heroH * 0.30f + armSwing * heroH * 0.05f;
        c.drawRect(cx - heroW * 0.42f, bodyTop + heroH * 0.04f,
                cx - heroW * 0.42f + armW, armEndL, paint);
        c.drawRect(cx + heroW * 0.26f, bodyTop + heroH * 0.04f,
                cx + heroW * 0.26f + armW, armEndR, paint);
        paint.setColor(col[2]);
        c.drawCircle(cx - heroW * 0.42f + armW * 0.5f, armEndL, armW * 0.62f, paint);
        c.drawCircle(cx + heroW * 0.26f + armW * 0.5f, armEndR, armW * 0.62f, paint);

        // cape (par-dessus le dos), seulement pour les heros qui en ont une
        if (CharacterDef.HAS_CAPE[idx]) {
            float flap = (float) Math.sin(runPhase * 1.7f) * heroW * 0.10f;
            float capeBottom = bodyBottom + heroH * 0.10f;
            paint.setColor(col[1]);
            path.reset();
            path.moveTo(cx - heroW * 0.34f, bodyTop + heroH * 0.02f);
            path.lineTo(cx + heroW * 0.34f, bodyTop + heroH * 0.02f);
            path.lineTo(cx + heroW * 0.64f + flap, capeBottom);
            path.lineTo(cx + heroW * 0.25f + flap * 0.5f, capeBottom + heroH * 0.05f);
            path.lineTo(cx - heroW * 0.25f - flap * 0.5f, capeBottom + heroH * 0.03f);
            path.lineTo(cx - heroW * 0.64f - flap, capeBottom);
            path.close();
            c.drawPath(path, paint);

            paint.setColor(darken(col[1], 0.75f));
            rect.set(cx - heroW * 0.60f - flap, capeBottom - heroH * 0.025f, cx + heroW * 0.60f + flap, capeBottom + heroH * 0.02f);
            c.drawRect(rect, paint);

            // embleme sur la cape
            drawStar(c, cx, bodyTop + (capeBottom - bodyTop) * 0.45f, heroW * 0.17f, col[4]);
        }

        // cou, oreilles, tete (de dos : cheveux)
        float headR = heroW * 0.26f;
        float headCy = bodyTop - headR * 0.85f;
        paint.setColor(col[2]);
        c.drawRect(cx - headR * 0.35f, bodyTop - headR * 0.4f, cx + headR * 0.35f, bodyTop + heroH * 0.02f, paint);
        c.drawCircle(cx - headR * 0.98f, headCy + headR * 0.1f, headR * 0.20f, paint);
        c.drawCircle(cx + headR * 0.98f, headCy + headR * 0.1f, headR * 0.20f, paint);
        paint.setColor(col[3]);
        c.drawCircle(cx, headCy, headR, paint);
        paint.setColor(darken(col[3], 0.7f));
        rect.set(cx - headR * 0.8f, headCy + headR * 0.25f, cx + headR * 0.8f, headCy + headR * 0.95f);
        c.drawOval(rect, paint);
    }

    /** Dessine l'image du heros (PNG), centree sur cx, les pieds en bas. */
    private void drawHeroImage(Canvas c, Bitmap bmp, float cx, float feet, float H, float t, boolean locked) {
        float bob = (float) Math.sin(t * 3f) * H * 0.008f;

        // ombre
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x44000000);
        rect.set(cx - H * 0.26f, feet - H * 0.02f, cx + H * 0.26f, feet + H * 0.025f);
        c.drawOval(rect, paint);

        float h = H;
        float w = h * bmp.getWidth() / (float) bmp.getHeight();
        float maxW = width * 0.88f;
        if (w > maxW) {
            h = h * maxW / w;
            w = maxW;
        }
        rect.set(cx - w / 2f, feet - h + bob, cx + w / 2f, feet + bob);

        imgPaint.setColorFilter(locked ? lockFilter : null);
        c.drawBitmap(bmp, null, rect, imgPaint);
        imgPaint.setColorFilter(null);

        if (locked) {
            // cadenas au centre du heros
            float lw = h * 0.085f;
            float lx = cx;
            float ly = feet - h * 0.50f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFC107);
            rect.set(lx - lw, ly, lx + lw, ly + lw * 1.5f);
            c.drawRoundRect(rect, lw * 0.25f, lw * 0.25f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(lw * 0.30f);
            rect.set(lx - lw * 0.6f, ly - lw * 0.9f, lx + lw * 0.6f, ly + lw * 0.5f);
            c.drawArc(rect, 180f, 180f, false, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF5A4000);
            c.drawCircle(lx, ly + lw * 0.75f, lw * 0.18f, paint);
        }
    }

    private void drawHeroFront(Canvas c, int idx, float cx, float feet, float H, float t, boolean locked) {
        Bitmap img = heroBmp[idx];
        if (img != null) {
            drawHeroImage(c, img, cx, feet, H, t, locked);
            return;
        }
        lockTint = locked;
        int[] col = CharacterDef.COLORS[idx];
        float bob = (float) Math.sin(t * 3f) * H * 0.008f;

        paint.setStyle(Paint.Style.FILL);

        // ombre
        paint.setColor(0x44000000);
        rect.set(cx - H * 0.30f, feet - H * 0.02f, cx + H * 0.30f, feet + H * 0.025f);
        c.drawOval(rect, paint);

        float tw = H * 0.20f;
        float bodyBottom = feet - H * 0.33f;
        float bodyTop = feet - H * 0.63f + bob;

        // cape derriere
        paint.setColor(hc(col[1]));
        path.reset();
        path.moveTo(cx - tw * 1.15f, bodyTop + H * 0.02f);
        path.lineTo(cx + tw * 1.15f, bodyTop + H * 0.02f);
        path.lineTo(cx + tw * 1.75f, feet - H * 0.07f);
        path.lineTo(cx - tw * 1.75f, feet - H * 0.07f);
        path.close();
        c.drawPath(path, paint);

        // jambes
        paint.setColor(hc(darken(col[0], 0.8f)));
        c.drawRect(cx - tw * 0.85f, bodyBottom - H * 0.01f, cx - tw * 0.05f, feet - H * 0.08f, paint);
        c.drawRect(cx + tw * 0.05f, bodyBottom - H * 0.01f, cx + tw * 0.85f, feet - H * 0.08f, paint);

        // bottes
        paint.setColor(hc(col[5]));
        rect.set(cx - tw * 0.95f, feet - H * 0.12f, cx - tw * 0.0f, feet);
        c.drawRoundRect(rect, H * 0.02f, H * 0.02f, paint);
        rect.set(cx + tw * 0.0f, feet - H * 0.12f, cx + tw * 0.95f, feet);
        c.drawRoundRect(rect, H * 0.02f, H * 0.02f, paint);

        // torse
        paint.setColor(hc(col[0]));
        rect.set(cx - tw, bodyTop, cx + tw, bodyBottom);
        c.drawRoundRect(rect, H * 0.03f, H * 0.03f, paint);

        // ceinture
        paint.setColor(hc(col[4]));
        c.drawRect(cx - tw, bodyBottom - H * 0.04f, cx + tw, bodyBottom, paint);
        c.drawRect(cx - H * 0.03f, bodyBottom - H * 0.05f, cx + H * 0.03f, bodyBottom + H * 0.005f,
                paint);

        // embleme
        drawStar(c, cx, bodyTop + H * 0.10f, H * 0.065f, hc(col[4]));

        // bras + mains
        float armW = H * 0.075f;
        paint.setColor(hc(col[0]));
        c.drawRect(cx - tw - armW, bodyTop + H * 0.01f, cx - tw, bodyTop + H * 0.27f, paint);
        c.drawRect(cx + tw, bodyTop + H * 0.01f, cx + tw + armW, bodyTop + H * 0.27f, paint);
        paint.setColor(hc(col[2]));
        c.drawCircle(cx - tw - armW * 0.5f, bodyTop + H * 0.285f, armW * 0.62f, paint);
        c.drawCircle(cx + tw + armW * 0.5f, bodyTop + H * 0.285f, armW * 0.62f, paint);

        // cou et tete
        float R = H * 0.165f;
        float headCy = bodyTop - R * 0.80f;
        paint.setColor(hc(col[2]));
        c.drawRect(cx - R * 0.3f, bodyTop - R * 0.4f, cx + R * 0.3f, bodyTop + H * 0.01f, paint);
        c.drawCircle(cx, headCy, R, paint);
        c.drawCircle(cx - R * 0.98f, headCy + R * 0.05f, R * 0.18f, paint);
        c.drawCircle(cx + R * 0.98f, headCy + R * 0.05f, R * 0.18f, paint);

        // cheveux
        paint.setColor(hc(col[3]));
        rect.set(cx - R * 1.05f, headCy - R * 1.12f, cx + R * 1.05f, headCy - R * 0.05f);
        c.drawOval(rect, paint);
        int style = idx % 3;
        if (style == 1) {
            for (int i = -1; i <= 1; i++) {
                path.reset();
                path.moveTo(cx + i * R * 0.5f - R * 0.28f, headCy - R * 0.95f);
                path.lineTo(cx + i * R * 0.5f + R * 0.28f, headCy - R * 0.95f);
                path.lineTo(cx + i * R * 0.5f, headCy - R * 1.45f);
                path.close();
                c.drawPath(path, paint);
            }
        } else if (style == 2) {
            c.drawRect(cx - R * 1.05f, headCy - R * 0.6f, cx - R * 0.75f, headCy + R * 0.7f, paint);
            c.drawRect(cx + R * 0.75f, headCy - R * 0.6f, cx + R * 1.05f, headCy + R * 0.7f, paint);
        }
        // face redessinee par-dessus la frange
        paint.setColor(hc(col[2]));
        rect.set(cx - R * 0.86f, headCy - R * 0.50f, cx + R * 0.86f, headCy + R * 0.98f);
        c.drawRoundRect(rect, R * 0.5f, R * 0.5f, paint);

        // masque
        if (CharacterDef.hasMask(idx)) {
            paint.setColor(hc(col[4]));
            rect.set(cx - R * 0.95f, headCy - R * 0.40f, cx + R * 0.95f, headCy + R * 0.22f);
            c.drawRoundRect(rect, R * 0.3f, R * 0.3f, paint);
        }

        // yeux
        float ex = R * 0.40f;
        float ey = headCy - R * 0.08f;
        paint.setColor(lockTint ? hc(0xFFFFFFFF) : 0xFFFFFFFF);
        rect.set(cx - ex - R * 0.22f, ey - R * 0.17f, cx - ex + R * 0.22f, ey + R * 0.17f);
        c.drawOval(rect, paint);
        rect.set(cx + ex - R * 0.22f, ey - R * 0.17f, cx + ex + R * 0.22f, ey + R * 0.17f);
        c.drawOval(rect, paint);
        paint.setColor(0xFF1B1B1B);
        c.drawCircle(cx - ex, ey, R * 0.10f, paint);
        c.drawCircle(cx + ex, ey, R * 0.10f, paint);

        // sourire
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, R * 0.07f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(lockTint ? 0xFF222222 : 0xFF8A3B2E);
        rect.set(cx - R * 0.34f, headCy + R * 0.18f, cx + R * 0.34f, headCy + R * 0.66f);
        c.drawArc(rect, 20f, 140f, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);

        // cadenas si le heros est verrouille
        if (locked) {
            float lw = H * 0.10f;
            float ly = bodyTop + H * 0.12f;
            paint.setColor(0xFFFFC107);
            rect.set(cx - lw, ly, cx + lw, ly + lw * 1.5f);
            c.drawRoundRect(rect, lw * 0.25f, lw * 0.25f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(lw * 0.30f);
            rect.set(cx - lw * 0.6f, ly - lw * 0.9f, cx + lw * 0.6f, ly + lw * 0.5f);
            c.drawArc(rect, 180f, 180f, false, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF5A4000);
            c.drawCircle(cx, ly + lw * 0.75f, lw * 0.18f, paint);
        }

        lockTint = false;
    }

    // ------------------------------------------------------------------
    // Rendu : ecrans
    // ------------------------------------------------------------------

    private void drawLoading(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(loadShader);
        c.drawRect(0f, 0f, width, height, paint);
        paint.setShader(null);

        // grandes spirales claires en arriere-plan
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.07f);
        float pulse = (float) Math.sin(animT * 1.2f) * width * 0.015f;
        for (int i = 0; i < 6; i++) {
            paint.setColor(i % 2 == 0 ? 0x18FFFFFF : 0x0CFFFFFF);
            c.drawCircle(width * 0.5f, height * 0.42f, width * (0.18f + i * 0.17f) + pulse, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        text.setTextAlign(Paint.Align.CENTER);
        float titleSize = Math.min(height * 0.060f, width * 0.125f);
        outlined(c, "HERO RUN", width / 2f, height * 0.13f, titleSize, 0xFFFFB300, 0xFF0F3A66);
        outlined(c, "3D", width / 2f, height * 0.13f + titleSize * 1.05f, titleSize * 1.15f, 0xFFFFB300, 0xFF0F3A66);

        float H = Math.min(height * 0.36f, width * 0.85f);
        drawHeroFront(c, save.selected, width / 2f, height * 0.66f + (float) Math.sin(animT * 5f) * 4f, H, animT, false);

        // barre de progression
        float bw = width * 0.70f;
        float bh = height * 0.028f;
        float bx = (width - bw) / 2f;
        float by = height * 0.80f;
        paint.setColor(0x55000000);
        rect.set(bx, by, bx + bw, by + bh);
        c.drawRoundRect(rect, bh, bh, paint);
        paint.setColor(0xFFFFB300);
        float fill = Math.max(bh, bw * loadShown);
        rect.set(bx, by, bx + fill, by + bh);
        c.drawRoundRect(rect, bh, bh, paint);
        paint.setColor(0x55FFFFFF);
        rect.set(bx + bh * 0.2f, by + bh * 0.12f, bx + fill - bh * 0.2f, by + bh * 0.45f);
        c.drawRoundRect(rect, bh, bh, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, bh * 0.12f));
        paint.setColor(0xFFFFFFFF);
        rect.set(bx, by, bx + bw, by + bh);
        c.drawRoundRect(rect, bh, bh, paint);
        paint.setStyle(Paint.Style.FILL);

        int pct = (int) (loadShown * 100f);
        outlined(c, "CHARGEMENT " + pct + "%", width / 2f, by + bh * 2.7f, height * 0.024f, 0xFFFFFFFF, 0xFF0F3A66);

        text.setColor(0xCCFFFFFF);
        outlined(c, "Astuce : glisse vers le bas pour te baisser", width / 2f, height * 0.93f,
                fitSize("Astuce : glisse vers le bas pour te baisser", width * 0.9f, height * 0.017f),
                0xFFE8F6FF, 0xFF0F3A66);
    }

    private void drawMenu(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x22000A20);
        c.drawRect(0f, 0f, width, height, paint);

        // pieces et reglages
        rect.set(width * 0.04f, height * 0.035f, width * 0.04f + width * 0.38f, height * 0.035f + btnGear.height());
        coinPill(c, rect, save.coins);
        drawGear(c, btnGear);

        // titre
        text.setTextAlign(Paint.Align.CENTER);
        float titleSize = Math.min(height * 0.060f, width * 0.125f);
        outlined(c, "HERO RUN", width / 2f, height * 0.19f, titleSize, 0xFFFFB300, 0xFF0F3A66);
        outlined(c, "3D", width / 2f, height * 0.19f + titleSize * 1.0f, titleSize * 1.1f, 0xFFFFFFFF, 0xFF0F3A66);

        // heros
        float H = Math.min(height * 0.30f, width * 0.80f);
        drawHeroFront(c, save.selected, width / 2f, height * 0.655f, H, animT, false);

        outlined(c, CharacterDef.NAME[save.selected], width / 2f, height * 0.700f, height * 0.030f,
                0xFFFFFFFF, 0xFF0F3A66);

        // TAP TO PLAY qui pulse
        float pulse = 1f + (float) Math.sin(animT * 4f) * 0.06f;
        float sz = height * 0.036f * pulse;
        outlined(c, "TOUCHE POUR JOUER", width / 2f, height * 0.775f, fitSize("TOUCHE POUR JOUER", width * 0.78f, sz),
                0xFFFFB300, 0xFF0F3A66);
        outlined(c, "Record : " + save.best, width / 2f, height * 0.815f, height * 0.022f, 0xFFFFFFFF, 0xFF0F3A66);

        button(c, btnMissions, "MISSIONS", 0xFF35B34A, 0xFF1E7B30, true);
        button(c, btnHeroes, "HÉROS", 0xFF35B34A, 0xFF1E7B30, true);
        button(c, btnShop, "BOUTIQUE", 0xFF35B34A, 0xFF1E7B30, true);
    }

    private void drawGear(Canvas c, RectF r) {
        float cx = r.centerX();
        float cy = r.centerY();
        float rad = r.width() * 0.5f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF3A3A8C);
        c.drawCircle(cx, cy, rad, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(rad * 0.10f);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, rad * 0.95f, paint);
        paint.setStrokeWidth(rad * 0.16f);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            c.drawLine(cx + (float) Math.cos(a) * rad * 0.48f, cy + (float) Math.sin(a) * rad * 0.48f,
                    cx + (float) Math.cos(a) * rad * 0.68f, cy + (float) Math.sin(a) * rad * 0.68f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, rad * 0.52f, paint);
        paint.setColor(0xFF3A3A8C);
        c.drawCircle(cx, cy, rad * 0.24f, paint);
    }

    private void screenTitle(Canvas c, String title) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xAA0A1B3C);
        c.drawRect(0f, 0f, width, height, paint);
        text.setTextAlign(Paint.Align.CENTER);
        outlined(c, title, width / 2f, height * 0.10f, Math.min(height * 0.045f, width * 0.10f),
                0xFFFFB300, 0xFF0F3A66);
    }

    private void arrowButton(Canvas c, RectF r, boolean left) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFB300);
        c.drawRoundRect(r, r.width() * 0.3f, r.width() * 0.3f, paint);
        paint.setColor(0xFF0F3A66);
        float cx = r.centerX();
        float cy = r.centerY();
        float s = r.width() * 0.22f;
        path.reset();
        if (left) {
            path.moveTo(cx - s, cy);
            path.lineTo(cx + s, cy - s * 1.3f);
            path.lineTo(cx + s, cy + s * 1.3f);
        } else {
            path.moveTo(cx + s, cy);
            path.lineTo(cx - s, cy - s * 1.3f);
            path.lineTo(cx - s, cy + s * 1.3f);
        }
        path.close();
        c.drawPath(path, paint);
    }

    private void drawHeroes(Canvas c) {
        screenTitle(c, "HÉROS");

        rect.set(width * 0.31f, height * 0.125f, width * 0.69f, height * 0.125f + height * 0.045f);
        coinPill(c, rect, save.coins);

        boolean owned = save.isOwned(viewHero);

        float H = Math.min(height * 0.31f, width * 0.78f);
        drawHeroFront(c, viewHero, width / 2f, height * 0.60f, H, animT, !owned);

        arrowButton(c, btnLeft, true);
        arrowButton(c, btnRight, false);

        text.setTextAlign(Paint.Align.CENTER);
        outlined(c, CharacterDef.NAME[viewHero], width / 2f, height * 0.655f, height * 0.036f,
                0xFFFFFFFF, 0xFF0F3A66);

        String status;
        int statusColor;
        if (save.selected == viewHero) {
            status = "Héros actuel";
            statusColor = 0xFF7CFF8A;
        } else if (owned) {
            status = "Débloqué";
            statusColor = 0xFF7CFF8A;
        } else {
            status = "Prix : " + CharacterDef.PRICE[viewHero] + " pièces";
            statusColor = 0xFFFFD166;
        }
        outlined(c, status, width / 2f, height * 0.695f, height * 0.024f, statusColor, 0xFF0F3A66);

        // points de position
        float dotR = width * 0.012f;
        float dotGap = width * 0.045f;
        float dx0 = width / 2f - dotGap * (CharacterDef.COUNT - 1) / 2f;
        for (int i = 0; i < CharacterDef.COUNT; i++) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i == viewHero ? 0xFFFFB300 : 0x66FFFFFF);
            c.drawCircle(dx0 + i * dotGap, height * 0.725f, i == viewHero ? dotR * 1.4f : dotR, paint);
        }

        if (save.selected == viewHero) {
            button(c, btnAction, "CHOISI ✓", 0xFF35B34A, 0xFF1E7B30, false);
        } else if (owned) {
            button(c, btnAction, "CHOISIR", 0xFF35B34A, 0xFF1E7B30, true);
        } else if (save.coins >= CharacterDef.PRICE[viewHero]) {
            button(c, btnAction, "ACHETER  " + CharacterDef.PRICE[viewHero], 0xFFFFB300, 0xFFE08A00, true);
        } else {
            button(c, btnAction, "PAS ASSEZ  " + CharacterDef.PRICE[viewHero], 0xFFE53935, 0xFFAD1F1C, true);
        }

        button(c, btnBack, "RETOUR", 0xFF3FA0FF, 0xFF1F6FCC, true);
    }

    private void drawMissions(Canvas c) {
        screenTitle(c, "MISSIONS");

        rect.set(width * 0.31f, height * 0.125f, width * 0.69f, height * 0.125f + height * 0.04f);
        coinPill(c, rect, save.coins);

        int[] active = save.activeMissions();
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            int m = active[i];
            if (m < 0) {
                continue;
            }
            any = true;
            RectF card = cards[i];
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF12305E);
            c.drawRoundRect(card, width * 0.04f, width * 0.04f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, width * 0.005f));
            paint.setColor(0xFFFFB300);
            c.drawRoundRect(card, width * 0.04f, width * 0.04f, paint);
            paint.setStyle(Paint.Style.FILL);

            text.setTextAlign(Paint.Align.LEFT);
            String label = Missions.text(m);
            float size = fitSize(label, card.width() * 0.90f, height * 0.026f);
            outlined(c, label, card.left + card.width() * 0.05f, card.top + card.height() * 0.28f, size,
                    0xFFFFFFFF, 0xFF0A1B3C);

            // barre de progression
            float prog = Math.min(1f, missionRatio(m));
            float bx = card.left + card.width() * 0.05f;
            float bw = card.width() * 0.90f;
            float by = card.top + card.height() * 0.40f;
            float bh = card.height() * 0.14f;
            paint.setColor(0x55000000);
            rect.set(bx, by, bx + bw, by + bh);
            c.drawRoundRect(rect, bh, bh, paint);
            paint.setColor(prog >= 1f ? 0xFF35B34A : 0xFFFFB300);
            rect.set(bx, by, bx + Math.max(bh, bw * prog), by + bh);
            c.drawRoundRect(rect, bh, bh, paint);

            String pr = save.missionProgress[m] + " / " + Missions.TARGET[m];
            outlined(c, pr, bx, by + bh + height * 0.026f, height * 0.018f, 0xFFE8F6FF, 0xFF0A1B3C);

            // recompense
            drawCoinIcon(c, card.left + card.width() * 0.07f, card.bottom - card.height() * 0.17f, height * 0.015f);
            outlined(c, "+" + Missions.REWARD[m], card.left + card.width() * 0.11f,
                    card.bottom - card.height() * 0.17f + height * 0.007f, height * 0.022f, 0xFFFFD166, 0xFF0A1B3C);

            boolean done = save.missionProgress[m] >= Missions.TARGET[m];
            button(c, btnClaim[i], done ? "RÉCLAMER" : "EN COURS", 0xFF35B34A, 0xFF1E7B30, done);
        }

        if (!any) {
            text.setTextAlign(Paint.Align.CENTER);
            outlined(c, "Bravo ! Toutes les missions", width / 2f, height * 0.40f, height * 0.028f,
                    0xFFFFFFFF, 0xFF0A1B3C);
            outlined(c, "sont terminées.", width / 2f, height * 0.44f, height * 0.028f, 0xFFFFFFFF, 0xFF0A1B3C);
        }

        button(c, btnBack, "RETOUR", 0xFF3FA0FF, 0xFF1F6FCC, true);
    }

    private float missionRatio(int m) {
        return save.missionProgress[m] / (float) Missions.TARGET[m];
    }

    private void drawShop(Canvas c) {
        screenTitle(c, "BOUTIQUE");

        rect.set(width * 0.31f, height * 0.125f, width * 0.69f, height * 0.125f + height * 0.04f);
        coinPill(c, rect, save.coins);

        String[] names = {"Aimant à pièces", "Bouclier"};
        String[] descs = {"Attire les pièces autour de toi", "Protège d'un obstacle"};
        for (int i = 0; i < 2; i++) {
            RectF card = cards[i];
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF12305E);
            c.drawRoundRect(card, width * 0.04f, width * 0.04f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, width * 0.005f));
            paint.setColor(0xFF6B7C99);
            c.drawRoundRect(card, width * 0.04f, width * 0.04f, paint);
            paint.setStyle(Paint.Style.FILL);

            text.setTextAlign(Paint.Align.LEFT);
            outlined(c, names[i], card.left + card.width() * 0.05f, card.top + card.height() * 0.32f,
                    height * 0.028f, 0xFFFFFFFF, 0xFF0A1B3C);
            outlined(c, descs[i], card.left + card.width() * 0.05f, card.top + card.height() * 0.55f,
                    fitSize(descs[i], card.width() * 0.9f, height * 0.020f), 0xFFC5D5EA, 0xFF0A1B3C);
            outlined(c, "BIENTÔT DISPONIBLE", card.left + card.width() * 0.05f, card.bottom - card.height() * 0.15f,
                    height * 0.020f, 0xFFFFD166, 0xFF0A1B3C);
        }

        text.setTextAlign(Paint.Align.CENTER);
        outlined(c, "Dépense tes pièces pour de nouveaux héros :", width / 2f, height * 0.675f,
                fitSize("Dépense tes pièces pour de nouveaux héros :", width * 0.92f, height * 0.021f),
                0xFFE8F6FF, 0xFF0A1B3C);
        button(c, btnShopHeroes, "VOIR LES HÉROS", 0xFFFFB300, 0xFFE08A00, true);
        button(c, btnBack, "RETOUR", 0xFF3FA0FF, 0xFF1F6FCC, true);
    }

    private void drawSettings(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xAA000000);
        c.drawRect(0f, 0f, width, height, paint);
        panel(c, width * 0.12f, height * 0.25f, width * 0.88f, height * 0.75f);

        text.setTextAlign(Paint.Align.CENTER);
        outlined(c, "PARAMÈTRES", width / 2f, height * 0.305f, height * 0.034f, 0xFFFFB300, 0xFF0F3A66);

        button(c, btnSound, save.sound ? "SON : OUI" : "SON : NON", 0xFF3FA0FF, 0xFF1F6FCC, true);
        button(c, btnAbout, "À PROPOS", 0xFF35B34A, 0xFF1E7B30, true);
        button(c, btnQuit, "QUITTER", 0xFFE53935, 0xFFAD1F1C, true);
        button(c, btnClose, "FERMER", 0xFFFFB300, 0xFFE08A00, true);
    }

    private void drawAbout(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xAA000000);
        c.drawRect(0f, 0f, width, height, paint);

        float left = width * 0.07f;
        float right = width * 0.93f;
        float top = height * 0.06f;
        float bottom = height * 0.94f;
        panel(c, left, top, right, bottom);

        text.setTextAlign(Paint.Align.CENTER);
        outlined(c, "À propos du jeu", width / 2f, top + height * 0.05f,
                Math.min(height * 0.032f, width * 0.07f), 0xFFFFB300, 0xFF0F3A66);

        float size = Math.min(height * 0.0165f, width * 0.032f);
        float step = size * 1.55f;
        float y = top + height * 0.095f;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(size);

        for (int i = 0; i < ABOUT_LINES.length; i++) {
            String line = ABOUT_LINES[i];
            boolean heading = line.length() > 0 && line.equals(line.toUpperCase());
            text.setStyle(Paint.Style.FILL);
            text.setColor(heading ? 0xFFFFC107 : 0xFFEDEFF5);
            c.drawText(line, left + width * 0.05f, y, text);
            y += step;
        }

        button(c, btnAboutOk, "COMPRIS", 0xFFFFB300, 0xFFE08A00, true);
    }

    private void drawHud(Canvas c) {
        float pad = width * 0.04f;
        float g = btnPause.width();

        // bouton pause
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFAD1F1C);
        rect.set(btnPause.left, btnPause.top + g * 0.06f, btnPause.right, btnPause.bottom + g * 0.06f);
        c.drawRoundRect(rect, g * 0.22f, g * 0.22f, paint);
        paint.setColor(0xFFE53935);
        c.drawRoundRect(btnPause, g * 0.22f, g * 0.22f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, g * 0.05f));
        paint.setColor(0xFFFFFFFF);
        c.drawRoundRect(btnPause, g * 0.22f, g * 0.22f, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawRect(btnPause.left + g * 0.30f, btnPause.top + g * 0.25f, btnPause.left + g * 0.44f, btnPause.bottom - g * 0.25f, paint);
        c.drawRect(btnPause.left + g * 0.56f, btnPause.top + g * 0.25f, btnPause.left + g * 0.70f, btnPause.bottom - g * 0.25f, paint);

        // score
        float pw = width * 0.44f;
        float ph = g * 0.78f;
        float px = width - pad - pw;
        float py = height * 0.035f;
        paint.setColor(0xFFFFB300);
        path.reset();
        path.moveTo(px + ph * 0.35f, py);
        path.lineTo(px + pw, py);
        path.lineTo(px + pw, py + ph);
        path.lineTo(px, py + ph);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(0xFFFFF3C4);
        path.reset();
        path.moveTo(px + ph * 0.35f + pw * 0.30f, py + ph * 0.1f);
        path.lineTo(px + pw - ph * 0.1f, py + ph * 0.1f);
        path.lineTo(px + pw - ph * 0.1f, py + ph * 0.9f);
        path.lineTo(px + pw * 0.30f, py + ph * 0.9f);
        path.close();
        c.drawPath(path, paint);
        text.setTextAlign(Paint.Align.LEFT);
        outlined(c, "x1", px + pw * 0.08f, py + ph * 0.68f, ph * 0.50f, 0xFF7A4B00, 0xFFFFE08A);
        text.setTextAlign(Paint.Align.RIGHT);
        outlined(c, String.valueOf(score), px + pw - ph * 0.25f, py + ph * 0.70f,
                fitSize(String.valueOf(score), pw * 0.55f, ph * 0.58f), 0xFF3A2A00, 0xFFFFF3C4);

        // pieces de la partie
        float cy2 = py + ph + height * 0.012f;
        paint.setColor(0xFFFFB300);
        rect.set(px + ph * 0.2f, cy2, px + pw, cy2 + ph * 0.85f);
        c.drawRoundRect(rect, ph * 0.3f, ph * 0.3f, paint);
        drawCoinIcon(c, px + ph * 0.2f + ph * 0.42f, cy2 + ph * 0.425f, ph * 0.32f);
        text.setTextAlign(Paint.Align.RIGHT);
        outlined(c, String.valueOf(coinsRun), px + pw - ph * 0.25f, cy2 + ph * 0.62f, ph * 0.50f,
                0xFFFFFFFF, 0xFF7A4B00);

        // vitesse
        float barW = width * 0.34f;
        float barH = height * 0.010f;
        float barX = pad;
        float barY = height - pad - barH;
        paint.setColor(0x44000000);
        rect.set(barX, barY, barX + barW, barY + barH);
        c.drawRoundRect(rect, barH, barH, paint);
        paint.setColor(0xFFFFC107);
        float ratio = (speed - GameConfig.START_SPEED) / (GameConfig.MAX_SPEED - GameConfig.START_SPEED);
        ratio = Math.max(0f, Math.min(1f, ratio));
        rect.set(barX, barY, barX + barW * ratio, barY + barH);
        c.drawRoundRect(rect, barH, barH, paint);
    }

    private void drawPaused(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x88000000);
        c.drawRect(0f, 0f, width, height, paint);
        panel(c, width * 0.12f, height * 0.32f, width * 0.88f, height * 0.68f);
        text.setTextAlign(Paint.Align.CENTER);
        outlined(c, "PAUSE", width / 2f, height * 0.385f, height * 0.040f, 0xFFFFB300, 0xFF0F3A66);
        button(c, btnResume, "REPRENDRE", 0xFF35B34A, 0xFF1E7B30, true);
        button(c, btnPauseMenu, "MENU", 0xFF3FA0FF, 0xFF1F6FCC, true);
    }

    private void drawGameOver(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x88000000);
        c.drawRect(0f, 0f, width, height, paint);

        float left = width * 0.10f;
        float right = width * 0.90f;
        float top = height * 0.27f;
        float bottom = height * 0.73f;
        panel(c, left, top, right, bottom);

        text.setTextAlign(Paint.Align.CENTER);
        outlined(c, "PERDU !", width / 2f, top + height * 0.08f, height * 0.045f, 0xFFFF5A52, 0xFF3A0A0A);

        float sz = height * 0.026f;
        outlined(c, "Score : " + score, width / 2f, top + height * 0.145f, sz, 0xFFFFFFFF, 0xFF0A1B3C);
        outlined(c, "Pièces gagnées : " + coinsRun, width / 2f, top + height * 0.185f, sz, 0xFFFFD166, 0xFF0A1B3C);
        outlined(c, "Record : " + save.best, width / 2f, top + height * 0.225f, sz, 0xFFFFFFFF, 0xFF0A1B3C);

        if (newRecord) {
            outlined(c, "NOUVEAU RECORD !", width / 2f, top + height * 0.268f, height * 0.024f,
                    0xFFFFB300, 0xFF0F3A66);
        }

        button(c, btnOverReplay, "REJOUER", 0xFF35B34A, 0xFF1E7B30, true);
        button(c, btnOverMenu, "MENU", 0xFF3FA0FF, 0xFF1F6FCC, true);
    }
}