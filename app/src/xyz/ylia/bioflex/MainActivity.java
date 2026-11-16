package xyz.ylia.bioflex;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.*;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

/**
 * Bioflex E85 — lecture BLE du % d'éthanol + mode journal complet.
 * Reconstitué par rétro-ingénierie de l'appli officielle 2.4.2 (usage éducatif,
 * pour ton propre boîtier). Protocole : commande "L\r\n" -> trame ASCII délimitée
 * par des lettres ; % éthanol = valeur au marqueur 'O'.
 */
public class MainActivity extends Activity {

    // --- 3 familles de modules BLE-UART : service, write, notify -----------
    static final UUID[][] MODULES = {
        { u("0000fff0-0000-1000-8000-00805f9b34fb"), u("0000fff2-0000-1000-8000-00805f9b34fb"), u("0000fff1-0000-1000-8000-00805f9b34fb") },
        { u("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), u("6e400002-b5a3-f393-e0a9-e50e24dcca9e"), u("6e400003-b5a3-f393-e0a9-e50e24dcca9e") },
        // Microchip ISSC (BM70/BM71) Transparent UART : write = 8841 (RX module), notify = 1e4d (TX module)
        { u("49535343-fe7d-4ae5-8fa9-9fafd205e455"), u("49535343-8841-43f4-a8d4-ecbe34729bb3"), u("49535343-1e4d-4bd9-ba61-23c647249616") },
    };
    static final UUID CCCD = u("00002902-0000-1000-8000-00805f9b34fb");
    static final byte[] READ_CMD = "L\r\n".getBytes(StandardCharsets.US_ASCII);
    static final long FRAME_IDLE_MS = 500;
    static UUID u(String s) { return UUID.fromString(s); }

    TextView status, output, modeBadge;
    LinearLayout deviceBox, fieldsBox, writeBox, advancedBox;
    GaugeView e85Gauge, tempGauge;

    // Libellés des champs décodés (lettre -> nom lisible, unité, statut)
    static final String[][] FIELD_INFO = {
        // lettre, libellé, unité, statut
        { "O", "E85", " %", "confirmé" },
        { "T", "Température", " °C", "confirmé" },
        { "S", "Seuil départ à froid", " °C", "confirmé" },
        { "M", "Consigne de démarrage", "", "confirmé" },
        { "F", "Valeur mode froid", " %", "confirmé" },
        { "C", "Valeur mode chaud", " %", "confirmé" },
    };

    // Réglages modifiables : lettre, libellé, unité, min, max, pas (garde-fous appli, pas le boîtier)
    // Enrichissements bornés 0–50 % ; seuil froid laissé libre (plage du protocole, octet 0–255) ;
    // consigne de démarrage = 1 ou 2 (champ consStart).
    // Pas : le boîtier n'accepte les enrichissements F / C que par paliers de 5 % (constaté sur
    // boîtier réel) ; seuil et consigne au pas de 1.
    static final String[][] SETTINGS = {
        { "S", "Seuil départ à froid", "°C", "0",  "255", "1" },
        { "F", "Enrich. mode froid",   "%",  "0",  "50",  "5" },
        { "C", "Enrich. mode chaud",   "%",  "0",  "50",  "5" },
        { "M", "Consigne de démarrage", "",  "1",  "2",   "1" },
    };

    // Aide contextuelle (bouton « ? ») par lettre de réglage.
    static final String[][] HELP = {
        { "M", "• Consigne 1 (usine) : enrichissement à froid seul. Seuil conseillé 12–15 °C.\n\n"
             + "• Consigne 2 : pré-pulvérisation à la mise du contact + sur-enrichissement d'environ "
             + "80 % sur les 150 premières injections. Seuil conseillé 30 °C, alimentation +12 V après "
             + "contact obligatoire. À réserver aux véhicules qui démarrent très mal à froid ; un usage "
             + "abusif dilue le carburant dans l'huile moteur." },
    };
    static String helpFor(String letter) {
        for (String[] h : HELP) if (h[0].equals(letter)) return h[1];
        return null;
    }

    Button scanBtn, readBtn, watchBtn, shareBtn, writeBtn, restoreBtn, applyBtn, resetBtn, advancedToggle, rescanBtn;
    EditText letterInput, valueInput;
    CheckBox autoRead;
    android.widget.ViewFlipper flipper;
    TextView scanStatus;
    boolean connected = false;
    LinearLayout reglagesBox;
    final Map<String,Integer> staged = new LinkedHashMap<>();
    // Après un « Appliquer », on resynchronise les valeurs en attente sur la 1re relecture :
    // le boîtier peut avoir bougé d'autres champs tout seul (ex. écrire le seuil le fait passer
    // en consigne 2), sinon un ré-Appliquer réécrirait ces champs « périmés » → réglages en vrac.
    boolean resyncStaged = false;
    // Commande écrite dont on attend l'écho du boîtier (ex. "F045"). Le boîtier renvoie
    // la commande à l'identique si elle est acceptée ; un écho différent = non acceptée.
    String pendingEcho = null;
    final Map<String,TextView> stagedView = new HashMap<>();
    final Map<String,TextView> currentView = new HashMap<>();

    // Dernière photo lue (LETTRE -> valeur) et valeurs d'origine des champs écrits
    final Map<String,String> lastSnapshot = new LinkedHashMap<>();
    final Map<String,String> writtenOriginal = new LinkedHashMap<>();

    BluetoothAdapter adapter;
    BluetoothLeScanner scanner;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic writeChar, notifyChar;
    final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    final java.io.ByteArrayOutputStream frameBuf = new java.io.ByteArrayOutputStream();
    Runnable frameTimer;
    boolean watching = false;
    // Boucle de lecture auto tant qu'on est connecté (pas de bouton Lire)
    final Runnable pollLoop = new Runnable() {
        @Override public void run() {
            if (!connected) return;
            sendRead();
            ui.postDelayed(this, 1200);
        }
    };

    File logFile;
    final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // =====================================================================
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Garder l'écran allumé tant que l'appli est au premier plan (usage en roulant).
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        flipper = new android.widget.ViewFlipper(this);

        // ===== Écran 1 : sélection du boîtier =====
        LinearLayout scanScreen = new LinearLayout(this);
        scanScreen.setOrientation(LinearLayout.VERTICAL);
        scanScreen.addView(greenHeader());
        LinearLayout selBody = new LinearLayout(this);
        selBody.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14); selBody.setPadding(pad, pad, pad, pad);
        scanStatus = new TextView(this);
        scanStatus.setText("Recherche du boîtier…");
        scanStatus.setTextColor(0xFF6B6B6B); scanStatus.setTextSize(13);
        selBody.addView(scanStatus);
        rescanBtn = btn("Rescanner", v -> startScan());
        selBody.addView(rescanBtn);
        Button demoBtn = btn("👁  Aperçu de l'écran (démo, sans connexion)", v -> showDemo());
        selBody.addView(demoBtn);
        TextView hint = new TextView(this);
        hint.setText("Les boîtiers Biomotors apparaissent en vert.");
        hint.setTextColor(0xFF9A9A9A); hint.setTextSize(11);
        hint.setPadding(0, dp(8), 0, dp(4));
        selBody.addView(hint);
        deviceBox = new LinearLayout(this);
        deviceBox.setOrientation(LinearLayout.VERTICAL);
        selBody.addView(deviceBox);
        ScrollView selScroll = new ScrollView(this);
        selScroll.addView(selBody);
        scanScreen.addView(selScroll);

        // ===== Écran 2 : dashboard (connecté) =====
        LinearLayout dashScreen = new LinearLayout(this);
        dashScreen.setOrientation(LinearLayout.VERTICAL);
        dashScreen.addView(greenHeader());
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setText("Connecté.");
        status.setTextColor(0xFF6B6B6B); status.setTextSize(12);
        body.addView(status);

        LinearLayout gauges = new LinearLayout(this);
        gauges.setOrientation(LinearLayout.HORIZONTAL);
        gauges.setPadding(0, dp(6), 0, dp(4));
        e85Gauge = new GaugeView(this, GaugeView.ETHANOL);
        e85Gauge.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        tempGauge = new GaugeView(this, GaugeView.TEMP);
        tempGauge.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        gauges.addView(e85Gauge); gauges.addView(tempGauge);
        body.addView(gauges);

        modeBadge = new TextView(this);
        modeBadge.setText("Mode : —");
        modeBadge.setTextColor(0xFF2D5208); modeBadge.setTextSize(13);
        modeBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        modeBadge.setGravity(android.view.Gravity.CENTER);
        modeBadge.setPadding(dp(16), dp(6), dp(16), dp(6));
        modeBadge.setBackground(rounded(0xFFF3F7EC, dp(14)));
        LinearLayout badgeWrap = new LinearLayout(this);
        badgeWrap.setGravity(android.view.Gravity.CENTER);
        badgeWrap.setPadding(0, dp(2), 0, dp(10));
        badgeWrap.addView(modeBadge);
        body.addView(badgeWrap);

        buildReglagesPanel();
        body.addView(reglagesBox);

        // --- Section avancée (repliée) ------------------------------------
        advancedToggle = btn("⌄  Avancé  (journal · écriture manuelle)", v -> {
            boolean vis = advancedBox.getVisibility() == View.VISIBLE;
            advancedBox.setVisibility(vis ? View.GONE : View.VISIBLE);
            advancedToggle.setText((vis ? "⌄" : "⌃") + "  Avancé  (journal · écriture manuelle)");
        });
        body.addView(advancedToggle);

        advancedBox = new LinearLayout(this);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.setVisibility(View.GONE);

        shareBtn = btn("Partager log", v -> shareLog());
        advancedBox.addView(shareBtn);
        autoRead = new CheckBox(this);
        autoRead.setText("Journaliser tout (hex + ascii)");
        autoRead.setChecked(true);
        advancedBox.addView(autoRead);

        writeBox = new LinearLayout(this);
        writeBox.setOrientation(LinearLayout.VERTICAL);
        TextView wHdr = new TextView(this);
        wHdr.setText("Écriture manuelle (moteur à l'arrêt) — lettre + valeur");
        wHdr.setTextSize(11);
        writeBox.addView(wHdr);
        LinearLayout manualRow = new LinearLayout(this);
        letterInput = new EditText(this);
        letterInput.setHint("Lettre");
        letterInput.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.AllCaps(), new android.text.InputFilter.LengthFilter(1) });
        letterInput.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        valueInput = new EditText(this);
        valueInput.setHint("Valeur");
        valueInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        valueInput.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 2f));
        writeBtn = btn("Écrire…", v -> {
            String l = letterInput.getText().toString().trim();
            String val = valueInput.getText().toString().trim();
            if (l.length() != 1) { toast("Entre une lettre."); return; }
            confirmWrite(l, fmtLikeBox(l, val)); // (A) même format que le boîtier (C + 27 -> C027)
        });
        manualRow.addView(letterInput); manualRow.addView(valueInput); manualRow.addView(writeBtn);
        writeBox.addView(manualRow);
        restoreBtn = btn("Restaurer (valeurs d'origine)", v -> restore());
        writeBox.addView(restoreBtn);
        advancedBox.addView(writeBox);

        TextView fHdr = new TextView(this);
        fHdr.setText("Champs lus :");
        fHdr.setTextSize(11);
        advancedBox.addView(fHdr);
        fieldsBox = new LinearLayout(this);
        fieldsBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.addView(fieldsBox);

        output = new TextView(this);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setTextSize(11);
        advancedBox.addView(output);

        body.addView(advancedBox);

        Button disconnectBtn = btn("Déconnecter", v -> disconnectAndScan());
        body.addView(disconnectBtn);
        setWriteEnabled(false);

        ScrollView dashScroll = new ScrollView(this);
        dashScroll.addView(body);
        dashScreen.addView(dashScroll);

        flipper.addView(scanScreen);
        flipper.addView(dashScreen);
        setContentView(flipper);

        // Fichier journal dans Android/data/xyz.ylia.bioflex/files/ (accessible)
        try {
            logFile = new File(getExternalFilesDir(null),
                    "bioflex-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log");
            log("=== Open-Bioflex-E85 ===");
            log("appareil: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
            log("fichier: " + logFile.getAbsolutePath());
        } catch (Exception e) { toast("Log impossible: " + e); }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) { scanStatus.setText("Pas de Bluetooth."); rescanBtn.setEnabled(false); }
        else startScan();
    }

    // En-tête vert réutilisable
    LinearLayout greenHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setBackgroundColor(0xFF0B0B0B);
        h.setPadding(dp(14), dp(10), dp(14), dp(10));
        h.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        h.addView(logo);
        TextView t = new TextView(this);
        t.setText("Open-Bioflex-E85");
        t.setTextColor(0xFFFFFFFF); t.setTextSize(18);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(dp(10), 0, 0, 0);
        h.addView(t);
        return h;
    }
    void disconnectAndScan() {
        connected = false;
        ui.removeCallbacks(pollLoop);
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Exception ignored) {}
        gatt = null; writeChar = notifyChar = null;
        setWriteEnabled(false);
        if (flipper != null) flipper.setDisplayedChild(0);
        startScan();
    }
    // Aperçu de l'écran avec des données fictives, sans boîtier (écriture désactivée).
    void showDemo() {
        try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
        connected = false;
        ui.removeCallbacks(pollLoop);
        Map<String,String> demo = new LinkedHashMap<>();
        demo.put("M", "1"); demo.put("F", "040"); demo.put("C", "025");
        demo.put("O", "072"); demo.put("T", "+014"); demo.put("S", "+012");
        lastSnapshot.clear(); lastSnapshot.putAll(demo);
        staged.clear();
        setWriteEnabled(false);
        status.setText("Aperçu (démo) — données fictives, non connecté");
        updateLive(demo);
        updateReglagesCurrent(demo);
        if (flipper != null) flipper.setDisplayedChild(1);
    }
    boolean likelyBiomotors(String name, ScanResult r) {
        if (name != null) {
            String n = name.toUpperCase(Locale.US);
            for (String k : new String[]{ "BM71", "BIOFLEX", "ESPBIO", "BIOMOTORS", "MINEW" })
                if (n.contains(k)) return true;
        }
        if (r.getScanRecord() != null && r.getScanRecord().getServiceUuids() != null)
            for (android.os.ParcelUuid pu : r.getScanRecord().getServiceUuids()) {
                String u = pu.getUuid().toString().toLowerCase(Locale.US);
                if (u.startsWith("0000fff0") || u.startsWith("6e400001") || u.startsWith("49535343-fe7d")) return true;
            }
        return false;
    }

    // --- Permissions ------------------------------------------------------
    String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return new String[]{ Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT };
        return new String[]{ Manifest.permission.ACCESS_FINE_LOCATION };
    }
    boolean hasPerms() {
        for (String p : neededPerms())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (hasPerms()) startScan(); else status.setText("Permissions refusées.");
    }

    // --- Scan -------------------------------------------------------------
    void startScan() {
        if (!hasPerms()) { requestPermissions(neededPerms(), 1); return; }
        if (adapter == null || !adapter.isEnabled()) { scanStatus.setText("Active le Bluetooth."); return; }
        found.clear(); deviceBox.removeAllViews();
        scanner = adapter.getBluetoothLeScanner();
        scanStatus.setText("Recherche en cours…"); log("--- scan start");
        try {
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        } catch (SecurityException e) { log("scan SecurityException: " + e); return; }
        ui.postDelayed(() -> {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
            log("--- scan stop (" + found.size() + " appareils)");
            scanStatus.setText(found.size() + " appareils trouvés. Choisis ton boîtier (en vert).");
        }, 8000);
    }

    final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String name;
            try { name = d.getName(); } catch (SecurityException e) { name = null; }
            String addr = d.getAddress();
            if (found.containsKey(addr)) return;
            found.put(addr, d);
            final boolean likely = likelyBiomotors(name, r);
            String label = (name != null ? name : "(sans nom)") + "   " + addr + "   " + r.getRssi() + " dBm";
            log("trouvé: " + label + (likely ? " [Biomotors?]" : "") + "  services=" + servicesOf(r));
            ui.post(() -> {
                Button bb = btn((likely ? "●  " : "") + label, v -> connect(d));
                if (likely) { bb.setBackground(rounded(0xFFDDF0C8, dp(8))); bb.setTextColor(0xFF2D5208); }
                if (likely) deviceBox.addView(bb, 0); else deviceBox.addView(bb);
            });
        }
        @Override public void onScanFailed(int code) { log("scan échec code=" + code); }
    };
    String servicesOf(ScanResult r) {
        if (r.getScanRecord() == null || r.getScanRecord().getServiceUuids() == null) return "?";
        return r.getScanRecord().getServiceUuids().toString();
    }

    // --- Connexion --------------------------------------------------------
    void onReady() {
        connected = true;
        setWriteEnabled(true);
        status.setText("Connecté — lecture en continu.");
        if (flipper != null) flipper.setDisplayedChild(1);
        ui.removeCallbacks(pollLoop);
        ui.post(pollLoop);
    }

    void connect(BluetoothDevice d) {
        try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
        scanStatus.setText("Connexion à " + d.getAddress() + "…");
        log("--- connect " + d.getAddress());
        if (gatt != null) {
            log("fermeture de la connexion précédente");
            try { gatt.disconnect(); gatt.close(); } catch (SecurityException ignored) {}
            gatt = null; writeChar = notifyChar = null;
        }
        try { gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE); }
        catch (SecurityException e) { log("connect SecurityException: " + e); }
    }

    final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            log("connectionState status=" + st + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                ui.post(() -> status.setText("Connecté. Découverte des services…"));
                try { g.requestMtu(155); } catch (Exception e) { discover(g); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ui.post(() -> {
                    connected = false;
                    ui.removeCallbacks(pollLoop);
                    setWriteEnabled(false);
                    try { g.close(); } catch (Exception ignored) {}
                    if (flipper != null) {
                        flipper.setDisplayedChild(0);
                        scanStatus.setText("Connexion perdue — recherche du boîtier…");
                        startScan();
                    }
                });
            }
        }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) {
            log("mtu=" + mtu + " status=" + st); discover(g);
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            log("servicesDiscovered status=" + st);
            for (BluetoothGattService s : g.getServices()) {
                log("service " + s.getUuid());
                for (BluetoothGattCharacteristic c : s.getCharacteristics())
                    log("  char " + c.getUuid() + " props=0x" + Integer.toHexString(c.getProperties()));
            }
            pickChars(g);
            if (writeChar == null || notifyChar == null) {
                ui.post(() -> status.setText("Caractéristiques write/notify introuvables (voir log)."));
                log("!! write/notify introuvables");
                return;
            }
            log("write=" + writeChar.getUuid() + "  notify=" + notifyChar.getUuid());
            enableNotify(g);
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            log("descriptorWrite " + d.getUuid() + " status=" + st);
            ui.post(() -> onReady());
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            log("write ok status=" + st + " -> " + c.getUuid());
        }
        // API < 33
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= 33) return; // déjà reçu par la version à 3 arguments
            onNotify(c.getValue());
        }
        // API >= 33
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            onNotify(v);
        }
    };

    void discover(BluetoothGatt g) {
        try { g.discoverServices(); } catch (SecurityException e) { log("discover SecurityException: " + e); }
    }

    void pickChars(BluetoothGatt g) {
        writeChar = notifyChar = null;
        for (UUID[] m : MODULES) {
            BluetoothGattService s = g.getService(m[0]);
            if (s == null) continue;
            BluetoothGattCharacteristic w = s.getCharacteristic(m[1]);
            BluetoothGattCharacteristic n = s.getCharacteristic(m[2]);
            if (w != null && n != null) { writeChar = w; notifyChar = n; return; }
        }
        // repli : première write + première notify rencontrées
        for (BluetoothGattService s : g.getServices())
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int p = c.getProperties();
                if (writeChar == null && (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) writeChar = c;
                if (notifyChar == null && (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notifyChar = c;
            }
    }

    void enableNotify(BluetoothGatt g) {
        try {
            g.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor cccd = notifyChar.getDescriptor(CCCD);
            if (cccd != null) {
                byte[] on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, on);
                else { cccd.setValue(on); g.writeDescriptor(cccd); }
            } else {
                log("pas de CCCD sur notify");
                ui.post(() -> onReady());
            }
        } catch (SecurityException e) { log("notify SecurityException: " + e); }
    }

    // --- Lecture / trame --------------------------------------------------
    void sendRead() {
        if (gatt == null || writeChar == null) return;
        frameBuf.reset();
        log(">> write L\\r\\n  (" + hex(READ_CMD) + ")");
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int type = (writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                gatt.writeCharacteristic(writeChar, READ_CMD, type);
            } else {
                writeChar.setValue(READ_CMD);
                gatt.writeCharacteristic(writeChar);
            }
        } catch (SecurityException e) { log("write SecurityException: " + e); }
    }

    void onNotify(byte[] v) {
        if (v == null) return;
        log("<< notif  hex=" + hex(v) + "  ascii=" + ascii(v));
        frameBuf.write(v, 0, v.length);
        if (frameTimer != null) ui.removeCallbacks(frameTimer);
        frameTimer = this::finishFrame;
        ui.postDelayed(frameTimer, FRAME_IDLE_MS);
    }

    void finishFrame() {
        byte[] raw = frameBuf.toByteArray();
        frameBuf.reset(); // (B) chaque rafale de notifications = une trame distincte
        String text = new String(raw, StandardCharsets.US_ASCII).trim();
        log("== TRAME (" + raw.length + " o) ascii=" + text);
        Map<String,String> f = parse(text);
        log("== champs=" + f);
        // Vérification d'écriture : le boîtier renvoie la commande à l'identique si acceptée.
        if (pendingEcho != null) {
            boolean dataFrame = f.containsKey("O") && f.containsKey("T");
            if (text.equals(pendingEcho) || text.endsWith(pendingEcho)) {
                final String exp = pendingEcho; pendingEcho = null;
                ui.post(() -> { output.append("✓ réglage confirmé (" + exp + ")\n"); toast("✓ " + exp + " confirmé"); });
            } else if (!dataFrame) {   // écho court mais différent = non accepté tel quel
                final String exp = pendingEcho, got = text; pendingEcho = null;
                ui.post(() -> { output.append("✗ NON confirmé : envoyé " + exp + ", reçu " + got + "\n"); toast("⚠ Non confirmé : " + got); });
            }
            // sinon (trame de données sans l'écho) : on attend encore l'écho
        }
        // (B) Une trame de données complète contient toujours O (E85) ET T (température).
        // Sinon c'est l'écho d'une commande d'écriture (ex. 'C027', 'S18') : on le
        // journalise mais on n'écrase NI le snapshot NI l'affichage (sinon champs vidés).
        if (!(f.containsKey("O") && f.containsKey("T"))) {
            log("   (écho / trame partielle — ignoré pour l'affichage)");
            final String echo = text;
            ui.post(() -> output.append("écho : " + echo + "\n"));
            return;
        }
        String e85 = f.get("O");
        lastSnapshot.clear(); lastSnapshot.putAll(f);
        StringBuilder sb = new StringBuilder();
        sb.append("Trame : ").append(text).append("\n");
        sb.append("Champs : ").append(f).append("\n");
        sb.append(">>> E85 ≈ ").append(pretty(e85)).append(" %").append("\n\n");
        ui.post(() -> {
            status.setText("E85 ≈ " + pretty(e85) + " %");
            updateLive(f);
            output.append(sb.toString());
            rebuildFields(f);
        });
        if (watching) ui.postDelayed(this::sendRead, 1000);
    }

    // Jauges + badge mode, mis à jour à chaque lecture.
    void updateLive(Map<String,String> f) {
        String o = f.get("O");
        if (o != null) e85Gauge.showEthanol(i(pretty(o)));
        String t = f.get("T"), sStr = f.get("S");
        Integer seuil = (sStr == null) ? null : i(pretty(sStr));
        if (t != null) tempGauge.showTemp(i(pretty(t)), seuil);

        // Le mode froid/chaud n'est pas transmis par le boîtier (champ « mode » = null ;
        // « M » = consigne de démarrage, constante) : on le déduit de T vs le seuil S.
        if (t != null && sStr != null) {
            boolean cold = i(pretty(t)) < i(pretty(sStr));
            modeBadge.setText(cold ? "À froid" : "À chaud");
            modeBadge.setTextColor(0xFFFFFFFF);
            modeBadge.setBackground(rounded(cold ? 0xFF378ADD : 0xFFE89A2B, dp(16)));
        } else {
            modeBadge.setText("Mode : —");
            modeBadge.setTextColor(0xFF9A9A9A);
            modeBadge.setBackground(rounded(0xFFF0F0F0, dp(16)));
        }

        updateReglagesCurrent(f);
    }
    static int i(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
    android.graphics.drawable.GradientDrawable rounded(int color, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color); g.setCornerRadius(radius); return g;
    }

    // Jauge demi-cercle façon Biomotors : zones colorées + aiguille + grand chiffre.
    //  ETHANOL : zones SP / MIXTE / E85.   TEMP : bleu (froid) / rouge (chaud) coupés au seuil.
    class GaugeView extends View {
        static final int ETHANOL = 1, TEMP = 2;
        final int kind;
        boolean hasData = false;
        float frac = 0f;        // position de l'aiguille, 0..1
        float seuilFrac = -1f;  // TEMP : position du seuil, 0..1 (<0 = inconnu)
        int seuilC = -1;        // TEMP : valeur du seuil en °C (<0 = inconnu)
        String big = "—", lab;
        final Paint pZone = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint pNeedle = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint pTick = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint pBig = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint pLab = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint pZl = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint pSeuil = new Paint(Paint.ANTI_ALIAS_FLAG);
        GaugeView(Context c, int kind) {
            super(c);
            this.kind = kind;
            lab = (kind == ETHANOL) ? "ÉTHANOL" : "TEMPÉRATURE";
            float d = getResources().getDisplayMetrics().density;
            pZone.setStyle(Paint.Style.STROKE); pZone.setStrokeWidth(17 * d); pZone.setStrokeCap(Paint.Cap.BUTT);
            pNeedle.setColor(0xFF2B2B2B); pNeedle.setStrokeWidth(3.5f * d); pNeedle.setStrokeCap(Paint.Cap.ROUND);
            pTick.setColor(0xFF1F1F1F); pTick.setStrokeWidth(2f * d);
            pBig.setColor(0xFF1F1F1F); pBig.setTextAlign(Paint.Align.CENTER); pBig.setFakeBoldText(true); pBig.setTextSize(27 * d);
            pLab.setColor(0xFF4A4A4A); pLab.setTextAlign(Paint.Align.CENTER); pLab.setFakeBoldText(true); pLab.setTextSize(12 * d);
            pZl.setColor(0xFFFFFFFF); pZl.setTextAlign(Paint.Align.CENTER); pZl.setFakeBoldText(true); pZl.setTextSize(9.5f * d);
            pSeuil.setColor(0xFF185FA5); pSeuil.setTextAlign(Paint.Align.CENTER); pSeuil.setFakeBoldText(true); pSeuil.setTextSize(9f * d);
        }
        void showEthanol(int pct) { frac = clamp(pct / 100f); big = pct + "%"; hasData = true; postInvalidate(); }
        void showTemp(int tC, Integer seuil) {
            frac = clamp(tC / 100f);                             // plage d'affichage 0–100 °C
            seuilFrac = (seuil == null) ? -1f : clamp(seuil / 100f);
            seuilC = (seuil == null) ? -1 : seuil;
            big = tC + "°C"; hasData = true; postInvalidate();
        }
        float clamp(float x) { return Math.max(0f, Math.min(1f, x)); }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), (int) (165 * getResources().getDisplayMetrics().density));
        }
        void seg(Canvas cv, RectF r, float a, float b, int color) {
            pZone.setColor(color);
            cv.drawArc(r, 180 + 180 * a, 180 * (b - a), false, pZone);
        }
        float px(float cx, float r, float f) { return cx + (float) (r * Math.cos(Math.toRadians(180 + 180 * f))); }
        float py(float cy, float r, float f) { return cy + (float) (r * Math.sin(Math.toRadians(180 + 180 * f))); }
        @Override protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = 110 * d, r = Math.min(getWidth() / 2f - 20 * d, 74 * d);
            RectF rect = new RectF(cx - r, cy - r, cx + r, cy + r);
            if (kind == ETHANOL) {
                seg(cv, rect, 0f, 0.30f, 0xFF2E7D32);   // SP
                seg(cv, rect, 0.30f, 0.60f, 0xFF8BC34A); // MIXTE
                seg(cv, rect, 0.60f, 1f, 0xFF378ADD);    // E85
                cv.drawText("SP", px(cx, r, 0.10f), py(cy, r, 0.10f) + 3 * d, pZl);
                cv.drawText("MIXTE", px(cx, r, 0.45f), py(cy, r, 0.45f) + 3 * d, pZl);
                cv.drawText("E85", px(cx, r, 0.83f), py(cy, r, 0.83f) + 3 * d, pZl);
            } else {
                float sp = (seuilFrac < 0) ? 0.30f : seuilFrac;
                seg(cv, rect, 0f, sp, 0xFF378ADD);       // froid
                seg(cv, rect, sp, 1f, 0xFFE24B4A);       // chaud
                if (seuilFrac >= 0) {
                    cv.drawLine(px(cx, r - 11 * d, sp), py(cy, r - 11 * d, sp),
                                px(cx, r + 11 * d, sp), py(cy, r + 11 * d, sp), pTick);
                    if (seuilC >= 0) { // valeur du seuil, écrite entre le bleu et le rouge
                        float sx = px(cx, r + 18 * d, sp), sy = py(cy, r + 18 * d, sp) + 3 * d;
                        float half = 14 * d;   // garde le texte dans les bords
                        sx = Math.max(half, Math.min(getWidth() - half, sx));
                        cv.drawText(seuilC + "°", sx, sy, pSeuil);
                    }
                }
            }
            if (hasData) {
                cv.drawLine(cx, cy, px(cx, r * 0.82f, frac), py(cy, r * 0.82f, frac), pNeedle);
                cv.drawCircle(cx, cy, 5 * d, pNeedle);
            }
            cv.drawText(big, cx, cy - 8 * d, pBig);
            cv.drawText(lab, cx, cy + 22 * d, pLab);
        }
    }

    // --- Panneau réglages (± sur S/F/C, plages de sécurité) ---------------
    void buildReglagesPanel() {
        reglagesBox = new LinearLayout(this);
        reglagesBox.setOrientation(LinearLayout.VERTICAL);
        TextView hdr = new TextView(this);
        hdr.setText("\nRÉGLAGES (moteur à l'arrêt) — ± par appui, puis Appliquer");
        hdr.setTextSize(12);
        reglagesBox.addView(hdr);
        for (String[] s : SETTINGS) {
            final String letter = s[0];
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            rowL.setGravity(android.view.Gravity.CENTER_VERTICAL);
            rowL.setPadding(0, dp(6), 0, dp(6));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout labRow = new LinearLayout(this);
            labRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            labRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); // pleine largeur de la colonne
            TextView lab = new TextView(this); lab.setText(s[1]); lab.setTextSize(14);
            // poids 1 : le libellé prend la place restante et laisse le "?" visible à sa droite
            lab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            labRow.addView(lab);
            final String help = helpFor(letter);
            if (help != null) {
                TextView q = new TextView(this);
                q.setText("?"); q.setTextSize(12); q.setTextColor(0xFF3B6D11);
                q.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                q.setGravity(android.view.Gravity.CENTER);
                q.setBackground(rounded(0xFFEEF4E4, dp(11)));
                LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(22), dp(22));
                qlp.leftMargin = dp(8);
                q.setLayoutParams(qlp);
                final String title = s[1];
                q.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle(title).setMessage(help)
                        .setPositiveButton("OK", null).show());
                labRow.addView(q);
            }
            TextView cur = new TextView(this); cur.setText("actuel : —"); cur.setTextSize(11);
            cur.setTextColor(0xFF9A9A9A);
            col.addView(labRow); col.addView(cur);
            currentView.put(letter, cur);
            rowL.addView(col);
            Button minus = btn("−", v -> stepStaged(letter, -1)); minus.setTextSize(18);
            TextView val = new TextView(this); val.setText("—"); val.setTextSize(18);
            val.setGravity(android.view.Gravity.CENTER); val.setMinWidth(dp(60));
            stagedView.put(letter, val);
            Button plus = btn("+", v -> stepStaged(letter, +1)); plus.setTextSize(18);
            rowL.addView(minus); rowL.addView(val); rowL.addView(plus);
            reglagesBox.addView(rowL);
        }
        LinearLayout actions = new LinearLayout(this);
        applyBtn = btn("Appliquer", v -> applyReglages());
        applyBtn.setTextSize(19);
        applyBtn.setPadding(0, dp(14), 0, dp(14));
        applyBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); // pleine largeur
        actions.addView(applyBtn);
        reglagesBox.addView(actions);
    }

    int[] rangeOf(String letter) {
        for (String[] s : SETTINGS) if (s[0].equals(letter))
            return new int[]{ Integer.parseInt(s[3]), Integer.parseInt(s[4]) };
        return new int[]{ 0, 100 };
    }
    Integer snapInt(String letter) {
        String v = lastSnapshot.get(letter);
        if (v == null) return null;
        try { return Integer.parseInt(v.replace("+", "").replace("-", "").trim()); }
        catch (Exception e) { return null; }
    }
    String unit(String letter) {
        for (String[] s : SETTINGS) if (s[0].equals(letter)) return s[2];
        return "";
    }
    void stepStaged(String letter, int delta) {
        Integer cur = staged.get(letter);
        if (cur == null) cur = snapInt(letter);
        if (cur == null) { toast("Lis d'abord le boîtier."); return; }
        int[] r = rangeOf(letter);
        staged.put(letter, Math.max(r[0], Math.min(r[1], nextStep(cur, stepOf(letter), delta))));
        refreshStaged(letter);
    }
    int stepOf(String letter) {
        for (String[] s : SETTINGS) if (s[0].equals(letter)) return Integer.parseInt(s[5]);
        return 1;
    }
    // Palier suivant (delta > 0) ou précédent (delta < 0) : toujours un multiple du pas, même en
    // partant d'une valeur lue hors palier (ex. 37 → 40 au « + », 35 au « − »).
    static int nextStep(int cur, int step, int delta) {
        if (step <= 1) return cur + delta;
        return delta > 0 ? (Math.floorDiv(cur, step) + 1) * step
                         : (Math.floorDiv(cur + step - 1, step) - 1) * step;
    }
    void refreshStaged(String letter) {
        TextView v = stagedView.get(letter);
        Integer st = staged.get(letter);
        if (v == null || st == null) return;
        v.setText(String.valueOf(st));
        Integer snap = snapInt(letter);
        v.setTextColor(snap != null && !snap.equals(st) ? 0xFF4E7A16 : 0xFF2B2B2B);
    }
    void updateReglagesCurrent(Map<String,String> f) {
        // Relecture après « Appliquer » : on repart de l'état réel du boîtier (évite de
        // réécrire des champs que le boîtier a modifiés lui-même, ex. la consigne).
        if (resyncStaged) { staged.clear(); resyncStaged = false; }
        for (String[] s : SETTINGS) {
            String letter = s[0];
            String raw = f.get(letter);
            TextView cur = currentView.get(letter);
            if (cur != null) cur.setText("actuel : " + (raw == null ? "—" : pretty(raw) + " " + s[2]));
            if (!staged.containsKey(letter)) {
                Integer sv = snapInt(letter);
                if (sv != null) { staged.put(letter, sv); }
            }
            refreshStaged(letter);
        }
    }
    // Format d'écriture par champ, CONFIRMÉ par capture HCI de l'appli officielle (handle 0x52) :
    //   S (seuil) et M (consigne) : entier brut (ex. S15, M1) ;
    //   F / C (enrichissements froid / chaud) : 3 chiffres zéro-paddés (ex. F045, C030).
    // La largeur acceptée dépend du champ (le boîtier lit mal un F/C non paddé).
    static String writeFormat(String letter, int v) {
        if (letter.equals("F") || letter.equals("C")) return String.format(Locale.US, "%03d", v);
        return String.valueOf(v);
    }
    // Outil « Écrire… » manuel : applique le format du champ si la saisie est un entier.
    String fmtLikeBox(String letter, String val) {
        try { return writeFormat(letter, Integer.parseInt(val.replace("+", "").trim())); }
        catch (Exception ignored) { return val; }
    }
    void applyReglages() {
        if (gatt == null || writeChar == null) { toast("Non connecté."); return; }
        List<String> changes = new ArrayList<>();
        final List<Map.Entry<String,String>> toWrite = new ArrayList<>();
        for (String[] s : SETTINGS) {
            String letter = s[0];
            Integer st = staged.get(letter), snap = snapInt(letter);
            if (st == null || snap == null || st.equals(snap)) continue;
            // Format par champ confirmé (capture HCI de l'appli officielle) : S/M bruts
            // (S15, M1), F/C sur 3 chiffres (F045, C030). Voir writeFormat().
            String val = writeFormat(letter, st);
            toWrite.add(new AbstractMap.SimpleEntry<>(letter, val));
            changes.add(s[1] + " : " + snap + " → " + st + " " + s[2]);
        }
        if (toWrite.isEmpty()) { toast("Aucune modification."); return; }
        new AlertDialog.Builder(this)
                .setTitle("Appliquer ces réglages ?")
                .setMessage("⚠ Moteur à l'arrêt !\n\n" + join(changes)
                        + "\n\nL'original est mémorisé → Restaurer.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Appliquer", (d, w) -> applyStep(toWrite, 0))
                .show();
    }
    void applyStep(List<Map.Entry<String,String>> items, int i) {
        if (i >= items.size()) { resyncStaged = true; ui.postDelayed(this::sendRead, 900); toast("Réglages envoyés."); return; }
        Map.Entry<String,String> e = items.get(i);
        String old = lastSnapshot.get(e.getKey());
        if (old != null) writtenOriginal.putIfAbsent(e.getKey(), old);
        pendingEcho = e.getKey() + e.getValue();   // on attend cet écho du boîtier
        byte[] cmd = (pendingEcho + "\r\n").getBytes(StandardCharsets.US_ASCII);
        log(">> SET " + e.getKey() + e.getValue() + "  (avant: " + old + ")");
        ui.post(() -> output.append("SET " + e.getKey() + e.getValue() + " (avant " + old + ")\n"));
        try {
            if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(writeChar, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            else { writeChar.setValue(cmd); gatt.writeCharacteristic(writeChar); }
        } catch (SecurityException ex) { log("apply SecurityException: " + ex); }
        ui.postDelayed(() -> applyStep(items, i + 1), 800); // (B) > idle trame, pas de collision d'échos
    }
    void doReset() {
        new AlertDialog.Builder(this)
                .setTitle("Réinitialiser le boîtier ?")
                .setMessage("Remet TOUS les réglages par défaut (commande Z). Difficilement réversible.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Réinitialiser", (d, w) -> writeToken("Z", ""))
                .show();
    }
    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append("\n"); sb.append(l.get(i)); }
        return sb.toString();
    }

    // Reconstruit la liste des champs : chaque ligne réécrit sa valeur ACTUELLE (test sans changement)
    void rebuildFields(Map<String,String> f) {
        fieldsBox.removeAllViews();
        for (Map.Entry<String,String> e : f.entrySet()) {
            final String letter = e.getKey(), value = e.getValue();
            Button b = btn(letter + " = " + value + "   [réécrire =]", v -> confirmWrite(letter, value));
            b.setTextSize(12);
            fieldsBox.addView(b);
        }
    }

    // Format CONFIRME (log reel) : trame = <LETTRE><valeur signee>, p.ex.
    //   SM1F040C030O072T+019S+012  ->  M=1 F=040 C=030 O=072 T=+019 S=+012
    // Le 'S' de tete (suivi d'une lettre, sans chiffre) est un marqueur de debut, ignore.
    static final Pattern TOKEN = Pattern.compile("([A-Z])([+-]?\\d+(?:[.,]\\d+)?)");
    Map<String,String> parse(String text) {
        Map<String,String> f = new LinkedHashMap<>();
        Matcher m = TOKEN.matcher(text);
        // putIfAbsent = on garde la 1re occurrence : si l'écho d'une écriture (ex. "S01")
        // s'est collé à la fin d'une trame de lecture, la vraie valeur (en tête) l'emporte.
        while (m.find()) f.putIfAbsent(m.group(1), m.group(2).replace(',', '.'));
        return f;
    }
    // Affichage lisible d'une valeur signee/zero-paddee (072 -> 72, +019 -> 19)
    static String pretty(String v) {
        try { return String.valueOf(Integer.parseInt(v.replace("+", ""))); }
        catch (Exception e) { return v; }
    }

    void toggleWatch() {
        watching = !watching;
        watchBtn.setText(watching ? "Stop" : "Boucle");
        if (watching) sendRead();
    }

    // --- Écriture sécurisée ----------------------------------------------
    void setWriteEnabled(boolean on) {
        if (writeBtn != null) writeBtn.setEnabled(on);
        if (restoreBtn != null) restoreBtn.setEnabled(on);
        if (applyBtn != null) applyBtn.setEnabled(on);
        if (resetBtn != null) resetBtn.setEnabled(on);
        if (letterInput != null) letterInput.setEnabled(on);
        if (valueInput != null) valueInput.setEnabled(on);
    }

    // Demande confirmation avant toute écriture, en montrant l'ancienne et la nouvelle valeur.
    void confirmWrite(String letter, String value) {
        final String old = lastSnapshot.get(letter);
        boolean identique = old != null && old.equals(value);
        String msg = "Commande : " + letter + value + "\\r\\n\n\n"
                + "Champ « " + letter + " »\n"
                + "Valeur actuelle : " + (old == null ? "(inconnue)" : old) + "\n"
                + "Valeur écrite : " + value + "\n\n"
                + (identique ? "Test SANS changement (même valeur)." : "⚠ Change la valeur. Moteur à l'arrêt !")
                + "\nL'original est mémorisé → bouton Restaurer.";
        new AlertDialog.Builder(this)
                .setTitle(identique ? "Test écriture (identique)" : "Écrire dans le boîtier ?")
                .setMessage(msg)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Écrire", (d, w) -> {
                    if (old != null) writtenOriginal.putIfAbsent(letter, old);
                    writeToken(letter, value);
                })
                .show();
    }

    // Construit <lettre><valeur>\r\n et l'envoie ; relit la trame ensuite.
    void writeToken(String letter, String value) {
        if (gatt == null || writeChar == null) { toast("Non connecté."); return; }
        byte[] cmd = (letter + value + "\r\n").getBytes(StandardCharsets.US_ASCII);
        String old = lastSnapshot.get(letter);
        log(">> WRITE " + letter + value + "  (avant: " + letter + "=" + old + ")  hex=" + hex(cmd));
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int type = (writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                gatt.writeCharacteristic(writeChar, cmd, type);
            } else {
                writeChar.setValue(cmd);
                gatt.writeCharacteristic(writeChar);
            }
            ui.post(() -> output.append("ÉCRIT : " + letter + value + " (avant " + letter + "=" + old + ")\n"));
        } catch (SecurityException e) { log("write SecurityException: " + e); }
        ui.postDelayed(this::sendRead, 900); // relit pour vérifier l'effet
    }

    // Réécrit, une par une, les valeurs d'origine des champs modifiés cette session.
    void restore() {
        if (writtenOriginal.isEmpty()) { toast("Rien à restaurer."); return; }
        new AlertDialog.Builder(this)
                .setTitle("Restaurer ?")
                .setMessage("Réécrire les valeurs d'origine de : " + writtenOriginal.keySet())
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Restaurer", (d, w) -> {
                    log("-- RESTORE " + writtenOriginal);
                    List<Map.Entry<String,String>> items = new ArrayList<>(writtenOriginal.entrySet());
                    restoreStep(items, 0);
                })
                .show();
    }
    void restoreStep(List<Map.Entry<String,String>> items, int i) {
        if (i >= items.size()) { ui.postDelayed(this::sendRead, 900); toast("Restauration envoyée."); return; }
        Map.Entry<String,String> e = items.get(i);
        byte[] cmd = (e.getKey() + e.getValue() + "\r\n").getBytes(StandardCharsets.US_ASCII);
        log(">> RESTORE " + e.getKey() + e.getValue());
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(writeChar, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else { writeChar.setValue(cmd); gatt.writeCharacteristic(writeChar); }
        } catch (SecurityException ex) { log("restore SecurityException: " + ex); }
        ui.postDelayed(() -> restoreStep(items, i + 1), 400);
    }

    // --- Journal ----------------------------------------------------------
    synchronized void log(String line) {
        String s = ts.format(new Date()) + "  " + line;
        android.util.Log.d("Bioflex", s);
        if (output != null && autoRead != null && autoRead.isChecked())
            ui.post(() -> output.append(s + "\n"));
        if (logFile != null) {
            try (FileWriter w = new FileWriter(logFile, true)) { w.write(s + "\n"); }
            catch (Exception ignored) {}
        }
    }
    void shareLog() {
        if (logFile == null || !logFile.exists()) { toast("Pas de log."); return; }
        // Copie le log dans Téléchargements (MediaStore) puis le partage.
        try {
            byte[] data = readAll(logFile);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, logFile.getName());
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(data); }
                toast("Enregistré dans Téléchargements : " + logFile.getName());
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Partager le log"));
            } else {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, new String(data, StandardCharsets.UTF_8));
                startActivity(Intent.createChooser(i, "Partager le log"));
            }
        } catch (Exception e) {
            toast("Chemin: " + logFile.getAbsolutePath());
            log("share impossible: " + e);
        }
    }
    static byte[] readAll(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    // --- utilitaires ------------------------------------------------------
    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02X ", x));
        return s.toString().trim();
    }
    static String ascii(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append((x >= 0x20 && x < 0x7f) ? (char) x : '.');
        return s.toString();
    }
    Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setOnClickListener(l); return b;
    }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
