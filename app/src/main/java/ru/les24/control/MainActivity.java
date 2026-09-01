package ru.les24.control;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.hardware.usb.*;
import android.net.Uri;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import android.graphics.Typeface;

import com.hoho.android.usbserial.driver.*;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class MainActivity extends Activity implements SerialInputOutputManager.Listener {
    private static final int BAUD = 115200;
    private static final String USB_PERMISSION = "ru.les24.control.USB_PERMISSION";
    private static final int REQ_SAVE_XML = 301;
    private static final String EULA_VERSION = "2.0-RU-2026";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService usbExecutor = Executors.newSingleThreadExecutor();
    private UsbManager usbManager;
    private UsbSerialPort port;
    private SerialInputOutputManager ioManager;
    private boolean connected = false;

    private LinearLayout pageHost;
    private TextView connectionStatus;
    private Button connectButton;
    private WebView analyzerWeb;
    private TextView terminalView;
    private final StringBuilder rxBuffer = new StringBuilder();
    private final ArrayList<LogRow> session = new ArrayList<>();
    private boolean analyzerDirty = false;

    private String currentMode = "";

    // Links
    private TextView linksState, linksInstruction, linksTarget;
    private TableLayout linksApTable, linksStaTable;
    private Button linksStart, linksRescan, linksBack, linksExit;
    private final LinkedHashMap<Integer, LinkAp> linkAps = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, LinkSta> linkStas = new LinkedHashMap<>();
    private String linksPhase = "idle";

    // RSSI localization
    private TextView indoorState, indoorInstruction, indoorBoot;
    private ProgressBar indoorProgress;
    private IndoorMapView indoorMap;
    private LinearLayout indoorPoints;
    private TableLayout indoorDevices;
    private Button indoorStart, indoorExit;
    private final LinkedHashSet<String> indoorDone = new LinkedHashSet<>();
    private final LinkedHashMap<String, ArrayList<Integer>> indoorPointRssi = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, IndoorDevice> indoorDeviceMap = new LinkedHashMap<>();
    private String indoorCapturePoint = null;
    private boolean indoorDeviceList = false;
    private boolean indoorReady = false;
    private boolean asciiCapture = false;
    private final ArrayList<String> asciiLines = new ArrayList<>();
    private String indoorSelectedLabel = "";

    private final int BG = Color.rgb(11,17,24);
    private final int CARD = Color.rgb(17,28,39);
    private final int CARD2 = Color.rgb(20,32,44);
    private final int TEXT = Color.rgb(231,240,247);
    private final int MUTED = Color.rgb(142,161,181);
    private final int BRAND = Color.rgb(30,115,206);
    private final int LINE = Color.rgb(38,57,76);
    private final int GREEN = Color.rgb(85,214,158);
    private final int RED = Color.rgb(239,93,104);

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    openDevice(device);
                } else {
                    toast("Доступ к USB не разрешён");
                }
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        registerUsbReceiver();
        buildUi();
        showEulaIfNeeded();
        ui.postDelayed(analyzerPump, 800);
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, filter);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(10), dp(10), dp(10), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        connectionStatus = label("LES24 · не подключено", true, 15);
        header.addView(connectionStatus, new LinearLayout.LayoutParams(0, dp(44), 1));
        connectButton = action("Подключить", true);
        connectButton.setOnClickListener(v -> connectOrDisconnect());
        header.addView(connectButton, lpWrap());
        Button save = action("XML", false);
        save.setOnClickListener(v -> saveXml());
        header.addView(save, lpWrap());
        root.addView(header, lpMatchWrap());

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        String[] tabs = {"Обзор", "Links AP→STA", "RSSI-локализация", "Терминал", "О программе"};
        for (String t : tabs) {
            Button b = navButton(t);
            b.setOnClickListener(v -> showPage(t));
            nav.addView(b, lpWrap());
        }
        navScroll.addView(nav);
        root.addView(navScroll, lpMatchWrap());

        pageHost = new LinearLayout(this);
        pageHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        showPage("Обзор");
    }

    private void showPage(String name) {
        pageHost.removeAllViews();
        if (name.equals("Обзор")) pageHost.addView(buildOverview(), lpMatch());
        else if (name.equals("Links AP→STA")) pageHost.addView(buildLinks(), lpMatch());
        else if (name.equals("RSSI-локализация")) pageHost.addView(buildIndoor(), lpMatch());
        else if (name.equals("Терминал")) pageHost.addView(buildTerminal(), lpMatch());
        else pageHost.addView(buildAbout(), lpMatch());
    }

    private View buildOverview() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);

        HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        addMode(actions, "Мониторинг", "1");
        addMode(actions, "Wi‑Fi AP", "2");
        addMode(actions, "Wi‑Fi STA", "3");
        addMode(actions, "BLE", "4");
        Button stop = action("■ В меню", false);
        stop.setOnClickListener(v -> sendCommand("m"));
        actions.addView(stop, lpWrap());
        actionsScroll.addView(actions);
        box.addView(actionsScroll, lpMatchWrap());

        analyzerWeb = new WebView(this);
        analyzerWeb.setBackgroundColor(BG);
        analyzerWeb.getSettings().setJavaScriptEnabled(true);
        analyzerWeb.getSettings().setAllowFileAccess(true);
        analyzerWeb.getSettings().setDomStorageEnabled(true);
        analyzerWeb.loadUrl("file:///android_asset/analyzer.html");
        analyzerWeb.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { analyzerDirty = true; }
        });
        box.addView(analyzerWeb, new LinearLayout.LayoutParams(-1, 0, 1));
        return box;
    }

    private void addMode(LinearLayout row, String title, String cmd) {
        Button b = action(title, cmd.equals("1"));
        b.setOnClickListener(v -> sendCommand(cmd));
        row.addView(b, lpWrap());
    }

    private View buildLinks() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = vertical();
        box.setPadding(0, dp(8), 0, dp(20));

        linksState = chip("ОЖИДАНИЕ");
        box.addView(titleRow("Links AP → STA", linksState));
        linksInstruction = cardText("Запустите режим. LES24 сформирует список доступных AP.");
        box.addView(linksInstruction, lpMatchWrap());

        LinearLayout buttons = horizontalWrap();
        linksStart = action("▶ Запустить", true); linksStart.setOnClickListener(v -> { resetLinks(); sendCommand("6"); });
        linksRescan = action("↻ STA", false); linksRescan.setOnClickListener(v -> sendRaw("r")); linksRescan.setEnabled(false);
        linksBack = action("← AP", false); linksBack.setOnClickListener(v -> sendRaw("b")); linksBack.setEnabled(false);
        linksExit = action("■ В меню", false); linksExit.setOnClickListener(v -> sendRaw("m")); linksExit.setEnabled(false);
        buttons.addView(linksStart); buttons.addView(linksRescan); buttons.addView(linksBack); buttons.addView(linksExit);
        box.addView(buttons, lpMatchWrap());

        box.addView(section("Точки доступа"));
        linksApTable = new TableLayout(this); styleTable(linksApTable);
        renderLinkAps(); box.addView(linksApTable, lpMatchWrap());

        linksTarget = section("Связанные STA · AP не выбрана"); box.addView(linksTarget);
        linksStaTable = new TableLayout(this); styleTable(linksStaTable);
        renderLinkStas(); box.addView(linksStaTable, lpMatchWrap());
        sc.addView(box); return sc;
    }

    private View buildIndoor() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = vertical(); box.setPadding(0,dp(8),0,dp(20));
        indoorState = chip("ОЖИДАНИЕ"); box.addView(titleRow("RSSI-локализация", indoorState));
        indoorInstruction = cardText("Пошаговая локализация по пяти контрольным точкам A0 → A1 → A2 → A3 → C."); box.addView(indoorInstruction);
        LinearLayout acts = horizontalWrap();
        indoorStart = action("▶ Запустить", true); indoorStart.setOnClickListener(v -> { resetIndoor(); sendCommand("9"); });
        indoorExit = action("■ В меню", false); indoorExit.setEnabled(false); indoorExit.setOnClickListener(v -> { if (indoorReady) sendRaw("0"); });
        acts.addView(indoorStart); acts.addView(indoorExit); box.addView(acts);
        indoorProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); indoorProgress.setMax(5); indoorProgress.setProgress(0); box.addView(indoorProgress, new LinearLayout.LayoutParams(-1,dp(16)));
        indoorBoot = muted("BOOT: —"); box.addView(indoorBoot);
        indoorMap = new IndoorMapView(this); box.addView(indoorMap, new LinearLayout.LayoutParams(-1,dp(380)));
        box.addView(section("Контрольные точки"));
        indoorPoints = vertical(); renderIndoorPoints(); box.addView(indoorPoints);
        box.addView(section("Найденные устройства"));
        indoorDevices = new TableLayout(this); styleTable(indoorDevices); renderIndoorDevices(); box.addView(indoorDevices);
        sc.addView(box); return sc;
    }

    private View buildTerminal() {
        LinearLayout box = vertical(); box.setPadding(0,dp(8),0,0);
        ScrollView scroll = new ScrollView(this);
        terminalView = new TextView(this); terminalView.setTextColor(TEXT); terminalView.setTextSize(12); terminalView.setTypeface(Typeface.MONOSPACE); terminalView.setText(buildTerminalSnapshot()); terminalView.setPadding(dp(8),dp(8),dp(8),dp(8));
        scroll.addView(terminalView); box.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout cmd = new LinearLayout(this); cmd.setOrientation(LinearLayout.HORIZONTAL);
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Команда"); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setBackgroundColor(CARD2);
        Button send = action("Отправить", true); send.setOnClickListener(v -> { String s=input.getText().toString().trim(); if(!s.isEmpty()){ sendRaw(s); input.setText(""); }});
        cmd.addView(input,new LinearLayout.LayoutParams(0,dp(48),1)); cmd.addView(send,lpWrap()); box.addView(cmd);
        return box;
    }

    private View buildAbout() {
        ScrollView sc = new ScrollView(this); LinearLayout box = vertical(); box.setPadding(dp(4),dp(8),dp(4),dp(20));
        box.addView(title("LES24 Control · Android"));
        box.addView(cardText("LES24 — устройство мониторинга активности в диапазоне 2.4 ГГц для выявления подозрительных Wi‑Fi AP, Wi‑Fi STA и BLE-устройств. Android-версия работает с существующей прошивкой LES24 через USB Serial 115200 и не требует изменений прибора."));
        box.addView(section("Возможности"));
        box.addView(body("• Мониторинг AP / STA / BLE\n• Live Analyzer\n• Links AP → STA\n• RSSI-локализация по 5 точкам\n• XML-лог, совместимый с desktop-версией\n• Сервисный терминал"));
        Button eula = action("Открыть EULA", false); eula.setOnClickListener(v -> showEulaDialog(false)); box.addView(eula,lpWrap());
        sc.addView(box); return sc;
    }

    private void connectOrDisconnect() {
        if (connected) { disconnectSerial(); return; }
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) { toast("USB Serial устройство не найдено. Подключите LES24 через OTG/USB-C."); return; }
        UsbDevice device = drivers.get(0).getDevice();
        if (usbManager.hasPermission(device)) openDevice(device);
        else {
            Intent intent = new Intent(USB_PERMISSION).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
            usbManager.requestPermission(device, pi);
        }
    }

    private void openDevice(UsbDevice device) {
        try {
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
            UsbSerialDriver selected = null;
            for (UsbSerialDriver d : drivers) if (d.getDevice().getDeviceId()==device.getDeviceId()) { selected=d; break; }
            if (selected == null || selected.getPorts().isEmpty()) throw new IOException("Serial driver not found");
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) throw new IOException("Cannot open USB device");
            port = selected.getPorts().get(0);
            port.open(connection);
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            try { port.setDTR(true); } catch(Exception ignored) {}
            try { port.setRTS(true); } catch(Exception ignored) {}
            ioManager = new SerialInputOutputManager(port, this);
            usbExecutor.submit(ioManager);
            connected = true;
            connectionStatus.setText("LES24 · подключено · 115200"); connectionStatus.setTextColor(GREEN);
            connectButton.setText("Отключить");
            toast("LES24 подключён");
        } catch(Exception e) { toast("Ошибка USB: "+e.getMessage()); disconnectSerial(); }
    }

    private void disconnectSerial() {
        try { if (ioManager != null) ioManager.stop(); } catch(Exception ignored) {}
        try { if (port != null) port.close(); } catch(Exception ignored) {}
        ioManager=null; port=null; connected=false;
        connectionStatus.setText("LES24 · не подключено"); connectionStatus.setTextColor(TEXT); connectButton.setText("Подключить");
    }

    private void sendCommand(String cmd) {
        if (cmd.matches("[1-9]")) currentMode=cmd;
        else if (cmd.equalsIgnoreCase("m") || cmd.equalsIgnoreCase("main")) currentMode="";
        sendRaw(cmd);
    }
    private void sendRaw(String cmd) {
        appendTerminal("> "+cmd);
        if (!connected || port==null) { toast("LES24 не подключён"); return; }
        byte[] data=(cmd.trim()+"\n").getBytes(StandardCharsets.UTF_8);
        usbExecutor.submit(() -> { try { port.write(data,1000); } catch(Exception e){ ui.post(() -> toast("Ошибка записи: "+e.getMessage())); }});
    }

    @Override public void onNewData(byte[] data) { ui.post(() -> processChunk(new String(data, StandardCharsets.UTF_8))); }
    @Override public void onRunError(Exception e) { ui.post(() -> { toast("USB отключён: "+e.getMessage()); disconnectSerial(); }); }

    private void processChunk(String chunk) {
        rxBuffer.append(chunk.replace("\r", ""));
        int idx;
        while ((idx=rxBuffer.indexOf("\n"))>=0) {
            String line=rxBuffer.substring(0,idx); rxBuffer.delete(0,idx+1); processLine(line);
        }
    }

    private void processLine(String line) {
        session.add(new LogRow(nowIso(), line)); analyzerDirty=true; appendTerminal(line);
        parseLinks(line); parseIndoor(line);
    }

    private void appendTerminal(String line) {
        if (terminalView != null) {
            terminalView.append((terminalView.length()>0?"\n":"")+line);
            ((View)terminalView.getParent()).post(() -> ((ScrollView)terminalView.getParent()).fullScroll(View.FOCUS_DOWN));
        }
    }
    private String buildTerminalSnapshot(){ StringBuilder b=new StringBuilder(); for(LogRow r:session){ if(b.length()>0)b.append('\n'); b.append(r.text); } return b.toString(); }

    private final Runnable analyzerPump = new Runnable() {
        @Override public void run() {
            if (analyzerDirty && analyzerWeb != null) {
                analyzerDirty=false; String xml=buildXml();
                analyzerWeb.evaluateJavascript("if(window.LES24_setRawText){window.LES24_setRawText("+JSONObject.quote(xml)+",true);}", null);
            }
            ui.postDelayed(this, 900);
        }
    };

    // ---------- Links parser ----------
    private void resetLinks(){ linkAps.clear(); linkStas.clear(); linksPhase="starting"; currentMode="6"; if(linksState!=null){ linksState.setText("ЗАПУСК"); linksInstruction.setText("LES24 сканирует эфир и формирует список доступных AP…"); linksStart.setEnabled(false); linksExit.setEnabled(true); renderLinkAps(); renderLinkStas(); }}
    private void parseLinks(String line){
        String t=line.trim();
        if(t.startsWith("Режим Links:")){ currentMode="6"; linksPhase="starting"; return; }
        if(!"6".equals(currentMode)) return;
        if(t.equals("Список доступных точек доступа:")){ linksPhase="choose_ap"; linkAps.clear(); linkStas.clear(); if(linksState!=null){linksState.setText("ВЫБЕРИТЕ AP");linksInstruction.setText("Нажмите на AP в таблице.");} renderLinkAps(); renderLinkStas(); return; }
        Matcher ap=Pattern.compile("^\\s*(\\d+)\\)\\s+SSID:\\s*(.*?)\\s+BSSID:\\s*([0-9A-Fa-f:]{17})\\s+CH:\\s*(\\d+)\\s+RSSI:\\s*(-?\\d+)").matcher(line);
        if(ap.find() && linksPhase.equals("choose_ap")){ int n=Integer.parseInt(ap.group(1)); linkAps.put(n,new LinkAp(n,ap.group(2).trim(),ap.group(3).toUpperCase(Locale.ROOT),Integer.parseInt(ap.group(4)),Integer.parseInt(ap.group(5)))); renderLinkAps(); return; }
        if(t.startsWith("Сканирование (12") || t.startsWith("Сканирование (12–")){ linksPhase="scanning"; if(linksState!=null){linksState.setText("СКАНИРОВАНИЕ STA");linksInstruction.setText("Адаптивное сканирование выбранной AP. Обычно 12–25 секунд.");} return; }
        Matcher head=Pattern.compile("STA, связанные с AP: SSID='(.*?)', BSSID=([0-9A-Fa-f:]{17})").matcher(t);
        if(head.find()){ linksPhase="results";linkStas.clear(); if(linksTarget!=null)linksTarget.setText("Связанные STA · "+head.group(1)+" · "+head.group(2).toUpperCase(Locale.ROOT)); renderLinkStas(); return; }
        if(linksPhase.equals("results")){
            Matcher st=Pattern.compile("^\\s*(\\d+)\\s+([0-9A-Fa-f:]{17})\\s+(-?\\d+)\\s+(\\d+)\\s*(.*)$").matcher(line);
            if(st.find()){ int n=Integer.parseInt(st.group(1));linkStas.put(n,new LinkSta(n,st.group(2).toUpperCase(Locale.ROOT),Integer.parseInt(st.group(3)),Integer.parseInt(st.group(4)),st.group(5).trim()));renderLinkStas();return; }
        }
        if(t.startsWith("Введите: r")){ linksPhase="ready"; if(linksState!=null){linksState.setText("ГОТОВО");linksInstruction.setText("Можно пересканировать STA, выбрать другую AP или выйти.");linksRescan.setEnabled(true);linksBack.setEnabled(true);linksExit.setEnabled(true);} }
        if(t.contains("LES-24 - Сканер 2.4Ghz") || (t.contains("Выход")&&t.toLowerCase(Locale.ROOT).contains("глав"))){ currentMode="";linksPhase="idle";if(linksStart!=null){linksStart.setEnabled(true);linksRescan.setEnabled(false);linksBack.setEnabled(false);linksExit.setEnabled(false);linksState.setText("ЗАВЕРШЕНО");} }
    }

    private void renderLinkAps(){ if(linksApTable==null)return; linksApTable.removeAllViews(); linksApTable.addView(tableHeader("№","SSID","BSSID","CH","RSSI")); for(LinkAp a:linkAps.values()){ TableRow r=tableRow(String.valueOf(a.num),a.ssid,a.bssid,String.valueOf(a.ch),a.rssi+" dBm"); r.setOnClickListener(v->{ if(!linksPhase.equals("choose_ap"))return; linksPhase="scanning"; linkStas.clear(); linksTarget.setText("Связанные STA · "+a.ssid+" · "+a.bssid); renderLinkStas(); linksState.setText("СКАНИРОВАНИЕ STA"); linksInstruction.setText("LES24 сканирует выбранную AP…"); sendRaw(String.valueOf(a.num)); }); linksApTable.addView(r);} }
    private void renderLinkStas(){ if(linksStaTable==null)return; linksStaTable.removeAllViews(); linksStaTable.addView(tableHeader("№","MAC STA","RSSI","Пакеты","Примечание")); for(LinkSta s:linkStas.values())linksStaTable.addView(tableRow(String.valueOf(s.num),s.mac,s.rssi+" dBm",String.valueOf(s.packets),s.note)); }

    // ---------- Indoor parser ----------
    private void resetIndoor(){ currentMode="9"; indoorDone.clear(); indoorDeviceMap.clear(); indoorPointRssi.clear(); for(String p:new String[]{"A0","A1","A2","A3","C"})indoorPointRssi.put(p,new ArrayList<>()); indoorCapturePoint=null;indoorDeviceList=false;indoorReady=false;asciiCapture=false;asciiLines.clear(); if(indoorState!=null){indoorState.setText("ПОДГОТОВКА");indoorInstruction.setText("Режим запущен. Первой будет точка A0.");indoorProgress.setProgress(0);indoorBoot.setText("BOOT: ожидайте подсказку");indoorExit.setEnabled(false);indoorMap.reset();renderIndoorPoints();renderIndoorDevices();} }
    private void parseIndoor(String line){
        String t=line.trim();
        if(t.contains("=== Локализация в помещении ===")){ currentMode="9"; return; }
        if(!"9".equals(currentMode))return;

        if(asciiCapture){
            if(line.startsWith("|")){asciiLines.add(line);return;}
            if(line.startsWith("+")&&line.endsWith("+")){asciiLines.add(line);if(asciiLines.size()>2){asciiCapture=false;parseAsciiMap();}return;}
            if(!asciiLines.isEmpty())asciiCapture=false;
        }
        if(line.startsWith("+")&&line.endsWith("+")&&line.length()>=20){asciiCapture=true;asciiLines.clear();asciiLines.add(line);return;}

        Matcher go=Pattern.compile("Подойдите к\\s+(A0|A1|A2|A3|C)\\s+и нажмите BOOT",Pattern.CASE_INSENSITIVE).matcher(t);
        if(go.find()){String p=go.group(1).toUpperCase(Locale.ROOT);indoorState.setText("ПЕРЕЙДИТЕ К "+p);indoorInstruction.setText("Перейдите с LES24 к точке "+p+", остановитесь и нажмите физическую BOOT.");indoorBoot.setText("BOOT: нажать в "+p);indoorMap.active=p;indoorMap.invalidate();renderIndoorPoints();return;}
        Matcher collect=Pattern.compile("\\[Сбор\\]\\s*Точка\\s+(A0|A1|A2|A3|C)",Pattern.CASE_INSENSITIVE).matcher(t);
        if(collect.find()){String p=collect.group(1).toUpperCase(Locale.ROOT);indoorState.setText("ИЗМЕРЕНИЕ "+p);indoorInstruction.setText("Не перемещайте LES24 до завершения измерения.");indoorBoot.setText("BOOT: не нажимать");indoorMap.active=p;indoorMap.invalidate();return;}
        Matcher result=Pattern.compile("Итог точки\\s+(A0|A1|A2|A3|C)",Pattern.CASE_INSENSITIVE).matcher(t);
        if(result.find()){indoorCapturePoint=result.group(1).toUpperCase(Locale.ROOT);indoorPointRssi.get(indoorCapturePoint).clear();return;}
        if(indoorCapturePoint!=null){ Matcher row=Pattern.compile("^\\s*(\\d+)\\s+(BLE|AP|STA)\\s+([0-9A-Fa-f:]{17})\\s+(-?\\d+)\\s+(.*)$").matcher(line); if(row.find()){indoorPointRssi.get(indoorCapturePoint).add(Integer.parseInt(row.group(4)));return;} if(t.isEmpty()){indoorDone.add(indoorCapturePoint);indoorCapturePoint=null;indoorProgress.setProgress(indoorDone.size());renderIndoorPoints();} }
        if(t.equals("Список найденных устройств:")){ if(indoorCapturePoint!=null){indoorDone.add(indoorCapturePoint);indoorCapturePoint=null;}indoorProgress.setProgress(indoorDone.size());indoorDeviceList=true;indoorDeviceMap.clear();indoorState.setText("РЕЗУЛЬТАТЫ");indoorInstruction.setText("Пять точек измерены. Выберите устройство ниже.");renderIndoorPoints();renderIndoorDevices();return;}
        if(indoorDeviceList){ Matcher d=Pattern.compile("^\\s*(\\d+)\\s*(BLE|AP|STA)\\s*([0-9A-Fa-f:]{17})\\s+(--|\\d+)\\s+(A0|A1|A2|A3|C|--)\\s+(Да|Нет)\\s*$",Pattern.CASE_INSENSITIVE).matcher(t); if(d.find()){int n=Integer.parseInt(d.group(1));indoorDeviceMap.put(n,new IndoorDevice(n,d.group(2).toUpperCase(Locale.ROOT),d.group(3).toUpperCase(Locale.ROOT),d.group(4),d.group(5),d.group(6)));renderIndoorDevices();return;} }
        if(t.startsWith("Введите номер устройства для отображения схемы")){indoorDeviceList=false;indoorReady=true;indoorExit.setEnabled(true);indoorState.setText("ВЫБОР УСТРОЙСТВА");indoorInstruction.setText("Нажмите на устройство в таблице, чтобы показать его на плане.");return;}
        Matcher chosen=Pattern.compile("Выбрано:\\s*(BLE|AP|STA)\\s+([0-9A-Fa-f:]{17})",Pattern.CASE_INSENSITIVE).matcher(t); if(chosen.find()){indoorSelectedLabel=chosen.group(1).toUpperCase(Locale.ROOT)+" · "+chosen.group(2).toUpperCase(Locale.ROOT);indoorState.setText("ПОСТРОЕНИЕ");return;}
        if(t.startsWith("Выход из режима локализации.")){currentMode="";indoorReady=false;indoorExit.setEnabled(false);indoorState.setText("ЗАВЕРШЕНО");indoorInstruction.setText("RSSI-локализация завершена.");}
    }
    private void renderIndoorPoints(){ if(indoorPoints==null)return;indoorPoints.removeAllViews();for(String p:new String[]{"A0","A1","A2","A3","C"}){ArrayList<Integer> rs=indoorPointRssi.get(p);String state=indoorDone.contains(p)?"Готово":(p.equals(indoorMap.active)?"Текущая":"Ожидание");String best="—";if(rs!=null&&!rs.isEmpty())best=Collections.max(rs)+" dBm";TextView v=body(p+"     "+state+"     устройств: "+(rs==null?0:rs.size())+"     лучший RSSI: "+best);v.setBackgroundColor(CARD);v.setPadding(dp(10),dp(8),dp(10),dp(8));indoorPoints.addView(v,new LinearLayout.LayoutParams(-1,-2));} }
    private void renderIndoorDevices(){if(indoorDevices==null)return;indoorDevices.removeAllViews();indoorDevices.addView(tableHeader("№","Тип","MAC","Сектор","Ближе","Вне"));for(IndoorDevice d:indoorDeviceMap.values()){TableRow r=tableRow(String.valueOf(d.num),d.type,d.mac,d.sector,d.near,d.outside);r.setOnClickListener(v->{if(!indoorReady)return;indoorSelectedLabel=d.type+" · "+d.mac;indoorReady=false;indoorState.setText("ПОСТРОЕНИЕ");indoorInstruction.setText("Получаю штатную схему от LES24…");indoorMap.clearMarker();sendRaw(String.valueOf(d.num));});indoorDevices.addView(r);} }
    private void parseAsciiMap(){for(int y=0;y<asciiLines.size();y++){String line=asciiLines.get(y);int x=line.indexOf('*');boolean outside=false;if(x<0){x=line.indexOf('X');outside=x>=0;}if(x>=0){float nx=Math.max(0f,Math.min(1f,(x-1)/32f));float ny=Math.max(0f,Math.min(1f,(17-y)/17f));indoorMap.setMarker(nx,ny,outside,indoorSelectedLabel);indoorState.setText("ГОТОВО");indoorInstruction.setText(indoorSelectedLabel+": положение перенесено со штатной ASCII-схемы LES24 на графический план.");return;}}}

    // ---------- XML / EULA ----------
    private void saveXml(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/xml");i.putExtra(Intent.EXTRA_TITLE,"LES24_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".xml");startActivityForResult(i,REQ_SAVE_XML);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_SAVE_XML&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){try(OutputStream os=getContentResolver().openOutputStream(data.getData())){os.write(buildXml().getBytes(StandardCharsets.UTF_8));toast("XML сохранён");}catch(Exception e){toast("Ошибка сохранения: "+e.getMessage());}}}
    private String buildXml(){StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ArrayOfXmlTransferTextLine xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");for(LogRow r:session)b.append("  <XmlTransferTextLine TimeStamp=\"").append(xml(r.time)).append("\" Direction=\"Rx\" Text=\"").append(xml(r.text)).append("\" Length=\"").append(r.text.length()).append("\" />\n");b.append("</ArrayOfXmlTransferTextLine>");return b.toString();}
    private String xml(String s){return s.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;");}
    private String nowIso(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US);return f.format(new Date());}
    private void showEulaIfNeeded(){SharedPreferences p=getSharedPreferences("les24",MODE_PRIVATE);if(!EULA_VERSION.equals(p.getString("eula","")))showEulaDialog(true);}
    private void showEulaDialog(boolean required){LinearLayout box=vertical();box.setPadding(dp(12),dp(8),dp(12),dp(8));ScrollView sc=new ScrollView(this);TextView txt=body(EULA_TEXT);sc.addView(txt);box.addView(sc,new LinearLayout.LayoutParams(-1,0,1));CheckBox cb=new CheckBox(this);cb.setText("Я прочитал(а) и принимаю условия EULA");cb.setTextColor(TEXT);box.addView(cb);AlertDialog dlg=new AlertDialog.Builder(this).setTitle("LES24 Control · EULA").setView(box).setNegativeButton(required?"Не принимаю":"Закрыть",(d,w)->{if(required)finish();}).setPositiveButton(required?"Принимаю":"OK",null).setCancelable(!required).create();dlg.setOnShowListener(x->{Button yes=dlg.getButton(AlertDialog.BUTTON_POSITIVE);yes.setEnabled(!required);cb.setOnCheckedChangeListener((b,c)->yes.setEnabled(c));yes.setOnClickListener(v->{if(required){getSharedPreferences("les24",MODE_PRIVATE).edit().putString("eula",EULA_VERSION).apply();}dlg.dismiss();});});dlg.show();}

    private static final String EULA_TEXT = "LES24 CONTROL — ЛИЦЕНЗИОННОЕ СОГЛАШЕНИЕ (EULA)\n\n"+
        "Программа предоставляется для законного использования совместно с прибором LES24. Пользователь получает простую неисключительную лицензию на использование программы по её функциональному назначению. Исключительные права на программу не передаются.\n\n"+
        "Запрещается использовать программу и LES24 для неправомерного доступа к компьютерной информации, незаконного перехвата сообщений, нарушения тайны связи и иных действий, запрещённых законодательством. Пользователь самостоятельно обеспечивает законность обследования объекта и необходимые полномочия.\n\n"+
        "Программа может сохранять SSID, BSSID, MAC, RSSI и временные метки. Если совокупность таких сведений относится к определённому или определяемому лицу, пользователь самостоятельно обеспечивает соблюдение требований применимого законодательства о персональных данных.\n\n"+
        "RSSI, оценка близости, автоматическая связь STA→AP и RSSI-локализация являются оценочными результатами и зависят от условий распространения радиоволн. Они не являются самостоятельным достаточным доказательством назначения, принадлежности или точного местоположения устройства без проверки специалистом.\n\n"+
        "В пределах, допускаемых законодательством РФ, правообладатель не отвечает за косвенные убытки и упущенную выгоду профессионального пользователя. Не ограничиваются права потребителей и иные императивные права, которые не могут быть ограничены соглашением. Права законного пользователя программы, предусмотренные ст. 1280 ГК РФ, сохраняются. К соглашению применяется законодательство Российской Федерации.";

    // ---------- UI helpers ----------
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout horizontalWrap(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView label(String s,boolean bold,int sp){TextView v=new TextView(this);v.setText(s);v.setTextColor(TEXT);v.setTextSize(sp);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setGravity(Gravity.CENTER_VERTICAL);v.setPadding(dp(8),0,dp(8),0);return v;}
    private TextView title(String s){return label(s,true,22);} private TextView section(String s){TextView v=label(s,true,16);v.setPadding(dp(4),dp(14),dp(4),dp(6));return v;} private TextView body(String s){TextView v=label(s,false,14);v.setPadding(dp(8),dp(6),dp(8),dp(6));return v;} private TextView muted(String s){TextView v=body(s);v.setTextColor(MUTED);return v;}
    private TextView chip(String s){TextView v=label(s,true,12);v.setTextColor(Color.rgb(157,215,255));v.setBackgroundColor(Color.rgb(16,38,58));v.setPadding(dp(10),dp(6),dp(10),dp(6));return v;}
    private View titleRow(String title,TextView chip){LinearLayout r=horizontalWrap();r.addView(label(title,true,21),new LinearLayout.LayoutParams(0,dp(48),1));r.addView(chip,lpWrap());return r;}
    private TextView cardText(String s){TextView v=body(s);v.setBackgroundColor(CARD);v.setPadding(dp(12),dp(12),dp(12),dp(12));return v;}
    private Button action(String s,boolean primary){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setAllCaps(false);b.setTextSize(13);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?BRAND:CARD2));b.setPadding(dp(10),0,dp(10),0);return b;}
    private Button navButton(String s){Button b=action(s,false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(44));p.setMargins(dp(3),dp(4),dp(3),dp(5));b.setLayoutParams(p);return b;}
    private void styleTable(TableLayout t){t.setStretchAllColumns(true);t.setShrinkAllColumns(true);t.setBackgroundColor(CARD);}
    private TableRow tableHeader(String...cells){TableRow r=new TableRow(this);r.setBackgroundColor(CARD2);for(String c:cells){TextView v=label(c,true,11);v.setTextColor(MUTED);v.setPadding(dp(6),dp(8),dp(6),dp(8));r.addView(v);}return r;}
    private TableRow tableRow(String...cells){TableRow r=new TableRow(this);r.setBackgroundColor(CARD);for(String c:cells){TextView v=label(c,false,11);v.setPadding(dp(5),dp(8),dp(5),dp(8));r.addView(v);}return r;}
    private LinearLayout.LayoutParams lpWrap(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(44));p.setMargins(dp(3),dp(3),dp(3),dp(3));return p;} private LinearLayout.LayoutParams lpMatchWrap(){return new LinearLayout.LayoutParams(-1,-2);} private LinearLayout.LayoutParams lpMatch(){return new LinearLayout.LayoutParams(-1,-1);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);} private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    @Override protected void onDestroy(){ui.removeCallbacks(analyzerPump);disconnectSerial();try{unregisterReceiver(usbReceiver);}catch(Exception ignored){}usbExecutor.shutdownNow();super.onDestroy();}

    static class LogRow { final String time,text; LogRow(String t,String x){time=t;text=x;} }
    static class LinkAp {final int num,ch,rssi;final String ssid,bssid;LinkAp(int n,String s,String b,int c,int r){num=n;ssid=s;bssid=b;ch=c;rssi=r;}}
    static class LinkSta {final int num,rssi,packets;final String mac,note;LinkSta(int n,String m,int r,int p,String x){num=n;mac=m;rssi=r;packets=p;note=x;}}
    static class IndoorDevice {final int num;final String type,mac,sector,near,outside;IndoorDevice(int n,String t,String m,String s,String ne,String o){num=n;type=t;mac=m;sector=s;near=ne;outside=o;}}

    class IndoorMapView extends View {
        String active=""; final Set<String> done=new HashSet<>(); Float mx=null,my=null; boolean outside=false; String markerLabel="";
        IndoorMapView(Context c){super(c);setBackgroundColor(BG);}
        void reset(){active="";done.clear();clearMarker();invalidate();}
        void clearMarker(){mx=null;my=null;markerLabel="";invalidate();}
        void setMarker(float x,float y,boolean out,String label){mx=x;my=y;outside=out;markerLabel=label;invalidate();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);float margin=52;float side=Math.min(getWidth()-2*margin,getHeight()-80);float l=(getWidth()-side)/2,t=36,r=l+side,b=t+side;p.setStyle(Paint.Style.FILL);p.setColor(CARD);c.drawRoundRect(l,t,r,b,14,14,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(LINE);c.drawRoundRect(l,t,r,b,14,14,p);p.setStrokeWidth(1);c.drawLine((l+r)/2,(t+b)/2,l,t,p);c.drawLine((l+r)/2,(t+b)/2,r,t,p);c.drawLine((l+r)/2,(t+b)/2,l,b,p);c.drawLine((l+r)/2,(t+b)/2,r,b,p);drawAnchor(c,p,"A0",l,b);drawAnchor(c,p,"A1",r,b);drawAnchor(c,p,"A2",r,t);drawAnchor(c,p,"A3",l,t);drawAnchor(c,p,"C",(l+r)/2,(t+b)/2);if(mx!=null){float x=l+mx*side,y=b-my*side;p.setStyle(Paint.Style.FILL);p.setColor(outside?RED:Color.rgb(69,200,255));c.drawCircle(x,y,12,p);p.setColor(TEXT);p.setTextSize(24);p.setTextAlign(Paint.Align.CENTER);c.drawText(markerLabel,x,y-22,p);}}
        private void drawAnchor(Canvas c,Paint p,String name,float x,float y){boolean a=name.equals(active);p.setStyle(Paint.Style.FILL);p.setColor(a?BRAND:(indoorDone.contains(name)?Color.rgb(18,59,43):CARD2));c.drawCircle(x,y,a?19:15,p);p.setColor(TEXT);p.setTextSize(22);p.setTextAlign(Paint.Align.CENTER);c.drawText(name,x,y+7,p);}
    }
}
