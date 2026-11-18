package cz.svatjir.wingmonitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Personal monitor-mix controller for one Aux mix on a Behringer WING. */
public class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(16, 19, 25);
    private static final int SURFACE = Color.rgb(28, 33, 41);
    private static final int SURFACE_LIGHT = Color.rgb(38, 45, 54);
    private static final int TEXT = Color.rgb(242, 245, 248);
    private static final int MUTED_TEXT = Color.rgb(151, 162, 174);
    private static final int ACCENT = Color.rgb(98, 214, 163);
    private static final int DANGER = Color.rgb(183, 68, 74);
    private static final int MIX_COUNT = WingNativeClient.MIX_BUS_COUNT;
    private static final int WING_CHANNELS = WingNativeClient.MAX_STAGE_CHANNELS;
    private static final int MIN_DB = -60;
    private static final int MAX_DB = 10;
    private static final float WIRE_OFF_DB = -144.0f;
    private static final int REQUEST_BLE_PERMISSIONS = 41;
    private static final int REQUEST_ENABLE_BLUETOOTH = 42;
    private static final int SCHEMA_VERSION = 2;

    /** WING-Q system colours 1..18; index 0 means no colour. */
    private static final int[] WING_COLORS = {
            Color.rgb(70, 76, 84),
            Color.rgb(62, 99, 204), Color.rgb(1, 128, 255), Color.rgb(90, 51, 255),
            Color.rgb(0, 206, 209), Color.rgb(1, 178, 62), Color.rgb(150, 204, 0),
            Color.rgb(242, 221, 0), Color.rgb(192, 106, 31), Color.rgb(224, 32, 64),
            Color.rgb(255, 122, 122), Color.rgb(255, 51, 246), Color.rgb(165, 51, 255),
            Color.rgb(255, 184, 26), Color.rgb(37, 195, 255), Color.rgb(255, 90, 48),
            Color.rgb(51, 230, 165), Color.rgb(112, 112, 112), Color.rgb(224, 224, 224)
    };
    private static final String[] WING_COLOR_NAMES = {
            "Bez barvy", "Modrá", "Světle modrá", "Fialová", "Tyrkysová", "Zelená", "Limetková",
            "Žlutá", "Hnědooranžová", "Červená", "Růžová", "Purpurová", "Šeříková", "Jantarová",
            "Azurová", "Oranžová", "Mátová", "Šedá", "Světle šedá"
    };
    private static final Pattern DRUM_NAME = Pattern.compile(
            "\\b(kick|kopák|kopak|bd|snare|sn|virbl|tom\\d*|floor|hi-?hat|hh|ride|crash|oh|ovh"
                    + "|overhead|drums?|bicí|bici)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final class Instrument {
        final String id;
        final String name;

        Instrument(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Group {
        final String id;
        final String name;
        final boolean byColor;
        final Instrument[] instruments;

        Group(String id, String name, boolean byColor, Instrument... instruments) {
            this.id = id;
            this.name = name;
            this.byColor = byColor;
            this.instruments = instruments;
        }
    }

    private static Instrument inst(String id, String name) {
        return new Instrument(id, name);
    }

    private static final int DRUM_GROUP = 0;
    private static final String MASTER_KEY = "master";
    private static final Group[] GROUPS = {
            new Group("bici", "Bicí", true),
            new Group("zeste", "Žestě", false,
                    inst("zeste.trumpeta", "Trumpeta"), inst("zeste.pozoun", "Pozoun"),
                    inst("zeste.tuba", "Tuba")),
            new Group("zpevy", "Zpěvy", false,
                    inst("zpevy.1", "Zpěv 1"), inst("zpevy.2", "Zpěv 2"),
                    inst("zpevy.3", "Zpěv 3")),
            new Group("basa", "Basa", false, inst("basa", "Basa")),
            new Group("rytmika", "Rytmika", false,
                    inst("rytmika.ekytara", "Elektrická kytara"),
                    inst("rytmika.akytara", "Akustická kytara"),
                    inst("rytmika.kytara2", "Kytara 2"), inst("rytmika.piano", "Piáno"),
                    inst("rytmika.harmonika", "Harmonika"), inst("rytmika.ukulele", "Ukulele")),
            new Group("housle", "Housle", false, inst("housle", "Housle"))
    };

    /** Buttons on one page that can be reordered by dragging with a finger. */
    private static final class ReorderList {
        final String prefKey;
        final List<View> views = new ArrayList<>();
        final List<String> keys = new ArrayList<>();

        ReorderList(String prefKey) {
            this.prefKey = prefKey;
        }
    }

    private interface LevelChanger {
        void change(int delta);
    }

    private enum Page { BUSES, GROUPS, CHANNELS, LEVEL, MASTER, ASSIGN, DRUM_COLOR, ACTIVE, TUNING }

    /** Per-instrument sensitivity and automation, resolved per console channel. */
    private static final class AutoSettings {
        int threshold;
        boolean ownThreshold;
        boolean gate;
        boolean clip;
    }

    private static final int MIN_THRESHOLD_DBFS = -60;
    private static final int MAX_THRESHOLD_DBFS = -10;
    private static final long GATE_HOLD_MS = 1500L;
    private static final float CLIP_DBFS = -1.0f;
    private static final int CLIP_STEP_DB = 3;
    private static final int CLIP_MAX_DB = 12;
    private static final long CLIP_INTERVAL_MS = 2000L;
    private static final long CLIP_UNDO_GRACE_MS = 30000L;
    private static final long METER_STALE_MS = 1200L;

    private final float[][] sendLevelDb = new float[MIX_COUNT][WING_CHANNELS + 1];
    private final boolean[][] sendOn = new boolean[MIX_COUNT][WING_CHANNELS + 1];
    private final float[] masterDb = new float[MIX_COUNT];
    private final boolean[] masterMuted = new boolean[MIX_COUNT];
    private final String[] channelName = new String[WING_CHANNELS + 1];
    private final String[] channelActName = new String[WING_CHANNELS + 1];
    private final int[] channelColor = new int[WING_CHANNELS + 1];
    private final int[] channelActColor = new int[WING_CHANNELS + 1];
    private final String[] busName = new String[MIX_COUNT + 1];
    private final String[] busActName = new String[MIX_COUNT + 1];
    private final Map<String, Integer> assignments = new HashMap<>();
    private final float[] inputMeterDbfs = new float[WING_CHANNELS + 1];
    private final float[] inputPeakDbfs = new float[WING_CHANNELS + 1];
    private final boolean[] inputActive = new boolean[WING_CHANNELS + 1];
    private final long[] firstAboveAt = new long[WING_CHANNELS + 1];
    private final long[] lastAboveAt = new long[WING_CHANNELS + 1];
    // Automation state, always for the currently selected Aux.
    private final boolean[] gateClosed = new boolean[WING_CHANNELS + 1];
    private final long[] gateLastAbove = new long[WING_CHANNELS + 1];
    private final boolean[] autoMutedOnConsole = new boolean[WING_CHANNELS + 1];
    private final float[] clipReductionDb = new float[WING_CHANNELS + 1];
    private final long[] lastClipActionAt = new long[WING_CHANNELS + 1];
    private final long[] clipGraceUntil = new long[WING_CHANNELS + 1];
    private AutoSettings[] channelSettingsCache;
    private long lastWingMeterAt;
    private long lastMeterUiAt;
    private ProgressBar tuningMeterBar;
    private TextView tuningMeterLabel;
    private final Runnable meterWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!metersFresh() && anyAutoMuteActive()) {
                openAllGates();
                Toast.makeText(MainActivity.this,
                        "Úrovně vstupů nepřichází – automatické ztlumení uvolněno",
                        Toast.LENGTH_LONG).show();
            }
            pedalHandler.postDelayed(this, 500);
        }
    };

    private Page page = Page.GROUPS;
    private int selectedBus;
    private int selectedGroup = DRUM_GROUP;
    private int selectedSlot = -1;
    private int selectedWingCh;
    private boolean firstSetup;
    private boolean busSetup;
    private Page levelReturnPage = Page.GROUPS;
    private Page activeReturnPage = Page.LEVEL;
    private Page assignReturnPage = Page.LEVEL;
    private Page drumColorReturnPage = Page.CHANNELS;
    private int drumColorSetting;
    private int activeThresholdDbfs = -45;
    private long lastInputMessageAt;
    private boolean meterBridgeSeen;
    private boolean simulatedInputPlaying;
    private String simulatedSongName = "";
    private static volatile MainActivity foregroundActivity;
    private final Map<String, ProgressBar> activeMeterBars = new HashMap<>();
    private final Map<String, TextView> activeMeterLabels = new HashMap<>();

    private LinearLayout content;
    private ScrollView pageScroll;
    private TextView busBadge;
    private TextView mixerBadge;
    private TextView pedalBadge;
    private TextView levelValue;
    private final ArrayList<View> menuItems = new ArrayList<>();
    private final Map<String, ClipDrawable> menuMeterClips = new HashMap<>();
    private boolean centerLongActionDone;
    private final Handler pedalHandler = new Handler(Looper.getMainLooper());
    private BluetoothFootPedalServer pedalServer;
    private WingNativeClient wingClient;
    private boolean wingConnected;
    private String wingHost = "";
    private String wingConsoleName = "WING";
    private long lastLocalMixWriteAt;
    private boolean mixerRefreshPending;
    private boolean mixerStructureChanged;
    private boolean mixerBusValuesChanged;
    private boolean mixerTargetValueChanged;
    private boolean mixerTargetStateChanged;
    private final Runnable mixerRefresh = this::runMixerRefresh;
    private Runnable centerHoldAction;
    private final Runnable[] directionRepeat = new Runnable[4];
    private final boolean[] pedalButtonsPressed = new boolean[4];
    private boolean keyboardCenterDown;
    private Runnable keyboardCenterLongAction;
    private boolean dragging;
    private ReorderList dragList;
    private View dragView;
    private int dragIndex;
    private int dragTarget;
    private int[] dragTops;
    private int[] dragSlots;
    private float dragStartRawY;
    private int dragStartScroll;
    private float lastTouchRawY;
    private final Runnable dragAutoScroll = new Runnable() {
        @Override
        public void run() {
            if (!dragging || pageScroll == null) return;
            int[] location = new int[2];
            pageScroll.getLocationOnScreen(location);
            int edge = dp(80);
            int step = 0;
            if (lastTouchRawY < location[1] + edge) {
                step = -dp(12);
            } else if (lastTouchRawY > location[1] + pageScroll.getHeight() - edge) {
                step = dp(12);
            }
            if (step != 0) {
                int before = pageScroll.getScrollY();
                pageScroll.scrollBy(0, step);
                if (pageScroll.getScrollY() != before) updateDrag();
            }
            pedalHandler.postDelayed(this, 16);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // A stage control must remain visible while the phone is in use.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        SharedPreferences preferences = prefs();
        migratePreferences(preferences);
        firstSetup = !preferences.getBoolean("primary_set", false);
        busSetup = !preferences.getBoolean("selected_bus_set", false);
        selectedBus = clamp(preferences.getInt("selected_bus", 0), 0, MIX_COUNT - 1);
        selectedGroup = clamp(preferences.getInt("primary_group", DRUM_GROUP), -1, GROUPS.length - 1);
        selectedSlot = preferences.getInt("primary_slot", -1);
        selectedWingCh = clamp(preferences.getInt("primary_ch", 0), 0, WING_CHANNELS);
        if (selectedGroup < 0 || selectedSlot < -1
                || selectedSlot >= GROUPS[selectedGroup].instruments.length) {
            selectedSlot = -1;
        }
        drumColorSetting = clamp(preferences.getInt("drum_color", 0), 0, WING_COLORS.length - 1);
        activeThresholdDbfs = preferences.getInt("active_threshold_dbfs", -45);

        for (Group group : GROUPS) {
            for (Instrument instrument : group.instruments) {
                assignments.put(instrument.id,
                        clamp(preferences.getInt("assign_" + instrument.id, 0), 0, WING_CHANNELS));
            }
        }
        for (int bus = 0; bus < MIX_COUNT; bus++) {
            masterDb[bus] = preferences.getFloat("master_" + bus, 0.0f);
            masterMuted[bus] = preferences.getBoolean("master_mute_" + bus, false);
            for (int ch = 1; ch <= WING_CHANNELS; ch++) {
                sendLevelDb[bus][ch] = preferences.getFloat("lvl_" + bus + "_" + ch, -12.0f);
                sendOn[bus][ch] = preferences.getBoolean("on_" + bus + "_" + ch, true);
            }
        }
        for (int ch = 1; ch <= WING_CHANNELS; ch++) {
            channelName[ch] = preferences.getString("mix_name_" + ch, "");
            channelColor[ch] = clamp(preferences.getInt("mix_col_" + ch, 0), 0, WING_COLORS.length - 1);
            channelActColor[ch] = -1;
        }
        for (int bus = 1; bus <= MIX_COUNT; bus++) {
            busName[bus] = preferences.getString("bus_name_" + bus, "");
        }
        Arrays.fill(inputMeterDbfs, -72.0f);
        page = busSetup ? Page.BUSES : (firstSetup ? Page.GROUPS : Page.LEVEL);

        pedalServer = new BluetoothFootPedalServer(this, new BluetoothFootPedalServer.Listener() {
            @Override
            public void onStatus(String status, boolean connected) {
                runOnUiThread(() -> updatePedalBadge(status, connected));
            }

            @Override
            public void onButton(int button, boolean pressed) {
                runOnUiThread(() -> handlePedalEvent(button, pressed));
            }
        });

        wingClient = new WingNativeClient(new WingNativeClient.Listener() {
            @Override
            public void onConnectionState(boolean connected, String host, String consoleName,
                    String detail) {
                runOnUiThread(() -> updateMixerConnection(connected, host, consoleName));
            }

            @Override
            public void onRemoteSendLevel(int wingChannel, int auxBus, float levelDb) {
                runOnUiThread(() -> applyRemoteSendLevel(wingChannel, auxBus, levelDb));
            }

            @Override
            public void onRemoteSendOn(int wingChannel, int auxBus, boolean on) {
                runOnUiThread(() -> applyRemoteSendOn(wingChannel, auxBus, on));
            }

            @Override
            public void onChannelName(int wingChannel, String name, boolean activeName) {
                runOnUiThread(() -> applyChannelName(wingChannel, name, activeName));
            }

            @Override
            public void onChannelColor(int wingChannel, int color, boolean activeColor) {
                runOnUiThread(() -> applyChannelColor(wingChannel, color, activeColor));
            }

            @Override
            public void onBusFader(int auxBus, float levelDb) {
                runOnUiThread(() -> applyRemoteMaster(auxBus, levelDb));
            }

            @Override
            public void onBusMute(int auxBus, boolean muted) {
                runOnUiThread(() -> applyRemoteMasterMute(auxBus, muted));
            }

            @Override
            public void onBusName(int auxBus, String name, boolean activeName) {
                runOnUiThread(() -> applyBusName(auxBus, name, activeName));
            }

            @Override
            public void onMeters(float[] inputLevelDb) {
                runOnUiThread(() -> applyWingMeters(inputLevelDb));
            }
        }, isRunningInEmulator(), preferences.getString("wing_host", ""));

        setContentView(createScreen());
        renderPage();
        wingClient.start();
        pedalHandler.postDelayed(meterWatchdog, 500);
    }

    private void migratePreferences(SharedPreferences preferences) {
        if (preferences.getInt("instrument_schema_version", 0) >= SCHEMA_VERSION) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith("level_") || key.startsWith("mute_")) editor.remove(key);
        }
        // The first prototype used a fixed demo instrument list; the player chooses again.
        editor.remove("primary_set").remove("primary_group").remove("primary_channel")
                .putInt("instrument_schema_version", SCHEMA_VERSION)
                .apply();
    }

    @Override
    protected void onDestroy() {
        // Never leave a channel auto-muted in the player's mix when the app goes away.
        openAllGates();
        if (wingClient != null) wingClient.stopAfterFlush(400);
        if (pedalServer != null) pedalServer.stop();
        pedalHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foregroundActivity = this;
    }

    @Override
    protected void onPause() {
        if (foregroundActivity == this) foregroundActivity = null;
        super.onPause();
    }

    private SharedPreferences prefs() {
        return getPreferences(MODE_PRIVATE);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---------------------------------------------------------------- Bluetooth pedal

    private void togglePedalServer() {
        if (isRunningInEmulator()) {
            Toast.makeText(this,
                    "Emulátor ovládá panel na PC přes ADB. BLE použij až na telefonu.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (pedalServer != null && pedalServer.isStarted()) {
            pedalServer.stop();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] permissions = {
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
            boolean missingPermission = false;
            for (String permission : permissions) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    missingPermission = true;
                    break;
                }
            }
            if (missingPermission) {
                requestPermissions(permissions, REQUEST_BLE_PERMISSIONS);
                return;
            }
        }
        startPedalServerIfBluetoothEnabled();
    }

    /** Pedal events from the PC simulator over ADB; same path as the BLE pedal. */
    public static void acceptPedalBroadcast(int button, boolean pressed) {
        MainActivity activity = foregroundActivity;
        if (activity == null) return;
        activity.runOnUiThread(() -> activity.handlePedalEvent(button, pressed));
    }

    private boolean isRunningInEmulator() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String hardware = Build.HARDWARE == null ? "" : Build.HARDWARE;
        return fingerprint.startsWith("generic")
                || model.toLowerCase(Locale.ROOT).contains("sdk_gphone")
                || hardware.equalsIgnoreCase("ranchu")
                || hardware.equalsIgnoreCase("goldfish");
    }

    private void startPedalServerIfBluetoothEnabled() {
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            updatePedalBadge("Bluetooth není dostupný", false);
        } else if (!adapter.isEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQUEST_ENABLE_BLUETOOTH);
            } catch (SecurityException error) {
                updatePedalBadge("Povol Bluetooth v nastavení", false);
            }
        } else {
            pedalServer.start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    updatePedalBadge("Bluetooth nepovoleno", false);
                    return;
                }
            }
            startPedalServerIfBluetoothEnabled();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) pedalServer.start();
            else updatePedalBadge("Bluetooth vypnutý", false);
        }
    }

    private void updatePedalBadge(String status, boolean connected) {
        if (pedalBadge == null) return;
        String text;
        if (connected) {
            text = "BLE: OK";
        } else if (pedalServer != null && pedalServer.isStarted()) {
            text = "BLE: ČEKÁ";
        } else if (status.contains("spouštím") || status.contains("Spouštím")) {
            text = "BLE: …";
        } else {
            text = "BLE: VYP";
        }
        pedalBadge.setText(text);
        pedalBadge.setTextColor(connected ? ACCENT : MUTED_TEXT);
        pedalBadge.setContentDescription("BLE přijímač pro pedál: " + status
                + ". Emulátor ovládá tlačítkový panel na PC přes ADB.");
        if (!status.equals("Pedál vypnutý") && !status.equals("BLE pedál připraven")) {
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
        }
    }

    private void handlePedalEvent(int button, boolean pressed) {
        if (button == BluetoothFootPedalServer.CENTER) {
            if (pressed) {
                centerLongActionDone = false;
                if (centerHoldAction != null) pedalHandler.removeCallbacks(centerHoldAction);
                centerHoldAction = () -> {
                    if (pedalButtonsPressed[BluetoothFootPedalServer.CENTER]) {
                        centerLongActionDone = true;
                        handleCenterLongPress();
                    }
                };
                pedalButtonsPressed[button] = true;
                pedalHandler.postDelayed(centerHoldAction, 650);
            } else {
                pedalButtonsPressed[button] = false;
                if (centerHoldAction != null) pedalHandler.removeCallbacks(centerHoldAction);
                if (!centerLongActionDone) handleCenterTap();
            }
            return;
        }

        if (button != BluetoothFootPedalServer.LEFT && button != BluetoothFootPedalServer.RIGHT) return;
        pedalButtonsPressed[button] = pressed;
        if (pressed) {
            handleFootDirection(button);
            if (directionRepeat[button] != null) pedalHandler.removeCallbacks(directionRepeat[button]);
            directionRepeat[button] = new Runnable() {
                @Override
                public void run() {
                    if (!pedalButtonsPressed[button]) return;
                    handleFootDirection(button);
                    boolean levelPage = page == Page.LEVEL || page == Page.MASTER;
                    pedalHandler.postDelayed(this, levelPage ? 140 : 320);
                }
            };
            pedalHandler.postDelayed(directionRepeat[button], 420);
        } else if (directionRepeat[button] != null) {
            pedalHandler.removeCallbacks(directionRepeat[button]);
        }
    }

    private void handleFootDirection(int button) {
        int delta = button == BluetoothFootPedalServer.LEFT ? -1 : 1;
        if (page == Page.LEVEL) {
            changeLevel(delta);
        } else if (page == Page.MASTER) {
            changeMaster(delta);
        } else {
            moveMenuFocus(delta);
        }
    }

    private void handleCenterTap() {
        if (page == Page.LEVEL) {
            page = levelReturnPage;
            renderPage();
        } else if (page == Page.MASTER) {
            page = Page.GROUPS;
            renderPage();
        } else {
            activateFocusedChoice();
        }
    }

    private void handleCenterLongPress() {
        if (page == Page.LEVEL) {
            toggleTargetMute();
        } else if (page == Page.MASTER) {
            toggleMasterMute();
        } else if (page == Page.CHANNELS || page == Page.ASSIGN || page == Page.DRUM_COLOR
                || page == Page.TUNING) {
            onBackPressed();
        }
    }

    // ---------------------------------------------------------------- Input meters

    public static void acceptInputMeterBroadcast(String encoded) {
        MainActivity activity = foregroundActivity;
        if (activity == null || encoded == null || encoded.isEmpty()) return;
        activity.runOnUiThread(() -> activity.decodeInputMeterPayload(encoded));
    }

    private void decodeInputMeterPayload(String encoded) {
        try {
            byte[] json = Base64.decode(encoded,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            applyInputMeterSnapshot(new JSONObject(new String(json, StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            // Invalid simulator packets must not interrupt the mixer UI.
        }
    }

    private boolean wingMetersLive() {
        return lastWingMeterAt > 0 && SystemClock.elapsedRealtime() - lastWingMeterAt < METER_STALE_MS;
    }

    private boolean simulatedMetersLive() {
        return simulatedInputPlaying && SystemClock.elapsedRealtime() - lastInputMessageAt < METER_STALE_MS;
    }

    private boolean metersFresh() {
        return wingMetersLive() || simulatedMetersLive();
    }

    private String meterSourceLabel() {
        if (wingMetersLive()) return "Úrovně: živě z pultu WING";
        if (simulatedMetersLive()) return "Úrovně: simulátor PC (ADB)";
        return "Úrovně vstupů nepřichází – automatika stojí";
    }

    /** Live input levels from the console's meter stream; these take priority over the PC simulator. */
    private void applyWingMeters(float[] levels) {
        lastWingMeterAt = SystemClock.elapsedRealtime();
        for (int ch = 1; ch <= WING_CHANNELS && ch < levels.length; ch++) {
            float value = Math.max(-72.0f, Math.min(6.0f, levels[ch]));
            inputMeterDbfs[ch] = Math.min(0.0f, value);
            inputPeakDbfs[ch] = value;
        }
        processMeters(lastWingMeterAt, false);
    }

    private void applyInputMeterSnapshot(JSONObject snapshot) {
        boolean wasPlaying = simulatedInputPlaying;
        simulatedInputPlaying = snapshot.optBoolean("playing", false);
        simulatedSongName = snapshot.optString("song", "");
        meterBridgeSeen = true;
        lastInputMessageAt = SystemClock.elapsedRealtime();
        if (wingMetersLive()) return;
        boolean statusChanged = wasPlaying != simulatedInputPlaying;

        if (!simulatedInputPlaying) {
            for (int ch = 1; ch <= WING_CHANNELS; ch++) {
                if (inputActive[ch]) statusChanged = true;
                inputMeterDbfs[ch] = -72.0f;
                inputPeakDbfs[ch] = -72.0f;
                inputActive[ch] = false;
                firstAboveAt[ch] = 0;
                lastAboveAt[ch] = 0;
            }
            // Without meter data the automation must not keep anything muted.
            openAllGates();
            refreshMeterViews(statusChanged);
            return;
        }

        Arrays.fill(inputMeterDbfs, -72.0f);
        Arrays.fill(inputPeakDbfs, -72.0f);
        JSONArray meters = snapshot.optJSONArray("meters");
        if (meters != null) {
            for (int i = 0; i < meters.length(); i++) {
                JSONObject meter = meters.optJSONObject(i);
                if (meter == null) continue;
                int ch = meter.optInt("ch", -1);
                if (ch < 1 || ch > WING_CHANNELS) continue;
                float dbfs = (float) meter.optDouble("dbfs", -72.0);
                float peak = (float) meter.optDouble("peak", dbfs);
                inputMeterDbfs[ch] = Math.max(-72.0f, Math.min(0.0f, dbfs));
                inputPeakDbfs[ch] = Math.max(-72.0f, Math.min(6.0f, Math.max(dbfs, peak)));
            }
        }
        processMeters(lastInputMessageAt, statusChanged);
    }

    private void processMeters(long now, boolean forceUiRefresh) {
        boolean activityChanged = forceUiRefresh;
        for (int ch = 1; ch <= WING_CHANNELS; ch++) {
            AutoSettings settings = channelSettings(ch);
            if (updateInputActivity(ch, inputMeterDbfs[ch], settings.threshold, now)) {
                activityChanged = true;
            }
            if (settings.gate) {
                updateGate(ch, inputMeterDbfs[ch], settings.threshold, now);
            } else if (gateClosed[ch] || autoMutedOnConsole[ch]) {
                openGate(ch);
            }
            if (settings.clip && inputPeakDbfs[ch] >= CLIP_DBFS) {
                handleClip(ch, now);
            }
        }
        if (activityChanged || now - lastMeterUiAt >= 100) {
            lastMeterUiAt = now;
            refreshMeterViews(activityChanged);
        }
    }

    private void refreshMeterViews(boolean activityChanged) {
        if (page == Page.ACTIVE && activityChanged) {
            renderActiveInputs();
        } else if (page == Page.ACTIVE) {
            updateActiveMeterViews();
        } else if (page == Page.TUNING) {
            updateTuningMeterViews();
        } else {
            refreshMenuMeters();
        }
    }

    private boolean updateInputActivity(int ch, float dbfs, int threshold, long now) {
        boolean wasActive = inputActive[ch];
        if (!wasActive) {
            if (dbfs >= threshold) {
                if (firstAboveAt[ch] == 0) firstAboveAt[ch] = now;
                lastAboveAt[ch] = now;
                if (now - firstAboveAt[ch] >= 250) inputActive[ch] = true;
            } else {
                firstAboveAt[ch] = 0;
            }
        } else if (dbfs >= threshold - 6) {
            lastAboveAt[ch] = now;
        } else if (lastAboveAt[ch] > 0 && now - lastAboveAt[ch] >= 800) {
            inputActive[ch] = false;
            firstAboveAt[ch] = 0;
        }
        return wasActive != inputActive[ch];
    }

    // ---------------------------------------------------------------- Automation: noise gate

    /**
     * Opens immediately when the input reaches the instrument's threshold and closes after
     * GATE_HOLD_MS below it. Closing only turns the channel's send OFF in the player's own Aux.
     */
    private void updateGate(int ch, float dbfs, int threshold, long now) {
        if (dbfs >= threshold) {
            gateLastAbove[ch] = now;
            if (gateClosed[ch]) openGate(ch);
        } else if (!gateClosed[ch]) {
            if (gateLastAbove[ch] == 0) gateLastAbove[ch] = now;
            if (now - gateLastAbove[ch] >= GATE_HOLD_MS) closeGate(ch);
        }
    }

    private void closeGate(int ch) {
        gateClosed[ch] = true;
        if (sendOn[selectedBus][ch] && wingClient != null
                && wingClient.sendChannelSendOn(ch, selectedBus + 1, false)) {
            autoMutedOnConsole[ch] = true;
        }
        onAutomationStateChanged(ch);
    }

    private void openGate(int ch) {
        boolean wasClosed = gateClosed[ch];
        gateClosed[ch] = false;
        gateLastAbove[ch] = SystemClock.elapsedRealtime();
        restoreConsoleOn(ch);
        if (wasClosed) onAutomationStateChanged(ch);
    }

    /** Turns the send back ON if the automation (not the player) switched it off. */
    private void restoreConsoleOn(int ch) {
        if (!autoMutedOnConsole[ch]) return;
        if (!sendOn[selectedBus][ch]) {
            autoMutedOnConsole[ch] = false;
            return;
        }
        if (wingClient != null && wingClient.sendChannelSendOn(ch, selectedBus + 1, true)) {
            autoMutedOnConsole[ch] = false;
        }
    }

    private boolean anyAutoMuteActive() {
        for (int ch = 1; ch <= WING_CHANNELS; ch++) {
            if (gateClosed[ch] || autoMutedOnConsole[ch]) return true;
        }
        return false;
    }

    private void openAllGates() {
        boolean changed = false;
        for (int ch = 1; ch <= WING_CHANNELS; ch++) {
            if (gateClosed[ch] || autoMutedOnConsole[ch]) {
                gateClosed[ch] = false;
                gateLastAbove[ch] = 0;
                restoreConsoleOn(ch);
                changed = true;
            }
        }
        if (changed && content != null) onAutomationStateChanged(0);
    }

    private void onAutomationStateChanged(int ch) {
        if (content == null) return;
        if (page == Page.LEVEL && (ch == 0 || ch == targetChannel())) {
            renderPage(false);
        } else if (page == Page.TUNING && (ch == 0 || ch == targetChannel())) {
            renderPage(false);
        } else if (page == Page.CHANNELS || page == Page.GROUPS) {
            mixerBusValuesChanged = true;
            scheduleMixerRefresh();
        }
    }

    // ---------------------------------------------------------------- Automation: clipping

    /**
     * When the input reaches -1 dBFS, lowers this channel in the player's own Aux by 3 dB
     * (at most every 2 s, at most 12 dB in total). It cannot repair input distortion.
     */
    private void handleClip(int ch, long now) {
        if (now < clipGraceUntil[ch] || now - lastClipActionAt[ch] < CLIP_INTERVAL_MS) return;
        if (clipReductionDb[ch] >= CLIP_MAX_DB) return;
        float current = sendLevelDb[selectedBus][ch];
        if (current <= MIN_DB) return;
        int next = Math.max(MIN_DB, Math.round(current) - CLIP_STEP_DB);
        clipReductionDb[ch] += current - next;
        lastClipActionAt[ch] = now;
        writeSendLevel(ch, next);
        Toast.makeText(this, "Lupání: " + activeCardTitle(ch) + " (CH " + ch + ") −"
                + CLIP_STEP_DB + " dB v AUX " + (selectedBus + 1), Toast.LENGTH_SHORT).show();
        if (page == Page.LEVEL && ch == targetChannel()) {
            renderPage(false);
        } else {
            onAutomationStateChanged(ch);
        }
    }

    private void undoClipReduction(int ch) {
        float restored = Math.min(MAX_DB, sendLevelDb[selectedBus][ch] + clipReductionDb[ch]);
        clipReductionDb[ch] = 0;
        clipGraceUntil[ch] = SystemClock.elapsedRealtime() + CLIP_UNDO_GRACE_MS;
        writeSendLevel(ch, Math.round(restored));
        renderPage(false);
    }

    private void resetAutomationForBusChange() {
        openAllGates();
        Arrays.fill(clipReductionDb, 0);
        Arrays.fill(lastClipActionAt, 0);
        Arrays.fill(clipGraceUntil, 0);
    }

    // ---------------------------------------------------------------- Per-instrument settings

    private String instrumentSettingsKey(Instrument instrument) {
        return "inst:" + instrument.id;
    }

    /** An assigned instrument owns the channel's settings; otherwise the channel itself. */
    private String channelSettingsKey(int ch) {
        for (Group group : GROUPS) {
            for (Instrument instrument : group.instruments) {
                if (assignedChannel(instrument) == ch) return instrumentSettingsKey(instrument);
            }
        }
        return "ch:" + ch;
    }

    private String targetSettingsKey() {
        Instrument instrument = selectedInstrument();
        return instrument != null ? instrumentSettingsKey(instrument) : channelSettingsKey(selectedWingCh);
    }

    private AutoSettings settingsForKey(String key) {
        SharedPreferences preferences = prefs();
        AutoSettings settings = new AutoSettings();
        settings.ownThreshold = preferences.contains("thr_" + key);
        settings.threshold = settings.ownThreshold
                ? preferences.getInt("thr_" + key, activeThresholdDbfs) : activeThresholdDbfs;
        settings.gate = preferences.getBoolean("gate_" + key, false);
        settings.clip = preferences.getBoolean("clip_" + key, false);
        return settings;
    }

    private AutoSettings channelSettings(int ch) {
        if (channelSettingsCache == null) {
            AutoSettings[] cache = new AutoSettings[WING_CHANNELS + 1];
            for (int i = 1; i <= WING_CHANNELS; i++) cache[i] = settingsForKey(channelSettingsKey(i));
            cache[0] = settingsForKey("ch:0");
            channelSettingsCache = cache;
        }
        return channelSettingsCache[validChannel(ch) ? ch : 0];
    }

    private void invalidateSettings() {
        channelSettingsCache = null;
    }

    private void updateActiveMeterViews() {
        for (int ch = 1; ch <= WING_CHANNELS; ch++) {
            String key = "ch:" + ch;
            ProgressBar bar = activeMeterBars.get(key);
            TextView label = activeMeterLabels.get(key);
            if (bar != null) bar.setProgress(levelToMeterProgress(inputMeterDbfs[ch]));
            if (label != null) setTextIfChanged(label, formatDbfs(inputMeterDbfs[ch]));
        }
    }

    // ---------------------------------------------------------------- Mixer model helpers

    private boolean validChannel(int ch) {
        return ch >= 1 && ch <= WING_CHANNELS;
    }

    private boolean hasMixerName(int ch) {
        if (!validChannel(ch)) return false;
        return (channelActName[ch] != null && !channelActName[ch].isEmpty())
                || (channelName[ch] != null && !channelName[ch].isEmpty());
    }

    private String channelDisplayName(int ch) {
        if (!validChannel(ch)) return "—";
        if (channelActName[ch] != null && !channelActName[ch].isEmpty()) return channelActName[ch];
        if (channelName[ch] != null && !channelName[ch].isEmpty()) return channelName[ch];
        return "CH " + ch;
    }

    private int channelDisplayColor(int ch) {
        if (!validChannel(ch)) return 0;
        int color = channelActColor[ch] >= 0 ? channelActColor[ch] : channelColor[ch];
        return clamp(color, 0, WING_COLORS.length - 1);
    }

    private static String colorName(int color) {
        return WING_COLOR_NAMES[clamp(color, 0, WING_COLOR_NAMES.length - 1)];
    }

    private String customBusName(int auxBus) {
        String name = busActName[auxBus] != null && !busActName[auxBus].isEmpty()
                ? busActName[auxBus] : busName[auxBus];
        if (name == null || name.trim().isEmpty()) return "";
        String normalized = name.replace(" ", "").toUpperCase(Locale.ROOT);
        if (normalized.equals("AUX" + auxBus) || normalized.equals("BUS" + auxBus)
                || normalized.equals("B" + auxBus) || normalized.equals("MIX" + auxBus)) {
            return "";
        }
        return name.trim();
    }

    private int assignedChannel(Instrument instrument) {
        Integer ch = assignments.get(instrument.id);
        return ch == null ? 0 : ch;
    }

    private Instrument selectedInstrument() {
        if (selectedGroup < 0 || selectedGroup >= GROUPS.length) return null;
        Instrument[] instruments = GROUPS[selectedGroup].instruments;
        if (selectedSlot < 0 || selectedSlot >= instruments.length) return null;
        return instruments[selectedSlot];
    }

    private int targetChannel() {
        Instrument instrument = selectedInstrument();
        if (instrument != null) return assignedChannel(instrument);
        return validChannel(selectedWingCh) ? selectedWingCh : 0;
    }

    private int autoDrumColor() {
        int[] counts = new int[WING_COLORS.length];
        for (int ch = 1; ch <= WING_CHANNELS; ch++) {
            if (!hasMixerName(ch)) continue;
            int color = channelDisplayColor(ch);
            if (color > 0 && DRUM_NAME.matcher(channelDisplayName(ch)).find()) counts[color]++;
        }
        int best = 0;
        for (int color = 1; color < counts.length; color++) {
            if (counts[color] > counts[best]) best = color;
        }
        return counts[best] > 0 ? best : 0;
    }

    private int effectiveDrumColor() {
        return drumColorSetting > 0 ? drumColorSetting : autoDrumColor();
    }

    private List<Integer> drumChannels() {
        List<Integer> channels = new ArrayList<>();
        int color = effectiveDrumColor();
        if (color == 0) return channels;
        for (int ch = 1; ch <= WING_CHANNELS; ch++) {
            if (hasMixerName(ch) && channelDisplayColor(ch) == color) channels.add(ch);
        }
        return channels;
    }

    private List<Integer> groupChannels(int group) {
        if (GROUPS[group].byColor) return drumChannels();
        List<Integer> channels = new ArrayList<>();
        for (Instrument instrument : GROUPS[group].instruments) {
            int ch = assignedChannel(instrument);
            if (ch > 0) channels.add(ch);
        }
        return channels;
    }

    private List<String> instrumentsUsing(int ch) {
        List<String> users = new ArrayList<>();
        for (Group group : GROUPS) {
            for (Instrument instrument : group.instruments) {
                if (assignedChannel(instrument) == ch) users.add(instrument.name + " (" + group.name + ")");
            }
        }
        return users;
    }

    // ---------------------------------------------------------------- Screen

    private View createScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(12), dp(18), dp(12));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView appName = label("WING Monitor", 19, TEXT);
        appName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.addView(appName);
        brand.addView(label("OSOBNÍ ODPOSLECH", 10, MUTED_TEXT));
        header.addView(brand, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        pedalBadge = label(isRunningInEmulator() ? "ADB" : "BLE: VYP", 10, MUTED_TEXT);
        pedalBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pedalBadge.setGravity(Gravity.CENTER);
        pedalBadge.setPadding(dp(8), dp(10), dp(8), dp(10));
        pedalBadge.setBackground(rounded(SURFACE, 12));
        pedalBadge.setContentDescription(isRunningInEmulator()
                ? "Emulátor se ovládá simulátorem na PC přes ADB"
                : "BLE přijímač pro pedál");
        pedalBadge.setOnClickListener(v -> togglePedalServer());
        LinearLayout.LayoutParams pedalParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pedalParams.rightMargin = dp(6);
        header.addView(pedalBadge, pedalParams);

        mixerBadge = label("MIX: HLEDÁM", 10, MUTED_TEXT);
        mixerBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mixerBadge.setGravity(Gravity.CENTER);
        mixerBadge.setPadding(dp(9), dp(10), dp(9), dp(10));
        mixerBadge.setBackground(rounded(SURFACE, 12));
        mixerBadge.setContentDescription("Nastavení spojení s mixpultem WING");
        mixerBadge.setOnClickListener(v -> configureMixerHost());
        LinearLayout.LayoutParams mixerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mixerParams.rightMargin = dp(6);
        header.addView(mixerBadge, mixerParams);

        busBadge = label("AUX 1", 12, ACCENT);
        busBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        busBadge.setGravity(Gravity.CENTER);
        busBadge.setPadding(dp(13), dp(11), dp(13), dp(11));
        busBadge.setBackground(rounded(SURFACE, 14));
        busBadge.setContentDescription("Vybrat odposlech");
        busBadge.setOnClickListener(v -> chooseBus());
        header.addView(busBadge);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        pageScroll = scroll;
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(12), dp(18), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private void renderPage() {
        renderPage(true);
    }

    private void renderPage(boolean scrollToTop) {
        content.removeAllViews();
        menuItems.clear();
        menuMeterClips.clear();
        activeMeterBars.clear();
        activeMeterLabels.clear();
        levelValue = null;
        busBadge.setText("AUX " + (selectedBus + 1) + " ▾");

        switch (page) {
            case BUSES:
                renderBuses();
                break;
            case GROUPS:
                renderGroups();
                break;
            case CHANNELS:
                renderChannels();
                break;
            case MASTER:
                renderMaster();
                break;
            case ASSIGN:
                renderAssign();
                break;
            case DRUM_COLOR:
                renderDrumColor();
                break;
            case ACTIVE:
                renderActiveInputs();
                break;
            case TUNING:
                renderTuning();
                break;
            default:
                renderLevel();
                break;
        }
        if (scrollToTop && pageScroll != null) {
            pageScroll.scrollTo(0, 0);
        }
    }

    private void renderPagePreservingFocus() {
        if (dragging) {
            // Never rebuild the list under the user's finger; refresh after the drop.
            mixerStructureChanged = true;
            scheduleMixerRefresh();
            return;
        }
        int focused = focusedMenuIndex();
        renderPage(false);
        if (focused >= 0 && focused < menuItems.size()) {
            View item = menuItems.get(focused);
            item.post(item::requestFocus);
        }
    }

    private void renderBuses() {
        addHeading("Který odposlech ovládáš?", "Vyber stereo AUX mix přiřazený tvým sluchátkům");
        for (int bus = 0; bus < MIX_COUNT; bus++) {
            final int busIndex = bus;
            String custom = customBusName(bus + 1);
            addPlainChoiceButton("AUX " + (bus + 1), custom.isEmpty() ? "Stereo monitor mix" : custom, () -> {
                if (busIndex != selectedBus) resetAutomationForBusChange();
                selectedBus = busIndex;
                busSetup = false;
                prefs().edit()
                        .putInt("selected_bus", selectedBus)
                        .putBoolean("selected_bus_set", true)
                        .apply();
                page = firstSetup ? Page.GROUPS : Page.LEVEL;
                renderPage();
            });
        }
    }

    private void renderGroups() {
        addHeading(firstSetup ? "Na co hraješ?" : "Další nástroje",
                firstSetup ? "Vyber svůj hlavní nástroj" : "Vyber skupinu");
        List<String> keys = new ArrayList<>();
        for (Group group : GROUPS) keys.add(group.id);
        if (!firstSetup) keys.add(MASTER_KEY);

        ReorderList list = new ReorderList("order_groups");
        for (String key : orderedKeys(list.prefKey, keys)) {
            View item;
            if (key.equals(MASTER_KEY)) {
                String master = "Celková hlasitost AUX " + (selectedBus + 1) + " · "
                        + formatDb(masterDb[selectedBus])
                        + (masterMuted[selectedBus] ? " · ZTLUMENO" : "");
                item = addChoiceButton("Master", master, "master", null, () -> {
                    page = Page.MASTER;
                    renderPage();
                });
            } else {
                final int groupIndex = groupIndexById(key);
                item = addChoiceButton(GROUPS[groupIndex].name, groupSubtitle(groupIndex),
                        "group:" + groupIndex, groupStripeColor(groupIndex),
                        () -> openGroup(groupIndex));
            }
            makeReorderable(list, item, key);
        }
        addDemoNote();
    }

    private static int groupIndexById(String id) {
        for (int i = 0; i < GROUPS.length; i++) {
            if (GROUPS[i].id.equals(id)) return i;
        }
        return 0;
    }

    private String groupSubtitle(int index) {
        Group group = GROUPS[index];
        if (group.byColor) {
            int color = effectiveDrumColor();
            if (color == 0) return "Vyber barvu bicích z pultu";
            int count = drumChannels().size();
            return countLabel(count, "kanál", "kanály", "kanálů") + " · " + colorName(color)
                    + (drumColorSetting == 0 ? " (auto)" : "");
        }
        if (group.instruments.length == 1) {
            return assignmentLabel(group.instruments[0]);
        }
        return countLabel(group.instruments.length, "nástroj", "nástroje", "nástrojů");
    }

    private Integer groupStripeColor(int index) {
        if (GROUPS[index].byColor) {
            int color = effectiveDrumColor();
            return color > 0 ? WING_COLORS[color] : null;
        }
        return null;
    }

    private String assignmentLabel(Instrument instrument) {
        int ch = assignedChannel(instrument);
        if (ch == 0) return "Nepřiřazeno · vyber kanál z pultu";
        return "CH " + ch + " · " + channelDisplayName(ch) + " · " + channelLevelDetails(ch);
    }

    private String channelLevelDetails(int ch) {
        String details = formatDb(sendLevelDb[selectedBus][ch]);
        if (!sendOn[selectedBus][ch]) details += " · ZTLUMENO";
        else if (gateClosed[ch]) details += " · AUTO: JEN ŠUM";
        if (clipReductionDb[ch] > 0) details += " · LUPÁNÍ −" + Math.round(clipReductionDb[ch]) + " dB";
        return details;
    }

    private static String countLabel(int count, String one, String few, String many) {
        String noun = count == 1 ? one : (count >= 2 && count <= 4 ? few : many);
        return count + " " + noun;
    }

    private void openGroup(int groupIndex) {
        selectedGroup = groupIndex;
        Group group = GROUPS[groupIndex];
        if (group.byColor) {
            if (effectiveDrumColor() == 0) {
                drumColorReturnPage = Page.GROUPS;
                page = Page.DRUM_COLOR;
            } else {
                page = Page.CHANNELS;
            }
        } else if (group.instruments.length == 1) {
            openInstrument(groupIndex, 0, Page.GROUPS);
            return;
        } else {
            page = Page.CHANNELS;
        }
        renderPage();
    }

    private void openInstrument(int groupIndex, int slot, Page returnPage) {
        selectedGroup = groupIndex;
        selectedSlot = slot;
        selectedWingCh = 0;
        openLevel(returnPage);
    }

    private void openChannel(int groupIndex, int ch, Page returnPage) {
        selectedGroup = groupIndex;
        selectedSlot = -1;
        selectedWingCh = ch;
        openLevel(returnPage);
    }

    private void openLevel(Page returnPage) {
        if (firstSetup) {
            savePrimary();
            firstSetup = false;
            levelReturnPage = Page.GROUPS;
        } else {
            levelReturnPage = returnPage;
        }
        page = Page.LEVEL;
        renderPage();
    }

    private void renderChannels() {
        if (selectedGroup < 0 || selectedGroup >= GROUPS.length) {
            page = Page.GROUPS;
            renderGroups();
            return;
        }
        Group group = GROUPS[selectedGroup];
        addBackButton("Zpět na skupiny", () -> {
            page = Page.GROUPS;
            renderPage();
        });
        addActiveInputsButton();

        if (group.byColor) {
            int color = effectiveDrumColor();
            addHeading(group.name, firstSetup ? "Vyber svůj hlavní nástroj"
                    : "Kanály z pultu · " + colorName(color));
            List<Integer> channels = drumChannels();
            List<String> drumKeys = new ArrayList<>();
            for (int ch : channels) drumKeys.add(String.valueOf(ch));
            ReorderList drumList = new ReorderList("order_drums");
            for (String key : orderedKeys(drumList.prefKey, drumKeys)) {
                final int wingCh = Integer.parseInt(key);
                View item = addChoiceButton(channelDisplayName(wingCh),
                        "CH " + wingCh + " · " + channelLevelDetails(wingCh),
                        "ch:" + wingCh, WING_COLORS[channelDisplayColor(wingCh)],
                        () -> openChannel(DRUM_GROUP, wingCh, Page.CHANNELS));
                makeReorderable(drumList, item, key);
            }
            if (channels.isEmpty()) {
                addInfo(wingConnected
                        ? "Na pultu není žádný pojmenovaný kanál v této barvě"
                        : "Pult není připojený – názvy kanálů zatím neznám");
            }
            addPlainChoiceButton("Barva bicích",
                    colorName(color) + (drumColorSetting == 0 ? " · automaticky" : "") + " · změnit",
                    () -> {
                        drumColorReturnPage = Page.CHANNELS;
                        page = Page.DRUM_COLOR;
                        renderPage();
                    });
        } else {
            addHeading(group.name, firstSetup ? "Vyber svůj hlavní nástroj" : "Vyber nástroj");
            List<String> instrumentKeys = new ArrayList<>();
            for (Instrument instrument : group.instruments) instrumentKeys.add(instrument.id);
            ReorderList instrumentList = new ReorderList("order_inst_" + group.id);
            final int groupIndex = selectedGroup;
            for (String key : orderedKeys(instrumentList.prefKey, instrumentKeys)) {
                final int slotIndex = slotIndexById(group, key);
                Instrument instrument = group.instruments[slotIndex];
                int ch = assignedChannel(instrument);
                Integer stripe = ch > 0 ? WING_COLORS[channelDisplayColor(ch)] : null;
                View item = addChoiceButton(instrument.name, assignmentLabel(instrument),
                        "inst:" + groupIndex + ":" + slotIndex, stripe,
                        () -> openInstrument(groupIndex, slotIndex, Page.CHANNELS));
                makeReorderable(instrumentList, item, key);
            }
        }
        addDemoNote();
    }

    private static int slotIndexById(Group group, String id) {
        for (int i = 0; i < group.instruments.length; i++) {
            if (group.instruments[i].id.equals(id)) return i;
        }
        return 0;
    }

    private void renderLevel() {
        boolean isPrimary = isPrimaryInstrument();
        addBackButton(isPrimary ? "Další nástroje" : "Zpět na nástroje", () -> {
            page = levelReturnPage;
            renderPage();
        });

        Instrument instrument = selectedInstrument();
        int ch = targetChannel();
        String groupName = selectedGroup >= 0 && selectedGroup < GROUPS.length
                ? GROUPS[selectedGroup].name : "Kanál z pultu";
        String title = instrument != null ? instrument.name : channelDisplayName(ch);

        if (ch <= 0) {
            addHeading(title, groupName + " · bez kanálu");
            addInfo("Tenhle nástroj ještě nemá přiřazený kanál z pultu.");
            Button assign = actionButton("PŘIŘADIT KANÁL Z PULTU", ACCENT, BACKGROUND, dp(76));
            assign.setOnClickListener(v -> openAssign());
            content.addView(assign, margins(0, dp(14), 0, 0));
            addDemoNote();
            return;
        }

        String subtitle = groupName + " · CH " + ch
                + (instrument != null ? " " + channelDisplayName(ch) : "")
                + " → AUX " + (selectedBus + 1);
        addHeading(title, subtitle);
        addTuningButton();

        boolean muted = !sendOn[selectedBus][ch];
        boolean silenced = muted || gateClosed[ch];
        levelValue = label(formatDb(sendLevelDb[selectedBus][ch]), 48, silenced ? MUTED_TEXT : ACCENT);
        levelValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        levelValue.setGravity(Gravity.CENTER);
        levelValue.setPadding(dp(12), dp(18), dp(12), dp(18));
        setMeterBackground(levelValue, "ch:" + ch, SURFACE, 82, 18);
        content.addView(levelValue, margins(0, dp(12), 0, dp(8)));

        if (!muted && gateClosed[ch]) {
            addAutomationNotice("AUTO: na kanálu je jen šum – v odposlechu ztlumeno");
        }
        if (clipReductionDb[ch] > 0) {
            int reduction = Math.round(clipReductionDb[ch]);
            addAutomationNotice("AUTO: lupání – staženo o " + reduction + " dB. Zkreslení tím nezmizí,"
                    + " gain musí snížit zvukař.");
            Button undo = actionButton("VRÁTIT +" + reduction + " dB", SURFACE_LIGHT, TEXT, dp(54));
            undo.setTextSize(15);
            undo.setOnClickListener(v -> undoClipReduction(ch));
            content.addView(undo, margins(0, 0, 0, dp(8)));
        }
        content.addView(new View(this), margins(0, dp(6), 0, 0));

        addAdjustButtons(this::changeLevel);

        Button muteButton = actionButton(muted ? "ZAPNOUT ZVUK" : "ZTLUMIT",
                muted ? ACCENT : DANGER, TEXT, dp(76));
        muteButton.setTextSize(19);
        muteButton.setOnClickListener(v -> toggleTargetMute());
        content.addView(muteButton, margins(0, dp(14), 0, 0));

        if (instrument != null) {
            Button assign = actionButton("Kanál z pultu: CH " + ch + " · " + channelDisplayName(ch) + "  ›",
                    SURFACE_LIGHT, TEXT, dp(58));
            assign.setTextSize(16);
            assign.setOnClickListener(v -> openAssign());
            content.addView(assign, margins(0, dp(10), 0, 0));
        }

        if (!isPrimary) {
            Button setPrimary = actionButton("☆  Nastavit jako hlavní nástroj", SURFACE_LIGHT,
                    TEXT, dp(58));
            setPrimary.setOnClickListener(v -> setAsPrimary());
            content.addView(setPrimary, margins(0, dp(10), 0, 0));
        }
        addDemoNote();
    }

    private void renderMaster() {
        addBackButton("Zpět na skupiny", () -> {
            page = Page.GROUPS;
            renderPage();
        });
        String custom = customBusName(selectedBus + 1);
        addHeading("Master", "Celková hlasitost odposlechu · AUX " + (selectedBus + 1)
                + (custom.isEmpty() ? "" : " · " + custom));

        boolean muted = masterMuted[selectedBus];
        levelValue = label(formatDb(masterDb[selectedBus]), 48, muted ? MUTED_TEXT : ACCENT);
        levelValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        levelValue.setGravity(Gravity.CENTER);
        levelValue.setPadding(dp(12), dp(18), dp(12), dp(18));
        levelValue.setBackground(rounded(SURFACE, 18));
        content.addView(levelValue, margins(0, dp(12), 0, dp(14)));

        addAdjustButtons(this::changeMaster);

        Button muteButton = actionButton(muted ? "ZAPNOUT ODPOSLECH" : "ZTLUMIT CELÝ ODPOSLECH",
                muted ? ACCENT : DANGER, TEXT, dp(76));
        muteButton.setTextSize(19);
        muteButton.setOnClickListener(v -> toggleMasterMute());
        content.addView(muteButton, margins(0, dp(14), 0, 0));
        addDemoNote();
    }

    private void addAdjustButtons(LevelChanger changer) {
        LinearLayout adjust = new LinearLayout(this);
        adjust.setGravity(Gravity.CENTER);
        LinearLayout quieter = adjustButton("−", "TIŠE");
        LinearLayout louder = adjustButton("+", "HLASITĚJI");
        LinearLayout.LayoutParams quieterParams = new LinearLayout.LayoutParams(0, dp(156), 1);
        quieterParams.rightMargin = dp(6);
        adjust.addView(quieter, quieterParams);
        LinearLayout.LayoutParams louderParams = new LinearLayout.LayoutParams(0, dp(156), 1);
        louderParams.leftMargin = dp(6);
        adjust.addView(louder, louderParams);
        content.addView(adjust);

        quieter.setOnClickListener(v -> changer.change(-1));
        louder.setOnClickListener(v -> changer.change(1));
        quieter.setOnLongClickListener(v -> {
            changer.change(-5);
            Toast.makeText(this, "−5 dB", Toast.LENGTH_SHORT).show();
            return true;
        });
        louder.setOnLongClickListener(v -> {
            changer.change(5);
            Toast.makeText(this, "+5 dB", Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void openAssign() {
        assignReturnPage = Page.LEVEL;
        page = Page.ASSIGN;
        renderPage();
    }

    private void renderAssign() {
        Instrument instrument = selectedInstrument();
        if (instrument == null) {
            page = Page.LEVEL;
            renderLevel();
            return;
        }
        addBackButton("Zpět", () -> {
            page = assignReturnPage;
            renderPage();
        });
        addHeading("Kanál pro: " + instrument.name, wingConnected
                ? "Kanály z pultu podle barev"
                : "Pult nepřipojen · poslední známé názvy");

        int current = assignedChannel(instrument);
        addPlainChoiceButton((current == 0 ? "✓  " : "") + "Nepřiřazeno",
                "Nástroj nebude ovládat žádný kanál", () -> assignChannel(instrument, 0));

        LinkedHashMap<Integer, List<Integer>> byColor = new LinkedHashMap<>();
        List<Integer> unnamed = new ArrayList<>();
        for (int ch = 1; ch <= WING_CHANNELS; ch++) {
            if (hasMixerName(ch)) {
                int color = channelDisplayColor(ch);
                List<Integer> list = byColor.get(color);
                if (list == null) {
                    list = new ArrayList<>();
                    byColor.put(color, list);
                }
                list.add(ch);
            } else {
                unnamed.add(ch);
            }
        }
        for (Map.Entry<Integer, List<Integer>> entry : byColor.entrySet()) {
            int color = entry.getKey();
            addSectionHeader("■  " + colorName(color) + " · "
                    + countLabel(entry.getValue().size(), "kanál", "kanály", "kanálů"), WING_COLORS[color]);
            for (int ch : entry.getValue()) addAssignChoice(instrument, ch, current);
        }
        if (!unnamed.isEmpty()) {
            addSectionHeader("Bez názvu", MUTED_TEXT);
            for (int ch : unnamed) addAssignChoice(instrument, ch, current);
        }
    }

    private void addAssignChoice(Instrument instrument, int ch, int current) {
        List<String> users = instrumentsUsing(ch);
        users.remove(instrument.name + " (" + groupOf(instrument) + ")");
        String subtitle = "CH " + ch + (users.isEmpty() ? "" : " · teď i: " + TextUtils.join(", ", users));
        final int wingCh = ch;
        addChoiceButton((ch == current ? "✓  " : "") + channelDisplayName(ch), subtitle, "ch:" + ch,
                WING_COLORS[channelDisplayColor(ch)], () -> assignChannel(instrument, wingCh));
    }

    private String groupOf(Instrument instrument) {
        for (Group group : GROUPS) {
            for (Instrument candidate : group.instruments) {
                if (candidate == instrument) return group.name;
            }
        }
        return "";
    }

    private void assignChannel(Instrument instrument, int ch) {
        int previous = assignedChannel(instrument);
        if (validChannel(previous) && previous != ch) openGate(previous);
        assignments.put(instrument.id, ch);
        prefs().edit().putInt("assign_" + instrument.id, ch).apply();
        invalidateSettings();
        page = assignReturnPage;
        renderPage();
    }

    private void renderDrumColor() {
        addBackButton("Zpět", () -> {
            page = drumColorReturnPage == Page.CHANNELS && effectiveDrumColor() > 0
                    ? Page.CHANNELS : Page.GROUPS;
            renderPage();
        });
        addHeading("Barva bicích", "Kanály této barvy na pultu se zobrazí ve skupině Bicí");

        int auto = autoDrumColor();
        addPlainChoiceButton((drumColorSetting == 0 ? "✓  " : "") + "Automaticky",
                auto > 0 ? "Podle názvů kanálů: " + colorName(auto)
                        : "Podle názvů kanálů se barvu nepodařilo poznat",
                () -> setDrumColor(0));

        LinkedHashMap<Integer, List<String>> colors = new LinkedHashMap<>();
        for (int ch = 1; ch <= WING_CHANNELS; ch++) {
            if (!hasMixerName(ch)) continue;
            int color = channelDisplayColor(ch);
            List<String> names = colors.get(color);
            if (names == null) {
                names = new ArrayList<>();
                colors.put(color, names);
            }
            names.add(channelDisplayName(ch));
        }
        for (Map.Entry<Integer, List<String>> entry : colors.entrySet()) {
            final int color = entry.getKey();
            List<String> names = entry.getValue();
            String preview = TextUtils.join(", ", names.subList(0, Math.min(4, names.size())))
                    + (names.size() > 4 ? ", …" : "");
            addChoiceButton((drumColorSetting == color ? "✓  " : "") + colorName(color),
                    countLabel(names.size(), "kanál", "kanály", "kanálů") + ": " + preview,
                    "color:" + color, WING_COLORS[color], () -> setDrumColor(color));
        }
        if (colors.isEmpty()) {
            addInfo(wingConnected ? "Pult neposlal žádné pojmenované kanály"
                    : "Pult není připojený – barvy kanálů zatím neznám");
        }
    }

    private void setDrumColor(int color) {
        drumColorSetting = color;
        prefs().edit().putInt("drum_color", color).apply();
        selectedGroup = DRUM_GROUP;
        page = effectiveDrumColor() > 0 ? Page.CHANNELS : Page.GROUPS;
        renderPage();
    }

    private void addActiveInputsButton() {
        Button active = actionButton("AKTIVNÍ VSTUPY  ›", SURFACE_LIGHT, TEXT, dp(54));
        active.setTextSize(15);
        active.setOnClickListener(v -> openActiveInputs());
        content.addView(active, margins(0, 0, 0, dp(12)));
    }

    private void addTuningButton() {
        AutoSettings settings = settingsForKey(targetSettingsKey());
        String summary = "Práh " + settings.threshold + " dBFS"
                + (settings.gate ? " · ztlumení šumu" : "")
                + (settings.clip ? " · ochrana při lupání" : "");
        Button tuning = actionButton("CITLIVOST A AUTOMATIKA  ›\n" + summary,
                SURFACE_LIGHT, TEXT, dp(64));
        tuning.setTextSize(15);
        tuning.setOnClickListener(v -> {
            page = Page.TUNING;
            renderPage();
        });
        content.addView(tuning, margins(0, 0, 0, dp(12)));
    }

    private void addAutomationNotice(String text) {
        TextView notice = label(text, 15, Color.rgb(255, 190, 92));
        notice.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(12), dp(10), dp(12), dp(10));
        notice.setBackground(rounded(SURFACE, 12));
        content.addView(notice, margins(0, 0, 0, dp(8)));
    }

    private void renderTuning() {
        tuningMeterBar = null;
        tuningMeterLabel = null;
        addBackButton("Zpět", () -> {
            page = Page.LEVEL;
            renderPage();
        });
        Instrument instrument = selectedInstrument();
        int ch = targetChannel();
        String title = instrument != null ? instrument.name : channelDisplayName(ch);
        addHeading(title + " · citlivost", ch > 0
                ? "CH " + ch + " · " + channelDisplayName(ch) + " · AUX " + (selectedBus + 1)
                : "Nástroj nemá přiřazený kanál z pultu");
        if (ch <= 0) {
            addInfo("Nejdřív přiřaď kanál z pultu.");
            return;
        }
        String key = targetSettingsKey();
        AutoSettings settings = settingsForKey(key);

        LinearLayout meterCard = new LinearLayout(this);
        meterCard.setOrientation(LinearLayout.VERTICAL);
        meterCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        meterCard.setBackground(rounded(SURFACE, 16));
        tuningMeterLabel = label("", 16, TEXT);
        tuningMeterLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        meterCard.addView(tuningMeterLabel);
        tuningMeterBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        tuningMeterBar.setMax(60);
        tuningMeterBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(SURFACE_LIGHT));
        meterCard.addView(tuningMeterBar, margins(-dp(4), dp(10), -dp(4), 0));
        meterCard.addView(label(meterSourceLabel(), 12, MUTED_TEXT), margins(0, dp(6), 0, 0));
        content.addView(meterCard, margins(0, 0, 0, dp(14)));
        updateTuningMeterViews();

        TextView threshold = label("Práh: " + settings.threshold + " dBFS"
                + (settings.ownThreshold ? "" : " (výchozí)"), 19, ACCENT);
        threshold.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(threshold, margins(0, 0, 0, dp(4)));
        content.addView(label("Pod prahem je na kanálu jen šum, nad prahem hraje.", 13, MUTED_TEXT),
                margins(0, 0, 0, dp(8)));

        LinearLayout thresholdControls = new LinearLayout(this);
        Button moreSensitive = actionButton("VÍCE CITLIVÉ\n−3 dB", SURFACE, TEXT, dp(58));
        Button lessSensitive = actionButton("MÉNĚ CITLIVÉ\n+3 dB", SURFACE, TEXT, dp(58));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(62), 1);
        left.rightMargin = dp(6);
        thresholdControls.addView(moreSensitive, left);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(62), 1);
        right.leftMargin = dp(6);
        thresholdControls.addView(lessSensitive, right);
        content.addView(thresholdControls, margins(0, 0, 0, dp(8)));
        moreSensitive.setOnClickListener(v -> adjustTargetThreshold(key, settings.threshold - 3));
        lessSensitive.setOnClickListener(v -> adjustTargetThreshold(key, settings.threshold + 3));
        if (settings.ownThreshold) {
            Button reset = actionButton("Použít výchozí práh (" + activeThresholdDbfs + " dBFS)",
                    SURFACE, TEXT, dp(50));
            reset.setTextSize(15);
            reset.setOnClickListener(v -> {
                prefs().edit().remove("thr_" + key).apply();
                invalidateSettings();
                renderPage(false);
            });
            content.addView(reset, margins(0, 0, 0, dp(8)));
        }

        addToggle("AUTOMATICKY ZTLUMIT ŠUM", settings.gate,
                "Když je kanál déle než 1,5 s pod prahem (jen hluk ze sálu), vypne ho v tvém odposlechu."
                        + " Jakmile signál překročí práh, hned ho zase zapne. Mění jen tvůj AUX.",
                () -> toggleSetting(key, "gate_", !settings.gate));
        addToggle("OCHRANA PŘI LUPÁNÍ", settings.clip,
                "Když vstup dojde až k " + (int) CLIP_DBFS + " dBFS (přebuzení), sníží kanál v tvém odposlechu"
                        + " o " + CLIP_STEP_DB + " dB, nejvýš o " + CLIP_MAX_DB + " dB. Ušetří uši,"
                        + " ale zkreslení neodstraní – gain musí snížit zvukař.",
                () -> toggleSetting(key, "clip_", !settings.clip));
    }

    private void addToggle(String title, boolean on, String explanation, Runnable action) {
        Button toggle = actionButton(title + ": " + (on ? "ZAP" : "VYP"),
                on ? ACCENT : SURFACE_LIGHT, on ? BACKGROUND : TEXT, dp(64));
        toggle.setTextSize(17);
        toggle.setOnClickListener(v -> action.run());
        content.addView(toggle, margins(0, dp(12), 0, dp(4)));
        content.addView(label(explanation, 13, MUTED_TEXT), margins(dp(4), 0, dp(4), dp(6)));
    }

    private void updateTuningMeterViews() {
        if (tuningMeterBar == null || tuningMeterLabel == null) return;
        int ch = targetChannel();
        if (ch <= 0) return;
        AutoSettings settings = channelSettings(ch);
        float level = inputMeterDbfs[ch];
        boolean above = level >= settings.threshold;
        setTextIfChanged(tuningMeterLabel, "Vstup teď: " + formatDbfs(level)
                + (above ? "  · HRAJE" : "  · šum/ticho") + (gateClosed[ch] ? "  · ZTLUMENO" : ""));
        int textColor = above ? ACCENT : MUTED_TEXT;
        if (tuningMeterLabel.getCurrentTextColor() != textColor) tuningMeterLabel.setTextColor(textColor);
        tuningMeterBar.setProgress(levelToMeterProgress(level));
        tuningMeterBar.setSecondaryProgress(levelToMeterProgress(settings.threshold));
        int barColor = inputPeakDbfs[ch] >= CLIP_DBFS ? DANGER : ACCENT;
        if (!Integer.valueOf(barColor).equals(tuningMeterBar.getTag())) {
            tuningMeterBar.setTag(barColor);
            tuningMeterBar.setProgressTintList(android.content.res.ColorStateList.valueOf(barColor));
        }
    }

    /** Avoids redrawing (and accessibility events) when a live meter value has not changed. */
    private static void setTextIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private void adjustTargetThreshold(String key, int threshold) {
        int value = clamp(threshold, MIN_THRESHOLD_DBFS, MAX_THRESHOLD_DBFS);
        prefs().edit().putInt("thr_" + key, value).apply();
        invalidateSettings();
        renderPage(false);
    }

    private void toggleSetting(String key, String prefix, boolean enabled) {
        prefs().edit().putBoolean(prefix + key, enabled).apply();
        invalidateSettings();
        if (prefix.equals("gate_") && !enabled) {
            int ch = targetChannel();
            if (validChannel(ch)) openGate(ch);
        }
        renderPage(false);
    }

    private void openActiveInputs() {
        activeReturnPage = page;
        page = Page.ACTIVE;
        renderPage();
    }

    private void renderActiveInputs() {
        content.removeAllViews();
        menuItems.clear();
        menuMeterClips.clear();
        activeMeterBars.clear();
        activeMeterLabels.clear();
        addBackButton("Zpět", () -> {
            page = activeReturnPage;
            renderPage();
        });
        boolean bridgeFresh = metersFresh();
        String sourceStatus = wingMetersLive() ? "Úrovně živě z pultu WING"
                : !meterBridgeSeen || SystemClock.elapsedRealtime() - lastInputMessageAt >= 1500
                ? "Úrovně nepřichází z pultu ani ze simulátoru"
                : simulatedInputPlaying ? "Simulátor PC · " + simulatedSongName
                : "Simulátor PC připojen · stop";
        addHeading("Aktivní vstupy", sourceStatus);

        TextView threshold = label("Výchozí práh: " + activeThresholdDbfs + " dBFS", 17, ACCENT);
        threshold.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(threshold, margins(0, 0, 0, dp(2)));
        content.addView(label("Pro nástroje bez vlastního prahu (ten se nastaví u nástroje).",
                13, MUTED_TEXT), margins(0, 0, 0, dp(8)));

        LinearLayout thresholdControls = new LinearLayout(this);
        thresholdControls.setGravity(Gravity.CENTER_VERTICAL);
        Button moreSensitive = actionButton("VÍCE CITLIVÉ\n−3 dB", SURFACE, TEXT, dp(58));
        Button lessSensitive = actionButton("MÉNĚ CITLIVÉ\n+3 dB", SURFACE, TEXT, dp(58));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(62), 1);
        left.rightMargin = dp(6);
        thresholdControls.addView(moreSensitive, left);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(62), 1);
        right.leftMargin = dp(6);
        thresholdControls.addView(lessSensitive, right);
        content.addView(thresholdControls, margins(0, 0, 0, dp(12)));
        moreSensitive.setOnClickListener(v -> adjustActiveThreshold(-3));
        lessSensitive.setOnClickListener(v -> adjustActiveThreshold(3));

        List<Integer> activeChannels = new ArrayList<>();
        for (int ch = 1; ch <= WING_CHANNELS; ch++) {
            if (inputActive[ch]) activeChannels.add(ch);
        }
        activeChannels.sort((a, b) -> Float.compare(inputMeterDbfs[b], inputMeterDbfs[a]));

        if (!activeChannels.isEmpty()) {
            for (int ch : activeChannels) addActiveInputCard(ch);
        } else {
            TextView empty = label(bridgeFresh
                    ? "Žádný vstup není nad prahem"
                    : "Čekám na úrovně vstupů", 17, MUTED_TEXT);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(26), dp(12), dp(26));
            empty.setBackground(rounded(SURFACE, 16));
            content.addView(empty, margins(0, 0, 0, 0));
        }

        TextView explanation = label("Aktivace po 250 ms · vypnutí po 800 ms pod prahem\n"
                + "Zelené pozadí ukazuje vstupní úroveň", 12, MUTED_TEXT);
        explanation.setGravity(Gravity.CENTER);
        content.addView(explanation, margins(0, dp(14), 0, 0));
    }

    private String activeCardTitle(int ch) {
        List<String> names = new ArrayList<>();
        for (Group group : GROUPS) {
            for (Instrument instrument : group.instruments) {
                if (assignedChannel(instrument) == ch && !names.contains(instrument.name)) {
                    names.add(instrument.name);
                }
            }
        }
        return names.isEmpty() ? channelDisplayName(ch) : TextUtils.join(" / ", names);
    }

    private void addActiveInputCard(int ch) {
        String key = "ch:" + ch;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(SURFACE, 16));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(activeCardTitle(ch), 22, TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView level = label(formatDbfs(inputMeterDbfs[ch]), 16, ACCENT);
        level.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        top.addView(level);
        card.addView(top);

        ProgressBar meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(60);
        meter.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        meter.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(SURFACE_LIGHT));
        meter.setProgress(levelToMeterProgress(inputMeterDbfs[ch]));
        card.addView(meter, margins(-dp(4), dp(10), -dp(4), 0));

        TextView tag = label("CH " + ch + " · " + channelDisplayName(ch) + " · nad prahem "
                + channelSettings(ch).threshold + " dBFS", 12, MUTED_TEXT);
        card.addView(tag, margins(0, dp(6), 0, 0));
        card.setOnClickListener(v -> openFromChannel(ch));
        content.addView(card, margins(0, 0, 0, dp(10)));
        menuItems.add(card);
        activeMeterBars.put(key, meter);
        activeMeterLabels.put(key, level);
        if (menuItems.size() == 1) card.post(card::requestFocus);
    }

    private void openFromChannel(int ch) {
        for (int g = 0; g < GROUPS.length; g++) {
            Instrument[] instruments = GROUPS[g].instruments;
            for (int s = 0; s < instruments.length; s++) {
                if (assignedChannel(instruments[s]) == ch) {
                    openInstrument(g, s, Page.ACTIVE);
                    return;
                }
            }
        }
        openChannel(drumChannels().contains(ch) ? DRUM_GROUP : -1, ch, Page.ACTIVE);
    }

    private void adjustActiveThreshold(int delta) {
        activeThresholdDbfs = clamp(activeThresholdDbfs + delta, MIN_THRESHOLD_DBFS, MAX_THRESHOLD_DBFS);
        prefs().edit().putInt("active_threshold_dbfs", activeThresholdDbfs).apply();
        invalidateSettings();
        renderActiveInputs();
    }

    private int levelToMeterProgress(float dbfs) {
        return Math.max(0, Math.min(60, Math.round(dbfs + 60.0f)));
    }

    private String formatDbfs(float dbfs) {
        return String.format(Locale.ROOT, "%.0f dBFS", dbfs);
    }

    private void addHeading(String title, String subtitle) {
        TextView heading = label(title, 31, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(heading, margins(0, dp(8), 0, dp(2)));
        content.addView(label(subtitle, 16, MUTED_TEXT), margins(0, 0, 0, dp(16)));
    }

    private void addInfo(String text) {
        TextView info = label(text, 16, MUTED_TEXT);
        info.setGravity(Gravity.CENTER);
        info.setPadding(dp(12), dp(22), dp(12), dp(22));
        info.setBackground(rounded(SURFACE, 16));
        content.addView(info, margins(0, 0, 0, dp(10)));
    }

    private void addSectionHeader(String text, int color) {
        TextView header = label(text, 15, color);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(header, margins(dp(4), dp(10), 0, dp(8)));
    }

    private View addChoiceButton(String title, String subtitle, String meterKey,
            Integer stripeColor, Runnable action) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(stripeColor == null ? 18 : 12), dp(14), dp(18), dp(14));
        button.setMinimumHeight(dp(88));
        button.setFocusable(true);
        button.setClickable(true);

        if (stripeColor != null) {
            View stripe = new View(this);
            stripe.setBackground(rounded(stripeColor, 4));
            LinearLayout.LayoutParams stripeParams = new LinearLayout.LayoutParams(dp(7), dp(52));
            stripeParams.rightMargin = dp(12);
            button.addView(stripe, stripeParams);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(title, 23, TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        texts.addView(name);
        texts.addView(label(subtitle, 14, MUTED_TEXT), margins(0, dp(3), 0, 0));
        button.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        setChoiceBackground(button, meterKey, false);
        button.setOnFocusChangeListener((view, hasFocus) -> {
            setChoiceBackground(button, meterKey, hasFocus);
            name.setTextColor(hasFocus ? ACCENT : TEXT);
        });
        button.setOnClickListener(v -> action.run());
        content.addView(button, margins(0, 0, 0, dp(10)));
        menuItems.add(button);
        if (menuItems.size() == 1) button.post(button::requestFocus);
        return button;
    }

    // ---------------------------------------------------------------- Drag to reorder

    /** Saved order first, then any keys not saved yet in their default order. */
    private List<String> orderedKeys(String prefKey, List<String> defaultKeys) {
        List<String> ordered = new ArrayList<>();
        String saved = prefs().getString(prefKey, "");
        if (!saved.isEmpty()) {
            for (String key : saved.split(",")) {
                if (defaultKeys.contains(key) && !ordered.contains(key)) ordered.add(key);
            }
        }
        for (String key : defaultKeys) {
            if (!ordered.contains(key)) ordered.add(key);
        }
        return ordered;
    }

    @SuppressWarnings("ClickableViewAccessibility")
    private void makeReorderable(ReorderList list, View view, String key) {
        list.views.add(view);
        list.keys.add(key);
        view.setOnLongClickListener(v -> {
            startDrag(list, v);
            return true;
        });
        view.setOnTouchListener((v, event) -> {
            lastTouchRawY = event.getRawY();
            if (!dragging || v != dragView) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                updateDrag();
            } else if (action == MotionEvent.ACTION_UP) {
                finishDrag(true);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                finishDrag(false);
            }
            return true;
        });
    }

    private void startDrag(ReorderList list, View view) {
        int index = list.views.indexOf(view);
        if (index < 0 || list.views.size() < 2 || pageScroll == null) return;
        dragging = true;
        dragList = list;
        dragView = view;
        dragIndex = index;
        dragTarget = index;
        int count = list.views.size();
        dragTops = new int[count];
        dragSlots = new int[count];
        for (int i = 0; i < count; i++) {
            View item = list.views.get(i);
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) item.getLayoutParams();
            dragTops[i] = item.getTop();
            dragSlots[i] = item.getHeight() + params.topMargin + params.bottomMargin;
        }
        dragStartRawY = lastTouchRawY;
        dragStartScroll = pageScroll.getScrollY();
        view.getParent().requestDisallowInterceptTouchEvent(true);
        view.setElevation(dp(10));
        view.setScaleX(1.03f);
        view.setScaleY(1.03f);
        view.setAlpha(0.92f);
        pedalHandler.post(dragAutoScroll);
    }

    private void updateDrag() {
        if (!dragging || dragView == null) return;
        int last = dragTops.length - 1;
        float dy = (lastTouchRawY - dragStartRawY) + (pageScroll.getScrollY() - dragStartScroll);
        float minDy = dragTops[0] - dragTops[dragIndex];
        float maxDy = (dragTops[last] + dragSlots[last]) - (dragTops[dragIndex] + dragSlots[dragIndex]);
        dy = Math.max(minDy, Math.min(maxDy, dy));
        dragView.setTranslationY(dy);

        float top = dragTops[dragIndex] + dy;
        float bottom = top + dragSlots[dragIndex];
        int target = dragIndex;
        for (int i = dragIndex + 1; i <= last; i++) {
            if (bottom > dragTops[i] + dragSlots[i] / 2f) target = i;
        }
        for (int i = dragIndex - 1; i >= 0; i--) {
            if (top < dragTops[i] + dragSlots[i] / 2f) target = i;
        }
        if (target == dragTarget) return;
        dragTarget = target;
        for (int i = 0; i <= last; i++) {
            if (i == dragIndex) continue;
            float shift = 0;
            if (dragIndex < i && i <= target) shift = -dragSlots[dragIndex];
            else if (target <= i && i < dragIndex) shift = dragSlots[dragIndex];
            dragList.views.get(i).animate().translationY(shift).setDuration(120).start();
        }
    }

    private void finishDrag(boolean commit) {
        if (!dragging) return;
        dragging = false;
        pedalHandler.removeCallbacks(dragAutoScroll);
        ReorderList list = dragList;
        int from = dragIndex;
        int to = dragTarget;
        dragList = null;
        dragView = null;
        if (commit && from != to) {
            List<String> keys = new ArrayList<>(list.keys);
            keys.add(to, keys.remove(from));
            // Keep saved keys that are not visible right now (e.g. Master during first setup).
            String saved = prefs().getString(list.prefKey, "");
            if (!saved.isEmpty()) {
                for (String key : saved.split(",")) {
                    if (!keys.contains(key)) keys.add(key);
                }
            }
            prefs().edit().putString(list.prefKey, TextUtils.join(",", keys)).apply();
        }
        renderPage(false);
    }

    private void addPlainChoiceButton(String title, String subtitle, Runnable action) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(18), dp(14), dp(18), dp(14));
        button.setMinimumHeight(dp(88));
        button.setBackground(rounded(SURFACE, 16));
        button.setFocusable(true);
        button.setClickable(true);
        TextView name = label(title, 23, TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.addView(name);
        button.addView(label(subtitle, 14, MUTED_TEXT), margins(0, dp(3), 0, 0));
        button.setOnFocusChangeListener((view, hasFocus) -> {
            view.setBackground(rounded(hasFocus ? SURFACE_LIGHT : SURFACE, 16));
            name.setTextColor(hasFocus ? ACCENT : TEXT);
        });
        button.setOnClickListener(v -> action.run());
        content.addView(button, margins(0, 0, 0, dp(10)));
        menuItems.add(button);
        if (menuItems.size() == 1) button.post(button::requestFocus);
    }

    private void setChoiceBackground(LinearLayout view, String meterKey, boolean focused) {
        setMeterBackground(view, meterKey, focused ? SURFACE_LIGHT : SURFACE,
                focused ? 120 : 82, 16);
    }

    private void setMeterBackground(View view, String meterKey, int baseColor, int fillAlpha,
            int radiusDp) {
        GradientDrawable base = rounded(baseColor, radiusDp);
        GradientDrawable fill = rounded(Color.argb(fillAlpha, 98, 214, 163), radiusDp);
        ClipDrawable clip = new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        clip.setLevel(menuMeterLevel(meterKey));
        view.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{base, clip}));
        menuMeterClips.put(meterKey, clip);
    }

    private int menuMeterLevel(String meterKey) {
        float dbfs = -72.0f;
        String[] parts = meterKey.split(":");
        try {
            if (parts[0].equals("group")) {
                for (int ch : groupChannels(Integer.parseInt(parts[1]))) {
                    if (inputMeterDbfs[ch] >= channelSettings(ch).threshold) {
                        dbfs = Math.max(dbfs, inputMeterDbfs[ch]);
                    }
                }
                if (dbfs <= -72.0f) return 0;
            } else if (parts[0].equals("inst")) {
                Instrument instrument = GROUPS[Integer.parseInt(parts[1])]
                        .instruments[Integer.parseInt(parts[2])];
                int ch = assignedChannel(instrument);
                if (ch <= 0 || inputMeterDbfs[ch] < channelSettings(ch).threshold) return 0;
                dbfs = inputMeterDbfs[ch];
            } else if (parts[0].equals("ch")) {
                int ch = Integer.parseInt(parts[1]);
                if (!validChannel(ch) || inputMeterDbfs[ch] < channelSettings(ch).threshold) return 0;
                dbfs = inputMeterDbfs[ch];
            } else {
                return 0;
            }
        } catch (RuntimeException ignored) {
            return 0;
        }
        return Math.max(0, Math.min(10_000, Math.round((dbfs + 60.0f) / 60.0f * 10_000.0f)));
    }

    private void refreshMenuMeters() {
        for (Map.Entry<String, ClipDrawable> entry : menuMeterClips.entrySet()) {
            entry.getValue().setLevel(menuMeterLevel(entry.getKey()));
        }
    }

    private void addBackButton(String title, Runnable action) {
        Button button = actionButton("‹   " + title, SURFACE_LIGHT, TEXT, dp(58));
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setOnClickListener(v -> action.run());
        content.addView(button, margins(0, 0, 0, dp(14)));
    }

    private LinearLayout adjustButton(String symbol, String hint) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(rounded(SURFACE, 18));

        TextView largeSymbol = label(symbol, 52, TEXT);
        largeSymbol.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        largeSymbol.setGravity(Gravity.CENTER);
        button.addView(largeSymbol);

        TextView caption = label(hint, 16, MUTED_TEXT);
        caption.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        caption.setGravity(Gravity.CENTER);
        button.addView(caption);
        return button;
    }

    private Button actionButton(String title, int background, int foreground, int height) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(19);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setMinHeight(height);
        button.setBackground(rounded(background, 14));
        return button;
    }

    private void addDemoNote() {
        boolean levelPage = page == Page.LEVEL || page == Page.MASTER;
        String footHelp = levelPage
                ? "Pedál: levá/pravá hlasitost · střed návrat / podrž mute"
                : "Pedál: levá/pravá výběr · střed otevřít";
        String mixLine = wingConnected
                ? "WING připojen: " + wingConsoleName + " (" + wingHost + ":2222)"
                : "OFFLINE — pult nepřipojen, změny se ukládají lokálně";
        String reorderHelp = page == Page.GROUPS || page == Page.CHANNELS
                ? "\nPodrž tlačítko a táhni prstem = změna pořadí" : "";
        TextView note = label(mixLine + "\n" + meterSourceLabel() + "\n"
                + "Zelené pozadí = vstup nad prahem\n" + footHelp + reorderHelp, 12, MUTED_TEXT);
        note.setGravity(Gravity.CENTER);
        content.addView(note, margins(0, dp(18), 0, 0));
    }

    // ---------------------------------------------------------------- Local mix changes

    private void changeLevel(int delta) {
        int ch = targetChannel();
        if (ch <= 0) return;
        float current = sendLevelDb[selectedBus][ch];
        int base = current <= MIN_DB ? MIN_DB : Math.round(current);
        int next = clamp(base + delta, MIN_DB, MAX_DB);
        // The player took over; clip protection starts counting again from this level.
        clipReductionDb[ch] = 0;
        writeSendLevel(ch, next);
        if (levelValue != null) levelValue.setText(formatDb(next));
    }

    private void writeSendLevel(int ch, int levelDb) {
        sendLevelDb[selectedBus][ch] = levelDb;
        prefs().edit().putFloat("lvl_" + selectedBus + "_" + ch, levelDb).apply();
        lastLocalMixWriteAt = SystemClock.elapsedRealtime();
        if (wingClient != null) {
            wingClient.sendChannelSendLevel(ch, selectedBus + 1, levelDb <= MIN_DB ? WIRE_OFF_DB : levelDb);
        }
    }

    private void toggleTargetMute() {
        int ch = targetChannel();
        if (ch <= 0) return;
        boolean nextOn = !sendOn[selectedBus][ch];
        sendOn[selectedBus][ch] = nextOn;
        prefs().edit().putBoolean("on_" + selectedBus + "_" + ch, nextOn).apply();
        lastLocalMixWriteAt = SystemClock.elapsedRealtime();
        // A manual mute/unmute overrides the automation for this channel.
        gateClosed[ch] = false;
        gateLastAbove[ch] = SystemClock.elapsedRealtime();
        autoMutedOnConsole[ch] = false;
        if (wingClient != null) wingClient.sendChannelSendOn(ch, selectedBus + 1, nextOn);
        renderPage(false);
    }

    private void changeMaster(int delta) {
        float current = masterDb[selectedBus];
        int base = current <= MIN_DB ? MIN_DB : Math.round(current);
        int next = clamp(base + delta, MIN_DB, MAX_DB);
        masterDb[selectedBus] = next;
        if (levelValue != null) levelValue.setText(formatDb(next));
        prefs().edit().putFloat("master_" + selectedBus, next).apply();
        lastLocalMixWriteAt = SystemClock.elapsedRealtime();
        if (wingClient != null) {
            wingClient.sendBusFader(selectedBus + 1, next <= MIN_DB ? WIRE_OFF_DB : next);
        }
    }

    private void toggleMasterMute() {
        boolean nextMuted = !masterMuted[selectedBus];
        masterMuted[selectedBus] = nextMuted;
        prefs().edit().putBoolean("master_mute_" + selectedBus, nextMuted).apply();
        lastLocalMixWriteAt = SystemClock.elapsedRealtime();
        if (wingClient != null) wingClient.sendBusMute(selectedBus + 1, nextMuted);
        renderPage();
    }

    // ---------------------------------------------------------------- Remote mixer updates

    private boolean isRecentLocalWrite() {
        return SystemClock.elapsedRealtime() - lastLocalMixWriteAt < 250;
    }

    private boolean isTargetOnScreen(int bus, int ch) {
        return page == Page.LEVEL && bus == selectedBus && ch == targetChannel();
    }

    private void applyRemoteSendLevel(int ch, int auxBus, float levelDb) {
        if (!validChannel(ch) || auxBus < 1 || auxBus > MIX_COUNT) return;
        int bus = auxBus - 1;
        boolean target = isTargetOnScreen(bus, ch);
        if (target && isRecentLocalWrite()) return;
        if (Math.abs(sendLevelDb[bus][ch] - levelDb) < 0.01f) return;
        sendLevelDb[bus][ch] = levelDb;
        if (bus == selectedBus) mixerBusValuesChanged = true;
        if (target) mixerTargetValueChanged = true;
        scheduleMixerRefresh();
    }

    private void applyRemoteSendOn(int ch, int auxBus, boolean on) {
        if (!validChannel(ch) || auxBus < 1 || auxBus > MIX_COUNT) return;
        int bus = auxBus - 1;
        if (bus == selectedBus && (gateClosed[ch] || autoMutedOnConsole[ch])) {
            if (!on) {
                // Our own automatic mute (or a stale one after reconnecting); not the player's choice.
                if (!gateClosed[ch]) restoreConsoleOn(ch);
                return;
            }
            // Someone switched it back on at the console or in WING-Q: that wins over the automation.
            autoMutedOnConsole[ch] = false;
            if (gateClosed[ch]) {
                gateClosed[ch] = false;
                gateLastAbove[ch] = SystemClock.elapsedRealtime();
                onAutomationStateChanged(ch);
            }
        }
        boolean target = isTargetOnScreen(bus, ch);
        if (target && isRecentLocalWrite()) return;
        if (sendOn[bus][ch] == on) return;
        sendOn[bus][ch] = on;
        if (bus == selectedBus) mixerBusValuesChanged = true;
        if (target) mixerTargetStateChanged = true;
        scheduleMixerRefresh();
    }

    private void applyRemoteMaster(int auxBus, float levelDb) {
        if (auxBus < 1 || auxBus > MIX_COUNT) return;
        int bus = auxBus - 1;
        boolean target = page == Page.MASTER && bus == selectedBus;
        if (target && isRecentLocalWrite()) return;
        if (Math.abs(masterDb[bus] - levelDb) < 0.01f) return;
        masterDb[bus] = levelDb;
        if (bus == selectedBus) mixerBusValuesChanged = true;
        if (target) mixerTargetValueChanged = true;
        scheduleMixerRefresh();
    }

    private void applyRemoteMasterMute(int auxBus, boolean muted) {
        if (auxBus < 1 || auxBus > MIX_COUNT) return;
        int bus = auxBus - 1;
        boolean target = page == Page.MASTER && bus == selectedBus;
        if (target && isRecentLocalWrite()) return;
        if (masterMuted[bus] == muted) return;
        masterMuted[bus] = muted;
        if (bus == selectedBus) mixerBusValuesChanged = true;
        if (target) mixerTargetStateChanged = true;
        scheduleMixerRefresh();
    }

    private void applyChannelName(int ch, String name, boolean activeName) {
        if (!validChannel(ch)) return;
        String value = name == null ? "" : name;
        if (activeName) {
            if (value.equals(channelActName[ch])) return;
            channelActName[ch] = value;
        } else {
            if (value.equals(channelName[ch])) return;
            channelName[ch] = value;
        }
        mixerStructureChanged = true;
        scheduleMixerRefresh();
    }

    private void applyChannelColor(int ch, int color, boolean activeColor) {
        if (!validChannel(ch)) return;
        int value = clamp(color, 0, WING_COLORS.length - 1);
        if (activeColor) {
            if (channelActColor[ch] == value) return;
            channelActColor[ch] = value;
        } else {
            if (channelColor[ch] == value) return;
            channelColor[ch] = value;
        }
        mixerStructureChanged = true;
        scheduleMixerRefresh();
    }

    private void applyBusName(int auxBus, String name, boolean activeName) {
        if (auxBus < 1 || auxBus > MIX_COUNT) return;
        String value = name == null ? "" : name;
        if (activeName) {
            if (value.equals(busActName[auxBus])) return;
            busActName[auxBus] = value;
        } else {
            if (value.equals(busName[auxBus])) return;
            busName[auxBus] = value;
        }
        mixerStructureChanged = true;
        scheduleMixerRefresh();
    }

    private void scheduleMixerRefresh() {
        if (mixerRefreshPending) return;
        mixerRefreshPending = true;
        pedalHandler.postDelayed(mixerRefresh, 120);
    }

    private void runMixerRefresh() {
        mixerRefreshPending = false;
        if (dragging) {
            scheduleMixerRefresh();
            return;
        }
        boolean structure = mixerStructureChanged;
        boolean busValues = mixerBusValuesChanged;
        boolean targetValue = mixerTargetValueChanged;
        boolean targetState = mixerTargetStateChanged;
        mixerStructureChanged = false;
        mixerBusValuesChanged = false;
        mixerTargetValueChanged = false;
        mixerTargetStateChanged = false;

        if (structure) persistMixerNames();
        if (page == Page.LEVEL || page == Page.MASTER) {
            if (structure || targetState) {
                renderPage();
            } else if (targetValue && levelValue != null) {
                levelValue.setText(page == Page.MASTER
                        ? formatDb(masterDb[selectedBus])
                        : formatDb(sendLevelDb[selectedBus][targetChannel()]));
            }
        } else if (structure || (busValues && (page == Page.GROUPS || page == Page.CHANNELS))) {
            renderPagePreservingFocus();
        }
    }

    private void persistMixerNames() {
        SharedPreferences.Editor editor = prefs().edit();
        for (int ch = 1; ch <= WING_CHANNELS; ch++) {
            editor.putString("mix_name_" + ch, hasMixerName(ch) ? channelDisplayName(ch) : "");
            editor.putInt("mix_col_" + ch, channelDisplayColor(ch));
        }
        for (int bus = 1; bus <= MIX_COUNT; bus++) {
            String name = busActName[bus] != null && !busActName[bus].isEmpty() ? busActName[bus] : busName[bus];
            editor.putString("bus_name_" + bus, name == null ? "" : name);
        }
        editor.apply();
    }

    private void updateMixerConnection(boolean connected, String host, String consoleName) {
        boolean becameConnected = connected && !wingConnected;
        boolean changed = (wingConnected != connected)
                || !String.valueOf(wingHost).equals(String.valueOf(host))
                || (consoleName != null && !consoleName.isEmpty() && !consoleName.equals(wingConsoleName));
        wingConnected = connected;
        wingHost = host == null ? "" : host;
        if (consoleName != null && !consoleName.isEmpty()) {
            wingConsoleName = consoleName;
        }
        if (becameConnected) {
            // Active ($) names and colours are re-sent in the dump; forget the previous console's.
            Arrays.fill(channelActName, null);
            Arrays.fill(channelActColor, -1);
            Arrays.fill(busActName, null);
        }
        if (mixerBadge != null) {
            mixerBadge.setText(connected ? "MIX: ONLINE" : "MIX: OFFLINE");
            mixerBadge.setTextColor(connected ? ACCENT : MUTED_TEXT);
        }
        if (changed) {
            renderPagePreservingFocus();
        }
    }

    private void configureMixerHost() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(isRunningInEmulator() ? "prázdné = auto (10.0.2.2)" : "prázdné = auto hledání v LAN");
        input.setText(wingClient == null ? "" : wingClient.getConfiguredHost());
        String status = wingConnected
                ? "Připojeno k " + wingConsoleName + " (" + wingHost + ":2222)"
                : "Nepřipojeno — používá protokol WING Native přes WebSocket/2222";
        new AlertDialog.Builder(this)
                .setTitle("Spojení s mixpultem WING")
                .setMessage(status)
                .setView(input)
                .setPositiveButton("Uložit a připojit", (dialog, which) -> {
                    String host = input.getText().toString().trim();
                    prefs().edit().putString("wing_host", host).apply();
                    if (wingClient != null) {
                        wingClient.setConfiguredHostAndReconnect(host);
                    }
                })
                .setNegativeButton("Zavřít", null)
                .show();
    }

    // ---------------------------------------------------------------- Primary instrument / bus

    private boolean isPrimaryInstrument() {
        SharedPreferences preferences = prefs();
        if (!preferences.getBoolean("primary_set", false)) return false;
        if (preferences.getInt("primary_group", -2) != selectedGroup
                || preferences.getInt("primary_slot", -2) != selectedSlot) {
            return false;
        }
        return selectedSlot >= 0 || preferences.getInt("primary_ch", -1) == selectedWingCh;
    }

    private void savePrimary() {
        prefs().edit()
                .putBoolean("primary_set", true)
                .putInt("primary_group", selectedGroup)
                .putInt("primary_slot", selectedSlot)
                .putInt("primary_ch", selectedSlot >= 0 ? 0 : selectedWingCh)
                .apply();
    }

    private void setAsPrimary() {
        savePrimary();
        levelReturnPage = Page.GROUPS;
        Toast.makeText(this, "Hlavní nástroj uložen", Toast.LENGTH_SHORT).show();
        renderPage();
    }

    private void chooseBus() {
        String[] buses = new String[MIX_COUNT];
        for (int bus = 0; bus < MIX_COUNT; bus++) {
            String custom = customBusName(bus + 1);
            buses[bus] = "Aux " + (bus + 1) + (custom.isEmpty() ? "" : " · " + custom)
                    + (bus == selectedBus ? "  ✓" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Vyber svůj odposlech")
                .setItems(buses, (dialog, which) -> {
                    if (which != selectedBus) resetAutomationForBusChange();
                    selectedBus = which;
                    busSetup = false;
                    prefs().edit()
                            .putInt("selected_bus", selectedBus)
                            .putBoolean("selected_bus_set", true)
                            .apply();
                    renderPage();
                })
                .setNegativeButton("Zrušit", null)
                .show();
    }

    // ---------------------------------------------------------------- Formatting / views

    private String formatDb(float db) {
        if (db <= MIN_DB) return "−∞ dB";
        float rounded = Math.round(db * 10.0f) / 10.0f;
        if (Math.abs(rounded - Math.round(rounded)) < 0.05f) {
            int whole = Math.round(rounded);
            return (whole > 0 ? "+" : "") + whole + " dB";
        }
        return String.format(Locale.ROOT, rounded > 0 ? "+%.1f dB" : "%.1f dB", rounded);
    }

    private TextView label(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- Navigation

    @Override
    public void onBackPressed() {
        if (page == Page.LEVEL) {
            page = levelReturnPage;
            renderPage();
        } else if (page == Page.MASTER) {
            page = Page.GROUPS;
            renderPage();
        } else if (page == Page.ACTIVE) {
            page = activeReturnPage;
            renderPage();
        } else if (page == Page.TUNING) {
            page = Page.LEVEL;
            renderPage();
        } else if (page == Page.ASSIGN) {
            page = assignReturnPage;
            renderPage();
        } else if (page == Page.DRUM_COLOR) {
            page = drumColorReturnPage == Page.CHANNELS && effectiveDrumColor() > 0
                    ? Page.CHANNELS : Page.GROUPS;
            renderPage();
        } else if (page == Page.CHANNELS) {
            page = Page.GROUPS;
            renderPage();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean centerKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER;

        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                onBackPressed();
            }
            return true;
        }

        if (centerKey) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    keyboardCenterDown = true;
                    centerLongActionDone = false;
                    if (keyboardCenterLongAction != null) {
                        pedalHandler.removeCallbacks(keyboardCenterLongAction);
                    }
                    keyboardCenterLongAction = () -> {
                        if (keyboardCenterDown && !centerLongActionDone) {
                            centerLongActionDone = true;
                            handleCenterLongPress();
                        }
                    };
                    pedalHandler.postDelayed(keyboardCenterLongAction, 650);
                } else if (event.isLongPress() && !centerLongActionDone) {
                    centerLongActionDone = true;
                    handleCenterLongPress();
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                keyboardCenterDown = false;
                if (keyboardCenterLongAction != null) {
                    pedalHandler.removeCallbacks(keyboardCenterLongAction);
                }
                if (!centerLongActionDone) handleCenterTap();
                return true;
            }
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                handleFootDirection(BluetoothFootPedalServer.LEFT);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                handleFootDirection(BluetoothFootPedalServer.RIGHT);
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int focusedMenuIndex() {
        for (int i = 0; i < menuItems.size(); i++) {
            if (menuItems.get(i).hasFocus()) return i;
        }
        return -1;
    }

    private void moveMenuFocus(int delta) {
        if (menuItems.isEmpty()) return;
        int focused = focusedMenuIndex();
        // Pedal events do not leave touch mode by themselves; requestFocusFromTouch does.
        if (focused < 0) {
            menuItems.get(0).requestFocusFromTouch();
            return;
        }
        int next = (focused + delta + menuItems.size()) % menuItems.size();
        menuItems.get(next).requestFocusFromTouch();
    }

    private void activateFocusedChoice() {
        for (View item : menuItems) {
            if (item.hasFocus()) {
                item.performClick();
                return;
            }
        }
        if (!menuItems.isEmpty()) menuItems.get(0).performClick();
    }
}
