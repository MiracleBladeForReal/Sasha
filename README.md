# Galaxy Sensor Lab Grain 0.3

Versione indipendente derivata da Galaxy Sensor Lab 0.2, conservata intatta su `main` al commit `206e1d0896eeccda3372424d76dfc341b6e043d9`. Sviluppo sul ramo `natural-grain-v0.3`.

Identificativo Android `com.galaxysensorlab.grain`, nome **Galaxy Sensor Lab Grain**: si installa accanto alla vecchia app e usa preferenze separate. Non disinstallare la 0.2.

Laboratorio video Android con elaborazione GPU reale, anteprima e salvataggio MP4. Tutto avviene sul telefono, senza caricamenti in rete.

## Funzioni

- Importazione tramite selettore Android, con permesso persistente sul file.
- Profilo SIDD Galaxy S6 Edge e coefficienti ISO originali conservati senza modifiche.
- Interruttore **Grana naturale**, con dimensione 0.5–12 px riferiti a 1080p, intensità luminanza e colore separate, morbidezza e frequenza di rinnovo 1–60 disegni/secondo.
- Modalità classica SIDD disponibile spegnendo Grana naturale.
- La grana naturale usa campi gaussiani spazialmente correlati; normalizza i pesi di interpolazione per contenere variazioni di ampiezza dovute alla dimensione. La crominanza ha una scala leggermente maggiore e la sua componente di luminanza viene sottratta.
- Il disegno dipende dal timestamp del video, quindi la sua velocità non cambia con il tempo di rendering. Non si tratta di riduzione del rumore temporale: nessun fotogramma sorgente viene fuso con gli altri.
- Esposizione, temperatura relativa, saturazione e contrasto.
- Riduzione rumore spaziale adattiva a cinque campioni, nitidezza e vignettatura.
- Confronto originale/elaborato con separatore regolabile e bypass originale.
- Preset Pulito, Notte e Cinema; impostazioni salvate automaticamente.
- Esportazione H.264/AAC in MP4, fino a 720p/1080p senza ingrandire la sorgente, con avanzamento, annullamento, apertura e condivisione.
- Nessun permesso di accesso generale alla memoria o alla fotocamera. Sorgenti originali non modificate.

## Installazione dal Samsung

1. Apri [Actions](https://github.com/MiracleBladeForReal/Sasha/actions/workflows/android.yml) e l’ultima build verde del ramo **natural-grain-v0.3**.
2. Scarica **GalaxySensorLab-Grain-debug-apk** dalla sezione **Artifacts**.
3. Estrai lo ZIP con **Archivio** e apri `app-debug.apk`.
4. Consenti l’installazione da quella fonte, se richiesta.

Android 8.0 o successivo. APK di sviluppo: le chiavi debug possono cambiare fra runner. In caso di firma incompatibile occorre disinstallare la versione precedente, perdendo le preferenze locali, e poi installare la nuova. Per pubblicare e aggiornare stabilmente servirà una chiave di produzione.

## Uso

Importa una clip locale, riproducila, cambia un cursore e attiva **Prima / dopo**. Con **Originale** vedi il bypass; disattivalo per vedere gli effetti. **Azzera** ripristina una pipeline neutra senza rumore aggiunto. **Salva video elaborato** apre il selettore Android per scegliere nome e destinazione. Il confronto non viene impresso nell’esportazione: si esporta sempre il fotogramma interamente elaborato. Tieni l’app aperta durante il rendering. Video HDR vengono convertiti in SDR in esportazione; la resa può differire dall’anteprima HDR. Compatibilità codec e prestazioni dipendono dal telefono; se necessario prova 720p e una sorgente MP4 H.264 SDR.

## Calibrazione e limiti scientifici

Il profilo è **Samsung Galaxy S6 Edge**, non una calibrazione automatica del telefono su cui gira l’app. È basato sulla tabella DNG NLF di [SIDD](https://abdokamel.github.io/sidd/), Abdelhamed, Lin, Brown, CVPR 2018, distribuita con licenza MIT.

Fonte fissata al commit [7759f112](https://github.com/AbdoKamel/abdokamel.github.io/blob/7759f112db725fb68cf89c28432cfd2baea90cb0/sidd/files/noise_level_functions.csv). Il JSON `app/src/main/assets/sidd_s6.json` contiene una riga rappresentativa per ISO 100, 200, 400, 800, 1600 e 3200, identificativo della scena e coefficienti RGB originali. Gli ISO intermedi usano interpolazione in log ISO; fuori intervallo non si estrapola. Per S6 tutti i beta2 pubblicati sono zero: l’app li conserva, senza inventare coefficienti di lettura.

Il modello di varianza è `beta1 * segnale_lineare + beta2`. Lo shader usa campioni gaussiani indipendenti per canale e timestamp, scalati con questa varianza. È un’approssimazione gaussiana del rumore dipendente dal segnale, non un campionatore Poisson esatto. La quantità di rumore regola la deviazione standard nella modalità classica. La modalità naturale conserva questi coefficienti come scala di partenza, ma cambia correlazioni spaziali e miscela luma/croma tramite controlli creativi: non è un nuovo profilo calibrato del sensore. La dimensione viene adattata all’altezza sorgente, con limite minimo di un pixel sorgente. A risoluzioni basse più valori fini possono quindi produrre lo stesso risultato.

L’esportazione richiede 8 Mbps fino a 720p e 16 Mbps oltre 720p per preservare meglio la grana; il codec può adattare il valore ai limiti del dispositivo. File più grandi richiedono più spazio temporaneo sul telefono. L’esposizione e lo sviluppo seguono il rumore.

**Un video decodificato non è un RAW:** contiene già demosaicizzazione, denoise, sharpening, tone mapping, compressione e rumore della ripresa. I coefficienti RAW sono applicati all’RGB lineare della pipeline Media3 come approssimazione sperimentale, senza ricostruzione Bayer e senza inversione dell’ISP. Non si promette una replica dell’ISP Samsung, né una misurazione del sensore sorgente. Temperatura e preset sono regolazioni creative, non matrici colore calibrate.

## Verifica automatica

La pipeline compila APK e test, esegue Android lint e avvia un emulatore Android 15. I test strumentali verificano i coefficienti pubblicati e producono veri MP4 attraverso codec e shader GL: esposizione e rumore devono cambiare i pixel estratti dal file; bypass e metà originale del confronto devono preservarli. Verificano anche durata e decodifica dei file. Una prova apre anche l’app, riproduce una clip ed esporta dal modello applicativo; acquisisce una schermata per la verifica visiva. Sono aggiunte prove su dimensione e componente colore della grana, rinnovo nel tempo e ripristino della modalità classica. I report sono artefatti della build. L’emulatore non sostituisce le prove su un Samsung fisico, specialmente per HEVC/HDR, file lunghi e stabilità termica.

Stack: Kotlin 2.0.21, Compose, Media3 1.5.1, Java 17, Gradle 8.9, AGP 8.7.3, SDK 35. GitHub Actions si avvia a ogni push su natural-grain-v0.3, pull request o tramite Run workflow.
