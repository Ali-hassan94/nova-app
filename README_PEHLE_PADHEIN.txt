NOVA ANDROID APP (jarvis-mobile)  -  bina PC, bina Tailscale, bilkul free
=========================================================================

KYA HAI
- Native Android (Kotlin) app. Aap ke Nova ki tarah: "Call karo" dabayein, phir bolte jayein.
- AI: Groq (free) seedha phone se. Awaaz: Android ka apna speech + TTS (free).
- Background me chalta hai (notification dikhegi). Rokne ke liye "Band karo" ya bolein "stop nova".

BUILD KARNE KE STEPS
1. Is zip ke andar ka sab kuch apne D:\AiAgent\jarvis-mobile\ me copy karein
   (khali files replace ho jayengi. .git folder ko mat chhedein).
2. Android Studio kholein: File > Open > D:\AiAgent\jarvis-mobile
   Pehli baar "Gradle sync" me 5-10 minute lagenge (internet chahiye). Agar
   "Trust project" ya "Update Gradle/AGP" poochhe to Trust karein; update na karein.
3. Phone me: Settings > About phone > Build number 7 baar dabayein >
   Developer options > USB debugging ON. USB se laga kar Run (hara tikon) dabayein.
   Ya: Build > Build APK(s), phir app-debug.apk phone me copy karke install karein.

PEHLI BAAR APP ME
1. console.groq.com se free API key banayein (gsk_...), app me paste karke "Key save".
2. "Accessibility on karein" > Installed apps > Nova > ON.
   (Agar ON na ho aur grey ho: App info > teen nuqton wala menu > "Allow restricted settings".)
3. "Display over other apps on karein" > Nova ON. (Background me apps kholne ke liye.)
4. Battery: Settings > Apps > Nova > Battery > Unrestricted.
5. "Call karo" dabayein, permissions Allow karein. App background me chali jayegi.

MISALEIN
"WhatsApp kholo" / "torch on karo" / "subah 6 baje ka alarm lagao" / "Ahmed ko call karo"
"YouTube par lofi songs chalao" / "WiFi settings kholo" / "awaaz barhao"
"WhatsApp kholo, Ahmed ko likho salam" (screen parh kar click/type karta hai)

HADOOD
- Hamesha "Hey Nova" wala wake word nahi; sunna "Call karo" se shuru hota hai.
- Kuch secure screens (banking, password) Accessibility block karti hain.
- Message bhejna tab hi hoga jab aap saaf kahein "bhej do".
- Speech recognition ke liye phone me Google app / Speech Services chahiye.
- Ye code yahan test nahi ho saka (yahan Android SDK nahi). Agar Gradle ya build error aaye,
  error ka screenshot bhej dein, main theek kar dunga.

ONLINE BUILD (Android Studio ke baghair) - GitHub Actions, free
1. Is zip ka sab kuch (chhupa hua .github folder samet) D:\AiAgent\jarvis-mobile me copy karein.
2. PowerShell me:  cd D:\AiAgent\jarvis-mobile
                   git add .
                   git commit -m "Nova app"
                   git push
3. github.com/Ali-hassan94/jarvis-mobile > Actions tab > "Build Nova APK" (5-8 minute).
4. Hara tick aane par: repo ke Releases (right side) > "Nova (latest APK)" > Nova.apk download.


NAYA (v2): mic ki beep khatam, key sab users ke liye pehle se app me
- Awaaz ab seedha record hoti hai aur Groq Whisper se text banti hai (beep/on-off awaaz nahi aati).
- Groq key GitHub Secret se APK me daali jati hai: repo > Settings > Secrets and variables > Actions >
  New repository secret > Name: GROQ_API_KEY  Value: aap ki gsk_ key. Phir Actions > Build Nova APK > Run workflow.
- Dhyan: APK ke andar key nikali ja sakti hai. APK sirf apne bharosemand logon ko bhejein, aur repo ko Private rakhein.


V3 (ALIXORA AI): naye features
- App ka naam/icon: ALIXORA AI (aap ki image). Awaaz me "Nova" ya "Alixora" dono chalta hai.
- "YouTube par <gaana> chalao" -> search karke pehli video khud chalata hai.
- "TikTok kholo aur har 8 second baad scroll karo" -> auto scroll. Rokne ke liye "Alixora, scroll roko".
- Messages: "Ahmed ko WhatsApp par likho ..." (bhejna tabhi jab aap kahein "bhej do"), SMS padhna,
  naye notifications/messages batana (3) Messages access on karna zaroori).
- Personal baat-cheet, real-life advice, yaaddasht (memory_save), battery, screenshot, lock waghera.
- Music/video chalte waqt Alixora sirf tab jawab deta hai jab aap uska naam lein (taake gaane ki awaaz par na chale).
