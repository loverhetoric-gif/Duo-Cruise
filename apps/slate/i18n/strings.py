# Duo Slate の画面の文言(9 言語)。ここが原本。gen.py が DuoSlate/Localizable.xcstrings と InfoPlist.xcstrings を書き出す。
# 並びは Duo Cruise と同じ: en(既定), ja, zh-Hans(簡体), zh-Hant(繁体), ko, de, fr, es, it
# 鍵はコードに書いた日本語そのもの。%@ は差し込み(2 つあるものは各言語で %1$@ %2$@ と順番を明示)。
# 言葉は一般的なアプリの定番の言い回しに合わせる(内部の呼び名・造語を出さない)。
LANGS = ["en", "ja", "zh-Hans", "zh-Hant", "ko", "de", "fr", "es", "it"]

S = {
# 設定
"設定": ["Settings", "設定", "设置", "設定", "설정", "Einstellungen", "Réglages", "Ajustes", "Impostazioni"],
# 大きな見出しに 1 行で収まるよう、長くなる言語はアプリ名を省く
"Duo Slate の設定": ["Duo Slate Settings", "Duo Slate の設定", "Duo Slate 设置", "Duo Slate 設定", "Duo Slate 설정", "Einstellungen", "Réglages", "Ajustes", "Impostazioni"],
"閉じる": ["Close", "閉じる", "关闭", "關閉", "닫기", "Schließen", "Fermer", "Cerrar", "Chiudi"],
"動きの速さ": ["Pointer Speed", "動きの速さ", "指针速度", "指標速度", "포인터 속도", "Zeigergeschwindigkeit", "Vitesse du pointeur", "Velocidad del puntero", "Velocità del puntatore"],
"発光の強さ %@": ["Glow Strength %@", "発光の強さ %@", "发光强度 %@", "發光強度 %@", "발광 강도 %@", "Leuchtstärke %@", "Intensité lumineuse %@", "Intensidad del brillo %@", "Intensità luminosa %@"],
"感度 %@": ["Sensitivity %@", "感度 %@", "灵敏度 %@", "靈敏度 %@", "감도 %@", "Empfindlichkeit %@", "Sensibilité %@", "Sensibilidad %@", "Sensibilità %@"],
"加速 %@(0＝加速なし)": ["Acceleration %@ (0 = none)", "加速 %@(0＝加速なし)", "加速 %@(0 = 不加速)", "加速 %@(0 = 不加速)", "가속 %@(0 = 가속 없음)", "Beschleunigung %@ (0 = keine)", "Accélération %@ (0 = aucune)", "Aceleración %@ (0 = ninguna)", "Accelerazione %@ (0 = nessuna)"],
"画面に合わせて自動で調整": ["Adjust Automatically for Display", "画面に合わせて自動で調整", "根据屏幕自动调整", "依螢幕自動調整", "화면에 맞게 자동 조정", "Automatisch an Bildschirm anpassen", "Ajuster automatiquement à l’écran", "Ajustar automáticamente a la pantalla", "Regola automaticamente per lo schermo"],
"画面の解像度 %lld×%lld に合わせています": ["Matched to display resolution %lld×%lld", "画面の解像度 %lld×%lld に合わせています", "已匹配屏幕分辨率 %lld×%lld", "已配合螢幕解析度 %lld×%lld", "화면 해상도 %lld×%lld에 맞춤", "An Bildschirmauflösung %lld×%lld angepasst", "Adapté à la résolution %lld×%lld", "Ajustado a la resolución %lld×%lld", "Adattato alla risoluzione %lld×%lld"],
"画面の解像度 %lld×%lld と、iPhone の向きに合わせています": ["Matched to display resolution %lld×%lld and iPhone orientation", "画面の解像度 %lld×%lld と、iPhone の向きに合わせています", "已匹配屏幕分辨率 %lld×%lld 和 iPhone 方向", "已配合螢幕解析度 %lld×%lld 與 iPhone 方向", "화면 해상도 %lld×%lld 및 iPhone 방향에 맞춤", "An Bildschirmauflösung %lld×%lld und iPhone-Ausrichtung angepasst", "Adapté à la résolution %lld×%lld et à l’orientation de l’iPhone", "Ajustado a la resolución %lld×%lld y a la orientación del iPhone", "Adattato alla risoluzione %lld×%lld e all’orientamento dell’iPhone"],
"ダークモード": ["Dark Mode", "ダークモード", "深色模式", "深色模式", "다크 모드", "Dunkelmodus", "Mode sombre", "Modo oscuro", "Modalità scura"],
"オン": ["On", "オン", "开", "開啟", "켬", "Ein", "Activé", "Activado", "Attiva"],
"オフ": ["Off", "オフ", "关", "關閉", "끔", "Aus", "Désactivé", "Desactivado", "Disattiva"],
"デバイスの設定": ["System", "デバイスの設定", "跟随系统", "跟隨系統", "시스템 설정", "System", "Système", "Sistema", "Sistema"],
"タッチパッドの配色": ["Touchpad Color", "タッチパッドの配色", "触控板配色", "觸控板配色", "터치패드 색상", "Touchpad-Farbe", "Couleur du pavé tactile", "Color del panel táctil", "Colore del touchpad"],
"ネオンの配色": ["Neon Color", "ネオンの配色", "霓虹配色", "霓虹配色", "네온 색상", "Neonfarbe", "Couleur du néon", "Color del neón", "Colore del neon"],
"ライト": ["Light", "ライト", "浅色", "淺色", "라이트", "Hell", "Clair", "Claro", "Chiaro"],
"ダーク": ["Dark", "ダーク", "深色", "深色", "다크", "Dunkel", "Sombre", "Oscuro", "Scuro"],
"%@の面 %@": ["%1$@ background: %2$@", "%1$@の面 %2$@", "%1$@背景:%2$@", "%1$@背景:%2$@", "%1$@ 배경: %2$@", "Hintergrund %1$@: %2$@", "Fond %1$@ : %2$@", "Fondo %1$@: %2$@", "Sfondo %1$@: %2$@"],
"%@のネオン %@": ["%1$@ neon: %2$@", "%1$@のネオン %2$@", "%1$@霓虹:%2$@", "%1$@霓虹:%2$@", "%1$@ 네온: %2$@", "Neon %1$@: %2$@", "Néon %1$@ : %2$@", "Neón %1$@: %2$@", "Neon %1$@: %2$@"],
"接続先の指定(通常は不要)": ["Manual Connection (Usually Not Needed)", "接続先の指定(通常は不要)", "手动指定连接目标(通常不需要)", "手動指定連線目標(通常不需要)", "연결 대상 지정(보통 필요 없음)", "Verbindungsziel festlegen (meist nicht nötig)", "Destination manuelle (généralement inutile)", "Destino manual (normalmente no es necesario)", "Destinazione manuale (di solito non serve)"],
"空欄のままで大丈夫です(例 192.168.43.1)": ["Leave blank (e.g. 192.168.43.1)", "空欄のままで大丈夫です(例 192.168.43.1)", "留空即可(例:192.168.43.1)", "留空即可(例:192.168.43.1)", "비워 두어도 됩니다(예: 192.168.43.1)", "Kann leer bleiben (z. B. 192.168.43.1)", "Peut rester vide (ex. 192.168.43.1)", "Puede quedar en blanco (p. ej., 192.168.43.1)", "Può restare vuoto (es. 192.168.43.1)"],
"通常は空欄のままにしてください。接続先は自動で見つかります。うまくつながらないときだけ、接続先の IP アドレスを入力します。": [
 "Normally, leave this blank. The device to connect to is found automatically. Enter its IP address only if the connection doesn't work.",
 "通常は空欄のままにしてください。接続先は自動で見つかります。うまくつながらないときだけ、接続先の IP アドレスを入力します。",
 "通常请留空。连接目标会自动找到。只有在无法正常连接时,才输入连接目标的 IP 地址。",
 "通常請留空。連線目標會自動找到。只有在無法順利連線時,才輸入連線目標的 IP 位址。",
 "보통은 비워 두세요. 연결 대상은 자동으로 찾습니다. 연결이 잘 되지 않을 때만 연결 대상의 IP 주소를 입력하세요.",
 "Lassen Sie dieses Feld normalerweise leer. Das Verbindungsziel wird automatisch gefunden. Geben Sie die IP-Adresse nur ein, wenn die Verbindung nicht klappt.",
 "En général, laissez ce champ vide. La destination est trouvée automatiquement. Saisissez son adresse IP uniquement si la connexion échoue.",
 "Normalmente, déjalo en blanco. El destino se encuentra automáticamente. Introduce su dirección IP solo si no se conecta bien.",
 "Di solito lascia vuoto questo campo. La destinazione viene trovata automaticamente. Inserisci il suo indirizzo IP solo se la connessione non riesce."],
"接続": ["Connection", "接続", "连接", "連線", "연결", "Verbindung", "Connexion", "Conexión", "Connessione"],
"再接続": ["Reconnect", "再接続", "重新连接", "重新連線", "다시 연결", "Erneut verbinden", "Reconnecter", "Volver a conectar", "Riconnetti"],
"Duo Slate について": ["About Duo Slate", "Duo Slate について", "关于 Duo Slate", "關於 Duo Slate", "Duo Slate 정보", "Über Duo Slate", "À propos de Duo Slate", "Acerca de Duo Slate", "Informazioni su Duo Slate"],
"バージョン %@": ["Version %@", "バージョン %@", "版本 %@", "版本 %@", "버전 %@", "Version %@", "Version %@", "Versión %@", "Versione %@"],

# 面の色・ネオンの配色(DuoNEON は名前なので訳さない)
"ホワイト": ["White", "ホワイト", "白色", "白色", "화이트", "Weiß", "Blanc", "Blanco", "Bianco"],
"ペーパー": ["Paper", "ペーパー", "纸白", "紙白", "페이퍼", "Papier", "Papier", "Papel", "Carta"],
"ミスト": ["Mist", "ミスト", "雾灰", "霧灰", "미스트", "Nebel", "Brume", "Niebla", "Nebbia"],
"ブラック": ["Black", "ブラック", "黑色", "黑色", "블랙", "Schwarz", "Noir", "Negro", "Nero"],
"スミ": ["Sumi", "スミ", "墨色", "墨色", "스미", "Tusche", "Encre", "Tinta", "Inchiostro"],
"スレート": ["Slate", "スレート", "石板", "石板", "슬레이트", "Schiefer", "Ardoise", "Pizarra", "Ardesia"],
"オーシャン": ["Ocean", "オーシャン", "海洋", "海洋", "오션", "Ozean", "Océan", "Océano", "Oceano"],
"アンバー": ["Amber", "アンバー", "琥珀", "琥珀", "앰버", "Bernstein", "Ambre", "Ámbar", "Ambra"],

# つながりの状態
"未接続": ["Not Connected", "未接続", "未连接", "未連線", "연결 안 됨", "Nicht verbunden", "Non connecté", "Sin conexión", "Non connesso"],
"Duo Cruise を探しています…": ["Searching for Duo Cruise…", "Duo Cruise を探しています…", "正在查找 Duo Cruise…", "正在尋找 Duo Cruise…", "Duo Cruise를 찾는 중…", "Duo Cruise wird gesucht …", "Recherche de Duo Cruise…", "Buscando Duo Cruise…", "Ricerca di Duo Cruise…"],
"%@ につないでいます…": ["Connecting to %@…", "%@ につないでいます…", "正在连接 %@…", "正在連線至 %@…", "%@에 연결 중…", "Verbindung mit %@ wird hergestellt …", "Connexion à %@…", "Conectando con %@…", "Connessione a %@…"],
"%@ に接続中・往復 %@ ms": ["Connected to %1$@ · round trip %2$@ ms", "%1$@ に接続中・往復 %2$@ ms", "已连接到 %1$@ · 往返 %2$@ 毫秒", "已連線至 %1$@・往返 %2$@ 毫秒", "%1$@에 연결됨 · 왕복 %2$@ms", "Mit %1$@ verbunden · Umlaufzeit %2$@ ms", "Connecté à %1$@ · aller-retour %2$@ ms", "Conectado a %1$@ · ida y vuelta %2$@ ms", "Connesso a %1$@ · andata e ritorno %2$@ ms"],
"ローカルネットワークの許可が必要です(iPhone の設定 → Duo Slate → ローカルネットワーク)": [
 "Local network access is required (Settings → Duo Slate → Local Network)",
 "ローカルネットワークの許可が必要です(iPhone の設定 → Duo Slate → ローカルネットワーク)",
 "需要本地网络权限(设置 → Duo Slate → 本地网络)",
 "需要區域網路權限(設定 → Duo Slate → 區域網路)",
 "로컬 네트워크 접근을 허용해야 합니다(설정 → Duo Slate → 로컬 네트워크)",
 "Zugriff auf das lokale Netzwerk erforderlich (Einstellungen → Duo Slate → Lokales Netzwerk)",
 "Accès au réseau local requis (Réglages → Duo Slate → Réseau local)",
 "Se necesita acceso a la red local (Ajustes → Duo Slate → Red local)",
 "È necessario l’accesso alla rete locale (Impostazioni → Duo Slate → Rete locale)"],
"Wi-Fi につながっていません": ["Not connected to Wi-Fi", "Wi-Fi につながっていません", "未连接 Wi-Fi", "未連接 Wi-Fi", "Wi-Fi에 연결되어 있지 않습니다", "Nicht mit dem WLAN verbunden", "Non connecté au Wi-Fi", "Sin conexión Wi-Fi", "Non connesso al Wi-Fi"],
"探せませんでした": ["Search failed", "探せませんでした", "查找失败", "尋找失敗", "찾지 못했습니다", "Suche fehlgeschlagen", "Échec de la recherche", "No se pudo buscar", "Ricerca non riuscita"],
"つながりません: %@": ["Can’t connect: %@", "つながりません: %@", "无法连接:%@", "無法連線:%@", "연결할 수 없습니다: %@", "Verbindung nicht möglich: %@", "Connexion impossible : %@", "No se puede conectar: %@", "Impossibile connettersi: %@"],
"待っています: %@": ["Waiting: %@", "待っています: %@", "正在等待:%@", "正在等待:%@", "대기 중: %@", "Warten: %@", "En attente : %@", "Esperando: %@", "In attesa: %@"],
"切れました": ["Disconnected", "切れました", "已断开连接", "已中斷連線", "연결이 끊어졌습니다", "Verbindung getrennt", "Déconnecté", "Desconectado", "Disconnesso"],
"返事がありません": ["No response", "返事がありません", "无响应", "沒有回應", "응답이 없습니다", "Keine Antwort", "Pas de réponse", "Sin respuesta", "Nessuna risposta"],

# Mac
"Esc で解除": ["Press Esc to release", "Esc で解除", "按 Esc 释放", "按 Esc 釋放", "Esc 키로 해제", "Mit Esc freigeben", "Échap pour libérer", "Pulsa Esc para soltar", "Premi Esc per rilasciare"],
"クリックで操作開始": ["Click to start controlling", "クリックで操作開始", "点按开始操作", "按一下開始操作", "클릭하여 조작 시작", "Zum Steuern klicken", "Cliquez pour commencer", "Haz clic para empezar", "Fai clic per iniziare"],
"コマンダー（開発用）": ["Commander (Development)", "コマンダー（開発用）", "旋钮(开发用)", "旋鈕(開發用)", "커맨더(개발용)", "Commander (Entwicklung)", "Commander (développement)", "Commander (desarrollo)", "Commander (sviluppo)"],
"ナビ": ["Navigation", "ナビ", "导航", "導航", "내비", "Navi", "Navigation", "Navegación", "Navigatore"],
"ミュージック": ["Music", "ミュージック", "音乐", "音樂", "음악", "Musik", "Musique", "Música", "Musica"],
"お気に入り": ["Favorites", "お気に入り", "收藏", "喜好項目", "즐겨찾기", "Favoriten", "Favoris", "Favoritos", "Preferiti"],
"戻る": ["Back", "戻る", "返回", "返回", "뒤로", "Zurück", "Retour", "Atrás", "Indietro"],
"ホーム": ["Home", "ホーム", "主页", "主畫面", "홈", "Home", "Accueil", "Inicio", "Home"],
"メニュー": ["Menu", "メニュー", "菜单", "選單", "메뉴", "Menü", "Menu", "Menú", "Menu"],
"回す=項目移動 / 押す=決定 / 倒す=カーソル": ["Turn = move · Press = select · Tilt = cursor", "回す=項目移動 / 押す=決定 / 倒す=カーソル", "旋转=移动项目 / 按下=确定 / 推动=光标", "旋轉=移動項目 / 按下=確定 / 推動=游標", "돌리기=항목 이동 / 누르기=선택 / 기울이기=커서", "Drehen = Eintrag wechseln · Drücken = Auswählen · Kippen = Cursor", "Tourner = déplacer · Appuyer = valider · Incliner = curseur", "Girar = mover · Pulsar = elegir · Inclinar = cursor", "Ruotare = spostare · Premere = selezionare · Inclinare = cursore"],
}

# Info.plist(権限の説明)
INFO = {
"NSLocalNetworkUsageDescription": [
 "Used to find the device to connect to on the same Wi-Fi and control it as a touchpad.",
 "同じ Wi-Fi にある接続先を見つけて、タッチパッドとして操作するために使います。",
 "用于在同一 Wi-Fi 中找到连接目标,并作为触控板进行操作。",
 "用於在同一 Wi-Fi 中找到連線目標,並作為觸控板進行操作。",
 "같은 Wi-Fi에 있는 연결 대상을 찾아 터치패드로 조작하는 데 사용합니다.",
 "Wird verwendet, um das Verbindungsziel im selben WLAN zu finden und es als Touchpad zu bedienen.",
 "Sert à trouver la destination sur le même réseau Wi-Fi et à la commander comme un pavé tactile.",
 "Se usa para encontrar el destino en la misma red Wi-Fi y controlarlo como un panel táctil.",
 "Serve a trovare la destinazione sulla stessa rete Wi-Fi e a controllarla come un touchpad."],
}
