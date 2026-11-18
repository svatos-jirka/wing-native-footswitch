# End-to-end testy (emulátor + simulátor pultu)

Automatický test, který spustí aplikaci v Android emulátoru proti simulátoru pultu a v jeho záznamu (JSONL) kontroluje, co aplikace posílá „do pultu“.

## Co test ověřuje

| Test | Ověření |
|---|---|
| `test_1_connects_and_subscribes_meters` | aplikace si vyžádá celý stav pultu a objedná měření 40 vstupů na UDP 1241 |
| `test_2_pedal_right_raises_level` | pravá klapka (ADB broadcast jako ze simulátoru pedálu) zvýší `ch.16.send.1.lvl` o 1 dB; zápis −11 dB má přesně bajty `d7 13 08 21 40 d5 c1 30 00 00` jako ve WING-Q |
| `test_3_noise_gate_mutes_and_reopens` | vstup −50 dBFS (pod prahem −41) kanál po 1,5 s v Auxu vypne, −20 dBFS ho znovu zapne |
| `test_4_clip_protection_lowers_send` | špička 0 dBFS sníží odeslání do Auxu jednou o 3 dB |
| `test_5_channel_rename_reaches_app` | přejmenování kanálu „zvukařem“ se hned objeví v aplikaci |

Test před spuštěním aplikaci nainstaluje, smaže její data a přes `run-as` jí podstrčí nastavení: Aux 1, hlavní nástroj Zpěvy / Zpěv 1 na CH 16, vlastní práh −41 dBFS, zapnuté ztlumení šumu i ochrana proti přebuzení.

## Spuštění

Předpoklady:

- běžící Android emulátor (`adb devices` ukazuje `emulator-…`, případně nastavte `ANDROID_SERIAL`),
- přeložené debug APK: `android\gradlew.bat -p android :app:assembleDebug`,
- volné porty 2222/2223/2229 (žádný jiný simulátor pultu neběží; test si spustí vlastní).

```powershell
cd tests\e2e
py -3.13 -m unittest -v test_emulator_e2e
```

Pokud test hlásí, že přesměrování UDP 1241 selhalo, restartujte emulátor. Po `redir del` ho emulátor někdy drží dál.

## Pomocné skripty

| Skript | K čemu |
|---|---|
| `ui_text.py` | vypíše texty na obrazovce emulátoru se souřadnicemi (místo snímků obrazovky) |
| `feed_levels.py` | pošle simulátoru pultu scénář úrovní, např. `py -3.13 feed_levels.py 16 2:-20 3:-50 2:-20 0.4:-20:0` |
| `rename_channel.py` | přejmenuje kanál na běžícím simulátoru pultu, např. `py -3.13 rename_channel.py 16 "Lead Vox"` |

Při rychle se měnících úrovních (živé měřiče) `uiautomator` hlásí `could not get idle state`, protože se obrazovka pořád překresluje. Pomůže zastavit nebo ustálit zdroj úrovní.

Jednotkové testy simulátoru pultu jsou v `tools/wing-mock` (`py -3.13 -m unittest -v`).
