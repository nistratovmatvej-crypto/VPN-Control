package com.vpncontrol.app

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.net.InetAddress
import java.text.DecimalFormat

// VPN status is read from the live AmneziaWG backend, not a stale UI toggle.
class MainActivity : Activity() {
    private val bg = 0xFF111015.toInt(); private val panel = 0xFF1D1B24.toInt(); private val panel2 = 0xFF292631.toInt()
    private val white = 0xFFF6F2FA.toInt(); private val muted = 0xFFAAA3B5.toInt(); private val amber = 0xFF9B6DFF.toInt()
    private val blue = 0xFFB18AFF.toInt(); private val green = 0xFF45D18B.toInt()
    private val prefs by lazy { getSharedPreferences("vpncontrol", MODE_PRIVATE) }
    private lateinit var root: LinearLayout; private lateinit var body: LinearLayout; private lateinit var nav: LinearLayout
    private var page = "home"; private var lastRenderedTunnelState = false; private var lastRenderedPendingState = false; private var startAutomationAfterPermission = false; private var autoSelectAfterPermission = false; private val handler = Handler(Looper.getMainLooper()); private var rx0 = 0L; private var tx0 = 0L; private var sampleAt = 0L; private val rxHistory = mutableListOf<Float>()
    private val refresh = object : Runnable { override fun run() { WireGuardControllerService.tunnelCounters()?.let { (rx, tx) -> TrafficStatsTracker.sampleTunnel(this@MainActivity, rx, tx) }; val actual = WireGuardControllerService.isTunnelUp(this@MainActivity); val pending = prefs.getBoolean("vpnConnecting", false); if (page == "monitor") { body.removeAllViews(); renderMonitor() } else if (page == "home" && (actual != lastRenderedTunnelState || pending != lastRenderedPendingState)) render(); handler.postDelayed(this, 900) } }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); window.statusBarColor = bg; window.navigationBarColor = bg; migrateLegacyConfig(); render(); handler.post(refresh) }
    override fun onResume() { super.onResume(); if (::root.isInitialized) render() }
    override fun onDestroy() { handler.removeCallbacks(refresh); super.onDestroy() }

    private fun migrateLegacyConfig() {
        val legacy = File(filesDir, "active.conf")
        val encrypted = File(filesDir, "active.enc")
        if (legacy.exists() && !encrypted.exists()) {
            try { encrypted.writeBytes(ConfigStore.encrypt(legacy.readBytes())); legacy.delete() }
            catch (_: Exception) { Toast.makeText(this, "Не удалось зашифровать старый профиль. Импортируй .conf заново.", Toast.LENGTH_LONG).show() }
        }
        val active = File(filesDir, "active.enc")
        if (active.exists() && ProfileStore.all(this).isEmpty()) {
            try { ProfileStore.add(this, prefs.getString("profileName", "Импортированный сервер") ?: "Импортированный сервер", ConfigStore.decrypt(active.readBytes())) }
            catch (_: Exception) { Toast.makeText(this, "Старый профиль не удалось перенести. Сохрани его .conf отдельно.", Toast.LENGTH_LONG).show() }
        }
    }
    private fun showToast(message: String) { val label = TextView(this).apply { text = message; textSize = 13f; setTextColor(white); setPadding(dp(18), dp(12), dp(18), dp(12)); background = shape(0xFF302A39.toInt(), 18, 0xFF59466B.toInt()) }; Toast(this).apply { duration = Toast.LENGTH_SHORT; view = label; setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(74)) }.show() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float, color: Int = white, bold: Boolean = false): TextView = TextView(this).apply { text=s; textSize=size; setTextColor(color); if(bold)setTypeface(null,Typeface.BOLD); setPadding(0,dp(3),0,dp(3)) }
    private fun shape(color: Int, radius: Int = 20, stroke: Int = 0): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat(); if(stroke!=0)setStroke(dp(1),stroke) }
    private fun card(): LinearLayout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(17),dp(15),dp(17),dp(15)); background=shape(panel,22,0xFF393342.toInt()) }
    private fun button(s: String, primary: Boolean = false, fn: () -> Unit): TextView = TextView(this).apply { text=s; textSize=14f; gravity=Gravity.CENTER; setTextColor(if(primary) 0xFF17131F.toInt() else white); typeface=Typeface.DEFAULT_BOLD; setPadding(dp(15),dp(13),dp(15),dp(13)); background=shape(if(primary) amber else panel2,15); setOnClickListener{fn()} }
    private fun gap(h:Int=12): View = View(this).apply { layoutParams=LinearLayout.LayoutParams(1,dp(h)) }
    private fun addCard(v: View) { body.addView(v, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) }) }
    private fun section(s:String) { body.addView(text(s.uppercase(),11f,amber,true).apply { setPadding(0,dp(14),0,dp(9)) }) }
    private fun line(a: String, b: String, color: Int = muted): View {
        return LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)); addView(text(a, 14f, white, true), LinearLayout.LayoutParams(0, -2, 1f)); addView(text(b, 13f, color, true)) }
    }
    private fun render() {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(20),dp(12),dp(20),dp(6)) }
        val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(5),0,dp(15)) }
        val mark=TextView(this).apply { text="ϟ"; textSize=27f; gravity=Gravity.CENTER; setTextColor(0xFFB18AFF.toInt()); background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(0xFF292033.toInt()); setStroke(dp(2),0xFF8D5DFF.toInt()) } }
        header.addView(mark,LinearLayout.LayoutParams(dp(48),dp(48)).apply { rightMargin=dp(12) })
        val brand=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        brand.addView(text("VPNcontrol",25f,white,true))
        brand.addView(text("AmneziaWG · Защищённое соединение",11f,muted))
        header.addView(brand,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(topAction("▥") { page="monitor"; render() })
        header.addView(topAction("⚙") { page="settings"; render() },LinearLayout.LayoutParams(dp(48),dp(48)).apply { leftMargin=dp(10) })
        root.addView(header)
        val scroll=ScrollView(this).apply { isFillViewport=true }; body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,0,0,dp(8)) }; scroll.addView(body); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        when(page){"home"->renderHome();"monitor"->renderMonitor();"automation"->renderAutomation();else->renderSettings()}
        nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setPadding(0,dp(8),0,dp(4)); background=shape(0xFF19171F.toInt(),18) }
        listOf("home" to "⌂\nГлавная","automation" to "☷\nПравила","settings" to "⚙\nНастройки").forEach { (id,title)-> val t=text(title,12f,if(page==id)amber else muted,true).apply{gravity=Gravity.CENTER;setPadding(0,dp(6),0,dp(6));setOnClickListener{page=id;render()}}; nav.addView(t,LinearLayout.LayoutParams(0,-2,1f)) }
        root.addView(nav,LinearLayout.LayoutParams(-1,-2)); setContentView(root)
    }
    private fun topAction(label:String, action:()->Unit):TextView = TextView(this).apply { text=label; textSize=22f; gravity=Gravity.CENTER; setTextColor(white); background=shape(panel2,50,0xFF393342.toInt()); setOnClickListener{action()} }
    private fun renderHome() {
        val connected=WireGuardControllerService.isTunnelUp(this@MainActivity)
        val pending=prefs.getBoolean("vpnConnecting", false)
        val profiles=ProfileStore.all(this).sortedByDescending { it.favorite }
        lastRenderedTunnelState=connected
        lastRenderedPendingState=pending
        val hero=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(18),dp(20),dp(18),dp(20)); background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xFF302342.toInt(),0xFF211B2E.toInt(),panel)).apply { cornerRadius=dp(26).toFloat(); setStroke(dp(1),0xFF594078.toInt()) } }
        val power=TextView(this).apply { text=if(connected)"✓" else if(pending)"…" else "⏻"; textSize=43f; gravity=Gravity.CENTER; setTextColor(white); background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(0xFF211A30.toInt()); setStroke(dp(3),if(connected)green else blue) }; setOnClickListener { if(connected)stopVpn() else if(!pending)requestVpnStart(); handler.postDelayed({render()},350) } }
        hero.addView(power,LinearLayout.LayoutParams(dp(118),dp(118)).apply { rightMargin=dp(18) })
        val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        info.addView(text(if(connected)"●  VPN ВКЛЮЧЁН" else if(pending)"●  ПОДКЛЮЧЕНИЕ…" else "●  VPN ВЫКЛЮЧЕН",19f,if(connected)green else if(pending)amber else muted,true))
        info.addView(text(prefs.getString("profileName","VPN-профиль") ?: "VPN-профиль",14f,white,true).apply { setPadding(0,dp(9),0,dp(5)) })
        info.addView(TextView(this).apply { text="Сменить профиль  ⌄"; textSize=13f; setTextColor(0xFFC5B6E8.toInt()); setOnClickListener{showProfileChooser()} })
        info.addView(text(if(connected)"Защищённый туннель активен" else if(pending)"Ожидание запуска туннеля" else "Трафик идёт напрямую · нажми питание",11f,muted))
        hero.addView(info,LinearLayout.LayoutParams(0,-2,1f)); addCard(hero)
        val counters=WireGuardControllerService.tunnelCounters()
        val rx=counters?.first ?: TrafficStatsTracker.value(this,"total_rx")
        val tx=counters?.second ?: TrafficStatsTracker.value(this,"total_tx")
        val stats=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; background=shape(panel,20,0xFF37323F.toInt()); setPadding(dp(12),dp(13),dp(12),dp(13)) }
        listOf(Triple("◉",if(connected)"Включён" else if(pending)"Запуск…" else "Выключен","Статус"),Triple("↓",bytes(rx),"Получено"),Triple("↑",bytes(tx),"Отправлено")).forEachIndexed { index,item ->
            val cell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
            cell.addView(text(item.first,19f,if(index==0)green else amber,true)); cell.addView(text(item.second,14f,white,true)); cell.addView(text(item.third,11f,muted))
            stats.addView(cell,LinearLayout.LayoutParams(0,-2,1f))
            if(index<2)stats.addView(View(this).apply{setBackgroundColor(0xFF393342.toInt())},LinearLayout.LayoutParams(dp(1),dp(42)))
        }
        body.addView(stats,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(20) })
        section("МАРШРУТИЗАЦИЯ")
        val routing=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        listOf(Triple("◎","Напрямую","Без VPN"),Triple("ϟ","Через VPN","Весь трафик через VPN")).forEachIndexed { index,item ->
            val selected=if(index==0)!connected else connected
            val tile=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(13),dp(15),dp(10),dp(15)); background=shape(if(selected)0xFF29213A.toInt() else panel,20,if(selected)amber else 0xFF393342.toInt()); setOnClickListener { if(index==0){ if(WireGuardControllerService.isTunnelUp(this@MainActivity))stopVpn() } else { if(!WireGuardControllerService.isTunnelUp(this@MainActivity))requestVpnStart() } } }
            tile.addView(text(item.first+"   "+item.second,15f,white,true)); tile.addView(text(item.third,11f,muted))
            routing.addView(tile,LinearLayout.LayoutParams(0,-2,1f).apply { if(index==0)rightMargin=dp(9) })
        }
        body.addView(routing,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(20) })
        section("РЕКОМЕНДУЕМЫЕ ПРОФИЛИ")
        val search=EditText(this).apply { hint="⌕  Поиск профилей"; textSize=14f; setSingleLine(true); setTextColor(white); setHintTextColor(muted); setPadding(dp(16),dp(12),dp(16),dp(12)); background=shape(panel,30,0xFF393342.toInt()) }
        body.addView(search,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        val rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; body.addView(rows)
        fun populate(query:String) {
            rows.removeAllViews()
            profiles.filter { it.name.contains(query,true) }.forEach { profile ->
                val active=ProfileStore.activeId(this)==profile.id
                val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(13),dp(15),dp(13)); background=shape(if(active)0xFF29213A.toInt() else panel,20,if(active)0xFF8D5DFF.toInt() else 0xFF302D37.toInt()); setOnClickListener { if(!active)activateProfile(profile.id) } }
                val name=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; name.addView(text(profile.name,15f,white,true)); name.addView(text(if(active)"Активный профиль" else "Нажми, чтобы выбрать",11f,muted))
                row.addView(name,LinearLayout.LayoutParams(0,-2,1f)); row.addView(text(if(active)"◉" else "○",23f,if(active)amber else muted,true))
                rows.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(7) })
            }
            if(profiles.isEmpty())rows.addView(text("Профили не найдены. Импортируй .conf.",13f,muted))
        }
        populate("")
        search.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){populate(s?.toString().orEmpty())};override fun afterTextChanged(s:android.text.Editable?){}})
        section("НАСТРОЙКИ DNS")
        val dns=card(); dns.addView(line("DNS-серверы",dnsServers(),green)); dns.addView(line("Сеть",networkType())); dns.addView(text("Настройки DNS и приложения с отдельными правилами доступны в разделе «Правила».",11f,muted)); addCard(dns)
        val actions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        actions.addView(button("＋  Импортировать",true){openConfigPicker()},LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(button("Диагностика",false){runDiagnostics()},LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(8) }); addCard(actions)
    }
    private fun showProfileChooser() {
        val profiles=ProfileStore.all(this)
        if(profiles.isEmpty()){openConfigPicker();return}
        AlertDialog.Builder(this).setTitle("Выбрать профиль").setItems(profiles.map{it.name}.toTypedArray()){_,which->
            if(ProfileStore.activeId(this)!=profiles[which].id) activateProfile(profiles[which].id)
        }.show()
    }
    private fun activateProfile(id:String) {
        try {
            val wasRunning=WireGuardControllerService.isTunnelUp(this)
            ProfileStore.activate(this,id)
            if(wasRunning) {
                prefs.edit().putBoolean("vpnConnecting",true).apply()
                val intent=Intent(this,WireGuardControllerService::class.java).setAction(WireGuardControllerService.ACTION_RECONNECT)
                if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(intent) else startService(intent)
                showToast("Переключаю профиль и перезапускаю туннель…")
            } else showToast("Профиль выбран")
            render()
        } catch(e:Exception) { showToast("Не удалось переключить профиль") }
    }
    private fun renderMonitor() {
        section("МОНИТОРИНГ ТУННЕЛЯ AMNEZIAWG")
        val c=card(); val counters=WireGuardControllerService.tunnelCounters(); val rx=counters?.first ?: TrafficStatsTracker.value(this,"total_rx"); val tx=counters?.second ?: TrafficStatsTracker.value(this,"total_tx"); val now=System.currentTimeMillis(); var down="—"; var up="—"; var rate=0f
        if(counters!=null && sampleAt>0 && now>sampleAt && rx>=rx0 && tx>=tx0){val sec=(now-sampleAt)/1000.0;rate=((rx-rx0)/sec).toFloat();down=speed(rate.toDouble());up=speed((tx-tx0)/sec)}; if(counters!=null){rx0=rx;tx0=tx;sampleAt=now};rxHistory.add(rate);if(rxHistory.size>36)rxHistory.removeAt(0)
        c.addView(line("↓ Загрузка VPN",if(counters==null)"Не подключён" else down,green));c.addView(line("↑ Отдача VPN",if(counters==null)"Не подключён" else up,amber));c.addView(line("Получено через VPN",bytes(TrafficStatsTracker.value(this,"total_rx"))));c.addView(line("Отправлено через VPN",bytes(TrafficStatsTracker.value(this,"total_tx"))));c.addView(line("Время VPN",connectionDuration()));c.addView(line("Тип сети",networkType()));c.addView(line("IP интерфейса",currentIp()));c.addView(line("DNS",dnsServers()));c.addView(text("Сетевая активность · реальные счётчики peer AmneziaWG",11f,muted,true).apply{setPadding(0,dp(12),0,dp(5))});c.addView(TrafficGraphView().apply{layoutParams=LinearLayout.LayoutParams(-1,dp(100))});addCard(c)
        section("ДИАГНОСТИКА")
        val d=card(); d.addView(text("Проверка доступности 1.1.1.1 (ICMP)",13f,muted)); d.addView(button("ПРОВЕРИТЬ PING",true){runDiagnostics()}); d.addView(text(prefs.getString("lastDiagnostic","Нажми, чтобы проверить доступность сети")?:"",12f,white).apply{setPadding(0,dp(10),0,0)}); addCard(d)
        section("ТРАФИК")
        val t=card();t.addView(line("Сегодня · получено",bytes(TrafficStatsTracker.value(this,"day_rx")),green));t.addView(line("Сегодня · отправлено",bytes(TrafficStatsTracker.value(this,"day_tx")),amber));t.addView(line("Месяц · получено",bytes(TrafficStatsTracker.value(this,"month_rx")),green));t.addView(line("Месяц · отправлено",bytes(TrafficStatsTracker.value(this,"month_tx")),amber));t.addView(text("Счётчики берутся из native AmneziaWG peer statistics и учитывают именно трафик через VPN-туннель. Фоновый сбор работает отдельной службой, пока туннель подключён.",11f,muted));addCard(t)
    }
    private fun installedLaunchableApps(): List<Pair<String, String>> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                pkg to info.loadLabel(packageManager).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun renderAutomation() {
        section("МАРШРУТИЗАЦИЯ ПРИЛОЖЕНИЙ")
        val intro = card()
        intro.addView(text("Настрой правила для приложений. Изменения применяются при следующем подключении или переподключении VPN.", 12f, muted))
        val mode = prefs.getString("routingMode", "all") ?: "all"
        listOf("all" to "Все приложения через VPN", "include" to "Только выбранные через VPN", "exclude" to "Выбранные напрямую, остальные через VPN").forEach { (value, label) ->
            intro.addView(RadioButton(this).apply { text = label; textSize = 14f; setTextColor(white); buttonTintList = android.content.res.ColorStateList.valueOf(amber); isChecked = mode == value; setOnClickListener { prefs.edit().putString("routingMode", value).apply(); render() } })
        }
        intro.addView(CheckBox(this).apply {
            text = "Автоматически переключать VPN по активному приложению"
            textSize = 13f; setTextColor(white); buttonTintList = android.content.res.ColorStateList.valueOf(green)
            isChecked = prefs.getBoolean("autoEnabled", false)
            setOnCheckedChangeListener { _, checked ->
                if (checked) startMonitor()
                else {
                    prefs.edit().putBoolean("autoEnabled", false).apply()
                    stopService(Intent(this@MainActivity, AppMonitorService::class.java))
                    Toast.makeText(this@MainActivity, "Автоматизация выключена", Toast.LENGTH_SHORT).show()
                }
            }
        })
        addCard(intro)

        val apps = installedLaunchableApps()
        val selected = prefs.getStringSet("routingPackages", emptySet())?.toMutableSet() ?: mutableSetOf()
        val dnsRequired = prefs.getStringSet("dnsRequiredPackages", emptySet())?.toMutableSet() ?: mutableSetOf()
        section("ПРИЛОЖЕНИЯ")
        val appCard = card()
        appCard.addView(text("${apps.size} установленных приложений · поиск по названию или пакету", 12f, muted))
        val search = EditText(this).apply {
            hint = "⌕  Найти приложение…"
            textSize = 14f
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = shape(panel2, 14, 0xFF51415F.toInt())
        }
        appCard.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(8) })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appCard.addView(rows)
        fun populateRows(query: String) {
            rows.removeAllViews()
            val filtered = apps.filter { (pkg, label) -> label.contains(query, true) || pkg.contains(query, true) }
            rows.addView(text("${filtered.size} найдено · VPN: ${selected.size} · ждать DNS: ${dnsRequired.size}", 11f, muted))
            if (filtered.isEmpty()) rows.addView(text("Ничего не найдено", 13f, muted))
            filtered.forEach { (pkg, label) ->
                val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
                item.addView(text(label, 14f, white, true))
                item.addView(text(pkg, 10f, muted))
                val toggles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                toggles.addView(CheckBox(this@MainActivity).apply {
                    text = "Через VPN"; textSize = 11f; setTextColor(white)
                    buttonTintList = android.content.res.ColorStateList.valueOf(amber)
                    isChecked = pkg in selected
                    setOnCheckedChangeListener { _, checked -> if (checked) selected.add(pkg) else selected.remove(pkg); prefs.edit().putStringSet("routingPackages", selected.toSet()).apply() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                toggles.addView(CheckBox(this@MainActivity).apply {
                    text = "Только при DNS"; textSize = 11f; setTextColor(white)
                    buttonTintList = android.content.res.ColorStateList.valueOf(green)
                    isChecked = pkg in dnsRequired
                    setOnCheckedChangeListener { _, checked -> if (checked) dnsRequired.add(pkg) else dnsRequired.remove(pkg); prefs.edit().putStringSet("dnsRequiredPackages", dnsRequired.toSet()).apply() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                item.addView(toggles)
                rows.addView(item)
                val divider = View(this).apply { setBackgroundColor(0xFF393342.toInt()) }
                rows.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        populateRows("")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { populateRows(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        addCard(appCard)

        section("ЗАЩИТА DNS")
        val dns = card()
        dns.addView(text("Для приложений с отметкой «Только при DNS» автоматика не включит VPN, пока проверка DNS не пройдёт. Проверяется реальное разрешение доменного имени. Для работы включи переключатель автоматизации выше.", 12f, muted))
        dns.addView(line("Приложений с ограничением", dnsRequired.size.toString(), green))
        dns.addView(line("DNS-проверка", if (prefs.getBoolean("dnsGateEnabled", true)) "Включена" else "Выключена", if (prefs.getBoolean("dnsGateEnabled", true)) green else muted))
        dns.addView(CheckBox(this).apply {
            text = "Пока DNS не отвечает — не включать VPN для отмеченных приложений"
            textSize = 13f; setTextColor(white); buttonTintList = android.content.res.ColorStateList.valueOf(green)
            isChecked = prefs.getBoolean("dnsGateEnabled", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("dnsGateEnabled", checked).apply(); render() }
        })
        val dnsMode = prefs.getString("dnsMode", "profile") ?: "profile"
        listOf("profile" to "DNS из конфигурации сервера", "cloudflare" to "Cloudflare · 1.1.1.1", "quad9" to "Quad9 · 9.9.9.9", "google" to "Google · 8.8.8.8").forEach { (value, label) ->
            dns.addView(RadioButton(this).apply { text = label; textSize = 14f; setTextColor(white); buttonTintList = android.content.res.ColorStateList.valueOf(amber); isChecked = dnsMode == value; setOnClickListener { prefs.edit().putString("dnsMode", value).apply(); render() } })
        }
        addCard(dns)

        section("ПРИМЕНЕНИЕ")
        val apply = card()
        apply.addView(text("Изменения маршрутизации и DNS вступят в силу после переподключения VPN.", 12f, muted))
        apply.addView(gap(8))
        apply.addView(button("ПЕРЕПОДКЛЮЧИТЬ VPN", true) {
            if (!File(filesDir, "active.enc").exists()) Toast.makeText(this, "Сначала добавь профиль VPNcontrol или импортируй конфигурацию", Toast.LENGTH_LONG).show()
            else if (prefs.getString("routingMode", "all") == "include" && selected.isEmpty()) Toast.makeText(this, "Выбери хотя бы одно приложение", Toast.LENGTH_LONG).show()
            else applyRoutingChanges()
        })
        addCard(apply)
    }

    private fun renderSettings() {
        section("СОХРАНЁННЫЕ СЕРВЕРЫ")
        val profiles = ProfileStore.all(this).sortedByDescending { it.favorite }
        val profileSearchCard = card()
        profileSearchCard.addView(text("ПОИСК ПО СЕРВЕРАМ", 10f, amber, true))
        val profileSearch = EditText(this).apply {
            hint = "⌕  Страна или название сервера…"
            textSize = 14f; setSingleLine(true); setTextColor(white); setHintTextColor(muted)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = shape(panel2, 14, 0xFF51415F.toInt())
        }
        profileSearchCard.addView(profileSearch)
        val profileRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        profileSearchCard.addView(profileRows)
        addCard(profileSearchCard)
        fun renderProfiles(query: String) {
            profileRows.removeAllViews()
            val filtered = profiles.filter { it.name.contains(query, true) || it.id.contains(query, true) }
            profileRows.addView(text("${filtered.size} серверов найдено", 11f, muted))
            if (filtered.isEmpty()) profileRows.addView(text(if (profiles.isEmpty()) "Пока нет серверов. Добавь профиль VPNcontrol или импортируй конфигурацию." else "Ничего не найдено", 13f, muted))
            filtered.forEach { profile ->
            val row=card(); val active=ProfileStore.activeId(this)==profile.id
            row.addView(text((if(active) "●  " else "○  ")+profile.name,16f,if(active)amber else white,true))
            row.addView(text((if(profile.favorite) "★ Избранное" else "☆ Не в избранном")+if(active) "  ·  Активный" else "",11f,muted))
            row.addView(gap(6)); row.addView(button(if(active) "АКТИВНЫЙ ПРОФИЛЬ" else "СДЕЛАТЬ АКТИВНЫМ",!active){if(prefs.getBoolean("vpnWasStarted",false)){Toast.makeText(this,"Сначала отключи VPN, затем переключи сервер",Toast.LENGTH_LONG).show()}else try{ProfileStore.activate(this,profile.id);render()}catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()}})
            row.addView(gap(6)); val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;addView(button(if(profile.favorite) "★" else "☆"){ProfileStore.toggleFavorite(this@MainActivity,profile.id);render()},LinearLayout.LayoutParams(0,-2,1f));addView(button("Удалить"){if(prefs.getBoolean("vpnWasStarted",false)&&active){Toast.makeText(this@MainActivity,"Сначала отключи VPN",Toast.LENGTH_LONG).show()}else{ProfileStore.remove(this@MainActivity,profile.id);render()}},LinearLayout.LayoutParams(0,-2,1f))};row.addView(actions);profileRows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
        }
        renderProfiles("")
        profileSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderProfiles(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val controls=card();controls.addView(button("＋  ИМПОРТИРОВАТЬ .CONF / AWG",true){openConfigPicker()});controls.addView(gap(7));controls.addView(button("✦  АВТОВЫБОР ЛУЧШЕГО СЕРВЕРА",false){selectBestServer()});addCard(controls)
        section("ПРОТОКОЛЫ")
        val protocols = card()
        protocols.addView(line("AmneziaWG", "Поддерживается", green))
        protocols.addView(line("WireGuard", "Поддерживается", green))
        protocols.addView(text("Тип определяется автоматически по импортированной конфигурации. TUIC, AnyTLS, Mieru и ShadowsocksR пока не поддерживаются этим движком — не показываем их как доступные, чтобы не создавать ложное ожидание.", 11f, muted))
        addCard(protocols)
        section("KILL SWITCH")
        val alwaysOn = Settings.Secure.getString(contentResolver,"always_on_vpn_app") == packageName
        val lockdown = try { Settings.Secure.getInt(contentResolver,"always_on_vpn_lockdown",0) == 1 } catch (_:Exception) { false }
        val k=card();k.addView(line("Always-on VPN",if(alwaysOn)"Включён" else "Выключен",if(alwaysOn)green else amber));k.addView(line("Блокировка без VPN",if(lockdown)"Включена" else "Выключена",if(lockdown)green else amber));k.addView(gap(7));k.addView(button("ОТКРЫТЬ НАСТРОЙКИ KILL SWITCH",true){startActivity(Intent(Settings.ACTION_VPN_SETTINGS))});k.addView(text(if(lockdown)"Системный lockdown обнаружен. Android блокирует трафик вне VPN." else "Для включения выбери VPNcontrol в системных настройках, включи Always-on VPN и затем «Блокировать подключения без VPN». Android не разрешает обычному приложению включать lockdown без действия пользователя.",11f,amber));k.addView(text("Важно: Kill Switch конфликтует с раздельным туннелированием. Приложения, не включённые в VPN-список, могут потерять интернет. Для режима «остальные приложения напрямую» системную блокировку лучше не включать.",11f,muted).apply{setPadding(0,dp(9),0,0)});addCard(k)
        section("DNS")
        val d=card();d.addView(line("Режим Private DNS",Settings.Global.getString(contentResolver,"private_dns_mode")?:"неизвестно"));d.addView(line("Имя DNS",Settings.Global.getString(contentResolver,"private_dns_specifier")?:"Автоматически"));d.addView(button("ОТКРЫТЬ НАСТРОЙКИ PRIVATE DNS",false){openPrivateDnsSettings()});addCard(d)
        section("РЕЗЕРВНЫЕ КОПИИ")
        val b=card();b.addView(text("Переносимый архив всех профилей, защищённый паролем. Пароль не сохраняется в приложении.",12f,muted));b.addView(gap(7));b.addView(button("СОЗДАТЬ ЗАШИФРОВАННУЮ КОПИЮ",true){askBackupPassword(false)});b.addView(gap(7));b.addView(button("ВОССТАНОВИТЬ ИЗ КОПИИ",false){askBackupPassword(true)});addCard(b)
        section("РАЗРЕШЕНИЯ")
        val a=card();a.addView(line("Статистика приложений",if(hasUsageAccess())"Разрешена" else "Не разрешена",if(hasUsageAccess())green else amber));a.addView(button("УПРАВЛЯТЬ РАЗРЕШЕНИЕМ",false){startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))});addCard(a)
        section("О ПРИЛОЖЕНИИ")
        val about=card();about.addView(text("VPNcontrol 0.6.0 · AmneziaWG",16f,white,true));about.addView(text("Ключи хранятся в Android Keystore. Экспортируемые резервные копии дополнительно защищаются отдельным паролем.",12f,muted));addCard(about)
    }

    private fun selectBestServer() {
        val profiles=ProfileStore.all(this)
        if(profiles.isEmpty()){Toast.makeText(this,"Сначала добавь серверы",Toast.LENGTH_LONG).show();return}
        if(prefs.getBoolean("vpnWasStarted",false)){Toast.makeText(this,"Отключи VPN перед тестированием серверов",Toast.LENGTH_LONG).show();return}
        Toast.makeText(this,"Проверяю серверы настоящим AmneziaWG handshake. Это может занять до минуты…",Toast.LENGTH_LONG).show()
        val consent = VpnService.prepare(this)
        if (consent != null) { autoSelectAfterPermission = true; startActivityForResult(consent,42); return }
        startAutoSelectService()
    }

    private fun startAutoSelectService() {
        val intent=Intent(this,WireGuardControllerService::class.java).setAction(WireGuardControllerService.ACTION_AUTO_SELECT)
        try { if(android.os.Build.VERSION.SDK_INT>=26) startForegroundService(intent) else startService(intent) }
        catch(e:Exception){Toast.makeText(this,"Не удалось запустить тест: ${e.message}",Toast.LENGTH_LONG).show()}
    }

    private fun askBackupPassword(restore:Boolean) {
        val field=EditText(this).apply{hint="Пароль минимум 8 символов";inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD}
        AlertDialog.Builder(this).setTitle(if(restore)"Пароль резервной копии" else "Защита резервной копии").setMessage("Используй пароль, который сможешь вспомнить. Без него восстановить архив невозможно.").setView(field).setNegativeButton("Отмена",null).setPositiveButton("Продолжить"){_,_->
            val password=field.text.toString().toCharArray(); if(password.size<8){Toast.makeText(this,"Минимум 8 символов",Toast.LENGTH_LONG).show();return@setPositiveButton}
            if(restore){backupPassword=password;val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/octet-stream"};startActivityForResult(i,44)}
            else try{val json=org.json.JSONObject().put("version",1).put("profiles",ProfileStore.exportPlainProfiles(this)).toString();backupBytes=PortableBackup.encrypt(json,password);password.fill('\u0000');val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/octet-stream";putExtra(Intent.EXTRA_TITLE,"VPN-Control-backup.vpcb")};startActivityForResult(i,43)}catch(e:Exception){Toast.makeText(this,"Ошибка: ${e.message}",Toast.LENGTH_LONG).show()}
        }.show()
    }
    private var backupPassword:CharArray?=null
    private var backupBytes:ByteArray?=null

    private fun hasUsageAccess():Boolean {val ops=getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager;return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),packageName)==AppOpsManager.MODE_ALLOWED}
    private fun startMonitor(){if(!hasUsageAccess()){startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));return};val vpnConsent=VpnService.prepare(this);if(vpnConsent!=null){startAutomationAfterPermission=true;startActivityForResult(vpnConsent,42);return};prefs.edit().putBoolean("autoEnabled",true).apply();val i=Intent(this,AppMonitorService::class.java);if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(i)else startService(i);Toast.makeText(this,"Автоматика запущена",Toast.LENGTH_SHORT).show()}
    private fun openConfigPicker(){val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"};startActivityForResult(i,41)}
    @Deprecated("Legacy picker callback") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==42){if(resultCode==RESULT_OK){if(startAutomationAfterPermission){startAutomationAfterPermission=false;startMonitor()}else if(autoSelectAfterPermission){autoSelectAfterPermission=false;startAutoSelectService()}else startWireGuard()}else{startAutomationAfterPermission=false;autoSelectAfterPermission=false;Toast.makeText(this,"Разрешение VPN не выдано",Toast.LENGTH_LONG).show()};return};if(requestCode==41&&resultCode==RESULT_OK){val uri=data?.data?:return;try{contentResolver.openInputStream(uri).use{input->val bytes=requireNotNull(input).readBytes();val raw=bytes.toString(Charsets.UTF_8);require(raw.contains("[Interface]")&&raw.contains("[Peer]")){"Файл не похож на конфигурацию WG/AWG"};val hasAwg=Regex("(?im)^(Jc|Jmin|Jmax|S[1-4]|H[1-4]|I[1-5])\\s*=").containsMatchIn(raw);val name=uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?:if(hasAwg)"AmneziaWG" else "VPN profile";ProfileStore.add(this,name,bytes);Toast.makeText(this,if(hasAwg)"Профиль AmneziaWG с параметрами обфускации сохранён" else "Профиль сохранён",Toast.LENGTH_LONG).show()}}catch(e:Exception){Toast.makeText(this,"Ошибка импорта: ${e.message}",Toast.LENGTH_LONG).show()};render();return};if(requestCode==43&&resultCode==RESULT_OK){val uri=data?.data;try{val bytes=backupBytes?:error("Архив не подготовлен");contentResolver.openOutputStream(uri!!).use{it!!.write(bytes)};Toast.makeText(this,"Зашифрованная копия сохранена",Toast.LENGTH_LONG).show()}catch(e:Exception){Toast.makeText(this,"Ошибка сохранения: ${e.message}",Toast.LENGTH_LONG).show()}finally{backupBytes=null};return};if(requestCode==44&&resultCode==RESULT_OK){val uri=data?.data;try{val bytes=contentResolver.openInputStream(uri!!).use{it!!.readBytes()};val json=PortableBackup.decrypt(bytes,backupPassword?:error("Пароль отсутствует"));val profiles=org.json.JSONObject(json).getJSONArray("profiles");ProfileStore.importPlainProfiles(this,profiles);Toast.makeText(this,"Профили восстановлены: ${profiles.length()}",Toast.LENGTH_LONG).show()}catch(e:Exception){Toast.makeText(this,"Не удалось восстановить: неверный пароль или повреждённый архив",Toast.LENGTH_LONG).show()}finally{backupPassword?.fill('\u0000');backupPassword=null};render();return}}
    private fun applyRoutingChanges() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, 42)
            return
        }
        val connected = WireGuardControllerService.isTunnelUp(this)
        if (!connected) {
            showToast("Настройки сохранены. VPN сейчас выключен.")
            return
        }
        try {
            val intent = Intent(this, WireGuardControllerService::class.java).setAction(WireGuardControllerService.ACTION_RECONNECT)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "Применяю настройки маршрутизации и DNS…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось применить настройки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestVpnStart(){startAutomationAfterPermission=false;if(!File(filesDir,"active.enc").exists()){showToast("Профиль VPNcontrol пока не установлен");return};val i=VpnService.prepare(this);if(i!=null)startActivityForResult(i,42)else startWireGuard()}
    private fun startWireGuard(){try{prefs.edit().putBoolean("vpnConnecting",true).apply();render();val i=Intent(this,WireGuardControllerService::class.java).setAction(WireGuardControllerService.ACTION_UP);if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(i)else startService(i); showToast("Подключаемся к VPNcontrol…")}catch(e:Exception){showToast("Ошибка VPN: ${e.message}")}}
    private fun stopVpn(){try{prefs.edit().putBoolean("vpnConnecting",false).apply();val i=Intent(this,WireGuardControllerService::class.java).setAction(WireGuardControllerService.ACTION_DOWN);if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(i)else startService(i);showToast("Отключаем VPN…")}catch(e:Exception){showToast("Не удалось отключить: ${e.message}")}}
    private fun openPrivateDnsSettings(){try{startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS"))}catch(_:Exception){startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))}}
    private fun networkType():String{val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager;val n=cm.activeNetwork?:return "Нет соединения";val c=cm.getNetworkCapabilities(n)?:return "Неизвестно";return when{c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)->"Wi-Fi";c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)->"Мобильная сеть";c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)->"Ethernet";else->"Другое"}}
    private fun currentIp():String{try{val interfaces=java.net.NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()){val ni=interfaces.nextElement();if(ni.isUp&&!ni.isLoopback){val addrs=ni.inetAddresses;while(addrs.hasMoreElements()){val a=addrs.nextElement();if(!a.isLoopbackAddress&&a is java.net.Inet4Address)return a.hostAddress?:"—"}}}}catch(_:Exception){};return "Недоступен"}
    private fun dnsServers():String{val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager;val n=cm.activeNetwork?:return "—";val lp=cm.getLinkProperties(n)?:return "—";return lp.dnsServers.joinToString(", "){it.hostAddress?:""}.ifBlank{"Не определены"}}
    private fun connectionDuration():String{if(!prefs.getBoolean("vpnWasStarted",false))return "Не подключён";val start=prefs.getLong("connectedAt",0L);if(start<=0)return "Подключение активно";val sec=((System.currentTimeMillis()-start)/1000).coerceAtLeast(0);return String.format("%02d:%02d:%02d",sec/3600,(sec%3600)/60,sec%60)}
    private inner class TrafficGraphView:View(this@MainActivity){private val paint=Paint(Paint.ANTI_ALIAS_FLAG);override fun onDraw(canvas:Canvas){super.onDraw(canvas);val w=width.toFloat();val h=height.toFloat();paint.color=0xFF393342.toInt();paint.strokeWidth=1f;for(i in 1..3)canvas.drawLine(0f,h*i/4,w,h*i/4,paint);if(rxHistory.size<2)return;val max=(rxHistory.maxOrNull()?:1f).coerceAtLeast(1f);paint.color=amber;paint.strokeWidth=dp(2).toFloat();paint.style=Paint.Style.STROKE;val path=android.graphics.Path();rxHistory.forEachIndexed{index,value->val x=w*index/(rxHistory.size-1).coerceAtLeast(1);val y=h-(value/max)*(h-dp(8))-dp(4);if(index==0)path.moveTo(x,y)else path.lineTo(x,y)};canvas.drawPath(path,paint);paint.style=Paint.Style.FILL}}
    private fun bytes(v:Long):String{if(v<0)return "Недоступно";val units=arrayOf("Б","КБ","МБ","ГБ","ТБ");var x=v.toDouble();var i=0;while(x>=1024&&i<units.lastIndex){x/=1024;i++};return "${DecimalFormat("0.0").format(x)} ${units[i]}"}
    private fun speed(v:Double):String="${bytes(v.toLong())}/с"
    private fun runDiagnostics(){Thread{val start=System.currentTimeMillis();val result=try{val ok=InetAddress.getByName("1.1.1.1").isReachable(2500);if(ok)"Узел 1.1.1.1 доступен · ${System.currentTimeMillis()-start} мс" else "Ответ не получен. ICMP может блокироваться сетью."}catch(e:Exception){"Ошибка сети: ${e.javaClass.simpleName}"};runOnUiThread{prefs.edit().putString("lastDiagnostic",result).apply();Toast.makeText(this,result,Toast.LENGTH_LONG).show();if(page=="monitor"){body.removeAllViews();renderMonitor()}}}.start()}
}
