package com.example.barcodescanner;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application de scan de codes-barres.
 * - Navigation : barre en bas (Accueil, Scanner, Résultat, Historique)
 * - Caméra     : CameraX
 * - Détection  : Google ML Kit
 * - Historique : SQLite
 */
public class MainActivity extends AppCompatActivity {

    // ---------- Numéros des onglets ----------
    private static final int TAB_HOME = 0;
    private static final int TAB_SCAN = 1;
    private static final int TAB_RESULT = 2;
    private static final int TAB_HISTORY = 3;

    // ---------- Pages et barre de navigation ----------
    private View[] pages;
    private View[] navItems;
    private TextView[] navIcons;
    private TextView[] navLabels;
    private View bottomNav;

    // ---------- Vues : accueil ----------
    private View headerBox;
    private MaterialButton btnHomeScan;
    private TextView tvCount, tvLastScan, tvHomeLastCode, tvHomeLastFormat;

    // ---------- Vues : scanner ----------
    private PreviewView previewView;
    private View scanFrame, placeholder;
    private TextView tvHint;
    private MaterialButton btnScan;

    // ---------- Vues : résultat ----------
    private View resultCard, emptyResultCard;
    private TextView tvFormat, tvResult;
    private MaterialButton btnCopy, btnNewScan;

    // ---------- Vues : historique ----------
    private TextView tvEmpty, tvClear, tvHistorySub;
    private RecyclerView rvHistory;

    // ---------- Caméra / scanner ----------
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private BarcodeScanner scanner;
    private final AtomicBoolean detected = new AtomicBoolean(false);
    private boolean scanning = false;

    // ---------- Données ----------
    private HistoryDb db;
    private HistoryAdapter adapter;
    private final List<ScanItem> items = new ArrayList<>();
    private String currentCode = "";

    // ---------- Permission caméra ----------
    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    toast("Permission caméra refusée : impossible de scanner");
                }
            });

    // =====================================================================
    //  Cycle de vie
    // =====================================================================
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

        // Icônes de la barre d'état en blanc (fond sombre)
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);

        initViews();
        applyStyles();

        db = new HistoryDb(this);
        cameraExecutor = Executors.newSingleThreadExecutor();
        scanner = BarcodeScanning.getClient(
                new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                        .build());

        adapter = new HistoryAdapter();
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        rvHistory.setAdapter(adapter);

        setupListeners();
        loadHistory();
        updateResultUi();
        setScanUi(false);
        showTab(TAB_HOME);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopScan(); // la caméra s'éteint quand l'appli n'est plus visible
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        scanner.close();
        db.close();
    }

    // =====================================================================
    //  Initialisation de l'interface
    // =====================================================================
    private void initViews() {
        pages = new View[]{
                findViewById(R.id.pageHome),
                findViewById(R.id.pageScan),
                findViewById(R.id.pageResult),
                findViewById(R.id.pageHistory)};
        navItems = new View[]{
                findViewById(R.id.navHome),
                findViewById(R.id.navScan),
                findViewById(R.id.navResult),
                findViewById(R.id.navHistory)};
        navIcons = new TextView[]{
                findViewById(R.id.iconHome),
                findViewById(R.id.iconScan),
                findViewById(R.id.iconResult),
                findViewById(R.id.iconHistory)};
        navLabels = new TextView[]{
                findViewById(R.id.lblHome),
                findViewById(R.id.lblScan),
                findViewById(R.id.lblResult),
                findViewById(R.id.lblHistory)};
        bottomNav = findViewById(R.id.bottomNav);

        headerBox = findViewById(R.id.headerBox);
        btnHomeScan = findViewById(R.id.btnHomeScan);
        tvCount = findViewById(R.id.tvCount);
        tvLastScan = findViewById(R.id.tvLastScan);
        tvHomeLastCode = findViewById(R.id.tvHomeLastCode);
        tvHomeLastFormat = findViewById(R.id.tvHomeLastFormat);

        previewView = findViewById(R.id.previewView);
        scanFrame = findViewById(R.id.scanFrame);
        placeholder = findViewById(R.id.placeholder);
        tvHint = findViewById(R.id.tvHint);
        btnScan = findViewById(R.id.btnScan);

        resultCard = findViewById(R.id.resultCard);
        emptyResultCard = findViewById(R.id.emptyResultCard);
        tvFormat = findViewById(R.id.tvFormat);
        tvResult = findViewById(R.id.tvResult);
        btnCopy = findViewById(R.id.btnCopy);
        btnNewScan = findViewById(R.id.btnNewScan);

        tvEmpty = findViewById(R.id.tvEmpty);
        tvClear = findViewById(R.id.tvClear);
        tvHistorySub = findViewById(R.id.tvHistorySub);
        rvHistory = findViewById(R.id.rvHistory);
    }

    private void setupListeners() {
        navItems[TAB_HOME].setOnClickListener(v -> showTab(TAB_HOME));
        navItems[TAB_SCAN].setOnClickListener(v -> showTab(TAB_SCAN));
        navItems[TAB_RESULT].setOnClickListener(v -> showTab(TAB_RESULT));
        navItems[TAB_HISTORY].setOnClickListener(v -> showTab(TAB_HISTORY));

        btnHomeScan.setOnClickListener(v -> {
            showTab(TAB_SCAN);
            onScanClicked();
        });
        btnNewScan.setOnClickListener(v -> {
            showTab(TAB_SCAN);
            onScanClicked();
        });

        btnScan.setOnClickListener(v -> {
            if (scanning) {
                stopScan();
            } else {
                onScanClicked();
            }
        });

        btnCopy.setOnClickListener(v -> copyToClipboard(currentCode));
        tvClear.setOnClickListener(v -> confirmClearHistory());
    }

    /** Dégradés, cadre de visée, badge de format et barre du bas. */
    private void applyStyles() {
        GradientDrawable header = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF4F8CFF, 0xFF1D3A8A});
        header.setCornerRadius(dp(28));
        headerBox.setBackground(header);

        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Color.TRANSPARENT);
        frame.setStroke(dp(3), 0xFF60A5FA);
        frame.setCornerRadius(dp(20));
        scanFrame.setBackground(frame);

        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x332F6BFF);
        chip.setCornerRadius(dp(50));
        tvFormat.setBackground(chip);
        tvFormat.setPadding(dp(14), dp(6), dp(14), dp(6));

        GradientDrawable nav = new GradientDrawable();
        nav.setColor(0xFF121A33);
        nav.setCornerRadius(dp(28));
        bottomNav.setBackground(nav);
    }

    // =====================================================================
    //  Navigation entre les pages
    // =====================================================================
    private void showTab(int tab) {
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);

            boolean selected = (i == tab);
            navIcons[i].setAlpha(selected ? 1f : 0.45f);
            navLabels[i].setTextColor(selected ? 0xFF60A5FA : 0xFF64748B);
            navLabels[i].setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);

            if (selected) {
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(0xFF1E2A52);
                pill.setCornerRadius(dp(20));
                navItems[i].setBackground(pill);
            } else {
                navItems[i].setBackground(null);
            }
        }
        // La caméra ne tourne que sur la page Scanner
        if (tab != TAB_SCAN) {
            stopScan();
        }
    }

    /** Page Scanner : caméra éteinte ou allumée. */
    private void setScanUi(boolean isScanning) {
        scanning = isScanning;
        placeholder.setVisibility(isScanning ? View.GONE : View.VISIBLE);
        scanFrame.setVisibility(isScanning ? View.VISIBLE : View.GONE);
        btnScan.setText(isScanning ? "Arrêter le scan" : "Scanner un code-barres");
        tvHint.setText(isScanning
                ? "Placez le code-barres dans le cadre"
                : "La caméra est éteinte");
    }

    /** Page Résultat : carte vide ou résultat. */
    private void updateResultUi() {
        boolean has = currentCode != null && !currentCode.isEmpty();
        resultCard.setVisibility(has ? View.VISIBLE : View.GONE);
        emptyResultCard.setVisibility(has ? View.GONE : View.VISIBLE);
    }

    // =====================================================================
    //  Caméra
    // =====================================================================
    private void onScanClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        detected.set(false);
        setScanUi(true);

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                toast("Erreur caméra : " + e.getMessage());
                setScanUi(false);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyze);

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
    }

    private void stopScan() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (scanning) {
            setScanUi(false);
        }
    }

    // =====================================================================
    //  Détection du code-barres
    // =====================================================================
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(@NonNull ImageProxy imageProxy) {
        if (detected.get() || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees());

        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String raw = barcode.getRawValue();
                        if (raw != null && !raw.isEmpty()) {
                            onBarcodeDetected(raw, barcode.getFormat());
                            break;
                        }
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onBarcodeDetected(String value, int format) {
        // Évite de traiter plusieurs fois le même scan
        if (!detected.compareAndSet(false, true)) return;

        String formatName = formatToString(format);
        currentCode = value;

        db.insert(value, formatName);
        loadHistory();

        tvResult.setText(value);
        tvFormat.setText(formatName);
        updateResultUi();

        // Va directement sur la page Résultat (et coupe la caméra)
        showTab(TAB_RESULT);
    }

    /** Transforme le code interne ML Kit en nom lisible. */
    private String formatToString(int format) {
        switch (format) {
            case Barcode.FORMAT_EAN_13:      return "EAN-13";
            case Barcode.FORMAT_EAN_8:       return "EAN-8";
            case Barcode.FORMAT_UPC_A:       return "UPC-A";
            case Barcode.FORMAT_UPC_E:       return "UPC-E";
            case Barcode.FORMAT_CODE_128:    return "Code 128";
            case Barcode.FORMAT_CODE_39:     return "Code 39";
            case Barcode.FORMAT_CODE_93:     return "Code 93";
            case Barcode.FORMAT_CODABAR:     return "Codabar";
            case Barcode.FORMAT_ITF:         return "ITF";
            case Barcode.FORMAT_QR_CODE:     return "QR Code";
            case Barcode.FORMAT_DATA_MATRIX: return "Data Matrix";
            case Barcode.FORMAT_PDF417:      return "PDF417";
            case Barcode.FORMAT_AZTEC:       return "Aztec";
            default:                         return "Inconnu";
        }
    }

    // =====================================================================
    //  Actions
    // =====================================================================
    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("code-barres", text));
        toast("Numéro copié : " + text);
    }

    private void confirmClearHistory() {
        if (items.isEmpty()) {
            toast("L'historique est déjà vide");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Effacer l'historique ?")
                .setMessage("Tous les codes scannés seront supprimés.")
                .setPositiveButton("Effacer", (d, w) -> {
                    db.clear();
                    loadHistory();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Relit SQLite et met à jour l'accueil et l'historique. */
    private void loadHistory() {
        items.clear();
        items.addAll(db.getAll());
        adapter.notifyDataSetChanged();

        boolean empty = items.isEmpty();
        int size = items.size();

        // Accueil
        tvCount.setText(String.valueOf(size));
        tvLastScan.setText(empty
                ? "Aucun scan pour le moment"
                : "Dernier scan : " + items.get(0).date);
        tvHomeLastCode.setText(empty ? "—" : items.get(0).code);
        tvHomeLastFormat.setText(empty ? "Aucun code scanné" : items.get(0).format);

        // Historique
        tvHistorySub.setText(size + (size > 1 ? " codes enregistrés" : " code enregistré"));
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvHistory.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // =====================================================================
    //  Modèle de données
    // =====================================================================
    static class ScanItem {
        final long id;
        final String code;
        final String format;
        final String date;

        ScanItem(long id, String code, String format, String date) {
            this.id = id;
            this.code = code;
            this.format = format;
            this.date = date;
        }
    }

    // =====================================================================
    //  Base de données SQLite
    // =====================================================================
    static class HistoryDb extends SQLiteOpenHelper {

        private static final String DB_NAME = "scan_history.db";
        private static final int DB_VERSION = 1;
        private static final String TABLE = "history";

        HistoryDb(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "code TEXT NOT NULL, "
                    + "format TEXT NOT NULL, "
                    + "scanned_at TEXT NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        }

        long insert(String code, String format) {
            String now = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    .format(new Date());
            ContentValues values = new ContentValues();
            values.put("code", code);
            values.put("format", format);
            values.put("scanned_at", now);
            return getWritableDatabase().insert(TABLE, null, values);
        }

        List<ScanItem> getAll() {
            List<ScanItem> list = new ArrayList<>();
            try (Cursor c = getReadableDatabase().query(
                    TABLE, null, null, null, null, null, "id DESC")) {
                while (c.moveToNext()) {
                    list.add(new ScanItem(
                            c.getLong(c.getColumnIndexOrThrow("id")),
                            c.getString(c.getColumnIndexOrThrow("code")),
                            c.getString(c.getColumnIndexOrThrow("format")),
                            c.getString(c.getColumnIndexOrThrow("scanned_at"))));
                }
            }
            return list;
        }

        void clear() {
            getWritableDatabase().delete(TABLE, null, null);
        }
    }

    // =====================================================================
    //  Adaptateur de la liste d'historique (lignes créées en Java)
    // =====================================================================
    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final TextView code, info;

            VH(View itemView, TextView code, TextView info) {
                super(itemView);
                this.code = code;
                this.info = info;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            RecyclerView.LayoutParams rowParams = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(10);
            row.setLayoutParams(rowParams);

            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(0xFF121A33);
            rowBg.setCornerRadius(dp(18));
            row.setBackground(rowBg);

            TextView icon = new TextView(ctx);
            icon.setText("🏷️");
            icon.setTextSize(20);
            icon.setGravity(Gravity.CENTER);
            GradientDrawable iconBg = new GradientDrawable();
            iconBg.setColor(0xFF24315E);
            iconBg.setCornerRadius(dp(14));
            icon.setBackground(iconBg);
            row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

            LinearLayout column = new LinearLayout(ctx);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colParams.setMarginStart(dp(12));

            TextView code = new TextView(ctx);
            code.setTextColor(Color.WHITE);
            code.setTextSize(15);
            code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            code.setSingleLine(true);
            code.setEllipsize(TextUtils.TruncateAt.END);

            TextView info = new TextView(ctx);
            info.setTextColor(0xFF94A3B8);
            info.setTextSize(12);

            column.addView(code);
            column.addView(info);
            row.addView(column, colParams);

            return new VH(row, code, info);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ScanItem item = items.get(position);
            holder.code.setText(item.code);
            holder.info.setText(item.format + "  •  " + item.date);
            // Un clic sur une ligne copie le numéro
            holder.itemView.setOnClickListener(v -> copyToClipboard(item.code));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}