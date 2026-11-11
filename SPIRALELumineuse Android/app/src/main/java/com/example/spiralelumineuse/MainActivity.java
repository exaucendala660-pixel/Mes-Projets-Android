package com.example.spiralelumineuse;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    private SpiralView spiralView;
    private Vibrator vibrator;
    private TextView textEtat, btnDemarrer, btnStop;

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

        // Icônes de la barre du haut en blanc
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);

        // ----- Fond dégradé bleu nuit -> violet -----
        GradientDrawable fond = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0B1026"),
                        Color.parseColor("#1B1B4B"),
                        Color.parseColor("#3B1D6E")});
        root.setBackground(fond);

        textEtat = findViewById(R.id.textEtat);
        btnDemarrer = findViewById(R.id.btnDemarrer);
        btnStop = findViewById(R.id.btnStop);

        // ----- Carte en verre pour la spirale -----
        FrameLayout container = findViewById(R.id.spiralContainer);
        container.setBackground(verre(32, "#14FFFFFF", "#33FFFFFF"));
        container.setClipToOutline(true); // coins arrondis
        spiralView = new SpiralView(this);
        container.addView(spiralView);

        // ----- Boutons -----
        btnDemarrer.setBackground(degrade(32, "#3B82F6", "#8B5CF6"));
        btnStop.setBackground(verre(32, "#26EF4444", "#80EF4444"));
        effetAppui(btnDemarrer);
        effetAppui(btnStop);
        majEtat(false);

        // ----- Petite animation d'entrée -----
        findViewById(R.id.titre).setAlpha(0f);
        findViewById(R.id.titre).animate().alpha(1f).setDuration(900).start();
        container.setAlpha(0f);
        container.animate().alpha(1f).setDuration(1200).start();

        // ----- Vibreur -----
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        btnDemarrer.setOnClickListener(v -> demarrer());
        btnStop.setOnClickListener(v -> arreter());
    }

    private void demarrer() {
        spiralView.start();
        majEtat(true);
        long[] rythme = {0, 500, 100};
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(rythme, 0));
        } else {
            vibrator.vibrate(rythme, 0);
        }
    }

    private void arreter() {
        spiralView.stop();
        majEtat(false);
        vibrator.cancel();
    }

    @Override
    protected void onPause() {
        super.onPause();
        arreter();
    }

    // ---------- Outils de design ----------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // Forme en verre : fond transparent + contour fin
    private GradientDrawable verre(int rayonDp, String fond, String contour) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(rayonDp));
        g.setColor(Color.parseColor(fond));
        g.setStroke(dp(1), Color.parseColor(contour));
        return g;
    }

    // Forme avec dégradé de gauche à droite
    private GradientDrawable degrade(int rayonDp, String c1, String c2) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor(c1), Color.parseColor(c2)});
        g.setCornerRadius(dp(rayonDp));
        return g;
    }

    // Le bouton rétrécit un peu quand on appuie
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

    // Change l'étiquette d'état et l'aspect des boutons
    private void majEtat(boolean actif) {
        if (actif) {
            textEtat.setText("● EN COURS");
            textEtat.setTextColor(Color.parseColor("#C4B5FD"));
            textEtat.setBackground(verre(30, "#338B5CF6", "#998B5CF6"));
            btnDemarrer.setAlpha(0.5f);
            btnStop.setAlpha(1f);
        } else {
            textEtat.setText("● EN ATTENTE");
            textEtat.setTextColor(Color.parseColor("#94A3B8"));
            textEtat.setBackground(verre(30, "#14FFFFFF", "#26FFFFFF"));
            btnDemarrer.setAlpha(1f);
            btnStop.setAlpha(0.5f);
        }
    }

    // ---------- La spirale animée avec effets lumineux ----------
    static class SpiralView extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] hsv = new float[3];
        private boolean running = false;
        private float angle = 0;
        private float hue = 220;

        SpiralView(Context context) {
            super(context);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            glow.setMaskFilter(new BlurMaskFilter(18, BlurMaskFilter.Blur.NORMAL));
        }

        void start() {
            running = true;
            invalidate();
        }

        void stop() {
            running = false;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float maxR = Math.min(cx, cy) * 0.92f;

            // Flash lumineux doux du fond (seulement quand ça tourne)
            if (running) {
                hsv[0] = hue;
                hsv[1] = 0.8f;
                hsv[2] = 0.7f + 0.3f * (float) Math.sin(angle * 4);
                int alpha = (int) (45 + 25 * Math.sin(angle * 4));
                canvas.drawColor(Color.HSVToColor(alpha, hsv));
            }

            float luminosite = running ? 1f : 0.35f; // spirale tamisée à l'arrêt
            int points = 260;
            for (int i = 0; i < points; i++) {
                float t = i / (float) points;
                float r = t * maxR;
                float a = i * 0.14f + angle;
                float x = cx + (float) Math.cos(a) * r;
                float y = cy + (float) Math.sin(a) * r;

                // Couleurs bleu -> violet -> rose
                hsv[0] = 200f + ((hue - 200f + t * 140f) % 160f + 160f) % 160f;
                hsv[1] = 0.85f;
                hsv[2] = luminosite;
                float taille = 3f + t * 8f;

                glow.setColor(Color.HSVToColor(running ? 255 : 120, hsv));
                canvas.drawCircle(x, y, taille * 2.2f, glow);
                core.setColor(Color.argb(running ? 255 : 110, 255, 255, 255));
                canvas.drawCircle(x, y, taille * 0.5f, core);
            }

            if (running) {
                angle += 0.06f;
                hue = 200f + (hue - 200f + 1.5f) % 160f;
                postInvalidateOnAnimation();
            }
        }
    }
}