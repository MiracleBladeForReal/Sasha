# Galaxy Sensor Lab 0.3

Laboratorio video Android con elaborazione GPU reale, anteprima e salvataggio MP4. Tutto avviene sul telefono, senza caricamenti in rete.

## Funzioni

- Importazione tramite selettore Android, con permesso persistente sul file.
- Rumore temporale RGB dipendente dal segnale e dal profilo ISO SIDD Galaxy S6 Edge.
- Esposizione, temperatura relativa, saturazione e contrasto.
- Riduzione rumore spaziale adattiva a cinque campioni, nitidezza e vignettatura.
- Compressione JPEG creativa: interruttore e intensità 0–100%, in anteprima e nel file salvato.
- Confronto originale/elaborato con separatore regolabile e bypass originale.
- Preset Pulito, Notte e Cinema; impostazioni salvate automaticamente.
- Esportazione H.264/AAC in MP4, fino a 720p/1080p senza ingrandire la sorgente, con avanzamento, annullamento, apertura e condivisione.
- Nessun permesso di accesso generale alla memoria o alla fotocamera. Sorgenti originali non modificate.

## Installazione dal Samsung

1. Apri [Actions](https://github.com/MiracleBladeForReal/Sasha/actions/workflows/android.yml) e l’ultima build verde.
2. Scarica **GalaxySensorLab-debug-apk** dalla sezione **Artifacts**.
3. Estrai lo ZIP con **Archivio** e apri `app-debug.apk`.
4. Consenti l’installazione da quella fonte, se richiesta.

Android 8.0 o successivo. APK di sviluppo: le chiavi debug possono cambiare fra runner. In caso di firma incompatibile occorre disinstallare la versione precedente, perdendo le preferenze locali, e poi installare la nuova. Per pubblicare e aggiornare stabilmente servirà una chiave di produzione.

## Uso

Importa una clip locale, riproducila, cambia un cursore e attiva **Prima / dopo**. Con **Originale** vedi il bypass; disattivalo per vedere gli effetti. **Azzera** ripristina una pipeline neutra senza rumore aggiunto. **Salva video elaborato** apre il selettore Android per scegliere nome e destinazione. Il confronto non viene impresso nell’esportazione: si esporta sempre il fotogramma interamente elaborato. Tieni l’app aperta durante il rendering. Video HDR vengono convertiti in SDR in esportazione; la resa può differire dall’anteprima HDR. Compatibilità codec e prestazioni dipendono dal telefono; se necessario prova 720p e una sorgente MP4 H.264 SDR.

## Calibrazione e limiti scientifici

Il profilo è **Samsung Galaxy S6 Edge**, non una calibrazione automatica del telefono su cui gira l’app. È basato sulla tabella DNG NLF di [SIDD](https://abdokamel.github.io/sidd/), Abdelhamed, Lin, Brown, CVPR 2018, distribuita con licenza MIT.

Fonte fissata al commit [7759f112](https://github.com/AbdoKamel/abdokamel.github.io/blob/7759f112db725fb68cf89c28432cfd2baea90cb0/sidd/files/noise_level_functions.csv). Il JSON `app/src/main/assets/sidd_s6.json` contiene una riga rappresentativa per ISO 100, 200, 400, 800, 1600 e 3200, identificativo della scena e coefficienti RGB originali. Gli ISO intermedi usano interpolazione in log ISO; fuori intervallo non si estrapola. Per S6 tutti i beta2 pubblicati sono zero: l’app li conserva, senza inventare coefficienti di lettura.

Il modello di varianza è `beta1 * segnale_lineare + beta2`. Lo shader usa campioni gaussiani indipendenti per canale e timestamp, scalati con questa varianza. È un’approssimazione gaussiana del rumore dipendente dal segnale, non un campionatore Poisson esatto. La quantità di rumore regola la deviazione standard. L’esposizione e lo sviluppo seguono il rumore.

**Un video decodificato non è un RAW:** contiene già demosaicizzazione, denoise, sharpening, tone mapping, compressione e rumore della ripresa. I coefficienti RAW sono applicati all’RGB lineare della pipeline Media3 come approssimazione sperimentale, senza ricostruzione Bayer e senza inversione dell’ISP. Non si promette una replica dell’ISP Samsung, né una misurazione del sensore sorgente. Temperatura e preset sono regolazioni creative, non matrici colore calibrate.

## Verifica automatica

La pipeline compila APK e test, esegue Android lint e avvia un emulatore Android 15. I test strumentali verificano i coefficienti pubblicati e producono veri MP4 attraverso codec e shader GL: esposizione e rumore devono cambiare i pixel estratti dal file; bypass e metà originale del confronto devono preservarli. Verificano anche durata e decodifica dei file. Una prova apre anche l’app, riproduce una clip ed esporta dal modello applicativo; acquisisce una schermata per la verifica visiva. I report sono artefatti della build. L’emulatore non sostituisce le prove su un Samsung fisico, specialmente per HEVC/HDR, file lunghi e stabilità termica.

Stack: Kotlin 2.0.21, Compose, Media3 1.5.1, Java 17, Gradle 8.9, AGP 8.7.3, SDK 35. GitHub Actions si avvia a ogni push su main, pull request o tramite Run workflow.


## Compressione JPEG

Attiva **Compressione JPEG** e regola **Intensità compressione**. A 0% o con l'interruttore spento il passaggio è escluso; disattivare l'effetto conserva l'intensità scelta. Azzera e i preset ripristinano l'effetto spento. Le impostazioni JPEG vengono salvate insieme agli altri controlli.

Dopo lo sviluppo, quattro passaggi GPU eseguono DCT separabile su blocchi fissi 8×8, quantizzazione con tabelle luminanza/crominanza JPEG e DCT inversa. Il trattamento avviene in YCbCr con gamma approssimata 2,2. L'intensità riduce la qualità da 100 a 5, con un ingresso graduale nel primo 10% del cursore. Gli artefatti dipendono dal contenuto; non vengono disegnati quadretti casuali. Non sono inclusi trascinamenti fra fotogrammi, datamoshing o sottocampionamento cromatico 4:2:0. È una simulazione delle perdite JPEG; l'esportazione resta MP4 H.264/AAC.

Coefficienti a 16 bit per canale sono impacchettati in texture RGBA8, senza letture CPU dei fotogrammi e senza richiedere framebuffer floating point. I bordi non multipli di 8 vengono estesi replicando l'ultimo pixel. Il confronto originale/elaborato viene applicato alla fine, preservando la metà originale. L'esportazione applica l'effetto all'intero fotogramma prima del ridimensionamento.

L'effetto usa circa 40 MiB di buffer aggiuntivi per una sorgente 1080p e aumenta il lavoro GPU; fluidità, consumi e limiti di risoluzione vanno verificati sul dispositivo. La dimensione dei blocchi segue i pixel della sorgente: ridimensionando in esportazione anche i blocchi si riducono. Una sorgente che supera i limiti delle texture della GPU non può utilizzare questo effetto.

Verifica aggiuntiva degli shader su Linux/Mesa: con Python, NumPy e libEGL installati, esegui `python3 tools/verify_jpeg_shader.py`. La prova esegue gli shader di produzione e confronta i pixel con una DCT di riferimento indipendente, includendo bordi parziali e orientamenti dei buffer.
