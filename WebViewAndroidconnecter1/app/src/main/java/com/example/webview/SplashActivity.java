package com.example.webview;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.animation.ValueAnimator;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class SplashActivity extends AppCompatActivity {

    private static final long DUREE_SPLASH = 2800; // en millisecondes

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ObjectAnimator pulsation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_splash);

        // Icônes blanches dans la barre d'état et de navigation
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);

        View logo = findViewById(R.id.logo);
        View slogan = findViewById(R.id.slogan);
        View ligne = findViewById(R.id.ligne);

        // 1) Logo : apparition + zoom avec petit rebond, puis pulsation douce
        logo.setAlpha(0f);
        logo.setScaleX(0.4f);
        logo.setScaleY(0.4f);
        logo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(900)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .withEndAction(() -> {
                    pulsation = ObjectAnimator.ofPropertyValuesHolder(
                            logo,
                            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f));
                    pulsation.setDuration(900);
                    pulsation.setRepeatCount(ValueAnimator.INFINITE);
                    pulsation.setRepeatMode(ValueAnimator.REVERSE);
                    pulsation.setInterpolator(new AccelerateDecelerateInterpolator());
                    pulsation.start();
                })
                .start();

        // 2) Texte "MADE IN CONGO" : glisse du bas en apparaissant
        slogan.setAlpha(0f);
        slogan.setTranslationY(40f);
        slogan.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(700)
                .setDuration(600)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // 3) Ligne tricolore : se remplit de gauche à droite
        ligne.setPivotX(0f);
        ligne.setScaleX(0f);
        ligne.animate()
                .scaleX(1f)
                .setStartDelay(900)
                .setDuration(1500)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        // 4) Après la durée prévue : ouvre le site avec un fondu
        handler.postDelayed(this::ouvrirSite, DUREE_SPLASH);
    }

    private void ouvrirSite() {
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (pulsation != null) {
            pulsation.cancel();
        }
    }
}