# Galaxy Sensor Lab Complete Lab 0.8

Nuova app cumulativa sul ramo `complete-lab-v0.8`, basata su Median Images 0.7 con funzioni recuperate dai rami JPEG 0.3, Grain 0.3 ed Edge Capture 0.4. **I rami e le app precedenti restano intatti.** Identificativo separato `com.galaxysensorlab.completelab`, nome **Galaxy Sensor Lab Complete Lab**: si installa accanto alle altre versioni e usa preferenze proprie.

## Funzioni riunite

- Video SDR e immagini JPG/JPEG, PNG, WebP e altri formati statici decodificabili da Android. Orientamento EXIF rispettato; foto convertite in sRGB, trasparenza conservata. Limite foto 24 megapixel e dimensioni ammesse dalla GPU, senza ridimensionamento automatico. Formati animati: un fotogramma.
- 439 profili fotocamera: S6 Edge SIDD e 438 modelli darktable, con ricerca e ISO pubblicati. **ISO intermedi** abilita anche l'interpolazione nel range del profilo: log ISO per SIDD, lineare per darktable.
- Quattro percorsi di rumore: **Sensore (0.7)**, **Classico (JPEG 0.3)**, **Grana naturale (Grain 0.3)** e **Simulazione di ripresa (Edge Capture)**. Quantità del rumore, dimensione, luminanza/colore, morbidezza, rinnovo, rumore di lettura aggiunto, ombre, protezione alte luci e coerenza temporale.
- **Nuova ripresa** cambia il seme del rumore; il seme viene conservato fra anteprima ed esportazione. Le foto usano timestamp zero, quindi non cambiano grana a ogni aggiornamento del controllo.
- Esposizione, temperatura, saturazione, contrasto, denoise adattivo, nitidezza e vignettatura recuperati dagli shader precedenti.
- Preset **Pulito**, **Notte**, **Cinema** e **Azzera tutti gli effetti**. Reset e preset preservano profilo e semi; spengono JPEG, Median e pixel difettosi. Il reset porta anche il rumore a zero. Le impostazioni vengono salvate automaticamente.
- **Compressione JPEG** con interruttore e intensità 0–100%: quattro passaggi GPU con DCT separabile 8 × 8, tabelle JPEG luminanza/crominanza, quantizzazione e DCT inversa della variante JPEG originale. È un effetto di compressione sul contenuto, non un semplice disegno di blocchi. Non include datamoshing o sottocampionamento 4:2:0.
- **Median** spento, raggio 1 px (3 × 3) o 2 px (5 × 5), mediana per canale dell'intero vicinato.
- **Pixel bruciati** bianchi, neri, RGB o misti: quantità 1–200, dimensione 1–6 px, intensità e disposizione. Mappa fissa nel tempo, indipendente dal seme del rumore.
- Bypass **Originale**, **Prima / dopo** e separatore regolabile. Questi controlli sono solo di anteprima: il file salvato contiene l'intero fotogramma elaborato.
- Video MP4 H.264/AAC a risoluzione **Originale**, **720p** o **1080p**, senza ingrandire la sorgente; avanzamento, annullamento, apertura e condivisione. Bitrate richiesto 16 Mbps fino a 1080p, 48 Mbps oltre. Nessun cambio intenzionale della frequenza.
- Foto salvate in **PNG**, alla risoluzione originale, con apertura e condivisione. Anche con effetto JPEG attivo, il contenitore finale resta PNG: gli artefatti JPEG sono impressi nei pixel.

## Ordine degli effetti e compatibilità

Median → rumore/grana → denoise/nitidezza → esposizione/temperatura/saturazione/contrasto/vignettatura → pixel bruciati → compressione JPEG. Il JPEG può attenuare i piccoli pixel difettosi, come avviene comprimendo un'immagine. A 0% o con interruttore spento il passaggio JPEG è escluso.

I controlli sono riuniti in una nuova pipeline: non viene promessa identità pixel per pixel con tutti i vecchi APK. Il percorso Sensore a impostazioni creative neutre conserva il modello della 0.7. Gli altri percorsi sono ripresi dalle implementazioni creative precedenti. I valori predefiniti scelgono Sensore, quantità 1, sviluppo neutro e JPEG/Median/pixel difettosi spenti.

Le sorgenti originali non vengono sovrascritte. Nessun permesso generale alla memoria o alla fotocamera, nessun caricamento in rete. L'immagine selezionata va reimportata alla riapertura dell'app; il video selezionato e le impostazioni vengono conservati. Tieni l'app aperta durante il salvataggio.

Il raggio Median 2, le modalità creative e la DCT JPEG aumentano il carico GPU. La variante JPEG usa buffer aggiuntivi (circa 40 MiB a 1080p); foto grandi possono richiedere molta memoria. Superamento dei limiti GPU o memoria viene segnalato. La pipeline video di questa edizione accetta SDR: HDR dichiarato viene rifiutato, senza conversione automatica. Non è un lettore RAW/DNG.

## Installazione

Apri [Actions del nuovo ramo](https://github.com/MiracleBladeForReal/Sasha/actions?query=branch%3Acomplete-lab-v0.8), scegli la build verde **Build Galaxy Sensor Lab Complete Lab APK** e scarica **GalaxySensorLab-Complete-Lab-debug-apk**. Estrai lo ZIP e apri `app-debug.apk` sul telefono. Non disinstallare le vecchie app.

Android 8 o successivo. APK di sviluppo: per aggiornamenti futuri con firma stabile servirà una chiave di produzione. Una firma debug incompatibile può richiedere di reinstallare soltanto Complete Lab, perdendo le preferenze di questa app.

## Modelli, dati e licenze

Il percorso Sensore usa `max(a × segnale + b, 0)` per canale in RGB lineare: Poisson ai bassi conteggi, approssimazione gaussiana da lambda 12. Le intercette darktable negative restano nel calcolo della varianza totale. S6 Edge conserva i sei beta2 uguali a zero. Il moltiplicatore Quantità e tutti i controlli aggiunti sono creativi, non nuove misure del sensore.

Grana naturale usa campi gaussiani correlati spazialmente. Simulazione di ripresa aggiunge rumore fotonico e di lettura, miscela luminanza/crominanza, curve ombre/luci e correlazione temporale MA(1); l'offset di lettura aggiunto è creativo e non misurato. Non vengono fusi fotogrammi sorgente.

Un MP4 contiene già sviluppo, denoise, nitidezza e compressione. Applicare coefficienti RAW al suo RGB è un'approssimazione: non ricostruisce Bayer/RAW né inverte l'ISP proprietario Samsung. Pixel difettosi, sviluppo colore e JPEG sono simulazioni creative. Non si dichiara una replica completa del telefono o una calibrazione di un video AI.

SIDD: Abdelhamed, Lin, Brown, CVPR 2018, [tabella al commit fissato](https://github.com/AbdoKamel/abdokamel.github.io/blob/7759f112db725fb68cf89c28432cfd2baea90cb0/sidd/files/noise_level_functions.csv), MIT. Sei righe rappresentative ISO 100–3200 in `sidd_s6.json`, coefficienti e scene conservati.

[darktable](https://github.com/darktable-org/darktable/blob/879067695ea85f42d238a5c1603a021d14d71d3f/data/noiseprofiles.json), GPL-3.0-or-later, blob `086a62784df9e660a9cdcfc96d15c5302522502e`, completo e inalterato. ISO duplicati: prima misura pubblicata. Edizione GPL-3.0-or-later; testi e attribuzioni nell'APK, AndroidX/Media3 Apache 2.0, collegamento al commit esatto della build nel dialogo licenze.

I riferimenti video GSMArena restano disponibili nel dialogo dati: sono video compressi del telefono, non misure RAW o una calibrazione a ISO noto.

## Verifica

Compilazione APK, Android lint e test Android 15 con codec/GL reali: coefficienti, profili/ISO, controlli creativi e persistenza, effetti su MP4 e foto, JPEG e bordi parziali, bypass/confronto, Median, pixel difettosi, importazione/esportazione delle immagini, colori/trasparenza e ripristino del video. Report e schermate sono artefatti della build. L'emulatore non sostituisce le prove di fluidità o fedeltà su un telefono fisico.

La DCT JPEG può essere verificata anche su Linux/Mesa con NumPy e libEGL: `MESA_SHADER_CACHE_DISABLE=true python tools/verify_jpeg_shader.py`. La prova esegue gli shader di produzione e li confronta con un riferimento matematico indipendente, compresi blocchi parziali e due orientamenti dei buffer.
