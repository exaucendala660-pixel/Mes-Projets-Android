package com.example.quizinfo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    // ---------- Couleurs du design ----------
    private static final int BG = Color.parseColor("#0B0D17");
    private static final int SURFACE = Color.parseColor("#151929");
    private static final int STROKE = Color.parseColor("#272C42");
    private static final int AMBER = Color.parseColor("#FFB400");
    private static final int MUTED = Color.parseColor("#9AA0B4");
    private static final int GREEN = Color.parseColor("#22C55E");
    private static final int RED = Color.parseColor("#EF4444");
    private static final int GREEN_DARK = Color.parseColor("#15803D");
    private static final int RED_DARK = Color.parseColor("#B91C1C");

    // Nombre de questions tirées à chaque partie (la banque en contient davantage)
    private static final int QUESTIONS_PER_QUIZ = 10;

    // ---------- Vues ----------
    private ScrollView screenHome, screenQuiz, screenResult, screenHistory;
    private EditText etName;
    private TextView tvLastResult, tvProgress, tvDomain, tvQuestion;
    private TextView tvFeedbackTitle, tvFeedbackPercent, tvExplanation;
    private TextView tvFinalPercent, tvLevel, tvScoreDetail, tvCorrect, tvWrong, tvHistoryEmpty;
    private ProgressBar progressBar;
    private RadioGroup rgAnswers;
    private RadioButton[] rbs;
    private View cardFeedback;
    private MaterialButton btnAction;
    private LinearLayout recapList, historyList;

    // ---------- Données ----------
    private DbHelper db;
    private List<Question> quiz = new ArrayList<>();
    private boolean[] results;
    private int current = 0;
    private int score = 0;
    private boolean answered = false;
    private String player = "Invité";
    private long quizSeed = 0;
    private boolean hasResult = false;
    private long lastActionClick = 0;

    // =====================================================
    //  CLASSE QUESTION
    // =====================================================
    private static class Question {
        final String domain, text, explanation;
        final String[] options;
        final int correct;

        Question(String domain, String text, String[] options, int correct, String explanation) {
            this.domain = domain;
            this.text = text;
            this.options = options;
            this.correct = correct;
            this.explanation = explanation;
        }
    }

    // =====================================================
    //  SQLITE
    // =====================================================
    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context context) {
            super(context, "quizinfo.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE results (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "player TEXT NOT NULL, " +
                    "correct INTEGER, " +
                    "total INTEGER, " +
                    "percent INTEGER, " +
                    "level TEXT, " +
                    "date_played TEXT)");
        }

        // Mise à jour non destructive : on ne supprime plus les données existantes
        @Override
        public void onUpgrade(SQLiteDatabase d, int oldV, int newV) {
            try {
                d.execSQL("ALTER TABLE results ADD COLUMN level TEXT");
            } catch (Exception ignored) {
                // La colonne existe déjà
            }
        }

        void insertResult(String player, int correct, int total, int percent, String level, String date) {
            ContentValues v = new ContentValues();
            v.put("player", player);
            v.put("correct", correct);
            v.put("total", total);
            v.put("percent", percent);
            v.put("level", level);
            v.put("date_played", date);
            getWritableDatabase().insert("results", null, v);
        }

        Cursor getAll() {
            return getReadableDatabase().rawQuery(
                    "SELECT id, player, correct, total, percent, level, date_played " +
                            "FROM results ORDER BY id DESC", null);
        }

        void clearAll() {
            getWritableDatabase().delete("results", null, null);
        }
    }

    // =====================================================
    //  CYCLE DE VIE
    // =====================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Icônes claires de la barre d'état (fond sombre)
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(false);

        db = new DbHelper(this);
        bindViews();
        applyStyles();
        setupListeners();

        boolean restored = false;
        if (savedInstanceState != null) {
            long seed = savedInstanceState.getLong("quizSeed", 0);
            if (seed != 0) {
                quizSeed = seed;
                current = savedInstanceState.getInt("current", 0);
                score = savedInstanceState.getInt("score", 0);
                answered = savedInstanceState.getBoolean("answered", false);
                player = savedInstanceState.getString("player", "Invité");
                hasResult = savedInstanceState.getBoolean("hasResult", false);
                int selected = savedInstanceState.getInt("selected", -1);
                boolean[] savedResults = savedInstanceState.getBooleanArray("results");
                buildQuizWithSeed(quizSeed);
                if (savedResults != null && savedResults.length == results.length) {
                    results = savedResults;
                }
                String screen = savedInstanceState.getString("screen", "home");
                restored = true;
                switch (screen) {
                    case "quiz":
                        showScreen(screenQuiz);
                        progressBar.setMax(quiz.size());
                        displayQuestion();
                        if (selected >= 0) rgAnswers.check(rbs[selected].getId());
                        if (answered) {
                            applyAnsweredUI(quiz.get(current), selected, results[current]);
                        } else if (selected >= 0) {
                            setOptionStyle(rbs[selected], 1);
                        }
                        break;
                    case "result":
                        renderResultScreen();
                        showScreen(screenResult);
                        break;
                    case "history":
                        showHistory();
                        break;
                    default:
                        showHome();
                        break;
                }
            }
        }
        if (!restored) {
            showHome();
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (screenHome.getVisibility() == View.VISIBLE) {
                    finish();
                } else {
                    showHome();
                }
            }
        });
    }

    // Sauvegarde de l'état du quiz (rotation d'écran, changement d'application...)
    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong("quizSeed", quizSeed);
        out.putInt("current", current);
        out.putInt("score", score);
        out.putBoolean("answered", answered);
        out.putBoolean("hasResult", hasResult);
        out.putBooleanArray("results", results);
        out.putString("player", player);

        int selected = -1;
        int checkedId = rgAnswers.getCheckedRadioButtonId();
        for (int i = 0; i < rbs.length; i++) {
            if (rbs[i].getId() == checkedId) selected = i;
        }
        out.putInt("selected", selected);

        String screen;
        if (screenQuiz.getVisibility() == View.VISIBLE) {
            screen = "quiz";
        } else if (screenResult.getVisibility() == View.VISIBLE) {
            screen = "result";
        } else if (screenHistory.getVisibility() == View.VISIBLE) {
            screen = "history";
        } else {
            screen = "home";
        }
        out.putString("screen", screen);
    }

    private void bindViews() {
        screenHome = findViewById(R.id.screenHome);
        screenQuiz = findViewById(R.id.screenQuiz);
        screenResult = findViewById(R.id.screenResult);
        screenHistory = findViewById(R.id.screenHistory);

        etName = findViewById(R.id.etName);
        tvLastResult = findViewById(R.id.tvLastResult);

        tvProgress = findViewById(R.id.tvProgress);
        tvDomain = findViewById(R.id.tvDomain);
        tvQuestion = findViewById(R.id.tvQuestion);
        progressBar = findViewById(R.id.progressBar);
        rgAnswers = findViewById(R.id.rgAnswers);
        rbs = new RadioButton[]{
                findViewById(R.id.rb1), findViewById(R.id.rb2),
                findViewById(R.id.rb3), findViewById(R.id.rb4)};
        cardFeedback = findViewById(R.id.cardFeedback);
        tvFeedbackTitle = findViewById(R.id.tvFeedbackTitle);
        tvFeedbackPercent = findViewById(R.id.tvFeedbackPercent);
        tvExplanation = findViewById(R.id.tvExplanation);
        btnAction = findViewById(R.id.btnAction);

        tvFinalPercent = findViewById(R.id.tvFinalPercent);
        tvLevel = findViewById(R.id.tvLevel);
        tvScoreDetail = findViewById(R.id.tvScoreDetail);
        tvCorrect = findViewById(R.id.tvCorrect);
        tvWrong = findViewById(R.id.tvWrong);
        recapList = findViewById(R.id.recapList);

        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty);
        historyList = findViewById(R.id.historyList);
    }

    private void applyStyles() {
        // Barre de progression arrondie
        GradientDrawable bg = rounded(STROKE, 0, 8, 0);
        GradientDrawable fg = rounded(AMBER, 0, 8, 0);
        ClipDrawable clip = new ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{bg, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        progressBar.setProgressDrawable(layers);

        // Pastilles
        tvDomain.setBackground(rounded(Color.parseColor("#2A2412"), AMBER, 20, 1));
        tvDomain.setPadding(dp(14), dp(6), dp(14), dp(6));
        tvLevel.setBackground(rounded(BG, 0, 30, 0));
        tvLevel.setPadding(dp(20), dp(8), dp(20), dp(8));

        for (RadioButton rb : rbs) {
            setOptionStyle(rb, 0);
        }
    }

    private void setupListeners() {
        findViewById(R.id.btnStart).setOnClickListener(v -> startQuiz());
        findViewById(R.id.btnHistory).setOnClickListener(v -> showHistory());
        findViewById(R.id.btnRestart).setOnClickListener(v -> startQuiz());
        findViewById(R.id.btnResultHistory).setOnClickListener(v -> showHistory());
        findViewById(R.id.btnResultHome).setOnClickListener(v -> showHome());
        findViewById(R.id.btnHistoryBack).setOnClickListener(v -> showHome());
        findViewById(R.id.btnClearHistory).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Effacer l'historique")
                        .setMessage("Veux-tu vraiment supprimer tous les résultats enregistrés ?")
                        .setPositiveButton("Effacer", (d, w) -> {
                            db.clearAll();
                            showHistory();
                            Toast.makeText(this, "Historique effacé", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Annuler", null)
                        .show());

        // Barre de navigation en bas : Accueil · Quiz · Résultats · Historique
        findViewById(R.id.navHome).setOnClickListener(v -> showHome());
        findViewById(R.id.navQuiz).setOnClickListener(v -> startQuiz());
        findViewById(R.id.navResults).setOnClickListener(v -> {
            if (hasResult) {
                showScreen(screenResult);
            } else {
                Toast.makeText(this, "Termine un quiz pour voir tes résultats", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.navHistory).setOnClickListener(v -> showHistory());

        btnAction.setOnClickListener(v -> {
            // Protection contre le double-tap
            long now = System.currentTimeMillis();
            if (now - lastActionClick < 500) return;
            lastActionClick = now;

            if (!answered) {
                validateAnswer();
            } else {
                nextQuestion();
            }
        });

        rgAnswers.setOnCheckedChangeListener((group, checkedId) -> {
            if (answered) return;
            for (RadioButton rb : rbs) {
                setOptionStyle(rb, rb.getId() == checkedId ? 1 : 0);
            }
        });
    }

    // =====================================================
    //  NAVIGATION ENTRE LES ÉCRANS
    // =====================================================
    private void showScreen(View target) {
        screenHome.setVisibility(View.GONE);
        screenQuiz.setVisibility(View.GONE);
        screenResult.setVisibility(View.GONE);
        screenHistory.setVisibility(View.GONE);
        target.setVisibility(View.VISIBLE);
        setActiveTab(target);
    }

    private void setActiveTab(View screen) {
        int active = R.id.navHome;
        if (screen == screenQuiz) {
            active = R.id.navQuiz;
        } else if (screen == screenResult) {
            active = R.id.navResults;
        } else if (screen == screenHistory) {
            active = R.id.navHistory;
        }
        int[] tabs = {R.id.navHome, R.id.navQuiz, R.id.navResults, R.id.navHistory};
        for (int id : tabs) {
            View tab = findViewById(id);
            if (tab instanceof ViewGroup && ((ViewGroup) tab).getChildAt(1) instanceof TextView) {
                TextView label = (TextView) ((ViewGroup) tab).getChildAt(1);
                label.setTextColor(id == active ? AMBER : MUTED);
            }
        }
    }

    private void showHome() {
        try (Cursor c = db.getAll()) {
            if (c.moveToFirst()) {
                int idxPlayer = c.getColumnIndexOrThrow("player");
                int idxPercent = c.getColumnIndexOrThrow("percent");
                int idxLevel = c.getColumnIndexOrThrow("level");
                tvLastResult.setText(String.format(Locale.FRANCE, "%s : %d %% · %s",
                        c.getString(idxPlayer), c.getInt(idxPercent), c.getString(idxLevel)));
            } else {
                tvLastResult.setText("Aucun quiz effectué pour le moment.");
            }
        }
        showScreen(screenHome);
    }

    // =====================================================
    //  LOGIQUE DU QUIZ
    // =====================================================
    private void startQuiz() {
        String name = etName.getText().toString().trim();
        player = name.isEmpty() ? "Invité" : name;
        hideKeyboard();

        quizSeed = System.currentTimeMillis();
        buildQuizWithSeed(quizSeed);
        current = 0;
        score = 0;
        answered = false;

        showScreen(screenQuiz);
        progressBar.setMax(quiz.size());
        showQuestion();
    }

    // Construit un quiz déterministe : même graine = mêmes questions,
    // même ordre et mêmes options (indispensable pour la restauration).
    private void buildQuizWithSeed(long seed) {
        List<Question> base = buildQuestions();
        Random r = new Random(seed);
        Collections.shuffle(base, r);

        quiz = new ArrayList<>();
        int count = Math.min(QUESTIONS_PER_QUIZ, base.size());
        for (int i = 0; i < count; i++) {
            quiz.add(shuffleOptions(base.get(i), r));
        }
        results = new boolean[quiz.size()];
    }

    // Mélange les propositions d'une question en conservant le bon index de réponse
    private Question shuffleOptions(Question q, Random r) {
        List<Integer> idx = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
        Collections.shuffle(idx, r);
        String[] opts = new String[4];
        int correct = 0;
        for (int i = 0; i < idx.size(); i++) {
            opts[i] = q.options[idx.get(i)];
            if (idx.get(i) == q.correct) correct = i;
        }
        return new Question(q.domain, q.text, opts, correct, q.explanation);
    }

    private void showQuestion() {
        answered = false;
        rgAnswers.clearCheck();
        for (RadioButton rb : rbs) {
            rb.setClickable(true);
            setOptionStyle(rb, 0);
        }
        cardFeedback.setVisibility(View.GONE);
        btnAction.setText("Valider");
        displayQuestion();
    }

    private void displayQuestion() {
        Question q = quiz.get(current);

        tvProgress.setText(String.format(Locale.FRANCE, "Question %d / %d", current + 1, quiz.size()));
        tvDomain.setText(q.domain);
        tvQuestion.setText(q.text);
        progressBar.setProgress(current);

        for (int i = 0; i < rbs.length; i++) {
            rbs[i].setText(q.options[i]);
        }

        screenQuiz.scrollTo(0, 0);
    }

    private void validateAnswer() {
        int checkedId = rgAnswers.getCheckedRadioButtonId();
        if (checkedId == -1) {
            Toast.makeText(this, "Choisis une réponse avant de valider", Toast.LENGTH_SHORT).show();
            return;
        }

        Question q = quiz.get(current);
        int selected = -1;
        for (int i = 0; i < rbs.length; i++) {
            if (rbs[i].getId() == checkedId) selected = i;
        }

        boolean ok = (selected == q.correct);
        results[current] = ok;
        if (ok) score++;
        answered = true;

        applyAnsweredUI(q, selected, ok);
    }

    private void applyAnsweredUI(Question q, int selected, boolean ok) {
        for (RadioButton rb : rbs) rb.setClickable(false);
        setOptionStyle(rbs[q.correct], 2);
        if (!ok) setOptionStyle(rbs[selected], 3);

        int percent = score * 100 / (current + 1);
        progressBar.setProgress(current + 1);

        tvFeedbackTitle.setText(ok ? "Bonne réponse" : "Mauvaise réponse");
        tvFeedbackTitle.setTextColor(ok ? GREEN_DARK : RED_DARK);
        tvFeedbackPercent.setText(String.format(Locale.FRANCE,
                "Taux de réussite : %d %%  (%d / %d)", percent, score, current + 1));
        tvExplanation.setText((ok ? "" : "Bonne réponse : " + q.options[q.correct] + ".\n") + q.explanation);
        cardFeedback.setVisibility(View.VISIBLE);

        btnAction.setText(current < quiz.size() - 1 ? "Question suivante" : "Voir le résultat");
        screenQuiz.post(() -> screenQuiz.fullScroll(View.FOCUS_DOWN));
    }

    private void nextQuestion() {
        current++;
        if (current >= quiz.size()) {
            finishQuiz();
        } else {
            showQuestion();
        }
    }

    private void finishQuiz() {
        int total = quiz.size();
        int percent = score * 100 / total;
        String level = getLevel(percent);

        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date());
        db.insertResult(player, score, total, percent, level, date);
        hasResult = true;

        renderResultScreen();
        showScreen(screenResult);
        screenResult.scrollTo(0, 0);
    }

    private void renderResultScreen() {
        int total = quiz.size();
        int percent = score * 100 / total;
        String level = getLevel(percent);

        tvFinalPercent.setText(String.format(Locale.FRANCE, "%d %%", percent));
        tvLevel.setText(level);
        tvScoreDetail.setText(String.format(Locale.FRANCE, "%s · %d / %d bonnes réponses", player, score, total));
        tvCorrect.setText(String.valueOf(score));
        tvWrong.setText(String.valueOf(total - score));

        recapList.removeAllViews();
        for (int i = 0; i < total; i++) {
            Question q = quiz.get(i);
            boolean ok = results[i];
            TextView tv = new TextView(this);
            String text = (ok ? "✓  " : "✗  ") + "Q" + (i + 1) + " · " + q.domain;
            if (!ok) text += "\nBonne réponse : " + q.options[q.correct];
            tv.setText(text);
            tv.setTextColor(ok ? GREEN : RED);
            tv.setTextSize(14);
            tv.setPadding(dp(16), dp(12), dp(16), dp(12));
            tv.setBackground(rounded(SURFACE, STROKE, 16, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            tv.setLayoutParams(lp);
            recapList.addView(tv);
        }
    }

    private String getLevel(int p) {
        if (p >= 90) return "Excellent niveau";
        if (p >= 75) return "Bon niveau";
        if (p >= 60) return "Niveau moyen";
        if (p >= 40) return "Niveau faible";
        return "Débutant";
    }

    // =====================================================
    //  HISTORIQUE (SQLite)
    // =====================================================
    private void showHistory() {
        historyList.removeAllViews();

        try (Cursor c = db.getAll()) {
            int idxPlayer = c.getColumnIndexOrThrow("player");
            int idxCorrect = c.getColumnIndexOrThrow("correct");
            int idxTotal = c.getColumnIndexOrThrow("total");
            int idxPercent = c.getColumnIndexOrThrow("percent");
            int idxLevel = c.getColumnIndexOrThrow("level");
            int idxDate = c.getColumnIndexOrThrow("date_played");

            tvHistoryEmpty.setVisibility(c.getCount() == 0 ? View.VISIBLE : View.GONE);

            while (c.moveToNext()) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(18), dp(16), dp(18), dp(16));
                row.setBackground(rounded(SURFACE, STROKE, 20, 1));
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = dp(10);
                row.setLayoutParams(rowLp);

                LinearLayout left = new LinearLayout(this);
                left.setOrientation(LinearLayout.VERTICAL);
                left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView name = new TextView(this);
                name.setText(c.getString(idxPlayer));
                name.setTextColor(Color.WHITE);
                name.setTextSize(17);
                name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);

                TextView detail = new TextView(this);
                detail.setText(String.format(Locale.FRANCE, "%s · %d/%d · %s",
                        c.getString(idxDate), c.getInt(idxCorrect), c.getInt(idxTotal), c.getString(idxLevel)));
                detail.setTextColor(MUTED);
                detail.setTextSize(13);

                left.addView(name);
                left.addView(detail);

                TextView percent = new TextView(this);
                percent.setText(String.format(Locale.FRANCE, "%d %%", c.getInt(idxPercent)));
                percent.setTextColor(AMBER);
                percent.setTextSize(24);
                percent.setTypeface(percent.getTypeface(), android.graphics.Typeface.BOLD);

                row.addView(left);
                row.addView(percent);
                historyList.addView(row);
            }
        }
        showScreen(screenHistory);
        screenHistory.scrollTo(0, 0);
    }

    // =====================================================
    //  OUTILS DE STYLE
    // =====================================================
    // state : 0 = normal, 1 = sélectionné, 2 = correct, 3 = faux
    private void setOptionStyle(RadioButton rb, int state) {
        switch (state) {
            case 1:
                rb.setBackground(rounded(Color.parseColor("#2A2412"), AMBER, 20, 2));
                break;
            case 2:
                rb.setBackground(rounded(Color.parseColor("#143D26"), GREEN, 20, 2));
                break;
            case 3:
                rb.setBackground(rounded(Color.parseColor("#43181D"), RED, 20, 2));
                break;
            default:
                rb.setBackground(rounded(SURFACE, STROKE, 20, 1));
                break;
        }
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etName.getWindowToken(), 0);
        etName.clearFocus();
    }

    // =====================================================
    //  QUESTIONS
    // =====================================================
    private List<Question> buildQuestions() {
        List<Question> l = new ArrayList<>();

        l.add(new Question("Matériel", "Que signifie l'abréviation CPU ?",
                new String[]{"Central Processing Unit", "Computer Personal Unit",
                        "Central Program Utility", "Control Processing User"}, 0,
                "CPU signifie Central Processing Unit : c'est le processeur, le cerveau de l'ordinateur qui exécute les instructions."));

        l.add(new Question("Réseaux", "Quel protocole attribue automatiquement une adresse IP aux appareils d'un réseau ?",
                new String[]{"DNS", "DHCP", "FTP", "SMTP"}, 1,
                "DHCP (Dynamic Host Configuration Protocol) distribue automatiquement les adresses IP. DNS, lui, traduit les noms de domaine en adresses IP."));

        l.add(new Question("Programmation", "En Java, quel mot-clé permet de créer un nouvel objet ?",
                new String[]{"this", "new", "static", "void"}, 1,
                "Le mot-clé new alloue la mémoire et appelle le constructeur de la classe pour créer un objet."));

        l.add(new Question("Bases de données", "Quelle commande SQL permet de lire des données dans une table ?",
                new String[]{"INSERT", "UPDATE", "SELECT", "DELETE"}, 2,
                "SELECT interroge la base et retourne les lignes demandées. INSERT ajoute, UPDATE modifie et DELETE supprime."));

        l.add(new Question("Systèmes", "Quel est le rôle principal d'un système d'exploitation ?",
                new String[]{"Créer des documents", "Gérer les ressources matérielles et logicielles",
                        "Naviguer sur Internet", "Protéger contre les virus uniquement"}, 1,
                "Le système d'exploitation (Windows, Linux, Android...) fait l'intermédiaire entre le matériel et les applications : mémoire, processeur, fichiers, périphériques."));

        l.add(new Question("Sécurité", "Comment appelle-t-on une attaque qui trompe l'utilisateur par un faux message pour voler ses identifiants ?",
                new String[]{"Le phishing", "La compilation", "La défragmentation", "Le formatage"}, 0,
                "Le phishing (hameçonnage) imite un site ou un message de confiance pour récupérer des mots de passe ou des données bancaires."));

        l.add(new Question("Web", "Quel langage sert à décrire la structure d'une page web ?",
                new String[]{"CSS", "HTML", "SQL", "Python"}, 1,
                "HTML structure le contenu (titres, paragraphes, liens). CSS s'occupe de la mise en forme."));

        l.add(new Question("Réseaux", "Combien de bits compte une adresse IPv4 ?",
                new String[]{"16", "32", "64", "128"}, 1,
                "Une adresse IPv4 fait 32 bits, soit 4 octets (ex. 192.168.1.1). IPv6 utilise 128 bits."));

        l.add(new Question("Algorithmique", "Quelle structure de données fonctionne selon le principe FIFO (premier entré, premier sorti) ?",
                new String[]{"La pile", "La file", "L'arbre", "Le graphe"}, 1,
                "Dans une file (queue), le premier élément ajouté est le premier retiré. La pile (stack) fonctionne en LIFO."));

        l.add(new Question("Matériel", "Quelle mémoire perd son contenu quand l'ordinateur est éteint ?",
                new String[]{"Le disque dur", "Le SSD", "La RAM", "La ROM"}, 2,
                "La RAM est une mémoire volatile : elle ne garde les données que lorsqu'elle est alimentée."));

        // ---------- Nouvelles questions ----------
        l.add(new Question("Bases de données", "Quel langage permet de gérer les données d'une base relationnelle ?",
                new String[]{"HTML", "SQL", "Java", "CSS"}, 1,
                "SQL (Structured Query Language) est le langage dédié aux bases de données relationnelles : lecture, insertion, modification et suppression de données."));

        l.add(new Question("Réseaux", "Qu'est-ce qu'un VPN ?",
                new String[]{"Un réseau privé virtuel chiffré", "Un virus informatique",
                        "Un type de câble réseau", "Un logiciel de bureautique"}, 0,
                "Un VPN (Virtual Private Network) crée une connexion chiffrée entre ton appareil et un serveur : ton trafic est protégé et ton adresse IP masquée."));

        l.add(new Question("Matériel", "Quel composant est principalement responsable du refroidissement du processeur ?",
                new String[]{"La carte son", "Le ventilateur", "Le lecteur DVD", "La souris"}, 1,
                "Le ventilateur (associé à un dissipateur) évacue la chaleur produite par le processeur pour éviter la surchauffe."));

        l.add(new Question("Web", "Que signifie le S de HTTPS par rapport à HTTP ?",
                new String[]{"Le site est plus rapide", "Les données sont chiffrées",
                        "Il ne fonctionne pas sur mobile", "Il est réservé aux administrations"}, 1,
                "Le S de HTTPS signifie Secure : les échanges entre le navigateur et le site sont chiffrés grâce à un certificat (TLS/SSL)."));

        l.add(new Question("Programmation", "Quel langage est principalement utilisé pour styliser une page web ?",
                new String[]{"CSS", "C++", "SQL", "Assembly"}, 0,
                "CSS (Cascading Style Sheets) définit l'apparence d'une page : couleurs, polices, marges, disposition des éléments."));

        l.add(new Question("Systèmes", "Quelle commande Windows affiche la configuration IP de la machine ?",
                new String[]{"ping", "ipconfig", "cd", "dir"}, 1,
                "ipconfig affiche la configuration réseau (adresse IP, masque, passerelle). ping teste la connexion, cd et dir concernent les dossiers."));

        return l;
    }
}
